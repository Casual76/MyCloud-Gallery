package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.SearchHistoryRecord
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.NameText
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PersonEntry
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.ids
import dev.pampa.mycloudgallery.core.search.history.HistoryNames
import dev.pampa.mycloudgallery.core.search.history.HistoryStore
import dev.pampa.mycloudgallery.core.search.history.LocalSearchHistory
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.parse.TestPeople
import dev.pampa.mycloudgallery.core.search.testing.FakePeopleLookup
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Correzioni dopo le revisioni della Fase 8, pacchetto D, nella parte di `core-search`:
 * - **RI-1** e **R9**: un nome senza lettere ne' cifre ("❤", "?", un nome fatto solo di giunti ZWJ e selettori) non e' cercabile, ma **non spegne** la ricerca degli altri nomi;
 *   un nome in un altro alfabeto ("Анна", "李明") si cerca (una sola normalizzazione Unicode, [NameText]);
 * - **R2**: la cronologia ricorda le persone dei chip "Persona" e le toglie per id e per nome (anche non latini);
 * - **I2**: un chip "Persona" gia' in Cerca non vale piu' dopo "Nascondi persona";
 * - **I4**: i `toString()` della ricerca non mostrano nomi.
 *
 * **Solo nomi inventati.**
 */
class CorrezioniDNomiTest {
  @get:Rule val tmp = TemporaryFolder()

  private val today = LocalDate.of(2026, 10, 21)

  // I nomi del banco: tre non cercabili (un emoji, un segno, un nome fatto solo di giunto ZWJ e selettori di variante) e due in altri alfabeti, accanto a un nome normale.
  private val zwjOnly = "‍️‍"
  private val oddNames = listOf("❤", "?", zwjOnly)
  private val otherAlphabets = listOf("Анна", "李明")

  // --- RI-1, R9: la normalizzazione dei nomi ------------------------------------------------------------------------------------------

  @Test
  fun unNomeSenzaLettereNeCifreSiNormalizzaAVuotoEUnoInUnAltroAlfabetoNo() {
    for (name in oddNames) {
      assertWithMessage("normalizzazione di un nome non cercabile").that(PeopleDictionary.normalize(name)).isEmpty()
      assertThat(NameText.isSearchable(name)).isFalse()
      assertThat(PersonEntry.of(name, TestPeople.id(40), 3)).isNull()
    }
    assertThat(PeopleDictionary.normalize("Анна")).isEqualTo("анна")
    assertThat(PeopleDictionary.normalize("李明")).isEqualTo("李明")
    assertThat(PeopleDictionary.normalize("Nicolò D'Amico")).isEqualTo("nicolo d amico")
    assertThat(PeopleDictionary.normalize("  Anna   MARIA ")).isEqualTo("anna maria")
    assertThat(NameText.words("Zio Piero 2")).containsExactly("zio", "piero", "2").inOrder()
    for (name in otherAlphabets + "Marco") assertThat(PersonEntry.of(name, TestPeople.id(41), 3)).isNotNull()
    // Senza media visibili non e' una voce (come prima), ma senza un'eccezione.
    assertThat(PersonEntry.of("Marco", TestPeople.id(42), 0)).isNull()
    // La stessa normalizzazione del tokenizzatore delle query: il nome scritto in Cerca si ritrova in qualunque alfabeto.
    val dictionary = PeopleDictionary(listOf(PersonEntry.of("Анна", TestPeople.id(43), 5)!!, PersonEntry.of("李明", TestPeople.id(44), 5)!!))
    val parser = ItalianQueryParser()
    for (typed in listOf("Анна", "анна", "фото АННА", "李明 al mare")) {
      val chips = parser.parse(typed, today, TestPeople.PLACES, dictionary).chips.filterIsInstance<SearchChip.Person>()
      assertWithMessage("chip per \"$typed\"").that(chips).hasSize(1)
    }
  }

