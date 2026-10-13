package dev.pampa.mycloudgallery.core.imaging.noop

import android.graphics.Bitmap
import android.os.CancellationSignal
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedSpec
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbStats
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.contract.ZoomSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// I null-object di core-imaging: cio' che :app lega finche' P6 non porta le implementazioni vere.
//
// Non stanno nel package `testing` di proposito: vanno nella release, mentre i fake no (tools/check.ps1 lo
// verifica). Falliscono chiusi: nessuna immagine, nessun errore sollevato. Le celle restano sul segnaposto e il
// viewer dice "non disponibile" invece di mostrare l'immagine sbagliata.

/** Nessun caricatore: le celle non ricevono mai una miniatura. */
object NoThumbnailLoader : ThumbnailLoader {
  private val empty: State<ImageBitmap?> = mutableStateOf(null)

  override fun image(key: ThumbKey, ref: MediaRef): State<ImageBitmap?> = empty

  override fun peek(key: ThumbKey): ImageBitmap? = null

  override fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>) = Unit

  override fun trim(level: Int) = Unit

  override val stats: StateFlow<ThumbStats> = MutableStateFlow(ThumbStats())
}

/** Nessuna sorgente: non produce pixel. */
object NoThumbnailSource : ThumbnailSource {
  override suspend fun load(ref: MediaRef, px: Int, cancel: CancellationSignal): Bitmap? = null
}

/** Nessun segnaposto vero: una bitmap 1x1 trasparente (ogni cella mostra solo il suo fondo). */
object NoPlaceholderCache : PlaceholderCache {
  private val transparent: ImageBitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap() }

  override fun image(h: ThumbHash): ImageBitmap = transparent
}

/** Nessuna immagine per il viewer: ogni media e' "non trovato" (la UI lo spiega e non si blocca). */
object NoViewerImageSource : ViewerImageSource {
  override fun state(ref: MediaRef, screen: IntSize): Flow<ViewerImageState> = flowOf(ViewerImageState.Failed(ImageFailure.NOT_FOUND))

  override fun zoomSpec(ref: MediaRef): ZoomSpec? = null

  override fun animated(ref: MediaRef): AnimatedSpec? = null
}
