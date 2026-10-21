package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException

/** Un media con cio' che resta da fare **qui** (gia' tolti il salvato e il fatto da altri). */
internal class Planned(val key: ContentKey, val kind: MediaKind, val source: MediaSource, val todo: Set<AiClass>) {
  val inputClass: SegmentClass get() = if (kind == MediaKind.VIDEO) SegmentClass.FRAMES else SegmentClass.ANALYSIS

  override fun toString(): String = "Planned($kind, $todo)"
}

/** Com'e' finito un media. */
internal enum class ItemEnd { COMPUTED, SKIPPED, WAITING, FAILED, MODEL_MISSING }

/** I conteggi di una tappa (solo numeri) e le chiavi finite (da togliere dal buffer). */
internal class Tally {
  var computed = 0
  var skipped = 0
  var waiting = 0
  var failed = 0
  val finished = LinkedHashSet<ContentKey>()

  fun count(end: ItemEnd, key: ContentKey) {
    when (end) {
      ItemEnd.COMPUTED -> computed++
      ItemEnd.SKIPPED -> skipped++
      ItemEnd.WAITING -> waiting++
      ItemEnd.FAILED -> failed++
      ItemEnd.MODEL_MISSING -> Unit
    }
    if (end == ItemEnd.COMPUTED || end == ItemEnd.SKIPPED || end == ItemEnd.FAILED) finished += key
  }
}

/**
 * Il lavoro su un media dentro una tappa (§2.2, righe "Ingressi", "Calcolo", "Controlli", "Salvataggio"): cosa resta da fare, da dove
 * vengono gli ingressi (buffer → scaricamento attestato → byte del telefono → "in attesa della preparazione", D10), il calcolo con
 * [MediaComputer] e il salvataggio **subito** seguito dall'outbox.
 */