  private fun lookupWithOddNames(): FakePeopleLookup {
    val lookup = FakePeopleLookup()
    var n = 50
    var photos = 5
    for (name in oddNames + otherAlphabets + "Marco" + "Anna") {
      val id = TestPeople.id(n++)
      lookup.name(id, name).sure(id, List(photos++) { MediaId.random() })
    }
    return lookup
  }

  @Test
  fun unNomeNonCercabileNonSpegneLaRicercaDegliAltriNomi() = runTest {
    val lookup = lookupWithOddNames()
    val dictionary = lookup.dictionary()
    // Dei sette nomi si cercano quattro: i tre senza lettere ne' cifre restano testo, gli altri ci sono.
    assertThat(dictionary.entries.map { it.label }).containsExactly("Анна", "李明", "Marco", "Anna")
    val parser = ItalianQueryParser()
    for (typed in listOf("Marco", "Анна", "李明", "Marco e Anna")) {
      val persons = parser.parse(typed, today, TestPeople.PLACES, dictionary).chips.filterIsInstance<SearchChip.Person>()
      assertWithMessage("chip \"Persona\" per \"$typed\"").that(persons).isNotEmpty()
    }
  }

  @Test
  fun isuggerimentiTengonoGliAltriNomiEProponganoIlCirillico() = runTest {
    val lookup = lookupWithOddNames()
    val library = SyntheticLibrary(seed = 3, count = 50)
    val suggestions = LibrarySuggestions(FakeSearchData(library.snapshot), LibraryPlaces { null }, InMemorySearchHistory(), lookup)
    // A riposo: le persone piu' fotografate con un nome cercabile, mai un nome che la normalizzazione svuota.
    val idle = suggestions.idle().people.map { it.label }
    assertThat(idle).containsAtLeast("Marco", "Анна", "李明")
    assertThat(idle).containsNoneIn(oddNames)
    // Mentre si scrive: "Ма" propone "Marco" (latino) e, scrivendo in cirillico, "Ан" propone "Анна".
    assertThat(suggestions.complete("mar").filterIsInstance<Suggestion.PersonSuggestion>().map { it.label }).containsExactly("Marco")
    assertThat(suggestions.complete("Ан").filterIsInstance<Suggestion.PersonSuggestion>().map { it.label }).containsExactly("Анна")
    assertThat(suggestions.complete("李").filterIsInstance<Suggestion.PersonSuggestion>().map { it.label }).containsExactly("李明")
    assertThat(suggestions.complete("❤")).isEmpty()
  }

  // --- R2: la cronologia ricorda le persone e le toglie per id e per nome -----------------------------------------------------------------

  private class Store : HistoryStore {
    val rows = MutableStateFlow<List<SearchHistoryRecord>>(emptyList())
    var next = 1L

    override suspend fun record(text: String, chipLabels: List<String>, persons: List<PersonId>) {
      rows.value = listOf(SearchHistoryRecord(next++, text, chipLabels, persons)) + rows.value.filter { it.text != text }
    }

    override suspend fun delete(id: Long) {
      rows.value = rows.value.filter { it.id != id }
    }

    override suspend fun clear() {
      rows.value = emptyList()
    }

    override fun changes(limit: Int): Flow<List<SearchHistoryRecord>> = rows

    override suspend fun records(limit: Int): List<SearchHistoryRecord> = rows.value.take(limit)
  }

  private fun chipOf(id: PersonId, label: String) = SearchChip.Person(PersonOption(listOf(id), PeopleMode.ANY, label), emptyList(), label.lowercase())

  @Test
  fun laCronologiaRicordaLePersoneDeiChipPersonaENienteAltro() = runTest {
    val store = Store()
    val history = LocalSearchHistory(store, backgroundScope)
    val marco = TestPeople.MARCO
    val anna = TestPeople.ANNA
    history.record("Marco e Anna al mare", listOf(chipOf(marco, "Marco"), chipOf(anna, "Anna"), SearchChip.Word("mare", false)))
    history.record("mare", listOf(SearchChip.Word("mare", false)))
    val rows = store.rows.value.associateBy { it.text }
    assertThat(rows.getValue("Marco e Anna al mare").persons).containsExactly(marco, anna).inOrder()
    assertThat(rows.getValue("mare").persons).isEmpty()
    assertThat(LocalSearchHistory.personsOf(listOf(chipOf(marco, "Marco"), chipOf(marco, "Marco")))).containsExactly(marco)
  }

