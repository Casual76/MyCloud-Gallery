package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.ChipAlternative
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della ricerca per persona (Fase 8, P10, docs/screenshots/8-P10): la riga "Persone" a riposo, i nomi che si completano, i chip "Persona" (una persona,
 * piu' persone) con i risultati, l'avviso "Sto ancora riconoscendo i volti", il foglio del chip con le altre letture di un nome che e' anche una festa o un luogo.
 * Chiaro e scuro, telefono e tablet, piu' il testo a 1,3x sul telefono in chiaro. **Solo nomi inventati**, miniature sintetiche, nessun volto. `gradlew
 * :feature-search:recordRoborazziDebug --tests "*PeopleSearchScreenshot*"` scrive i PNG; nei normali `testDebugUnitTest` verificano solo che le scene compongano.
 */
internal enum class PeopleScene(val file: String) {
  IDLE("cerca-persone-a-riposo"),
  TYPING("cerca-persone-scrivendo"),
  ONE("cerca-persona-risultati"),
  MANY("cerca-persone-e-o-risultati"),
  PROGRESS("cerca-persona-riconoscimento-in-corso"),
  SHEET_ONE("cerca-persona-foglio-del-chip"),
  SHEET_FESTA("cerca-persona-foglio-festa"),
  SHEET_PLACE("cerca-persona-foglio-luogo"),
}

internal object PeopleSearchScenes {
  private val lib = library(count = 400, seed = 11L)
  private val today = LocalDate.of(2026, 10, 21)
  private val thumbs = TestThumbnails(lib)

  private fun option(n: Int, label: String, mode: PeopleMode = PeopleMode.ANY) =
    PersonOption(listOf(PersonId.fromBytes(ByteArray(PersonId.BYTES) { n.toByte() })), mode, label)

  private val marco = option(1, "Marco")
  private val anna = option(2, "Anna")
  private val giulia = option(3, "Giulia")
  private val luca = option(4, "Luca")
  private val sara = option(5, "Sara")
  private val elena = option(6, "Elena")
  private val natale = option(7, "Natale")
  private val roma = option(8, "Roma")

  private val festa = SearchChip.DateRange(LocalDate.of(2000, 12, 24), LocalDate.of(2000, 12, 26), everyYear = true, label = "Natale (24–26 dicembre, tutti gli anni)")
  private val marcoChip = SearchChip.Person(marco, listOf(ChipAlternative(SearchChip.Word("marco", false))), "marco")
  private val bothChip = SearchChip.Person(option(9, "Marco e Anna", PeopleMode.ALL), listOf(ChipAlternative(SearchChip.Word("marco e anna", false))), "marco e anna")
  private val anyChip = SearchChip.Person(option(10, "Marco o Anna", PeopleMode.ANY), listOf(ChipAlternative(SearchChip.Word("marco o anna", false))), "marco o anna")
  private val natalePerson = SearchChip.Person(natale, listOf(ChipAlternative(festa), ChipAlternative(SearchChip.Word("natale", false))), "natale")
  private val place = SearchChip.Place(PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE), listOf(PlaceOption(setOf(2), "Roma (Texas)", PlaceLevel.PLACE)), "roma", roma)
  private val video = SearchChip.MediaType(SearchMediaType.VIDEO, "Video")

  private val idle = IdleSuggestions(
    recents = listOf(recent(1, "Marco al mare", "Marco"), recent(2, "Anna e Giulia a Natale", "Anna e Giulia", "Natale")),
    places = listOf(PlaceOption(setOf(11), "Firenze", PlaceLevel.PLACE), PlaceOption(setOf(12), "Toscana", PlaceLevel.REGION)),
    types = listOf(SearchMediaType.SCREENSHOT, SearchMediaType.VIDEO, SearchMediaType.DOCUMENT),
    people = listOf(marco, anna, giulia, luca, sara, elena),
  )

  private fun results(chips: List<SearchChip>, notices: List<SearchNotice> = emptyList(), likely: Boolean = true): SearchScreenState.Results {
    val r = lib.results(confident = 0..35, likely = if (likely) 120..127 else IntRange.EMPTY, chips = chips, order = ResultOrder.RECENT, notices = notices)
    return SearchScreenState.Results(r.chips, ResultOrder.RECENT, r.confident, r.likely, if (likely) 8 else 0, false, SearchCoverage.Complete, notices)
  }

  private fun state(scene: PeopleScene): SearchScreenState = when (scene) {
    PeopleScene.IDLE -> SearchScreenState.Idle(idle, idle.recents)
    PeopleScene.TYPING -> SearchScreenState.Typing("ma", listOf(Suggestion.PersonSuggestion(marco), Suggestion.PersonSuggestion(option(12, "Maria Teresa")), Suggestion.Recent(idle.recents[0])))
    PeopleScene.ONE, PeopleScene.SHEET_ONE -> results(listOf(marcoChip))
    PeopleScene.MANY -> results(listOf(bothChip, video), likely = false)
    PeopleScene.PROGRESS -> results(listOf(anyChip), notices = listOf(SearchNotice.PeopleRecognizing(60)))
    PeopleScene.SHEET_FESTA -> results(listOf(natalePerson))
    PeopleScene.SHEET_PLACE -> results(listOf(place))
  }

  private fun query(scene: PeopleScene): String = when (scene) {
    PeopleScene.TYPING -> "ma"
    PeopleScene.ONE, PeopleScene.SHEET_ONE -> "Marco al mare"
    PeopleScene.MANY -> "video di Marco e Anna"
    PeopleScene.PROGRESS -> "Marco o Anna"
    PeopleScene.SHEET_FESTA -> "Natale"
    PeopleScene.SHEET_PLACE -> "Roma"
    PeopleScene.IDLE -> ""
  }

  private fun sheetChip(scene: PeopleScene): SearchChip? = when (scene) {
    PeopleScene.SHEET_ONE -> bothChip
    PeopleScene.SHEET_FESTA -> natalePerson
    PeopleScene.SHEET_PLACE -> place
    else -> null
  }

  @Composable
  fun Content(scene: PeopleScene) {
    val sheet = sheetChip(scene)
    val page: @Composable () -> Unit = {
      SearchScreen(
        state = state(scene),
        query = query(scene),
        readiness = SearchReadiness.Ready,
        queryModel = null,
        today = today,
        thumbnails = thumbs.thumbnails,
        handlers = NoSearchHandlers,
      )
    }
    if (sheet == null) {
      page()
    } else {
      // Il foglio del chip sopra la pagina, come in uso (la finestra vera dei fogli non entra in uno screenshot): i risultati, una velatura e il foglio in basso.
      Box(modifier = Modifier.fillMaxSize()) {
        page()
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        Surface(
          modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 560.dp),
          shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
          color = MaterialTheme.colorScheme.surface,
        ) {
          ChipSheetContent(sheet, NoSearchHandlers, onDismiss = {})
        }
      }
    }
  }
}

internal abstract class PeopleSearchScreenshotBase(
  private val scene: PeopleScene,
  private val dark: Boolean,
  private val scale: Float?,
  private val device: String,
) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun capture() {
    rule.setSearchContent(dark = dark, fontScale = scale) { PeopleSearchScenes.Content(scene) }
    rule.waitForIdle()
    val name = "${scene.file}-${if (dark) "scuro" else "chiaro"}-$device${if (scale != null) "-testo-grande" else ""}"
    rule.onRoot().captureRoboImage("../docs/screenshots/8-P10/$name.png")
  }

  companion object {
    /** Ogni scena in chiaro e scuro; il testo grande solo in chiaro (telefono). */
    fun parameters(wide: Boolean): List<Array<Any?>> = PeopleScene.entries.flatMap { scene ->
      buildList {
        add(arrayOf<Any?>(scene, false, null))
        add(arrayOf<Any?>(scene, true, null))
        if (!wide) add(arrayOf<Any?>(scene, false, 1.3f))
      }
    }
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h780dp-xhdpi")
internal class PeopleSearchPhoneScreenshotTest(scene: PeopleScene, dark: Boolean, scale: Float?) : PeopleSearchScreenshotBase(scene, dark, scale, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = false)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h800dp-xhdpi")
internal class PeopleSearchTabletScreenshotTest(scene: PeopleScene, dark: Boolean, scale: Float?) : PeopleSearchScreenshotBase(scene, dark, scale, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = true)
  }
}
