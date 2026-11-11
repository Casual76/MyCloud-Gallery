package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import kotlinx.coroutines.flow.Flow

/**
 * Un album in cui sta un media: il nome per la UI e quanti elementi ha. [key] e' l'identita' dell'album, che usa solo chi lo apre
 * ([ViewerMediaAlbums.open]).
 */
@Immutable
data class ViewerAlbumEntry(val key: String, val title: String, val count: Int)

/**
 * Gli album in cui sta un media (H2, giro di correzione 1: "gli album dell'elemento"). Una porta **letta solo a pannello informazioni aperto**:
 * `:app` la lega agli album veri (gli elementi di ogni album nel catalogo); senza
 * ([dev.pampa.mycloudgallery.feature.viewer.ViewerServices.mediaAlbums] null) la sezione non c'e', come per ogni capacita' assente (D13).
 */
interface ViewerMediaAlbums {

  /** Gli album di [media], nell'ordine dell'elenco degli album; vuoto se non sta in nessuno. */
  fun albumsOf(media: MediaId): Flow<List<ViewerAlbumEntry>>

  /** Apre la pagina dell'album (chi lega la porta chiude il viewer e ci porta). */
  fun open(album: ViewerAlbumEntry)
}

/** La sezione "Album" del pannello: quello che `ViewerOverlay` monta fra le sezioni in piu'. */
internal class AlbumsPanel(val sections: List<InfoSectionModel>) {
  companion object {
    /** Nessuna sezione: il viewer senza la porta, il pannello chiuso, un media che non sta in nessun album. */
    val NONE: AlbumsPanel = AlbumsPanel(emptyList())
  }
}

/** La sezione "Album" per [media] mentre il pannello e' aperto ([active]): un tocco su un album lo apre. */
@Composable
internal fun rememberAlbumsPanel(
  source: ViewerMediaAlbums?,
  media: MediaId,
  active: Boolean,
  onOpen: ((ViewerAlbumEntry) -> Unit)? = null,
): AlbumsPanel {
  if (source == null || !active) return AlbumsPanel.NONE
  val albums by remember(source, media) { source.albumsOf(media) }.collectAsState(initial = emptyList())
  val header = stringResource(R.string.viewer_albums_header)
  // CS-22: nel viewer il tocco chiude prima il viewer (con il suo volo) e poi apre l'album; da solo (le prove del pannello) apre e basta.
  val open = rememberUpdatedState(onOpen ?: source::open)
  val sections = remember(albums, header) {
    if (albums.isEmpty()) {
      emptyList()
    } else {
      listOf(InfoSectionModel(header = header, rows = emptyList(), content = { AlbumEntries(albums) { open.value(it) } }, icon = Icons.Rounded.PhotoAlbum))
    }
  }
  return AlbumsPanel(sections)
}

/** Gli album come voci da toccare: il nome, che apre l'album. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumEntries(albums: List<ViewerAlbumEntry>, onOpen: (ViewerAlbumEntry) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(EntryGap), verticalArrangement = Arrangement.spacedBy(EntryGap)) {
    albums.forEach { album ->
      FluidChip(
        label = album.title,
        selected = false,
        onClick = { onOpen(album) },
        leading = { Icon(imageVector = Icons.Rounded.PhotoAlbum, contentDescription = null) },
      )
    }
  }
}

private val EntryGap = 8.dp
