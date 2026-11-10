package dev.pampa.mycloudgallery.core.ai.testing

import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidates
import dev.pampa.mycloudgallery.core.ai.contract.AiComputeRun
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.ai.contract.AiRoles
import dev.pampa.mycloudgallery.core.ai.contract.AiRunResult
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.index.ai.AiBufferSink
import dev.pampa.mycloudgallery.core.index.ai.AiDoneClasses
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.ai.AiIndexPublisher
import dev.pampa.mycloudgallery.core.index.ai.AnalysisFetcher
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiLimits
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import dev.pampa.mycloudgallery.core.trust.data.VecItem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * L'ESECUTORE DI RIFERIMENTO del calcolo AI (docs/fase6-progetto.md §2.2, §4.1, §5.2): piccolo, leggibile, senza prenotazioni ne'
 * unita' di lavoro, sugli stessi contratti del calcolo vero di P5 e con i motori finti. P9 (valutazione) e P10 (prova delle
 * interruzioni) partono da qui e passano a quello vero quando P5 consegna; il vero deve rispettare gli stessi invarianti.
 *
 * Cosa garantisce (e cosa P5 deve garantire):
 * - **mai senza ruolo**: con Indicizzatore spento non tocca niente ([AiStop.ROLE_OFF], E-A13);
 * - **salvare prima di pubblicare**: ogni risultato va in [AiResults] e solo DOPO al pubblicatore; dopo una morte del processo i
 *   media gia' salvati non si rifanno (D22: [AiResults.saved]);
 * - **una volta per tutta la famiglia**: salta le classi che un altro dispositivo ha gia' pubblicato ([AiDoneClasses], D9);
 * - **OCR solo con la lettura attiva qui** ([AiRoles.textReadingHere], D41): spenta, il lettore non si chiama mai;
 * - **ingressi solo dal buffer**: le anteprime mancanti si chiedono ad [AnalysisFetcher] (blob attestati); senza, il media resta "in
 *   attesa della preparazione" (D10, E-A15); i media del solo telefono si calcolano dal buffer e restano locali (niente pubblicazione);
 * - **vettori validi**: finiti e normalizzati, altrimenti si riprova una volta e poi il media e' fallito (`AI_VECTOR_INVALID`);
 * - **tempo**: si ferma al media dopo la scadenza di [budget] (orologio monotono [nanos], mai l'ora del telefono).
 */
class FakeAiCompute(
  private val roles: AiRoles,
  private val candidates: AiCandidates,
  private val buffer: AiBuffer,
  private val images: ImageEmbedder,
  private val reader: TextReader,
  private val results: AiResults,
  private val publisher: AiIndexPublisher,
  private val doneByOthers: AiDoneClasses,
  private val fetcher: AnalysisFetcher,
  private val recipe: AiRecipe = AiRecipe.CURRENT,
  private val batch: Int = 200,
  private val nanos: () -> Long = { System.nanoTime() },
) : AiComputeRun {

  override suspend fun run(scope: AiScope, budget: Duration): AiRunResult {
    if (!roles.indexer.value) return result(stop = AiStop.ROLE_OFF)
    val start = nanos()
    val share = (scope as? AiScope.Share)?.share
    val list = when (scope) {
      is AiScope.Share -> candidates.forShare(scope.share, recipe.version, batch)
      AiScope.PhoneOnly -> candidates.phoneOnly(recipe.version, batch)
    }
    val others = if (share != null) doneByOthers.done(share, list.map { it.contentKey }, recipe.version) else emptyMap()
    if (share != null) {
      val missing = list.filter { needsImage(it) && buffer.get(it.contentKey, inputClass(it)) == null }
      for ((cls, group) in missing.groupBy { inputClass(it) }) {
        fetcher.fetch(share, group.map { it.contentKey }, setOf(cls), AiBufferSink { k, c, b -> buffer.put(k, c, b) }, budget)
      }
    }

    var computed = 0
    var skipped = 0
    var waiting = 0
    var failed = 0
    val done = mutableListOf<ContentKey>()
    for (candidate in list) {
      if ((nanos() - start).nanoseconds >= budget) return result(computed, skipped, waiting, failed, AiStop.BUDGET).also { flush(share, done) }
      if (!roles.indexer.value) return result(computed, skipped, waiting, failed, AiStop.ROLE_OFF).also { flush(share, done) }
      val key = candidate.contentKey
      val readingHere = roles.textReadingHere.value
      val todo = candidate.missing
        .filter { it == AiClass.VEC || readingHere }
        .toSet() - results.saved(key, recipe.version) - others[key].orEmpty()
      if (todo.isEmpty()) {
        skipped++
        done += key
        continue
      }
      val input = buffer.get(key, inputClass(candidate))
      if (input == null) {
        waiting++
        continue
      }
      var ok = true
      if (AiClass.VEC in todo) {
        val body = vectorBody(candidate, input)
        if (body == null) {
          ok = false
          failed++
        } else {
          results.saveVectors(key, body)
          if (share != null) publisher.stage(share, listOf(AiEntry.Vector(key, body)))
        }
      }
      if (ok && AiClass.OCR in todo) {
        val source = if (candidate.kind == MediaKind.VIDEO) OcrSource.FRAMES else OcrSource.ANALYSIS
        val read = reader.read(OcrImage(input, 1024, 768, source))
        val body = OcrBody(OcrEngineCode.MLKIT_V2_LATIN, recipe.ocrVersion, source, read.lines.take(AiLimits.MAX_OCR_LINES))
        results.saveText(key, body)
        if (share != null) publisher.stage(share, listOf(AiEntry.Text(key, body)))
      }
      if (ok) {
        computed++
        done += key
      }
    }
    flush(share, done)
    return result(computed, skipped, waiting, failed, null)
  }

  private suspend fun flush(share: String?, done: List<ContentKey>) {
    if (share != null) publisher.flush(share, FlushReason.UNIT_FINISHED)
    buffer.evict(done)
  }

  private fun needsImage(candidate: AiCandidate): Boolean = candidate.missing.isNotEmpty()

  private fun inputClass(candidate: AiCandidate): SegmentClass =
    if (candidate.kind == MediaKind.VIDEO) SegmentClass.FRAMES else SegmentClass.ANALYSIS

  /** Un vettore valido o null: una seconda prova se il primo non e' finito o normalizzato (E-A12). */
  private suspend fun vectorBody(candidate: AiCandidate, input: ByteArray): VecBody? {
    repeat(2) {
      val vector = try {
        images.embed(listOf(ModelImage(input, recipe.longSide, recipe.longSide))).single()
      } catch (e: AiEngineException) {
        return@repeat
      }
      val halves = Float16.encode(vector)
      val norm = Float16.norm(halves)
      if (vector.all { it.isFinite() } && norm in AiLimits.MIN_NORM..AiLimits.MAX_NORM) {
        val source = when {
          candidate.kind == MediaKind.VIDEO -> VecSource.FRAMES
          else -> VecSource.ANALYSIS
        }
        val item = VecItem(if (source == VecSource.FRAMES) 0 else null, halves)
        return VecBody(SpaceCode.CURRENT, recipe.version, source, listOf(item))
      }
    }
    return null
  }

  private fun result(computed: Int = 0, skipped: Int = 0, waiting: Int = 0, failed: Int = 0, stop: AiStop?) =
    AiRunResult(computed, skipped, waiting, failed, stop, images.backend.value)
}
