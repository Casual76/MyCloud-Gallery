package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.ids
import dev.pampa.mycloudgallery.core.search.parse.ItalianCalendar
import dev.pampa.mycloudgallery.core.search.parse.TestPeople
import dev.pampa.mycloudgallery.core.search.testing.FakePeopleLookup
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 8 (P10, D26, §2.7, D28, E-F36, E-F37): il chip "Persona" nel motore di ricerca. Una Libreria sintetica di 600 celle, tre persone con un nome
 * (inventato) e le loro celle "sicure" e "forse" scelte dal test: il chip e' un **filtro** sulle celle della porta, con le altre regole (tipi, date, luoghi,
 * contenuto) in "e"; le celle "forse" stanno fra i meno probabili; i nascosti non escono mai, nemmeno se la porta li da' per errore; dove i volti non
 * ci sono il nome resta testo. Il motore vero e quello di riferimento danno lo stesso.
 */
class PeopleSearchEngineTest {
  @get:Rule val tmp = TemporaryFolder()

  private val marco = TestPeople.MARCO
  private val anna = TestPeople.ANNA
  private val giulia = TestPeople.GIULIA

  private class World(val f: EngineFixture, val lookup: FakePeopleLookup) {
    fun cells(vararg positions: Int): List<MediaId> = positions.map { f.id(it) }

    fun range(from: Int, until: Int, step: Int = 1): List<MediaId> = (from until until step step).map { f.id(it) }
  }

