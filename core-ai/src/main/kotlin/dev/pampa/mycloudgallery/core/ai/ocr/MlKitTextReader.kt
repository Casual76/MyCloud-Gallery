package dev.pampa.mycloudgallery.core.ai.ocr

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Un lettore che tiene risorse native e si puo' rilasciare (allo spegnimento della lettura, E-A18). */
interface ReleasableTextReader : TextReader {
  /** Chiude il riconoscitore. Idempotente; una `read` dopo lo riapre (se il cancello lo permette). */
  fun release()
}

/**
 * Il motore OCR (docs/fase6-progetto.md D12, D13, D43): **ML Kit Text Recognition v2, script latino, versione integrata**
 * (`com.google.mlkit:text-recognition`, modello nell'APK, niente Play Services). Esiste solo dietro il cancello della lettura del
 * testo: [MlKitBootstrap] avvia la libreria alla prima [read] e solo se la lettura e' attiva su questo dispositivo; da spenta, [read]
 * lancia `TEXT_READING_OFF` senza toccare ML Kit. Chi lo costruisce (`GatedTextReader`, P11) lo fa solo con la lettura attiva, cosi'
 * nessuna classe di questo file si carica da spenti.
 *
 * - **Ingresso**: un [OcrImage] di `ModelInputs.forOcr` (JPEG nostro, 1024 o 2048 px, trasparenza sul bianco); qui si decodifica in
 *   bitmap e si passa a ML Kit con rotazione 0 (le immagini sono gia' raddrizzate).
 * - **Uscita**: le righe del formato (riquadro, inclinazione, estremi delle parole: [OcrLayout]) e `dense` ([OcrDensity]); zero
 *   righe = "analizzata, nessun testo" (un risultato, non un errore).
 * - **Errori**: ogni guasto di ML Kit e' `AiEngineException` (`INIT_FAILED` se la libreria non parte, `BAD_INPUT` per l'immagine) e
 *   `OCR_FAILED` alla Diagnostica; mai il testo letto nei log (D40).
 *
 * Una lettura alla volta (ML Kit ha un esecutore suo); la cancellazione del chiamante lascia finire la lettura e butta il risultato.
 */
class MlKitTextReader(
  private val bootstrap: MlKitBootstrap,
  private val diag: (DiagCode) -> Unit = {},
) : ReleasableTextReader {

  private val lock = Any()
  private var recognizer: TextRecognizer? = null

  override suspend fun read(image: OcrImage): OcrResult {
    bootstrap.ensureInitialized()
    val bitmap = decode(image.jpeg) ?: run {
      diag(AiDiag.OCR_FAILED)
      throw AiEngineException(AiEngineError.BAD_INPUT)
    }
    try {
      val recognized = try {
        recognizerLocked().process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
      } catch (e: AiEngineException) {
        throw e
      } catch (t: Throwable) {
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        diag(AiDiag.OCR_FAILED)
        throw AiEngineException(if (t is OutOfMemoryError) AiEngineError.OUT_OF_MEMORY else AiEngineError.INIT_FAILED, t)
      }
      val layout = OcrLayout.convert(rawLines(recognized), bitmap.width, bitmap.height)
      return OcrResult(layout.lines, dense = OcrDensity.isDense(layout.textHeightsPx))
    } finally {
      bitmap.recycle()
    }
  }

  override fun release() {
    val old = synchronized(lock) { recognizer.also { recognizer = null } } ?: return
    try {
      old.close()
    } catch (_: RuntimeException) {
      // gia' chiuso o libreria in disordine: niente da fare
    }
  }

  private fun recognizerLocked(): TextRecognizer = synchronized(lock) {
    recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
  }

  /** Il JPEG (nostro, gia' passato da `ModelInputs`) come bitmap software; null se non si decodifica. */
  private fun decode(jpeg: ByteArray): Bitmap? =
    try {
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg))) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    } catch (_: IOException) {
      null
    } catch (_: RuntimeException) {
      null
    } catch (_: OutOfMemoryError) {
      null
    }

  private fun rawLines(text: Text): List<RawLine> =
    text.textBlocks.flatMap { block ->
      block.lines.map { line ->
        RawLine(
          text = line.text,
          corners = line.cornerPoints?.map { RawPoint(it.x.toFloat(), it.y.toFloat()) }.orEmpty(),
          box = line.boundingBox?.let { RawBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) },
          angleDegrees = line.angle,
          words = line.elements.map { element ->
            RawWord(element.text, element.cornerPoints?.map { RawPoint(it.x.toFloat(), it.y.toFloat()) }.orEmpty())
          },
        )
      }
    }

  /** Il risultato di un `Task` di Play Services senza la libreria delle coroutines per i Task (non e' fra le dipendenze). */
  private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
  }
}
