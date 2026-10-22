package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.OcrLine
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import dev.pampa.mycloudgallery.core.trust.data.VecItem
import kotlinx.coroutines.CancellationException

/** Gli ingressi di un media, gia' nel buffer (o preparati dal telefono): anteprima o fotogrammi, e l'HQ se serve (D12). */
internal class MediaInput(
  val kind: MediaKind,
  val source: MediaSource,
  /** `analysis` di una foto, o il corpo `frames` di un video. */
  val primary: ByteArray,
  /** Vero se gli ingressi vengono dai byte del telefono di questo dispositivo (sorgente 3/4 dei corpi). */
  val fromPhone: Boolean,
  /** L'HQ (2048 px) solo quando serve: chiamata al piu' una volta. */
  val hq: suspend () -> ByteArray?,
)

/** Com'e' andato il vettore di un media. */
internal sealed interface VectorOutcome {
  class Done(val body: VecBody) : VectorOutcome

  /** Ingresso rifiutato da [AiInputs] (formato non decodificabile, troppo grande): "non supportato". */
  data object Refused : VectorOutcome

  /** Vettore non valido due volte (E-A12): `AI_VECTOR_INVALID`, media saltato. */
  data object Invalid : VectorOutcome

  /** Il modello manca o non parte: il calcolo si ferma (`MODEL_MISSING`). */
  data object ModelMissing : VectorOutcome
}

/** Com'e' andata la lettura del testo di un media. */
internal sealed interface TextOutcome {
  class Done(val body: OcrBody) : TextOutcome

  data object Refused : TextOutcome

  /** Errore del motore: niente si salva, si riprova al giro dopo (`OCR_FAILED`). */
  data object Failed : TextOutcome

  /** La lettura e' stata spenta mentre si calcolava (E-A18): niente OCR da qui in poi. */
  data object Off : TextOutcome
}

/**
 * Il calcolo di **un** media (§2.2, righe "Calcolo" e "Controlli"; tabella "Ingressi per tipo di media"): vettori con
 * [ImageEmbedder] (una foto = un vettore; un video = un vettore per fotogramma, D11) e testo con [TextReader] (solo se la lettura e'
 * attiva qui: il lettore si raggiunge **solo** da [reader], un provider chiamato al primo uso, D41 e regola di P0).
 *
 * Controlli: 768 componenti finite e norma 1 ± 0,01 dopo il passaggio a float16; altrimenti si ripete una volta (il motore vero
 * passa alla CPU, E-A12) e al secondo errore il media e' "non valido". Il testo si pulisce con le regole del formato (`OcrText`) e
 * si tiene nei tetti del corpo `ocr` (`AiSegments.admits`): mai un corpo che gli altri rifiuterebbero.
 */