  @Test
  fun rinominataUnaPersonaLeRicercheDelNomeDiPrimaSiTolgonoPerIdAnchePiuNonSiRitrovaIlNome() = runTest {
    val store = Store()
    val history = LocalSearchHistory(store, backgroundScope)
    val giulia = TestPeople.GIULIA
    history.record("Giulia al mare", listOf(chipOf(giulia, "Giulia")))
    history.record("compleanno", listOf(chipOf(giulia, "Giulia R.")))
    history.record("mare ad agosto", listOf(SearchChip.Word("mare", false)))
    // Per id: nemmeno il nome di prima serve (la voce di "compleanno" porta solo il nome nuovo: l'id la prende lo stesso).
    val removed = history.purge(emptyList(), listOf(giulia))
    assertThat(removed).isEqualTo(2)
    assertThat(store.rows.value.map { it.text }).containsExactly("mare ad agosto")
    // Idempotente.
    assertThat(history.purge(emptyList(), listOf(giulia))).isEqualTo(0)
  }

  @Test
  fun iNomiNonLatiniSiTolgonoDallaCronologia() = runTest {
    val history = InMemorySearchHistory()
    history.record("foto di Анна al mare", emptyList())
    history.record("李明 e Marco", emptyList())
    history.record("Анн", emptyList())
    history.record("mare", emptyList())
    // Un nome in cirillico e uno in cinese: prima la normalizzazione li svuotava e le voci restavano.
    assertThat(history.purge(listOf("Анна", "李明"))).isEqualTo(2)
    assertThat(history.entries.value.map { it.text }).containsExactly("Анн", "mare")
  }

  @Test
  fun unNomeSenzaLettereSiCercaComeSiScriveEUnoFattoDiSoliGiuntiNonToglieNiente() = runTest {
    val history = InMemorySearchHistory()
    history.record("la nonna ❤ al mare", emptyList())
    history.record("mare", emptyList())
    history.record("che cos'e'?", emptyList())
    // "❤" non ha parole: si cerca nel testo com'e' scritto. Un nome fatto solo di giunti (invisibile) non e' un testo da cercare: non toglie niente (sarebbe tutto).
    assertThat(history.purge(listOf(zwjOnly, " "))).isEqualTo(0)
    assertThat(history.purge(listOf("❤"))).isEqualTo(1)
    assertThat(history.entries.value.map { it.text }).containsExactly("mare", "che cos'e'?")
    assertThat(HistoryNames.wanted(listOf(zwjOnly, "", "  ")).isEmpty).isTrue()
  }

  @Test
  fun senzaINomiSiTolgonoLeRicercheNateDaUnChipPersona() = runTest {
    val store = Store()
    val history = LocalSearchHistory(store, backgroundScope)
    history.record("Marco al mare", listOf(chipOf(TestPeople.MARCO, "Marco")))
    history.record("mare", listOf(SearchChip.Word("mare", false)))
    history.record("cena con Zio Piero", emptyList())
    assertThat(history.purgePersonSearches()).isEqualTo(1)
    assertThat(store.rows.value.map { it.text }).containsExactly("mare", "cena con Zio Piero")
    // Idempotente, e il riferimento in memoria e il null-object si comportano allo stesso modo.
    assertThat(history.purgePersonSearches()).isEqualTo(0)
    val reference = InMemorySearchHistory()
    reference.record("Marco al mare", listOf(chipOf(TestPeople.MARCO, "Marco")))
    reference.record("mare", emptyList())
    assertThat(reference.purgePersonSearches()).isEqualTo(1)
    assertThat(reference.entries.value.map { it.text }).containsExactly("mare")
  }

  // --- I2: un chip "Persona" gia' in Cerca dopo "Nascondi persona" ----------------------------------------------------------------------

