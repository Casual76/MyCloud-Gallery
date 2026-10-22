package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException

/** Un media con cio' che resta da fare **qui** (gia' tolti il salvato e il fatto da altri). */
internal class Planned(val key: ContentKey, val kind: MediaKind, val source: MediaSource, val todo: Set<AiClass>) {
  val inputClass: SegmentClass get() = if (kind == MediaKind.VIDEO) SegmentClass.FRAMES else SegmentClass.ANALYSIS

  override fun toString(): String = "Planned($kind, $todo)"
}

/** Com'e' finito un media. [RETRY] = un errore passeggero del motore (IX-7): si riprova al giro dopo, le anteprime restano. */
internal enum class ItemEnd { COMPUTED, SKIPPED, WAITING, FAILED, RETRY, MODEL_MISSING }

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
      ItemEnd.FAILED, ItemEnd.RETRY -> failed++
      ItemEnd.MODEL_MISSING -> Unit
    }
    if (end == ItemEnd.COMPUTED || end == ItemEnd.SKIPPED || end == ItemEnd.FAILED) finished += key
  }
}

/**
 * Il lavoro su un media dentro una tappa (§2.2, righe "Ingressi", "Calcolo", "Controlli", "Salvataggio"): cosa resta da fare, da dove
 * vengono gli ingressi (buffer → scaricamento attestato → byte del telefono → "in attesa della preparazione", D10), il calcolo con
 * [MediaComputer] e il salvataggio **subito**; le voci per l'outbox si raccolgono e si mettono in fila una volta per lotto (IX-6: la
 * regola M3 si chiede per il lotto, non per ogni media).
 *
 * Revisione A:
 * - **R5, provenienza**: con un buffer che la ricorda ([AiOriginBuffer]) gli ingressi di una share sono, in ordine, un blob proprio, i byte
 *   del telefono, un blob attestato **in quella share**; con risultati che la ricordano ([AiOriginResults]) un risultato nato
 *   dall'anteprima di un altro dispositivo vale (ed e' "salvato", e si ripubblica) solo per la share di quell'anteprima;
 * - **IX-7**: solo un ingresso rifiutato da [AiInputs] (o un vettore non valido due volte, E-A12) segna il media "non supportato"; un
 *   errore passeggero del motore lo lascia da fare (al piu' [MAX_TRANSIENT] volte per processo, poi vale come non valido).
 */