  /** Marco: 30 celle sicure (pari fino a 58) e tre "forse"; Anna: 20 sicure (0..9 e 100..109) e una "forse"; Giulia: 20 sicure (200..219). */
  private suspend fun world(): World {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 21, count = 600, placeIds = EngineFixture.PLACE_IDS))
    f.open()
    val lookup = FakePeopleLookup().name(marco, "Marco").name(anna, "Anna").name(giulia, "Giulia")
    val w = World(f, lookup)
    lookup.sure(marco, w.range(0, 60, 2)).likely(marco, w.cells(61, 63, 65))
    lookup.sure(anna, w.range(0, 10) + w.range(100, 110)).likely(anna, w.cells(11))
    lookup.sure(giulia, w.range(200, 220))
    return w
  }

  private fun idsOf(view: TimelineView): Set<MediaId> = ids(view).toSet()

  private fun person(results: SearchResults): SearchChip.Person = results.chips.filterIsInstance<SearchChip.Person>().single()

  private fun engines(w: World, lookup: PeopleLookup = w.lookup) = listOf(w.f.engine(people = lookup), w.f.reference(people = lookup))

  @Test
  fun unNomeEUnFiltroSulleCelleSicureEIForseSonoMenoProbabili() = runTest {
    val w = world()
    for (engine in engines(w)) {
      val results = engine.search(SearchRequest("Marco"))
      assertThat(person(results).label).isEqualTo("Marco")
      assertThat(idsOf(results.confident)).containsExactlyElementsIn(w.range(0, 60, 2))
      assertThat(idsOf(results.likely)).containsExactlyElementsIn(w.cells(61, 63, 65))
      // Un'altra persona non entra mai: le celle di Giulia non ci sono.
      assertThat(idsOf(results.confident) + idsOf(results.likely)).containsNoneIn(w.range(200, 220))
    }
  }

  @Test
  fun piuPersoneInEOInOQualunque() = runTest {
    val w = world()
    for (engine in engines(w)) {
      val all = engine.search(SearchRequest("Marco e Anna"))
      assertThat(person(all).option.mode).isEqualTo(PeopleMode.ALL)
      assertThat(idsOf(all.confident)).containsExactlyElementsIn(w.cells(0, 2, 4, 6, 8))
      // "Tutte": la cella 11 ha Anna fra i "forse" ma non Marco; la 61 ha Marco fra i "forse" ma non Anna: nessuna delle due passa.
      assertThat(idsOf(all.likely)).isEmpty()

      val any = engine.search(SearchRequest("Marco o Anna"))
      assertThat(person(any).option.mode).isEqualTo(PeopleMode.ANY)
      assertThat(idsOf(any.confident)).containsExactlyElementsIn(w.range(0, 60, 2) + w.range(1, 10, 2) + w.range(100, 110))
      assertThat(idsOf(any.likely)).containsExactlyElementsIn(w.cells(11, 61, 63, 65))
    }
  }

  @Test
  fun conGliAltriChipSiCombinaInE() = runTest {
    val w = world()
    val video = SearchChip.MediaType(SearchMediaType.VIDEO, "Video")
    val videos = (0 until w.f.snapshot.size).filter { ChipMatching.accepts(video, w.f.snapshot, it) }.map { w.f.id(it) }.toSet()
    val marcoSure = w.range(0, 60, 2).toSet()
    assertThat(marcoSure.intersect(videos)).isNotEmpty()
    for (engine in engines(w)) {
      val results = engine.search(SearchRequest("video di Marco"))
      assertThat(results.chips.map { it::class.simpleName }).containsExactly("MediaType", "Person").inOrder()
      assertThat(idsOf(results.confident)).containsExactlyElementsIn(marcoSure.intersect(videos))
    }
  }

  @Test
  fun conIlContenutoLaPersonaRestringeLeCandidate() = runTest {
    val w = world()
    // Cane per le prime 20 celle (di Marco e di Anna) e per quelle di Giulia: cercando "cane" con Marco restano solo quelle di Marco.
    val vectors = HashMap<Long, List<FloatArray>>()
    for (i in 0 until 600) vectors[i.toLong()] = listOf(w.f.image(if (i < 20 || i in 200..219) "cane" else "albero", i.toLong()))
    w.f.putAll(vectors)
    for (engine in engines(w)) {
      val found = idsOf(engine.search(SearchRequest("Marco cane")).confident)
      assertThat(found).isNotEmpty()
      assertThat(found).containsNoneIn(w.range(200, 220))
      assertThat(found - w.range(0, 20, 2).toSet()).isEmpty()
    }
  }

  @Test
  fun iNascostiNonEscono_nemmenoSeLaPortaLiDaPerErrore() = runTest {
    val w = world()
    val hidden = w.f.library.hidden.map { it.id }
    assertThat(hidden).isNotEmpty()
    // La porta "sbaglia": fra le celle di Marco ci sono anche i nascosti e una cella appena nascosta altrove.
    w.lookup.sure(marco, hidden).likely(marco, hidden)
    val justHidden = w.f.id(10)
    w.lookup.sure(marco, listOf(justHidden))
    w.f.data.hiddenIds += hidden + justHidden
    for (engine in engines(w)) {
      for (text in listOf("Marco", "Marco e Anna", "Marco o Anna", "video di Marco")) {
        val results = engine.search(SearchRequest(text, showLikely = true))
        val all = idsOf(results.confident) + idsOf(results.likely)
        assertWithMessage("«$text»").that(all).containsNoneIn(hidden + justHidden)
      }
    }
  }

  @Test
  fun doveIVoltiNonCiSonoUnNomeRestaTestoNessunChip() = runTest {
    val w = world()
    w.lookup.available.value = false
    for (engine in engines(w)) {
      assertThat(engine.search(SearchRequest("Marco")).chips.filterIsInstance<SearchChip.Person>()).isEmpty()
    }
    // La porta di serie (nessuna persona) e' la stessa cosa.
    assertThat(w.f.engine().search(SearchRequest("Marco")).chips.filterIsInstance<SearchChip.Person>()).isEmpty()
    // E un chip arrivato da prima (un'istantanea vecchia) non trova niente: mai le celle di altre persone.
    val old = SearchChip.Person(PersonOption(listOf(marco), PeopleMode.ANY, "Marco"), emptyList(), "marco")
    for (engine in engines(w)) {
      val stale = engine.search(SearchRequest("Marco", editedChips = listOf(old)))
      assertThat(stale.confident.size + stale.likely.size).isEqualTo(0)
    }
  }

  @Test
  fun unaPersonaDimenticataNonDaCelleDiAltri() = runTest {
    val w = world()
    val forgotten = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 9 })
    val chip = SearchChip.Person(PersonOption(listOf(forgotten, marco), PeopleMode.ALL, "Dimenticata e Marco"), emptyList(), "dimenticata e marco")
    for (engine in engines(w)) {
      val results = engine.search(SearchRequest("x", editedChips = listOf(chip)))
      assertThat(results.confident.size + results.likely.size).isEqualTo(0)
    }
  }

  @Test
  fun inAttesaDelPrimoCalcoloCercaLoDice() = runTest {
    val w = world()
    for (engine in engines(w)) {
      w.lookup.progress.value = null
      assertThat(engine.search(SearchRequest("Marco")).notices.filterIsInstance<SearchNotice.PeopleRecognizing>()).isEmpty()
      w.lookup.progress.value = 60
      assertThat(engine.search(SearchRequest("Marco")).notices).contains(SearchNotice.PeopleRecognizing(60))
      // Senza un chip "Persona" l'avviso non c'e'.
      assertThat(engine.search(SearchRequest("video")).notices.filterIsInstance<SearchNotice.PeopleRecognizing>()).isEmpty()
    }
  }

  @Test
  fun unaPortaCheSbagliaNonFermaLaRicerca() = runTest {
    val w = world()
    val failing = object : PeopleLookup {
      override val available = MutableStateFlow(true)

      override suspend fun dictionary(): PeopleDictionary = error("guasto")

      override suspend fun cells(option: PersonOption, includeLikely: Boolean): Set<MediaId> = error("guasto")
    }
    val old = SearchChip.Person(PersonOption(listOf(marco), PeopleMode.ANY, "Marco"), emptyList(), "marco")
    for (engine in engines(w, failing)) {
      assertThat(engine.search(SearchRequest("Marco al mare")).chips.filterIsInstance<SearchChip.Person>()).isEmpty()
      // Il chip c'e' ma la porta non risponde: nessun risultato, nessuna eccezione.
      val stale = engine.search(SearchRequest("x", editedChips = listOf(old)))
      assertThat(stale.confident.size + stale.likely.size).isEqualTo(0)
    }
  }

  /** E-F36 nel motore: il peso di una festa o di un mese lo conta l'istantanea. Un nome piu' fotografato del mese vince, uno meno fotografato no. */
  @Test
  fun unNomeCheEUnMeseVinceSoloSeHaPiuMediaDelMese() = runTest {
    val w = world()
    val snapshot = w.f.snapshot
    val byMonth = (0 until snapshot.size).groupingBy { ChipMatching.dayOf(snapshot.localDayAt(it))?.monthValue ?: 0 }.eachCount().filterKeys { it != 0 }
    val month = byMonth.maxByOrNull { it.value }!!.key
    val name = ItalianCalendar.MONTHS[month - 1]
    val label = name.replaceFirstChar { it.uppercase() }
    assertThat(byMonth.getValue(month)).isAtLeast(3)
    val few = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 31 })
    val many = PersonId.fromBytes(ByteArray(PersonId.BYTES) { 32 })
    val small = FakePeopleLookup().name(few, label).sure(few, w.range(0, 1))
    val big = FakePeopleLookup().name(many, label).sure(many, (0 until snapshot.size).map { w.f.id(it) })
    for ((lookup, personWins) in listOf(small to false, big to true)) {
      for (engine in engines(w, lookup)) {
        val chip = engine.search(SearchRequest(name)).chips.single()
        if (personWins) {
          assertThat(chip).isInstanceOf(SearchChip.Person::class.java)
          assertThat((chip as SearchChip.Person).alternatives.map { it.chip }.filterIsInstance<SearchChip.DateRange>()).hasSize(1)
        } else {
          assertThat(chip).isInstanceOf(SearchChip.DateRange::class.java)
          assertThat((chip as SearchChip.DateRange).person?.label).isEqualTo(label)
        }
      }
    }
  }

  /** La parita' del §5.2 con le persone: il motore vero e il riferimento danno le stesse celle nella stessa classe e nello stesso ordine, in 300 query miste. */
  @Test
  fun lePersoneStessaClasseEStessoOrdineDelRiferimento() = runTest {
    val w = world()
    val concepts = listOf("cane", "gatto", "spiaggia", "montagna", "tramonto", "torta", "auto", "fiore")
    val vectors = HashMap<Long, List<FloatArray>>()
    for (i in 0 until 600) vectors[i.toLong()] = listOf(w.f.image(concepts[(i * 7 + i / 3) % concepts.size], i.toLong()))
    w.f.putAll(vectors)
    val names = listOf("Marco", "Anna", "Giulia")
    val r = java.util.SplittableRandom(5)
    val engine = w.f.engine(people = w.lookup)
    val reference = w.f.reference(people = w.lookup)
    var withPeople = 0
    repeat(300) { n ->
      fun name() = names[r.nextInt(names.size)]
      fun concept() = concepts[r.nextInt(concepts.size)]
      val text = when (r.nextInt(8)) {
        0 -> name()
        1 -> "${name()} ${concept()}"
        2 -> "${name()} e ${name()}"
        3 -> "${name()} o ${name()} ${concept()}"
        4 -> "video ${name()}"
        5 -> "foto di ${name()} e ${name()} ${concept()}"
        6 -> "${name()}, ${name()}"
        else -> concept()
      }
      val order = if (n % 2 == 0) dev.pampa.mycloudgallery.core.search.contract.ResultOrder.RECENT else dev.pampa.mycloudgallery.core.search.contract.ResultOrder.RELEVANT
      val mine = engine.search(SearchRequest(text, order = order))
      val ref = reference.search(SearchRequest(text, order = order))
      assertWithMessage("sicuri di «$text»").that(EngineFixture.positions(mine.confident)).containsExactlyElementsIn(EngineFixture.positions(ref.confident)).inOrder()
      assertWithMessage("meno probabili di «$text»").that(EngineFixture.positions(mine.likely)).containsExactlyElementsIn(EngineFixture.positions(ref.likely)).inOrder()
      assertThat(mine.chips.map { it.label }).isEqualTo(ref.chips.map { it.label })
      if (mine.chips.any { it is SearchChip.Person }) withPeople++
    }
    assertThat(withPeople).isGreaterThan(150)
  }

  @Test
  fun ilPesoDiUnaDataSonoLeCelleDellIstantanea() = runTest {
    val w = world()
    val snapshot = w.f.snapshot
    val chip = SearchChip.DateRange(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 12, 31), everyYear = true, label = "Tutto")
    assertThat(SnapshotDateMedia(snapshot).count(chip)).isEqualTo((0 until snapshot.size).count { ChipMatching.accepts(chip, snapshot, it) })
    assertThat(SnapshotDateMedia(snapshot).count(SearchChip.Word("x", exact = false))).isEqualTo(snapshot.size)
  }
}
