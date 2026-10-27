package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 8 (P10, D26, §2.7, E-F36): la ricerca per persona composta. La riga "Persone" a riposo (pillole da 48 dp, una per nome), i completamenti dei nomi, il
 * chip "Persona" (letto da TalkBack) con il foglio delle altre letture ("Cerca la data", "Cerca il luogo", "Cerca come parola", "Cerca la persona"),
 * l'avviso "Sto ancora riconoscendo i volti: 60%". Bersagli da 48 dp, testo a 1,3x, tema scuro. Solo nomi inventati.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class PeopleSearchScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val lib: SyntheticLibrary = library(count = 300)
  private val today: LocalDate = LocalDate.of(2026, 10, 21)
  private val handlers = RecordingHandlers()

  private fun option(n: Int, label: String, mode: PeopleMode = PeopleMode.ANY) =
    PersonOption(listOf(PersonId.fromBytes(ByteArray(PersonId.BYTES) { n.toByte() })), mode, label)

  private val marco = option(1, "Marco")
  private val anna = option(2, "Anna")

  private val festa = SearchChip.DateRange(LocalDate.of(2000, 12, 24), LocalDate.of(2000, 12, 26), everyYear = true, label = "Natale (24–26 dicembre)")
  private val natale = option(3, "Natale")
  private val personNatale = SearchChip.Person(natale, listOf(ChipAlternative(festa), ChipAlternative(SearchChip.Word("natale", false))), "natale")

  private class Shown(val state: SearchScreenState, val query: String)

  private val shown = mutableStateOf<Shown?>(null)
  private var composed = false

  private fun show(state: SearchScreenState, query: String = "", dark: Boolean = false, fontScale: Float? = null) {
    shown.value = Shown(state, query)
    if (composed) {
      rule.waitForIdle()
      return
    }
    composed = true
    val thumbs = TestThumbnails(lib)
    rule.setSearchContent(dark = dark, fontScale = fontScale) {
      shown.value?.let { SearchScreen(it.state, it.query, SearchReadiness.Ready, null, today, thumbs.thumbnails, handlers) }
    }
  }

  private fun results(chips: List<SearchChip>, notices: List<SearchNotice> = emptyList()): SearchScreenState.Results {
    val r = lib.results(confident = 0..11, likely = 20..24, chips = chips, order = ResultOrder.RECENT, notices = notices)
    return SearchScreenState.Results(r.chips, ResultOrder.RECENT, r.confident, r.likely, 5, false, SearchCoverage.Complete, notices)
  }

  private fun assertTouchTargets() {
    val density = rule.density.density
    val small = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.boundsInRoot.height / density < MIN_DP - 0.5f }
    assertWithMessage("bersagli sotto i 48 dp: ${small.map { "${it.boundsInRoot.height / density}dp ${it.config}" }}").that(small).isEmpty()
  }

  @Test
  fun aRiposoLaRigaDellePersoneCercaIlNome() {
    val idle = IdleSuggestions(emptyList(), listOf(PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE)), listOf(SearchMediaType.VIDEO), people = listOf(marco, anna))
    show(SearchScreenState.Idle(idle, idle.recents))
    rule.onNodeWithText("Persone").assertIsDisplayed()
    rule.onNodeWithContentDescription("Cerca le foto di Marco").performClick()
    rule.onNodeWithContentDescription("Cerca le foto di Anna").performClick()
    assertThat(handlers.calls).containsExactly("person:Marco", "person:Anna").inOrder()
    assertTouchTargets()
  }

  @Test
  fun senzaPersoneNonCeLaRiga() {
    val idle = IdleSuggestions(emptyList(), listOf(PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE)), emptyList())
    show(SearchScreenState.Idle(idle, idle.recents))
    assertThat(rule.onAllNodesWithTextCount("Persone")).isEqualTo(0)
  }

  @Test
  fun unNomeSiCompletaMentreSiScrive() {
    show(SearchScreenState.Typing("ma", listOf(Suggestion.PersonSuggestion(marco))), query = "ma")
    rule.onNodeWithText("Marco").performClick()
    assertThat(handlers.calls).containsExactly("completion:Marco")
    assertTouchTargets()
  }

  @Test
  fun ilChipPersonaSiLeggeIntero_ESiModificaDalFoglio() {
    val chip = SearchChip.Person(option(4, "Marco e Anna", PeopleMode.ALL), listOf(ChipAlternative(SearchChip.Word("marco e anna", false))), "marco e anna")
    show(results(listOf(chip)))
    rule.onNodeWithContentDescription("Filtro persona: Marco e Anna. Tocca per modificare").performClick()
    rule.onNodeWithText("Cerca come parola").performClick()
    rule.onNodeWithContentDescription("Filtro persona: Marco e Anna. Tocca per modificare").performClick()
    rule.onNodeWithText("Togli il filtro").performClick()
    assertThat(handlers.calls).containsExactly("reading:Marco e Anna->marco e anna", "removeChip:Marco e Anna").inOrder()
  }

  @Test
  fun unNomeCheEAncheUnaFestaPropone_LaFesta_ELaParola_E_F36() {
    show(results(listOf(personNatale)))
    rule.onNodeWithContentDescription("Filtro persona: Natale. Tocca per modificare").performClick()
    rule.onNodeWithText("Natale (24–26 dicembre)").assertIsDisplayed()
    rule.onNodeWithText("Cerca la data").assertIsDisplayed()
    rule.onNodeWithText("Natale (24–26 dicembre)").performClick()
    assertThat(handlers.calls).containsExactly("reading:Natale->Natale (24–26 dicembre)")
    assertTouchTargets()
  }

  @Test
  fun dallaFestaSiTornaAllaPersona_E_F36() {
    val asFestival = festa.copy(person = natale)
    show(results(listOf(asFestival)))
    rule.onNodeWithContentDescription("Filtro data: Natale (24–26 dicembre). Tocca per modificare").performClick()
    rule.onNodeWithText("Cerca la persona").assertIsDisplayed()
    rule.onNodeWithText("Cerca la persona").performClick()
    assertThat(handlers.calls).containsExactly("reading:Natale (24–26 dicembre)->Natale")
  }

  @Test
  fun unLuogoConUnaPersonaPropone_LaPersona_E_Gli_Omonimi() {
    val roma = PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE)
    val texas = PlaceOption(setOf(2), "Roma (Texas)", PlaceLevel.PLACE)
    val place = SearchChip.Place(roma, listOf(texas), "roma", option(5, "Roma"))
    show(results(listOf(place)))
    rule.onNodeWithContentDescription("Filtro luogo: Roma. Tocca per modificare").performClick()
    rule.onNodeWithText("Cerca la persona").assertIsDisplayed()
    rule.onNodeWithText("Roma (Texas)").assertIsDisplayed()
    rule.onNodeWithText("Cerca la persona").performClick()
    assertThat(handlers.calls).containsExactly("reading:Roma->Roma")
  }

  @Test
  fun inAttesaDelPrimoCalcoloSiDice_ConLaPercentuale() {
    show(results(listOf(SearchChip.Person(marco, emptyList(), "marco")), notices = listOf(SearchNotice.PeopleRecognizing(60))))
    rule.onNodeWithText("Sto ancora riconoscendo i volti: 60%.").assertIsDisplayed()
    rule.onNodeWithText("Persone").assertIsDisplayed()
  }

  @Test
  fun ilTestoA13xEIlTemaScuroMostranoTuttoCioCheServe() {
    show(
      results(listOf(personNatale, SearchChip.Person(option(4, "Marco e Anna", PeopleMode.ALL), emptyList(), "marco e anna")), listOf(SearchNotice.PeopleRecognizing(15))),
      dark = true,
      fontScale = 1.3f,
    )
    rule.onNodeWithContentDescription("Filtro persona: Natale. Tocca per modificare").assertIsDisplayed()
    rule.onNodeWithText("Sto ancora riconoscendo i volti: 15%.").assertIsDisplayed()
    assertTouchTargets()
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

  private companion object {
    const val MIN_DP = 48f
  }
}