internal class ItemWorker(
  private val parts: AiComputeParts,
  private val computer: MediaComputer,
  private val recipe: AiRecipe,
  private val counters: AiComputeCounters,
) {
  private val roles get() = parts.roles

  /**
   * Le classi da calcolare per [key]: quelle che mancano ([missing], gia' senza `ocr` se la lettura e' spenta, D41) meno cio' che e'
   * salvato qui con la ricetta corrente e cio' che altri hanno pubblicato ([others], D9). Cio' che e' salvato qui ma non e' fatto
   * nella share si rimette nell'outbox di [share] senza ricalcolarlo (stesso contenuto in piu' share; morte fra salvataggio e
   * outbox).
   */
  suspend fun plan(share: String?, key: ContentKey, kind: MediaKind, source: MediaSource, missing: Set<AiClass>, others: Set<AiClass>): Planned {
    val wanted = missing.filterTo(LinkedHashSet()) { it == AiClass.VEC || roles.textReadingHere.value }
    if (wanted.isEmpty()) return Planned(key, kind, source, emptySet())
    val saved = saved(key)
    if (share != null) {
      for (cls in wanted.intersect(saved) - others) restage(share, key, cls)
    }
    return Planned(key, kind, source, wanted - saved - others)
  }

  private suspend fun saved(key: ContentKey): Set<AiClass> {
    val forVec = parts.results.saved(key, recipe.version)
    val forOcr = if (recipe.ocrVersion == recipe.version) forVec else parts.results.saved(key, recipe.ocrVersion)
    return buildSet {
      if (AiClass.VEC in forVec) add(AiClass.VEC)
      if (AiClass.OCR in forOcr) add(AiClass.OCR)
    }
  }

  private suspend fun restage(share: String, key: ContentKey, cls: AiClass) {
    val entry = when (cls) {
      AiClass.VEC -> parts.localBodies.vectors(key)?.takeIf { it.recipe >= recipe.version }?.let { AiEntry.Vector(key, it) }
      AiClass.OCR -> parts.localBodies.text(key)?.takeIf { it.recipe >= recipe.ocrVersion }?.let { AiEntry.Text(key, it) }
    } ?: return
    parts.publisher.stage(share, listOf(entry))
    counters.restaged.incrementAndGet()
  }

  /**
   * Scarica nel buffer, a lotti, le anteprime che mancano ai media di [planned] (blob **attestati** della share, D10; il traffico lo
   * registra lo scaricatore, `AiTraffic` di P2). Un errore del NAS non ferma niente: quei media restano "in attesa".
   */
  suspend fun prefetch(share: String, planned: List<Planned>, budget: Duration, fetchBatch: Int) {
    val need = planned.filter { it.todo.isNotEmpty() && !has(it.key, it.inputClass) }
    for ((cls, group) in need.groupBy { it.inputClass }) {
      for (chunk in group.chunked(fetchBatch)) {
        val report = try {
          parts.fetcher.fetch(share, chunk.map { it.key }, setOf(cls), parts.buffer.asFetchSink(), budget)
        } catch (e: IOException) {
          return
        }
        if (report.stoppedEarly) return
      }
    }
  }

  private suspend fun has(key: ContentKey, cls: SegmentClass): Boolean {
    val buffer = parts.buffer
    return if (buffer is DiskAiBuffer) buffer.contains(key, cls) else buffer.get(key, cls) != null
  }

  /**
   * Il media: ingressi, vettore (prima), testo (dopo, sulla stessa anteprima, solo con la lettura attiva **adesso**), salvataggio e
   * outbox ([share] null = risultati solo locali: i media del solo telefono). [localOnly]: niente NAS nemmeno per l'HQ.
   */
  suspend fun process(share: String?, item: Planned, localOnly: Boolean): ItemEnd {
    if (item.todo.isEmpty()) return ItemEnd.SKIPPED
    val input = input(share, item, localOnly) ?: return ItemEnd.WAITING
    val key = item.key
    var did = false
    if (AiClass.VEC in item.todo) {
      when (val outcome = computer.vectors(input)) {
        is VectorOutcome.Done -> {
          parts.results.saveVectors(key, outcome.body)
          if (share != null) parts.publisher.stage(share, listOf(AiEntry.Vector(key, outcome.body)))
          did = true
        }
        VectorOutcome.Refused, VectorOutcome.Invalid -> {
          parts.unsupported.mark(key, AiClass.VEC)
          return ItemEnd.FAILED
        }
        VectorOutcome.ModelMissing -> return ItemEnd.MODEL_MISSING
      }
    }
    if (AiClass.OCR in item.todo && roles.textReadingHere.value) {
      when (val outcome = computer.text(input) { roles.textReadingHere.value }) {
        is TextOutcome.Done -> {
          parts.results.saveText(key, outcome.body)
          if (share != null) parts.publisher.stage(share, listOf(AiEntry.Text(key, outcome.body)))
          did = true
        }
        TextOutcome.Refused -> parts.unsupported.mark(key, AiClass.OCR)
        TextOutcome.Failed, TextOutcome.Off -> Unit
      }
    }
    return if (did) ItemEnd.COMPUTED else ItemEnd.SKIPPED
  }

  /** Buffer, poi i byte del telefono (preparazione locale, solo `analysis` o `frames`); altrimenti null ("in attesa"). */
  private suspend fun input(share: String?, item: Planned, localOnly: Boolean): MediaInput? {
    var fromPhone = false
    val primary = parts.buffer.get(item.key, item.inputClass) ?: preparePhone(item)?.also { fromPhone = true } ?: return null
    var hqAsked = false
    var hqBytes: ByteArray? = null
    val hq: suspend () -> ByteArray? = {
      if (!hqAsked) {
        hqAsked = true
        hqBytes = parts.buffer.get(item.key, SegmentClass.HQ)
        if (hqBytes == null && share != null && !localOnly) {
          try {
            parts.fetcher.fetch(share, listOf(item.key), setOf(SegmentClass.HQ), parts.buffer.asFetchSink(), Duration.INFINITE)
          } catch (e: IOException) {
            // Senza HQ vale la prima passata.
          }
          hqBytes = parts.buffer.get(item.key, SegmentClass.HQ)
        }
      }
      hqBytes
    }
    return MediaInput(item.kind, item.source, primary, fromPhone, hq)
  }

  /** La preparazione dai byte del telefono (D10): l'uscita va anche nel buffer, cosi' una ripresa non la rifa'. */
  private suspend fun preparePhone(item: Planned): ByteArray? {
    val preparer = parts.preparer ?: return null
    val open = try {
      parts.phone.open(item.key, item.kind)
    } catch (e: IOException) {
      return null
    }
    val ready = open as? PrepOpen.Ready ?: return null
    val result = try {
      preparer.prepare(ready.source, PrepSpec(classes = setOf(item.inputClass)))
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      null
    } finally {
      runCatching { ready.close() }
    }
    val done = result as? PrepResult.Done ?: return null
    val bytes = done.outputs.firstNotNullOfOrNull { output ->
      when {
        output is PrepOutput.Image && output.cls == SegmentClass.ANALYSIS -> output.bytes
        output is PrepOutput.Frames -> runCatching { FramesBlob.encode(output.frames) }.getOrNull()
        else -> null
      }
    } ?: return null
    parts.buffer.put(item.key, item.inputClass, bytes)
    return bytes
  }
}
