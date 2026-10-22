package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.compute.AiUnsupported
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidates
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.NasWorkItem
import dev.pampa.mycloudgallery.core.index.ai.AiKey
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaStore
import dev.pampa.mycloudgallery.core.index.ai.AiShareContent
import dev.pampa.mycloudgallery.core.index.ai.AiWorkCatalog
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.io.IOException
import java.util.Locale

// Le PORTE dei dati AI dell'indice condiviso (P2) e del calcolo (P5) sopra il catalogo locale (Fase 6, P11). Solo dati locali: le
// chiavi di contenuto vengono dalle posizioni del NAS che il catalogo conosce (impronta rapida e dimensione, `ContentKey.of`), mai da un
// record remoto.

/** I file **presenti** di una share per l'account di questo dispositivo, con la loro chiave di contenuto (versione corrente). */
internal suspend fun shareRows(catalog: MediaCatalog, account: AccountId, share: String): List<Pair<ContentKey, NasWorkItem>> =
  catalog.nasWorkRows(account, share).mapNotNull { row ->
    runCatching { ContentKey.of(row.quickHash, row.stamp.size) }.getOrNull()?.let { it to row }
  }

/** Il mese di un media (`aaaa-mm`, `0000-00` senza data), come le unita' della preparazione. */
internal fun monthOf(localDay: Int?): String {
  if (localDay == null || localDay <= 0) return "0000-00"
  return "%04d-%02d".format(Locale.ROOT, localDay / 10_000, localDay / 100 % 100)
}

/**
 * La regola **M3** con le chiavi (D8): delle chiavi date, quelle che sono la chiave di contenuto di un file **presente** di [share]
 * nel catalogo locale, con la versione corrente (timbro e impronta rapida dell'ultimo elenco).
 */
class CatalogAiShareContent(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: suspend () -> AccountId?,
) : AiShareContent {
  override suspend fun owned(share: String, keys: Collection<ContentKey>): Set<ContentKey> {
    if (keys.isEmpty()) return emptySet()
    val c = catalog() ?: return emptySet()
    val owner = account() ?: return emptySet()
    val wanted = keys.toHashSet()
    return shareRows(c, owner, share).mapNotNullTo(HashSet()) { (key, _) -> key.takeIf { it in wanted } }
  }
}

/**
 * I media "non supportati" per classe (E-A12: ingresso rifiutato, vettore non valido due volte): segnati dal calcolo, letti dal
 * catalogo del lavoro (non si riprovano a ogni giro) e dalla copertura. Un file di righe `hex classe` in `noBackupFilesDir`, in sola
 * aggiunta; l'azzeramento lo toglie.
 */
class FileAiUnsupported(private val file: File) : AiUnsupported {
  private val lock = Any()
  private var loaded: HashSet<AiKey>? = null

  override suspend fun mark(key: ContentKey, cls: AiClass) {
    synchronized(lock) {
      val set = loadLocked()
      if (!set.add(AiKey(key, cls))) return
      try {
        file.parentFile?.mkdirs()
        file.appendText("${key.hex} ${cls.wire}\n")
      } catch (e: IOException) {
        // Resta in memoria per questo processo: al prossimo il media si riprova una volta.
      }
    }
  }

  fun classesOf(keys: Collection<ContentKey>): Map<ContentKey, Set<AiClass>> = synchronized(lock) {
    val set = loadLocked()
    keys.associateWith { k -> AiClass.entries.filterTo(LinkedHashSet()) { AiKey(k, it) in set } }.filterValues { it.isNotEmpty() }
  }

  private fun loadLocked(): HashSet<AiKey> = loaded ?: HashSet<AiKey>().also { set ->
    try {
      if (file.isFile) {
        file.readLines().forEach { line ->
          val parts = line.trim().split(' ')
          if (parts.size != 2) return@forEach
          val cls = AiClass.fromWire(parts[1]) ?: return@forEach
          runCatching { ContentKey(parts[0]) }.getOrNull()?.let { set += AiKey(it, cls) }
        }
      }
    } catch (e: IOException) {
      // Nel dubbio niente e' "non supportato": il calcolo riprova.
    }
    loaded = set
  }

