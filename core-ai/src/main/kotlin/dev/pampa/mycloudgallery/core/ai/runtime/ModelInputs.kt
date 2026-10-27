package dev.pampa.mycloudgallery.core.ai.runtime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.model.OcrSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * La PREPARAZIONE DEGLI INGRESSI dei motori (docs/fase6-progetto.md §1.2 "Ingressi", D5, D11, §4.3 "Ingressi difesi"): l'unico punto in
 * cui [ModelImage] e [OcrImage] si costruiscono (hanno il costruttore `internal`). **Mai byte arrivati dal NAS o da un'altra app al
 * decodificatore nativo dei motori**: ogni immagine passa da qui, e qui
 *
 * 1. si controlla la dimensione dei byte ([MAX_INPUT_BYTES]);
 * 2. si legge l'intestazione con [ImageHeaders] **senza decodificare** e si rifiuta tutto cio' che e' ostile o fuori dai tetti di
 *    `ImageBounds` (formato, lunghezze che mentono, animazioni, dimensioni gigantesche: vedi [ImageHeaders]);
 * 3. solo allora si decodifica con `ImageDecoder` (il decodificatore della piattaforma, gia' difeso dalla Fase 4), **gia' ridotto** alla
 *    taglia dell'ingresso e controllando che le dimensioni che la piattaforma legge siano quelle dichiarate;
 * 4. si compone sul bianco l'eventuale trasparenza (un JPEG non ha il canale alfa: senza questo diventerebbe nero) e si ricodifica in
 *    JPEG: i motori ricevono sempre un JPEG baseline nostro, senza metadati, ICC ne' altro (`stb_image` di LiteRT-LM e ML Kit non
 *    vedono mai i byte originali).
 *
 * Tutto e' bloccante (decodifica e ricodifica): da un thread di lavoro, mai dal principale. Un ingresso che non passa da' `null` (o
 * viene saltato in [fromFrames]): non e' un errore del motore ma un file da non usare (`BAD_INPUT`).
 */
object ModelInputs {

  /**
   * Il tetto dei byte di un ingresso. I blob dell'indice stanno sotto 1 MiB (`TrustLimits.MAX_BLOB_BYTES`) e i fotogrammi di un
   * video sotto 2 MiB in tutto; per le anteprime preparate in locale c'e' margine.
   */
  const val MAX_INPUT_BYTES: Int = 4 * 1024 * 1024

  /** Al piu' 9 fotogrammi per video (D11: la copertina e fino a 8 fotogrammi). */
  const val MAX_FRAMES: Int = 9

  /** Il lato lungo massimo di un'immagine per l'OCR: l'HQ (2048 px). Piu' grande si riduce, mai si ingrandisce. */
  const val OCR_MAX_LONG_SIDE: Int = 2048

  /** Qualita' del JPEG che si passa all'OCR: i bordi delle lettere contano piu' del peso. */
  private const val OCR_JPEG_QUALITY = 95

  /**
   * Quante volte si e' arrivati al decodificatore della piattaforma. Serve alla prova "gli ingressi ostili non arrivano mai alla
   * decodifica" (`ModelInputsDeviceTest`): un file rifiutato dall'intestazione non lo fa salire.
   */
  internal val decodeAttempts: AtomicInteger = AtomicInteger()

  /**
   * L'anteprima di analisi (JPEG, 1024 px) per il modello: ridotta a `recipe.longSide` sul lato lungo e ricodificata in JPEG alla
   * qualita' della ricetta (768 px, q95). Null se l'ingresso non e' un JPEG ammesso o non si decodifica (E-A: contato "non supportato").
   */
  fun fromAnalysis(jpeg: ByteArray, recipe: AiRecipe = AiRecipe.CURRENT): ModelImage? =
    prepare(jpeg, JPEG_ONLY, recipe.longSide, recipe.jpegQuality)?.let { ModelImage(it.jpeg, it.width, it.height) }

  /**
   * I fotogrammi di un video (D11): al piu' [MAX_FRAMES] validi, nell'ordine dato (la copertina per prima), ognuno come
   * [fromAnalysis] con il suo istante in `frameMs`. Un fotogramma che non passa si **salta** (gli altri valgono: ne prende uno dei
   * successivi al suo posto, ma si esaminano al piu' il doppio del tetto, cosi' una lista di scarti non costa troppo): chi calcola
   * tiene i vettori per `frameMs`, non per posizione.
   */
  fun fromFrames(frames: List<VideoFrame>, recipe: AiRecipe = AiRecipe.CURRENT): List<ModelImage> =
    frames.asSequence().take(2 * MAX_FRAMES).mapNotNull { frame ->
      prepare(frame.jpeg, JPEG_ONLY, recipe.longSide, recipe.jpegQuality)?.let { ModelImage(it.jpeg, it.width, it.height, frame.atMs) }
    }.take(MAX_FRAMES).toList()

