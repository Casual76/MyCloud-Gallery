package dev.pampa.mycloudgallery.core.search.engine

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.coverage.CoverageNotices
import dev.pampa.mycloudgallery.core.search.coverage.CoverageTracker
import dev.pampa.mycloudgallery.core.search.engine.EngineFixture.Companion.ids
import dev.pampa.mycloudgallery.core.search.index.key
import dev.pampa.mycloudgallery.core.search.index.ocrBody
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Revisione A della Fase 6 nel motore di Cerca: le aree senza indicizzatori e la lettura del testo non attiva arrivano sopra i risultati e
 * fra i motivi della copertura (PR-1, E-I12, D31); il bit dei documenti si calcola davvero dal testo letto e il tipo "documenti" trova i
 * documenti (CE-1, D34).
 */
class RevisioneACercaTest {

  @get:Rule val tmp = TemporaryFolder()

  private class Notices(var areas: List<Boolean> = emptyList(), var readingOff: Boolean = false) : CoverageNotices {
    override suspend fun areasWithoutIndexer(): List<Boolean> = areas

    override suspend fun textReadingOff(): Boolean = readingOff
  }

  private fun engine(f: EngineFixture, notices: CoverageNotices, tracker: CoverageTracker = CoverageTracker(notices = notices)) =
    LocalSearchEngine(f.data, f.index, f.keys, f.texts, EmbedderQueryEmbedding(FakeTextEmbedder()), f.today, f.places, coverageTracker = tracker, coverageNotices = notices)

