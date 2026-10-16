package dev.pampa.mycloudgallery.core.library.similar

import android.graphics.Bitmap
import android.os.CancellationSignal
import androidx.compose.ui.unit.IntSize
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.SimilarCandidate
import dev.pampa.mycloudgallery.core.imaging.budget.ImagingBudget
import dev.pampa.mycloudgallery.core.imaging.decode.ImageFormat
import dev.pampa.mycloudgallery.core.imaging.decode.ImageFailureException
import dev.pampa.mycloudgallery.core.imaging.decode.ImageProbe
import dev.pampa.mycloudgallery.core.imaging.decode.ScreenImageDecoder
import dev.pampa.mycloudgallery.core.imaging.nas.BlobDecoder
import dev.pampa.mycloudgallery.core.imaging.nas.NasBlobScopes
import dev.pampa.mycloudgallery.core.imaging.nas.PlatformBlobDecoder
import dev.pampa.mycloudgallery.core.imaging.viewer.ImageOrigin
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Fase 5A, P15 (docs/fase5-progetto.md §2.12 "Firma visiva"): DA DOVE vengono i pixel da firmare. Foto del telefono: l'originale,
// decodificato ridotto; foto del NAS: **la miniatura dell'indice** (la stessa su ogni dispositivo), mai l'originale; video: due
// fotogrammi, da una porta che lega P7. "Nessuna lettura del NAS oltre le miniature dei candidati" (D36): questo file non nomina
// `NasMediaReader` ne' un percorso del NAS.

/** L'esito del tentativo di leggere i pixel di un candidato. */
sealed interface SignaturePixels {

  /** I fotogrammi pronti: uno per una foto, [VisualSignatures.VIDEO_FRAMES] per un video, agli istanti chiesti. */
  class Ready(val frames: List<Raster>) : SignaturePixels {
    init {
      require(frames.isNotEmpty()) { "Nessun fotogramma." }
    }
  }

  /**
   * Non adesso: la miniatura dell'indice non c'e' ancora (la Preparazione non l'ha prodotta o la replica non l'ha scaricata) o il
   * telefono non puo' ancora leggere il file. Il candidato si rimanda al giro dopo. [contentKey] e' la chiave di contenuto ridotta
   * della copia del NAS da preparare per prima (`UnitPriority`), se e' il caso.
   */
  class Later(val contentKey: Long? = null) : SignaturePixels

  /** Mai (almeno in questa vita del processo): formato che non si firma (animato, RAW), file illeggibile, nessuna sorgente. */
  data object Never : SignaturePixels
}

/** Chi sa dare i pixel di un candidato. Nessuna eccezione verso fuori: un file illeggibile e' [SignaturePixels.Never]. */
fun interface SignatureSource {
  suspend fun pixelsOf(candidate: SimilarCandidate): SignaturePixels
}

/** Decodifica l'originale di una foto del telefono ridotto, dritto. Null se non si legge. */
fun interface PhonePhotoPixels {
  suspend fun decode(ref: MediaRef.Phone): Raster?
}

/**
 * I fotogrammi di un video: la porta che lega P7 (telefono: `MediaMetadataRetriever` sul file; NAS: i fotogrammi chiave
 * dell'indice, classe `frames`, **mai il video**). [instants] sono gli istanti chiesti ([VideoInstants]); chi non ha un fotogramma
 * esattamente li' prende il piu' vicino (la Preparazione prende il fotogramma chiave piu' vicino all'istante).
 */
fun interface VideoFrames {
  suspend fun frames(candidate: SimilarCandidate, instants: VideoInstants): SignaturePixels
}

/** Senza fotogrammi dei video (finche' P7 non lega la porta): i video non si firmano e restano celle a se'. */
object NoVideoFrames : VideoFrames {
  override suspend fun frames(candidate: SimilarCandidate, instants: VideoInstants): SignaturePixels = SignaturePixels.Never
}

/**
 * I due istanti in cui si guarda un video (§2.12: "due fotogrammi agli stessi istanti"), funzione della sola durata. Sono quelli che la
 * Preparazione usa per la copertina e per il fotogramma chiave centrale (`FrameTimes`): cosi' sul NAS ci sono gia' (classe `frames`).
 * La copertina sta al piu' a 1 s (il primo fotogramma e' spesso nero) o a meta' di un video piu' corto; il secondo e' a meta' video.
 * Due copie dello stesso video hanno durate a meno di 0,5 s: i loro istanti differiscono di meno di 0,25 s.
 */
class VideoInstants private constructor(val atMs: List<Long>) {
  override fun toString(): String = "VideoInstants(${atMs.size})"

  companion object {
    private const val COVER_MAX_MS = 1_000L
    private const val MIN_GAP_MS = 250L

    /** Gli istanti per un video lungo [durationMs]. Un video senza durata si guarda solo all'inizio (e non ha due fotogrammi). */
    fun of(durationMs: Long?): VideoInstants {
      val duration = durationMs ?: 0L
      if (duration <= 0L) return VideoInstants(listOf(0L))
      val cover = minOf(COVER_MAX_MS, duration / 2)
      val middle = (duration / 2).coerceIn(0L, duration - 1)
      return VideoInstants(if (middle - cover >= MIN_GAP_MS) listOf(cover, middle) else listOf(cover))
    }
  }
}

// --- Le sorgenti vere ------------------------------------------------------------------------------------------------------

/** Dalla bitmap alla firma. Le bitmap hardware (non leggibili) si copiano prima in software. */
object BitmapRasters {

