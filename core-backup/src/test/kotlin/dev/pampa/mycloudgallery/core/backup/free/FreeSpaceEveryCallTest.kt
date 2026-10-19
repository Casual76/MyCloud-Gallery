package dev.pampa.mycloudgallery.core.backup.free

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.free.testing.InMemoryFreeSpaceCatalog
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P9, §4.3 ("ogni passo in piu' tappe ha il test 'guasto a ogni chiamata' piu' la morte del processo a ogni scrittura del diario") e
 * invariante 14 del §5.4, per "Libera spazio" sul NAS finto e sul telefono finto:
 * - un guasto (`FaultPlan`, connessione che cade) **prima e dopo** che il NAS abbia risposto a **ogni** chiamata del giro (riletture, timbri);
 * - la **morte del processo** prima e dopo **ogni** scrittura del diario di "Libera spazio" e del catalogo, anche fra il dialogo di sistema e
 *   la registrazione della risposta; e dopo, la ripresa nello stesso avvio, oppure dopo un riavvio del telefono.
 * Il caso: tre media al sicuro (un video), uno con due versioni, uno con la sola copia comune (guardiano) e uno con la copia privata
 * **rovinata conservando il timbro** (solo la rilettura lo vede). Dopo ogni interruzione: (a) ogni lotto che esce, anche dalla ripresa a
 * meta', contiene solo righe con le copie integre adesso (verita' del NAS finto); (b) nessuna riga tolta dal telefono perde la traccia nel
 * diario, nessuna riga e' data per liberata senza esserlo; (c) alla fine tutto cio' che e' al sicuro e' liberato, quella rovinata no.
 */
class FreeSpaceEveryCallTest {

  private class Case(val freeable: List<FreeSpaceBench.Made>, val tampered: FreeSpaceBench.Made)

  private class Stats(var rounds: Int = 0, var interruptions: Int = 0, var batches: Int = 0)

  private suspend fun setup(b: FreeSpaceBench): Case {
    val good = listOf(b.safe(), b.safe(), b.safe(kind = MediaKind.VIDEO, size = 9_000), b.edited(), b.safe(privateCopy = false, commonCopy = true))
    val tampered = b.safe()
    // Un byte cambiato sul posto conservando dimensione, data e fileId: il timbro non lo vede, la rilettura completa si'.
    val path = tampered.privatePaths.getValue(tampered.current)
    val stamp = (b.nas.reader.stat(FreeSpaceBench.PRIVATE, FreeSpaceBench.pathOf(path), Purpose.BACKGROUND) as NasResult.Ok).value!!
    val bytes = b.nas.bytes(FreeSpaceBench.PRIVATE, path)!!.also { it[7] = (it[7] + 1).toByte() }
    b.nas.modify(FreeSpaceBench.PRIVATE, path, NasContent.of(bytes), modifiedAt = stamp.modifiedAt)
    return Case(good, tampered)
  }

  /** Un "processo": piano, conferma, verifica a tappe, lotti fino in fondo; ogni lotto controllato quando esce. */
  private suspend fun flow(b: FreeSpaceBench, space: SafeFreeSpace, stats: Stats, budget: Duration = 1.hours) {
    val plan = space.plan(dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter())
    val confirmation = dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations.confirm(plan, b.clock.monotonicMs())
    batches(b, space, space.verify(plan, confirmation, budget), stats, budget)
  }

  private suspend fun batches(b: FreeSpaceBench, space: SafeFreeSpace, start: FreeSpaceRun, stats: Stats, budget: Duration = 1.hours) {
    var run = start
    var stages = 0
    while (run.isActive && run.state != FreeSpaceRunState.READY && stages++ < 10) run = space.resume(run, budget)
    while (true) {
      val batch = space.nextBatch(run) ?: return
      stats.batches++
      assertWithMessage("lotto ${batch.index}: invariante 14").that(b.batchViolations(batch)).isEmpty()
      run = space.recordOutcome(batch, b.dialog(batch))
    }
  }

  /** La ripresa dopo l'interruzione: un processo nuovo riprende il giro di prima (se c'e'), risolve gli esiti in dubbio e ne fa uno nuovo. */
  private suspend fun recover(b: FreeSpaceBench, stats: Stats, reboot: Boolean) {
    if (reboot) {
      b.bootCount = b.bootCount!! + 1
      b.clock.monotonic = 2_000
    }
    b.restart()
    assertWithMessage("traccia a meta'").that(b.trackingViolations(settled = false)).isEmpty()
    val last = b.diary.runs().lastOrNull()
    if (last != null) {
      val resumed = b.space.resume(runOf(last), 1.hours)
      if (reboot) assertThat(resumed.state).isNotEqualTo(FreeSpaceRunState.READY)
      batches(b, b.space, resumed, stats)
    }
    b.space.settle()
    flow(b, b.space, stats)
  }

