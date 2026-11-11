package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.antigravity.fluidengine.ui.theme.FluidToneTile
import dev.antigravity.fluidengine.ui.theme.FluidToneTileSize
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// LE COPERTINE DELLE RACCOLTE (H5, fase H, 11/11). L'utente voleva voci "piu' rappresentative e meno standard": dove una voce ha una foto che la
// rappresenta (l'ultimo ricordo, un viaggio) la piastrella mostra **quella foto** al posto dell'icona; dove non ce l'ha, o non deve averla
// (Nascosti, Eliminati di recente, i comandi), resta l'icona sulla piastrella del tono. Le due cose hanno la stessa misura, cosi' i testi di un
// gruppo partono tutti dallo stesso punto.
//
// **Non costa niente di nuovo.** La copertina e' un `MediaId` che la sezione ha gia' (il ricordo e il viaggio lo portano); la miniatura viene dalla
// **stessa cache** della griglia, con la stessa chiave (versione e taglia M, la taglia della Libreria a Giorni e delle copertine della scheda Album):
// nessuna query, nessuna lettura in piu' per disegnare la scheda.
//
// **Mai un nascosto.** Il riferimento si cerca nella Libreria visibile ([CollectionItemsSource] con `LibraryFilter.ALL`), che non contiene mai i
// nascosti (E-H1): una copertina che e' stata nascosta dopo che la sezione l'ha scelta non ha una richiesta, e la riga mostra l'icona.

/**
 * Da dove arrivano le copertine: il caricatore delle miniature e la Libreria visibile. `null` (il default della composizione) = niente copertine,
 * solo icone: e' cosi' nei test e nelle anteprime che non le passano.
 */
@Stable
class CollectionCovers(val loader: ThumbnailLoader, private val library: ViewerItems) {

  /** La Libreria visibile: cambia quando cambia l'indice (una foto nascosta, una nuova). */
  val view get() = library.view

  /** La richiesta della miniatura di [media] in [view], o null se il media non e' nella Libreria visibile (un nascosto, un eliminato). */
  fun request(view: TimelineView, media: MediaId): ThumbRequest? {
    val viewIndex = view.viewIndexOf(media)
    if (viewIndex < 0) return null
    val ref = library.refOrNull(media) ?: return null
    val index = view.snapshotIndex(viewIndex)
    val version = (view.snapshot as? ColumnarTimelineSnapshot)?.versionKeyAt(index) ?: MediaGridState.DefaultVersionKey
    return ThumbRequest(ThumbKey(media, version, ThumbBucket.M), ref)
  }
}

/** Le copertine della scheda Raccolte: le mette `CollectionsTab`; null = solo icone. */
val LocalCollectionCovers = staticCompositionLocalOf<CollectionCovers?> { null }

/** Il lato della piastrella delle righe delle Raccolte: piu' grande di quella di serie (32 dp), perche' una foto a 32 dp non si legge. */
internal val CollectionRowArtSize: Dp = 44.dp

/**
 * Chiede le miniature delle copertine [media] **una volta**, e di nuovo solo se l'elenco o la Libreria cambiano: non a ogni scorrimento (sono
 * poche e stanno tutte nella pagina). Si usa solo con un pannello: sul tablet il pannello accanto puo' essere una griglia, che ha la sua finestra
 * di richieste e non va disturbata; li' la copertina c'e' se e' gia' in cache, altrimenti resta l'icona.
 */
@Composable
internal fun CollectionCoverWindow(covers: CollectionCovers, media: List<MediaId>) {
  LaunchedEffect(covers, media) {
    if (media.isEmpty()) return@LaunchedEffect
    covers.view
      .map { view -> media.mapNotNull { covers.request(view, it) } }
      .distinctUntilChanged()
      .collect { requests -> if (requests.isNotEmpty()) covers.loader.window(requests, emptyList()) }
  }
}

/**
 * La piastrella di una riga delle Raccolte: l'icona sul tono e, sopra, la copertina [cover] quando la miniatura c'e' (entra in dissolvenza la
 * prima volta, ferma se era gia' in cache). Senza copertine ([LocalCollectionCovers] null) o senza [cover], l'icona e basta.
 */
@Composable
internal fun CollectionRowArt(icon: ImageVector, tone: FluidTone, cover: MediaId?) {
  Box(modifier = Modifier.size(CollectionRowArtSize)) {
    FluidToneTile(tone = tone, size = CollectionRowArtSize) { Icon(imageVector = icon, contentDescription = null) }
    val covers = LocalCollectionCovers.current
    if (cover != null && covers != null) CoverImage(covers, cover, Modifier.matchParentSize())
  }
}

@Composable
private fun CoverImage(covers: CollectionCovers, media: MediaId, modifier: Modifier) {
  val view by covers.view.collectAsState()
  val request = remember(view, media) { covers.request(view, media) } ?: return
  val image = covers.loader.image(request.key, request.ref).value
  val alpha by animateFloatAsState(targetValue = if (image != null) 1f else 0f, animationSpec = FluidMotion.fadeIn(), label = "copertinaRaccolta")
  if (image == null) return
  Image(
    bitmap = image,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    // La forma della piastrella del tono alla stessa misura (il raggio segue il lato, come in `FluidToneTile`); l'alfa si legge nel disegno.
    modifier = modifier.clip(ContinuousCornerShape(FluidRadius.Small * (CollectionRowArtSize / FluidToneTileSize))).graphicsLayer { this.alpha = alpha },
  )
}