  fun of(bitmap: Bitmap): Raster {
    val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: error("Copia non riuscita.") else bitmap
    val pixels = IntArray(soft.width * soft.height)
    soft.getPixels(pixels, 0, soft.width, 0, 0, soft.width, soft.height)
    if (soft !== bitmap) soft.recycle()
    return Raster.of(bitmap.width, bitmap.height, pixels)
  }
}

/**
 * L'originale di una foto del telefono, letto dall'[ImageOrigin] (il `ContentResolver`), decodificato **ridotto** ([SIDE] px sul
 * lato lungo, `ScreenImageDecoder`: campionato dal codec, orientamento sempre dritto, mai l'intera bitmap di un JPEG da 200 MP) e
 * convertito in sRGB. Niente posizione GPS: la firma non la usa e non serve `setRequireOriginal`.
 */
class ImageOriginPhonePixels(
  private val origin: ImageOrigin,
  private val budget: ImagingBudget,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PhonePhotoPixels {

  override suspend fun decode(ref: MediaRef.Phone): Raster? = withContext(dispatcher) {
    val bytes = origin.openLocal(ref) ?: return@withContext null
    try {
      val facts = ImageProbe.probe(bytes)
      // Animati e RAW non si firmano: un GIF non ha "uno scatto" e un DNG si decodifica piano e a lungo.
      if (facts.animated != null || facts.format == ImageFormat.TIFF_RAW) return@withContext null
      val decoded = ScreenImageDecoder.decode(bytes, facts, IntSize(SIDE, SIDE), budget, CancellationSignal())
      try {
        BitmapRasters.of(decoded.bitmap)
      } finally {
        decoded.bitmap.recycle()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: ImageFailureException) {
      null
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    } catch (_: RuntimeException) {
      null
    } catch (_: OutOfMemoryError) {
      null
    } finally {
      bytes.close()
    }
  }

  companion object {
    /** Il lato lungo a cui si decodifica: 8 pixel per cella della firma sulle foto in orizzontale, rumore della ricompressione annullato. */
    const val SIDE: Int = 512
  }
}

/**
 * La miniatura **dell'indice** di una foto del NAS: la classe `THUMB_M` (lato corto 400) e, se manca, `THUMB_S` (200), dalla replica
 * locale. Niente miniatura provvisoria (e' dell'EXIF del file, locale e diversa da dispositivo a dispositivo), niente originale: se la
 * replica non ha la miniatura il candidato aspetta ([SignaturePixels.Later] con la chiave da preparare per prima).
 */
class NasThumbnailPixels(
  private val blobs: ReplicaBlobs,
  private val scopes: NasBlobScopes? = null,
  private val decoder: BlobDecoder = PlatformBlobDecoder,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  suspend fun thumbnail(ref: MediaRef.Nas): SignaturePixels {
    val scope = try {
      scopes?.scopeOf(ref)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    return withContext(dispatcher) {
      for (cls in CLASSES) {
        val location = (if (scope == null) blobs.locate(ref.contentKey, cls) else blobs.locate(ref.contentKey, cls, scope)) ?: continue
        val bytes = try {
          blobs.read(location)
        } catch (_: IOException) {
          // Il pacco non c'e' piu' (replica azzerata o ricostruita): come se il blob non ci fosse.
          continue
        }
        val bitmap = try {
          decoder.decode(bytes, SIDE, CancellationSignal())
        } catch (e: CancellationException) {
          throw e
        } catch (_: RuntimeException) {
          null
        } ?: continue
        return@withContext try {
          SignaturePixels.Ready(listOf(BitmapRasters.of(bitmap)))
        } finally {
          bitmap.recycle()
        }
      }
      GalleryLog.d(TAG) { "miniatura dell'indice assente" }
      SignaturePixels.Later(ref.contentKey)
    }
  }

  companion object {
    private const val TAG = "SimilarCopies"

    /** Il lato lungo massimo della decodifica: le miniature dell'indice sono piu' piccole (400 sul lato corto), non si ingrandiscono. */
    const val SIDE: Int = 640

    /** Prima la M, poi la S (il livello Risparmio ha solo la S). */
    private val CLASSES = listOf(SegmentClass.THUMB_M, SegmentClass.THUMB_S)
  }
}

/**
 * La sorgente vera: la foto dal telefono se il file c'e' e si legge, altrimenti la miniatura dell'indice della copia del NAS; il
 * video dalla porta [video]. Le posizioni (`_ID` del telefono, copia del NAS) le dice il catalogo.
 */
class CatalogSignatureSource(
  private val catalog: MediaCatalog,
  private val phone: PhonePhotoPixels,
  private val nas: NasThumbnailPixels,
  private val video: VideoFrames = NoVideoFrames,
) : SignatureSource {

  override suspend fun pixelsOf(candidate: SimilarCandidate): SignaturePixels {
    if (candidate.isVideo) return video.frames(candidate, VideoInstants.of(candidate.durationMs))
    val id = candidate.mediaId
    val phoneRef = catalog.phoneRefs(listOf(id))[id]
    if (phoneRef != null) {
      phone.decode(phoneRef)?.let { return SignaturePixels.Ready(listOf(it)) }
    }
    val nasRef = catalog.nasRefs(listOf(id))[id]
    if (nasRef != null) return nas.thumbnail(nasRef)
    // Il file del telefono non si e' letto e non c'e' una copia sul NAS: se il telefono l'ha, il file e' illeggibile; altrimenti non c'e'
    // una posizione leggibile adesso (`_ID` non ancora riletto, E-P22): si riprova.
    return if (phoneRef != null) SignaturePixels.Never else SignaturePixels.Later()
  }
}
