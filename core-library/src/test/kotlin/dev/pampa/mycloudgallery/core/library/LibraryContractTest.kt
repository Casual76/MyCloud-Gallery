package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.PositionIds
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.contract.SelectionModel
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.noop.NoGridLayoutEngine
import dev.pampa.mycloudgallery.core.library.noop.NoTimelineRepository
import dev.pampa.mycloudgallery.core.library.testing.FakeLocalMediaActions
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.testing.InMemoryLibraryPreferences
import dev.pampa.mycloudgallery.core.library.testing.SimpleGridAnchors
import dev.pampa.mycloudgallery.core.library.testing.SimpleGridLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.SimpleScrubberModel
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.testing.viewerItems
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryContractTest {

  private val library = SyntheticLibrary(seed = 7, count = 1_500, placeIds = intArrayOf(1001, 1002, 1003))
  private val engine = SimpleGridLayoutEngine()

  private fun spec(level: GridLevel, style: GridStyle = GridStyle.SQUARE, columns: Int = 4, newestAtBottom: Boolean = true) =
    GridSpec(level, style, columns = columns, widthPx = 1080, gapPx = 4, densityDp = 2.6f, newestAtBottom = newestAtBottom)

  @Test
  fun laLibreriaSinteticaEDeterministicaEIlPiuRecenteEPrimo() {
    val a = library.snapshot
    val b = SyntheticLibrary(seed = 7, count = 1_500, placeIds = intArrayOf(1001, 1002, 1003)).snapshot
    assertThat(a.size).isEqualTo(1_500)
    assertThat((0 until a.size).map { a.idAt(it) }).isEqualTo((0 until b.size).map { b.idAt(it) })
    // 0 e' il piu' recente (sort_time DESC) e gli id sono tutti diversi.
    assertThat((1 until a.size).all { a.sortTimeAt(it - 1) >= a.sortTimeAt(it) }).isTrue()
    assertThat((0 until a.size).map { a.idAt(it) }.toSet()).hasSize(a.size)
    assertThat(a.indexOf(a.idAt(10))).isEqualTo(10)
    // La composizione del §5.2, a larghe maglie: ci sono video, screenshot, WhatsApp, preferiti e luoghi.
    val kinds = (0 until a.size).map { a.kindAt(it) }.toSet()
    val sources = (0 until a.size).map { a.sourceAt(it) }.toSet()
    assertThat(kinds).containsAtLeast(MediaKind.PHOTO, MediaKind.VIDEO)
    assertThat(sources).containsAtLeast(MediaSource.CAMERA, MediaSource.SCREENSHOT, MediaSource.WHATSAPP)
    assertThat((0 until a.size).any { a.hasFlag(it, CellFlags.FAVORITE) }).isTrue()
    assertThat((0 until a.size).filter { a.placeAt(it) != 0 }.map { a.placeAt(it) }.toSet()).isNotEmpty()
    assertThat((0 until a.size).all { a.thumbHashAt(it) != null }).isTrue()
    assertThat((0 until a.size).filter { a.kindAt(it) == MediaKind.VIDEO }.all { a.durationMsAt(it) > 0 }).isTrue()
  }

  @Test
  fun iFiltriRapidiHannnoIlLoroSignificato() {
    val all = library.view(LibraryFilter.ALL)
    val videos = library.view(LibraryFilter.VIDEOS)
    val noNoise = library.view(LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP)
    assertThat(all.size).isEqualTo(1_500)
    assertThat(videos.size).isGreaterThan(0)
    assertThat(videos.size).isLessThan(all.size)
    assertThat((0 until videos.size).all { library.snapshot.kindAt(videos.snapshotIndex(it)) == MediaKind.VIDEO }).isTrue()
    assertThat((0 until noNoise.size).none { library.snapshot.sourceAt(noNoise.snapshotIndex(it)) in setOf(MediaSource.SCREENSHOT, MediaSource.WHATSAPP) }).isTrue()
    // Un id che non passa il filtro non e' nella vista; uno che passa ha la sua posizione.
    val firstVideo = library.snapshot.idAt(videos.snapshotIndex(0))
    assertThat(videos.viewIndexOf(firstVideo)).isEqualTo(0)
    val notVideo = (0 until library.snapshot.size).first { library.snapshot.kindAt(it) != MediaKind.VIDEO }
    assertThat(videos.viewIndexOf(library.snapshot.idAt(notVideo))).isEqualTo(-1)
    assertThat(LibraryFilter.fromCode("sconosciuto")).isEqualTo(LibraryFilter.ALL)
  }

  @Test
  fun ilLayoutHaSezioniIntestazioniESommePrefisse() {
    val view = library.view()
    for (level in GridLevel.entries) {
      val layout = engine.layout(view, spec(level))
      assertThat(layout.itemCount).isEqualTo(view.size)
      // Ogni cella sta in una riga di celle, e le righe sono ordinate dall'alto con somme prefisse.
      assertThat((0 until view.size).all { layout.rowKind(layout.rowOfItem(it)) == RowKind.CELLS }).isTrue()
      assertThat((1 until layout.rowCount).all { layout.rowTop(it) > layout.rowTop(it - 1) }).isTrue()
      assertThat(layout.rowTop(layout.rowCount - 1) + layout.rowHeight(layout.rowCount - 1)).isEqualTo(layout.totalHeight)
      assertThat(layout.sections.sumOf { it.count }).isEqualTo(view.size)
      val headers = (0 until layout.rowCount).count { layout.rowKind(it) == RowKind.HEADER }
      if (level.hasHeaders) assertThat(headers).isEqualTo(layout.sections.size) else assertThat(headers).isEqualTo(0)
    }
    val years = engine.layout(view, spec(GridLevel.YEARS))
    val days = engine.layout(view, spec(GridLevel.DAYS))
    // Meno sezioni a livelli piu' lontani.
    assertThat(years.sections.size).isLessThan(days.sections.size)
    assertThat(years.sections.size).isAtMost(16)
  }

  @Test
  fun iPiuRecentiStannoInFondoEIlTabNonSbagliaVersoDiLettura() {
    val view = library.view()
    val bottom = engine.layout(view, spec(GridLevel.MONTHS, newestAtBottom = true))
    val top = engine.layout(view, spec(GridLevel.MONTHS, newestAtBottom = false))
    // L'ultima posizione di lettura e' il piu' recente (vista 0) se i recenti stanno in fondo; il contrario altrimenti.
    assertThat(bottom.viewIndexAt(bottom.itemCount - 1)).isEqualTo(0)
    assertThat(top.viewIndexAt(top.itemCount - 1)).isEqualTo(view.size - 1)
    assertThat(bottom.positionOfViewIndex(bottom.viewIndexAt(5))).isEqualTo(5)
    // La prima sezione dall'alto e' la piu' vecchia.
    assertThat(bottom.sections.first().firstDay).isLessThan(bottom.sections.last().firstDay)
  }

  @Test
  fun lePosizioniOrizzontaliNonSiSovrappongonoNeEscono() {
    val view = library.view()
    for (style in GridStyle.entries) {
      val layout = engine.layout(view, spec(GridLevel.ALL, style, columns = 3))
      for (row in 0 until layout.rowCount) {
        var edge = -1
        for (p in layout.itemsOf(row)) {
          assertThat(layout.itemLeft(p)).isGreaterThan(edge)
          edge = layout.itemLeft(p) + layout.itemWidth(p) - 1
          assertThat(layout.itemAt(row, layout.itemLeft(p) + 1f)).isEqualTo(p)
        }
        if (!layout.itemsOf(row).isEmpty()) assertThat(edge).isAtMost(1080)
      }
    }
  }

  @Test
  fun lAncoraRestaSottoIlDitoEloScrubberUsaLeSommePrefisse() {
    val view = library.view()
    val l = engine.layout(view, spec(GridLevel.DAYS))
    val anchors = SimpleGridAnchors()
    val row = l.rowAtOffset(l.totalHeight / 2)
    val x = 300f
    val anchor = anchors.anchorAt(l, row, x, yInRow = 10f, screenY = 500f)
    assertThat(l.rowOfItem(anchor.position)).isIn(listOf(row, row + 1))

    // Su un altro layout (livello Tutte) l'elemento sotto il dito e' lo stesso, e la riga e' quella della sua posizione.
    val l2 = engine.layout(view, spec(GridLevel.ALL, columns = 3))
    val target = anchors.place(l2, anchor)
    assertThat(target.row).isEqualTo(l2.rowOfItem(anchor.position))

    val scrubber = SimpleScrubberModel()
    assertThat(scrubber.rowAt(l, 0f)).isEqualTo(0)
    assertThat(scrubber.rowAt(l, 1f)).isEqualTo(l.rowAtOffset(l.totalHeight))
    assertThat(scrubber.fractionOf(l, 0)).isEqualTo(0f)
    assertThat(scrubber.fractionOf(l, row)).isWithin(0.01f).of(0.5f)
    assertThat(scrubber.label(l, 0).year).isAtLeast(2010)
  }

  @Test
  fun laSelezioneATrascinamentoDecidePrimaCella() {
    val ids = (0 until 10).map { library.snapshot.idAt(it) }
    val selection = SelectionModel()
    val resolver = PositionIds { ids[it] }

    selection.toggle(ids[8])
    // Parte da una cella non selezionata: seleziona l'intervallo, in qualunque direzione.
    selection.beginDrag(2, resolver)
    selection.dragTo(5)
    assertThat(selection.selected.value.ids).containsExactly(ids[2], ids[3], ids[4], ids[5], ids[8])
    // Tornando indietro l'intervallo si restringe.
    selection.dragTo(3)
    assertThat(selection.selected.value.ids).containsExactly(ids[2], ids[3], ids[8])
    selection.endDrag()
    assertThat(selection.isActive).isTrue()

    // Parte da una cella gia' selezionata: deseleziona quelle che attraversa.
    selection.beginDrag(3, resolver)
    selection.dragTo(2)
    assertThat(selection.selected.value.ids).containsExactly(ids[8])
    selection.endDrag()

    selection.clear()
    assertThat(selection.isActive).isFalse()
  }

  @Test
  fun iFakeDelRepositoryEDelleAzioniEIlViewer() = runTest {
    val repo = FakeTimelineRepository(library.snapshot)
    repo.start()
    val videos = repo.view(LibraryFilter.VIDEOS)
    val before = videos.value.size
    // Il catalogo cambia (modifica esterna): la vista gia' richiesta si aggiorna da sola.
    repo.publish(SyntheticLibrary(seed = 8, count = 300).snapshot)
    assertThat(videos.value.size).isNotEqualTo(before)

    val prefs = InMemoryLibraryPreferences()
    prefs.setFilter(LibraryFilter.SCREENSHOTS)
    prefs.setLevel(GridLevel.MONTHS)
    assertThat(prefs.filter.value).isEqualTo(LibraryFilter.SCREENSHOTS)
    assertThat(prefs.level.value).isEqualTo(GridLevel.MONTHS)

    val id = library.snapshot.idAt(3)
    val actions = FakeLocalMediaActions()
    actions.setFavorite(setOf(id), on = true)
    actions.setHidden(setOf(id), on = true)
    actions.setHidden(setOf(id), on = false)
    assertThat(actions.favorites).containsExactly(id)
    assertThat(actions.hidden).isEmpty()

    val items = library.viewerItems()
    assertThat(items.view.value.size).isEqualTo(1_500)
    assertThat(items.ref(id)).isEqualTo(library.refAt(3))
  }

  @Test
  fun iNullObjectSonoVuotiEFallisconoChiusi() = runTest {
    assertThat(EmptyTimelineSnapshot.size).isEqualTo(0)
    assertThat(EmptyTimelineSnapshot.indexOf(library.snapshot.idAt(0))).isEqualTo(-1)
    assertThrows(IndexOutOfBoundsException::class.java) { EmptyTimelineSnapshot.idAt(0) }
    assertThat(NoTimelineRepository.view(LibraryFilter.ALL).value.size).isEqualTo(0)
    val layout = NoGridLayoutEngine.layout(NoTimelineRepository.view(LibraryFilter.ALL).value, spec(GridLevel.DAYS))
    assertThat(layout.rowCount).isEqualTo(0)
    assertThat(layout.totalHeight).isEqualTo(0)
    // Il layout di una vista vuota con il motore vero e' vuoto, non rotto.
    assertThat(engine.layout(NoTimelineRepository.view(LibraryFilter.ALL).value, spec(GridLevel.DAYS)).rowCount).isEqualTo(0)
  }
}