  /**
   * L'immagine per l'OCR (D12): l'anteprima di analisi (JPEG, 1024 px) o l'HQ (WebP, 2048 px), **alla stessa risoluzione** (il testo
   * piccolo si perde se si riduce), ridotta solo oltre [OCR_MAX_LONG_SIDE], con la trasparenza composta sul bianco. [source] dice da
   * dove viene (finisce nel corpo `ocr`).
   */
  fun forOcr(bytes: ByteArray, source: OcrSource): OcrImage? =
    prepare(bytes, JPEG_OR_WEBP, OCR_MAX_LONG_SIDE, OCR_JPEG_QUALITY)?.let { OcrImage(it.jpeg, it.width, it.height, source) }

  /**
   * Fase 8 (D17, P2): l'immagine per i motori dei volti. Stessa catena degli altri ingressi (tetto dei byte, intestazione letta **senza
   * decodificare**, `ImageBounds`, decodifica gia' ridotta della piattaforma, trasparenza composta sul bianco), con due differenze: ammette
   * JPEG e WebP (l'anteprima di analisi e' un JPEG, l'HQ un WebP, i fotogrammi dei video JPEG) e consegna i **pixel** (ARGB_8888 in sRGB,
   * gia' raddrizzati dall'orientamento EXIF) invece di ricodificarli: il rilevatore e l'allineamento lavorano sui pixel. [maxSide] e' il
   * lato lungo massimo (1024 per l'anteprima, 2048 per l'HQ; oltre [FaceEngineLimits.HQ_SIDE] si riduce comunque a quello), mai si ingrandisce.
   * [frame] e' il fotogramma (0 per una foto). Null per un ingresso da non usare: non e' un errore del motore ma un file da non analizzare.
   */
  fun forFaces(bytes: ByteArray, maxSide: Int, frame: Int = 0): FaceImage? {
    if (frame !in 0..FaceEngineLimits.MAX_FRAME || maxSide < 1) return null
    if (bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES) return null
    val header = ImageHeaders.read(bytes) ?: return null
    if (header.format !in JPEG_OR_WEBP) return null
    val decoded = decode(bytes, header, minOf(maxSide, FaceEngineLimits.HQ_SIDE), srgb = true) ?: return null
    var flat: Bitmap? = null
    try {
      flat = flattenOnWhite(decoded)
      val width = flat.width
      val height = flat.height
      if (width < 1 || height < 1 || maxOf(width, height) > FaceEngineLimits.HQ_SIDE) return null
      val pixels = IntArray(width * height)
      flat.getPixels(pixels, 0, width, 0, 0, width, height)
      return FaceImage(pixels, width, height, frame)
    } catch (_: OutOfMemoryError) {
      return null
    } catch (_: RuntimeException) {
      return null
    } finally {
      if (flat != null && flat !== decoded) flat.recycle()
      decoded.recycle()
    }
  }

  // --- Decodifica difesa ----------------------------------------------------------------------------------------------------

  private val JPEG_ONLY = setOf(InputFormat.JPEG)
  private val JPEG_OR_WEBP = setOf(InputFormat.JPEG, InputFormat.WEBP)

  private class Prepared(val jpeg: ByteArray, val width: Int, val height: Int)

  /** L'intestazione dichiarata non e' quella che la piattaforma legge: un file che mente. */
  private class Rejected : RuntimeException()

  private fun prepare(bytes: ByteArray, allowed: Set<InputFormat>, maxLongSide: Int, quality: Int): Prepared? {
    if (bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES) return null
    val header = ImageHeaders.read(bytes) ?: return null
    if (header.format !in allowed) return null
    val decoded = decode(bytes, header, maxLongSide) ?: return null
    var flat: Bitmap? = null
    try {
      flat = flattenOnWhite(decoded)
      val out = ByteArrayOutputStream(128 * 1024)
      if (!flat.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
      return Prepared(out.toByteArray(), flat.width, flat.height)
    } catch (_: OutOfMemoryError) {
      return null
    } finally {
      if (flat != null && flat !== decoded) flat.recycle()
      decoded.recycle()
    }
  }

  private fun decode(bytes: ByteArray, header: ImageHeader, maxLongSide: Int, srgb: Boolean = false): Bitmap? =
    try {
      decodeAttempts.incrementAndGet()
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        // Dopo l'orientamento EXIF le due misure possono essere scambiate; qualunque altra differenza e' un file che mente.
        val sameAsDeclared = (w == header.width && h == header.height) || (w == header.height && h == header.width)
        if (!sameAsDeclared) throw Rejected()
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        // I volti: pixel in sRGB qualunque sia il profilo del file (un'immagine a gamma larga non sposta i colori che YuNet e SFace hanno visto).
        if (srgb) decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val longSide = max(w, h)
        if (longSide > maxLongSide) {
          val scale = maxLongSide.toDouble() / longSide
          decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
        }
      }
    } catch (_: Rejected) {
      null
    } catch (_: IOException) {
      null // DecodeException (formato non riconosciuto, dati corrotti o incompleti) e' una IOException
    } catch (_: RuntimeException) {
      null
    } catch (_: OutOfMemoryError) {
      null
    }

  /** Una bitmap con trasparenza si compone sul bianco; senza, e' la stessa. */
  private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    return out
  }
}
