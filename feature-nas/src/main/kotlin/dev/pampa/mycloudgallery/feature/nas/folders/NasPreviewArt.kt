package dev.pampa.mycloudgallery.feature.nas.folders

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.galleryIconTileShape
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.feature.nas.NasDimens
import dev.pampa.mycloudgallery.feature.nas.contract.NasFolderPreviews
import dev.pampa.mycloudgallery.feature.nas.contract.NasThumbnails
import kotlinx.coroutines.flow.distinctUntilChanged

// LE ANTEPRIME NELLE RIGHE DI CARTELLE NAS (I4, fase I: «l'anteprima vince sull'icona»). Ogni riga ha la sua piastrella per tipo, sempre della stessa
// misura: quando la Libreria conosce il file (o le ultime foto di una cartella) la miniatura le si posa sopra, ritagliata nella stessa forma, e la riga non
// cambia altezza. Una cartella con almeno quattro foto conosciute ha un mosaico di quattro; con meno, la piu' recente; in ogni caso il segno della cartella
// nell'angolo. Nessun caricamento nuovo: la cache e la porta sono quelle della Libreria visibile ([NasThumbnails]); le richieste partono solo per le righe
// sullo schermo e per il gruppo dopo ([NasPreviewWindow]), come la griglia.

/** Le richieste di miniatura di una riga: il file, o le foto della copertina di una cartella (al piu' quattro). Nessuna se la Libreria non lo mostra. */
internal fun previewRequests(entry: NasFolderEntry, previews: NasFolderPreviews, thumbnails: NasThumbnails): List<ThumbRequest> =
  if (entry.isFolder) {
    previews.folders[entry.path].orEmpty().take(NasFolderPreviews.FOLDER_COVER_COUNT).mapNotNull(thumbnails.request)
  } else {
    listOfNotNull(previews.files[entry.path]?.let(thumbnails.request))
  }

/**
 * La finestra delle miniature di Cartelle NAS: le righe dei gruppi sullo schermo (le chiavi `entries-N` di `groupedRows`) e il gruppo dopo. Si ricalcola
 * quando cambiano i gruppi visibili, non a ogni fotogramma; senza anteprime o senza cache non chiede niente.
 */
@Composable
internal fun NasPreviewWindow(listState: LazyListState, entries: List<NasFolderEntry>, previews: NasFolderPreviews, thumbnails: NasThumbnails) {
  val loader = thumbnails.loader ?: return
  if (previews == NasFolderPreviews.None) return
  LaunchedEffect(listState, entries, previews, thumbnails) {
    val chunks = entries.chunked(NasDimens.RowsPerGroup)
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { info -> (info.key as? String)?.let(::groupIndex) } }
      .distinctUntilChanged()
      .collect { shown ->
        val visible = shown.flatMap { chunks.getOrNull(it).orEmpty() }
        val ahead = shown.maxOrNull()?.let { chunks.getOrNull(it + 1) }.orEmpty()
        loader.window(
          visible.flatMap { previewRequests(it, previews, thumbnails) },
          ahead.flatMap { previewRequests(it, previews, thumbnails) },
        )
      }
  }
}

/** L'indice del gruppo di righe dalla chiave dell'elemento della lista (`entries-3` -> 3); null per gli altri elementi. */
private fun groupIndex(key: String): Int? = if (key.startsWith(GroupKeyPrefix)) key.removePrefix(GroupKeyPrefix).toIntOrNull() else null

/**
 * Il disegno a sinistra di una riga: la piastrella per tipo ([icon], [family]) e, sopra, la miniatura del file o la copertina della cartella quando la
 * Libreria le conosce. La piastrella resta il fondo: finche' i pixel non arrivano (o se la Libreria non mostra il file) si vede lei.
 */
@Composable
internal fun NasEntryArt(entry: NasFolderEntry, icon: ImageVector, family: GalleryIconFamily, previews: NasFolderPreviews, thumbnails: NasThumbnails) {
  val side = NasDimens.EntryArt
  val requests = remember(entry.path, entry.isFolder, previews, thumbnails) { previewRequests(entry, previews, thumbnails) }
  val loader = thumbnails.loader
  Box(modifier = Modifier.size(side)) {
    GalleryIconTile(icon = icon, family = family, size = side)
    if (loader != null && requests.isNotEmpty()) {
      val images = requests.map { loader.image(it.key, it.ref) }
      Box(modifier = Modifier.matchParentSize().clip(galleryIconTileShape(side))) {
        if (entry.isFolder && images.size >= NasFolderPreviews.FOLDER_COVER_COUNT) Mosaic(images) else Cover(images.first(), Modifier.fillMaxSize())
      }
      if (entry.isFolder) FolderMark(Modifier.align(Alignment.BottomEnd))
    }
  }
}

/** Una miniatura che riempie il suo posto, ritagliata; niente finche' non e' in cache (sotto si vede la piastrella). */
@Composable
private fun Cover(image: State<ImageBitmap?>, modifier: Modifier) {
  val bitmap = image.value ?: return
  Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/** La copertina di una cartella: le sue quattro foto piu' recenti, due per due. */
@Composable
private fun Mosaic(images: List<State<ImageBitmap?>>) {
  Column(modifier = Modifier.fillMaxSize()) {
    for (row in 0 until 2) {
      Row(modifier = Modifier.weight(1f)) {
        for (col in 0 until 2) {
          Box(modifier = Modifier.weight(1f).fillMaxSize().padding(NasDimens.MosaicGap)) {
            Cover(images[row * 2 + col], Modifier.fillMaxSize())
          }
        }
      }
    }
  }
}

/** Il segno della cartella sopra la copertina: un tondo velato con la cartella, cosi' una cartella non si scambia per una foto. */
@Composable
private fun FolderMark(modifier: Modifier) {
  Box(
    modifier = modifier
      .size(NasDimens.FolderMark)
      .background(GalleryOnPhoto.scrim(), FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = Icons.Rounded.Folder, contentDescription = null, tint = GalleryOnPhoto.Content, modifier = Modifier.size(NasDimens.FolderMarkGlyph))
  }
}

/** Il prefisso delle chiavi dei gruppi di righe di `groupedRows` nell'elenco di una cartella. */
private const val GroupKeyPrefix = "entries-"
