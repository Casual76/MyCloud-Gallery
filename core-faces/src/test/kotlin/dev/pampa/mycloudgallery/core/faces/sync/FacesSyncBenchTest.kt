package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHeadLocation
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import java.util.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * IL BANCO DELLA SINCRONIZZAZIONE DEI VOLTI (Fase 8, P3, "Fatto quando": **200 giri del banco con `FaultPlan`**). Tre dispositivi
 * dell'account `test` e uno di `test2` lavorano insieme sopra l'area privata con i guasti del NAS prima e dopo ogni operazione (15%), morti
 * del processo, stato locale perso, ore che avanzano, stato fresco che va e viene e un attaccante con la password che copia nella mia area
 * i file dell'altro account e riempie le cartelle di spazzatura. Dopo ogni giro:
 * 1. ogni scrittura riuscita di un dispositivo sta sotto `faces/<G>/<lui>/` (e nessuna nell'area comune);
 * 2. nessuna lettura sotto la cartella del dispositivo dell'altro account;
 * 3. nessuna scrittura di un dispositivo senza lo stato fresco;
 * 4. ogni testa scritta da un dispositivo onesto si rilegge valida con tutti i suoi segmenti;
 * 5. l'anti-rollback della replica non torna mai indietro;
 * 6. nessuna eccezione verso fuori.
 * Alla fine, senza guasti, i tre dispositivi convergono: stesse voci `people`, stessi corpi vincenti, stessa istantanea vincente.
 */
open class FacesSyncBenchTest {

  /** Il mondo della prova: in memoria (P3). La versione sul database dei volti (P6) lo sostituisce con lo stesso mondo sopra `RoomSyncStorage`. */
  protected open fun newWorld(): FacesSyncWorld = FacesSyncWorld()

  /** Tre mondi diversi (gli id dei dispositivi sono casuali: cambiano gli spareggi), 200 giri ciascuno. */
  @Test
  fun duecentoGiriConGuasti() = runTest {
    for (seed in listOf(28_10L, 1_028L, 2_026L)) bench(seed)
  }

  private suspend fun bench(seed: Long) {
    val world = newWorld()
    val plan = FacesFaultPlan(seed = seed, probability = 0.15)
    val share = world.share("test")
    val phone = world.device("Telefono", "test")
    val tablet = world.device("Tablet", "test")
    val laptop = world.device("Portatile", "test")
    val stranger = world.device("Telefono di Test2", "test2")
    val honest = listOf(phone, tablet, laptop)
    val random = Random(seed)
    val commonBefore = world.family.common.paths().toSet()
    val marks = HashMap<Pair<String, String>, Long>()
    share.plan = plan
    var seq = 0L
    val outcomes = java.util.TreeMap<String, Int>()

    repeat(200) { round ->
      val stale = honest.filter { random.nextInt(8) == 0 }.toSet()
      for (d in honest) d.switch.fresh.value = d !in stale
      val writtenBefore = honest.associateWith { it.attempts.size }
      repeat(6) {
        val d = honest[random.nextInt(honest.size)]
        when (random.nextInt(10)) {
          0, 1 -> d.compute(random.nextInt(30), faces = 1 + random.nextInt(3), recipe = 1 + random.nextInt(2))
          2 -> d.edit(PeopleEdit.Name(FacesSyncWorld.person(random.nextInt(6)), "Nome ${random.nextInt(20)}"), dated = random.nextInt(5) != 0)
          3 -> d.edit(PeopleEdit.Hidden(FacesSyncWorld.person(random.nextInt(6)), random.nextBoolean()))
          4 -> if (d === phone) {
            val keys = phone.archive.winners((0 until 30).map { FacesSyncWorld.key(it) }).filterValues { it.faces.isNotEmpty() }.keys.map { k -> (0 until 30).first { FacesSyncWorld.key(it) == k } }
            if (keys.isNotEmpty()) {
              seq++
              phone.snapshot(seq, seq - 1, mapOf(FacesSyncWorld.person(1) to keys.take(3).map { it to 0 }))
            }
          }
          5, 6 -> d.round()
          7 -> d.publisher.flush(FlushReason.entries[random.nextInt(FlushReason.entries.size)]).also { outcomes.merge(it.toString().substringBefore('('), 1, Int::plus) }
          8 -> d.restart(loseLocal = random.nextInt(15) == 0)
          else -> world.clock.advanceMillis(random.nextInt(360_000).toLong())
        }
      }
      // L'attaccante: i file dell'altro account copiati nella mia area, spazzatura al prossimo nome di una testa.
      if (random.nextInt(5) == 0) {
        stranger.compute(random.nextInt(30))
        stranger.publisher.flush(FlushReason.UNIT_FINISHED)
        for (path in stranger.share.paths().filter { it.isWithin(NasLayout.FACES_DIR) }) share.plant(path, stranger.share.bytes(path)!!)
      }
      if (random.nextInt(6) == 0) {
        val victim = honest[random.nextInt(honest.size)]
        val top = share.paths().filter { it.isWithin(NasLayout.facesHeadDir(world.generation, victim.id)) }.mapNotNull { NasLayout.parseVersionName(it.name) }.maxOrNull() ?: 0L
        share.plant(NasLayout.facesHead(world.generation, victim.id, top + 1 + random.nextInt(3)), ByteArray(64) { random.nextInt().toByte() })
      }
      check(round, world, honest, stranger, commonBefore, marks)
      for (d in stale) assertWithMessage("giro $round: ${d.name} ha scritto senza lo stato fresco").that(d.attempts.size).isEqualTo(writtenBefore.getValue(d))
    }
    assertThat(plan.fired).isGreaterThan(50)
    assertWithMessage("pubblicazioni riuscite durante i guasti").that(outcomes["Published"] ?: 0).isGreaterThan(5)

    // Senza guasti, i tre convergono.
    plan.enabled = false
    for (d in honest) d.switch.fresh.value = true
    repeat(4) {
      world.clock.advanceMillis(400_000)
      for (d in honest) {
        d.round()
        d.publisher.flush(FlushReason.UNIT_FINISHED)
      }
    }
    for (d in honest) d.round()
    val people = phone.archive.people()
    val keys = (0 until 30).map { FacesSyncWorld.key(it) }
    val winners = phone.archive.winners(keys).mapValues { it.value.author to it.value.digest }
    val snapshot = phone.sync.winningSnapshot()?.let { it.device to it.mark }
    for (d in listOf(tablet, laptop)) {
      assertWithMessage("voci di ${d.name}").that(d.archive.people()).isEqualTo(people)
      assertWithMessage("corpi di ${d.name}\n" + diagnose(world, honest, keys)).that(d.archive.winners(keys).mapValues { it.value.author to it.value.digest }).isEqualTo(winners)
      assertWithMessage("istantanea di ${d.name}").that(d.sync.winningSnapshot()?.let { it.device to it.mark }).isEqualTo(snapshot)
    }
    assertThat(people).isNotEmpty()
    assertThat(winners).isNotEmpty()
  }

  /** Che cosa sa ogni dispositivo (solo per il messaggio di una prova fallita). */
  private suspend fun diagnose(world: FacesSyncWorld, honest: List<SyncDevice>, keys: List<dev.pampa.mycloudgallery.core.model.ContentKey>): String {
    val out = StringBuilder()
    val share = world.share("test")
    for (d in honest) {
      val state = d.publishStore.load(world.generation)
      val own = d.archive.winners(keys).filterValues { it.author == d.id }
      val missing = own.keys - state.keys
      val notAdmitted = own.values.count { !FacesWire.admits(it) }
      val heads = share.paths().filter { it.isWithin(NasLayout.facesHeadDir(world.generation, d.id)) }.mapNotNull { NasLayout.parseVersionName(it.name) }.sorted()
      out.append("${d.name} ${d.id}: chiavi=${state.keys.size} proprie=${own.size} mancanti=${missing.size} nonAmmesse=$notAdmitted pub=${state.publication} incerto=${state.uncertain} teste=$heads\n")
      for ((other, r) in d.replicaStore.load(world.generation).devices) out.append("   legge $other v=${r.mark?.version} fatti=${r.done.size}/${r.segments.size}\n")
      val latest = state.publication ?: continue
      val bytes = share.bytes(NasLayout.facesHead(world.generation, d.id, latest.version)) ?: continue
      val head = (FacesDocs.decodeHead(bytes, world.family.state, FacesHeadLocation(world.generation, d.id, latest.version), "test", null) as? Verdict.Accepted)?.value?.head ?: continue
      val published = head.segments.filter { it.cls == dev.pampa.mycloudgallery.core.trust.data.SegmentClass.FACES }.flatMap { seg ->
        val b = share.bytes(NasLayout.facesSegment(world.generation, d.id, seg.ref.hash)) ?: return@flatMap emptyList()
        ((FacesDocs.decodeSegment(b, seg.ref, seg.cls) as? Verdict.Accepted)?.value as? dev.pampa.mycloudgallery.core.trust.data.FacesSegmentContent.Bodies)?.entries?.map { it.contentKey } ?: emptyList()
      }.toSet()
      out.append("   nella sua testa ${latest.version}: ${published.size} corpi; proprie non pubblicate: ${(own.keys - published).size}\n")
    }
    return out.toString()
  }

  private fun check(
    round: Int,
    world: FacesSyncWorld,
    honest: List<SyncDevice>,
    stranger: SyncDevice,
    commonBefore: Set<dev.pampa.mycloudgallery.core.trust.nas.SharePath>,
    marks: HashMap<Pair<String, String>, Long>,
  ) = kotlinx.coroutines.runBlocking {
    val share = world.share("test")
    for (d in honest) {
      for ((who, path) in d.attempts) {
        assertWithMessage("giro $round: scrittura di un altro").that(who).isEqualTo(d.id)
        assertWithMessage("giro $round: fuori da faces/<G>/<io>/").that(path.isWithin(NasLayout.facesDeviceDir(world.generation, d.id))).isTrue()
      }
      // Ogni testa scritta da lui si rilegge valida, con tutti i suoi segmenti validi.
      val heads = d.written.map { it.second }.filter { it.isWithin(NasLayout.facesHeadDir(world.generation, d.id)) }
      for (path in heads.takeLast(2)) {
        val version = NasLayout.parseVersionName(path.name)!!
        val bytes = (share.bytes(path))!!
        val head = (FacesDocs.decodeHead(bytes, world.family.state, FacesHeadLocation(world.generation, d.id, version), "test", null) as? Verdict.Accepted)?.value
        assertWithMessage("giro $round: testa di ${d.name} non valida").that(head).isNotNull()
        for (segment in head!!.head.segments) {
          val seg = share.bytes(NasLayout.facesSegment(world.generation, d.id, segment.ref.hash))
          assertWithMessage("giro $round: segmento mancante").that(seg).isNotNull()
          assertWithMessage("giro $round: segmento non valido").that(FacesDocs.decodeSegment(seg!!, segment.ref, segment.cls)).isInstanceOf(Verdict.Accepted::class.java)
        }
      }
      // L'anti-rollback della replica non torna indietro (stato durevole: sopravvive alle morti del processo, non alla perdita dei dati).
      for ((other, replica) in d.replicaStore.load(world.generation).devices) {
        val version = replica.mark?.version ?: continue
        val key = "${System.identityHashCode(d.replicaStore)}/${d.id}" to other
        val before = marks[key]
        if (before != null) assertWithMessage("giro $round: anti-rollback").that(version).isAtLeast(before)
        marks[key] = version
      }
    }
    assertWithMessage("giro $round: area comune").that(world.family.common.paths().toSet()).isEqualTo(commonBefore)
    assertWithMessage("giro $round: letta la cartella dell'altro account")
      .that(share.reads.none { it.isWithin(NasLayout.facesDeviceDir(world.generation, stranger.id)) }).isTrue()
    // Nessuna lettura dei suoi dati letta per errore come se fosse nostra: nessuna sua voce nei nostri archivi.
    for (d in honest) assertWithMessage("giro $round: voce dell'altro account").that(d.archive.people().values.none { it.author == stranger.id }).isTrue()
    Unit
  }
}
