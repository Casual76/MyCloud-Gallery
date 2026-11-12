package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason as BackupWait
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * **Fase I (BS-3, BS-4, BF-4)**: chi sceglie il motivo che il foglio, la testata e il piede mostrano ([ExistingCopiesLive]). Le prove del foglio danno il
 * motivo gia' scelto e provano il testo; qui si prova la scelta: quale attivita' si chiede alle regole, quando, e che cosa diventa un verdetto.
 */
class ExistingCopiesLiveTest {

  private val searching = ExistingCopiesState(privateAreaScanned = false, similarPending = 0)
  private val comparing = ExistingCopiesState(privateAreaScanned = true, similarPending = 120)
  private val hot = RuleDecision.Wait(WaitReason.Thermal(ThermalStatus.SEVERE))
  private val run = RuleDecision.Run(network = NetworkClass.HOME_WIFI)

  private fun live(state: ExistingCopiesState, scan: NasScanStatus? = null, awaiting: Boolean = false, decision: RuleDecision = run): Pair<ExistingCopiesState, List<WorkRequest>> {
    val asked = mutableListOf<WorkRequest>()
    val result = runBlocking { ExistingCopiesLive.of(state, scan, awaiting) { request -> asked += request; decision } }
    return result to asked
  }

  @Test
  fun laRicercaChiedeLaScansioneEDiceIFileGuardati() {
    val scan = NasScanStatus.Scanning(mode = ScanMode.Full, foldersDone = 3, filesSeen = 1_234, detected = 0, filesTotal = 5_559, first = true)
    val (state, asked) = live(searching, scan = scan)
    assertThat(asked).containsExactly(ExistingCopiesLive.SEARCH)
    assertThat(state.heldBy).isNull()
    assertThat(state.scanSeen).isEqualTo(1_234)
    assertThat(state.scanTotal).isEqualTo(5_559)
  }

  @Test
  fun laRicercaFermaPerIlCaldoNeDiceIlMotivo_SenzaScanner() {
    val (state, _) = live(searching, scan = NasScanStatus.Idle, decision = hot)
    assertThat(state.heldBy).isEqualTo(BackupWait.THERMAL)
    // Le regole lasciano partire non vuol dire che giri: «gira» lo dice solo lo scanner.
    assertThat(state.scanSeen).isNull()
  }

  /** BF-4: un motivo che il backup non nomina (il congelamento) non sparisce: e' «altro», e la pagina dice che e' fermo. */
  @Test
  fun unMotivoSenzaNomeResta_Altro() {
    val (state, _) = live(searching, decision = RuleDecision.Wait(WaitReason.Frozen(FreezeReason.VANISHED)))
    assertThat(state.heldBy).isEqualTo(BackupWait.NEXT_RUN)
  }

  /** BS-3: il confronto dipende dalla Preparazione solo se aspetta anteprime del NAS; allora (e solo allora) il motivo e' quello della Preparazione. */
  @Test
  fun ilConfrontoChiedeLaPreparazioneSoloSeAspettaAnteprime() {
    val (withPreviews, askedWith) = live(comparing, awaiting = true, decision = hot)
    assertThat(askedWith).containsExactly(ExistingCopiesLive.PREVIEWS)
    assertThat(withPreviews.heldBy).isEqualTo(BackupWait.THERMAL)

    // Pixel gia' sul telefono, firme, accorpamenti, miniature che arrivano dall'indice: niente Preparazione da aspettare, nessun motivo (la riga neutra).
    val (withoutPreviews, askedWithout) = live(comparing, awaiting = false, decision = hot)
    assertThat(askedWithout).isEmpty()
    assertThat(withoutPreviews.heldBy).isNull()
  }

  @Test
  fun aRicercaFinitaENienteDaConfrontareNonSiChiedeNiente() {
    val (state, asked) = live(ExistingCopiesState.SETTLED, awaiting = true, decision = hot)
    assertThat(asked).isEmpty()
    assertThat(state.heldBy).isNull()
    assertThat(state.scanSeen).isNull()
  }

  @Test
  fun unVerdettoCheLasciaPartireNonEUnMotivo() {
    assertThat(ExistingCopiesLive.heldOf(run)).isNull()
    assertThat(ExistingCopiesLive.heldOf(RuleDecision.Stop(WaitReason.PowerSaver))).isEqualTo(BackupWait.POWER_SAVER)
    assertThat(ExistingCopiesLive.heldOf(RuleDecision.Wait(WaitReason.NasUnreachable))).isEqualTo(BackupWait.NAS)
  }
}
