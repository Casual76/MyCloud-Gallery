package dev.pampa.mycloudgallery.core.backup.free

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il "Fatto quando" di 5.5 (`docs/fase5-progetto.md` §2.5): **"simulando una copia sul NAS corrotta o mancante, quell'elemento non finisce
 * mai tra quelli da liberare"**, con tutte le varianti del progetto: un byte cambiato prima della verifica (anche conservando il timbro),
 * dopo la verifica e prima del controllo finale, la copia tolta (prima e dopo la verifica), il NAS che cade a meta' verifica, l'ora del
 * telefono (e del NAS) spostata. Ogni lotto che esce e' controllato contro la verita' del NAS finto ([FreeSpaceBench.batchViolations]).
 */
class FattoQuando55Test {

  private val bench = FreeSpaceBench()

  /** Tre media al sicuro: il caso sotto prova e' sempre il primo, gli altri due devono andare comunque. */
  private fun three() = Triple(bench.safe(), bench.safe(), bench.safe())

  @Test
  fun unByteCambiatoPrimaDellaVerifica_conIlTimbroNuovo_restaSulTelefono() = runTest {
    val (bad, a, b) = three()
    val (plan, confirmation) = bench.confirmed()
    assertThat(plan.itemCount).isEqualTo(3)
    // Il PC cambia un byte dopo il foglio e prima della verifica sul momento.
    bench.corruptOnNas(FreeSpaceBench.PRIVATE, bad.privatePaths.getValue(bad.current))
    val run = bench.space.verify(plan, confirmation, 1.hours)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(run.dropped).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1)
    val done = bench.freeAll(run)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactly(a.phoneId, b.phoneId)
    assertThat(bench.phone.ref(bad.phoneId!!)).isNotNull()
    assertThat(done.freedItems).isEqualTo(2)
  }

  @Test
  fun unByteCambiatoPrimaDellaVerifica_conLoStessoTimbro_laRiletturaLoTrova_eB8() = runTest {
    val (bad, _, _) = three()
    val path = bad.privatePaths.getValue(bad.current)
    // Una modifica sul posto che conserva dimensione e data (I3 della revisione 5A): il timbro non la vede, la rilettura si'.
    val stamp = bench.nas.reader.stat(FreeSpaceBench.PRIVATE, FreeSpaceBench.pathOf(path), dev.pampa.mycloudgallery.core.nas.contract.Purpose.BACKGROUND)
      .let { (it as dev.pampa.mycloudgallery.core.nas.contract.NasResult.Ok).value!! }
    val corrupted = bench.nas.bytes(FreeSpaceBench.PRIVATE, path)!!.also { it[10] = (it[10] + 1).toByte() }
    bench.nas.modify(FreeSpaceBench.PRIVATE, path, NasContent.of(corrupted), modifiedAt = stamp.modifiedAt)
    val run = bench.start()
    assertThat(run.dropped).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1)
    // E-B8: la copia esce da "al sicuro" (il backup la rifara' dal telefono, che ce l'ha ancora).
    assertThat(bench.catalog.location(locationOf(bad))!!.verifiedAtMillis).isNull()
    assertThat(bench.catalog.verifications).contains(locationOf(bad) to false)
    bench.freeAll(run)
    assertThat(bench.phone.ref(bad.phoneId!!)).isNotNull()
    assertThat(bench.removed).hasSize(2)
  }

  @Test
  fun unByteCambiatoDopoLaVerificaEPrimaDelControlloFinale_restaSulTelefono_l39() = runTest {
    val (bad, a, b) = three()
    val run = bench.start()
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(run.readyItems).isEqualTo(3)
    bench.corruptOnNas(FreeSpaceBench.PRIVATE, bad.privatePaths.getValue(bad.current))
    val batch = bench.space.nextBatch(run)!!
    assertThat(batch.items.map { it.mediaId }).containsExactly(a.id, b.id)
    assertThat(bench.batchViolations(batch)).isEmpty()
    val after = bench.space.recordOutcome(batch, bench.dialog(batch))
    assertThat(after.dropped).containsExactly(FreeSpaceExclusion.COPY_CHANGED, 1)
    assertThat(after.state).isEqualTo(FreeSpaceRunState.DONE)
    assertThat(bench.phone.ref(bad.phoneId!!)).isNotNull()
  }

  @Test
  fun laCopiaTolta_primaDellaVerifica() = runTest {
    val (bad, _, _) = three()
    val (plan, confirmation) = bench.confirmed()
    bench.nas.delete(FreeSpaceBench.PRIVATE, bad.privatePaths.getValue(bad.current))
    val run = bench.space.verify(plan, confirmation, 1.hours)
    assertThat(run.dropped).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1)
    bench.freeAll(run)
    assertThat(bench.phone.ref(bad.phoneId!!)).isNotNull()
    assertThat(bench.removed).hasSize(2)
  }

  @Test
  fun laCopiaTolta_dopoLaVerifica() = runTest {
    val (bad, _, _) = three()
    val run = bench.start()
    bench.nas.delete(FreeSpaceBench.PRIVATE, bad.privatePaths.getValue(bad.current))
    val done = bench.freeAll(run)
    assertThat(done.dropped).containsExactly(FreeSpaceExclusion.COPY_CHANGED, 1)
    assertThat(bench.phone.ref(bad.phoneId!!)).isNotNull()
    assertThat(bench.removed).hasSize(2)
  }

  @Test
  fun laCopiaDellOriginaleInVersioniRovinata_ilMediaModificatoRestaSulTelefono() = runTest {
    val edited = bench.edited()
    val ok = bench.safe()
    // La copia dell'attuale e' perfetta; quella dell'originale (una versione da proteggere) no.
    bench.corruptOnNas(FreeSpaceBench.PRIVATE, edited.privatePaths.getValue(edited.original))
    val done = bench.freeAll(bench.start())
    assertThat(done.dropped).containsExactly(FreeSpaceExclusion.VERSION_NOT_SAFE, 1)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactly(ok.phoneId)
  }

  @Test
  fun ilNasCadeAMetaVerifica_nienteDiQuelGiroArrivaAUnDialogo_eB9() = runTest {
    repeat(4) { bench.safe() }
    val (plan, confirmation) = bench.confirmed()
    // Conta le chiamate di una verifica pulita, poi fa cadere la connessione a meta'.
    val counter = FaultPlan()
    val probe = FreeSpaceBench().also { other -> repeat(4) { other.safe() } }
    val (probePlan, probeConfirmation) = probe.confirmed()
    probe.nas.readFaults = counter
    probe.space.verify(probePlan, probeConfirmation, 1.hours)
    val half = counter.operations / 2
    assertThat(half).isAtLeast(2)

    val fault = FaultPlan().at(half, FaultPhase.BEFORE, SimFault.DropConnection)
    bench.nas.readFaults = fault
    val run = bench.space.verify(plan, confirmation, 1.hours)
    assertThat(fault.fired).hasSize(1)
    assertThat(run.state).isEqualTo(FreeSpaceRunState.BLOCKED)
    assertThat(run.blocked).isEqualTo(FreeSpaceBlock.NAS_UNREACHABLE)
    assertThat(bench.space.nextBatch(run)).isNull()
    assertThat(bench.space.resume(run, 1.hours).state).isEqualTo(FreeSpaceRunState.BLOCKED)
    assertThat(bench.removed).isEmpty()

    // Il NAS torna: un giro nuovo (piano, conferma, verifica) libera tutto.
    bench.nas.readFaults = null
    bench.freeAll(bench.start())
    assertThat(bench.removed).hasSize(4)
  }

  @Test
  fun eB9_conIlNasIrraggiungibileLiberaSpazioNonParte() = runTest {
    bench.safe()
    bench.nas.failWith = NasError.Timeout
    val run = bench.start()
    assertThat(run.state).isEqualTo(FreeSpaceRunState.BLOCKED)
    assertThat(run.blocked).isEqualTo(FreeSpaceBlock.NAS_UNREACHABLE)
    assertThat(bench.space.nextBatch(run)).isNull()
    // E senza l'ora del NAS (nessuna sessione l'ha mai raggiunto) non si comincia nemmeno.
    bench.nas.failWith = null
    bench.clock.nasNow = null
    assertThat(bench.start().blocked).isEqualTo(FreeSpaceBlock.NAS_UNREACHABLE)
  }

  @Test
  fun eB9_ilNasCadeAlControlloFinale_nessunDialogo() = runTest {
    bench.safe()
    val run = bench.start()
    assertThat(run.state).isEqualTo(FreeSpaceRunState.READY)
    bench.nas.failWith = NasError.Timeout
    assertThat(bench.space.nextBatch(run)).isNull()
    assertThat(bench.refresh(run).blocked).isEqualTo(FreeSpaceBlock.NAS_UNREACHABLE)
    assertThat(bench.removed).isEmpty()
  }

  @Test
  fun lOraDelTelefonoEDelNasSpostata_nonCambiaNiente_valeSoloLOrologioMonotono() = runTest {
    repeat(3) { bench.safe() }
    val run = bench.start()
    // Un salto dell'ora (NTP sistemato, l'utente che cambia data): l'ora del NAS va avanti di un anno, poi indietro di due. La validita'
    // della verifica non la guarda: misura solo l'orologio monotono (D31). L'orologio del telefono non lo legge nessuno
    // (`NoWallClockSourcesTest`).
    bench.clock.nasNow = bench.clock.nasNow!! + 365L * 86_400_000
    val first = bench.space.nextBatch(run)!!
    assertThat(bench.batchViolations(first)).isEmpty()
    bench.clock.nasNow = bench.clock.nasNow!! - 2 * 365L * 86_400_000
    // La UI ha perso il lotto e lo richiede: e' lo stesso, ricontrollato, mai un secondo lotto aperto.
    val again = bench.space.nextBatch(run)!!
    assertThat(again.index).isEqualTo(first.index)
    assertThat(again.items.map { it.phone }).containsExactlyElementsIn(first.items.map { it.phone })
    assertThat(bench.refresh(run).state).isEqualTo(FreeSpaceRunState.READY)

    // Il monotono, invece: oltre le 6 ore la verifica non vale piu', qualunque ora dica il NAS.
    bench.clock.monotonic += FreeSpaceRules.VERIFICATION_VALID_MS + 1
    bench.clock.nasNow = bench.clock.nasNow!! - 30L * 86_400_000
    assertThat(bench.space.nextBatch(run)).isNull()
    assertThat(bench.refresh(run).state).isEqualTo(FreeSpaceRunState.EXPIRED)
    // Il dialogo aperto prima della scadenza risponde dopo: la risposta si registra (nessuna traccia persa), il giro resta scaduto.
    val after = bench.space.recordOutcome(again, bench.dialog(again))
    assertThat(after.state).isEqualTo(FreeSpaceRunState.EXPIRED)
    assertThat(after.freedItems).isEqualTo(3)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
  }

  @Test
  fun unaCopiaRovinataDalGuardianoNonSiLibera_eB14() = runTest {
    val common = bench.safe(privateCopy = false, commonCopy = true)
    val ok = bench.safe()
    bench.guardian.mode = BenchGuardian.Mode.CORRUPT
    val done = bench.freeAll(bench.start())
    assertThat(done.guardedTotal).isEqualTo(1)
    assertThat(done.dropped).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactly(ok.phoneId)
    assertThat(bench.phone.ref(common.phoneId!!)).isNotNull()
  }

  @Test
  fun ogniLottoContieneSoloCopieIntegreAdesso_ancheConGuastiMisti() = runTest {
    // Un po' di tutto insieme: il lotto che esce e' sempre integro (verita' del NAS) e mai oltre 2.000 elementi.
    val good = (1..6).map { bench.safe() }
    val bad1 = bench.safe()
    val bad2 = bench.safe()
    val bad3 = bench.safe()
    val (plan, confirmation) = bench.confirmed()
    bench.corruptOnNas(FreeSpaceBench.PRIVATE, bad1.privatePaths.getValue(bad1.current))
    val run = bench.space.verify(plan, confirmation, 1.hours)
    bench.nas.delete(FreeSpaceBench.PRIVATE, bad2.privatePaths.getValue(bad2.current))
    bench.corruptOnNas(FreeSpaceBench.PRIVATE, bad3.privatePaths.getValue(bad3.current))
    // freeAll controlla ogni lotto nel momento in cui esce (FreeSpaceBench.batchViolations).
    val done = bench.freeAll(run)
    assertWithMessage("lotti presentati").that(bench.presented).isNotEmpty()
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactlyElementsIn(good.map { it.phoneId })
    assertThat(done.dropped).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1, FreeSpaceExclusion.COPY_CHANGED, 2)
    // Il telefono ha ancora i byte giusti dei tre esclusi.
    for (m in listOf(bad1, bad2, bad3)) assertThat(bench.phone.sha256(m.phoneId!!)).isEqualTo(Sha256.of(m.data.getValue(m.current)))
  }

  private fun locationOf(made: FreeSpaceBench.Made) = bench.catalog.get(made.id)!!.media.locations.first {
    it.isNas && (it.location as dev.pampa.mycloudgallery.core.model.Location.Nas).share == FreeSpaceBench.PRIVATE
  }.id
}
