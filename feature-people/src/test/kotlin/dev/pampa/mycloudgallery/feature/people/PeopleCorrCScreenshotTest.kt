package dev.pampa.mycloudgallery.feature.people

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.faces.contract.FacesPhase
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.feature.people.activation.FacesActivationScreen
import dev.pampa.mycloudgallery.feature.people.activation.PeopleSettingsHandlers
import dev.pampa.mycloudgallery.feature.people.activation.PeopleSettingsScreen
import dev.pampa.mycloudgallery.feature.people.contract.AnalyzeNowState
import dev.pampa.mycloudgallery.feature.people.contract.ComputingDevice
import dev.pampa.mycloudgallery.feature.people.contract.FaceModelRow
import dev.pampa.mycloudgallery.feature.people.contract.FacesActivationState
import dev.pampa.mycloudgallery.feature.people.contract.FacesAnalyzeEstimate
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSettingsState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSwitchShown
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot delle schermate nuove o cambiate dalle correzioni C della Fase 8: «Analizza ora» con la stima (Impostazioni -> Persone e pagina di attivazione, con la
 * conferma anche sui dati mobili), il progresso della libreria (CA-1), il motivo vero di un archivio che non si apre (CA-7), lo stato «non ancora» prima del montaggio (L350)
 * e «disattivata da un altro tuo dispositivo» in Impostazioni (L322). Telefono (412 dp), chiaro e scuro, e il testo a 1,3x. **Nessun volto, nessun nome**: solo righe di testo.
 *
 * `gradlew :feature-people:recordRoborazziDebug --tests "*PeopleCorrCScreenshot*"` scrive i PNG in `docs/screenshots/8-corr-c/`. Nei normali `testDebugUnitTest` si
 * verifica solo che le schermate compongano, dicano le cose giuste, non si sovrappongano e abbiano bersagli da 48 dp.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class PeopleCorrCScreenshotTest(private val dark: Boolean) : PeopleScreenTest() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, vararg expect: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    showAll(dark = dark, fontScale = fontScale, content = content)
    for (text in expect) assertShows(name, text)
    assertTouchTargets(name)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("../docs/screenshots/8-corr-c/$name-$look-telefono$suffix.png")
  }

  private val first = FacesAnalyzeEstimate(remaining = 12_000, done = 0, minutes = 37, bytes = 1_800_000_000L)
  private val left = FacesAnalyzeEstimate(remaining = 4_200, done = 7_800, minutes = 11, bytes = 630_000_000L)
  private val mobileNote = "Stai per usare i dati mobili. Servono circa 1,7 GB. Vuoi continuare?"
  private val models = listOf(FaceModelRow("YuNet", "2023mar", "MIT"), FaceModelRow("SFace", "2021dec", "Apache 2.0"))
  private val phone = ComputingDevice("Telefono", thisDevice = true, excluded = false)

  private fun settings(
    shown: PeopleSwitchShown = PeopleSwitchShown.ON,
    progress: FacesStatus = FacesStatus.Waiting(FacesWait.RULES),
    analyze: AnalyzeNowState = AnalyzeNowState.Hidden,
    known: Boolean = true,
    offElsewhere: Boolean = false,
  ) = PeopleSettingsState(
    shown = shown,
    devices = if (shown == PeopleSwitchShown.ON) listOf(phone) else emptyList(),
    progress = progress,
    excludedHere = false,
    hiddenPeople = 0,
    models = models,
    canErase = false,
    erase = null,
    computesHere = true,
    known = known,
    offElsewhere = offElsewhere,
    analyze = analyze,
  )

  private val handlers = PeopleSettingsHandlers(onActivate = {}, onOpenNotice = {}, onExclude = {}, onInclude = {}, onOpenLicenses = {})

  @Composable
  private fun Settings(state: PeopleSettingsState) = PeopleSettingsScreen(state = state, handlers = handlers, onBack = {})

  // --- CA-2: Impostazioni -> Persone con «Analizza ora» ---------------------------------------------------------------------------------

  @Test fun impostazioniAnalizzaOra() =
    shoot("impostazioni-analizza-ora", "Analizzare le tue 12.000 foto richiede circa 37 minuti.", "Analizza ora", "In attesa della notte") {
      Settings(settings(analyze = AnalyzeNowState.Ready(first)))
    }

  @Test fun impostazioniAnalizzaOraTestoGrande() =
    shoot("impostazioni-analizza-ora", "Analizzare le tue 12.000 foto richiede circa 37 minuti.", fontScale = LARGE_TEXT) { Settings(settings(analyze = AnalyzeNowState.Ready(first))) }

  @Test fun impostazioniAnalizzaOraLibreriaMezzaFatta() =
    shoot("impostazioni-analizza-ora-restano", "Restano 4.200 foto da analizzare: servono circa 11 minuti.", "Analizza ora") {
      Settings(settings(analyze = AnalyzeNowState.Ready(left)))
    }

  @Test fun impostazioniAnalizzaOraConteggio() =
    shoot("impostazioni-analizza-ora-conteggio", "Conto le foto da analizzare") { Settings(settings(analyze = AnalyzeNowState.Counting)) }

  @Test fun impostazioniAnalizzaOraConferma() =
    shoot("impostazioni-analizza-ora-conferma", "Analizzare adesso?", "anche di giorno e a batteria", "circa 37 minuti", "Annulla") {
      Settings(settings(analyze = AnalyzeNowState.Confirming(first, network = null)))
    }

  @Test fun impostazioniAnalizzaOraConfermaTestoGrande() =
    shoot("impostazioni-analizza-ora-conferma", "Analizzare adesso?", "Annulla", fontScale = LARGE_TEXT) {
      Settings(settings(analyze = AnalyzeNowState.Confirming(first, network = null)))
    }

  @Test fun impostazioniAnalizzaOraConfermaDatiMobili() =
    shoot("impostazioni-analizza-ora-conferma-dati-mobili", "Analizzare adesso?", "Stai per usare i dati mobili", "Servono circa 1,7 GB", "Annulla") {
      Settings(settings(analyze = AnalyzeNowState.Confirming(first, network = mobileNote)))
    }

  @Test fun impostazioniAnalizzaOraConfermaDatiMobiliTestoGrande() =
    shoot("impostazioni-analizza-ora-conferma-dati-mobili", "Stai per usare i dati mobili", fontScale = LARGE_TEXT) {
      Settings(settings(analyze = AnalyzeNowState.Confirming(first, network = mobileNote)))
    }

  @Test fun impostazioniAnalizzaOraAvviata() =
    shoot("impostazioni-analizza-ora-avviata", "l’analisi parte adesso") { Settings(settings(analyze = AnalyzeNowState.Started)) }

  // --- CA-1: il progresso e' della libreria ----------------------------------------------------------------------------------------------

  @Test fun impostazioniAvanzamentoDellaLibreria() =
    shoot("impostazioni-avanzamento-libreria", "Analizzate 3.400 foto su 12.000") {
      Settings(settings(progress = FacesStatus.Running(3_400, 12_000, FacesPhase.DETECT), analyze = AnalyzeNowState.Hidden))
    }

  // --- CA-7: il motivo vero di un archivio che non si apre --------------------------------------------------------------------------------

  @Test fun impostazioniAttesaDellaChiave() =
    shoot("impostazioni-attesa-chiave", "la chiave che protegge i dati dei volti non risponde") { Settings(settings(progress = FacesStatus.Waiting(FacesWait.KEYSTORE))) }

  @Test fun impostazioniAttesaDelNas() = shoot("impostazioni-attesa-nas", "In attesa del NAS") { Settings(settings(progress = FacesStatus.Waiting(FacesWait.NAS))) }

  // --- L350: non ancora ----------------------------------------------------------------------------------------------------------------

  @Test fun impostazioniNonAncora() = shoot("impostazioni-non-ancora", "MODELLI E LICENZE") { Settings(settings(shown = PeopleSwitchShown.OFF, known = false)) }

  @Test fun impostazioniNonAncoraTestoGrande() =
    shoot("impostazioni-non-ancora", "MODELLI E LICENZE", fontScale = LARGE_TEXT) { Settings(settings(shown = PeopleSwitchShown.OFF, known = false)) }

  // --- L322: disattivata da un altro dispositivo ---------------------------------------------------------------------------------------

  @Test fun impostazioniDisattivataDaUnAltroDispositivo() =
    shoot("impostazioni-disattivata-altrove", "Persone è stata disattivata da un altro tuo dispositivo", "I volti e i nomi sono stati cancellati anche da qui", "Attiva riconoscimento dei volti") {
      Settings(settings(shown = PeopleSwitchShown.OFF, offElsewhere = true))
    }

  @Test fun impostazioniDisattivataDaUnAltroDispositivoTestoGrande() =
    shoot("impostazioni-disattivata-altrove", "Persone è stata disattivata da un altro tuo dispositivo", fontScale = LARGE_TEXT) {
      Settings(settings(shown = PeopleSwitchShown.OFF, offElsewhere = true))
    }

  // --- La pagina di attivazione, dopo «Attiva» ----------------------------------------------------------------------------------------

  @Composable
  private fun Activation(analyze: AnalyzeNowState) = FacesActivationScreen(
    state = FacesActivationState.Activated(syncPending = false),
    onActivate = {}, onNotNow = {}, onBecomeIndexer = {}, onOpenLicenses = {}, onRetry = {}, onDone = {}, onBack = {},
    analyze = analyze,
  )

  @Test fun attivazioneAnalizzaOra() =
    shoot("attivazione-analizza-ora", "Persone è attiva", "Analizzare le tue 12.000 foto richiede circa 37 minuti.", "Analizza ora", "Fatto") { Activation(AnalyzeNowState.Ready(first)) }

  @Test fun attivazioneAnalizzaOraTestoGrande() =
    shoot("attivazione-analizza-ora", "Persone è attiva", "Analizza ora", fontScale = LARGE_TEXT) { Activation(AnalyzeNowState.Ready(first)) }

  @Test fun attivazioneAnalizzaOraConferma() =
    shoot("attivazione-analizza-ora-conferma", "Analizzare adesso?", "Annulla", "Fatto") { Activation(AnalyzeNowState.Confirming(first, network = null)) }

  @Test fun attivazioneAnalizzaOraConfermaDatiMobili() =
    shoot("attivazione-analizza-ora-conferma-dati-mobili", "Stai per usare i dati mobili", "Annulla") { Activation(AnalyzeNowState.Confirming(first, network = mobileNote)) }

  @Test fun attivazioneSenzaStima() = shoot("attivazione-senza-stima", "Persone è attiva", "Fatto") { Activation(AnalyzeNowState.Hidden) }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
