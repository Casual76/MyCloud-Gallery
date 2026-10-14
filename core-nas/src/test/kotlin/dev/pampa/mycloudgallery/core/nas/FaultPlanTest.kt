package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.fs.SmbNasHousekeeping
import dev.pampa.mycloudgallery.core.nas.fs.SmbServerSideCopy
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il piano dei guasti del simulatore (§5.2) e la "ripresa dopo un guasto a ogni chiamata" (§4.3): per ogni operazione
 * SMB di un'azione, una caduta **prima** che il server l'applichi e una **dopo** (risposta persa), poi una sessione
 * scaduta. Gli invarianti: nessun file a meta' visibile, nessun contenuto sbagliato, nessun temporaneo altrui toccato;
 * dopo un nuovo tentativo pulito il risultato e' quello giusto e i propri temporanei si tolgono.
 */
class FaultPlanTest {

  private val faults = listOf(SimFault.DropConnection, SimFault.ExpireSession)
  private val tmp = "${TrustFormat.ROOT_DIR}/tmp"

  /** Quante operazioni conta il piano per [action] su un NAS pulito. */
  private suspend fun TestScope.countOps(prepare: (Harness) -> Unit, action: suspend (Harness) -> Unit): Int {
    val h = Harness(backgroundScope, virtualClock())
    prepare(h)
    val plan = FaultPlan()
    h.backend.faults = plan
    action(h)
    return plan.operations
  }