  private suspend fun engineWorld(): Triple<EngineFixture, FakePeopleLookup, List<MediaId>> {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 21, count = 200, placeIds = EngineFixture.PLACE_IDS))
    f.open()
    val marco = (0 until 20 step 2).map { f.id(it) }
    val anna = (1 until 20 step 2).map { f.id(it) }
    val lookup = FakePeopleLookup().name(TestPeople.MARCO, "Marco").name(TestPeople.ANNA, "Anna")
    lookup.sure(TestPeople.MARCO, marco).sure(TestPeople.ANNA, anna)
    return Triple(f, lookup, marco)
  }

  private fun idsOf(view: TimelineView): Set<MediaId> = ids(view).toSet()

  @Test
  fun unChipPersonaGiaInCercaNonDaPiuIRisultatiDiUnaPersonaNascosta() = runTest {
    val (f, lookup, marcoCells) = engineWorld()
    for (engine in listOf(f.engine(people = lookup), f.reference(people = lookup))) {
      val first = engine.search(SearchRequest("Marco"))
      assertThat(idsOf(first.confident)).containsExactlyElementsIn(marcoCells)
      val chips = first.chips
      // "Nascondi persona" con la ricerca aperta: i chip restano in memoria (ordine, "mostra meno probabili") e la domanda si rilancia con quelli (senza testo: il nome nascosto
      // sarebbe una parola da cercare, che e' un'altra cosa).
      lookup.hide(TestPeople.MARCO)
      val again = engine.search(SearchRequest("", editedChips = chips))
      assertWithMessage("risultati di una persona nascosta").that(idsOf(again.confident) + idsOf(again.likely)).isEmpty()
      // La persona torna visibile: il chip torna a valere.
      lookup.show(TestPeople.MARCO)
      assertThat(idsOf(engine.search(SearchRequest("", editedChips = chips)).confident)).containsExactlyElementsIn(marcoCells)
    }
  }

  @Test
  fun conUnaPersonaNascostaFraDueInOQualunqueRestanoLeAltreEInTutteNonRestaNiente() = runTest {
    val (f, lookup, marcoCells) = engineWorld()
    val engine = f.engine(people = lookup)
    val any = engine.search(SearchRequest("Marco o Anna"))
    val all = engine.search(SearchRequest("Marco e Anna"))
    lookup.hide(TestPeople.ANNA)
    // "Una qualunque": Anna esce, Marco resta. "Tutte": una che manca svuota il chip (mai i risultati di Marco da solo).
    assertThat(idsOf(engine.search(SearchRequest("", editedChips = any.chips)).confident)).containsExactlyElementsIn(marcoCells)
    assertThat(idsOf(engine.search(SearchRequest("", editedChips = all.chips)).confident)).isEmpty()
  }

  // --- I4: nessun nome nei toString() ----------------------------------------------------------------------------------------------------

  @Test
  fun iToStringDellaRicercaNonMostranoNomiNeId() {
    val id = TestPeople.MARCO
    val entry = HistoryEntry(7, "Marco al mare", listOf("Marco"), listOf(id))
    val record = SearchHistoryRecord(8, "Marco al mare", listOf("Marco"), listOf(id))
    val notice = SearchNotice.TypoCorrected("marko", "Marco")
    for (shown in listOf(entry.toString(), record.toString(), notice.toString(), HistoryNames.wanted(listOf("Marco"), listOf(id)).toString())) {
      assertThat(shown).doesNotContain("Marco")
      assertThat(shown).doesNotContain("marko")
      assertThat(shown).doesNotContain(id.value)
    }
    assertThat(SearchHistory.MAX_ENTRIES).isEqualTo(50)
  }

  @Test
  fun leVociDellaCronologiaLeggonoIlRecentePerPrimoNellElencoVivo() = runTest {
    val store = Store()
    val history = LocalSearchHistory(store, backgroundScope)
    history.record("uno", listOf(chipOf(TestPeople.LUCA, "Luca")))
    val entries = history.entries.first { it.isNotEmpty() }
    assertThat(entries.single().persons).containsExactly(TestPeople.LUCA)
  }
}
