package dev.pampa.mycloudgallery.core.faces.switch

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Correzioni dopo le revisioni della Fase 8, pacchetto A.
 *
 * - **R1**: l'interruttore toglie ogni generazione che questo dispositivo ha **sul disco**, non solo quella che `faces-local` ricorda (una scrittura
 *   non riuscita prima di una morte del processo, un file rovinato). Prima, `faces-<G>.db` e la sua chiave restavano per sempre e il dispositivo
 *   "confermava" lo stesso la cancellazione. Vale per la voce spenta (il gancio non lascia uscire una testa `off` finche' i file ci sono), per
 *   l'esclusione e per una nuova attivazione. Sempre solo con una prova: mai l'assenza o una voce che non si capisce.
 * - **CE-3**: il dispositivo che toglie i dati perche' **un altro** ha fatto "Disattiva e cancella" lo ricorda ([dev.pampa.mycloudgallery.core.faces.contract.FacesLocal.offElsewhere]),
 *   per dirlo sopra "Attiva riconoscimento dei volti"; chi ha cancellato non lo porta, e una nuova attivazione lo toglie.
 */
class SwitchDiskGenerationsTest {

  private val rigs = mutableListOf<SwitchRig>()

  private fun rig(): SwitchRig = SwitchRig().also { rigs += it }

  @After
  fun chiudi() = rigs.forEach { it.close() }

  private val g1 = SwitchRig.generation(1)
  private val g2 = SwitchRig.generation(2)

  /**
   * Il tablet B di R1: la voce dell'account e' `on|G`, ma `faces-local` non ricorda niente (la scrittura dell'avviso non e' riuscita, o il file e' rovinato:
   * [dev.pampa.mycloudgallery.core.faces.contract.FacesLocal.EMPTY]), mentre `faces-<G>.db` e la sua chiave sono sul disco.
   */
  private suspend fun tabletWithForgottenData(generation: FaceGeneration = g1): SwitchRig = rig().also {
    it.account.set(FacesSetting.On(generation, 1))
    it.eraser.data += generation
    it.eraser.calls.clear()
  }

  // --- R1: la voce spenta -------------------------------------------------------------------------------------------------------------

  @Test
  fun r1_unaGenerazioneCheFacesLocalNonRicordaSiTogliConLaVoceSpenta_PrimaDiRispondereFatto() = runTest {
    val rig = tabletWithForgottenData()
    assertThat(rig.local.dataGeneration).isNull()
    // Gli altri dispositivi dicono `off|G`; qui la cancellazione non riesce (un file tenuto aperto): il gancio non puo' dire "fatto".
    rig.eraser.succeeds = false
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    assertThat(rig.switch.reconcile(FacesSetting.Off(g1))).isEqualTo(ReconcileOutcome.Failed(DefaultFacesSwitch.ERASE_PENDING))
    assertThat(rig.eraser.data).containsExactly(g1)
    // Appena si riesce, la generazione sparisce dal disco e solo allora il gancio lascia uscire la testa.
    rig.eraser.succeeds = true
    assertThat(rig.switch.reconcile(FacesSetting.Off(g1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.calls.last()).isEqualTo(g1 to LocalEraseWhy.ACCOUNT_OFF)
    assertThat(rig.eraser.data).isEmpty()
  }

  @Test
  fun r1_unaVoceSpentaCheArrivaDalRegistroTogliLaGenerazioneNonRicordata() = runTest {
    val rig = tabletWithForgottenData()
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    assertThat(rig.eraser.calls).containsExactly(g1 to LocalEraseWhy.ACCOUNT_OFF)
    assertThat(rig.eraser.data).isEmpty()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
  }

  @Test
  fun r1_unaGenerazioneNuovaTogliLaVecchiaNonRicordata_MaNonLaNuova() = runTest {
    val rig = tabletWithForgottenData(g1)
    rig.eraser.data += g2
    rig.account.injectRemote(FacesSettingCodec.on(g2, 1))
    // La voce nomina g2 accesa: g1 e' di un'attivazione vecchia e si toglie, g2 non si tocca (se ne occupera' l'avviso).
    assertThat(rig.eraser.calls).containsExactly(g1 to LocalEraseWhy.GENERATION_CHANGED)
    assertThat(rig.eraser.data).containsExactly(g2)
  }

  @Test
  fun r1_nessunaProvaNessunaCancellazione_LaGenerazioneNominataAccesaResta() = runTest {
    val rig = tabletWithForgottenData()
    assertThat(rig.switch.reconcile(FacesSetting.On(g1, 1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.switch.reconcile(null)).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.switch.reconcile(FacesSetting.Unknown)).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.calls).isEmpty()
    assertThat(rig.eraser.data).containsExactly(g1)
  }

  @Test
  fun r1_ilDiscoCheNonSiLeggeNonLasciaPubblicareUnaTestaChiDiceOff() = runTest {
    val rig = tabletWithForgottenData()
    rig.eraser.diskUnreadable = true
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    assertThat(rig.switch.reconcile(FacesSetting.Off(g1))).isEqualTo(ReconcileOutcome.Failed(DefaultFacesSwitch.ERASE_PENDING))
    assertThat(rig.eraser.calls).isEmpty()
    rig.eraser.diskUnreadable = false
    assertThat(rig.switch.reconcile(FacesSetting.Off(g1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.data).isEmpty()
  }

  // --- R1: l'esclusione e l'attivazione -----------------------------------------------------------------------------------------------

  @Test
  fun r1_nonSuQuestoDispositivoTogliAncheLaGenerazioneNonRicordata() = runTest {
    val rig = tabletWithForgottenData()
    rig.switch.excludeThisDevice()
    assertThat(rig.eraser.calls).containsExactly(g1 to LocalEraseWhy.EXCLUDED)
    assertThat(rig.eraser.data).isEmpty()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(g1, HereState.EXCLUDED))
  }

  @Test
  fun r1_unaNuovaAttivazioneTogliLeGenerazioniSulDiscoCheNessunoRicorda() = runTest {
    val rig = rig()
    rig.requirementsMet()
    rig.eraser.data += g1
    val outcome = rig.switch.activate(rig.consent())
    assertThat(outcome).isInstanceOf(ActivateOutcome.Activated::class.java)
    assertThat(rig.eraser.data).doesNotContain(g1)
    assertThat(rig.eraser.calls.first()).isEqualTo(g1 to LocalEraseWhy.GENERATION_CHANGED)
  }

  @Test
  fun r1_ilConsensoSullaGenerazioneDellAccountNonTogliQuellaChePerLeiServe() = runTest {
    val rig = tabletWithForgottenData(g1)
    rig.eraser.data += g2
    rig.switch.acceptNotice(1)
    // g1 e' quella dell'account e resta; l'altra non serve a nessuno e si toglie.
    assertThat(rig.eraser.data).containsExactly(g1)
    assertThat(rig.local.dataGeneration).isEqualTo(g1)
  }

  // --- CE-3: il secondo dispositivo ----------------------------------------------------------------------------------------------------

  private suspend fun deviceWithData(): SwitchRig = rig().also {
    it.account.set(FacesSetting.On(g1, 1))
    it.localStore.update { l -> l.copy(noticedGeneration = g1, noticedTextVersion = 1, dataGeneration = g1) }
    it.eraser.data += g1
    it.eraser.calls.clear()
  }

  @Test
  fun ce3_ilSecondoDispositivoRicordaCheLAltroHaDisattivato() = runTest {
    val rig = deviceWithData()
    assertThat(rig.switch.offElsewhere.value).isFalse()
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(rig.local.offElsewhere).isTrue()
    assertThat(rig.switch.offElsewhere.value).isTrue()
  }

  @Test
  fun ce3_unaNuovaAttivazioneToglieLaRiga() = runTest {
    val rig = deviceWithData()
    rig.requirementsMet()
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    assertThat(rig.switch.offElsewhere.value).isTrue()
    assertThat(rig.switch.activate(rig.consent())).isInstanceOf(ActivateOutcome.Activated::class.java)
    assertThat(rig.local.offElsewhere).isFalse()
    assertThat(rig.switch.offElsewhere.value).isFalse()
  }

  @Test
  fun ce3_chiHaCancellatoNonPortaLaRiga_ilSegnoDiCancellazioneInCorsoLoEscludeEDopoLaFineTuttoETornatoDiSerie() = runTest {
    val rig = deviceWithData()
    // "Disattiva e cancella" fatto QUI: il segno `erasing` e' acceso mentre il gancio vede `off|G` (passi 5 e 6 del diario).
    rig.localStore.update { it.copy(erasing = g1) }
    rig.account.set(FacesSetting.Off(g1))
    assertThat(rig.switch.reconcile(FacesSetting.Off(g1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.data).isEmpty()
    assertThat(rig.local.offElsewhere).isFalse()
    assertThat(rig.switch.offElsewhere.value).isFalse()
  }

  @Test
  fun ce3_senzaDatiSuQuestoDispositivoNonCeNienteDaSpiegare() = runTest {
    val rig = rig()
    rig.account.set(FacesSetting.On(g1, 1))
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(rig.local.offElsewhere).isFalse()
    assertThat(rig.switch.offElsewhere.value).isFalse()
  }

  @Test
  fun ce3_laRigaValeSoloDaSpenta() = runTest {
    val rig = deviceWithData()
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    assertThat(rig.switch.offElsewhere.value).isTrue()
    // Un altro dispositivo riaccende: lo stato non e' piu' `Off` e la riga non si mostra (si vede l'avviso).
    rig.account.injectRemote(FacesSettingCodec.on(g2, 1))
    assertThat(rig.switch.currentState()).isInstanceOf(FacesState.AwaitingNotice::class.java)
    assertThat(rig.switch.offElsewhere.value).isFalse()
  }
}
