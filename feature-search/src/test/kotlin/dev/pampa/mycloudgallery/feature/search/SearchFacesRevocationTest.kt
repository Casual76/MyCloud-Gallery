package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.ParsedQuery
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.engine.LibraryPlaces
import dev.pampa.mycloudgallery.core.search.engine.LibrarySuggestions
import dev.pampa.mycloudgallery.core.search.testing.FakePeopleLookup
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.core.search.testing.SimpleQueryParser
import dev.pampa.mycloudgallery.feature.search.contract.SearchFace
import dev.pampa.mycloudgallery.feature.search.contract.SearchFaces
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * CG-1 (giro 1 della fase I): i volti degli esempi della ricerca seguono **lo stesso contratto** delle Raccolte (CU-19). Una revoca che non passa dalla
 * vista (Persone spenta, ritagli cancellati: la Libreria e le foto d'origine restano uguali) toglie subito tutti i volti, dalla barra aperta e dalla terna
 * della prossima apertura; un volto torna solo se il cancello lo ripermette; un ritaglio in volo da prima della revoca non rientra. Il cancello e' finto:
 * permette o rifiuta (letto quando si chiede), puo' tenere il ritaglio in volo, e ha il suo segno delle revoche.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class SearchFacesRevocationTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val lib = SyntheticLibrary(seed = 33L, count = 240, placeIds = intArrayOf(1, 2, 3, 4, 5))
  private val anna = PersonId("00000000-0000-4000-8000-000000000007")
  private val origin: MediaId = lib.id(4)

  private class Gate(private val origin: MediaId) : SearchFaces {
    var allow = true
    var hold: CompletableDeferred<Unit>? = null
    var asked = 0
    val token = MutableStateFlow(0)

    override suspend fun face(person: PersonId, sizePx: Int): SearchFace? {
      asked++
      val permitted = allow
      val wait = hold
      wait?.await()
      return if (permitted) SearchFace(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), setOf(origin)) else null
    }

    override val revocations: Flow<Any?> get() = token
  }

  private class World(val controller: SearchController, val examples: SearchExamples, val lookup: FakePeopleLookup, val gate: Gate)

  /**
   * Il parser finto non conosce le persone: qui, come l'analizzatore vero, un nome del dizionario non e' una parola libera (resta il chip della persona),
   * cosi' la prova della persona trova le sue foto.
   */
  private val parser = object : QueryParser {
    private val simple = SimpleQueryParser()

    override fun parse(text: String, today: java.time.LocalDate, places: PlaceDictionary): ParsedQuery = simple.parse(text, today, places)

    override fun parse(text: String, today: java.time.LocalDate, places: PlaceDictionary, people: PeopleDictionary): ParsedQuery {
      val parsed = simple.parse(text, today, places)
      val names = people.entries.map { SearchText.normalize(it.name) }.toSet()
      val words = parsed.words.filterNot { SearchText.normalize(it) in names }
      return parsed.copy(words = words, semantic = if (words.isEmpty()) null else parsed.semantic)
    }
  }

  private fun TestScope.world(gate: Gate = Gate(origin)): World {
    // Anna ha 60 foto: e' un buon esempio (almeno 12 risultati).
    val lookup = FakePeopleLookup().name(anna, "Anna").sure(anna, (0 until 60).map { lib.id(it) })
    val data = FakeSearchData(lib.snapshot)
    val places = LibraryPlaces { id -> PlaceName("Luogo $id", null, "Italia") }
    val engine = InMemorySearchEngine(
      data, embedder = null, parser = parser, today = FixedToday(), places = { places.dictionary(lib.snapshot) }, people = lookup,
    )
    val history = InMemorySearchHistory()
    val suggestions = LibrarySuggestions(data, places, history, lookup)
    val examples = SearchExamples(engine, data, gate, backgroundScope, compute = StandardTestDispatcher(testScheduler), prepareDelayMillis = 10)
    val controller = SearchController(engine, suggestions, history, data, null, backgroundScope, 100, 200, examples)
    return World(controller, examples, lookup, gate)
  }

  private fun World.annaInBar(): Boolean =
    controller.strip.value.any { (it.suggestion as? Suggestion.PersonSuggestion)?.option?.ids?.contains(anna) == true }

  /** Cio' che il tondo disegnerebbe adesso per Anna (lo legge nel disegno): un volto, o niente. */
  private fun World.annaDrawn(): Boolean = examples.faceImages.image(anna, lib.snapshot) != null

  /** Una chiusura e una riapertura della ricerca. */
  private fun TestScope.cycle(w: World) {
    w.controller.onOpened()
    runCurrent()
    w.controller.clear()
    advanceTimeBy(100)
    runCurrent()
  }

  /** Apre e chiude finche' Anna e' nella terna (almeno una volta): la rotazione la porta in barra. */
  private fun TestScope.cycleUntilAnna(w: World) {
    var turns = 0
    do {
      cycle(w)
      turns++
    } while (!w.annaInBar() && turns < 16)
    assertThat(w.annaInBar()).isTrue()
  }

  @Test
  fun unaRevocaConLaStessaVistaTogliePersoneEVoltiDallaBarraApertaEDallaTernaSeguente_CG1() = runTest {
    val w = world()
    advanceTimeBy(1_000)
    runCurrent()
    cycleUntilAnna(w)
    assertThat(w.annaDrawn()).isTrue()
    // Persone spenta: la Libreria non cambia, la foto d'origine resta visibile.
    assertThat(lib.snapshot.indexOf(origin)).isAtLeast(0)
    w.lookup.available.value = false
    w.gate.allow = false
    w.gate.token.value = 1
    runCurrent()
    // Nello stesso giro: nessun volto da disegnare, nessuno tenuto, e Anna non e' piu' nella barra aperta.
    assertThat(w.annaDrawn()).isFalse()
    assertThat(w.examples.faceImages.count).isEqualTo(0)
    assertThat(w.annaInBar()).isFalse()
    // Le terne seguenti (anche quella gia' pronta per la prossima apertura): mai Anna, mai un volto.
    repeat(8) {
      cycle(w)
      assertThat(w.annaInBar()).isFalse()
      assertThat(w.annaDrawn()).isFalse()
    }
  }

  @Test
  fun dopoUnaRevocaIlVoltoTornaSoloSeIlCancelloLoRipermette_CG1() = runTest {
    val w = world()
    advanceTimeBy(1_000)
    runCurrent()
    cycleUntilAnna(w)
    assertThat(w.annaDrawn()).isTrue()
    // Ritagli cancellati (la cache svuotata): il segno cambia e il cancello, per ora, rifiuta.
    w.gate.allow = false
    w.gate.token.value = 1
    runCurrent()
    assertThat(w.annaDrawn()).isFalse()
    advanceTimeBy(1_000)
    runCurrent()
    // Il nome di Anna vale ancora: torna fra gli esempi, ma senza volto, a ogni apertura.
    cycleUntilAnna(w)
    repeat(6) {
      cycle(w)
      assertThat(w.annaDrawn()).isFalse()
    }
    // Il cancello lo ripermette: all'apertura con Anna nella terna il volto torna.
    w.gate.allow = true
    cycleUntilAnna(w)
    assertThat(w.annaDrawn()).isTrue()
  }

  @Test
  fun unRitaglioInVoloDaPrimaDellaRevocaNonRientra_CG1() = runTest {
    val gate = Gate(origin)
    gate.hold = CompletableDeferred()
    val w = world(gate)
    advanceTimeBy(1_000)
    runCurrent()
    // Gli esempi sono pronti, il volto di Anna e' stato chiesto (con il permesso), ma il ritaglio e' ancora in volo.
    assertThat(gate.asked).isAtLeast(1)
    assertThat(w.annaDrawn()).isFalse()
    // La revoca arriva mentre e' in volo. Le domande fatte dopo restano senza risposta: conta solo che la risposta vecchia, quando arriva, non entri.
    val inFlight = gate.hold!!
    gate.hold = CompletableDeferred()
    gate.token.value = 1
    runCurrent()
    inFlight.complete(Unit)
    advanceTimeBy(1_000)
    runCurrent()
    assertThat(w.annaDrawn()).isFalse()
    assertThat(w.examples.faceImages.count).isEqualTo(0)
    repeat(4) {
      cycle(w)
      assertThat(w.annaDrawn()).isFalse()
    }
  }

  /** La barra aperta legge i volti nel disegno: al fotogramma dopo la revoca il volto non c'e' piu', anche con la stessa istantanea. */
  @Test
  fun laBarraApertaSmetteDiDisegnareIlVoltoAlFotogrammaDopoLaRevoca_CG1() {
    val faces = SuggestionFaces.fixed(mapOf(anna to (Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap() to setOf(origin))))
    val item = SuggestionItem(Suggestion.PersonSuggestion(PersonOption(listOf(anna), PeopleMode.ANY, "Anna")), SuggestionArt.Face(anna))
    rule.setSearchContent { SuggestionDisc(item, lib.snapshot, null, faces) }
    rule.onAllNodesWithTag(SuggestionFaceTag, useUnmergedTree = true).assertCountEquals(1)
    rule.runOnUiThread { faces.revoke(Any()) }
    // Il ritaglio non c'e' piu' per nessuno, gia' prima del disegno.
    assertThat(faces.image(anna, lib.snapshot)).isNull()
    rule.mainClock.advanceTimeByFrame()
    rule.onAllNodesWithTag(SuggestionFaceTag, useUnmergedTree = true).assertCountEquals(0)
  }

  /**
   * CS-24 (giro 3 della fase I): il volto gia' disegnato la cui foto d'origine si nasconde **dopo**. Nessuna revoca: il ritaglio resta tenuto con il segno
   * di adesso; e' l'istantanea visibile nuova, senza nessuna foto d'origine, che al disegno dopo toglie il volto (resta il segno della persona).
   */
  @Test
  fun unVoltoGiaDisegnatoSeNeVaQuandoLaSuaFotoDOrigineSiNascondeDopo_CS24() {
    val faces = SuggestionFaces.fixed(mapOf(anna to (Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap() to setOf(origin))))
    val item = SuggestionItem(Suggestion.PersonSuggestion(PersonOption(listOf(anna), PeopleMode.ANY, "Anna")), SuggestionArt.Face(anna))
    var library by mutableStateOf<TimelineSnapshot>(lib.snapshot)
    rule.setSearchContent { SuggestionDisc(item, library, null, faces) }
    rule.onAllNodesWithTag(SuggestionFaceTag, useUnmergedTree = true).assertCountEquals(1)
    // La foto d'origine si nasconde: l'istantanea visibile nuova non la contiene piu'.
    library = object : TimelineSnapshot by lib.snapshot {
      override fun indexOf(id: MediaId): Int = if (id == origin) -1 else lib.snapshot.indexOf(id)
    }
    rule.waitForIdle()
    assertThat(faces.count).isEqualTo(1)
    rule.onAllNodesWithTag(SuggestionFaceTag, useUnmergedTree = true).assertCountEquals(0)
    rule.onAllNodesWithTag(SuggestionGlyphTag, useUnmergedTree = true).assertCountEquals(1)
  }
}
