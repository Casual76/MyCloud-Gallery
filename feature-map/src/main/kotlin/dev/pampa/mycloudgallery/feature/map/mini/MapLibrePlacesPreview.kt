package dev.pampa.mycloudgallery.feature.map.mini

import android.content.Context
import android.graphics.Bitmap
import dev.pampa.mycloudgallery.feature.map.contract.MapEngineStarter
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import dev.pampa.mycloudgallery.feature.map.places.PlacesPreviewRenderer
import dev.pampa.mycloudgallery.feature.map.places.PreviewCamera
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter

// LA CARTA DELL'ANTEPRIMA DEI LUOGHI (fase I, I1): `MapSnapshotter` di MapLibre con lo **stesso stile solo offline** della mini-mappa del viewer
// (`MapStyles.miniMap`: la carta di base negli asset, nessuna sorgente online, nessuno sprite: **nessuna richiesta di rete**, qualunque cosa dica la mappa
// dettagliata). Un'immagine ferma: mentre la pagina scorre non costa niente (la scheda la disegna come una foto). Nessun segno sopra: le foto dei luoghi le
// appunta la scheda delle Raccolte, con i suoi cancelli. MapLibre parte pigra ([MapEngineStarter]) e **solo quando l'anteprima e' sullo schermo**.
// **MapLibre non si disegna in Robolectric**: si prova sul telefono; l'inquadratura e le regole si provano sulla JVM (`PlacesPreviewFramesTest`).

class MapLibrePlacesPreview(
  private val context: Context,
  private val starter: MapEngineStarter,
) : PlacesPreviewRenderer {

  override suspend fun render(camera: PreviewCamera, widthDp: Int, heightDp: Int, density: Float, dark: Boolean): Bitmap? {
    val shot = withContext(Dispatchers.Main.immediate) {
      if (!starter.ensure(context)) return@withContext null
      val target = LatLng(camera.center.latE6 / MICRO, camera.center.lonE6 / MICRO)
      suspendCancellableCoroutine<Bitmap?> { continuation ->
        // La misura in dp e il rapporto dei pixel dello schermo: la carta ha la scala giusta (lo zoom e' quello dei 512 dp del mondo) ed e' nitida.
        val options = MapSnapshotter.Options(widthDp, heightDp)
          .withStyleBuilder(Style.Builder().fromUri(MapStyles.miniMap(dark).uri))
          .withCameraPosition(CameraPosition.Builder().target(target).zoom(camera.zoom).build())
          .withPixelRatio(density)
          .withLogo(false)
        val snapshotter = MapSnapshotter(context, options)
        continuation.invokeOnCancellation { snapshotter.cancel() }
        snapshotter.start(
          { snapshot -> if (continuation.isActive) continuation.resume(snapshot.bitmap) },
          { if (continuation.isActive) continuation.resume(null) },
        )
      }
    } ?: return null
    // La bitmap e' del MapSnapshotter: se ne tiene una copia nostra, fatta fuori dal thread principale.
    return withContext(Dispatchers.Default) { shot.copy(Bitmap.Config.ARGB_8888, false) }
  }

  private companion object {
    const val MICRO = 1_000_000.0
  }
}
