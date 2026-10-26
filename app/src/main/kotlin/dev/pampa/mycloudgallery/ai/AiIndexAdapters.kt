package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.compute.AiInputOrigin
import dev.pampa.mycloudgallery.core.ai.compute.AiLocalBodiesFor
import dev.pampa.mycloudgallery.core.ai.compute.AiOriginResults
import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.index.ai.AiCompactionMemory
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.ai.AiIndexCounters
import dev.pampa.mycloudgallery.core.index.ai.AiIndexDiagnostics
import dev.pampa.mycloudgallery.core.index.ai.AiKey
import dev.pampa.mycloudgallery.core.index.ai.AiOwnBodies
import dev.pampa.mycloudgallery.core.index.ai.AiPendingCounts
import dev.pampa.mycloudgallery.core.index.ai.AiRedelivery
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaSink
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaStore
import dev.pampa.mycloudgallery.core.index.ai.AiWinner
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.search.index.IndexRepair
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import dev.pampa.mycloudgallery.core.search.index.SearchIndexCounters
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import java.io.File
import java.io.IOException
import java.util.Locale

// Gli ADATTATORI fra il calcolo (P5), la replica `ai` (P2) e l'indice locale di ricerca (P6), in :app (Fase 6, P11).
//
// Revisione A: l'indice locale tiene, per ogni origine, anche il corpo `ocr` com'e' e la sorgente dei vettori. Le righe del testo dal vivo
// e i corpi da ripubblicare vengono da li' (R4, R5), non piu' da file per chiave scritti a parte (`ocr-layout`, `local-bodies`), che
// l'ultimo arrivato sovrascriveva e che la revoca non toglieva.

/** La provenienza del calcolo (core-ai) come la vede l'indice locale (core-search). */
internal fun AiInputOrigin.derivation(): LocalAiIndex.Derivation = when (this) {
  AiInputOrigin.Own -> LocalAiIndex.Derivation.Own
  is AiInputOrigin.Preview -> LocalAiIndex.Derivation.Preview(share, device)
}

/**
 * L'`AiResults` del calcolo (P5) sopra l'indice locale (P6), con la provenienza (R5, [AiOriginResults]): il dato e il corpo da ripubblicare
 * stanno insieme nell'indice, che e' il punto di conferma (`saved`). Un risultato nato dall'anteprima di un altro dispositivo vale solo per
 * la share di quell'anteprima ([savedFor], [vectorsFor], [textFor]).
 */
class IndexAiResults(
  private val index: LocalAiIndex,
  /** L'indice aperto (`SearchGraph.open`, idempotente): un lavoro in un processo appena nato puo' arrivare prima dell'apertura. */
  private val ready: suspend () -> Boolean = { true },
) : AiResults, AiOriginResults, AiLocalBodiesFor {
  private suspend fun opened() {
    if (!ready()) throw IOException("indice di ricerca non aperto")
  }

  override suspend fun saveVectors(key: ContentKey, body: VecBody) = saveVectors(key, body, AiInputOrigin.Own)

  override suspend fun saveText(key: ContentKey, body: OcrBody) = saveText(key, body, AiInputOrigin.Own)

  override suspend fun saveVectors(key: ContentKey, body: VecBody, from: AiInputOrigin) {
    opened()
    index.saveVectors(key, body, from.derivation())
  }

  override suspend fun saveText(key: ContentKey, body: OcrBody, from: AiInputOrigin) {
    opened()
    index.saveText(key, body, from.derivation())
  }

  override suspend fun saved(key: ContentKey, recipe: Int): Set<AiClass> {
    opened()
    return index.saved(key, recipe)
  }

  override suspend fun savedFor(key: ContentKey, recipe: Int, share: String?): Set<AiClass> {
    opened()
    return index.savedFor(key, recipe, share)
  }

  override suspend fun vectors(key: ContentKey): VecBody? = vectorsFor(key, null)

  override suspend fun text(key: ContentKey): OcrBody? = textFor(key, null)

  override suspend fun vectorsFor(key: ContentKey, share: String?): VecBody? {
    opened()
    return index.localVectors(key, share)
  }

  override suspend fun textFor(key: ContentKey, share: String?): OcrBody? {
    opened()
    return index.localText(key, share)
  }
}

/**
 * La destinazione della replica `ai` (`AiReplicaSink`, P2) sull'indice locale (P6): i vincitori di una share entrano con la loro
 * origine (la replica toglie solo le sue, mai cio' che e' calcolato qui); il testo porta anche le righe per il testo dal vivo (D42: il
 * testo letto da un altro dispositivo vale anche qui, con la lettura spenta e senza ML Kit). La revoca di un dispositivo
 * ([forgetDevice]) toglie anche cio' che questo dispositivo ha calcolato sulle sue anteprime (R5).
 */
class IndexReplicaSink(
  private val index: LocalAiIndex,
  /** L'indice aperto: senza (catalogo non ancora pronto) la replica non conferma la consegna e la ripete al giro dopo. */
  private val ready: suspend () -> Boolean = { true },
) : AiReplicaSink {
  private suspend fun opened() {
    if (!ready()) throw IOException("indice di ricerca non aperto")
  }

  override suspend fun apply(share: String, winners: List<AiWinner>) {
    opened()
    val items = winners.map { w ->
      when (val e = w.entry) {
        is AiEntry.Vector -> LocalAiIndex.ReplicaItem.Vectors(e.contentKey, w.device, e.body)
        is AiEntry.Text -> LocalAiIndex.ReplicaItem.Text(e.contentKey, w.device, e.body)
      }
    }
    if (items.isNotEmpty()) index.applyReplica(share, items)
  }

  override suspend fun withdraw(share: String, keys: List<AiKey>) {
    opened()
    if (keys.isNotEmpty()) index.withdraw(share, keys.map { it.contentKey to it.cls })
  }

  override suspend fun forgetDevice(device: String) {
    opened()
    index.forgetDevice(device)
  }

  override suspend fun forgetShare(share: String) {
    opened()
    index.forgetShare(share)
  }
}

