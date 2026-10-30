package dev.pampa.mycloudgallery.feature.people.activation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesPhase
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.feature.people.contract.AnalyzeNowState
import dev.pampa.mycloudgallery.feature.people.contract.ComputingDevice
import dev.pampa.mycloudgallery.feature.people.contract.FaceModelRow
import dev.pampa.mycloudgallery.feature.people.contract.FacesActivationState
import dev.pampa.mycloudgallery.feature.people.contract.FacesAnalyzeEstimate
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSettingsState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSwitchShown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 8, correzioni C: «Analizza ora» con la stima (CA-2), il progresso della libreria nella riga di Impostazioni (CA-1), il motivo vero di un archivio che non si
 * apre (CA-7), lo stato «non ancora» prima del montaggio (L350) e «disattivata da un altro tuo dispositivo» anche qui (L322). Ogni prova cade senza la correzione.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class CorrezioniCPersoneTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  /** Una porta di «Analizza ora» a mano: la stima si completa quando il test lo dice, e si contano i tocchi. */
  private class FakeAnalyze(var network: String? = null) : FacesAnalyzeNow {
    var pending = CompletableDeferred<FacesAnalyzeEstimate?>()
    var estimates = 0
    var starts = 0

    override suspend fun estimate(): FacesAnalyzeEstimate? {
      estimates++
      return pending.await()
    }

    override suspend fun networkNote(estimate: FacesAnalyzeEstimate): String? = network

    override fun start() {
      starts++
    }
  }

  private val first = FacesAnalyzeEstimate(remaining = 12_000, done = 0, minutes = 37, bytes = 1_800_000_000L)

  // ---- CA-2: il controllore di «Analizza ora» ---------------------------------------------------------------------------------------------

  @Test
  fun ca2_laStimaArrivaDopoIlPrimoDisegno_Poi_ilTastoChiedeLaConferma() {
    val port = FakeAnalyze()
    val c = AnalyzeNowController(port, immediateScope())
    assertThat(c.state.value).isEqualTo(AnalyzeNowState.Hidden)

    c.refresh()
    // La pagina e' disegnata con lo scheletro finche' il conto non finisce (il conto rilegge il catalogo).
    assertThat(c.state.value).isEqualTo(AnalyzeNowState.Counting)
    port.pending.complete(first)
    assertThat(c.state.value).isEqualTo(AnalyzeNowState.Ready(first))

    c.ask()
    assertThat(c.state.value).isEqualTo(AnalyzeNowState.Confirming(first, network = null))
    assertThat(port.starts).isEqualTo(0)
    c.confirm()
    assertThat(c.state.value).isEqualTo(AnalyzeNowState.Started)
    assertThat(port.starts).isEqualTo(1)
    // Una seconda conferma, o un tocco dopo, non fanno partire altro.
    c.confirm()
    c.ask()
    assertThat(port.starts).isEqualTo(1)
  }

  @Test
  fun ca2_ilCalcoloNonParteSenzaLaConferma() {
    val port = FakeAnalyze()
    val c = AnalyzeNowController(port, immediateScope())
    c.refresh()
    port.pending.complete(first)

    c.confirm() // senza aver chiesto: niente
    assertThat(port.starts).isEqualTo(0)
    assertThat(c.state.value).isEqualTo(AnalyzeNowState.Ready(first))

    c.ask()
    c.cancel()
    assertThat(c.state.value).isEqualTo(AnalyzeNowState.Ready(first))
    c.confirm() // annullata: niente
    assertThat(port.starts).isEqualTo(0)
  }

  @Test
  fun ca2_suiDatiMobiliLaConfermaPortaLaStimaDelleRegole() {
    val port = FakeAnalyze(network = "Stai per usare i dati mobili. Servono circa 1,8 GB. Vuoi continuare?")
    val c = AnalyzeNowController(port, immediateScope())
    c.refresh()
    port.pending.complete(first)

    c.ask()

    val confirming = c.state.value as AnalyzeNowState.Confirming
    assertThat(confirming.network).contains("dati mobili")
    assertThat(AnalyzeNowText.confirm(resources, first, confirming.network)).contains("Servono circa 1,8 GB")
  }

  @Test
  fun ca2_senzaLavoroDaFareONonSaDirloNonSiOffreNiente() {
    for (answer in listOf(null, FacesAnalyzeEstimate(remaining = 0, done = 12_000, minutes = 0))) {
      val port = FakeAnalyze()
      val c = AnalyzeNowController(port, immediateScope())
      c.refresh()
      port.pending.complete(answer)
      assertThat(c.state.value).isEqualTo(AnalyzeNowState.Hidden)
    }
    // Senza la porta non si offre mai.
    val none = AnalyzeNowController(null, immediateScope())
    none.refresh()
    assertThat(none.state.value).isEqualTo(AnalyzeNowState.Hidden)
    assertThat(none.available).isFalse()
  }

  @Test
  fun ca2_unaConfermaAperta_UnaRichiestaGiaFatta_NonSiChiudonoDaSole() {
    val port = FakeAnalyze()
    val c = AnalyzeNowController(port, immediateScope())
    c.refresh()
    port.pending.complete(first)
    c.ask()
    c.refresh() // un altro conto non chiude la conferma
    assertThat(c.state.value).isInstanceOf(AnalyzeNowState.Confirming::class.java)
    c.confirm()
    c.hide() // la richiesta resta detta
    c.refresh()
    assertThat(c.state.value).isEqualTo(AnalyzeNowState.Started)
    assertThat(port.estimates).isEqualTo(1)
  }

  // ---- CA-2: Impostazioni -> Persone ------------------------------------------------------------------------------------------------------

  private fun activeSwitch() = FakeFacesSwitch(initial = FacesState.On(sampleGeneration, HereState.ACTIVE))

  @Test
  fun ca2_ImpostazioniContaLeFotoSoloDoveAnalizza() {
    val facts = MutableStateFlow(PeopleSettingsFacts(devices = emptyList(), computesHere = false))
    val port = FakeAnalyze()
    val c = PeopleSettingsController(activeSwitch(), facts, immediateScope(), port)
    // Un dispositivo che non analizza le foto non conta niente (e non offre niente).
    assertThat(port.estimates).isEqualTo(0)
    assertThat(c.state.value.analyze).isEqualTo(AnalyzeNowState.Hidden)

    facts.value = PeopleSettingsFacts(computesHere = true)
    assertThat(port.estimates).isEqualTo(1)
    assertThat(c.state.value.analyze).isEqualTo(AnalyzeNowState.Counting)
    port.pending.complete(first)
    assertThat(c.state.value.analyze).isEqualTo(AnalyzeNowState.Ready(first))
  }

  /** Dopo una richiesta, quando il calcolo gira la pagina dice il suo avanzamento; quando si ferma o finisce la stima si rilegge (non resta «l'analisi parte adesso»). */
  @Test
  fun ca2_DopoLaRichiestaIlCambioDiFaseRilegge() {
    val facts = MutableStateFlow(PeopleSettingsFacts())
    val port = FakeAnalyze()
    val c = PeopleSettingsController(activeSwitch(), facts, immediateScope(), port)
    port.pending.complete(first)
    c.askAnalyze()
    c.confirmAnalyze()
    assertThat(port.starts).isEqualTo(1)
    assertThat(c.state.value.analyze).isEqualTo(AnalyzeNowState.Started)

    facts.value = PeopleSettingsFacts(progress = FacesStatus.Running(10, 12_000, FacesPhase.DETECT))
    assertThat(c.state.value.analyze).isEqualTo(AnalyzeNowState.Started) // la riga dell'avanzamento dice il resto, la richiesta resta detta

    // Il calcolo si ferma di nuovo ad aspettare le regole: «Analizza ora» torna con una stima nuova (ne restano meno).
    port.pending = CompletableDeferred(FacesAnalyzeEstimate(remaining = 11_990, done = 10, minutes = 37))
    facts.value = PeopleSettingsFacts(progress = FacesStatus.Waiting(FacesWait.RULES))
    assertThat(port.estimates).isEqualTo(2)
    assertThat(c.state.value.analyze).isEqualTo(AnalyzeNowState.Ready(FacesAnalyzeEstimate(remaining = 11_990, done = 10, minutes = 37)))
  }

  @Test
  fun ca2_ImpostazioniNonContaDaSpentaNeDuranteIlCalcolo() {
    val port = FakeAnalyze()
    val off = FakeFacesSwitch(initial = FacesState.Off)
    PeopleSettingsController(off, MutableStateFlow(PeopleSettingsFacts()), immediateScope(), port)
    assertThat(port.estimates).isEqualTo(0)

    val running = MutableStateFlow(PeopleSettingsFacts(progress = FacesStatus.Running(10, 100, FacesPhase.DETECT)))
    PeopleSettingsController(activeSwitch(), running, immediateScope(), port)
    // Il calcolo e' in corso: la riga dell'avanzamento basta, niente stima e niente tasto.
    assertThat(port.estimates).isEqualTo(0)
  }

  @Test
  fun ca2_ImpostazioniNonContaPrimaDelMontaggio() {
    val port = FakeAnalyze()
    val switch = activeSwitch().also { it.known.value = false }
    val c = PeopleSettingsController(switch, MutableStateFlow(PeopleSettingsFacts()), immediateScope(), port)
    assertThat(port.estimates).isEqualTo(0)
    assertThat(c.state.value.known).isFalse()

    switch.known.value = true
    assertThat(port.estimates).isEqualTo(1)
    assertThat(c.state.value.known).isTrue()
  }

  private val phone = ComputingDevice("Telefono", thisDevice = true, excluded = false)
  private val models = listOf(FaceModelRow("YuNet", "2023mar", "MIT"), FaceModelRow("SFace", "2021dec", "Apache-2.0"))

  private fun state(
    shown: PeopleSwitchShown = PeopleSwitchShown.ON,
    progress: FacesStatus = FacesStatus.Waiting(FacesWait.RULES),
    analyze: AnalyzeNowState = AnalyzeNowState.Hidden,
    known: Boolean = true,
    offElsewhere: Boolean = false,
    computesHere: Boolean = true,
  ) = PeopleSettingsState(
    shown = shown,
    devices = listOf(phone),
    progress = progress,
    excludedHere = false,
    hiddenPeople = 0,
    models = models,
    canErase = false,
    erase = null,
    computesHere = computesHere,
    known = known,
    offElsewhere = offElsewhere,
    analyze = analyze,
  )

  private class Taps {
    var ask = 0
    var cancel = 0
    var confirm = 0
  }

  private fun show(state: PeopleSettingsState, fontScale: Float? = null): Taps {
    val taps = Taps()
    val handlers = PeopleSettingsHandlers(
      onActivate = {}, onOpenNotice = {}, onExclude = {}, onInclude = {}, onOpenLicenses = {},
      onAnalyzeAsk = { taps.ask++ }, onAnalyzeCancel = { taps.cancel++ }, onAnalyzeConfirm = { taps.confirm++ },
    )
    rule.setPeopleContent(fontScale = fontScale) { PeopleSettingsScreen(state = state, handlers = handlers, onBack = {}) }
    return taps
  }

  private fun node(label: String) = rule.onNodeWithText(label, substring = true).also {
    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label, substring = true))
  }

  private fun shows(label: String): Boolean = rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty()

  @Test
  fun ca2_laPaginaDiceQuantoCi_vuoleEOffreIlTasto() {
    val taps = show(state(analyze = AnalyzeNowState.Ready(first)))
    node("Analizzare le tue 12.000 foto richiede circa 37 minuti.").assertIsDisplayed()
    // Il primo calcolo segue le regole di sottofondo: la pagina lo dice, e il tasto e' l'unico modo di scavalcarle.
    node("Di norma l’analisi parte da sola, di notte, in carica e sul Wi-Fi di casa.").assertIsDisplayed()
    node("anteprime che ci sono già").assertIsDisplayed()
    node("Analizza ora").performClick()
    assertThat(taps.ask).isEqualTo(1)
  }

  @Test
  fun ca2_ilTastoEdLaConfermaHannoBersagliDa48Dp() {
    show(state(analyze = AnalyzeNowState.Ready(first)))
    node("Analizza ora").assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun ca2_laConfermaDiceLaDurataEAnnullaOConferma() {
    val taps = show(state(analyze = AnalyzeNowState.Confirming(first, network = null)))
    node("Analizzare adesso?").assertIsDisplayed()
    node("anche di giorno e a batteria").assertIsDisplayed()
    node("circa 37 minuti").assertIsDisplayed()
    node("Annulla").assertHeightIsAtLeast(48.dp)
    node("Annulla").performClick()
    assertThat(taps.cancel).isEqualTo(1)
    // «Analizza ora» nella conferma e' il bottone di conferma: due scritte, la seconda e' quella del bottone.
    rule.onAllNodesWithText("Analizza ora").onFirst().performClick()
    assertThat(taps.confirm).isEqualTo(1)
  }

  @Test
  fun ca2_suiDatiMobiliLaConfermaMostraLaStimaDeiDati() {
    show(state(analyze = AnalyzeNowState.Confirming(first, network = "Stai per usare i dati mobili. Servono circa 1,8 GB. Vuoi continuare?")))
    node("Stai per usare i dati mobili").assertIsDisplayed()
    node("Servono circa 1,8 GB").assertIsDisplayed()
  }

  @Test
  fun ca2_ilConteggioHaLoScheletro() {
    show(state(analyze = AnalyzeNowState.Counting))
    node("Conto le foto da analizzare").assertIsDisplayed()
    assertThat(shows("Analizza ora")).isFalse()
  }

  @Test
  fun ca2_unaRichiestaFattaDiceCheParte() {
    show(state(analyze = AnalyzeNowState.Started))
    node("l’analisi parte adesso").assertIsDisplayed()
  }

  @Test
  fun ca2_nonSiOffreDoveNonSiAnalizza() {
    show(state(analyze = AnalyzeNowState.Ready(first), computesHere = false))
    assertThat(shows("Analizza ora")).isFalse()
    assertThat(shows("Analizzare le tue")).isFalse()
  }

  @Test
  fun ca2_nonSiOffreNelMezzoDelCalcolo() {
    show(state(analyze = AnalyzeNowState.Ready(first), progress = FacesStatus.Running(10, 100, FacesPhase.DETECT)))
    assertThat(shows("Analizza ora")).isFalse()
    assertThat(shows("Analizzare le tue")).isFalse()
    // La riga dell'avanzamento dice il resto.
    node("Analizzate 10 foto su 100").assertIsDisplayed()
  }

  @Test
  fun ca2_leFrasiDellaStimaSonoItalianeConIPuntiDelleMigliaia() {
    assertThat(AnalyzeNowText.estimate(resources, first)).isEqualTo("Analizzare le tue 12.000 foto richiede circa 37 minuti.")
    assertThat(AnalyzeNowText.estimate(resources, FacesAnalyzeEstimate(1, 0, 1))).isEqualTo("Analizzare la tua foto richiede circa 1 minuto.")
    assertThat(AnalyzeNowText.estimate(resources, FacesAnalyzeEstimate(4_200, 7_800, 11))).isEqualTo("Restano 4.200 foto da analizzare: servono circa 11 minuti.")
    assertThat(AnalyzeNowText.estimate(resources, FacesAnalyzeEstimate(1, 99, 1))).isEqualTo("Resta 1 foto da analizzare: serve circa 1 minuto.")
    assertThat(AnalyzeNowText.duration(resources, 60)).isEqualTo("1 ora")
    assertThat(AnalyzeNowText.duration(resources, 130)).isEqualTo("2 ore e 10 minuti")
    assertThat(AnalyzeNowText.duration(resources, 0)).isEqualTo("1 minuto")
  }

  // ---- CA-1: la riga dell'avanzamento e' della libreria -----------------------------------------------------------------------------

  @Test
  fun ca1_laRigaDiceLeFotoDellaLibreria() {
    show(state(progress = FacesStatus.Running(3_400, 12_000, FacesPhase.DETECT)))
    node("Analizzate 3.400 foto su 12.000").assertIsDisplayed()
  }

  @Test
  fun ca1_senzaTotaleNienteNumeri() {
    show(state(progress = FacesStatus.Running(0, 0, FacesPhase.DETECT)))
    node("Sto analizzando le foto").assertIsDisplayed()
    assertThat(shows("su 0")).isFalse()
  }

  // ---- CA-7: il motivo vero ---------------------------------------------------------------------------------------------------------

  @Test
  fun ca7_unaChiaveCheNonRispondeNonEPocoSpazio() {
    show(state(progress = FacesStatus.Waiting(FacesWait.KEYSTORE)))
    node("la chiave che protegge i dati dei volti non risponde").assertIsDisplayed()
    assertThat(shows("poco spazio libero")).isFalse()
    assertThat(waitText(FacesWait.KEYSTORE)).isNotEqualTo(waitText(FacesWait.STORAGE))
  }

  // ---- L350: non ancora -------------------------------------------------------------------------------------------------------------

  @Test
  fun l350_primaDelMontaggioLaPaginaNonDiceSpenta() {
    show(state(shown = PeopleSwitchShown.OFF, known = false))
    // Uno scheletro fermo (TalkBack sente «Sto caricando»), non una rotella con un testo di stato.
    assertThat(rule.onAllNodesWithContentDescription("Sto caricando").fetchSemanticsNodes()).isNotEmpty()
    // Nessuna frase di stato: ogni «spenta» sarebbe sbagliata per chi ha Persone accesa, e «Attiva» risponderebbe «non disponibile».
    assertThat(shows("Persone è spenta")).isFalse()
    assertThat(shows("Attiva riconoscimento dei volti")).isFalse()
    assertThat(shows("Persone è attiva")).isFalse()
  }

  @Test
  fun l350_noto_laPaginaDiceLoStatoVero() {
    show(state(shown = PeopleSwitchShown.OFF, known = true))
    node("Persone è spenta").assertIsDisplayed()
    assertThat(rule.onAllNodesWithContentDescription("Sto caricando").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun l350_ilControlloreNonRispondeNotoFincheLeCoseNonSonoMontate() {
    val switch = FakeFacesSwitch().also { it.known.value = false }
    val c = PeopleSettingsController(switch, MutableStateFlow(PeopleSettingsFacts()), immediateScope())
    assertThat(c.state.value.known).isFalse()
    switch.known.value = true
    assertThat(c.state.value.known).isTrue()
  }

  @Test
  fun l350_laPaginaDiAttivazioneAspettaLoStatoNotoPrimaDiLeggereIRequisiti() {
    val switch = FakeFacesSwitch().also { it.known.value = false }
    val c = FacesActivationController(switch, metRequirements(), indexerRole = null, scope = immediateScope())
    c.load()
    // Lo scheletro (nessuno stato): ne' i requisiti ne' «Attiva» finche' lo stato non e' vero.
    assertThat(c.state.value).isNull()

    switch.known.value = true
    assertThat(c.state.value).isInstanceOf(FacesActivationState.Explain::class.java)
  }

  // ---- L322: spenta da un altro dispositivo, anche qui ----------------------------------------------------------------------------------

  @Test
  fun l322_daSpentaDaUnAltroDispositivoLaPaginaLoDice() {
    show(state(shown = PeopleSwitchShown.OFF, offElsewhere = true))
    node("Persone è stata disattivata da un altro tuo dispositivo").assertIsDisplayed()
    node("I volti e i nomi sono stati cancellati anche da qui").assertIsDisplayed()
    // E il resto della pagina c'e': lo stato di sempre e «Attiva riconoscimento dei volti».
    node("Persone è spenta").assertIsDisplayed()
    node("Attiva riconoscimento dei volti").assertIsDisplayed()
  }

  @Test
  fun l322_senzaIlSegnoLaRigaNonCe() {
    show(state(shown = PeopleSwitchShown.OFF, offElsewhere = false))
    assertThat(shows("disattivata da un altro")).isFalse()
  }

  @Test
  fun l322_ilControlloreDiceLaRigaSoloDaSpentaECon_ilSegno() {
    val off = FakeFacesSwitch(initial = FacesState.Off)
    val c = PeopleSettingsController(off, MutableStateFlow(PeopleSettingsFacts()), immediateScope())
    assertThat(c.state.value.offElsewhere).isFalse()
    off.offElsewhere.value = true
    assertThat(c.state.value.offElsewhere).isTrue()
    // Con Persone riaccesa il segno non vale piu'.
    off.state.value = FacesState.On(sampleGeneration, HereState.ACTIVE)
    assertThat(c.state.value.offElsewhere).isFalse()
  }

  // ---- La pagina di attivazione ------------------------------------------------------------------------------------------------------

  @Test
  fun ca2_laPaginaDiAttivazioneOffreLaStimaDopoAttivata() {
    val port = FakeAnalyze()
    val switch = activeSwitch()
    val c = FacesActivationController(switch, metRequirements(), indexerRole = null, scope = immediateScope(), analyze = port)
    c.load()
    // Persone e' gia' attiva qui: la pagina dice «Attivata» e conta le foto dopo il primo disegno.
    assertThat(c.state.value).isInstanceOf(FacesActivationState.Activated::class.java)
    assertThat(port.estimates).isEqualTo(1)
    port.pending.complete(first)
    assertThat(c.analyzeState.value).isEqualTo(AnalyzeNowState.Ready(first))

    val taps = Taps()
    rule.setPeopleContent {
      FacesActivationScreen(
        state = c.state.value,
        onActivate = {}, onNotNow = {}, onBecomeIndexer = {}, onOpenLicenses = {}, onRetry = {}, onDone = {}, onBack = {},
        analyze = c.analyzeState.value,
        onAnalyzeAsk = { taps.ask++ },
      )
    }
    node("Persone è attiva").assertIsDisplayed()
    node("Analizzare le tue 12.000 foto richiede circa 37 minuti.").assertIsDisplayed()
    node("Analizza ora").performClick()
    assertThat(taps.ask).isEqualTo(1)
    // «Fatto» resta il tasto principale.
    node("Fatto").assertIsDisplayed()
  }

  @Test
  fun ca2_primaDellAttivazioneLaPaginaNonContaNiente() {
    val port = FakeAnalyze()
    val c = FacesActivationController(FakeFacesSwitch(), metRequirements(), indexerRole = null, scope = immediateScope(), analyze = port)
    c.load()
    assertThat(c.state.value).isInstanceOf(FacesActivationState.Explain::class.java)
    assertThat(port.estimates).isEqualTo(0)
    assertThat(c.analyzeState.value).isEqualTo(AnalyzeNowState.Hidden)
  }
}
