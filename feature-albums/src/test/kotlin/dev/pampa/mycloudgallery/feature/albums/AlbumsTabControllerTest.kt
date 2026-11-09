package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineView
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumOpenings
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumsTabKeys
import dev.pampa.mycloudgallery.feature.albums.contract.InMemoryAlbumOpenings
import dev.pampa.mycloudgallery.feature.albums.contract.SpecialAlbum
import dev.pampa.mycloudgallery.feature.albums.list.CreateAlbumResult
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabController
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabEntry
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** G2b: il controllore della scheda Album sul mondo finto degli album e su una libreria sintetica per le voci speciali. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsTabControllerTest {

  private val tab = AlbumsTabScenes()
  private val scenes = tab.scenes

  private fun TestScope.controller(openings: AlbumOpenings = InMemoryAlbumOpenings()) = AlbumsTabController(
    scenes.world.albums, scenes.world.sharedAlbums, tab.specials, openings, backgroundScope, worker = UnconfinedTestDispatcher(testScheduler),
  )

  private fun ready(state: AlbumsTabState): AlbumsTabState.Ready = state as AlbumsTabState.Ready

  private fun keys(state: AlbumsTabState): List<String> = ready(state).entries.map { it.key }

  @Test
  fun laSchedaHaIFissiGliAlbumELeVociSpecialiNonVuote() = runTest {
    val c = controller()
    runCurrent()
    val state = ready(c.state.value)
    assertThat(keys(state).take(3)).containsExactly(
      AlbumsTabKeys.special(SpecialAlbum.FAVORITES), AlbumsTabKeys.special(SpecialAlbum.CAMERA), AlbumsTabKeys.special(SpecialAlbum.VIDEOS),
    ).inOrder()
    assertThat(state.pinned).isEqualTo(3)
    // Tutti gli album del mondo finto (miei, smart, condivisi) ci sono, e Screenshot e Messaggistica stanno con loro.
    val albums = scenes.world.albums.all.value.albums.map { AlbumsTabKeys.album(it.id) }
    assertThat(keys(state)).containsAtLeastElementsIn(albums)
    assertThat(keys(state)).containsAtLeast(AlbumsTabKeys.special(SpecialAlbum.SCREENSHOTS), AlbumsTabKeys.special(SpecialAlbum.MESSAGING))
    assertThat(state.albumCount).isEqualTo(albums.size)
    assertThat(state.albumsPending).isFalse()
    // Il primo valore c'e' subito, senza aspettare il flusso: la scheda si disegna al primo fotogramma.
    assertThat(controller().state.value).isInstanceOf(AlbumsTabState.Ready::class.java)
  }

  @Test
  fun unaVoceApertaPassaPrimaDegliAltriMaNonPrimaDeiFissi() = runTest {
    val c = controller()
    runCurrent()
    val christmas = ready(c.state.value).entries.first { it is AlbumsTabEntry.Item && it.album.id == scenes.christmas }
    c.opened(christmas)
    // L'apertura conta dopo la transizione della pagina: prima la scheda non si rimescola.
    advanceTimeBy(AlbumsTabController.OPEN_SETTLE_MS - 100)
    runCurrent()
    assertThat(keys(c.state.value).indexOf(christmas.key)).isNotEqualTo(3)
    advanceTimeBy(200)
    runCurrent()
    assertThat(keys(c.state.value)[3]).isEqualTo(christmas.key)

    val screenshots = ready(c.state.value).entries.first { it.key == AlbumsTabKeys.special(SpecialAlbum.SCREENSHOTS) }
    c.opened(screenshots)
    advanceTimeBy(AlbumsTabController.OPEN_SETTLE_MS + 1)
    runCurrent()
    assertThat(keys(c.state.value).subList(3, 5)).containsExactly(screenshots.key, christmas.key).inOrder()
  }

  @Test
  fun finoAQuandoLeApertureNonSonoLetteSoloIFissiELoScheletro() = runTest {
    val order = MutableStateFlow<Map<String, Long>?>(null)
    val openings = object : AlbumOpenings {
      override val order: StateFlow<Map<String, Long>?> = order
      override suspend fun opened(key: String) = Unit
    }
    val c = controller(openings)
    runCurrent()
    val waiting = ready(c.state.value)
    assertThat(waiting.albumsPending).isTrue()
    assertThat(waiting.entries.map { it.key }).containsExactly(
      AlbumsTabKeys.special(SpecialAlbum.FAVORITES), AlbumsTabKeys.special(SpecialAlbum.CAMERA), AlbumsTabKeys.special(SpecialAlbum.VIDEOS),
    ).inOrder()
    order.value = mapOf(AlbumsTabKeys.album(scenes.lake) to 1L)
    runCurrent()
    val shown = ready(c.state.value)
    assertThat(shown.albumsPending).isFalse()
    assertThat(shown.entries[3].key).isEqualTo(AlbumsTabKeys.album(scenes.lake))
  }

  @Test
  fun unaVoceSpecialeCheSiSvuotaSparisceMaIPreferitiRestano() = runTest {
    val c = controller()
    runCurrent()
    tab.views.getValue(SpecialAlbum.SCREENSHOTS).value = EmptyTimelineView(LibraryFilter.SCREENSHOTS)
    tab.views.getValue(SpecialAlbum.FAVORITES).value = EmptyTimelineView(LibraryFilter.FAVORITES)
    runCurrent()
    val state = ready(c.state.value)
    assertThat(keys(state)).doesNotContain(AlbumsTabKeys.special(SpecialAlbum.SCREENSHOTS))
    val favorites = state.entries.first() as AlbumsTabEntry.Special
    assertThat(favorites.kind).isEqualTo(SpecialAlbum.FAVORITES)
    assertThat(favorites.count).isEqualTo(0)
  }

  @Test
  fun nuovoAlbumAManoComeNellElenco() = runTest {
    val c = controller()
    val created = c.createManual("Natale 2026")
    assertThat(created).isInstanceOf(CreateAlbumResult.Created::class.java)
    runCurrent()
    assertThat(keys(c.state.value)).contains(AlbumsTabKeys.album((created as CreateAlbumResult.Created).id))
    assertThat(c.createManual("   ")).isEqualTo(CreateAlbumResult.InvalidTitle(TitleProblem.EMPTY))
  }
}