  private suspend fun finalChecks(b: FreeSpaceBench, case: Case, label: String) {
    assertWithMessage("$label: traccia").that(b.trackingViolations(settled = true)).isEmpty()
    assertWithMessage("$label: la copia rovinata non si libera").that(b.phone.ref(case.tampered.phoneId!!)).isNotNull()
    assertWithMessage("$label: il telefono ha ancora i suoi byte").that(b.phone.sha256(case.tampered.phoneId!!))
      .isEqualTo(Sha256.of(case.tampered.data.getValue(case.tampered.current)))
    assertWithMessage("$label: tutto il resto liberato").that(b.removed.map { it.mediaStoreId }).containsExactlyElementsIn(case.freeable.map { it.phoneId })
    val tamperedItems = b.diary.everything().map { it.second }.filter { it.mediaId == case.tampered.id }
    assertWithMessage("$label: mai consegnata ad Android").that(tamperedItems.filter { it.state.handedToAndroid }).isEmpty()
  }

  @Test
  fun unGuastoDelNasPrimaEDopoOgniChiamata() = runTest {
    val stats = Stats()
    val clean = FreeSpaceBench()
    val cleanCase = setup(clean)
    val counter = FaultPlan()
    clean.nas.readFaults = counter
    flow(clean, clean.space, stats)
    clean.nas.readFaults = null
    finalChecks(clean, cleanCase, "pulito")
    val total = counter.operations
    assertWithMessage("chiamate contate").that(total).isAtLeast(20)
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val b = FreeSpaceBench()
        val case = setup(b)
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        b.nas.readFaults = fault
        flow(b, b.space, stats)
        b.nas.readFaults = null
        val label = "guasto alla chiamata $n/$total $phase"
        assertWithMessage("$label: il guasto e' scattato").that(fault.fired).hasSize(1)
        stats.interruptions++
        recover(b, stats, reboot = false)
        stats.rounds++
        finalChecks(b, case, label)
      }
    }
    assertThat(stats.interruptions).isEqualTo(2 * total)
    assertThat(stats.batches).isAtLeast(stats.rounds)
    println("Libera spazio, guasti: $total chiamate, ${stats.interruptions} interruzioni, ${stats.rounds} riprese, ${stats.batches} lotti controllati")
  }

  @Test
  fun laMorteDelProcessoPrimaEDopoOgniScrittura_ripresaNelloStessoAvvio() = runTest { deaths(reboot = false) }

  @Test
  fun laMorteDelProcessoPrimaEDopoOgniScrittura_ripresaDopoUnRiavvio() = runTest { deaths(reboot = true) }

  private suspend fun deaths(reboot: Boolean) {
    val stats = Stats()
    val clean = FreeSpaceBench()
    val cleanCase = setup(clean)
    val counter = BackupDeath()
    flow(clean, dying(clean, counter), stats)
    finalChecks(clean, cleanCase, "pulito")
    val total = counter.steps
    assertWithMessage("scritture contate").that(total).isAtLeast(10)
    for (n in 1..total) {
      for (after in listOf(false, true)) {
        val b = FreeSpaceBench()
        val case = setup(b)
        val death = BackupDeath(n, after)
        try {
          flow(b, dying(b, death), stats)
        } catch (_: ProcessDeath) {
          // il processo e' morto: si butta cio' che sta in memoria e si riparte dal diario
        }
        val label = "morte alla scrittura $n/$total ${if (after) "dopo" else "prima"}${if (reboot) " e riavvio" else ""}"
        assertWithMessage("$label: la morte e' avvenuta").that(death.died).isTrue()
        stats.interruptions++
        recover(b, stats, reboot)
        stats.rounds++
        finalChecks(b, case, label)
      }
    }
    assertThat(stats.interruptions).isEqualTo(2 * total)
    println("Libera spazio, morti${if (reboot) " con riavvio" else ""}: $total scritture, ${stats.interruptions} interruzioni, ${stats.rounds} riprese, ${stats.batches} lotti controllati")
  }

  /** Un motore sopra un diario e un catalogo che "muoiono" alla scrittura scelta da [death]. */
  private fun dying(b: FreeSpaceBench, death: BackupDeath): SafeFreeSpace =
    b.engine(diary = DyingFreeSpaceDiary(b.diary, death), catalog = DyingFreeSpaceCatalog(b.catalog, death))

  @Test
  fun unDiarioCheNonConservaICampiFacoltativi_nelloStessoProcessoVaTutto() = runTest {
    // L'adattatore Room senza colonne nuove (P14): niente validita', niente riga del telefono, solo la copia della versione del telefono.
    // Nello stesso processo il motore ricorda per intero cio' che ha scritto.
    val b = FreeSpaceBench()
    val case = setup(b)
    flow(b, b.engine(diary = LossyFreeSpaceDiary(b.diary)), Stats())
    finalChecks(b, case, "diario incompleto, stesso processo")
  }

  @Test
  fun unDiarioIncompleto_dopoLaMorteDelProcessoNienteVaAvanti_eLaTracciaResta() = runTest {
    val b = FreeSpaceBench()
    val case = setup(b)
    val lossy = LossyFreeSpaceDiary(b.diary)
    val first = b.engine(diary = lossy)
    val plan = first.plan(dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter())
    val run = first.verify(plan, dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations.confirm(plan, b.clock.monotonicMs()), 1.hours)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
    val batch = first.nextBatch(run)!!
    assertThat(b.batchViolations(batch)).isEmpty()
    // L'utente conferma nel dialogo di sistema; il processo muore prima della risposta.
    b.dialog(batch)
    val second = b.engine(diary = lossy)
    val resumed = second.resume(run, 1.hours)
    // Il diario non sa la riga del telefono: nessuna prova possibile, quindi "in dubbio", mai "liberato" ne' riproposto.
    assertThat(resumed.isActive).isFalse()
    assertThat(resumed.freedItems).isEqualTo(0)
    assertThat(second.nextBatch(resumed)).isNull()
    assertThat(b.diary.everything().map { it.second }.filter { it.state.handedToAndroid }.map { it.state }.toSet()).containsExactly(FreeItemState.IN_DOUBT)
    assertThat(b.trackingViolations(settled = true)).isEmpty()
    finalChecks(b, case, "diario incompleto dopo la morte")
  }

  @Test
  fun unDiarioIncompleto_unGiroAMetaNonRiprendeDopoLaMorteDelProcesso() = runTest {
    val b = FreeSpaceBench()
    setup(b)
    val lossy = LossyFreeSpaceDiary(b.diary)
    val first = b.engine(diary = lossy)
    val plan = first.plan(dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter())
    val run = first.verify(plan, dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations.confirm(plan, b.clock.monotonicMs()), Duration.ZERO)
    assertThat(run.isActive).isTrue()
    // Stesso avvio, conteggio dei riavvii noto: ma la validita' non e' nel diario, quindi il giro non si fida e si chiude.
    val resumed = b.engine(diary = lossy).resume(run, 1.hours)
    assertThat(resumed.state).isEqualTo(FreeSpaceRunState.EXPIRED)
    assertThat(b.removed).isEmpty()
  }

  private fun runOf(record: FreeRunRecord): FreeSpaceRun = FreeSpaceRun(
    id = record.id, planHash = record.planHash, state = record.state, permanent = record.permanent,
    guardedDone = 0, guardedTotal = 0, verifiedDone = 0, verifiedTotal = 0, verifiedBytes = 0, readyItems = 0, readyBytes = 0,
    batchesTotal = 0, batchesDone = record.batchesDone, freedItems = 0, freedBytes = 0,
  )
}

