package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexBuilder
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexReader
import dev.pampa.mycloudgallery.core.library.index.TimelinePatcher
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.util.UUID
import org.junit.Test

/**
 * L'indice a colonne: costruzione, `indexOf`, filtri (D16), copertine dei gruppi, riferimenti ai byte, e i budget di
 * memoria (~80 B per elemento) e di tempo (larghi, 5x l'atteso: i numeri veri li danno i dispositivi).
 */
class TimelineIndexTest {

  private val library = SyntheticLibrary(seed = 1, count = SyntheticLibrary.FULL_COUNT, placeIds = IntArray(40) { 3_000_000 + it })

  @Test
  fun lIndiceAColonneEUgualeAllaLibreriaSintetica() {
    val index = library.index()
    assertSameIndex(index, library.snapshot)
    for (i in intArrayOf(0, 1, 777, 50_000, 99_999)) assertThat(index.indexOf(index.idAt(i))).isEqualTo(i)
    assertThat(index.indexOf(MediaId(UUID(1, 2).toString()))).isEqualTo(-1)
    // Le copertine portano l'identita' del gruppo; le altre celle no.
    val covers = (0 until index.size).filter { index.groupSizeAt(it) > 1 }
    assertThat(covers).isNotEmpty()
    assertThat(covers.all { index.groupKeyAt(it) == library.cellAt(it).groupId?.value }).isTrue()
    assertThat(index.groupKeyAt((0 until index.size).first { index.groupSizeAt(it) == 1 })).isNull()
  }

  @Test
  fun budgetDiMemoriaETempoDellIndice() {
    val rows = library.indexRows()
    // Costruzione dalle righe (il percorso del Cursor senza il database: stessa funzione per riga).
    var built: ColumnarTimelineSnapshot? = null
    val buildMs = timeMs { built = TimelineIndexReader.fromRows(rows, 1, emptyMap()) }
    val index = built!!
    val lookupMs = timeMs { for (i in 0 until index.size) check(index.indexOf(index.idAt(i)) == i) }
    val perItem = index.estimatedBytes().toDouble() / index.size
    val withoutTable = index.estimatedBytes(includeIdTable = false).toDouble() / index.size
    // La memoria vera: l'heap prima e dopo una copia dell'indice (rumorosa: solo un tetto largo).
    val refs = refsFor(index)
    val heap = heapDeltaPerItem {
      TimelinePatcher.withRefs(TimelineIndexReader.fromRows(rows, 2, emptyMap()), refs).also { it.indexOf(it.idAt(0)) }
    }
    println("INDEX_BUDGET: build100k=${buildMs}ms lookup100k=${lookupMs}ms bytesPerItem=${"%.1f".format(perItem)} " +
      "(senza tabella id ${"%.1f".format(withoutTable)}) heapDeltaPerItem~${"%.1f".format(heap)}")
    assertThat(perItem).isAtMost(100.0)
    assertThat(withoutTable).isAtMost(90.0)
    assertThat(buildMs).isLessThan(5_000)
    assertThat(lookupMs).isLessThan(5_000)
  }

  @Test
  fun iFiltriRapidiD16() {
    val index = library.index()
    for (filter in LibraryFilter.entries) {
      val view = FilteredTimelineView(index, filter)
      val expected = (0 until index.size).filter { filter.accepts(index.kindAt(it), index.sourceAt(it), index.flagsAt(it)) }
      assertThat(view.size).isEqualTo(expected.size)
      assertThat((0 until view.size).map { view.snapshotIndex(it) }).isEqualTo(expected)
      if (expected.isNotEmpty()) assertThat(view.viewIndexOf(index.idAt(expected[expected.size / 2]))).isEqualTo(expected.size / 2)
    }
    val videos = FilteredTimelineView(index, LibraryFilter.VIDEOS)
    assertThat((0 until videos.size).all { index.kindAt(videos.snapshotIndex(it)) == MediaKind.VIDEO }).isTrue()
    val clean = FilteredTimelineView(index, LibraryFilter.WITHOUT_SCREENSHOTS_AND_WHATSAPP)
    assertThat((0 until clean.size).none { index.sourceAt(clean.snapshotIndex(it)) in setOf(MediaSource.SCREENSHOT, MediaSource.WHATSAPP) }).isTrue()
    val favorites = FilteredTimelineView(index, LibraryFilter.FAVORITES)
    assertThat(favorites.size).isEqualTo(index.countFlag(CellFlags.FAVORITE))
    // Un id che non passa il filtro non e' nella vista.
    val photo = (0 until index.size).first { index.kindAt(it) != MediaKind.VIDEO }
    assertThat(videos.viewIndexOf(index.idAt(photo))).isEqualTo(-1)
    // Un filtro senza risultati: vista vuota (la UI mostra "Mostra tutto").
    val noFavorites = TimelineIndexReader.fromRows(listOf(indexRow(UUID(5, 5).toString(), 10)), 1, emptyMap())
    assertThat(FilteredTimelineView(noFavorites, LibraryFilter.FAVORITES).size).isEqualTo(0)
  }

