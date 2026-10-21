package dev.pampa.mycloudgallery.core.index.testing

import dev.pampa.mycloudgallery.core.index.ai.AiBufferSink
import dev.pampa.mycloudgallery.core.index.ai.AiDoneClasses
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.ai.AiIndexPublisher
import dev.pampa.mycloudgallery.core.index.ai.AiPendingCounts
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaSink
import dev.pampa.mycloudgallery.core.index.ai.AiWinner
import dev.pampa.mycloudgallery.core.index.ai.AnalysisFetcher
import dev.pampa.mycloudgallery.core.index.ai.FetchReport
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow

// I fake dei dati AI dell'indice (Fase 6, P0, §5.2): in src/main per i test di P5, P10 e P11. Mai nella release (tools/check.ps1).
// Funzionano davvero (outbox per share, cancello, blocchi permanenti con le voci in attesa e la ripubblicazione senza ricalcolo,
// blob attestati, budget), ma senza database ne' NAS: P2 scrive le implementazioni vere e una suite di parita' contro questi.

/**
 * Il pubblicatore finto della testa `ai`: un'outbox per share, il cancello ([gate]) interrogato a ogni [flush]. Un blocco
 * **permanente** ([permanent]: F4-D31 `LOCAL_ONLY`, `READ_ONLY_AREA`) sposta l'outbox nelle voci in attesa (`ai_pending_publish`,
 * D8): non si perdono e [releasePending] le ripubblica quando la share si apre. Un blocco temporaneo lascia tutto nell'outbox.
 *
 * [failNextFlush] simula un errore del NAS prima di scrivere (l'outbox resta intatta): il "guasto a ogni chiamata" dei test di P5.
 */
class FakeAiIndexPublisher(
  var gate: IndexWriteGate = IndexWriteGate { null },
  val permanent: Set<WriteBlock> = setOf(WriteBlock.LOCAL_ONLY, WriteBlock.READ_ONLY_AREA),
) : AiIndexPublisher {

  /** Una testa pubblicata. */
  class Published(val share: String, val headVersion: Long, val entries: List<AiEntry>)

  private val outboxes = LinkedHashMap<String, MutableList<AiEntry>>()
  private val waiting = LinkedHashMap<String, MutableList<AiEntry>>()
  private val versions = HashMap<String, Long>()

  val published: MutableList<Published> = mutableListOf()
  val staged: MutableList<Pair<String, AiEntry>> = mutableListOf()
  var failNextFlush: Boolean = false

  override val pending: MutableStateFlow<AiPendingCounts> = MutableStateFlow(AiPendingCounts.EMPTY)

  override suspend fun stage(share: String, entries: List<AiEntry>) {
    synchronized(this) {
      outboxes.getOrPut(key(share)) { mutableListOf() } += entries
      entries.forEach { staged += share to it }
      refresh()
    }
  }

  override suspend fun flush(share: String, reason: FlushReason): PublishOutcome {
    val k = key(share)
    val box = synchronized(this) { outboxes[k]?.toList().orEmpty() }
    if (box.isEmpty()) return PublishOutcome.NothingPending
    gate.blockFor(share)?.let { block ->
      if (block in permanent) {
        synchronized(this) {
          waiting.getOrPut(k) { mutableListOf() } += box
          outboxes[k]?.clear()
          refresh()
        }
      }
      return PublishOutcome.Blocked(block)
    }
    if (failNextFlush) {
      failNextFlush = false
      throw IOException("guasto simulato prima della testa ai")
    }
    val version = synchronized(this) {
      outboxes[k]?.clear()
      val v = (versions[k] ?: 0L) + 1
      versions[k] = v
      refresh()
      v
    }
    published += Published(share, version, box)
    return PublishOutcome.Published(version, segments = box.map { it.cls }.distinct().size, bytes = 0)
  }

  override suspend fun releasePending(share: String): Int {
    if (gate.blockFor(share) != null) return 0
    val moved = synchronized(this) {
      val list = waiting.remove(key(share)).orEmpty()
      outboxes.getOrPut(key(share)) { mutableListOf() } += list
      refresh()
      list.size
    }
    return moved
  }

  /** Le voci in attesa di una share pubblicabile (per i test di D8). */
  fun waitingFor(share: String): List<AiEntry> = synchronized(this) { waiting[key(share)]?.toList().orEmpty() }

  private fun refresh() {
    pending.value = AiPendingCounts(outboxes.values.sumOf { it.size }, waiting.values.sumOf { it.size })
  }

  private fun key(share: String) = share.lowercase(Locale.ROOT)
}

