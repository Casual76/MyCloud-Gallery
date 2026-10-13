package dev.pampa.mycloudgallery.feature.viewer

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.noop.NoPlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.noop.NoViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetailsReader
import dev.pampa.mycloudgallery.core.metadata.noop.NoExifDetailsReader
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.places.noop.NoPlaceNames
import dev.pampa.mycloudgallery.core.places.noop.NoReverseGeocoder
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetailsSource
import dev.pampa.mycloudgallery.feature.viewer.details.NoMediaDetails
import dev.pampa.mycloudgallery.feature.viewer.details.NoViewerBytes
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerBytes
import dev.pampa.mycloudgallery.feature.viewer.video.BytesMotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayerFactory
import kotlinx.coroutines.withContext

/**
 * Tutto cio' che il viewer riceve da fuori, in un posto solo. `:app` lo costruisce dal grafo (P11); i test e gli
 * screenshot lo costruiscono con i fake.
 *
 * **Fuori dalla firma di [ViewerOverlay] non c'e' niente di vero.** Il viewer non conosce Hilt, il catalogo ne' il
 * MediaStore: conosce questi contratti. I campi `null` sono **capacita' assenti** e il viewer le nasconde invece di
 * mostrare una voce morta (D13): senza [actions] non ci sono ne' "Preferito" ne' "Nascondi", senza [intents] non ci
 * sono "Condividi", "Copia" ed "Elimina dal telefono". I default sono i null-object: un viewer senza niente mostra
 * le immagini che ha e basta.
 *
 * @property images da dove arrivano i pixel (anteprima, versione nitida, zoom, animati).
 * @property thumbnails la cache delle miniature, perche' l'espansione parta dalla stessa bitmap della cella (`peek`).
 * @property placeholders i segnaposto sfocati (ThumbHash), per la pagina che non ha ancora niente.
 * @property details i dettagli del catalogo per il pannello informazioni.
 * @property exif l'EXIF su richiesta, letto dai [bytes].
 * @property motion dove sta il video di una motion photo (di default lo cerca nei [bytes]).
 * @property player come si crea il lettore video; null = Media3 (il default).
 * @property animated come si decodificano GIF e WebP animati; null = `ImageDecoder` sull'URI di [intents].
 * @property onConfirmMobileData cosa fare quando l'utente accetta i dati mobili per un originale (Fase 4); null = la
 *   nota non propone il tasto.
 * @property dispatchers i thread di lavoro (letture di dettagli ed EXIF): nei test sono uno solo, senza attese.
 */
@Immutable
class ViewerServices(
  val images: ViewerImageSource = NoViewerImageSource,
  val thumbnails: ThumbnailLoader = NoThumbnailLoader,
  val placeholders: PlaceholderCache = NoPlaceholderCache,
  val details: MediaDetailsSource = NoMediaDetails,
  val exif: ExifDetailsReader = NoExifDetailsReader,
  val bytes: ViewerBytes = NoViewerBytes,
  val motion: MotionVideoLocator = BytesMotionVideoLocator(bytes),
  val places: PlaceNames = NoPlaceNames,
  val geocoder: ReverseGeocoder = NoReverseGeocoder,
  val actions: LocalMediaActions? = null,
  val intents: PhoneMediaIntents? = null,
  val player: ViewerPlayerFactory? = null,
  val animated: AnimatedDrawableLoader? = null,
  val onConfirmMobileData: ((MediaRef) -> Unit)? = null,
  val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
)

/** Decodifica un'immagine animata (GIF, WebP animato) in un `Drawable` che si anima da solo (E-M11). */
fun interface AnimatedDrawableLoader {
  suspend fun load(ref: MediaRef): Drawable?
}

/** Il decoder vero: `ImageDecoder` sull'URI `content://` di [uriOf]. Gira fuori dal thread principale. */
class ImageDecoderAnimatedLoader(
  private val resolver: ContentResolver,
  private val uriOf: (MediaRef.Phone) -> Uri,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : AnimatedDrawableLoader {

  override suspend fun load(ref: MediaRef): Drawable? {
    val phone = ref as? MediaRef.Phone ?: return null
    return withContext(dispatchers.io) {
      try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, uriOf(phone)))
      } catch (_: java.io.IOException) {
        null
      } catch (_: SecurityException) {
        null
      } catch (_: OutOfMemoryError) {
        null
      }
    }
  }
}
