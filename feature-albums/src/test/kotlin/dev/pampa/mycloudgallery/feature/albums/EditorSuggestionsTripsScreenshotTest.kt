package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceAlternative
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.ProposalNote
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.albums.contract.BroadHint
import dev.pampa.mycloudgallery.feature.albums.contract.SmartEditorState
import dev.pampa.mycloudgallery.feature.albums.contract.TripsState
import dev.pampa.mycloudgallery.feature.albums.editor.EditorProblem
import dev.pampa.mycloudgallery.feature.albums.editor.NoSmartEditorHandlers
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorScreen
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorUi
import dev.pampa.mycloudgallery.feature.albums.page.NoSuggestionsHandlers
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionKind
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionsScreen
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionsUi
import dev.pampa.mycloudgallery.feature.albums.testing.AlbumSamples
import dev.pampa.mycloudgallery.feature.albums.trips.NoTripsHandlers
import dev.pampa.mycloudgallery.feature.albums.trips.TripsScreen
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot dell'editor delle regole, di "Suggerite" / "Da condividere" e dei viaggi (Fase 7, P10): ogni caso del §1.5 `SmartEditorState`,
 * `SuggestionsState` e `TripsState`. Chiaro e scuro, telefono e tablet, piu' il testo grande dove la pagina e' piena di controlli.
 */
abstract class EditorSuggestionsTripsShots(private val dark: Boolean, private val device: String) : AlbumsComposeTest() {

