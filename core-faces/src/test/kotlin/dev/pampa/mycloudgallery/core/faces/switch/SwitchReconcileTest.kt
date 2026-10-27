package dev.pampa.mycloudgallery.core.faces.switch

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.testing.FacesRegister
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryFacesPersonalSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * L'allineamento dei dati locali con la voce dell'account (D30, `FacesSwitch.reconcile`, il gancio prima di ogni pubblicazione), le due
 * attivazioni quasi insieme (E-F9), il dispositivo spento che torna (E-F32), la freschezza (D30) e la fermata. Il principio che tutte tengono:
 * **solo una prova esplicita toglie dati** (voce spenta, altra generazione, esclusione dell'utente), mai l'assenza o una forma sconosciuta.
 */
class SwitchReconcileTest {

  private val rigs = mutableListOf<SwitchRig>()

  private fun rig(build: () -> SwitchRig = { SwitchRig() }): SwitchRig = build().also { rigs += it }

  @After
  fun chiudi() = rigs.forEach { it.close() }

  private val g1 = SwitchRig.generation(1)
  private val g2 = SwitchRig.generation(2)

  /** Un dispositivo con Persone attiva sulla generazione [generation] e i dati locali di quella generazione. */
  private suspend fun withData(generation: FaceGeneration, build: () -> SwitchRig = { SwitchRig() }): SwitchRig = rig(build).also {
    it.account.set(FacesSetting.On(generation, 1))
    it.localStore.update { l -> l.copy(noticedGeneration = generation, noticedTextVersion = 1, dataGeneration = generation) }
    it.eraser.data += generation
    it.eraser.calls.clear()
  }

  /**
   * La voce [evidence] arriva dal registro ma l'allineamento che parte da solo non riesce a togliere i dati (la cancellazione fallisce): i dati
   * restano, ricordati, e il gancio prima della pubblicazione e' l'ultima garanzia (D30). Dopo la chiamata la cancellazione funziona di nuovo.
   */
  private fun SwitchRig.registerSays(evidence: FacesSetting) {
    eraser.succeeds = false
    account.injectRemote(FacesSettingCodec.valueOf(evidence))
    eraser.calls.clear()
    eraser.succeeds = true
  }

  // --- reconcile: cosa toglie ----------------------------------------------------------------------------------------------------------

  @Test
  fun laVoceSpentaTogliIDatiDiQuellaGenerazione_ChiaveEDatiPoiLoStatoEOff() = runTest {
    val rig = withData(g1)
    rig.registerSays(FacesSetting.Off(g1))
    assertThat(rig.local.dataGeneration).isEqualTo(g1)
    assertThat(rig.switch.reconcile(FacesSetting.Off(g1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.calls).containsExactly(g1 to LocalEraseWhy.ACCOUNT_OFF)
    assertThat(rig.eraser.data).isEmpty()
    assertThat(rig.local.dataGeneration).isNull()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
  }

  /** Senza il gancio: la voce che cambia nel registro (la sincronizzazione porta la scelta degli altri) allinea i dati da sola. */
  @Test
  fun laVoceCheArrivaDalRegistroAllineaDaSola() = runTest {
    val rig = withData(g1)
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    assertThat(rig.eraser.calls).containsExactly(g1 to LocalEraseWhy.ACCOUNT_OFF)
    assertThat(rig.local.dataGeneration).isNull()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
  }

  @Test
  fun unaGenerazioneNuovaTogliIDatiDellaVecchia_EIlDispositivoAspettaLAvviso() = runTest {
    val rig = withData(g1)
    rig.registerSays(FacesSetting.On(g2, 1))
    assertThat(rig.switch.reconcile(FacesSetting.On(g2, 1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.calls).containsExactly(g1 to LocalEraseWhy.GENERATION_CHANGED)
    assertThat(rig.local.dataGeneration).isNull()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.AwaitingNotice(g2, 1))
  }

  @Test
  fun laStessaGenerazioneNonTogliNiente() = runTest {
    val rig = withData(g1)
    assertThat(rig.switch.reconcile(FacesSetting.On(g1, 1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.calls).isEmpty()
    assertThat(rig.eraser.data).containsExactly(g1)
  }

  /** Voce assente (uno store non ancora aperto, un registro azzerato) o sconosciuta (un'app piu' nuova): nessuna prova, nessuna cancellazione. */
  @Test
  fun unaVoceAssenteOSconosciutaNonTogliMaiNiente() = runTest {
    // Dati ricordati, ma la voce dell'account non c'e' (ancora): niente la dice spenta.
    val rig = rig()
    rig.localStore.update { it.copy(noticedGeneration = g1, noticedTextVersion = 1, dataGeneration = g1) }
    rig.eraser.data += g1
    assertThat(rig.switch.reconcile(null)).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.switch.reconcile(FacesSetting.Unknown)).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.calls).isEmpty()
    assertThat(rig.eraser.data).containsExactly(g1)
    assertThat(rig.local.dataGeneration).isEqualTo(g1)
    // Neppure la voce sconosciuta che arriva dal registro (un'app piu' nuova) fa cancellare: nemmeno da sola.
    rig.account.injectRemote(PersonalValue.Text("3|on|nuova|forma"))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.NeedsUpdate)
    assertThat(rig.eraser.calls).isEmpty()
    assertThat(rig.local.dataGeneration).isEqualTo(g1)
  }

  @Test
  fun alVoltoDelloStoreVuotoAllAvvioNonSiCancellaNiente() = runTest {
    // Il processo riparte: lo stato locale ricorda i dati, ma la voce dei dati personali non e' ancora stata letta (appare vuota).
    val first = withData(g1)
    first.close()
    val restarted = rig {
      SwitchRig(
        register = first.register, name = "A2", localStore = first.localStore, eraser = first.eraser,
        account = InMemoryFacesPersonalSettings(FacesRegister(), "A2"),
      )
    }
    assertThat(restarted.account.current()).isNull()
    assertThat(restarted.switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(restarted.eraser.calls).isEmpty()
    assertThat(restarted.local.dataGeneration).isEqualTo(g1)
  }

  /** L'esclusione e' una decisione dell'utente: toglie i dati rimasti anche se la voce e' sconosciuta o assente. */
  @Test
  fun unaEsclusioneInterrottaSiFinisceDiTogliere_QualunqueSiaLaVoce() = runTest {
    for (evidence in listOf(null, FacesSetting.Unknown, FacesSetting.On(g1, 1))) {
      val rig = withData(g1)
      rig.localStore.update { it.copy(excluded = true) }
      assertThat(rig.switch.reconcile(evidence)).isEqualTo(ReconcileOutcome.Done)
      assertThat(rig.eraser.calls).containsExactly(g1 to LocalEraseWhy.EXCLUDED)
      assertThat(rig.local.dataGeneration).isNull()
    }
  }

  /** Un argomento piu' vecchio di una scelta fatta qui (la scelta e' ancora "da datare") non cancella i dati appena nati. */
  @Test
  fun unArgomentoPiuVecchioDellaScelta_NonCancellaIDatiAppenaNati() = runTest {
    val rig = rig()
    rig.account.online = false
    val generation = rig.nextGeneration()
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.Activated(generation))
    rig.eraser.data += generation
    assertThat(rig.switch.syncPending.value).isTrue()

    // Lo stato che il gancio sta per pubblicare e' quello di prima: spento, o una generazione precedente.
    assertThat(rig.switch.reconcile(FacesSetting.Off(g1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.switch.reconcile(FacesSetting.On(g2, 1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.calls).isEmpty()
    assertThat(rig.eraser.data).containsExactly(generation)
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
  }

  // --- reconcile: quando non riesce ------------------------------------------------------------------------------------------------------

  @Test
  fun seLaCancellazioneNonRiesceIlGiroNonPubblica_ESiRiprova() = runTest {
    val rig = withData(g1)
    rig.eraser.succeeds = false
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    val outcome = rig.switch.reconcile(FacesSetting.Off(g1))
    assertThat(outcome).isEqualTo(ReconcileOutcome.Failed(DefaultFacesSwitch.ERASE_PENDING))
    // La generazione resta ricordata: nessun dato dimenticato senza che qualcuno l'abbia tolto.
    assertThat(rig.local.dataGeneration).isEqualTo(g1)
    rig.eraser.succeeds = true
    assertThat(rig.switch.reconcile(FacesSetting.Off(g1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.data).isEmpty()
    assertThat(rig.local.dataGeneration).isNull()
  }

  @Test
  fun unaCancellazioneCheLanciaEUnFallimento_MaUnaCancellazioneDelProcessoNo() = runTest {
    val rig = withData(g1)
    rig.eraser.throws = IllegalStateException("disco")
    rig.account.injectRemote(FacesSettingCodec.off(g1))
    assertThat(rig.switch.reconcile(FacesSetting.Off(g1))).isInstanceOf(ReconcileOutcome.Failed::class.java)
    rig.eraser.throws = CancellationException("fermato")
    val failure = runCatching { rig.switch.reconcile(FacesSetting.Off(g1)) }.exceptionOrNull()
    assertThat(failure).isInstanceOf(CancellationException::class.java)
    assertThat(rig.local.dataGeneration).isEqualTo(g1)
  }

  @Test
  fun ilCodiceDelGanciaEQuelloDelLaDiagnosticaSenzaDati() {
    assertThat(DefaultFacesSwitch.ERASE_PENDING).matches("^[A-Z][A-Z0-9_]{0,47}$")
  }

  // --- Dispositivo spento che torna (E-F7, E-F32) -----------------------------------------------------------------------------------------

  /**
   * Il tablet ha i dati di G1 e resta spento; il telefono "Disattiva e cancella" (voce `off|G1`). Quando il tablet torna, al primo giro dei dati
   * personali **cancella prima**, e la voce che pubblica dopo non lascia dati di G1 su di lui (D30).
   */
  @Test
  fun ilDispositivoSpentoCheTornaCancellaPrimaDiPubblicare() = runTest {
    val phone = withData(g1)
    val tablet = withData(g1) { SwitchRig(register = phone.register, name = "B", seed = 2) }
    phone.account.set(FacesSetting.Off(g1))
    // Il telefono ha cancellato i suoi; il tablet era spento e non lo sa.
    assertThat(tablet.eraser.data).containsExactly(g1)

    val published = mutableListOf<FacesSetting?>()
    suspend fun round() {
      tablet.account.pull() // legge la voce nuova
      // Il gancio prima della pubblicazione: se non riesce, quel giro non pubblica.
      if (tablet.switch.reconcile(tablet.account.current()) == ReconcileOutcome.Done) {
        assertThat(tablet.eraser.data).isEmpty() // ...e la testa che esce non lascia dati di G1 su questo dispositivo.
        published += tablet.account.current()
      }
    }
    round()
    assertThat(published).containsExactly(FacesSetting.Off(g1))
    assertThat(tablet.switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(tablet.local.dataGeneration).isNull()
  }

  // --- Due attivazioni quasi insieme (E-F9) ------------------------------------------------------------------------------------------

  @Test
  fun dueAttivazioniQuasiInsieme_VinceLaPiuRecente_LAltraGenerazioneSiCancella() = runTest {
    val register = FacesRegister()
    val phone = rig { SwitchRig(register = register, name = "A", seed = 1) }
    val tablet = rig { SwitchRig(register = register, name = "B", seed = 2) }
    phone.account.online = false
    tablet.account.online = false
    val gPhone = phone.nextGeneration()
    val gTablet = tablet.nextGeneration()
    phone.switch.activate(phone.consent())
    tablet.switch.activate(tablet.consent())
    phone.eraser.data += gPhone
    tablet.eraser.data += gTablet
    // Fuori casa tutti e due: ognuno e' attivo con la sua generazione.
    assertThat(phone.switch.currentState()).isEqualTo(FacesState.On(gPhone, HereState.ACTIVE))
    assertThat(tablet.switch.currentState()).isEqualTo(FacesState.On(gTablet, HereState.ACTIVE))

    // Prima il telefono torna a casa, poi il tablet: l'ora del NAS piu' recente e' quella del tablet.
    phone.account.online = true
    phone.account.sync()
    tablet.account.online = true
    tablet.account.sync()
    phone.account.sync()

    assertThat(phone.account.current()).isEqualTo(FacesSetting.On(gTablet, 1))
    assertThat(tablet.account.current()).isEqualTo(FacesSetting.On(gTablet, 1))
    // Il tablet resta com'e'; il telefono perde la sua generazione: dati cancellati (chiave per prima), avviso per la generazione vincente.
    assertThat(tablet.switch.currentState()).isEqualTo(FacesState.On(gTablet, HereState.ACTIVE))
    assertThat(tablet.eraser.calls).isEmpty()
    assertThat(phone.eraser.calls).containsExactly(gPhone to LocalEraseWhy.GENERATION_CHANGED)
    assertThat(phone.eraser.data).isEmpty()
    assertThat(phone.switch.currentState()).isEqualTo(FacesState.AwaitingNotice(gTablet, 1))
    assertThat(phone.switch.currentState().activeHere).isFalse()
  }

  // --- Freschezza (D30) -----------------------------------------------------------------------------------------------------------------

  @Test
  fun laFreschezzaVieneDaUnGiroCompletoEScadeSulTempoMonotono() = runTest {
    val rig = rig()
    rig.switch.activate(rig.consent())
    assertThat(rig.switch.fresh.value).isFalse()
    rig.switch.onPersonalRoundCompleted()
    assertThat(rig.switch.fresh.value).isTrue()
    rig.clock.advanceMillis(FacesSwitch.FRESH_MILLIS - 1)
    assertThat(rig.switch.fresh.value).isTrue()
    // Letta al momento: nessun timer e' scattato (il telefono ha dormito), ma il tempo monotono dice che il giro e' vecchio.
    rig.clock.advanceMillis(1)
    assertThat(rig.switch.fresh.value).isFalse()
  }

  @Test
  fun unaScelteFattaQuiSpegneLaFreschezzaFinoAlGiroDopo() = runTest {
    val rig = rig()
    rig.switch.onPersonalRoundCompleted()
    assertThat(rig.switch.fresh.value).isTrue()
    val generation = rig.nextGeneration()
    rig.switch.activate(rig.consent())
    assertThat(rig.switch.fresh.value).isFalse()
    rig.switch.onPersonalRoundCompleted()
    assertThat(rig.switch.fresh.value).isTrue()
    // La voce cambia da un altro dispositivo (generazione nuova a meta' di un lavoro): lo stato non e' piu' quello che il giro ha visto.
    rig.account.injectRemote(FacesSettingCodec.on(g2, 1))
    assertThat(rig.switch.fresh.value).isFalse()
    assertThat(rig.switch.currentState().generationOrNull).isEqualTo(g2)
    assertThat(rig.switch.currentState().generationOrNull).isNotEqualTo(generation)
  }

  @Test
  fun dopoUnRiavvioNonCeFreschezzaFinoAlPrimoGiro() = runTest {
    val first = rig()
    first.switch.activate(first.consent())
    first.switch.onPersonalRoundCompleted()
    assertThat(first.switch.fresh.value).isTrue()
    first.close()
    val second = rig {
      SwitchRig(register = first.register, name = "A", account = first.account, localStore = first.localStore, eraser = first.eraser, clock = first.clock)
    }
    assertThat(second.switch.fresh.value).isFalse()
    second.switch.onPersonalRoundCompleted()
    assertThat(second.switch.fresh.value).isTrue()
  }

  @Test
  fun chiRaccogliLaFreschezzaRiceveIlValoreNuovo() = runTest {
    val rig = rig()
    rig.switch.fresh.test {
      assertThat(awaitItem()).isFalse()
      rig.switch.onPersonalRoundCompleted()
      assertThat(awaitItem()).isTrue()
      rig.account.injectRemote(FacesSettingCodec.on(g1, 1))
      assertThat(awaitItem()).isFalse()
      cancelAndIgnoreRemainingEvents()
    }
  }

  // --- Cancellazione in corso e fermata -----------------------------------------------------------------------------------------------

  @Test
  fun conUnaCancellazioneInCorsoNonSiRiattiva() = runTest {
    val rig = rig()
    rig.switch.activate(rig.consent())
    rig.localStore.update { it.copy(erasing = rig.local.dataGeneration) }
    assertThat(rig.switch.currentState()).isInstanceOf(FacesState.Erasing::class.java)
    assertThat(rig.switch.currentState().activeHere).isFalse()
    val writes = rig.register.writes()
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.Unavailable)
    assertThat(rig.register.writes()).isEqualTo(writes)
  }

  @Test
  fun fermatoLInterruttoreNonFaPiuNiente_LoStatoLocaleRestaPerChiAzzera() = runTest {
    val rig = rig()
    rig.switch.activate(rig.consent())
    val local = rig.local
    rig.switch.stop()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(rig.switch.state.value).isEqualTo(FacesState.Off)
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.Unavailable)
    rig.switch.excludeThisDevice()
    assertThat(rig.switch.reconcile(FacesSetting.Off(g1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(rig.eraser.calls).isEmpty()
    assertThat(rig.local).isEqualTo(local)
    assertThat(rig.switch.fresh.value).isFalse()
  }

  @Test
  fun ilToStringDelloStatoLocaleNonMostraValori() {
    assertThat(FacesLocal(excluded = true, noticedGeneration = g1, dataGeneration = g1).toString()).doesNotContain(g1.base32)
  }
}
