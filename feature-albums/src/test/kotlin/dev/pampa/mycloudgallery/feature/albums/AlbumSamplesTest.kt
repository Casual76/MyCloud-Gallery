package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumListState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.CloseSheetState
import dev.pampa.mycloudgallery.feature.albums.contract.SmartEditorState
import dev.pampa.mycloudgallery.feature.albums.contract.TripsState
import dev.pampa.mycloudgallery.feature.albums.testing.AlbumSamples
import org.junit.Test

/** Fase 7 (P0): ogni caso degli stati del §1.5 ha un esempio (P10 parte da qui per screenshot e test), e le regole degli stati tengono. */
class AlbumSamplesTest {

  // I `when` sono esaustivi: un caso nuovo senza esempio non compila qui o fa fallire il conteggio.
  private fun caseOf(state: AlbumListState): String = when (state) {
    AlbumListState.Loading -> "loading"
    is AlbumListState.Ready -> "ready"
    AlbumListState.Empty -> "empty"
  }

  private fun caseOf(state: AlbumPageState): String = when (state) {
    is AlbumPageState.Ready -> "ready"
    is AlbumPageState.Recomputing -> "recomputing"
    is AlbumPageState.Closed -> "closed"
    is AlbumPageState.Unavailable -> "unavailable"
  }

  private fun caseOf(state: SmartEditorState): String = when (state) {
    is SmartEditorState.Interpreting -> "interpreting"
    is SmartEditorState.Proposal -> "proposal"
    is SmartEditorState.TooBroad -> "too_broad"
    is SmartEditorState.NoSearch -> "no_search"
  }

  private fun caseOf(state: TripsState): String = when (state) {
    is TripsState.Proposals -> "proposals"
    is TripsState.NoGps -> "no_gps"
    TripsState.Empty -> "empty"
    TripsState.Searching -> "searching"
  }

  @Test
  fun ogniCasoDegliStatiHaUnEsempio() {
    assertThat(AlbumSamples.lists.map(::caseOf).toSet()).hasSize(3)
    assertThat(AlbumSamples.pages.map(::caseOf).toSet()).hasSize(4)
    assertThat(AlbumSamples.editors.map(::caseOf).toSet()).hasSize(4)
    assertThat(AlbumSamples.trips.map(::caseOf).toSet()).hasSize(4)
    assertThat(AlbumSamples.suggestions).hasSize(3)
  }

  /** Q5: chi chiude deve scegliere per gli altri; la copia per gli altri e "Riporta" insieme vogliono l'avviso. */
  @Test
  fun ilFoglioDiChiusuraVuoleLaSceltaPerGliAltri() {
    val first = AlbumSamples.closeSheets.first()
    assertThat(first.canConfirm).isFalse()
    assertThat(first.copy(forOthers = OthersAfterClose.REMOVE).canConfirm).isTrue()
    val keep = AlbumSamples.closeSheets.last()
    assertThat(keep.warnCopiesLoseBroughtBack).isTrue()
    assertThat(keep.copy(forOthers = OthersAfterClose.REMOVE).warnCopiesLoseBroughtBack).isFalse()
    assertThat(CloseSheetState(keep.album, keep.title, keep.mode, OthersAfterClose.KEEP_COPY, 0, true).warnCopiesLoseBroughtBack).isFalse()
  }
}
