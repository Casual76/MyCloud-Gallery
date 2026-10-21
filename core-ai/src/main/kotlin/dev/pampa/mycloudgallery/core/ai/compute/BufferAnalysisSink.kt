package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.index.ai.AiBufferSink
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.prepare.contract.AnalysisSink
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlinx.coroutines.CancellationException

/**
 * La porta `AnalysisSink` di core-prepare sopra il buffer (D10; §1.3 "core-prepare"): sugli indicizzatori la preparazione della Fase
 * 4 (e quella del backup della Fase 5) consegna qui `analysis` e `frames` appena prodotti, invece di buttarli (R2 della 4A). Cosi'
 * "se e' il telefono che ha caricato, l'anteprima e' gia' nel suo buffer" (§2.2) e con F4-D31 acceso non si rileggono gli originali.
 *
 * - Solo `analysis` (immagine) e `frames` (riscritti come corpo `frames`, [FramesBlob]); le altre classi si ignorano.
 * - Solo se questo dispositivo e' Indicizzatore **adesso** ([indexer]): spento il ruolo, niente si accumula.
 * - **Mai** blocca ne' fa fallire la preparazione: un buffer pieno o sotto 1 GiB rifiuta in silenzio, un errore si ingoia (solo
 *   l'annullamento della coroutine passa).
 */
class BufferAnalysisSink(
  private val buffer: AiBuffer,
  private val indexer: () -> Boolean,
) : AnalysisSink {

  /** Blob messi nel buffer e rifiutati (pieno, poco spazio, errore): solo numeri. */
  @Volatile var delivered: Int = 0
    private set

  @Volatile var refused: Int = 0
    private set

  override suspend fun deliver(contentKey: ContentKey, outputs: List<PrepOutput>) {
    if (!indexer()) return
    for (output in outputs) {
      val bytes = when {
        output is PrepOutput.Image && output.cls == SegmentClass.ANALYSIS -> output.bytes
        output is PrepOutput.Frames -> runCatching { FramesBlob.encode(output.frames) }.getOrNull()
        else -> continue
      }
      val ok = try {
        bytes != null && buffer.put(contentKey, output.cls, bytes)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        false
      }
      if (ok) delivered++ else refused++
    }
  }
}

/** Il buffer visto da core-index (`AiBufferSink`): dove lo scaricamento delle anteprime mette i blob attestati. */
fun AiBuffer.asFetchSink(): AiBufferSink = AiBufferSink { key, cls, bytes -> put(key, cls, bytes) }
