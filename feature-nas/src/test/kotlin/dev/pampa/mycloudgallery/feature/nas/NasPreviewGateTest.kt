package dev.pampa.mycloudgallery.feature.nas

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.ui.test.hasTestTag
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.contract.NasFolderPreviews
import dev.pampa.mycloudgallery.feature.nas.contract.NasThumbnails
import dev.pampa.mycloudgallery.feature.nas.folders.NasEntryArt
import dev.pampa.mycloudgallery.feature.nas.folders.NasPreviewImageTag
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * CG-2 e CS-11 (fase I): le anteprime di Cartelle NAS **gia' disegnate** seguono la Libreria visibile. La riga e' composta, la miniatura e' nella cache,
 * nessuna rilettura del catalogo gira (le anteprime della pagina restano quelle di prima): quando la Libreria visibile cambia e la foto non c'e' piu'
 * (nascosta, area esclusa) la richiesta e' rifiutata e la miniatura sparisce **subito**; in una copertina a mosaico resta solo cio' che e' ancora
 * visibile, e una cartella il cui unico contenuto visibile e' appena stato nascosto torna alla sua piastrella. Senza il cancello sulla vista di adesso
 * (la richiesta ricordata con le chiavi di prima) la prova cade.
 */
class NasPreviewGateTest : ComposeNasTest() {

  private fun media(n: Int) = MediaId("00000000-0000-4000-8000-%012d".format(n))

  /** La Libreria visibile finta: un segno che cambia a ogni modifica e gli id che contiene. */
  private val visible = MutableStateFlow<Any?>(0)
  private val shown = HashSet<MediaId>()

  private val thumbnails = NasThumbnails(FakeThumbnailLoader(), visible) { media ->
    if (media in shown) ThumbRequest(ThumbKey(media, 1L, ThumbBucket.M), MediaRef.Synthetic(media.value.hashCode().toLong(), 4, 3)) else null
  }

  /** Toglie [ids] dalla Libreria visibile, come un «Nascondi» dal viewer: cambia la vista, nient'altro. */
  private fun hide(id: MediaId) {
    shown.remove(id)
    visible.value = (visible.value as Int) + 1
    composeRule.waitForIdle()
  }

  /** Quante miniature sono disegnate nella riga (il file, o le celle della copertina). */
  private fun drawn(): Int = composeRule.onAllNodes(hasTestTag(NasPreviewImageTag), useUnmergedTree = true).fetchSemanticsNodes().size

  private val file = NasFolderEntry(SharePath.parse("Viaggi/mappa.jpg"), false, 1_000, 0L, 7L)
  private val folder = NasFolderEntry(SharePath.parse("Viaggi/Oslo"), true, 0, 0L, null, 6)

  @Test
  fun unaFotoNascostaSmetteDiDisegnarsiPrimaDiOgniRilettura() {
    shown += media(1)
    val previews = NasFolderPreviews(files = mapOf(file.path to media(1)))
    show { NasEntryArt(file, Icons.Rounded.Image, GalleryIconFamily.Photos, previews, thumbnails) }
    assertThat(drawn()).isEqualTo(1)

    hide(media(1))
    assertThat(drawn()).isEqualTo(0)
  }

  @Test
  fun ilMosaicoTieneSoloCioCheEAncoraVisibile() {
    shown += (1..4).map(::media)
    val previews = NasFolderPreviews(folders = mapOf(folder.path to (1..4).map(::media)))
    show { NasEntryArt(folder, Icons.Rounded.Folder, GalleryIconFamily.Network, previews, thumbnails) }
    assertThat(drawn()).isEqualTo(4)

    // Una delle quattro nascosta: niente mosaico con un buco (ne' con la nascosta), la copertina e' una delle visibili finche' la rilettura arriva.
    hide(media(2))
    assertThat(drawn()).isEqualTo(1)
  }

  @Test
  fun unaCartellaConLUnicaFotoVisibileAppenaNascostaTornaAllaPiastrella() {
    shown += media(9)
    val previews = NasFolderPreviews(folders = mapOf(folder.path to listOf(media(9))))
    show { NasEntryArt(folder, Icons.Rounded.Folder, GalleryIconFamily.Network, previews, thumbnails) }
    assertThat(drawn()).isEqualTo(1)

    hide(media(9))
    assertThat(drawn()).isEqualTo(0)
  }

  @Test
  fun unMediaMaiVisibileNonHaRichiesta() {
    // CS-11: il rifiuto della porta: un media che la Libreria visibile non contiene non arriva mai alla cache.
    val previews = NasFolderPreviews(files = mapOf(file.path to media(5)))
    show { NasEntryArt(file, Icons.Rounded.Image, GalleryIconFamily.Photos, previews, thumbnails) }
    assertThat(drawn()).isEqualTo(0)
  }
}
