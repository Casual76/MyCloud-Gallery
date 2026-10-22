package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.work.PublishedClasses
import dev.pampa.mycloudgallery.core.index.work.PublishedProgress
import dev.pampa.mycloudgallery.core.index.work.WorkSource
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.util.Locale

/**
 * Il lavoro AI gia' fatto nella share (D9; formato-nas.md §5.6 "Unita' del calcolo AI"): un contenuto e' "fatto" **per classe**
 * quando una testa `ai` **attestata** di **qualunque** dispositivo approvato ha la voce della ricetta corrente (o di una piu' nuova:
 * vincerebbe comunque, D7). Niente evidenza `thumbS`: la voce stessa e' la prova (la sua chiave intera ha un record `meta` della
 * share). E' insieme:
 * - [PublishedClasses] per `LeasedWorkUnits` con [WorkPass.AI]: [refresh] rilegge le teste `ai` della share ([AiShareSync.refresh])
 *   all'inizio di ogni unita' e a ogni rinnovo; [progress] toglie dalle unita' cio' che altri hanno gia' pubblicato (E-I1);
 * - [AiDoneClasses] per il calcolo (P5), dalla replica senza rete.
 *
 * Le classi "fatte" sono i vincitori consegnati all'indice locale ([AiReplicaStore.allApplied]): gli stessi che la ricerca usa.
 *
 * Revisione A (R16): "fatto" vuol dire la ricetta **corrente** ([recipe], la piu' alta che quest'app conosce). Una ricetta piu' nuova di
 * quella che l'app conosce (anche la 255 di un dispositivo compromesso) non conta come fatta: si calcola con la ricetta corrente, e dopo
 * un aggiornamento dell'app la ricetta vecchia non conta piu' (D21).
 */
class AiPublishedClasses(
  private val sync: AiShareSync,
  private val store: AiReplicaStore,
  /** La ricetta corrente di ogni classe (`AiRecipe` di core-ai: quella dei vettori e quella dell'OCR). */
  private val recipe: (AiClass) -> Int,
) : PublishedClasses, AiDoneClasses {

  private val lock = Any()
  private val done = HashMap<String, Map<ContentKey, Set<SegmentClass>>>()

  override suspend fun refresh(share: String): RemoteResult<Unit> {
    val result = sync.refresh(share)
    // Anche se il NAS non ha risposto: vale cio' che la replica sa gia'.
    load(share)
    return result
  }

  private suspend fun load(share: String) {
    val map = HashMap<ContentKey, MutableSet<SegmentClass>>()
    for (mark in store.allApplied(share.lowercase(Locale.ROOT))) {
      if (mark.recipe != recipe(mark.key.cls)) continue
      map.getOrPut(mark.key.contentKey) { LinkedHashSet() } += mark.key.cls.segmentClass
    }
    synchronized(lock) { done[share.lowercase(Locale.ROOT)] = map }
  }

  override fun progress(share: String, keys: Collection<ContentKey>): Map<ContentKey, PublishedProgress> = synchronized(lock) {
    val known = done[share.lowercase(Locale.ROOT)] ?: return@synchronized emptyMap()
    keys.mapNotNull { k -> known[k]?.let { k to PublishedProgress(it, emptySet()) } }.toMap()
  }

  override suspend fun done(share: String, keys: Collection<ContentKey>, recipe: Int): Map<ContentKey, Set<AiClass>> {
    val wanted = keys.flatMap { k -> AiClass.entries.map { AiKey(k, it) } }
    val out = HashMap<ContentKey, MutableSet<AiClass>>()
    for ((key, mark) in store.applied(share.lowercase(Locale.ROOT), wanted)) {
      if (mark.recipe == recipe) out.getOrPut(key.contentKey) { LinkedHashSet() } += key.cls
    }
    return out
  }
}

/**
 * I media di una share per le unita' del calcolo AI (D9, [WorkPass.AI]): **tutti** i media del mese (le parti non si spostano quando
 * il lavoro avanza), con [WorkItem.missing] = le classi AI che servono qui ([wanted]: `vec` per ogni indicizzatore, `ocr` solo con la
 * lettura del testo attiva su questo dispositivo, D41) meno quelle gia' a posto nell'indice locale ([AiWorkCatalog.settled]). Cio' che
 * gli altri hanno pubblicato lo toglie [AiPublishedClasses] dentro `LeasedWorkUnits`.
 */
class AiWorkSource(
  private val catalog: AiWorkCatalog,
  private val wanted: suspend () -> Set<AiClass>,
) : WorkSource {

  override suspend fun months(share: String, pass: WorkPass): List<String> {
    require(pass == WorkPass.AI) { "Solo il passaggio ai." }
    val want = wanted()
    if (want.isEmpty()) return emptyList()
    return catalog.months(share).filter { month -> items(share, month, want).any { it.missing.isNotEmpty() } }
  }

  override suspend fun monthsOf(share: String, pass: WorkPass, visible: Set<Long>): List<String> {
    require(pass == WorkPass.AI) { "Solo il passaggio ai." }
    if (visible.isEmpty() || wanted().isEmpty()) return emptyList()
    return catalog.monthsOf(share, visible)
  }

  override suspend fun monthItems(share: String, pass: WorkPass, month: String): List<WorkItem> {
    require(pass == WorkPass.AI) { "Solo il passaggio ai." }
    return items(share, month, wanted())
  }

  private suspend fun items(share: String, month: String, want: Set<AiClass>): List<WorkItem> {
    val media = catalog.monthMedia(share, month)
    if (media.isEmpty()) return emptyList()
    val settled = if (want.isEmpty()) emptyMap() else catalog.settled(share, media.map { it.contentKey })
    return media.map { item ->
      val missing = (want - settled[item.contentKey].orEmpty()).mapTo(LinkedHashSet()) { it.segmentClass }
      item.copy(missing = missing)
    }
  }
}
