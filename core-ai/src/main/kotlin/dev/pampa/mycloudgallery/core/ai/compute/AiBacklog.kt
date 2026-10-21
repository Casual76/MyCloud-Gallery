package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidates
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AiRoles
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.rules.estimate.AiWork
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass

/**
 * Il backlog VERO del calcolo AI per le stime (§2.8, D15; `BacklogFacts.analyze` di core-rules): conta, con le **stesse porte** del
 * calcolo ([AiCandidates], [AiRoles], [AiBuffer]), i media che aspettano su questo dispositivo, per tipo e per classe. :app (P11) lo
 * mette in `BacklogFactsReader`; la pagina Ricerca e modelli ne ricava "Analizzare 18.000 media richiede circa 2 notti"
 * (`AiComputeCosts.nights`, con la misura del dispositivo al posto di `aiItemsPerSecond`).
 *
 * Non ci sono le aree d'altri (le share sono quelle di [AiRoles.shares], D14). Il testo conta solo con la lettura attiva qui (D41).
 * [limit] e' il tetto dei candidati letti per ambito (un conteggio, non un elenco da mostrare).
 */
class AiBacklog(
  private val roles: AiRoles,
  private val candidates: AiCandidates,
  private val buffer: AiBuffer? = null,
  private val recipe: AiRecipe = AiRecipe.CURRENT,
  private val limit: Int = 1_000_000,
) {
  suspend fun count(): AiWork {
    if (!roles.indexer.value) return AiWork.NONE
    val reading = roles.textReadingHere.value
    val all = roles.shares().flatMap { candidates.forShare(it, recipe.version, limit) } + candidates.phoneOnly(recipe.version, limit)
    // Lo stesso contenuto in piu' aree si calcola una volta (D23).
    val unique = all.groupBy { it.contentKey }.map { (_, same) -> merge(same) }
    var photos = 0L
    var videos = 0L
    var texts = 0L
    var buffered = 0L
    for (c in unique) {
      val vec = AiClass.VEC in c.missing
      val ocr = reading && AiClass.OCR in c.missing
      if (!vec && !ocr) continue
      if (vec) if (c.kind == MediaKind.VIDEO) videos++ else photos++
      if (ocr) texts++
      val cls = if (c.kind == MediaKind.VIDEO) SegmentClass.FRAMES else SegmentClass.ANALYSIS
      val inBuffer = when (buffer) {
        null -> false
        is DiskAiBuffer -> buffer.contains(c.contentKey, cls)
        else -> buffer.get(c.contentKey, cls) != null
      }
      if (vec && inBuffer) buffered++
    }
    return AiWork(photos, videos, texts, buffered)
  }

  private fun merge(same: List<AiCandidate>): AiCandidate =
    if (same.size == 1) same[0] else same[0].copy(missing = same.flatMapTo(LinkedHashSet()) { it.missing })
}
