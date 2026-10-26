package dev.pampa.mycloudgallery.core.ai.noop

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiComputeRun
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AiRunResult
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.contract.CutoutImage
import dev.pampa.mycloudgallery.core.ai.contract.CutoutResult
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.NormalizedPoint
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.ai.contract.TextVector
import dev.pampa.mycloudgallery.core.ai.contract.WarmUpResult
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I null-object dei motori e del calcolo (Fase 6, P0): falliscono CHIUSI. Nessun modello, nessun vettore, nessun OCR (la lettura del
// testo resta spenta: ML Kit non parte, D41), nessun ritaglio, nessun calcolo. La ricerca resta utilizzabile con filtri e testo (D35).

object NoTextEmbedder : TextEmbedder {
  override suspend fun warmUp(): WarmUpResult = WarmUpResult.Failed(AiEngineError.MODEL_UNAVAILABLE)

  override suspend fun embed(text: String): TextVector = throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)

  override suspend fun release() = Unit
}

object NoImageEmbedder : ImageEmbedder {
  override val backend: StateFlow<AiBackend> = MutableStateFlow(AiBackend.CPU)

  override suspend fun embed(images: List<ModelImage>): List<FloatArray> = throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)

  override suspend fun release() = Unit
}

/** Il lettore spento: ML Kit non si tocca. */
object OffTextReader : TextReader {
  override suspend fun read(image: OcrImage): OcrResult = throw AiEngineException(AiEngineError.TEXT_READING_OFF)
}

object NoSubjectCutter : SubjectCutter {
  override suspend fun cut(image: CutoutImage, point: NormalizedPoint): CutoutResult = CutoutResult.Failed(AiEngineError.MODEL_UNAVAILABLE)

  override suspend fun release() = Unit
}

object NoAiComputeRun : AiComputeRun {
  override suspend fun run(scope: AiScope, budget: Duration): AiRunResult =
    AiRunResult(computed = 0, skipped = 0, waitingForPreparation = 0, failed = 0, stoppedBy = AiStop.MODEL_MISSING, backend = null)
}

/** Nessun buffer: tutto rifiutato (il calcolo lavora solo su cio' che arriva dal NAS al momento). */
object NoAiBuffer : AiBuffer {
  override suspend fun put(key: ContentKey, cls: SegmentClass, bytes: ByteArray): Boolean = false

  override suspend fun get(key: ContentKey, cls: SegmentClass): ByteArray? = null

  override suspend fun evict(done: Collection<ContentKey>) = Unit

  override val usedBytes: StateFlow<Long> = MutableStateFlow(0L)
}

/** La lettura del testo sempre spenta: nessun consenso la accende. */
object OffTextReadingSwitch : TextReadingSwitch {
  override val state: StateFlow<TextReadingState> = MutableStateFlow(TextReadingState.Off)

  override val excludedHere: StateFlow<Boolean> = MutableStateFlow(false)

  override val syncPending: StateFlow<Boolean> = MutableStateFlow(false)

  override suspend fun enableForAccount(consent: TextReadingConsent) = Unit

  override suspend fun excludeThisDevice() = Unit

  override suspend fun includeThisDevice() = Unit

  override suspend fun disable() = Unit
}
