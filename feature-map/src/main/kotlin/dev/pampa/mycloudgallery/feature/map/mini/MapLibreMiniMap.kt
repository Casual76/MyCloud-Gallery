package dev.pampa.mycloudgallery.feature.map.mini

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.feature.map.contract.MapEngineStarter
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter

// LA MINI-MAPPA VERA (Fase 9, P4; docs/fase9-progetto.md §2.10 "Mini-mappa del viewer", D29, E-MAP16). `MapSnapshotter` di MapLibre con lo stile **solo offline** (`MapStyles.miniMap`: nessuna sorgente online, nessuno
// sprite, solo `asset://`: **nessuna richiesta di rete**, qualunque cosa dica la mappa dettagliata) e un segno sul punto disegnato con il colore del tema. Con l'online spento, accesso o in roaming non cambia niente.
// **MapLibre non si disegna in Robolectric**: questa parte si prova sul telefono (F122); lo stile e le regole (mai offline-con-sorgente, mai un nascosto) si provano sulla JVM sopra [MiniMapService].
// MapLibre parte pigra (`MapEngineStarter`, con il nostro client installato prima), mai all'avvio.

class MapLibreMiniMap(
  private val context: Context,
  private val starter: MapEngineStarter,
) : MiniMapRenderer {

  override suspend fun render(point: GeoE6, widthPx: Int, heightPx: Int, look: MiniMapLook): Bitmap? = withContext(Dispatchers.Main.immediate) {
    if (!starter.ensure(context)) return@withContext null
    val target = LatLng(point.latE6 / 1_000_000.0, point.lonE6 / 1_000_000.0)
    suspendCancellableCoroutine { continuation ->
      val options = MapSnapshotter.Options(widthPx, heightPx)
        .withStyleBuilder(Style.Builder().fromUri(MapStyles.miniMap(look.dark).uri))
        .withCameraPosition(CameraPosition.Builder().target(target).zoom(ZOOM).build())
        .withPixelRatio(1f)
        .withLogo(false)
      val snapshotter = MapSnapshotter(context, options)
      continuation.invokeOnCancellation { snapshotter.cancel() }
      snapshotter.start(
        { snapshot ->
          // La bitmap della mappa e' del MapSnapshotter: se ne fa una copia modificabile e ci si disegna il segno.
          val bitmap = snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
          val at = snapshot.pixelForLatLng(target)
          val canvas = Canvas(bitmap)
          val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ring }
          val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.marker }
          canvas.drawCircle(at.x, at.y, MARKER_RING_DP * look.density, ring)
          canvas.drawCircle(at.x, at.y, MARKER_DOT_DP * look.density, dot)
          if (continuation.isActive) continuation.resume(bitmap)
        },
        { if (continuation.isActive) continuation.resume(null) },
      )
    }
  }

  private companion object {
    /** Lo zoom della mini-mappa: una regione, abbastanza da riconoscere il posto con la sola carta di base (lo stile offline si ferma a 9). */
    const val ZOOM = 6.5
    const val MARKER_RING_DP = 9f
    const val MARKER_DOT_DP = 6f
  }
}
