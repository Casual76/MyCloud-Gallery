package dev.pampa.mycloudgallery.feature.viewer

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import org.junit.Test

/**
 * B5 (G3): le foto piu' vecchie a sinistra. La regola e' una funzione pura: dall'ordine del tempo della vista si decide se il pager va rovesciato e dove sta la pagina a sinistra.
 * Qui la regola; il pager vero, con i gesti e la tastiera, e' in [ViewerPagerOrderTest].
 */
class ViewerPagingTest {

  private fun library(count: Int) = SyntheticLibrary(seed = 3, count = count, placeIds = FakePlaceData.IDS)

  @Test
  fun laTimelineHaLePiuRecentiPerPrime() {
    val library = library(50)
    assertThat(ViewerPaging.orderOf(library.view())).isEqualTo(ViewerOrder.NEWEST_FIRST)
  }

  @Test
  fun unaVistaDalPiuVecchioAlPiuRecenteHaLePiuVecchiePerPrime() {
    val library = library(50)
    val oldestFirst = IndexOrderedTimelineView(library.snapshot, (49 downTo 0).toList().toIntArray())
    assertThat(ViewerPaging.orderOf(oldestFirst)).isEqualTo(ViewerOrder.OLDEST_FIRST)
  }

  @Test
  fun unOrdineSenzaIlVersoDelTempoNonHaUnPiuVecchio() {
    val library = library(50)
    // Una pertinenza qualunque: dall'alto e dal basso a turno.
    val shuffled = ((0 until 25).flatMap { listOf(it, 49 - it) }).toIntArray()
    assertThat(ViewerPaging.orderOf(IndexOrderedTimelineView(library.snapshot, shuffled))).isEqualTo(ViewerOrder.UNORDERED)
  }

  @Test
  fun unaVistaDiUnElementoOVuotaNonCambiaNiente() {
    val library = library(5)
    assertThat(ViewerPaging.orderOf(IndexOrderedTimelineView(library.snapshot, intArrayOf(2)))).isEqualTo(ViewerOrder.NEWEST_FIRST)
    assertThat(ViewerPaging.orderOf(IndexOrderedTimelineView(library.snapshot, IntArray(0)))).isEqualTo(ViewerOrder.NEWEST_FIRST)
  }

  @Test
  fun conMoltiElementiSiGuardaSoloUnCampione_eLOrdineSiCapisceLoStesso() {
    val library = library(3_000)
    assertThat(ViewerPaging.orderOf(library.view())).isEqualTo(ViewerOrder.NEWEST_FIRST)
    val oldestFirst = IndexOrderedTimelineView(library.snapshot, (2_999 downTo 0).toList().toIntArray())
    assertThat(ViewerPaging.orderOf(oldestFirst)).isEqualTo(ViewerOrder.OLDEST_FIRST)
    // Una lista che scende per meta' e poi sale non e' ordinata: il campione lo vede.
    val broken = ((0 until 1_500) + (2_999 downTo 1_500)).toIntArray()
    assertThat(ViewerPaging.orderOf(IndexOrderedTimelineView(library.snapshot, broken))).isEqualTo(ViewerOrder.UNORDERED)
  }

  @Test
  fun ilPagerSiRovesciaSoloQuandoLePiuVecchieHannoLePosizioniAlte() {
    // Da sinistra a destra: la pagina 0 sta a sinistra e le posizioni crescono verso destra, quindi le piu' vecchie (posizioni alte) vanno a sinistra solo rovesciando.
    assertThat(ViewerPaging.reverseLayout(ViewerOrder.NEWEST_FIRST, rtl = false)).isTrue()
    assertThat(ViewerPaging.reverseLayout(ViewerOrder.OLDEST_FIRST, rtl = false)).isFalse()
    assertThat(ViewerPaging.reverseLayout(ViewerOrder.UNORDERED, rtl = false)).isFalse()
    // Da destra a sinistra Compose parte gia' dalla destra: tutto al contrario, e la sinistra dello schermo resta quella delle piu' vecchie.
    assertThat(ViewerPaging.reverseLayout(ViewerOrder.NEWEST_FIRST, rtl = true)).isFalse()
    assertThat(ViewerPaging.reverseLayout(ViewerOrder.OLDEST_FIRST, rtl = true)).isTrue()
    assertThat(ViewerPaging.reverseLayout(ViewerOrder.UNORDERED, rtl = true)).isTrue()
  }

  @Test
  fun laPaginaASinistraStaDoveLaVedeLUtente() {
    // Rovesciato da sinistra a destra: a sinistra c'e' la posizione dopo (la piu' vecchia). Non rovesciato: la posizione prima.
    assertThat(ViewerPaging.leftPageDelta(reversed = true, rtl = false)).isEqualTo(1)
    assertThat(ViewerPaging.leftPageDelta(reversed = false, rtl = false)).isEqualTo(-1)
    // Da destra a sinistra il pager non rovesciato ha la posizione 0 a destra, quindi a sinistra c'e' la dopo.
    assertThat(ViewerPaging.leftPageDelta(reversed = false, rtl = true)).isEqualTo(1)
    assertThat(ViewerPaging.leftPageDelta(reversed = true, rtl = true)).isEqualTo(-1)
  }

  @Test
  fun lePiuVecchieSonoSempreASinistra_inOgniCombinazione() {
    for (order in listOf(ViewerOrder.NEWEST_FIRST, ViewerOrder.OLDEST_FIRST)) {
      for (rtl in listOf(false, true)) {
        val delta = ViewerPaging.leftPageDelta(ViewerPaging.reverseLayout(order, rtl), rtl)
        // Il passo verso sinistra cresce verso le piu' vecchie se le posizioni alte sono le vecchie, altrimenti scende.
        val towardOlder = if (order == ViewerOrder.NEWEST_FIRST) 1 else -1
        assertThat(delta).isEqualTo(towardOlder)
      }
    }
  }
}