  @Test
  fun righeFuoriOrdineSegnapostiNonValidiERiferimenti() {
    val a = UUID(0x10, 1).toString()
    val b = UUID(0x20, 1).toString()
    val c = UUID(0x20, 2).toString()
    val tooLong = ByteArray(60) { 1 }
    val rows = listOf(
      indexRow(a, sortTime = 100, thumb = tooLong),
      indexRow(c, sortTime = 200, kind = "sconosciuto", source = "boh"),
      indexRow(b, sortTime = 200, kind = "video", duration = 61_500, place = 42, group = UUID(9, 9).toString()),
    )
    val ref = MediaRef.Phone("external_primary", 77, "image/jpeg")
    val index = TimelineIndexReader.fromRows(rows, 5, mapOf(UUID(9, 9).toString() to 3), mapOf(b to ref))
    // Ordine del catalogo: sort_time DESC, media_id DESC (c > b a parita' di istante).
    assertThat((0 until 3).map { index.idAt(it).value }).containsExactly(c, b, a).inOrder()
    assertThat(index.kindAt(0)).isEqualTo(MediaKind.PHOTO)
    assertThat(index.sourceAt(0)).isEqualTo(MediaSource.OTHER)
    assertThat(index.durationMsAt(1)).isEqualTo(61_500)
    assertThat(index.groupSizeAt(1)).isEqualTo(3)
    assertThat(index.refAt(1)).isEqualTo(ref)
    assertThat(index.refAt(0)).isNull()
    // Un segnaposto oltre i 36 byte non e' un ThumbHash: scartato, non un'eccezione.
    assertThat(index.thumbHashAt(2)).isNull()
    // La chiave della versione cambia con i byte mostrati.
    val other = TimelineIndexReader.fromRows(rows, 5, emptyMap(), mapOf(b to ref.copy(mediaStoreId = 78)))
    assertThat(other.versionKeyAt(1)).isNotEqualTo(index.versionKeyAt(1))
    // Un costruttore vuoto da' l'indice vuoto.
    assertThat(TimelineIndexBuilder().build(0).size).isEqualTo(0)
  }

  private fun refsFor(index: ColumnarTimelineSnapshot): Map<Int, MediaRef.Phone> =
    (0 until index.size).associateWith { MediaRef.Phone("external_primary", 1_000L + it, if (it % 10 == 0) "video/mp4" else "image/jpeg") }

  companion object {
    inline fun timeMs(block: () -> Unit): Long {
      val start = System.nanoTime()
      block()
      return (System.nanoTime() - start) / 1_000_000
    }

    /** Byte per elemento trattenuti da cio' che [block] restituisce (heap prima e dopo, con GC). */
    fun heapDeltaPerItem(block: () -> ColumnarTimelineSnapshot): Double {
      val rt = Runtime.getRuntime()
      fun used(): Long {
        repeat(3) {
          System.gc()
          Thread.sleep(20)
        }
        return rt.totalMemory() - rt.freeMemory()
      }
      val before = used()
      val kept = block()
      val after = used()
      check(kept.size > 0)
      return (after - before).toDouble() / kept.size
    }
  }
}
