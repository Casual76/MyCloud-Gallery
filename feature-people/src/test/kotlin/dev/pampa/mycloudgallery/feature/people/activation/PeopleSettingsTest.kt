package dev.pampa.mycloudgallery.feature.people.activation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.FacesPhase
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.feature.people.contract.ComputingDevice
import dev.pampa.mycloudgallery.feature.people.contract.EraseSheetState
import dev.pampa.mycloudgallery.feature.people.contract.FaceModelRow
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSettingsState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSwitchShown
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Impostazioni -> Persone (docs/fase8-progetto.md §1.5 `PeopleSettingsState`, §2.1, D4, D34; riga P7 del §4.4): le regole che portano lo stato dell'interruttore
 * a cio' che si mostra e si offre, il controllore sull'interruttore finto, e la pagina (testi, esclusione con la sua conferma, modelli e licenze,
 * "Disattiva e cancella", accessibilita').
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class PeopleSettingsTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  // --- Le regole ---------------------------------------------------------------------------------------------------------------------

  /** Porta in vista la riga con questo testo: la pagina e' una lista pigra, e cio' che sta sotto lo schermo non esiste finche' non ci si arriva scorrendo. */
  private fun node(label: String, substring: Boolean = false): SemanticsNodeInteraction {
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label, substring = substring))
    return rule.onNodeWithText(label, substring = substring)
  }

  @Test
  fun ogniStatoDellInterruttoreHaLaSuaRiga() {
    val g = sampleGeneration
    assertThat(PeopleSettingsRules.shownOf(FacesState.Off)).isEqualTo(PeopleSwitchShown.OFF)
    assertThat(PeopleSettingsRules.shownOf(FacesState.On(g, HereState.ACTIVE))).isEqualTo(PeopleSwitchShown.ON)
    assertThat(PeopleSettingsRules.shownOf(FacesState.On(g, HereState.LOCAL_ONLY))).isEqualTo(PeopleSwitchShown.LOCAL_ONLY)
    assertThat(PeopleSettingsRules.shownOf(FacesState.On(g, HereState.EXCLUDED))).isEqualTo(PeopleSwitchShown.EXCLUDED_HERE)
    assertThat(PeopleSettingsRules.shownOf(FacesState.AwaitingNotice(g, 1))).isEqualTo(PeopleSwitchShown.AWAITING_NOTICE)
    assertThat(PeopleSettingsRules.shownOf(FacesState.Erasing(g))).isEqualTo(PeopleSwitchShown.ERASING)
    assertThat(PeopleSettingsRules.shownOf(FacesState.NeedsUpdate)).isEqualTo(PeopleSwitchShown.NEEDS_UPDATE)
  }

  @Test
  fun cosaSiOffreInOgniStato() {
    val g = sampleGeneration
    val all = listOf(
      FacesState.Off, FacesState.On(g, HereState.ACTIVE), FacesState.On(g, HereState.LOCAL_ONLY), FacesState.On(g, HereState.EXCLUDED),
      FacesState.AwaitingNotice(g, 1), FacesState.Erasing(g), FacesState.NeedsUpdate,
    )
    // "Disattiva e cancella": con Persone accesa (anche escluso: l'account e' acceso) o in attesa dell'avviso, mai da spenta, in cancellazione o da aggiornare.
    assertThat(all.filter { PeopleSettingsRules.canErase(it) }).containsExactly(all[1], all[2], all[3], all[4])
    // "Non su questo dispositivo": dove Persone potrebbe lavorare.
    assertThat(all.filter { PeopleSettingsRules.canExclude(it) }).containsExactly(all[1], all[2], all[4])
    // "Attiva qui": solo da escluso.
    assertThat(all.filter { PeopleSettingsRules.canInclude(it) }).containsExactly(all[3])
    assertThat(all.filter { PeopleSettingsRules.showsWork(it) }).containsExactly(all[1], all[2])
  }

  // --- Il controllore ---------------------------------------------------------------------------------------------------------------

  @Test
  fun ilControlloreComponeLoStatoDellInterruttoreConIFatti() {
    val facts = MutableStateFlow(PeopleSettingsFacts())
    val switch = FakeFacesSwitch(initial = FacesState.On(sampleGeneration, HereState.ACTIVE))
    val c = PeopleSettingsController(switch, facts, immediateScope())
    assertThat(c.state.value.shown).isEqualTo(PeopleSwitchShown.ON)
    assertThat(c.state.value.canErase).isTrue()
    assertThat(c.state.value.excludedHere).isFalse()
    facts.value = PeopleSettingsFacts(hiddenPeople = 2, progress = FacesStatus.Running(5, 10, FacesPhase.DETECT))
    assertThat(c.state.value.hiddenPeople).isEqualTo(2)
    assertThat(c.state.value.progress).isEqualTo(FacesStatus.Running(5, 10, FacesPhase.DETECT))
  }

  @Test
  fun escludereEIncludereCambiaLoStatoDiQuestoDispositivo() {
    val switch = FakeFacesSwitch(initial = FacesState.On(sampleGeneration, HereState.ACTIVE))
    val c = PeopleSettingsController(switch, MutableStateFlow(PeopleSettingsFacts()), immediateScope())
    c.exclude()
    assertThat(c.state.value.shown).isEqualTo(PeopleSwitchShown.EXCLUDED_HERE)
    assertThat(c.state.value.excludedHere).isTrue()
    assertThat(switch.erasedLocally).hasSize(1)
    c.include()
    assertThat(c.state.value.shown).isEqualTo(PeopleSwitchShown.ON)
  }

  // --- La pagina -----------------------------------------------------------------------------------------------------------------------

  private class Calls {
    var activate = 0
    var notice = 0
    var exclude = 0
    var include = 0
    var hidden = 0
    var licenses = 0
    var diagnostics = 0
    var erase = 0
  }

  private val tablet = ComputingDevice("Tablet di casa", thisDevice = false, excluded = false)
  private val phone = ComputingDevice("Telefono", thisDevice = true, excluded = false)
  private val models = listOf(FaceModelRow("YuNet", "2023mar", "MIT"), FaceModelRow("SFace", "2021dec", "Apache-2.0"))

  private fun state(
    shown: PeopleSwitchShown,
    devices: List<ComputingDevice> = emptyList(),
    progress: FacesStatus = FacesStatus.Off,
    hidden: Int = 0,
    canErase: Boolean = false,
    erase: EraseSheetState.Progress? = null,
  ) = PeopleSettingsState(shown, devices, progress, excludedHere = shown == PeopleSwitchShown.EXCLUDED_HERE, hiddenPeople = hidden, models = models, canErase = canErase, erase = erase)

  private fun show(
    state: PeopleSettingsState,
    fontScale: Float? = null,
    dark: Boolean = false,
    hidden: Boolean = true,
    erase: Boolean = true,
  ): Calls {
    val calls = Calls()
    val handlers = PeopleSettingsHandlers(
      onActivate = { calls.activate++ },
      onOpenNotice = { calls.notice++ },
      onExclude = { calls.exclude++ },
      onInclude = { calls.include++ },
      onOpenHidden = if (hidden) ({ calls.hidden++ }) else null,
      onOpenLicenses = { calls.licenses++ },
      onOpenDiagnostics = { calls.diagnostics++ },
      onErase = if (erase) ({ calls.erase++ }) else null,
    )
    rule.setPeopleContent(dark = dark, fontScale = fontScale) { PeopleSettingsScreen(state = state, handlers = handlers, onBack = {}) }
    return calls
  }

  private fun text(vararg phrases: String) {
    for (phrase in phrases) node(phrase, substring = true).assertIsDisplayed()
  }

  private fun count(phrase: String): Int = rule.onAllNodesWithText(phrase, substring = true).fetchSemanticsNodes().size

  @Test
  fun spentaSpiegaEPortaAllAttivazione() {
    val calls = show(state(PeopleSwitchShown.OFF))
    text("Persone è spenta")
    node("Attiva riconoscimento dei volti").performClick()
    assertThat(calls.activate).isEqualTo(1)
    // Da spenta niente "Chi analizza", niente esclusione, niente cancellazione.
    assertThat(count("ANALISI DELLE FOTO")).isEqualTo(0)
    assertThat(count("Non su questo dispositivo")).isEqualTo(0)
    assertThat(count("Disattiva e cancella")).isEqualTo(0)
  }

  @Test
  fun inAttesaDellAvvisoSiLeggeESiSceglie() {
    val calls = show(state(PeopleSwitchShown.AWAITING_NOTICE, canErase = true))
    text("Da confermare su questo dispositivo", "non scarica, non calcola e non mostra niente")
    node("Leggi e scegli").performClick()
    assertThat(calls.notice).isEqualTo(1)
    // Anche qui "Non su questo dispositivo": subito, senza conferma (non c'e' niente da cancellare).
    node("Non su questo dispositivo").performClick()
    assertThat(calls.exclude).isEqualTo(1)
  }

  @Test
  fun attivaMostraChiAnalizzaEAPuntoCheE() {
    show(state(PeopleSwitchShown.ON, devices = listOf(phone, tablet), progress = FacesStatus.Running(420, 1_000, FacesPhase.DETECT), canErase = true))
    text("Persone è attiva", "ANALISI DELLE FOTO", "Questo dispositivo", "Tablet di casa", "Analizzate 420 foto su 1000")
  }

  @Test
  fun lAttesaDelNasHaLeSueParole() {
    show(state(PeopleSwitchShown.ON, devices = listOf(phone), progress = FacesStatus.Waiting(FacesWait.NAS)))
    text("In attesa del NAS")
  }

  /** Ogni motivo di attesa ha parole sue, tutte diverse (e nessuna ripete un codice). */
  @Test
  fun ogniAttesaHaLeSueParole() {
    val resources = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>().resources
    val words = FacesWait.entries.map { resources.getString(waitText(it)) }
    assertThat(words).containsNoDuplicates()
    for ((reason, word) in FacesWait.entries.zip(words)) {
      assertThat(word).isNotEmpty()
      assertThat(word).doesNotContain(reason.code)
    }
  }

  @Test
  fun senzaDispositiviCheAnalizzanoLaPaginaLoDice() {
    show(state(PeopleSwitchShown.ON, devices = emptyList()))
    text("Nessun tuo dispositivo analizza le foto.")
  }

  @Test
  fun unProblemaDelCalcoloRimandaAllaDiagnostica() {
    val calls = show(state(PeopleSwitchShown.ON, devices = listOf(phone), progress = FacesStatus.Problem("FACES_ENGINE_FAILED")))
    text("Qualcosa non va: la Diagnostica dice cosa.")
    rule.onNodeWithText("Qualcosa non va", substring = true).performClick()
    assertThat(calls.diagnostics).isEqualTo(1)
    // Il codice non si mostra: sono parole per chi legge, i codici stanno nella Diagnostica.
    assertThat(count("FACES_ENGINE_FAILED")).isEqualTo(0)
  }

  // --- Esclusione con conferma (cancella dati di qui) ---------------------------------------------------------------------------------

  @Test
  fun nonSuQuestoDispositivoChiedeConfermaPercheCancellaIDatiDiQui() {
    val calls = show(state(PeopleSwitchShown.ON, devices = listOf(phone)))
    node("Non su questo dispositivo").performClick()
    assertThat(calls.exclude).isEqualTo(0)
    text("Togliere Persone da questo dispositivo?", "Restano sul NAS e sugli altri tuoi dispositivi")
    node("Togli da qui").performClick()
    assertThat(calls.exclude).isEqualTo(1)
  }

  @Test
  fun annullareLaConfermaNonCambiaNiente() {
    val calls = show(state(PeopleSwitchShown.ON, devices = listOf(phone)))
    node("Non su questo dispositivo").performClick()
    node("Annulla").performClick()
    assertThat(calls.exclude).isEqualTo(0)
    rule.onNodeWithText("Non su questo dispositivo").assertIsDisplayed()
  }

  /** Sotto la recinzione nomi e correzioni sono solo qui: la conferma lo dice. */
  @Test
  fun sottoLaRecinzioneLaConfermaDiceCheINomiSiPerdono() {
    show(state(PeopleSwitchShown.LOCAL_ONLY, devices = listOf(phone)))
    text("Persone è attiva, ma ogni dispositivo ha le sue")
    node("Non su questo dispositivo").performClick()
    text("i nomi e le correzioni che hai dato", "non tornano")
  }

  @Test
  fun dispositivoEsclusoOffreAttivaQuiENonLaCancellazioneDiQuiAncora() {
    val calls = show(state(PeopleSwitchShown.EXCLUDED_HERE, canErase = true))
    text("Persone non è attiva su questo dispositivo", "Sugli altri tuoi dispositivi lavora come prima.")
    node("Attiva qui").performClick()
    assertThat(calls.include).isEqualTo(1)
    assertThat(count("ANALISI DELLE FOTO")).isEqualTo(0)
    // L'account e' acceso: "Disattiva e cancella" resta offerta anche da qui.
    text("Disattiva e cancella")
  }

  // --- Nascoste, modelli, cancellazione ---------------------------------------------------------------------------------------------

  @Test
  fun lePersoneNascosteSonoUnNumeroSolo() {
    val calls = show(state(PeopleSwitchShown.ON, devices = listOf(phone), hidden = 3))
    text("Persone nascoste", "Non compaiono in Persone né nella ricerca")
    node("3").assertIsDisplayed()
    rule.onNodeWithText("Persone nascoste").performClick()
    assertThat(calls.hidden).isEqualTo(1)
  }

  @Test
  fun senzaPersoneNascosteLaRigaNonCe() {
    show(state(PeopleSwitchShown.ON, devices = listOf(phone), hidden = 0))
    assertThat(count("Persone nascoste")).isEqualTo(0)
  }

  @Test
  fun iModelliHannoVersioneELicenza_EIlRimandoAlleLicenze() {
    val calls = show(state(PeopleSwitchShown.ON, devices = listOf(phone)))
    text("YuNet 2023mar", "SFace 2021dec", "licenza MIT", "licenza Apache-2.0", "non si scarica niente")
    node("Informazioni e licenze").performClick()
    assertThat(calls.licenses).isEqualTo(1)
  }

  @Test
  fun disattivaECancellaPortaAlFoglioDiP8() {
    val calls = show(state(PeopleSwitchShown.ON, devices = listOf(phone), canErase = true))
    text("Toglie tutto, dal NAS e da tutti i tuoi dispositivi")
    node("Disattiva e cancella").performClick()
    assertThat(calls.erase).isEqualTo(1)
  }

  @Test
  fun senzaIlFoglioLegatoOSenzaPoterlaOffrireLaRigaNonCe() {
    show(state(PeopleSwitchShown.ON, devices = listOf(phone), canErase = true), erase = false)
    assertThat(count("Disattiva e cancella")).isEqualTo(0)
  }

  @Test
  fun lAvanzamentoDellaCancellazioneStaInPagina() {
    val ack = DeviceAck("Telefono", "A".repeat(26), AckState.CONFIRMED, null, thisDevice = true)
    val other = ack.copy(label = "Tablet", state = AckState.WAITING, thisDevice = false)
    val progress = EraseSheetState.Progress(StepState.DONE, StepState.RUNNING, listOf(ack, other), waiting = null)
    show(state(PeopleSwitchShown.ON, devices = listOf(phone), canErase = true, erase = progress))
    text("Su questo dispositivo: fatto · sul NAS: in corso · dispositivi che hanno confermato: 1 su 2")
  }

  /** Mentre si cancella la pagina non offre di rifarlo, ma dice a che punto e': in locale, sul NAS, quanti dispositivi hanno confermato. */
  @Test
  fun inCancellazioneSiVedeLAvanzamentoENonLaRiga() {
    val ack = DeviceAck("Telefono", "A".repeat(26), AckState.CONFIRMED, null, thisDevice = true)
    val progress = EraseSheetState.Progress(StepState.DONE, StepState.WAITING, listOf(ack), waiting = null)
    show(state(PeopleSwitchShown.ERASING, erase = progress))
    text("Cancellazione in corso", "Su questo dispositivo: fatto · sul NAS: in attesa · dispositivi che hanno confermato: 1 su 1")
    assertThat(count("Disattiva e cancella")).isEqualTo(0)
  }

  @Test
  fun inCancellazioneNonSiOffreNientAltroCheLoStato() {
    show(state(PeopleSwitchShown.ERASING))
    text("Cancellazione in corso", "Non si può riattivare finché non ha finito.")
    assertThat(count("Disattiva e cancella")).isEqualTo(0)
    assertThat(count("Non su questo dispositivo")).isEqualTo(0)
  }

  @Test
  fun unaVoceDiUnAppPiuNuovaDiceDiAggiornare() {
    show(state(PeopleSwitchShown.NEEDS_UPDATE))
    text("Aggiorna l’app", "non si calcola e non si cancella niente")
    assertThat(count("Disattiva e cancella")).isEqualTo(0)
  }

  // --- Accessibilita' e riservatezza ------------------------------------------------------------------------------------------------------

  @Test
  fun aTestoGrandeLeRigheSiRaggiungonoEIPulsantiRestanoDa48Dp() {
    show(state(PeopleSwitchShown.OFF), fontScale = 1.3f)
    node("Attiva riconoscimento dei volti").assertIsDisplayed()
    assertThat(rule.onNodeWithText("Attiva riconoscimento dei volti").fetchSemanticsNode().boundsInRoot.height / rule.density.density).isAtLeast(47.5f)
  }

  @Test
  fun ilTemaScuroCompone() {
    show(state(PeopleSwitchShown.ON, devices = listOf(phone, tablet), canErase = true), dark = true)
    rule.onNodeWithText("Persone è attiva").assertIsDisplayed()
  }

  /** D37 e Q3: nessun nome di persona, nessun id, nessuna parola sulla famiglia nelle impostazioni. */
  @Test
  fun nienteNomiNeFamigliaNelleImpostazioni() {
    show(state(PeopleSwitchShown.ON, devices = listOf(phone, tablet), hidden = 2, canErase = true))
    assertThat(count("famigli")).isEqualTo(0)
    assertThat(count(sampleGeneration.base32)).isEqualTo(0)
  }
}