  companion object {
    const val FILE = "ai-unsupported"
  }
}

/**
 * Il catalogo del lavoro AI (D9, `AiWorkSource`): i media **presenti** della share per mese e, per P5 (`REPORT-p5`), le classi gia' a
 * posto **nella share**: i vincitori consegnati dalla replica `ai` (anche i propri, una volta pubblicati e riletti) con la ricetta
 * corrente, oppure "non supportato". **Non** conta cio' che e' salvato solo qui: il calcolo lo rimette nell'outbox senza ricalcolarlo,
 * e un media salvato ma mai pubblicato (F4-D31, morte del processo) torna cosi' in un'unita'.
 */
class CatalogAiWorkCatalog(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: suspend () -> AccountId?,
  private val replica: AiReplicaStore,
  private val unsupported: FileAiUnsupported,
  private val recipe: AiRecipe = AiRecipe.CURRENT,
) : AiWorkCatalog {

  private suspend fun rows(share: String): List<Pair<ContentKey, NasWorkItem>> {
    val c = catalog() ?: return emptyList()
    val owner = account() ?: return emptyList()
    return shareRows(c, owner, share)
  }

  override suspend fun months(share: String): List<String> = rows(share).map { monthOf(it.second.localDay) }.distinct().sortedDescending()

  override suspend fun monthsOf(share: String, visible: Set<Long>): List<String> =
    rows(share).filter { it.first.short in visible }.map { monthOf(it.second.localDay) }.distinct().sortedDescending()

  override suspend fun monthMedia(share: String, month: String): List<WorkItem> = rows(share).mapNotNull { (key, row) ->
    if (monthOf(row.localDay) != month) return@mapNotNull null
    val path = runCatching { SharePath.parseMediaPath(row.path.value) }.getOrNull() ?: return@mapNotNull null
    WorkItem(key, path, row.stamp, row.kind, row.mime, row.mediaId, row.versionId, emptySet())
  }

  override suspend fun settled(share: String, keys: Collection<ContentKey>): Map<ContentKey, Set<AiClass>> {
    if (keys.isEmpty()) return emptyMap()
    val out = HashMap<ContentKey, MutableSet<AiClass>>()
    val wanted = keys.flatMap { k -> AiClass.entries.map { AiKey(k, it) } }
    for ((key, mark) in replica.applied(share.lowercase(Locale.ROOT), wanted)) {
      val current = if (key.cls == AiClass.VEC) recipe.version else recipe.ocrVersion
      if (mark.recipe >= current) out.getOrPut(key.contentKey) { LinkedHashSet() } += key.cls
    }
    unsupported.classesOf(keys).forEach { (k, classes) -> out.getOrPut(k) { LinkedHashSet() } += classes }
    return out
  }
}

/**
 * I candidati del calcolo dal catalogo locale (contratto `AiCandidates`): per una share (il calcolo con il solo buffer, E-A6) i media
 * presenti dal piu' recente con le classi che mancano nella share; i media del solo telefono non hanno ancora una sorgente di byte in
 * :app (vedi `docs/decisioni.md`, sezione P11): nessun candidato.
 */
class CatalogAiCandidates(
  private val work: CatalogAiWorkCatalog,
  private val readingHere: () -> Boolean,
) : AiCandidates {
  override suspend fun forShare(share: String, recipe: Int, limit: Int): List<AiCandidate> {
    val months = work.months(share)
    val out = ArrayList<AiCandidate>()
    for (month in months) {
      val media = work.monthMedia(share, month)
      val settled = work.settled(share, media.map { it.contentKey })
      for (item in media) {
        val wanted = if (readingHere()) setOf(AiClass.VEC, AiClass.OCR) else setOf(AiClass.VEC)
        val missing = wanted - settled[item.contentKey].orEmpty()
        if (missing.isNotEmpty()) out += AiCandidate(item.contentKey, item.kind, MediaSource.OTHER, missing)
        if (out.size >= limit) return out
      }
    }
    return out
  }

  override suspend fun phoneOnly(recipe: Int, limit: Int): List<AiCandidate> = emptyList()
}