  /**
   * Per ogni operazione [1..total], ogni fase e ogni guasto: un NAS nuovo (con [prepare]) e il piano gia' montato. Il
   * NAS di ogni giro si chiude alla fine (niente memoria trattenuta dai lavori della sessione).
   */
  private fun TestScope.forEachFault(total: Int, prepare: (Harness) -> Unit = {}, block: (Harness, String) -> Unit) {
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        for (fault in faults) {
          val job = SupervisorJob()
          try {
            val h = Harness(backgroundScope + job, virtualClock())
            prepare(h)
            h.backend.faults = FaultPlan().at(n, phase, fault)
            block(h, "operazione $n, $phase, $fault")
          } finally {
            job.cancel()
          }
        }
      }
    }
  }

  @Test
  fun leDueFasiDelGuastoSiDistinguono() = runTest {
    // Prima: la rinomina non avviene. Dopo: avviene, ma la risposta si perde.
    for ((phase, applied) in listOf(FaultPhase.BEFORE to false, FaultPhase.AFTER to true)) {
      val h = Harness(backgroundScope, virtualClock())
      h.backend.faults = FaultPlan().at(1, phase, SimFault.DropConnection, SimOp.RENAME)
      val result = h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { ctx ->
        ctx.share.createExclusive("a.tmp").use { it.renameNoReplace("a.jpg") }
      }
      assertThat(result).isInstanceOf(NasResult.Err::class.java)
      assertThat(h.backend.exists(SHARE, "a.jpg")).isEqualTo(applied)
      assertThat(h.backend.faults!!.fired.single().op).isEqualTo(SimOp.RENAME)
    }
  }

  @Test
  fun pubblicazioneEsclusivaConUnGuastoAOgniChiamata() = runTest {
    val path = SharePath.of(TrustFormat.ROOT_DIR, "registro", "00000000000000000001")
    val bytes = Random(1).nextBytes(1024 * 1024 + 5)
    val total = countOps({}) { assertThat(it.manager.fileSystem(SHARE).publishExclusive(path, bytes)).isEqualTo(PublishResult.CREATED) }
    assertThat(total).isAtLeast(8)

    forEachFault(total, prepare = { it.backend.put(SHARE, "$tmp/zzzzzzzzzzzzzzzzzzzzzzzzzz-" + "ab".repeat(16), byteArrayOf(7)) }) { h, where ->
      val fs = h.manager.fileSystem(SHARE)
      val first = runCatching { fs.publishExclusive(path, bytes) }
      first.exceptionOrNull()?.let { assertWithMessage(where).that(it).isInstanceOf(NasIoException::class.java) }
      first.getOrNull()?.let { assertWithMessage(where).that(it).isEqualTo(PublishResult.CREATED) }
      val visible = h.backend.bytes(SHARE, path.value)
      assertWithMessage(where).that(visible == null || visible.contentEquals(bytes)).isTrue()

      h.backend.faults = null
      val again = fs.publishExclusive(path, bytes)
      assertWithMessage(where).that(again).isEqualTo(if (first.getOrNull() == PublishResult.CREATED || visible != null) PublishResult.ALREADY_EXISTS else PublishResult.CREATED)
      assertWithMessage(where).that(h.backend.bytes(SHARE, path.value)).isEqualTo(bytes)
      // I propri temporanei rimasti (risposta della CREATE persa) li toglie la pulizia; quello altrui resta.
      runBlockingBounded("la pulizia dei temporanei") { SmbNasHousekeeping(h.manager) { listOf(SHARE) }.purgeOwnStaleTemps(DEVICE, Duration.ZERO) }
      assertWithMessage(where).that(h.backend.children(SHARE, tmp)).containsExactly("zzzzzzzzzzzzzzzzzzzzzzzzzz-" + "ab".repeat(16))
    }
  }

  @Test
  fun copiaLatoServerConUnGuastoAOgniChiamata() = runTest {
    // Tre tratti in una richiesta (le richieste lunghe sono provate in TransferBudgetTest).
    val source = Random(2).nextBytes(2 * 1024 * 1024 + 3)
    val prepare = { h: Harness -> h.backend.put(SHARE, "video.mp4", source) }
    val total = countOps(prepare) {
      assertThat(SmbServerSideCopy(it.manager).copy(SHARE, SharePath.of("video.mp4"), SHARE, SharePath.of("copia", "video.mp4")))
        .isEqualTo(NasResult.Ok(source.size.toLong()))
    }

    forEachFault(total, prepare) { h, where ->
      val copy = SmbServerSideCopy(h.manager)
      val first = runBlockingBounded("la copia") { copy.copy(SHARE, SharePath.of("video.mp4"), SHARE, SharePath.of("copia", "video.mp4")) }
      val visible = h.backend.bytes(SHARE, "copia/video.mp4")
      // Mai una copia a meta' visibile; la sorgente non si tocca.
      assertWithMessage(where).that(visible == null || visible.contentEquals(source)).isTrue()
      assertWithMessage(where).that(h.backend.bytes(SHARE, "video.mp4")).isEqualTo(source)
      if (first is NasResult.Ok) assertWithMessage(where).that(visible).isEqualTo(source)

      h.backend.faults = null
      val again = runBlockingBounded("la copia ripetuta") { copy.copy(SHARE, SharePath.of("video.mp4"), SHARE, SharePath.of("copia", "video.mp4")) }
      if (visible == null) {
        assertWithMessage(where).that(again).isEqualTo(NasResult.Ok(source.size.toLong()))
      } else {
        assertWithMessage(where).that(again).isEqualTo(NasResult.Err(NasError.Exists))
      }
      assertWithMessage(where).that(h.backend.bytes(SHARE, "copia/video.mp4")).isEqualTo(source)
      runBlockingBounded("la pulizia dei temporanei") { SmbNasHousekeeping(h.manager) { listOf(SHARE) }.purgeOwnStaleTemps(DEVICE, Duration.ZERO) }
      assertWithMessage(where).that(h.backend.children(SHARE, tmp)).isEmpty()
    }
  }

  @Test
  fun elencoConUnGuastoAOgniChiamataNonDaMaiDoppioni() = runTest {
    val prepare = { h: Harness -> repeat(25) { h.backend.put(SHARE, "foto/$it.jpg", byteArrayOf(1)) } }
    val total = countOps(prepare) { h ->
      h.manager.mediaReader().list(SHARE, SharePath.of("foto"), Purpose.BACKGROUND, pageSize = 10) {}.value()
    }

    forEachFault(total, prepare) { h, where ->
      val names = ArrayList<String>()
      val result = runBlockingBounded("l'elenco") {
        h.manager.mediaReader().list(SHARE, SharePath.of("foto"), Purpose.BACKGROUND, pageSize = 10) { page -> page.mapTo(names) { it.name } }
      }
      assertWithMessage(where).that(names).containsNoDuplicates()
      if (result is NasResult.Ok) assertWithMessage(where).that(names).hasSize(25)
    }
  }

  @Test
  fun readAllConUnGuastoAOgniChiamataDaLoSha256GiustoOUnErrore() = runTest {
    val content = Random(3).nextBytes(3 * NasMediaLimits.READ_ALL_CHUNK_BYTES + 99)
    val prepare = { h: Harness -> h.backend.put(SHARE, "grande.dng", content) }
    val read: suspend (Harness) -> NasResult<Sha256> = { h ->
      when (val opened = h.manager.mediaReader().open(SHARE, SharePath.of("grande.dng"), Purpose.BACKGROUND)) {
        is NasResult.Err -> opened
        is NasResult.Ok -> h.manager.mediaReader().readAll(opened.value, { _, _ -> })
      }
    }
    val total = countOps(prepare) { assertThat(read(it)).isEqualTo(NasResult.Ok(Sha256.of(content))) }

    forEachFault(total, prepare) { h, where ->
      val result = runBlockingBounded("la lettura") { read(h) }
      if (result is NasResult.Ok) assertWithMessage(where).that(result.value).isEqualTo(Sha256.of(content))
    }
  }

  @Test
  fun unBloccoProgrammatoLoChiudeIlSorvegliante() = runTest {
    val io = kotlinx.coroutines.CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val h = Harness(backgroundScope, virtualClock(), io = kotlinx.coroutines.Dispatchers.IO)
    h.backend.put(SHARE, "foto/a.jpg", byteArrayOf(1))
    blockingIo("il primo elenco") { h.manager.browser(SHARE).list(SharePath.of("foto"), 10) }.value()
    h.backend.faults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.Stall, SimOp.OPEN_DIRECTORY)
    val entered = java.util.concurrent.CountDownLatch(1)
    h.backend.beforeOp = { op, _ -> if (op == SimOp.OPEN_DIRECTORY) entered.countDown() }
    val result = io.async { h.manager.browser(SHARE).list(SharePath.of("foto"), 10) }
    entered.mustOpen("l'elenco fermo")

    // Il server non risponde: dopo la scadenza interattiva il sorvegliante chiude la connessione.
    advanceTimeBy(15_500)
    runCurrent()
    try {
      assertThat(result.awaitBlocking("l'elenco fermo").errorOrNull()).isEqualTo(NasError.Timeout)
      assertThat(h.backend.openConnections).isEqualTo(0)
    } finally {
      io.cancel()
    }
  }

  @Test
  fun iGuastiCasualiSonoRipetibili() = runTest {
    fun run(): List<Int> {
      val h = Harness(backgroundScope, virtualClock())
      repeat(10) { h.backend.put(SHARE, "f/$it", byteArrayOf(1)) }
      val plan = FaultPlan().random(seed = 42, probability = 0.2, phase = FaultPhase.AFTER, fault = SimFault.DropConnection)
      h.backend.faults = plan
      repeat(5) { runBlockingBounded("l'elenco") { h.manager.mediaReader().list(SHARE, SharePath.of("f"), Purpose.BACKGROUND) {} } }
      return plan.fired.map { it.index }
    }
    val first = run()
    assertThat(first).isNotEmpty()
    assertThat(run()).isEqualTo(first)
  }
}