internal class MediaComputer(
  private val images: ImageEmbedder,
  private val reader: () -> TextReader,
  private val inputs: AiInputs,
  private val recipe: AiRecipe,
  private val counters: AiComputeCounters,
) {

  suspend fun vectors(input: MediaInput): VectorOutcome {
    val modelImages: List<ModelImage> = if (input.kind == MediaKind.VIDEO) {
      val frames = FramesBlob.split(input.primary) ?: return VectorOutcome.Refused
      inputs.frames(frames, recipe).take(AiLimits.MAX_VECTORS)
    } else {
      listOfNotNull(inputs.image(input.primary, recipe))
    }
    if (modelImages.isEmpty()) {
      counters.inputsRefused.incrementAndGet()
      return VectorOutcome.Refused
    }
    repeat(2) {
      val before = images.backend.value
      val vectors = try {
        images.embed(modelImages)
      } catch (e: CancellationException) {
        throw e
      } catch (e: AiEngineException) {
        when (e.error) {
          AiEngineError.MODEL_UNAVAILABLE, AiEngineError.INIT_FAILED -> return VectorOutcome.ModelMissing
          AiEngineError.BAD_INPUT -> {
            counters.inputsRefused.incrementAndGet()
            return VectorOutcome.Refused
          }
          else -> null
        }
      }
      if (before == AiBackend.GPU && images.backend.value == AiBackend.CPU) counters.gpuFallbacks.incrementAndGet()
      val body = vectors?.let { bodyOf(input, modelImages, it) }
      if (body != null) return VectorOutcome.Done(body)
    }
    counters.vectorsInvalid.incrementAndGet()
    return VectorOutcome.Invalid
  }

  private fun bodyOf(input: MediaInput, modelImages: List<ModelImage>, vectors: List<FloatArray>): VecBody? {
    if (vectors.size != modelImages.size) return null
    val dimension = SpaceCode.CURRENT.space.dimension
    val items = vectors.mapIndexed { i, v ->
      if (v.size != dimension || !v.all { it.isFinite() }) return null
      val halves = Float16.encode(v)
      if (Float16.norm(halves) !in AiLimits.MIN_NORM..AiLimits.MAX_NORM) return null
      VecItem(if (input.kind == MediaKind.VIDEO) (modelImages[i].frameMs ?: 0) else null, halves)
    }
    val source = when {
      input.kind == MediaKind.VIDEO -> VecSource.FRAMES
      input.fromPhone -> VecSource.PHONE_ORIGINAL
      else -> VecSource.ANALYSIS
    }
    val body = VecBody(SpaceCode.CURRENT, recipe.version, source, items)
    return body.takeIf { AiSegments.admits(it) }
  }

  /** Il testo del media; [readingHere] si rilegge prima di **ogni** lettura (E-A18: spenta a meta' = niente piu' OCR). */
  suspend fun text(input: MediaInput, readingHere: () -> Boolean): TextOutcome {
    if (input.kind == MediaKind.VIDEO) {
      val frames = FramesBlob.split(input.primary) ?: return TextOutcome.Refused
      val lines = ArrayList<OcrLine>()
      val seen = HashSet<String>()
      for (frame in FramesBlob.forOcr(frames, recipe.ocrFramesPerVideo)) {
        val read = read(frame.jpeg, OcrSource.FRAMES, readingHere) ?: return TextOutcome.Off
        val result = read.getOrElse { return if (it is Refusal) TextOutcome.Refused else TextOutcome.Failed }
        result.lines.forEach { if (seen.add(it.text)) lines += it }
      }
      return finish(OcrSource.FRAMES, lines)
    }
    val firstSource = if (input.fromPhone) OcrSource.PHONE_ORIGINAL else OcrSource.ANALYSIS
    // Screenshot: direttamente l'HQ (o l'originale, se e' sul telefono), D12.
    if (input.source == MediaSource.SCREENSHOT && !input.fromPhone) {
      val hq = input.hq()
      if (hq != null) {
        val read = read(hq, OcrSource.HQ, readingHere) ?: return TextOutcome.Off
        read.getOrNull()?.let { return finish(OcrSource.HQ, it.lines) }
      }
    }
    val read = read(input.primary, firstSource, readingHere) ?: return TextOutcome.Off
    val first = read.getOrElse { return if (it is Refusal) TextOutcome.Refused else TextOutcome.Failed }
    if (first.dense && !input.fromPhone) {
      // Testo fitto: seconda passata sull'HQ; se non c'e' o fallisce vale la prima.
      val hq = input.hq()
      if (hq != null) {
        val second = read(hq, OcrSource.HQ, readingHere) ?: return TextOutcome.Off
        second.getOrNull()?.let { return finish(OcrSource.HQ, it.lines) }
      }
    }
    return finish(firstSource, first.lines)
  }

  private class Refusal : Exception("BAD_INPUT")

  /** Una lettura: null = lettura spenta adesso (E-A18); un fallimento con [Refusal] = ingresso rifiutato. */
  private suspend fun read(bytes: ByteArray, source: OcrSource, readingHere: () -> Boolean): Result<OcrResult>? {
    if (!readingHere()) return null
    val image = inputs.ocr(bytes, source) ?: run {
      counters.inputsRefused.incrementAndGet()
      return Result.failure(Refusal())
    }
    return try {
      Result.success(reader().read(image))
    } catch (e: CancellationException) {
      throw e
    } catch (e: AiEngineException) {
      if (e.error == AiEngineError.TEXT_READING_OFF) return null
      if (e.error == AiEngineError.BAD_INPUT) return Result.failure(Refusal())
      counters.ocrFailed.incrementAndGet()
      Result.failure(e)
    } catch (e: Exception) {
      counters.ocrFailed.incrementAndGet()
      Result.failure(e)
    }
  }

  private fun finish(source: OcrSource, raw: List<OcrLine>): TextOutcome =
    OcrBodies.of(source, raw, recipe)?.let { TextOutcome.Done(it) } ?: TextOutcome.Failed
}