  private val scenes = Scenes()
  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, expect: List<String> = emptyList(), fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale) { AlbumHost { content() } }
    for (text in expect) {
      assertWithMessage("manca «$text» nella scena $name").that(composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()).isNotEmpty()
    }
    val suffix = if (fontScale != null) "-testo-grande" else ""
    if (ScreenshotPolicy.capture(name, device, fontScale)) composeRule.onRoot().captureRoboImage("../docs/screenshots/7-P10/$name-$look-$device$suffix.png")
  }

  // --- Editor delle regole ------------------------------------------------------------------------------------------------------------

  private val first = LocalDayRange(LocalDate.of(2026, 3, 13), LocalDate.of(2026, 3, 19))
  private val second = LocalDayRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 6))
  private val oslo = PlaceRule(listOf(3143244), GeoPoint(59.91, 10.75), 25, "Oslo", PlaceScope.AROUND)
  private val osloUs = PlaceRule(listOf(5000001), GeoPoint(41.9, -85.5), 15, "Oslo (Michigan)", PlaceScope.AROUND)
  private val sample: List<MediaId> = (1..12).map { ShareFixtures.media(it) }
  private val preview = SmartPreview(confident = 230, suggested = 12, tooBroad = false, sample = sample, searchUsed = true)

  private fun editor(ui: SmartEditorUi): @Composable () -> Unit = {
    SmartEditorScreen(ui, scenes.thumbnails.thumbnails, NoSmartEditorHandlers)
  }

  private fun proposal(
    rules: SmartRules = SmartRules(SmartPeriod.Ranges(listOf(first, second)), oslo, null),
    state: SmartEditorState? = null,
    previewing: Boolean = false,
    problem: EditorProblem? = null,
    creating: Boolean = true,
    canSave: Boolean = true,
    preview: SmartPreview = this.preview,
  ): SmartEditorUi = SmartEditorUi(
    title = "Oslo 2026",
    state = state ?: SmartEditorState.Proposal(rules, listOf(PlaceAlternative(oslo, 220), PlaceAlternative(osloUs, 3)), listOf(first, second), preview, setOf(ProposalNote.SEVERAL_SERIES)),
    rules = rules,
    alternatives = listOf(PlaceAlternative(oslo, 220), PlaceAlternative(osloUs, 3)),
    series = listOf(first, second),
    notes = setOf(ProposalNote.SEVERAL_SERIES),
    preview = preview,
    previewing = previewing,
    problem = problem,
    canSave = canSave,
    creating = creating,
  )

  @Test
  fun editorInterpretaIlNome() =
    shoot("editor-interpreta", listOf("Oslo 2026"), content = editor(SmartEditorUi("Oslo 2026", SmartEditorState.Interpreting("Oslo 2026"), canSave = false)))

  @Test
  fun editorProposta() = shoot("editor-proposta", listOf("Oslo"), content = editor(proposal()))

  @Test
  fun editorPropostaTestoGrande() = shoot("editor-proposta", listOf("Oslo"), LARGE_TEXT, editor(proposal()))

  @Test
  fun editorPropostaInRicalcolo() = shoot("editor-ricalcolo", content = editor(proposal(previewing = true)))

  @Test
  fun editorRegoleTroppoLarghe() {
    val rules = SmartRules(null, null, null)
    val broad = SmartPreview(confident = 5_200, suggested = 0, tooBroad = true, sample = sample, searchUsed = false)
    val ui = proposal(
      rules = rules, preview = broad, canSave = false,
      state = SmartEditorState.TooBroad(rules, broad, setOf(BroadHint.NARROW_PERIOD, BroadHint.ADD_PLACE)),
    )
    shoot("editor-troppo-larghe", content = editor(ui))
  }

  @Test
  fun editorDescrizioneSenzaIlMotoreDiRicerca() {
    val rules = SmartRules(SmartPeriod.Ranges(listOf(first)), oslo, "neve sul tetto")
    val noSearch = SmartPreview(confident = 140, suggested = 0, tooBroad = false, sample = sample, searchUsed = false)
    val ui = proposal(rules = rules, preview = noSearch, state = SmartEditorState.NoSearch(rules, noSearch))
    shoot("editor-senza-ricerca", content = editor(ui))
  }

  @Test
  fun editorCambiaLeRegoleDiUnAlbumCheCe() = shoot("editor-modifica", content = editor(proposal(creating = false)))

  @Test
  fun editorNonSiPuoSalvare() = shoot("editor-problema", content = editor(proposal(problem = EditorProblem.RULES_TOO_LONG, canSave = false)))

  // --- Suggerite, Da condividere -----------------------------------------------------------------------------------------------------

  private fun suggestionsUi(kind: SuggestionKind, selecting: Boolean = false, selected: Int = 0): SuggestionsUi {
    val view: OrderedTimelineView = scenes.content(scenes.oslo).suggested!!
    val ids = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
    val album = scenes.album(if (kind == SuggestionKind.TO_SHARE) scenes.osloShared else scenes.oslo)
    return SuggestionsUi(album, kind, view, view.size, selecting, ids.take(selected).toSet(), loaded = true)
  }

  private fun suggestions(ui: SuggestionsUi): @Composable () -> Unit = {
    SuggestionsScreen(ui, scenes.thumbnails.thumbnails, NoSuggestionsHandlers)
  }

  @Test
  fun suggerite() = shoot("suggerite", listOf("Accetta"), content = suggestions(suggestionsUi(SuggestionKind.SUGGESTED)))

  @Test
  fun suggeriteTestoGrande() = shoot("suggerite", listOf("Accetta"), LARGE_TEXT, suggestions(suggestionsUi(SuggestionKind.SUGGESTED)))

  @Test
  fun suggeriteInSelezione() = shoot("suggerite-selezione", listOf("Accetta"), content = suggestions(suggestionsUi(SuggestionKind.SUGGESTED, selecting = true, selected = 3)))

  @Test
  fun daCondividere() = shoot("da-condividere", listOf("Condividi"), content = suggestions(suggestionsUi(SuggestionKind.TO_SHARE)))

  @Test
  fun suggeriteVuote() {
    val ui = SuggestionsUi(scenes.album(scenes.oslo), SuggestionKind.SUGGESTED, loaded = true)
    shoot("suggerite-vuote", content = suggestions(ui))
  }

  // --- Viaggi -----------------------------------------------------------------------------------------------------------------------

  private val tripOne = AlbumSamples.trip.copy(cover = ShareFixtures.media(3))
  private val tripTwo = AlbumSamples.trip.copy(
    id = TripId.of(AlbumSamples.trip.kind, LocalDayRange(LocalDate.of(2025, 7, 2), LocalDate.of(2025, 7, 9)), listOf(1234)),
    days = LocalDayRange(LocalDate.of(2025, 7, 2), LocalDate.of(2025, 7, 9)), label = "Lisbona, luglio 2025", count = 412, cover = ShareFixtures.media(9), placeIds = listOf(1234),
  )
  private val event = AlbumSamples.event.copy(cover = ShareFixtures.media(14))

  private fun trips(state: TripsState, creating: TripId? = null): @Composable () -> Unit = {
    TripsScreen(state, scenes.thumbnails.thumbnails, NoTripsHandlers, creating = creating)
  }

  @Test
  fun viaggiProposte() = shoot("viaggi-proposte", listOf("Oslo, marzo 2026"), content = trips(TripsState.Proposals(listOf(tripOne, tripTwo, event), hasOlder = true)))

  @Test
  fun viaggiProposteTestoGrande() =
    shoot("viaggi-proposte", listOf("Oslo, marzo 2026"), LARGE_TEXT, trips(TripsState.Proposals(listOf(tripOne, tripTwo, event), hasOlder = true)))

  @Test
  fun viaggiMentreSiCreaUnAlbum() = shoot("viaggi-creazione", content = trips(TripsState.Proposals(listOf(tripOne, tripTwo), hasOlder = false), creating = tripOne.id))

  @Test
  fun viaggiSenzaPosizioneSoloEventi() = shoot("viaggi-senza-gps", content = trips(TripsState.NoGps(listOf(event))))

  @Test
  fun viaggiNessunaProposta() = shoot("viaggi-vuoto", content = trips(TripsState.Empty))

  companion object {
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_360_QUALIFIERS)
class EditorSuggestionsTripsPhoneScreenshotTest(dark: Boolean) : EditorSuggestionsTripsShots(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class EditorSuggestionsTripsTabletScreenshotTest(dark: Boolean) : EditorSuggestionsTripsShots(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