internal class ItemWorker(
  private val parts: AiComputeParts,
  private val computer: MediaComputer,
  private val recipe: AiRecipe,
  private val counters: AiComputeCounters,
  /** IX-7: gli errori passeggeri del motore per chiave in questo processo (li tiene il motore, non la tappa). */
  private val strikes: MutableMap<ContentKey, Int> = HashMap(),
) {
  private val roles get() = parts.roles
  private val originBuffer: AiOriginBuffer? get() = parts.buffer as? AiOriginBuffer
  private val originResults: AiOriginResults? get() = parts.results as? AiOriginResults

  /**
   * Le classi da calcolare per [key]: quelle che mancano ([missing], gia' senza `ocr` se la lettura e' spenta, D41) meno cio' che e'
   * salvato qui **per questa share** con la ricetta corrente e cio' che altri hanno pubblicato ([others], D9). Cio' che e' salvato per
   * questa share ma non e' fatto nella share va in [staged] (per l'outbox) senza ricalcolarlo (stesso contenuto in piu' share; morte fra
   * salvataggio e outbox).
   */
  suspend fun plan(
    share: String?,
    key: ContentKey,
    kind: MediaKind,
    source: MediaSource,
    missing: Set<AiClass>,
    others: Set<AiClass>,
    staged: MutableList<AiEntry>? = null,
  ): Planned {
    val wanted = missing.filterTo(LinkedHashSet()) { it == AiClass.VEC || roles.textReadingHere.value }
    if (wanted.isEmpty()) return Planned(key, kind, source, emptySet())
    val saved = saved(key, share)
    if (share != null) {
      for (cls in wanted.intersect(saved) - others) restage(share, key, cls, staged)
    }
    return Planned(key, kind, source, wanted - saved - others)
  }

  /**
   * Vero se per [key] qui non resta niente da calcolare per [share]: il vettore e' salvato con la ricetta corrente (o fatto da altri,
   * [others]) e, con la lettura attiva qui, anche il testo (AI-D1: le sue anteprime nel buffer non servono piu').
   */
  suspend fun finishedHere(key: ContentKey, share: String?, others: Set<AiClass> = emptySet()): Boolean {
    val done = saved(key, share) + others
    return AiClass.VEC in done && (AiClass.OCR in done || !roles.textReadingHere.value)
  }

  private suspend fun saved(key: ContentKey, share: String?): Set<AiClass> {
    val results = originResults
    suspend fun at(r: Int): Set<AiClass> = results?.savedFor(key, r, share) ?: parts.results.saved(key, r)
    val forVec = at(recipe.version)
    val forOcr = if (recipe.ocrVersion == recipe.version) forVec else at(recipe.ocrVersion)
    return buildSet {
      if (AiClass.VEC in forVec) add(AiClass.VEC)
      if (AiClass.OCR in forOcr) add(AiClass.OCR)
    }
  }

  private suspend fun restage(share: String, key: ContentKey, cls: AiClass, staged: MutableList<AiEntry>?) {
    val bodies = parts.localBodies as? AiLocalBodiesFor
    val entry = when (cls) {
      AiClass.VEC -> (bodies?.vectorsFor(key, share) ?: parts.localBodies.takeIf { bodies == null }?.vectors(key))
        ?.takeIf { it.recipe >= recipe.version }?.let { AiEntry.Vector(key, it) }
      AiClass.OCR -> (bodies?.textFor(key, share) ?: parts.localBodies.takeIf { bodies == null }?.text(key))
        ?.takeIf { it.recipe >= recipe.ocrVersion }?.let { AiEntry.Text(key, it) }
    } ?: return
    if (staged != null) staged += entry else parts.publisher.stage(share, listOf(entry))
    counters.restaged.incrementAndGet()
  }

  /**
   * Scarica nel buffer, a lotti, le anteprime che mancano ai media di [planned] (blob **attestati** della share, D10; il traffico lo
   * registra lo scaricatore, `AiTraffic` di P2). Un errore del NAS non ferma niente: quei media restano "in attesa".
   */
  suspend fun prefetch(share: String, planned: List<Planned>, budget: Duration, fetchBatch: Int) {
    val need = planned.filter { it.todo.isNotEmpty() && !has(it.key, it.inputClass, share) }
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

  private suspend fun has(key: ContentKey, cls: SegmentClass, share: String): Boolean {
    originBuffer?.let { return it.containsFor(key, cls, share) }
    val buffer = parts.buffer
    return if (buffer is DiskAiBuffer) buffer.contains(key, cls) else buffer.get(key, cls) != null
  }

  /**
   * Il media: ingressi, vettore (prima), testo (dopo, sulla stessa anteprima, solo con la lettura attiva **adesso**), salvataggio; le voci
   * per l'outbox vanno in [staged] ([share] null = risultati solo locali: i media del solo telefono). [localOnly]: niente NAS nemmeno per
   * l'HQ.
   */
  suspend fun process(share: String?, item: Planned, localOnly: Boolean, staged: MutableList<AiEntry>? = null): ItemEnd {
    if (item.todo.isEmpty()) return ItemEnd.SKIPPED
    val input = input(share, item, localOnly) ?: return ItemEnd.WAITING
    val key = item.key
    var did = false
    // Le voci per l'outbox: nella lista del lotto se c'e' (IX-6), altrimenti subito; mai per i media del solo telefono.
    suspend fun stage(entry: AiEntry) {
      if (share == null) return
      if (staged != null) staged += entry else parts.publisher.stage(share, listOf(entry))
    }
    if (AiClass.VEC in item.todo) {
      when (val outcome = computer.vectors(input)) {
        is VectorOutcome.Done -> {
          saveVectors(key, outcome.body, input.from)
          stage(AiEntry.Vector(key, outcome.body))
          strikes.remove(key)
          did = true
        }
        VectorOutcome.Refused, VectorOutcome.Invalid -> {
          unsupported(key, AiClass.VEC, input.from)
          return ItemEnd.FAILED
        }
        VectorOutcome.Failed -> {
          // IX-7: un guasto passeggero del motore: il media resta da fare (al piu' MAX_TRANSIENT volte, poi vale come non valido).
          counters.engineFailures.incrementAndGet()
          val n = (strikes[key] ?: 0) + 1
          strikes[key] = n
          if (n >= MAX_TRANSIENT) {
            strikes.remove(key)
            unsupported(key, AiClass.VEC, input.from)
            return ItemEnd.FAILED
          }
          return ItemEnd.RETRY
        }
        VectorOutcome.ModelMissing -> return ItemEnd.MODEL_MISSING
      }
    }
    if (AiClass.OCR in item.todo && roles.textReadingHere.value) {
      when (val outcome = computer.text(input) { roles.textReadingHere.value }) {
        is TextOutcome.Done -> {
          val from = if (outcome.body.source == OcrSource.HQ) AiInputOrigin.of(input.from, input.hqFrom()) else input.from
          saveText(key, outcome.body, from)
          stage(AiEntry.Text(key, outcome.body))
          did = true
        }
        TextOutcome.Refused -> unsupported(key, AiClass.OCR, input.from)
        TextOutcome.Failed, TextOutcome.Off -> Unit
      }
    }
    return if (did) ItemEnd.COMPUTED else ItemEnd.SKIPPED
  }

  private suspend fun saveVectors(key: ContentKey, body: VecBody, from: AiInputOrigin) {
    val results = originResults
    if (results != null) results.saveVectors(key, body, from) else parts.results.saveVectors(key, body)
  }

  private suspend fun saveText(key: ContentKey, body: OcrBody, from: AiInputOrigin) {
    val results = originResults
    if (results != null) results.saveText(key, body, from) else parts.results.saveText(key, body)
  }

  /**
   * "Non supportato" (E-A12): le anteprime del media non servono piu' (AI-D1: prima si tolgono, poi si segna; una morte in mezzo fa solo
   * riprovare il media). Con la provenienza il segno vale solo dove valeva l'ingresso (R5).
   */
  private suspend fun unsupported(key: ContentKey, cls: AiClass, from: AiInputOrigin) {
    parts.buffer.evict(listOf(key))
    val marks = parts.unsupported
    if (marks is AiUnsupportedFrom) marks.mark(key, cls, from) else marks.mark(key, cls)
  }

  /**
   * Gli ingressi (D10; R5 per la provenienza): un blob proprio nel buffer, poi i byte del telefono (preparati ora), poi un blob attestato
   * in questa share; altrimenti null ("in attesa"). L'HQ allo stesso modo, scaricato dalla share al primo bisogno.
   */
  private suspend fun input(share: String?, item: Planned, localOnly: Boolean): MediaInput? {
    val buffer = originBuffer
    var fromPhone = false
    val primary: AiBlob = if (buffer != null) {
      buffer.getFor(item.key, item.inputClass, null)
        ?: preparePhone(item)?.also { fromPhone = true }?.let { AiBlob(it, AiInputOrigin.Own) }
        ?: share?.let { buffer.getFor(item.key, item.inputClass, it) }
        ?: return null
    } else {
      parts.buffer.get(item.key, item.inputClass)?.let { AiBlob(it, AiInputOrigin.Own) }
        ?: preparePhone(item)?.also { fromPhone = true }?.let { AiBlob(it, AiInputOrigin.Own) }
        ?: return null
    }
    var hqAsked = false
    var hq: AiBlob? = null
    suspend fun hqBlob(): AiBlob? {
      if (buffer != null) return buffer.getFor(item.key, SegmentClass.HQ, share)
      return parts.buffer.get(item.key, SegmentClass.HQ)?.let { AiBlob(it, AiInputOrigin.Own) }
    }
    val loadHq: suspend () -> AiBlob? = {
      if (!hqAsked) {
        hqAsked = true
        hq = hqBlob()
        if (hq == null && share != null && !localOnly) {
          try {
            parts.fetcher.fetch(share, listOf(item.key), setOf(SegmentClass.HQ), parts.buffer.asFetchSink(), Duration.INFINITE)
          } catch (e: IOException) {
            // Senza HQ vale la prima passata.
          }
          hq = hqBlob()
        }
      }
      hq
    }
    return MediaInput(item.kind, item.source, primary.bytes, fromPhone, { loadHq()?.bytes }, primary.from) { hq?.from }
  }

  /** La preparazione dai byte del telefono (D10): l'uscita va anche nel buffer (propria), cosi' una ripresa non la rifa'. */
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

  companion object {
    /** IX-7: errori passeggeri del motore per lo stesso media in un processo, oltre i quali il media vale come "non valido". */
    const val MAX_TRANSIENT: Int = 3
  }
}
