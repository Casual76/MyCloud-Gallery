package dev.pampa.mycloudgallery.core.index.ai

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.p3.FaultPlan
import dev.pampa.mycloudgallery.core.index.p3.FaultyFileSystem
import dev.pampa.mycloudgallery.core.index.p3.ProcessDeath
import dev.pampa.mycloudgallery.core.index.p3.TestFile
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Il BANCO dei dati AI (riga P2 del §4.4, "200 giri del banco", come i banchi di core-index della Fase 4). Una famiglia con un
 * preparatore, tre indicizzatori (uno di un altro account, che non lavora mai nell'area privata, D14) e due share (comune e privata).
 * A ogni giro uno a caso fa un passo:
 * - prepara file nuovi (record `meta`: l'attestazione, D7) o ne toglie uno (lapide e catalogo, E-I21);
 * - un indicizzatore calcola e mette in fila vettori e testi (ricetta 1, a volte 2), e pubblica (fine unita' o periodica);
 * - un dispositivo sincronizza le teste `index` e poi `ai` (aggancio del §1.4);
 * - l'area privata diventa in sola lettura o torna scrivibile per un indicizzatore (blocco permanente, D8: voci in attesa e
 *   ripubblicazione senza ricalcolo);
 * - guasti: errore del NAS a una chiamata a caso, morte del processo durante la pubblicazione o la sincronizzazione (si riparte con
 *   gli stessi database), revoca di un indicizzatore (E-I15).
 *
 * **Invarianti** a ogni giro: nell'indice locale di ogni dispositivo solo voci di file della share e gia' preparati (attestazione, M3).
 * Alla fine (blocchi tolti, voci in attesa rilasciate, due giri di sincronizzazione): tutti i dispositivi hanno gli **stessi**
 * vincitori, uguali a quelli che un oracolo calcola leggendo il NAS da zero (D7: ricetta piu' alta, poi id minore); nessuna voce persa
 * (ogni ultima voce messa in fila per un file ancora presente e' pubblicata); nell'indice `ai` di ogni share solo chiavi di file di
 * quella share (M3).
 */
class AiBenchTest {

  @get:Rule val tmp = TemporaryFolder()

  private val common = "test-comune"
  private val private = "test"
  private val shares = listOf(common, private)

  private class Indexer(val device: AiDevice, val shares: List<String>) {
    var publisher = device.publisher()
    var revoked = false

    /** L'ultima voce messa in fila per (share, chiave, classe): la sua impronta. */
    val last = HashMap<Pair<String, AiKey>, String>()
  }

  private fun run(seed: Int, rounds: Int = 200) = runTest(timeout = Duration.parse("5m")) {
    val random = Random(seed)
    val ai = AiWorld(tmp.newFolder())
    val files = shares.associateWith { share -> (1..24).map { ai.file(share, "Foto/IMG_${share}_$it.jpg") } }
    val preparer = ai.device("Tablet")
    val indexers = listOf(
      Indexer(ai.device("Telefono"), shares),
      Indexer(ai.device("Telefono 2"), shares),
      Indexer(ai.device("Telefono familiare", account = "test2"), listOf(common)),
    )
    val everyone = listOf(preparer) + indexers.map { it.device }
    val prepared = shares.associateWith { HashSet<ContentKey>() }
    val removed = shares.associateWith { HashSet<ContentKey>() }
    var deaths = 0
    var readOnly = 0

    suspend fun publishWithFaults(x: Indexer, share: String, reason: FlushReason) {
      val plan = FaultPlan()
      when (random.nextInt(10)) {
        0 -> plan.dieAt = 1 + random.nextInt(8)
        1 -> {
          plan.errorAt = 1 + random.nextInt(8)
          plan.error = NasError.Timeout
        }
      }
      val publisher = x.device.publisher(plan)
      try {
        publisher.flush(share, reason)
      } catch (death: ProcessDeath) {
        deaths++
      }
      // Un processo nuovo: stesso database, pubblicatore nuovo.
      x.publisher = x.device.publisher()
      x.publisher.recover()
    }

    suspend fun syncWithFaults(device: AiDevice) {
      val plan = FaultPlan()
      if (random.nextInt(8) == 0) plan.dieAt = 1 + random.nextInt(30)
      if (random.nextInt(8) == 0) {
        plan.errorAt = 1 + random.nextInt(30)
        plan.error = NasError.Timeout
      }
      val fs = { s: String -> FaultyFileSystem(ai.world.nas.share(s), plan) }
      val remote = device.remote(fs)
      try {
        device.engine(remote, device.shareSync(remote, device.aiRemote(fs))).sync(SyncScope(shares.toSet(), setOf(SegmentClass.THUMB_S)), Duration.INFINITE)
      } catch (death: ProcessDeath) {
        deaths++
      }
    }

    repeat(rounds) { round ->
      ai.clock.advance(random.nextLong(10_000, 120_000))
      when (random.nextInt(100)) {
        in 0..14 -> {
          // Il preparatore pubblica i record di alcuni file nuovi.
          val share = shares.random(random)
          val todo = files.getValue(share).filter { it.contentKey !in prepared.getValue(share) && it.contentKey !in removed.getValue(share) }.shuffled(random).take(1 + random.nextInt(5))
          if (todo.isNotEmpty()) {
            preparer.prepare(todo, share)
            todo.forEach { prepared.getValue(share) += it.contentKey }
          }
        }
        in 15..17 -> {
          // Un file sparisce dal NAS: lapide del preparatore, catalogo aggiornato (M3 non vale piu').
          val share = shares.random(random)
          val victim = files.getValue(share).firstOrNull { it.contentKey in prepared.getValue(share) && it.contentKey !in removed.getValue(share) && random.nextInt(4) == 0 }
          if (victim != null) {
            val publisher = preparer.indexPublisher()
            publisher.stage(share, listOf(MetaChange.Remove(victim.path)), emptyList())
            publisher.flush(share, FlushReason.UNIT_FINISHED)
            ai.content.remove(share, victim.contentKey)
            removed.getValue(share) += victim.contentKey
          }
        }
        in 18..54 -> {
          // Un indicizzatore calcola qualcosa e lo mette in fila, poi pubblica.
          val x = indexers.filterNot { it.revoked }.random(random)
          val share = x.shares.random(random)
          val batch = files.getValue(share).shuffled(random).take(1 + random.nextInt(4))
          val entries = batch.flatMap { f ->
            val recipe = if (random.nextInt(10) == 0) 2 else 1
            val vec = x.device.vector(f, recipe = recipe, seed = f.path.hashCode() * 31 + x.device.id.hashCode() + recipe)
            if (random.nextBoolean()) listOf(vec, x.device.text(f, "testo ${random.nextInt(3)}", recipe)) else listOf(vec)
          }
          x.publisher.stage(share, entries)
          for (e in entries) if (e.contentKey !in removed.getValue(share)) x.last[share to e.key] = e.digest()
          publishWithFaults(x, share, if (random.nextBoolean()) FlushReason.UNIT_FINISHED else FlushReason.PERIODIC)
        }
        in 55..84 -> syncWithFaults(everyone.random(random))
        in 85..92 -> {
          // L'area privata diventa in sola lettura (o torna scrivibile) per un indicizzatore: blocco permanente (D8).
          val x = indexers.filterNot { it.revoked }.filter { private in it.shares }.randomOrNull(random)
          if (x != null) {
            val blocked = x.device.gate.blocks.containsKey(private)
            x.device.gate.block(private, if (blocked) null else WriteBlock.READ_ONLY_AREA)
            readOnly++
            if (blocked) x.publisher.releasePending(private)
          }
        }
        in 93..98 -> {
          // Un errore del NAS a meta' di una pubblicazione (guasto temporaneo).
          val x = indexers.filterNot { it.revoked }.random(random)
          publishWithFaults(x, x.shares.random(random), FlushReason.UNIT_FINISHED)
        }
        else -> {
          // Revoca (una sola, E-I15): i suoi dati escono da tutte le repliche.
          val x = indexers.firstOrNull { !it.revoked && random.nextInt(3) == 0 }
          if (x != null && indexers.count { it.revoked } == 0 && round > rounds / 3) {
            ai.world.trust.changeStatus(x.device.base.signer, DeviceStatus.REVOKED)
            x.revoked = true
          }
        }
      }
      // Invariante di ogni giro: nell'indice locale solo voci di file della share, gia' preparati.
      for (device in everyone) {
        for (winner in device.sink.applied) {
          val share = winner.share.lowercase()
          val label = "seme $seed, giro $round"
          assertWithMessage("$label: voce di un file di un'altra share").that(files.getValue(share).map { it.contentKey }).contains(winner.entry.contentKey)
          assertWithMessage("$label: voce senza record").that(prepared.getValue(share)).contains(winner.entry.contentKey)
        }
      }
    }

    // --- Fine: blocchi tolti, voci in attesa rilasciate, tutto pubblicato, due giri di sincronizzazione ----------------------------
    val alive = indexers.filterNot { it.revoked }
    for (x in alive) {
      x.device.gate.blocks.clear()
      x.publisher = x.device.publisher()
      x.publisher.recover()
      for (share in x.shares) {
        x.publisher.releasePending(share)
        var guard = 0
        while (x.publisher.flush(share, FlushReason.UNIT_FINISHED) != PublishOutcome.NothingPending) check(++guard < 10)
      }
      assertWithMessage("seme $seed: voci in attesa").that(x.device.publishStore.pending()).isEqualTo(AiPendingCounts.EMPTY)
    }
    val readers = listOf(preparer) + alive.map { it.device }
    repeat(2) { readers.forEach { it.sync(*shares.toTypedArray()) } }

    val oracle = oracle(ai, shares, readers.map { it.id } - indexers.filter { it.revoked }.map { it.device.id }.toSet(), files, removed, prepared)
    for (device in readers) assertWithMessage("seme $seed: vincitori di un dispositivo").that(device.winners()).isEqualTo(oracle)
    for (x in alive) {
      for (share in x.shares) {
        val published = aiReaderState(ai, share, x.device.id)
        for ((at, digest) in x.last) {
          if (at.first != share || at.second.contentKey in removed.getValue(share)) continue
          assertWithMessage("seme $seed: voce persa").that(published[at.second]).contains(digest)
        }
      }
    }
    // M3 sul NAS: nell'indice `ai` di ogni share solo chiavi di file di quella share.
    for (share in shares) {
      val allowed = files.getValue(share).map { it.contentKey }.toSet()
      for (x in indexers) {
        val keys = aiReaderState(ai, share, x.device.id).keys.mapTo(HashSet()) { it.contentKey }
        assertWithMessage("seme $seed: M3 in $share").that(allowed).containsAtLeastElementsIn(keys)
      }
    }
    println("P2 banco: seme $seed, $rounds giri, ${oracle.size} vincitori, $deaths morti del processo, ${indexers.count { it.revoked }} revoche, $readOnly cambi di sola lettura")
  }

  /**
   * L'oracolo: per ogni share, le teste `ai` dei dispositivi [devices] lette da zero, le voci attestate (file preparato e non tolto)
   * e il vincitore di D7 (ricetta piu' alta, poi id minore, poi impronta minore).
   */
  private suspend fun oracle(
    ai: AiWorld,
    shares: List<String>,
    devices: List<String>,
    files: Map<String, List<TestFile>>,
    removed: Map<String, Set<ContentKey>>,
    prepared: Map<String, Set<ContentKey>>,
  ): Map<Triple<String, ContentKey, AiClass>, Triple<String, Int, String>> {
    val remote = NasAiRemoteIndex({ ai.world.nas.share(it) }, { ai.world.trust.state }, "test", Dispatchers.Unconfined)
    val out = HashMap<Triple<String, ContentKey, AiClass>, Triple<String, Int, String>>()
    for (share in shares) {
      val attested = prepared.getValue(share) - removed.getValue(share)
      val candidates = ArrayList<AiCandidate>()
      for (device in devices) {
        remote.startRound()
        val head = ((remote.latestHead(share, device, null) as RemoteResult.Ok).value as? HeadFetch.Accepted)?.head ?: continue
        for (segment in head.segments) {
          val content = ((remote.segment(head, segment) as RemoteResult.Ok).value as AiSegmentFetch.Ok).content
          content.entries.forEach { candidates += AiCandidate(device, segment.ref.hash, AiKey(it.contentKey, content.cls), it.body.recipe, it.digest) }
        }
      }
      // La regola scritta qui da capo (non AiFusion): ricetta piu' alta, poi id del dispositivo minore, poi impronta minore.
      for ((key, list) in candidates.filter { it.key.contentKey in attested }.groupBy { it.key }) {
        val recipe = list.maxOf { it.recipe }
        val device = list.filter { it.recipe == recipe }.minOf { it.deviceId }
        val digest = list.filter { it.recipe == recipe && it.deviceId == device }.minOf { it.digest }
        out[Triple(share, key.contentKey, key.cls)] = Triple(device, recipe, digest)
      }
    }
    check(files.isNotEmpty())
    return out
  }

  @Test
  fun duecentoGiriCasuali_seme1() = run(seed = 1)

  @Test
  fun duecentoGiriCasuali_seme2() = run(seed = 2)

  @Test
  fun duecentoGiriCasuali_seme3() = run(seed = 3)
}