/** Il diario che muore: le scritture contano come passi (le letture no). */
internal class DyingFreeSpaceDiary(private val inner: FreeSpaceDiary, private val death: BackupDeath) : FreeSpaceDiary by inner {
  override suspend fun create(run: NewFreeRun, items: List<FreeItemRecord>): FreeRunRecord = death.around { inner.create(run, items) }

  override suspend fun update(run: FreeRunRecord, items: List<FreeItemRecord>) = death.around { inner.update(run, items) }
}

/** Il catalogo che muore: le sue scritture (la verifica, le copie liberate) contano come passi. */
internal class DyingFreeSpaceCatalog(private val inner: InMemoryFreeSpaceCatalog, private val death: BackupDeath) : FreeSpaceCatalog by inner {
  override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean =
    death.around { inner.markVerified(location, readBack, atMillis) }

  override suspend fun freedFromPhone(locations: List<LocationId>) = death.around { inner.freedFromPhone(locations) }
}

/**
 * Un diario che conserva solo cio' che le colonne v4 di `free_space_run`/`free_space_item` sanno tenere (come un adattatore Room senza
 * migrazione): niente validita', niente riga del telefono ne' timbro, solo la prima copia verificata. Il motore deve fallire chiuso.
 */
internal class LossyFreeSpaceDiary(private val inner: FreeSpaceDiary) : FreeSpaceDiary by inner {
  override suspend fun run(id: dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunId): FreeRunRecord? = inner.run(id)?.copy(validity = null, blocked = null)

  override suspend fun runs(): List<FreeRunRecord> = inner.runs().map { it.copy(validity = null, blocked = null) }

  override suspend fun items(id: dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunId): List<FreeItemRecord> =
    inner.items(id).map { it.copy(phone = null, phoneStamp = null, mime = null, checks = it.checks?.take(1)) }

  override suspend fun create(run: NewFreeRun, items: List<FreeItemRecord>): FreeRunRecord = inner.create(run, items).copy(validity = null, blocked = null)

  override suspend fun update(run: FreeRunRecord, items: List<FreeItemRecord>) {
    // Le colonne che non ci sono non si scrivono: cio' che si legge torna senza.
    val stored = inner.run(run.id)!!
    val old = inner.items(run.id).associateBy { it.index }
    inner.update(run.copy(validity = stored.validity), items.map { item ->
      val o = old.getValue(item.index)
      item.copy(phone = o.phone, phoneStamp = o.phoneStamp, mime = o.mime, checks = item.checks?.take(1))
    })
  }
}