/**
 * R15: chi ripara l'indice locale (record rovinato, registro rotto) chiede alla replica `ai` di riconsegnare: si toglie il segno
 * "consegnato" di quelle chiavi in ogni share ([AiRedelivery]), cosi' la fusione del giro dopo le consegna anche se il vincitore e' lo
 * stesso. Cio' che era calcolato qui lo rifa' il calcolo (`saved` non lo dice piu').
 */
class ReplicaIndexRepair(private val store: AiReplicaStore) : IndexRepair {
  private val redelivery = AiRedelivery(store)

  override suspend fun redeliver(keys: Collection<ContentKey>) = redelivery.redeliver(keys)

  override suspend fun redeliverAll() = redelivery.redeliver(null)
}

/**
 * R14: i corpi che questo dispositivo sostiene ancora per le proprie voci vive (la compattazione delle teste `ai` proprie): quelli calcolati
 * qui **per quella share** (R5: dai propri byte, o da un'anteprima di quella share e di un dispositivo non revocato) e uguali a quelli
 * pubblicati. Dopo una revoca un corpo cambiato o tolto fa uscire la voce: il calcolo la rifa' se serve.
 */
class IndexOwnBodies(
  private val index: LocalAiIndex,
  /** L'indice aperto: senza, non si sa cosa c'e' e la compattazione non toglie niente (eccezione). */
  private val ready: suspend () -> Boolean = { true },
) : AiOwnBodies {
  override suspend fun body(share: String, key: AiKey, digest: String): ByteArray? {
    if (!ready()) throw IOException("indice di ricerca non aperto")
    val body = when (key.cls) {
      AiClass.VEC -> index.localVectors(key.contentKey, share)
      AiClass.OCR -> index.localText(key.contentKey, share)
    } ?: return null
    return AiSegments.bodyOrNull(body)?.takeIf { AiSegments.digest(it) == digest }
  }
}

/**
 * R14: quando ogni share e' stata compattata l'ultima volta (ora del NAS), in un file di `noBackupFilesDir` (righe `impronta ora`): la
 * compattazione si ricontrolla una volta al giorno anche se i processi durano poco. Le share si scrivono come impronta, mai per nome.
 */
class FileCompactionMemory(private val file: File) : AiCompactionMemory {
  private val lock = Any()
  private var loaded: HashMap<String, Long>? = null

  override fun last(share: String): Long? = synchronized(lock) { loadLocked()[tag(share)] }

  override fun done(share: String, atNasMillis: Long) {
    synchronized(lock) {
      val map = loadLocked()
      map[tag(share)] = atNasMillis
      try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(map.entries.joinToString("") { "${it.key} ${it.value}\n" })
        if (!tmp.renameTo(file)) {
          file.delete()
          tmp.renameTo(file)
        }
      } catch (e: IOException) {
        // Resta in memoria: al prossimo processo si ricontrolla una volta di piu'.
      }
    }
  }

  private fun loadLocked(): HashMap<String, Long> = loaded ?: HashMap<String, Long>().also { map ->
    try {
      if (file.isFile) {
        file.readLines().forEach { line ->
          val parts = line.trim().split(' ')
          if (parts.size == 2) parts[1].toLongOrNull()?.let { map[parts[0]] = it }
        }
      }
    } catch (e: IOException) {
      // Nel dubbio si ricontrolla.
    }
    loaded = map
  }

  companion object {
    const val FILE = "ai-compaction"

    /** L'impronta di una share (minuscolo): niente nomi in chiaro in un file. */
    fun tag(share: String): String = shareTag(share)
  }
}

/** L'impronta corta di una share (minuscolo, SHA-256, 16 cifre): per i file locali che ricordano qualcosa per share. */
internal fun shareTag(share: String): String {
  val digest = java.security.MessageDigest.getInstance("SHA-256").digest(share.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
  return digest.take(8).joinToString("") { "%02x".format(it) }
}

/**
 * DG-1: il contributo dei dati AI dell'indice alla Diagnostica (`AI_SEGMENT_REJECTED`, `AI_VECTOR_DISAGREE`, `AI_INDEX_STATE`), legato in
 * `AiModule` accanto ad `AiDiagnostics`: i contatori della replica e del pubblicatore, le voci in attesa lette dall'archivio di adesso e i
 * numeri dei tetti dell'indice locale (R1, R3). Solo numeri: mai share, chiavi, dispositivi o testo (D40).
 */
fun aiIndexContributor(
  counters: AiIndexCounters,
  pending: suspend () -> AiPendingCounts?,
  local: () -> SearchIndexCounters?,
  titleOf: (DiagCode) -> Int = DiagCatalog::titleOf,
): AiIndexDiagnostics = AiIndexDiagnostics(counters, pending, titleOf) {
  local()?.let { c ->
    mapOf(
      "localOverCap" to c.overCap.get().toLong(),
      "localEarlyCompactions" to c.earlyCompactions.get().toLong(),
      "localResets" to c.resets.get().toLong(),
      "localCorruptRecords" to c.corruptRecords.get().toLong(),
    )
  }.orEmpty()
}
