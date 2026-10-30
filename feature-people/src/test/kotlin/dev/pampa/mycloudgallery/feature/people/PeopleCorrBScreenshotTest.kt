package dev.pampa.mycloudgallery.feature.people

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesWait
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryRows
import dev.pampa.mycloudgallery.feature.people.activation.PeopleSettingsHandlers
import dev.pampa.mycloudgallery.feature.people.activation.PeopleSettingsScreen
import dev.pampa.mycloudgallery.feature.people.contract.ComputingDevice
import dev.pampa.mycloudgallery.feature.people.contract.FaceModelRow
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSettingsState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSwitchShown
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionSources
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionStates
import dev.pampa.mycloudgallery.feature.people.section.SectionData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot delle righe nuove della sezione Persone delle Raccolte e della pagina (Fase 8, correzioni dopo le revisioni, pacchetto B): AT-1 (con Persone
 * accesa la sezione dice sempre qualcosa: il primo calcolo che aspetta, "Ancora nessuna persona", e con la nota della recinzione), DR-1 ("Qui le persone non si
 * calcolano" nella sezione e in Impostazioni → Persone, e il testo di "Attiva" che non dice piu' che i nomi viaggiano). Telefono (412 dp), chiaro e scuro, e il
 * testo a 1,3x. **Nessun volto, nessun nome**: solo righe di testo.
 *
 * `gradlew :feature-people:recordRoborazziDebug --tests "*PeopleCorrBScreenshot*"` scrive i PNG in `docs/screenshots/8-corr-b/`. Nei normali `testDebugUnitTest` si
 * verifica solo che le schermate compongano e dicano le cose giuste.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class PeopleCorrBScreenshotTest(private val dark: Boolean) : PeopleScreenTest() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private val generation = FaceGeneration.random()
  private val active = FacesState.On(generation, HereState.ACTIVE)
  private val local = FacesState.On(generation, HereState.LOCAL_ONLY)
  private val idle = FacesStatus.Idle(atNas = 1L)

  private fun shoot(name: String, vararg expect: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    showAll(dark = dark, fontScale = fontScale, content = content)
    for (text in expect) assertShows(name, text)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("../docs/screenshots/8-corr-b/$name-$look-telefono$suffix.png")
  }

  private fun groupOf(section: SectionData) = CollectionEntryGroup(
    title = section.title,
    entries = section.entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing) },
    code = section.code,
    hideable = section.hideable,
    hint = section.hint,
  )

  /** La sezione come la mostra la scheda Raccolte: l'intestazione e il gruppo di righe (le stesse righe di `CollectionEntryRows`). */
  @Composable
  private fun SectionScene(state: FacesState, status: FacesStatus, computesHere: Boolean = true) {
    val resources = LocalContext.current.resources
    val list = PeopleList.EMPTY
    val section = PeopleSectionSources.sectionOf(resources, PeopleSectionStates.of(state, list, status, computesHere = computesHere), list)
    FluidScreen(title = "Raccolte", modifier = Modifier.fillMaxSize()) {
      item { FluidSectionHeader(title = section.title) }
      item { CollectionEntryRows(groupOf(section), selectedCode = null, twoPane = false, onSelect = {}) }
    }
  }

  // --- AT-1: con Persone accesa la sezione dice sempre qualcosa ---------------------------------------------------------------------------

  @Test fun sezionePrimoCalcoloInAttesa() = shoot("sezione-primo-calcolo-attesa", "Sto per riconoscere i volti") { SectionScene(active, FacesStatus.Off) }

  @Test fun sezionePrimoCalcoloInAttesaTestoGrande() =
    shoot("sezione-primo-calcolo-attesa", "Sto per riconoscere i volti", fontScale = LARGE_TEXT) { SectionScene(active, FacesStatus.Off) }

  @Test fun sezioneAncoraNessunaPersona() = shoot("sezione-ancora-nessuna-persona", "Ancora nessuna persona", "almeno tre foto") { SectionScene(active, idle) }

  @Test fun sezioneAncoraNessunaPersonaTestoGrande() =
    shoot("sezione-ancora-nessuna-persona", "Ancora nessuna persona", fontScale = LARGE_TEXT) { SectionScene(active, idle) }

  @Test fun sezioneAncoraNessunaPersonaSoloQui() =
    shoot("sezione-ancora-nessuna-persona-solo-qui", "Ancora nessuna persona", "Solo su questo dispositivo") { SectionScene(local, idle) }

  @Test fun sezioneInAttesaDelleRegole() = shoot("sezione-attesa-regole", "Sto per riconoscere i volti") { SectionScene(active, FacesStatus.Waiting(FacesWait.RULES)) }

  // --- DR-1: un dispositivo che non analizza le foto --------------------------------------------------------------------------------------

  @Test fun sezioneNonSiCalcolano() =
    shoot("sezione-non-si-calcolano", "Qui le persone non si calcolano", "Indicizzatore", "Impostazioni → Ricerca e modelli") { SectionScene(local, FacesStatus.Off, computesHere = false) }

  @Test fun sezioneNonSiCalcolanoTestoGrande() =
    shoot("sezione-non-si-calcolano", "Qui le persone non si calcolano", fontScale = LARGE_TEXT) { SectionScene(local, FacesStatus.Off, computesHere = false) }

  @Test fun sezioneSpentaSenzaViaggiano() = shoot("sezione-spenta", "Attiva riconoscimento dei volti", "nessun familiare li vede") { SectionScene(FacesState.Off, FacesStatus.Off) }

  @Test fun sezioneSpentaTestoGrande() = shoot("sezione-spenta", "Attiva riconoscimento dei volti", fontScale = LARGE_TEXT) { SectionScene(FacesState.Off, FacesStatus.Off) }

  // --- DR-1: Impostazioni -> Persone ---------------------------------------------------------------------------------------------------------

  private val tablet = ComputingDevice("Tablet di casa", thisDevice = false, excluded = false)

  private fun settings(computesHere: Boolean) = PeopleSettingsState(
    shown = PeopleSwitchShown.LOCAL_ONLY,
    devices = if (computesHere) listOf(ComputingDevice("Telefono", thisDevice = true, excluded = false)) else listOf(tablet),
    progress = if (computesHere) idle else FacesStatus.Waiting(FacesWait.NOT_INDEXER),
    excludedHere = false,
    hiddenPeople = 0,
    models = listOf(FaceModelRow("YuNet", "2023mar", "MIT"), FaceModelRow("SFace", "2021dec", "Apache 2.0")),
    canErase = false,
    erase = null,
    computesHere = computesHere,
  )

  private val handlers = PeopleSettingsHandlers(
    onActivate = {}, onOpenNotice = {}, onExclude = {}, onInclude = {}, onOpenHidden = null, onOpenLicenses = {}, onOpenDiagnostics = null, onErase = null,
  )

  @Test fun impostazioniNonSiCalcolano() = shoot("impostazioni-non-si-calcolano", "Qui le persone non si calcolano", "Indicizzatore") {
    PeopleSettingsScreen(state = settings(computesHere = false), handlers = handlers, onBack = {})
  }

  @Test fun impostazioniNonSiCalcolanoTestoGrande() = shoot("impostazioni-non-si-calcolano", "Qui le persone non si calcolano", fontScale = LARGE_TEXT) {
    PeopleSettingsScreen(state = settings(computesHere = false), handlers = handlers, onBack = {})
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
