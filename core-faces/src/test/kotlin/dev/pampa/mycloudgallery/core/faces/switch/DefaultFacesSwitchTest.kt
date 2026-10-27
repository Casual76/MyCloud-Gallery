package dev.pampa.mycloudgallery.core.faces.switch

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Ogni riga del §2.1 di `docs/fase8-progetto.md` sull'interruttore VERO (`DefaultFacesSwitch`): attivazione (anche senza l'ora del NAS),
 * requisiti, avviso che blocca ogni trattamento, esclusione che cancella in locale, riattivazione, testo nuovo e voce sconosciuta. Le righe
 * "due attivazioni insieme" e le cancellazioni dell'allineamento sono in `SwitchReconcileTest`, i guasti in `SwitchFaultTest`.
 */
class DefaultFacesSwitchTest {

  private val rigs = mutableListOf<SwitchRig>()

  private fun rig(block: SwitchRig.() -> Unit = {}, build: () -> SwitchRig = { SwitchRig() }): SwitchRig = build().also {
    rigs += it
    it.block()
  }

  @After
  fun chiudi() = rigs.forEach { it.close() }

  // --- Spenta ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun spentaDiSerie_NienteFreschezzaNienteDaDatare() {
    val rig = rig()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
    assertThat(rig.switch.state.value).isEqualTo(FacesState.Off)
    assertThat(rig.switch.fresh.value).isFalse()
    assertThat(rig.switch.syncPending.value).isFalse()
  }

  // --- Requisiti (D5, E-F10) -------------------------------------------------------------------------------------------------------

  @Test
  fun senzaAreaPrivataNonSiAccendeENienteSiScrive() = runTest {
    val rig = rig()
    rig.requirements.privateArea = PrivateAreaState.Missing
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.NoPrivateArea)
    rig.requirements.privateArea = PrivateAreaState.SameAsCommon
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.NoPrivateArea)
    assertThat(rig.account.current()).isNull()
    assertThat(rig.register.writes()).isEqualTo(0L)
    assertThat(rig.local).isEqualTo(FacesLocal.EMPTY)
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
  }

  @Test
  fun senzaIndicizzatoriLaPaginaProponeIlRuolo_ENienteSiScrive() = runTest {
    val rig = rig()
    rig.requirements.indexers = IndexerState(accountIndexers = 0, thisDevice = false, canBecomeIndexer = true)
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.NoIndexer(canBecomeIndexer = true))
    rig.requirements.indexers = IndexerState(accountIndexers = 0, thisDevice = false, canBecomeIndexer = false)
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.NoIndexer(canBecomeIndexer = false))
    assertThat(rig.register.writes()).isEqualTo(0L)
    assertThat(rig.local).isEqualTo(FacesLocal.EMPTY)
    // Accesa la funzione di indicizzatore su questo dispositivo (anche se il suo stato firmato non c'e' ancora) i requisiti ci sono.
    rig.requirements.indexers = IndexerState(accountIndexers = 0, thisDevice = true, canBecomeIndexer = true)
    assertThat(rig.switch.activate(rig.consent())).isInstanceOf(ActivateOutcome.Activated::class.java)
  }

  // --- "Attiva" (D3) -----------------------------------------------------------------------------------------------------------------

  @Test
  fun attivareCreaUnaGenerazioneNuovaEVaSubito() = runTest {
    val rig = rig()
    val expected = rig.nextGeneration()
    val outcome = rig.switch.activate(rig.consent())
    assertThat(outcome).isEqualTo(ActivateOutcome.Activated(expected))
    // Subito, in modo sincrono: lo stato e la voce.
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(expected, HereState.ACTIVE))
    assertThat(rig.switch.state.value).isEqualTo(FacesState.On(expected, HereState.ACTIVE))
    assertThat(rig.switch.currentState().activeHere).isTrue()
    assertThat(rig.account.current()).isEqualTo(FacesSetting.On(expected, 1))
    assertThat(rig.register.read()).isEqualTo(FacesSettingCodec.on(expected, 1))
    // L'avviso di questa generazione e' gia' visto (chi attiva ha appena letto la pagina) e i dati si ricordano.
    assertThat(rig.local).isEqualTo(FacesLocal(noticedGeneration = expected, noticedTextVersion = 1, dataGeneration = expected))
    assertThat(rig.eraser.calls).isEmpty()
  }

  /** Riga "Attiva": vale subito anche senza l'ora del NAS; la voce resta "da datare" e prende l'ora alla prima sincronizzazione. */
  @Test
  fun senzaLOraDelNasLAttivazioneValeSubitoERestaDaDatare() = runTest {
    val rig = rig()
    rig.account.online = false
    val generation = rig.nextGeneration()
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.Activated(generation))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
    assertThat(rig.switch.syncPending.value).isTrue()
    assertThat(rig.register.writes()).isEqualTo(0L)

    rig.account.online = true
    rig.account.sync()
    assertThat(rig.switch.syncPending.value).isFalse()
    assertThat(rig.register.read()).isEqualTo(FacesSettingCodec.on(generation, 1))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
  }

  @Test
  fun sulleShareVereLaRecinzioneDaLocalOnly() = runTest {
    val rig = rig()
    rig.requirements.localOnly = true
    val generation = rig.nextGeneration()
    rig.switch.activate(rig.consent())
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.LOCAL_ONLY))
    assertThat(rig.switch.currentState().activeHere).isTrue()
    // Si accende anche dopo: la recinzione si rilegge.
    rig.requirements.localOnly = false
    rig.switch.refreshRequirements()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
  }

  @Test
  fun unConsensoDiUnAltroTestoNonAccendeNiente() = runTest {
    val rig = rig()
    assertThat(rig.switch.activate(rig.consent(version = 2))).isEqualTo(ActivateOutcome.StaleConsent)
    assertThat(rig.register.writes()).isEqualTo(0L)
    assertThat(rig.local).isEqualTo(FacesLocal.EMPTY)
  }

  @Test
  fun unDoppioTocco_OLAccountGiaAcceso_NonCreaUnaSecondaGenerazione() = runTest {
    val rig = rig()
    val first = rig.nextGeneration()
    val a = rig.switch.activate(rig.consent())
    val b = rig.switch.activate(rig.consent())
    assertThat(a).isEqualTo(ActivateOutcome.Activated(first))
    assertThat(b).isEqualTo(ActivateOutcome.Activated(first))
    assertThat(rig.account.current()).isEqualTo(FacesSetting.On(first, 1))
    assertThat(rig.register.writes()).isEqualTo(1L)
    assertThat(rig.eraser.calls).isEmpty()
  }

  /** E-F33: dopo "Disattiva e cancella" la riattivazione e' una generazione nuova; i dati della vecchia non tornano. */
  @Test
  fun riattivareDopoUnaCancellazioneECreaUnaGenerazioneNuova() = runTest {
    val rig = rig()
    val first = rig.nextGeneration()
    rig.switch.activate(rig.consent())
    rig.eraser.data += first
    // "Disattiva e cancella" (P8) ha spento l'account: la voce e' `off|G`.
    rig.account.set(FacesSetting.Off(first))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.Off)
    // L'allineamento ha tolto i dati della generazione spenta.
    assertThat(rig.eraser.calls).containsExactly(first to LocalEraseWhy.ACCOUNT_OFF)
    assertThat(rig.eraser.data).isEmpty()

    val second = rig.nextGeneration()
    assertThat(second).isNotEqualTo(first)
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.Activated(second))
    assertThat(rig.account.current()).isEqualTo(FacesSetting.On(second, 1))
    assertThat(rig.local.dataGeneration).isEqualTo(second)
    assertThat(rig.local.noticedGeneration).isEqualTo(second)
  }

  @Test
  fun attivareConDatiDiUnaGenerazioneVecchiaLiToglieEPrima() = runTest {
    val rig = rig()
    val old = SwitchRig.generation(77)
    rig.localStore.update { it.copy(noticedGeneration = old, noticedTextVersion = 1, dataGeneration = old) }
    rig.eraser.data += old
    val generation = rig.nextGeneration()
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.Activated(generation))
    assertThat(rig.eraser.calls.first()).isEqualTo(old to LocalEraseWhy.GENERATION_CHANGED)
    assertThat(rig.eraser.data).isEmpty()
    assertThat(rig.local.dataGeneration).isEqualTo(generation)
  }

  @Test
  fun seLaCancellazioneDeiVecchiDatiNonRiesceLaGenerazioneNuovaNonNasce() = runTest {
    val rig = rig()
    val old = SwitchRig.generation(77)
    rig.localStore.update { it.copy(noticedGeneration = old, noticedTextVersion = 1, dataGeneration = old) }
    rig.eraser.succeeds = false
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.Unavailable)
    assertThat(rig.account.current()).isNull()
    assertThat(rig.register.writes()).isEqualTo(0L)
    assertThat(rig.local.dataGeneration).isEqualTo(old)
  }

  // --- Altri dispositivi dell'account (D4, E-F7) ---------------------------------------------------------------------------------------

  @Test
  fun unAltroDispositivoAspettaLAvviso_NienteScaricatoCalcolatoOMostrato() = runTest {
    val phone = rig()
    val tablet = rig(build = { SwitchRig(register = phone.register, name = "B", seed = 2) })
    val generation = phone.nextGeneration()
    phone.switch.activate(phone.consent())
    // Il tablet riceve la voce al primo giro dei dati personali.
    tablet.account.pull()
    val state = tablet.switch.currentState()
    assertThat(state).isEqualTo(FacesState.AwaitingNotice(generation, 1))
    assertThat(state.activeHere).isFalse()
    // Finche' non si vede l'avviso: nessun dato ricordato, nessuna cancellazione, nessuna scrittura, niente da fare per chi calcola o scarica.
    assertThat(tablet.local).isEqualTo(FacesLocal.EMPTY)
    assertThat(tablet.eraser.calls).isEmpty()
    assertThat(tablet.register.writes()).isEqualTo(1L)
    assertThat(tablet.switch.state.value).isEqualTo(state)
  }

  @Test
  fun vaBeneChiudeLAvvisoEAttivaIlDispositivo_SenzaGenerazioneNuova() = runTest {
    val phone = rig()
    val tablet = rig(build = { SwitchRig(register = phone.register, name = "B", seed = 2) })
    val generation = phone.nextGeneration()
    phone.switch.activate(phone.consent())
    tablet.account.pull()

    tablet.switch.acceptNotice(1)
    assertThat(tablet.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
    assertThat(tablet.local).isEqualTo(FacesLocal(noticedGeneration = generation, noticedTextVersion = 1, dataGeneration = generation))
    // L'avviso e' una tantum e non cambia la voce dell'account.
    assertThat(tablet.register.writes()).isEqualTo(1L)
    assertThat(tablet.account.current()).isEqualTo(FacesSetting.On(generation, 1))
  }

  @Test
  fun unAvvisoDiUnAltroTestoOSenzaAvvisoDaVedereNonFaNiente() = runTest {
    val phone = rig()
    val tablet = rig(build = { SwitchRig(register = phone.register, name = "B", seed = 2) })
    phone.switch.activate(phone.consent())
    tablet.account.pull()
    tablet.switch.acceptNotice(2)
    assertThat(tablet.switch.currentState()).isInstanceOf(FacesState.AwaitingNotice::class.java)
    // Su un dispositivo che non aspetta niente "Va bene" non fa nulla.
    phone.switch.acceptNotice(1)
    assertThat(phone.local.dataGeneration).isNotNull()
    val before = phone.local
    phone.switch.acceptNotice(1)
    assertThat(phone.local).isEqualTo(before)
  }

  @Test
  fun lAttivazioneFattaAltroveConLaPaginaSullAvvisoVaCometoccarVaBene() = runTest {
    val phone = rig()
    val tablet = rig(build = { SwitchRig(register = phone.register, name = "B", seed = 2) })
    val generation = phone.nextGeneration()
    phone.switch.activate(phone.consent())
    tablet.account.pull()
    // Il tablet apre la pagina di attivazione (non l'avviso) e tocca "Attiva": l'account e' gia' acceso, niente generazione nuova.
    assertThat(tablet.switch.activate(tablet.consent())).isEqualTo(ActivateOutcome.Activated(generation))
    assertThat(tablet.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
    assertThat(tablet.register.writes()).isEqualTo(1L)
  }

  // --- Esclusione (D4, E-F8) ----------------------------------------------------------------------------------------------------------

  @Test
  fun nonSuQuestoDispositivoCancellaInLocale_NonCambiaLAccount() = runTest {
    val rig = rig()
    val generation = rig.nextGeneration()
    rig.switch.activate(rig.consent())
    rig.eraser.data += generation
    val voice = rig.register.read()
    val writes = rig.register.writes()

    rig.switch.excludeThisDevice()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.EXCLUDED))
    assertThat(rig.switch.currentState().activeHere).isFalse()
    assertThat(rig.eraser.calls).containsExactly(generation to LocalEraseWhy.EXCLUDED)
    assertThat(rig.eraser.data).isEmpty()
    assertThat(rig.local.excluded).isTrue()
    assertThat(rig.local.dataGeneration).isNull()
    // L'esclusione e' locale: mai una voce, il NAS e gli altri dispositivi non cambiano.
    assertThat(rig.register.read()).isEqualTo(voice)
    assertThat(rig.register.writes()).isEqualTo(writes)
  }

  @Test
  fun siPuoEscludereAncheDallAvviso_NienteDaCancellare() = runTest {
    val phone = rig()
    val tablet = rig(build = { SwitchRig(register = phone.register, name = "B", seed = 2) })
    val generation = phone.nextGeneration()
    phone.switch.activate(phone.consent())
    tablet.account.pull()
    tablet.switch.excludeThisDevice()
    assertThat(tablet.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.EXCLUDED))
    assertThat(tablet.eraser.calls).isEmpty()
    assertThat(tablet.register.writes()).isEqualTo(1L)
    // Un dispositivo escluso non vede piu' avvisi per la stessa generazione.
    assertThat(tablet.switch.currentState().activeHere).isFalse()
  }

  @Test
  fun lEsclusioneRestaAnchePerUnaGenerazioneNuova() = runTest {
    val phone = rig()
    val tablet = rig(build = { SwitchRig(register = phone.register, name = "B", seed = 2) })
    phone.switch.activate(phone.consent())
    tablet.account.pull()
    tablet.switch.excludeThisDevice()
    // L'account viene spento e riacceso altrove (generazione nuova): il tablet resta fuori finche' l'utente non dice "Attiva qui".
    phone.account.set(FacesSetting.Off(phone.local.dataGeneration!!))
    val second = phone.nextGeneration()
    phone.switch.activate(phone.consent())
    tablet.account.pull()
    assertThat(tablet.switch.currentState()).isEqualTo(FacesState.On(second, HereState.EXCLUDED))
  }

  // --- Riattivare qui -------------------------------------------------------------------------------------------------------------------

  @Test
  fun attivaQuiNonCreaGenerazioniNuove_SiRiprendeLAvvisoGiaVisto() = runTest {
    val rig = rig()
    val generation = rig.nextGeneration()
    rig.switch.activate(rig.consent())
    rig.switch.excludeThisDevice()
    val writes = rig.register.writes()

    rig.switch.includeThisDevice()
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
    assertThat(rig.local.excluded).isFalse()
    assertThat(rig.local.dataGeneration).isEqualTo(generation)
    assertThat(rig.register.writes()).isEqualTo(writes)
    assertThat(rig.account.current()).isEqualTo(FacesSetting.On(generation, 1))
  }

  @Test
  fun attivaQuiSenzaAvvisoVistoPassaDalLAvviso() = runTest {
    val phone = rig()
    val tablet = rig(build = { SwitchRig(register = phone.register, name = "B", seed = 2) })
    val generation = phone.nextGeneration()
    phone.switch.activate(phone.consent())
    tablet.account.pull()
    tablet.switch.excludeThisDevice()
    tablet.switch.includeThisDevice()
    assertThat(tablet.switch.currentState()).isEqualTo(FacesState.AwaitingNotice(generation, 1))
    assertThat(tablet.local.dataGeneration).isNull()
    tablet.switch.acceptNotice(1)
    assertThat(tablet.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
  }

  @Test
  fun attivaQuiSuUnDispositivoNonEscluso_NonFaNiente() = runTest {
    val rig = rig()
    rig.switch.activate(rig.consent())
    val before = rig.local
    rig.switch.includeThisDevice()
    assertThat(rig.local).isEqualTo(before)
  }

  @Test
  fun attivareAdessoDaUnDispositivoEscluso_LoRiaccende() = runTest {
    val phone = rig()
    val tablet = rig(build = { SwitchRig(register = phone.register, name = "B", seed = 2) })
    val generation = phone.nextGeneration()
    phone.switch.activate(phone.consent())
    tablet.account.pull()
    tablet.switch.excludeThisDevice()
    assertThat(tablet.switch.activate(tablet.consent())).isEqualTo(ActivateOutcome.Activated(generation))
    assertThat(tablet.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
  }

  // --- Testo cambiato ---------------------------------------------------------------------------------------------------------------

  @Test
  fun conUnTestoNuovoTuttiTornanoAllAvviso_IlCalcoloSiFermaMaIDatiRestano() = runTest {
    val old = rig()
    val generation = old.nextGeneration()
    old.switch.activate(old.consent())
    old.eraser.data += generation
    // L'app si aggiorna: stessa voce, stesso file locale, testo 2.
    old.close()
    val newer = rig(build = {
      SwitchRig(register = old.register, name = "A", account = old.account, localStore = old.localStore, eraser = old.eraser, textVersion = 2)
    })
    assertThat(newer.switch.currentState()).isEqualTo(FacesState.AwaitingNotice(generation, 2))
    assertThat(newer.switch.currentState().activeHere).isFalse()
    assertThat(newer.eraser.calls).isEmpty()
    assertThat(newer.eraser.data).containsExactly(generation)
    assertThat(newer.local.dataGeneration).isEqualTo(generation)

    newer.switch.acceptNotice(2)
    assertThat(newer.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
    // L'account passa al testo nuovo (stessa generazione): anche gli altri dispositivi tornano all'avviso.
    assertThat(newer.account.current()).isEqualTo(FacesSetting.On(generation, 2))
    assertThat(newer.eraser.calls).isEmpty()
  }

  @Test
  fun unDispositivoConLAppVecchiaNonCapisceUnTestoPiuNuovo() = runTest {
    val newer = rig(build = { SwitchRig(textVersion = 2) })
    newer.switch.activate(newer.consent(version = 2))
    val older = rig(build = { SwitchRig(register = newer.register, name = "B", seed = 2) })
    older.account.pull()
    assertThat(older.switch.currentState()).isEqualTo(FacesState.NeedsUpdate)
    assertThat(older.switch.activate(older.consent())).isEqualTo(ActivateOutcome.NeedsUpdate)
    assertThat(older.register.writes()).isEqualTo(1L)
    assertThat(older.local).isEqualTo(FacesLocal.EMPTY)
  }

  // --- Voce sconosciuta (E-F12) ------------------------------------------------------------------------------------------------------

  @Test
  fun unaVoceSconosciutaEDaAggiornare_NienteSiAccendeNienteSiCancella() = runTest {
    val rig = rig()
    val generation = rig.nextGeneration()
    rig.switch.activate(rig.consent())
    rig.eraser.data += generation
    rig.account.injectRemote(PersonalValue.Text("2|on|${generation.base32}|9|forse"))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.NeedsUpdate)
    assertThat(rig.switch.currentState().activeHere).isFalse()
    assertThat(rig.switch.activate(rig.consent())).isEqualTo(ActivateOutcome.NeedsUpdate)
    assertThat(rig.switch.reconcile(FacesSetting.Unknown)).isEqualTo(ReconcileOutcome.Done)
    // Nessuna cancellazione: ne' dei dati ne' dello stato locale; nessuna scrittura della voce.
    assertThat(rig.eraser.calls).isEmpty()
    assertThat(rig.eraser.data).containsExactly(generation)
    assertThat(rig.local.dataGeneration).isEqualTo(generation)
    assertThat(rig.register.read()).isEqualTo(PersonalValue.Text("2|on|${generation.base32}|9|forse"))
  }

  @Test
  fun quandoLaVoceTornaLeggibileIDatiCeranoAncora() = runTest {
    val rig = rig()
    val generation = rig.nextGeneration()
    rig.switch.activate(rig.consent())
    rig.account.injectRemote(PersonalValue.Text("2|boh"))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.NeedsUpdate)
    rig.account.injectRemote(FacesSettingCodec.on(generation, 1))
    assertThat(rig.switch.currentState()).isEqualTo(FacesState.On(generation, HereState.ACTIVE))
    assertThat(rig.eraser.calls).isEmpty()
  }
}