  @Test
  fun pr1_unaRicercaDiceLeAreeSenzaIndicizzatoriPrimaLaComune() = runTest {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 4, count = 60))
    f.open()
    f.putAll((0 until 60).associate { it.toLong() to listOf(f.image("albero", it.toLong())) })
    val notices = Notices(areas = listOf(false, true))
    val found = engine(f, notices).search(SearchRequest("albero"))
    assertThat(found.notices.filterIsInstance<SearchNotice.NoIndexerForArea>())
      .containsExactly(SearchNotice.NoIndexerForArea(true), SearchNotice.NoIndexerForArea(false)).inOrder()

    // Coperte: niente. Una ricerca per soli filtri non parla di contenuto.
    notices.areas = emptyList()
    assertThat(engine(f, notices).search(SearchRequest("albero")).notices.filterIsInstance<SearchNotice.NoIndexerForArea>()).isEmpty()
    notices.areas = listOf(true)
    assertThat(engine(f, notices).search(SearchRequest("video")).notices.filterIsInstance<SearchNotice.NoIndexerForArea>()).isEmpty()
  }

  @Test
  fun pr1_leParoleCercateConLaLetturaNonAttivaLoDiconoEIlMotivoEFraQuelliDellaCopertura() = runTest {
    val f = EngineFixture(tmp.newFolder(), SyntheticLibrary(seed = 4, count = 50))
    f.open()
    f.putAll((0 until 40).associate { it.toLong() to listOf(f.image("albero", it.toLong())) })
    val notices = Notices(readingOff = true)
    val tracker = CoverageTracker(notices = notices)
    val found = engine(f, notices, tracker).search(SearchRequest("\"pizzeria mario\""))
    assertThat(found.notices).contains(SearchNotice.TextReadingOff)
    val partial = tracker.state.value as SearchCoverage.Partial
    assertThat(partial.reasons).containsEntry(CoverageReason.TEXT_READING_OFF, 50)
    assertThat(partial.reasons).containsEntry(CoverageReason.PREPARATION, 10)

    notices.readingOff = false
    assertThat(engine(f, notices).search(SearchRequest("\"pizzeria mario\"")).notices).doesNotContain(SearchNotice.TextReadingOff)
  }

  /** Il testo di uno scontrino: dieci righe. */
  private val receipt = (1..10).joinToString("\n") { "riga $it dello scontrino" }

  /** Un cartello: tre righe corte. */
  private val sign = "uscita\nvietato fumare\nparcheggio"

  private fun firstIndex(s: InMemoryTimelineSnapshot, kind: MediaKind, screenshot: Boolean, skip: Set<Int> = emptySet()): Int =
    (0 until s.size).first { it !in skip && s.kindAt(it) == kind && (s.sourceAt(it) == MediaSource.SCREENSHOT) == screenshot && s.groupSizeAt(it) <= 1 }

  @Test
  fun ce1_ilBitDeiDocumentiSiAccendeDalTestoLettoESiSpegneQuandoIlTestoNonBastaPiu() = runTest {
    val library = SyntheticLibrary(seed = 9, count = 400)
    val f = EngineFixture(tmp.newFolder(), library)
    f.open()
    val s = library.snapshot
    val doc = firstIndex(s, MediaKind.PHOTO, screenshot = false)
    val signPhoto = firstIndex(s, MediaKind.PHOTO, screenshot = false, skip = setOf(doc))
    val screenshot = firstIndex(s, MediaKind.PHOTO, screenshot = true)
    val video = firstIndex(s, MediaKind.VIDEO, screenshot = false)
    for (i in listOf(doc, screenshot, video)) f.index.saveText(key(i.toLong()), ocrBody(receipt))
    f.index.saveText(key(signPhoto.toLong()), ocrBody(sign))
    // Un bit acceso prima (per esempio da un testo poi ritirato) su una foto senza testo.
    val stale = (0 until s.size).first { it !in setOf(doc, signPhoto, screenshot, video) && !s.hasFlag(it, CellFlags.DOCUMENT) }
    f.data.snapshot.value = s.withFlags(stale, s.flagsAt(stale) or CellFlags.DOCUMENT)
    for (i in 0 until s.size) if (i != stale && s.hasFlag(i, CellFlags.DOCUMENT)) error("la Libreria sintetica non accende DOCUMENT")

    val writes = HashMap<MediaId, Boolean>()
    val flags = DocumentFlags(f.data, f.keys, f.index, { ids, on -> ids.forEach { writes[it] = on }; ids.size })
    assertThat(flags.refresh()).isEqualTo(2)
    // Lo scontrino si', il cartello no; lo screenshot e il video nemmeno con lo stesso testo; il bit vecchio si spegne.
    assertThat(writes).containsExactly(f.id(doc), true, f.id(stale), false)
  }

  @Test
  fun ce1_laSogliaSulVettoreValeSoloQuandoECalibrata() = runTest {
    val library = SyntheticLibrary(seed = 9, count = 200)
    val f = EngineFixture(tmp.newFolder(), library)
    f.open()
    val s = library.snapshot
    val photo = firstIndex(s, MediaKind.PHOTO, screenshot = false)
    f.putImage(photo.toLong(), "scontrino")
    val prototype = f.image("scontrino", 999)
    val writes = HashMap<MediaId, Boolean>()
    val sink = DocumentFlagSink { ids, on -> ids.forEach { writes[it] = on }; ids.size }
    // Di serie (nessuna soglia calibrata) il vettore non conta e i prototipi non si chiedono nemmeno.
    var asked = 0
    DocumentFlags(f.data, f.keys, f.index, sink, prototypes = { asked++; listOf(prototype) }).refresh()
    assertThat(writes).isEmpty()
    assertThat(asked).isEqualTo(0)
    // Con una soglia (quella che P9 scrivera'), il vettore vicino ai prototipi basta.
    DocumentFlags(f.data, f.keys, f.index, sink, prototypes = { listOf(prototype) }, vectorThreshold = 0.5f).refresh()
    assertThat(writes).containsExactly(f.id(photo), true)
  }

  @Test
  fun ce1_ilTipoDocumentiTrovaLeCelleColBit() = runTest {
    val library = SyntheticLibrary(seed = 9, count = 200)
    val f = EngineFixture(tmp.newFolder(), library)
    f.open()
    val s = library.snapshot
    val doc = firstIndex(s, MediaKind.PHOTO, screenshot = false)
    f.index.saveText(key(doc.toLong()), ocrBody(receipt))
    DocumentFlags(f.data, f.keys, f.index, { ids, on ->
      var next = f.data.snapshot.value as InMemoryTimelineSnapshot
      for (id in ids) {
        val i = next.indexOf(id)
        next = next.withFlags(i, if (on) next.flagsAt(i) or CellFlags.DOCUMENT else next.flagsAt(i) and CellFlags.DOCUMENT.inv())
      }
      f.data.snapshot.value = next
      ids.size
    }).refresh()
    val found = f.engine().search(SearchRequest("documenti"))
    assertThat(ids(found.confident)).containsExactly(f.id(doc))
  }
}
