package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabEntry
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabScreen
import dev.pampa.mycloudgallery.feature.albums.tab.AlbumsTabState
import dev.pampa.mycloudgallery.feature.albums.tab.NoAlbumsTabHandlers
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import java.util.UUID
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * K3b, FAB-10 e GF-5 sulla scheda Album, sul telefono dell'utente (Galaxy S25, 360 dp).
 *
 * - **FAB-10**: la finestra delle copertine segue le righe in vista. Prima era fissa sulle prime 48 voci: con 120 album quelle oltre la 48ª non si caricavano mai (i test non lo
 *   vedevano perche' il caricatore finto carica da solo: qui si guardano le **finestre** che la scheda gli manda, non le immagini).
 * - **GF-5**: tre colonne da 360 dp in su (con la copertina minima a 104 dp erano due: tre solo da 380 dp), due col testo grande come prima.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_360_QUALIFIERS)
class AlbumsTabWindowTest : AlbumsComposeTest() {

  private val tab = AlbumsTabScenes()

  /** Un album per ognuno dei [count] numeri, con la copertina di un media suo. */
  private fun manyAlbums(count: Int): AlbumsTabState.Ready {
    val items = (0 until count).map { n ->
      AlbumsTabEntry.Item(
        Album.Manual(
          id = AlbumId(UUID(0x4_0000_0000L, n.toLong()).toString()),
          title = "Album $n",
          cover = CoverRef.Auto(FakeAlbumLibrary.id(n)),
          count = 3,
          sort = AlbumSort.NEWEST_FIRST,
          updatedAtNas = 1_000L + n,
          newestDay = null,
        ),
      )
    }
    return AlbumsTabState.Ready(items, pinned = 0)
  }

  private fun entryCover(state: AlbumsTabState.Ready, index: Int) = state.entries[index].cover

  @Test
  fun scorrendoInFondoLeCopertineDellUltimaSchermataVengonoChieste() {
    val state = manyAlbums(120)
    val thumbs = TestAlbumThumbnails()
    var list: LazyListState? = null
    show {
      AlbumHost {
        val listState = rememberLazyListState()
        list = listState
        AlbumsTabScreen(state, thumbs.thumbnails, NoAlbumsTabHandlers, listState = listState)
      }
    }
    // In cima: si chiedono le prime copertine e non quelle in fondo.
    val first = thumbs.loader.windows.last().first.map { it.key.mediaId }
    assertThat(first).contains(entryCover(state, 0))
    assertThat(first).doesNotContain(entryCover(state, 119))
    val windowsBefore = thumbs.loader.windows.size

    // Si scorre in fondo (le righe sono 40 a tre colonne, l'ultima e' l'elemento 39 della lista): l'ultima schermata deve avere le sue copertine.
    composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(39)
    composeRule.waitForIdle()

    val last = thumbs.loader.windows.last()
    val visible = last.first.map { it.key.mediaId }
    assertWithMessage("l'ultima copertina e' nella finestra").that(visible).contains(entryCover(state, 119))
    assertWithMessage("la prima non e' piu' nella finestra").that(visible).doesNotContain(entryCover(state, 0))
    // Le ultime tre righe sono in vista: le copertine oltre la 48ª, che prima non si chiedevano mai, ci sono.
    assertThat(visible).containsAtLeastElementsIn((111 until 120).map { entryCover(state, it) })
    // Niente richiesta per fotogramma: una finestra per cambio delle righe in vista, non una per pixel di scorrimento.
    assertWithMessage("finestre mandate scorrendo: ${thumbs.loader.windows.size - windowsBefore}").that(thumbs.loader.windows.size - windowsBefore).isAtMost(6)
    assertThat(list).isNotNull()
  }

  private fun tileY(startsWith: String): Float {
    val matcher = hasContentDescription(startsWith, substring = true)
    val node = composeRule.onAllNodes(matcher).fetchSemanticsNodes().first()
    return node.positionInRoot.y
  }

  private fun screen(): @Composable () -> Unit = {
    AlbumHost { AlbumsTabScreen(tab.full(), tab.scenes.thumbnails.thumbnails, NoAlbumsTabHandlers) }
  }

  @Test
  fun sul360DpLaSchedaHaTreColonne() {
    show { screen()() }
    // I tre fissi (Preferiti, Fotocamera, Video) stanno nella stessa riga; il quarto, un album, nella riga sotto.
    val favorites = tileY("Preferiti")
    assertThat(tileY("Fotocamera")).isEqualTo(favorites)
    assertThat(tileY("Video")).isEqualTo(favorites)
    val fourth = tab.full().entries[3]
    val fourthTitle = (fourth as? AlbumsTabEntry.Item)?.album?.title ?: error("la quarta voce della scheda piena e' un album")
    assertThat(tileY(fourthTitle)).isGreaterThan(favorites)
  }

  @Test
  fun colTestoGrandeLeColonneSonoDueComePrima() {
    show(fontScale = 1.5f) { screen()() }
    val favorites = tileY("Preferiti")
    assertThat(tileY("Fotocamera")).isEqualTo(favorites)
    assertThat(tileY("Video")).isGreaterThan(favorites)
  }
}
