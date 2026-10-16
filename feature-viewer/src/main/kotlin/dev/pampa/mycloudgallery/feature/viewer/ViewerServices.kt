package dev.pampa.mycloudgallery.feature.viewer

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
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
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginalFetcher
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopies
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
import dev.pampa.mycloudgallery.feature.viewer.download.PhoneDownload
import dev.pampa.mycloudgallery.feature.viewer.info.MediaBackupStates
import dev.pampa.mycloudgallery.feature.viewer.menu.NasMediaActions
import dev.pampa.mycloudgallery.feature.viewer.video.BytesMotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoSource
import dev.pampa.mycloudgallery.feature.viewer.video.ViewerPlayerFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
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
 * @property onConfirmMobileData cosa fare quando l'utente accetta i dati mobili per un originale (Fase 4): :app registra la
 *   conferma per quel media (`MobileDataConsent.confirm`); il viewer poi ricarica la pagina da solo. null = la nota non
 *   propone il tasto.
 * @property originals l'originale dei media del NAS portato sul telefono con lo zoom (Fase 4, D19): senza, lo zoom resta
 *   sulla nitida che c'e'. Di norma e' la stessa `NasImageOrigin` che e' dietro [images].
 * @property nasVideo i video del NAS in streaming (Fase 4, D19): senza, un video del NAS non parte e il viewer dice che il
 *   NAS non risponde.
 * @property download "Scarica sul telefono" (Fase 4, D20): senza, la voce non compare nel menu (D13).
 * @property nasActions "Sposta", "Rinomina" ed "Elimina…" per un media del NAS (Fase 4B, P14/P16): senza, le voci non compaiono (D13).
 * @property backup lo stato del backup di un media per la riga "Backup" del pannello informazioni (Fase 5A, D16): senza, la riga non c'e' (D13).
 * @property similarCopies Fase 5A (D36, §2.12): il rilevatore delle copie simili per la sezione "N copie" del pannello (con "Mostra come
 *   principale" e "Separa"): senza, la sezione non c'e' (D13). Di norma e' la stessa istanza della Diagnostica e del riepilogo del primo backup.
 * @property outsideCopy dove sta una copia di quel gruppo che non e' nell'indice (le copie unite in una cella hanno nell'indice solo la
 *   copertina): senza, il tocco su una riga di una copia fuori indice non apre niente.
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
  val originals: OriginalFetcher? = null,
  val nasVideo: NasVideoSource? = null,
  val download: PhoneDownload? = null,
  val nasActions: NasMediaActions? = null,
  val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  val backup: MediaBackupStates? = null,
  val similarCopies: SimilarCopies? = null,
  val outsideCopy: OutsideCopyLookup? = null,
)

/** Decodifica un'immagine animata (GIF, WebP animato) in un `Drawable` che si anima da solo (E-M11). */
fun interface AnimatedDrawableLoader {
  suspend fun load(ref: MediaRef): Drawable?
}

/**
 * Il decoder di ripiego: `ImageDecoder` sull'URI `content://` di [uriOf]. Gira fuori dal thread principale.
 *
 * **Con lo stesso tetto del percorso primario** (`PipelineViewerImageSource.drawable` di core-imaging; revisione di
 * sicurezza della Fase 3, R2): la tela si legge dall'intestazione e si riduce a [maxSide] sul lato lungo e a un quarto di
 * [maxPixels] (un'immagine animata tiene in memoria piu' fotogrammi). Una tela che **da sola** supera [maxPixels] (un WebP
 * ostile puo' dichiarare 16383×16383) non si anima: si mostra il primo fotogramma, fermo, alla taglia dello schermo.
 *
 * @param maxSide il lato lungo dello schermo, in pixel.
 * @param maxPixels il tetto di pixel del viewer (`ImagingBudget.viewerMaxPixels`).
 */
class ImageDecoderAnimatedLoader(
  private val resolver: ContentResolver,
  private val uriOf: (MediaRef.Phone) -> Uri,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val maxSide: Int = AnimatedSizing.DEFAULT_MAX_SIDE,
  private val maxPixels: Long = AnimatedSizing.DEFAULT_MAX_PIXELS,
) : AnimatedDrawableLoader {

  override suspend fun load(ref: MediaRef): Drawable? {
    val phone = ref as? MediaRef.Phone ?: return null
    return withContext(dispatchers.io) {
      try {
        val source = ImageDecoder.createSource(resolver, uriOf(phone))
        val bounds = boundsOf(uriOf(phone)) ?: return@withContext null
        if (AnimatedSizing.tooLargeToAnimate(bounds.first, bounds.second, maxPixels)) {
          // Il primo fotogramma, fermo: una bitmap sola alla taglia dello schermo, mai la tela intera.
          val still = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            AnimatedSizing.stillTarget(info.size.width, info.size.height, maxSide, maxPixels)?.let { (w, h) -> decoder.setTargetSize(w, h) }
          }
          BitmapDrawable(null, still)
        } else {
          ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            AnimatedSizing.animatedTarget(info.size.width, info.size.height, maxSide, maxPixels)?.let { (w, h) -> decoder.setTargetSize(w, h) }
          }
        }
      } catch (_: java.io.IOException) {
        null
      } catch (_: SecurityException) {
        null
      } catch (_: IllegalArgumentException) {
        null
      } catch (_: OutOfMemoryError) {
        null
      }
    }
  }

  /** Le dimensioni dichiarate nell'intestazione (nessun pixel decodificato), o null se non si leggono. */
  private fun boundsOf(uri: Uri): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
  }
}

/**
 * Le taglie di decodifica delle immagini animate (R2): le stesse regole del percorso primario di core-imaging. Pura, si
 * prova su JVM.
 */
object AnimatedSizing {

  /** Il lato lungo di riserva quando chi costruisce il decoder non conosce lo schermo. */
  const val DEFAULT_MAX_SIDE: Int = 2_560

  /** Il tetto di riserva: 16 MP, quello di un telefono con `memoryClass` medio. */
  const val DEFAULT_MAX_PIXELS: Long = 16L * 1024 * 1024

  /** Vero se la tela supera da sola il tetto del viewer: non si anima (si mostra il primo fotogramma). */
  fun tooLargeToAnimate(width: Int, height: Int, maxPixels: Long): Boolean = width.toLong() * height > maxPixels

  /** La taglia per animare: lato lungo entro [maxSide], pixel entro un quarto di [maxPixels]; null = va bene cosi'. */
  fun animatedTarget(width: Int, height: Int, maxSide: Int, maxPixels: Long): Pair<Int, Int>? =
    target(width, height, maxSide, maxPixels / ANIMATED_FRAMES_IN_MEMORY)

  /** La taglia del fotogramma fermo: lato lungo entro [maxSide], pixel entro [maxPixels]; null = va bene cosi'. */
  fun stillTarget(width: Int, height: Int, maxSide: Int, maxPixels: Long): Pair<Int, Int>? = target(width, height, maxSide, maxPixels)

  private fun target(width: Int, height: Int, maxSide: Int, maxPixels: Long): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    var scale = 1.0
    scale = min(scale, max(maxSide, 1).toDouble() / max(width, height))
    scale = min(scale, sqrt(max(maxPixels, 1L).toDouble() / (width.toDouble() * height)))
    if (scale >= 1.0) return null
    return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
  }

  /** Come in core-imaging: un'immagine animata tiene in memoria piu' fotogrammi, quindi un quarto del tetto. */
  private const val ANIMATED_FRAMES_IN_MEMORY = 4L
}