/** La replica finta: registra cio' che arriva, per share e chiave. */
class FakeAiReplicaSink : AiReplicaSink {
  val applied: MutableList<AiWinner> = mutableListOf()
  val forgottenDevices: MutableList<String> = mutableListOf()
  val forgottenShares: MutableList<String> = mutableListOf()

  override suspend fun apply(share: String, winners: List<AiWinner>) {
    synchronized(this) { applied += winners }
  }

  override suspend fun forgetDevice(device: String) {
    synchronized(this) {
      forgottenDevices += device
      applied.removeAll { it.device == device }
    }
  }

  override suspend fun forgetShare(share: String) {
    synchronized(this) {
      forgottenShares += share
      applied.removeAll { it.share.equals(share, ignoreCase = true) }
    }
  }
}

/**
 * Lo scaricamento finto delle anteprime: i blob li sceglie il test ([put]) con il flag di **attestazione** (R1 della 4A). Un blob
 * non attestato non si consegna mai (conta come mancante). Rispetta le classi ammesse ([AnalysisFetcher.FETCHABLE]) e ferma lo
 * scaricamento quando il buffer rifiuta. [calls] e [bytesRead] servono ai test del traffico (una lettura del NAS registrata).
 */
class FakeAnalysisFetcher : AnalysisFetcher {
  private class Blob(val bytes: ByteArray, val attested: Boolean)

  private val blobs = HashMap<Triple<String, ContentKey, SegmentClass>, Blob>()
  var calls: Int = 0
    private set
  var bytesRead: Long = 0
    private set

  /** Mette nella share [share] il blob [cls] di [key]. */
  fun put(share: String, key: ContentKey, cls: SegmentClass, bytes: ByteArray, attested: Boolean = true) {
    blobs[Triple(share.lowercase(Locale.ROOT), key, cls)] = Blob(bytes.copyOf(), attested)
  }

  override suspend fun fetch(
    share: String,
    keys: List<ContentKey>,
    classes: Set<SegmentClass>,
    into: AiBufferSink,
    budget: Duration,
  ): FetchReport {
    calls++
    require(classes.all { it in AnalysisFetcher.FETCHABLE }) { "Solo analysis, frames e hq." }
    var fetched = 0
    var missing = 0
    var bytes = 0L
    for (key in keys) {
      for (cls in classes) {
        val blob = blobs[Triple(share.lowercase(Locale.ROOT), key, cls)]
        if (blob == null || !blob.attested) {
          missing++
          continue
        }
        bytes += blob.bytes.size
        bytesRead += blob.bytes.size
        if (!into.put(key, cls, blob.bytes.copyOf())) return FetchReport(fetched, missing, bytes, stoppedEarly = true)
        fetched++
      }
    }
    return FetchReport(fetched, missing, bytes, stoppedEarly = false)
  }
}

/** Il lavoro gia' fatto da altri, scelto dal test (per share, chiave e ricetta). */
class FakeAiDoneClasses : AiDoneClasses {
  private val done = HashMap<Triple<String, ContentKey, Int>, MutableSet<AiClass>>()

  fun mark(share: String, key: ContentKey, recipe: Int, vararg classes: AiClass) {
    done.getOrPut(Triple(share.lowercase(Locale.ROOT), key, recipe)) { LinkedHashSet() } += classes
  }

  override suspend fun done(share: String, keys: Collection<ContentKey>, recipe: Int): Map<ContentKey, Set<AiClass>> =
    keys.mapNotNull { key -> done[Triple(share.lowercase(Locale.ROOT), key, recipe)]?.let { key to it.toSet() } }.toMap()
}
