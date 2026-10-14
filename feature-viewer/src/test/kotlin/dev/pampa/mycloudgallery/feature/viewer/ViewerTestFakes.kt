package dev.pampa.mycloudgallery.feature.viewer

import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedSpec
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.contract.ZoomSpec
import dev.pampa.mycloudgallery.core.imaging.testing.FakeViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerAnchor
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Le immagini finte che capiscono anche i riferimenti del telefono: i video e le motion photo del viewer passano da
 * `MediaRef.Phone` (l'URI serve al lettore), ma i pixel vengono dai gradienti sintetici. Inoltra tutto a
 * [FakeViewerImageSource] dopo aver trasformato il riferimento.
 */
internal class PhoneAwareImages(
  val delegate: FakeViewerImageSource = FakeViewerImageSource(),
  /** Dal riferimento del telefono a quello sintetico con le proporzioni dell'elemento (come le darebbe la sorgente vera). */
  private val mapper: (MediaRef.Phone) -> MediaRef = { MediaRef.Synthetic(seed = it.mediaStoreId, width = 1600, height = 900) },
) : ViewerImageSource {

  private fun synthetic(ref: MediaRef): MediaRef = when (ref) {
    is MediaRef.Phone -> mapper(ref)
    else -> ref
  }

  /** I riferimenti che il test dichiara animati (GIF, WebP). */
  val animated: MutableMap<MediaRef, AnimatedSpec> = HashMap()

  override fun state(ref: MediaRef, screen: IntSize): Flow<ViewerImageState> = delegate.state(synthetic(ref), screen)

  /** Con `true` la sorgente dichiara i tasselli possibili (serve un file vero: in Robolectric di solito no). */
  var tiled: Boolean = false

  override fun zoomSpec(ref: MediaRef): ZoomSpec? = delegate.zoomSpec(synthetic(ref))?.copy(tiled = tiled)

  override fun animated(ref: MediaRef): AnimatedSpec? = animated[ref]
}

/** Gli elementi del viewer con riferimenti del telefono per i video e le motion photo (servono al lettore e alla pressione lunga). */
internal class PhoneItems(
  initial: TimelineView,
  private val fixture: ViewerFixture,
) : ViewerItems {
  private val flow = MutableStateFlow(initial)
  override val view: StateFlow<TimelineView> = flow

  fun publish(view: TimelineView) {
    flow.value = view
  }

  override fun ref(id: MediaId): MediaRef {
    val index = fixture.library.snapshot.indexOf(id)
    val kind = fixture.library.snapshot.kindAt(index)
    return MediaRef.Phone("external_primary", 1000L + index, if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg")
  }

  fun uriOf(ref: MediaRef.Phone): Uri = Uri.parse("content://media/${ref.volume}/${ref.mediaStoreId}")
}

/** I dettagli di prova: una data a Oslo (UTC+1), il luogo, un file JPEG da 12 MP in DCIM/Camera. */
internal fun sampleDetails(id: MediaId, kind: MediaKind = MediaKind.PHOTO, estimated: Boolean = false): MediaDetails = MediaDetails(
  id = id,
  kind = kind,
  fileName = "IMG_20240312_124100.jpg",
  folder = "DCIM/Camera/",
  sizeBytes = 3_600_000L,
  mime = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
  width = 4000,
  height = 3000,
  orientationDegrees = 0,
  durationMs = if (kind == MediaKind.VIDEO) 134_000L else null,
  captureTime = CaptureTime(
    utcMillis = 1_710_243_660_000L,
    offsetMinutes = 60,
    confidence = if (estimated) CaptureTimeConfidence.ESTIMATED else CaptureTimeConfidence.EXACT,
    source = if (estimated) CaptureTimeSource.FILE_NAME else CaptureTimeSource.EXIF_WITH_OFFSET,
  ),
  location = GeoPoint(59.9127, 10.7461),
  place = PlaceRef(FakePlaceData.OSLO.place.id.value, 1),
)

internal fun FakeViewerAnchor.cellAt(id: MediaId, left: Float, top: Float, size: Float) {
  bounds[id] = dev.pampa.mycloudgallery.core.library.contract.PixelRect(left, top, left + size, top + size)
}

/** Un passo del copione di una sorgente di immagini finta. */
internal sealed interface Step {
  data class Emit(val state: ViewerImageState) : Step

  data class Wait(val millis: Long) : Step
}

/**
 * Una sorgente di immagini che segue un **copione** per ogni riferimento: anteprima, attesa, nitida; oppure anteprima e
 * "il NAS non risponde". Conta i flussi attivi insieme e quelli annullati prima di finire, per E-V5 ("al massimo tre
 * decodifiche in volo, le altre annullate"). Capisce i riferimenti del telefono come [PhoneAwareImages].
 */
internal class ScriptedImages(
  private val script: (MediaRef) -> List<Step>,
) : ViewerImageSource {

  var inFlight: Int = 0
    private set
  var maxInFlight: Int = 0
    private set
  var cancelled: Int = 0
    private set
  var started: Int = 0
    private set
  val requested: MutableList<MediaRef> = mutableListOf()

  override fun state(ref: MediaRef, screen: IntSize): Flow<ViewerImageState> = kotlinx.coroutines.flow.flow {
    started++
    requested += ref
    inFlight++
    maxInFlight = maxOf(maxInFlight, inFlight)
    var finished = false
    try {
      for (step in script(ref)) {
        when (step) {
          is Step.Emit -> emit(step.state)
          is Step.Wait -> kotlinx.coroutines.delay(step.millis)
        }
      }
      finished = true
    } finally {
      inFlight--
      if (!finished) cancelled++
    }
  }

  override fun zoomSpec(ref: MediaRef): ZoomSpec? = ZoomSpec(1600, 900, tiled = false)

  override fun animated(ref: MediaRef): AnimatedSpec? = null

  companion object {
    /** Un gradiente come bitmap (diverso per ogni riferimento, come le foto vere). */
    fun bitmap(ref: MediaRef, side: Int = 96): androidx.compose.ui.graphics.ImageBitmap {
      val seed = when (ref) {
        is MediaRef.Phone -> ref.mediaStoreId
        is MediaRef.Synthetic -> ref.seed
        // Fase 4 (P0): il riferimento del NAS esiste; il gradiente si fa dalla chiave di contenuto.
        is MediaRef.Nas -> ref.contentKey
      }
      val width = side
      val height = side * 9 / 16
      return SyntheticPixels.bitmap(seed, width, height).asImageBitmap()
    }

    fun preview(ref: MediaRef) = Step.Emit(ViewerImageState.Preview(bitmap(ref, 48)))

    fun sharp(ref: MediaRef) = Step.Emit(ViewerImageState.Sharp(bitmap(ref), hdr = false, colorSpace = null))
  }
}
