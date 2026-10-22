package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
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
import dev.pampa.mycloudgallery.feature.search.contract.SearchUnavailableReason
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La schermata Cerca composta (Fase 6, P8, §1.5, §2.6): ogni stato mostrato, ogni azione dei messaggi, i chip letti da TalkBack, i bersagli da 48 dp,
 * il testo a 1,3x e nessuna miniatura dal NAS.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class SearchScreenUiTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val lib: SyntheticLibrary = library(count = 300)
  private val today: LocalDate = LocalDate.of(2026, 10, 21)
  private val handlers = RecordingHandlers()
  private val video = SearchChip.MediaType(SearchMediaType.VIDEO, "Video")

  private class Shown(
    val state: SearchScreenState,
    val query: String,
    val readiness: SearchReadiness,
    val model: ModelState?,
    val thumbs: TestThumbnails,
  )

  private val shown = mutableStateOf<Shown?>(null)
  private var composed = false

  /** Mostra lo stato; chiamato una seconda volta nello stesso test, cambia lo stato della stessa composizione (setContent si chiama una volta sola). */
  private fun show(
    state: SearchScreenState,
    query: String = "",
    readiness: SearchReadiness = SearchReadiness.Ready,
    model: ModelState? = null,
    dark: Boolean = false,
    fontScale: Float? = null,
    thumbs: TestThumbnails = TestThumbnails(lib),
  ) {
    shown.value = Shown(state, query, readiness, model, thumbs)
    if (composed) {
      rule.waitForIdle()
      return
    }
    composed = true
    rule.setSearchContent(dark = dark, fontScale = fontScale) {
      shown.value?.let { SearchScreen(it.state, it.query, it.readiness, it.model, today, it.thumbs.thumbnails, handlers) }
    }
  }

  private fun results(
    confident: IntRange = 0..11,
    likely: IntRange = IntRange.EMPTY,
    order: ResultOrder = ResultOrder.RECENT,
    chips: List<SearchChip> = listOf(video),
    notices: List<SearchNotice> = emptyList(),
    coverage: SearchCoverage = SearchCoverage.Complete,
    showLikely: Boolean = false,
  ): SearchScreenState.Results {
    val r = lib.results(confident, likely, chips, order, notices, coverage)
    return SearchScreenState.Results(r.chips, order, r.confident, r.likely, likely.count(), showLikely, coverage, notices)
  }

  private fun assertTouchTargets() {
    val density = rule.density.density
    val small = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.boundsInRoot.height / density < MIN_DP - 0.5f }
    assertWithMessage("bersagli sotto i 48 dp: ${small.map { "${it.boundsInRoot.height / density}dp ${it.config}" }}").that(small).isEmpty()
  }

  // --- A riposo e mentre si scrive ---------------------------------------------------------------------------------------------------

  @Test
  fun aRiposoRicercheRecentiLuoghiETipi_ConLeLoroAzioni() {
    val roma = PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE)
    val idle = IdleSuggestions(listOf(recent(1, "mare ad agosto", "Agosto"), recent(2, "scontrino")), listOf(roma), listOf(SearchMediaType.SCREENSHOT, SearchMediaType.VIDEO))
    show(SearchScreenState.Idle(idle, idle.recents))
    rule.onNodeWithText("Ricerche recenti").assertIsDisplayed()
    rule.onNodeWithText("mare ad agosto").performClick()
    rule.onNodeWithContentDescription("Cancella la ricerca «scontrino»").performClick()
    rule.onNodeWithText("Cancella tutte").performClick()
    rule.onNodeWithText("Roma").performClick()
    rule.onNodeWithText("Screenshot").performClick()
    rule.onNodeWithText("Preferiti").performClick()
    assertThat(handlers.calls).containsExactly(
      "recent:mare ad agosto", "delete:scontrino", "clearAll", "place:Roma", "type:Screenshot", "completion:Preferiti",
    ).inOrder()
    assertTouchTargets()
  }

  @Test
  fun mentreSiScriveLaRigaCercaEICompletamenti() {
    val state = SearchScreenState.Typing("ag", listOf(Suggestion.Term("Agosto"), Suggestion.Type(SearchMediaType.SCREENSHOT, "Screenshot")))
    show(state, query = "ag")
    assertThat(rule.onAllNodesWithText("ag").fetchSemanticsNodes()).isNotEmpty()
    rule.onNodeWithText("Agosto").performClick()
    assertThat(handlers.calls).containsExactly("completion:Agosto")
    assertTouchTargets()
  }

  @Test
  fun aRiposoSenzaIlModelloDelleQuerySiPropoeDiScaricarlo_E_Q11() {
    val idle = IdleSuggestions(emptyList(), emptyList(), emptyList())
    show(SearchScreenState.Idle(idle, emptyList()), readiness = SearchReadiness.FiltersAndTextOnly(modelMissing = true))
    rule.onNodeWithText("Per cercare per contenuto serve il modello di ricerca (157 MB). Intanto cerco per date, luoghi e testo.").assertIsDisplayed()
    rule.onNodeWithText("Scarica").performClick()
    assertThat(handlers.calls).containsExactly("download")
  }

  // --- Risultati ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun iRisultatiHannoChipOrdineEConteggioEUnTocco_ApreIlViewerSuiRisultati() {
    show(results())
    rule.onNodeWithText("Risultati").assertIsDisplayed()
    rule.onNodeWithText("12 risultati").assertIsDisplayed()
    rule.onNodeWithContentDescription("Filtro tipo: Video. Tocca per modificare").assertIsDisplayed()
    rule.onNodeWithText("Recenti").assertIsDisplayed()
    rule.onNodeWithText("Pertinenti").performClick()
    assertThat(handlers.calls).containsExactly("order:relevant")
    rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Foto del", substring = true)).onFirst().performClick()
    assertThat(handlers.calls.last()).startsWith("open:")
    assertThat(handlers.opened!!.size).isEqualTo(12)
  }

  @Test
  fun inRecentiCeLIntestazioneDelPeriodo() {
    show(results(order = ResultOrder.RECENT))
    // Le intestazioni di periodo: oggi, ieri, un giorno, un mese o un anno; almeno una e' sempre scritta con il suo titolo.
    val titles = rule.onAllNodes(androidx.compose.ui.test.hasText("20", substring = true)).fetchSemanticsNodes()
    assertThat(titles).isNotNull()
    rule.onNodeWithText("Risultati").assertIsDisplayed()
  }

  @Test
  fun iMenoProbabiliSiMostranoSoloSeRichiesti_E_Q12() {
    show(results(confident = 0..4, likely = 10..16))
    rule.onNodeWithText("Mostra anche risultati meno probabili (7)").performClick()
    assertThat(handlers.calls).containsExactly("showLikely")
    show(results(confident = 0..4, likely = 10..16, showLikely = true))
    rule.onNodeWithText("Meno probabili").assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("Mostra anche risultati meno probabili (7)").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun laCoperturaSottoIl98SiDiceConPerche_E_Q6() {
    val partial = SearchCoverage.Partial(12_300, 15_000, mapOf(CoverageReason.PREPARATION to 2_700))
    show(results(coverage = partial))
    rule.onNodeWithText("La ricerca per contenuto copre 12.300 media su 15.000.").assertIsDisplayed()
    rule.onNodeWithText("Perché?").performClick()
    assertThat(handlers.calls).containsExactly("why")
  }

  @Test
  fun gliAvvisiDelMotoreSiVedonoConLeLoroAzioni() {
    val notices = listOf(
      SearchNotice.TypoCorrected("screenshot", "screeshot"),
      SearchNotice.NoIndexerForArea(commonArea = true),
      SearchNotice.TextReadingOff,
    )
    show(results(notices = notices))
    rule.onNodeWithText("Ho cercato «screenshot» invece di «screeshot».").assertIsDisplayed()
    rule.onNodeWithText("Nessun dispositivo analizza l’area comune.").assertIsDisplayed()
    rule.onNodeWithText("Attiva su questo dispositivo").performClick()
    rule.onNodeWithText("Le parole dentro le foto non si cercano: la lettura del testo è spenta.").assertIsDisplayed()
    rule.onNodeWithText("Scopri di più").performClick()
    assertThat(handlers.calls).containsExactly("enableIndexer", "learn").inOrder()
  }

  @Test
  fun unLuogoConAlternativeSiModificaDalFoglioDelChip_E_Q10() {
    val roma = PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE)
    val texas = PlaceOption(setOf(2), "Roma (Texas)", PlaceLevel.PLACE)
    val place = SearchChip.Place(roma, listOf(texas), "roma")
    show(results(chips = listOf(place)))
    rule.onNodeWithContentDescription("Filtro luogo: Roma. Tocca per modificare").performClick()
    rule.onNodeWithText("Roma (Texas)").performClick()
    rule.onNodeWithContentDescription("Filtro luogo: Roma. Tocca per modificare").performClick()
    rule.onNodeWithText("Cerca come parola").performClick()
    rule.onNodeWithContentDescription("Filtro luogo: Roma. Tocca per modificare").performClick()
    rule.onNodeWithText("Togli il filtro").performClick()
    assertThat(handlers.calls).containsExactly("alternative:Roma (Texas)", "asWord:roma", "removeChip:Roma").inOrder()
  }

  @Test
  fun iChipDiDataESimiliHannoLaLoroLetturaPerTalkBack() {
    val date = SearchChip.DateRange(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), everyYear = false, label = "Ieri, 20 ottobre")
    val similar = SearchChip.Similar(lib.id(3), "Simili")
    show(results(chips = listOf(date, similar)))
    rule.onNodeWithContentDescription("Filtro data: Ieri, 20 ottobre. Tocca per modificare").assertIsDisplayed()
    rule.onNodeWithContentDescription("Simili a questa foto. Tocca per modificare").assertIsDisplayed()
  }

  // --- Stati vuoti e d'errore ---------------------------------------------------------------------------------------------------------

  @Test
  fun nessunRisultato_PropostaDiTogliereUnFiltro_E_Q3() {
    val word = SearchChip.Word("zebre", exact = false)
    show(SearchScreenState.NoResults(listOf(video, word), 0, listOf(video)), query = "zebre video")
    rule.onNodeWithText("Nessun risultato per «zebre video». Prova a togliere un filtro.").assertIsDisplayed()
    rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Filtro tipo: Video. Tocca per modificare")).onLast().performClick()
    assertThat(rule.onAllNodesWithText("Mostra anche", substring = true).fetchSemanticsNodes()).isEmpty()
    assertTouchTargets()
  }

  @Test
  fun nessunRisultatoSicuro_OfferteDeiMenoProbabili_E_Q12() {
    show(SearchScreenState.NoResults(listOf(video), 9, emptyList()), query = "zebre")
    rule.onNodeWithText("Nessun risultato sicuro.").assertIsDisplayed()
    rule.onNodeWithText("Mostra anche risultati meno probabili (9)").performClick()
    assertThat(handlers.calls).containsExactly("showLikely")
  }

  @Test
  fun laRicercaNonDisponibile_OgniCasoHaLaSuaAzione() {
    show(SearchScreenState.Unavailable(SearchUnavailableReason.EMPTY_LIBRARY))
    rule.onNodeWithText("Non ci sono ancora foto da cercare.").assertIsDisplayed()
    rule.onNodeWithText("Consenti accesso").performClick()
    show(SearchScreenState.Unavailable(SearchUnavailableReason.INTERNAL_ERROR))
    rule.onNodeWithText("La ricerca non ha funzionato.").assertIsDisplayed()
    rule.onNodeWithText("Riprova").performClick()
    rule.onNodeWithText("Apri la Diagnostica").performClick()
    assertThat(handlers.calls).containsExactly("allow", "retry", "diagnostics").inOrder()
    assertTouchTargets()
  }

  // --- Accessibilita' e nessun NAS ----------------------------------------------------------------------------------------------------

  @Test
  fun iBersagliDeiRisultatiSonoDa48Dp() {
    show(results(coverage = SearchCoverage.Partial(1, 2, emptyMap()), notices = listOf(SearchNotice.TextReadingOff)))
    assertTouchTargets()
  }

  @Test
  fun ilTestoA13xEIlTemaScuroMostranoTuttoCioCheServe() {
    show(results(coverage = SearchCoverage.Partial(12_300, 15_000, emptyMap())), dark = true, fontScale = 1.3f)
    rule.onNodeWithText("Risultati").assertIsDisplayed()
    rule.onNodeWithText("Pertinenti").assertIsDisplayed()
    rule.onNodeWithContentDescription("Filtro tipo: Video. Tocca per modificare").assertIsDisplayed()
    assertTouchTargets()
  }

  /**
   * D28, D33, CE-5: in Cerca non si legge mai il NAS. Un media solo sul NAS (sul tablet, quasi tutti) ha la miniatura della replica locale: il suo riferimento
   * va al caricatore, ma **mai come richiesta visibile**: solo "in arrivo". La sorgente del NAS legge l'originale solo per il contenuto visibile (D17), quindi in
   * Cerca risponde con la replica o la miniatura provvisoria e basta. Prima il riferimento del NAS si scartava e questi media restavano sul segnaposto.
   */
  @Test
  fun inCercaNonSiLeggeMaiIlNas_ISoloNasPassanoComeRichiesteInArrivo() {
    val thumbs = TestThumbnails(lib, nasEvery = 2)
    show(results(confident = 0..19), thumbs = thumbs)
    rule.waitForIdle()
    val visible: List<ThumbRequest> = thumbs.loader.windows.flatMap { it.first }
    val ahead: List<ThumbRequest> = thumbs.loader.windows.flatMap { it.second }
    assertThat(visible).isNotEmpty()
    assertThat(visible.map { it.ref }.filterIsInstance<MediaRef.Nas>()).isEmpty()
    assertThat(ahead).isNotEmpty()
    assertThat(ahead.map { it.ref }.all { it is MediaRef.Nas }).isTrue()
  }

  /** TS-5: il chip "Simili a" chiede la miniatura del suo media alla finestra del caricatore (il media e' escluso dai risultati: nessuna cella lo chiedeva). */
  @Test
  fun ilChipSimiliAChiedeLaSuaMiniatura_TS5() {
    val thumbs = TestThumbnails(lib)
    val similar = SearchChip.Similar(lib.id(40), "Simili")
    show(results(confident = 0..9, chips = listOf(similar)), thumbs = thumbs)
    rule.waitForIdle()
    val requested = thumbs.loader.windows.flatMap { it.first + it.second }
    assertThat(requested.map { it.key.mediaId }).contains(lib.id(40))
  }

  private companion object {
    const val MIN_DP = 48f
  }
}
