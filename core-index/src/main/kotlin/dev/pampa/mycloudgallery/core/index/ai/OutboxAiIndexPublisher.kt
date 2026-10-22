package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.PendingCounts
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.Chunking
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.Nas
import dev.pampa.mycloudgallery.core.index.publish.OutboxIndexPublisher
import dev.pampa.mycloudgallery.core.index.publish.RemoteFailures
import dev.pampa.mycloudgallery.core.index.publish.ShareLocks
import dev.pampa.mycloudgallery.core.index.publish.nasCall
import dev.pampa.mycloudgallery.core.index.store.OwnHead
import dev.pampa.mycloudgallery.core.index.store.OwnSegment
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.EncodedSegment
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il pubblicatore della testa `ai` di questo dispositivo (D8; formato-nas.md §5.5 "Testa `ai`", §5.5-bis). Stessa meccanica di
 * [OutboxIndexPublisher], una testa per share, ma senza pacco dei blob: i corpi `vec` e `ocr` sono piccoli (al piu' 14 e 64 KiB) e
 * stanno nelle righe del database ([AiPublishStore]). Uno per processo.
 *
 * **Regole** (prima di accettare una voce e di nuovo prima di firmare):
 * - **M3** ([content]): una voce solo per la chiave di contenuto di un file di quella share nel catalogo locale, con timbro e `qh1`
 *   freschi. Una chiave che non lo e' (di un'altra share, o il file e' cambiato) non entra e si conta: nessun vettore o testo di un
 *   file privato finisce nell'indice dell'area comune;
 * - il corpo passa dagli stessi controlli di chi legge ([AiSegments.bodyOrNull]): mai un segmento che gli altri rifiuterebbero;
 * - un corpo identico alla voce gia' pubblicata per la stessa (classe, chiave) non si ripubblica;
 * - gli stessi cancelli dell'indice ([IndexWriteRules]: D31, congelamento, salto dell'ora, sola lettura, emergenza) e la fiducia;
 *   una testa ogni 5 minuti d'ora del NAS per share, salvo [FlushReason.UNIT_FINISHED]; segmenti chiusi a 12 MiB (4.096 voci).
 *
 * **Blocco permanente** (D31 su una share vera, area in sola lettura: [IndexWriteRules.permanentBlockFor]): le voci vanno fra quelle
 * **in attesa** ([AiRowState.HELD], `ai_pending_publish`) con il corpo gia' calcolato; [releasePending] le ripubblica quando la share
 * si apre, **senza ricalcolarle** (D8), se la loro chiave e' ancora di un file della share con timbro fresco. Diversamente dalle
 * miniature (R2 della 4A) non si buttano: costano una notte di calcolo, non una rilettura.
 *
 * **Compattazione** (revisione A, R14; [AiOwnCompaction]): a ogni pubblicazione permessa, se e' il momento (una volta al giorno d'ora del
 * NAS, troppi segmenti, voci superate, o una richiesta dopo la revoca di un dispositivo: [requestCompaction]) la testa si riscrive con le
 * sole voci vive di file della share che questo dispositivo sostiene ancora; quelle tolte escono dalla replica di chi legge.
 *
 * **Morte del processo a ogni passo.** [stage] e' una transazione. [flush]: si leggono le righe (niente si toglie) → segmenti con
 * nome = impronta (una ripetizione trova "esiste gia'" con gli stessi byte) → testa `version + 1` con **tutti** i segmenti propri →
 * una transazione con testa, righe consumate e voci vive. Una morte prima del commit lascia l'outbox intatta: il giro dopo ricostruisce
 * gli stessi segmenti (piano deterministico) e adotta la testa gia' scritta invece di scriverne un'altra.
 */
class OutboxAiIndexPublisher(
  private val identity: IndexIdentity,
  private val clock: NasClock,
  private val fileSystem: (String) -> NasFileSystem,
  private val store: AiPublishStore,
  private val content: AiShareContent,
  private val rules: IndexWriteRules,
  val counters: AiIndexCounters = AiIndexCounters(),
  private val locks: ShareLocks = ShareLocks(),
  private val io: CoroutineDispatcher = Dispatchers.IO,
  /** Righe dell'outbox al massimo per pubblicazione (4 segmenti pieni): il resto alla pubblicazione dopo. */
  private val maxRowsPerFlush: Int = DEFAULT_MAX_ROWS_PER_FLUSH,
  /** R14: i corpi che questo dispositivo sostiene ancora (null = si tolgono solo le voci superate e quelle di file non piu' della share). */
  ownBodies: AiOwnBodies? = null,
  /** R14: quando si e' compattata ogni share. */
  private val compactions: AiCompactionMemory = AiCompactionMemory.inMemory(),
  /** R14: ogni quanto (ora del NAS) si controllano le voci vive; null = solo su richiesta o per troppi segmenti e voci superate. */
  private val compactEveryMillis: Long? = null,
) : AiIndexPublisher {

  override val pending: MutableStateFlow<AiPendingCounts> = MutableStateFlow(AiPendingCounts.EMPTY)

  private val heads = AiHeadWriter(identity, io)

  private val compaction = AiOwnCompaction(identity, store, content, ownBodies, counters, io)

  /** Le richieste di compattazione (un numero che cresce) e, per share, l'ultima soddisfatta: solo in memoria (si ripete a ogni revoca). */
  private val requests = java.util.concurrent.atomic.AtomicLong()
  private val satisfied = HashMap<String, Long>()

  /**
   * R5, R14: un dispositivo e' stato revocato (o perso): alla prossima pubblicazione permessa di ogni share la testa propria si
   * ricontrolla subito, cosi' le voci calcolate sulle sue anteprime escono anche per chi legge.
   */
  fun requestCompaction() {
    requests.incrementAndGet()
  }

  /** [stage], [releasePending] e [purge] non si sovrappongono (le righe si leggono e si scrivono a gruppi). */
  private val stageLock = Mutex()

  /** Da chiamare all'avvio: aggiorna [pending] dal database. */
  suspend fun recover() {
    refreshPending()
  }

  override suspend fun stage(share: String, entries: List<AiEntry>) {
    if (entries.isEmpty()) return
    require(OutboxIndexPublisher.isShareName(share)) { "Nome di share non valido." }
    stageLock.withLock {
      val owned = content.owned(share, entries.mapTo(HashSet()) { it.contentKey })
      val staged = LinkedHashMap<AiKey, AiStaged>()
      for (entry in entries) {
        if (entry.contentKey !in owned) {
          counters.foreign.incrementAndGet()
          continue
        }
        val body = AiSegments.bodyOrNull(entry.body())
        if (body == null) {
          counters.unpublishable.incrementAndGet()
          continue
        }
        // La stessa (classe, chiave) due volte nella stessa chiamata: vale l'ultima.
        staged[AiKey(entry.contentKey, entry.cls)] = AiStaged(entry.cls, entry.contentKey, entry.body().recipe, body)
      }
      if (staged.isEmpty()) return
      // Un corpo identico a quello gia' pubblicato o gia' in attesa non si ripete (una ripresa che rimette in fila cio' che c'era).
      val live = store.live(share, staged.keys)
      val waiting = (store.refs(share, AiRowState.OUTBOX) + store.refs(share, AiRowState.HELD))
        .groupBy { it.key }
        .mapValues { (_, rows) -> rows.maxBy { it.id }.digest }
      val fresh = staged.values.filter { s ->
        val same = waiting[s.key]?.let { it == s.digest } ?: (live[s.key]?.digest == s.digest)
        if (same) counters.unchanged.incrementAndGet()
        !same
      }
      if (fresh.isEmpty()) return
      val state = if (rules.permanentBlockFor(share) != null) AiRowState.HELD else AiRowState.OUTBOX
      store.stage(share, state, fresh)
    }
    refreshPending()
  }

  override suspend fun flush(share: String, reason: FlushReason): PublishOutcome = locks.withLock(share) {
    flushLocked(share, reason).also { refreshPending() }
  }

  /**
   * D8: la share e' diventata pubblicabile (D31 spento o share di prova, area non piu' in sola lettura). Le voci in attesa con la
   * chiave ancora di un file della share (timbro e `qh1` freschi) tornano nell'outbox **cosi' come sono state calcolate**; le altre
   * si tolgono (il file e' cambiato o sparito: la voce non descriverebbe piu' niente). Quante sono tornate.
   */
  override suspend fun releasePending(share: String): Int {
    if (rules.permanentBlockFor(share) != null) return 0
    val moved = stageLock.withLock {
      val held = store.refs(share, AiRowState.HELD)
      if (held.isEmpty()) return@withLock 0
      val owned = content.owned(share, held.mapTo(HashSet()) { it.key.contentKey })
      val (keep, drop) = held.partition { it.key.contentKey in owned }
      if (drop.isNotEmpty()) {
        store.discard(share, drop.mapTo(HashSet()) { it.id })
        counters.foreign.addAndGet(drop.size)
      }
      if (keep.isNotEmpty()) store.move(share, keep.mapTo(HashSet()) { it.id }, AiRowState.OUTBOX)
      keep.size
    }
    refreshPending()
    return moved
  }

  /**
   * Toglie le righe (outbox e in attesa) delle share non piu' configurate: i loro risultati non hanno piu' dove andare. Da chiamare
   * all'avvio, dopo [recover]. Quante righe ha tolto.
   */
  suspend fun purge(configured: Set<String>): Int {
    val keep = configured.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    var removed = 0
    for (share in store.shares()) {
      if (share.lowercase(Locale.ROOT) in keep) continue
      locks.withLock(share) {
        stageLock.withLock {
          val rows = store.refs(share, AiRowState.OUTBOX) + store.refs(share, AiRowState.HELD)
          store.discard(share, rows.mapTo(HashSet()) { it.id })
          removed += rows.size
        }
      }
    }
    refreshPending()
    return removed
  }

  /** Vero se qualcuna di [keys] aspetta ancora nell'outbox di [share] (per `LeasedWorkUnits`: non si rilascia un'unita' a meta'). */
  suspend fun hasPending(share: String, keys: Set<ContentKey>): Boolean =
    store.refs(share, AiRowState.OUTBOX).any { it.key.contentKey in keys }

  // --- Pubblicazione ----------------------------------------------------------------------------------------------------

  /** Un segmento da scrivere: le righe vincenti (la piu' recente per chiave) e tutte quelle che consuma. */
  private class Chunk(val cls: AiClass, val rows: List<AiOutboxRow>, val consumed: List<AiOutboxRow>)

  private suspend fun flushLocked(share: String, reason: FlushReason): PublishOutcome {
    // Le righe piu' vecchie, al piu' [maxRowsPerFlush]: una pubblicazione non carica in memoria mesi di risultati in attesa.
    val rows = store.rows(share, AiRowState.OUTBOX, maxRowsPerFlush)
    if (rows.isEmpty()) {
      // Solo voci in attesa di una share pubblicabile: chi chiama sa perche' non esce niente (D8).
      val held = store.refs(share, AiRowState.HELD).isNotEmpty()
      rules.blockFor(share)?.takeIf { held }?.let { return PublishOutcome.Blocked(it) }
      // Niente da pubblicare: e' il momento giusto per ricontrollare la testa propria (R14).
      return compactIfDue(share)?.let { PublishOutcome.Published(it.version, 0, 0) } ?: PublishOutcome.NothingPending
    }
    rules.blockFor(share)?.let { block ->
      // Un blocco permanente arrivato dopo [stage] (sola lettura scoperta adesso, D31): le voci vanno in attesa, non si perdono.
      if (rules.permanentBlockFor(share) != null) {
        stageLock.withLock { store.move(share, store.refs(share, AiRowState.OUTBOX).mapTo(HashSet()) { it.id }, AiRowState.HELD) }
      }
      return PublishOutcome.Blocked(block)
    }
    val registry = identity.writableRegistry() ?: return PublishOutcome.Blocked(WriteBlock.NOT_TRUSTED)
    val own = store.ownHead(share)
    val now = clock.nowMillis()
    if (reason != FlushReason.UNIT_FINISHED && own != null) {
      val next = own.publishedAtNas + TrustLimits.HEAD_MIN_INTERVAL_MILLIS
      if (now < next) return PublishOutcome.TooSoon(next)
    }
    val plan = plan(share, rows)
    val consumedOnly = plan.second
    if (plan.first.isEmpty()) {
      // Niente da pubblicare davvero (tutto superato o identico a cio' che c'e'): si consumano le righe senza una testa nuova.
      if (consumedOnly.isNotEmpty() && own != null) {
        store.commit(share, AiPublishCommit(own, consumedOnly, emptyList(), emptyList()))
      } else if (consumedOnly.isNotEmpty()) {
        store.discard(share, consumedOnly)
      }
      return PublishOutcome.NothingPending
    }

    val fs = fileSystem(share)
    val done = ArrayList<Pair<Chunk, OwnSegment>>()
    var bytes = 0L
    var spaceFailure: RemoteFailure? = null
    for (chunk in plan.first) {
      val encoded = build(chunk)
      when (val result = nasCall(io) { fs.publishExclusive(NasLayout.aiSegment(identity.deviceId, encoded.ref.hash), encoded.bytes) }) {
        is Nas.Ok -> {
          done += chunk to OwnSegment(encoded.classed, encoded.entries)
          bytes += encoded.bytes.size
        }
        is Nas.Failed -> {
          if (!RemoteFailures.isSpace(result.failure)) return PublishOutcome.Failed(result.failure)
          spaceFailure = result.failure
          break
        }
      }
    }
    if (done.isEmpty()) return PublishOutcome.Failed(spaceFailure ?: RemoteFailure.OTHER)

    val fresh = done.map { it.second }
    val listed = (own?.segments.orEmpty() + fresh).distinctBy { it.hash }.sortedBy { it.hash }
    if (listed.size > TrustLimits.MAX_HEAD_SEGMENTS) return PublishOutcome.Failed(RemoteFailure.OTHER)
    val head = when (val result = heads.publish(fs, registry, own?.version ?: 0, listed.map { it.segment }, now)) {
      is AiHeadWriter.Result.Done -> result
      is AiHeadWriter.Result.Failed -> return PublishOutcome.Failed(result.failure)
    }
    val live = done.flatMap { (chunk, segment) ->
      chunk.rows.map { AiLiveEntry(it.staged.key, it.staged.recipe, it.staged.digest, segment.hash) }
    }
    val consumed = HashSet<Long>()
    done.forEach { (chunk, _) -> chunk.consumed.forEach { consumed += it.id } }
    // Le righe solo "superate" si consumano con la prima pubblicazione completa (nessuna resta indietro per sempre).
    if (spaceFailure == null) consumed += consumedOnly
    store.commit(share, AiPublishCommit(OwnHead(head.version, head.mark, now, listed), consumed, fresh, live))
    counters.published.addAndGet(live.size)
    if (spaceFailure == null) {
      val compacted = compactIfDue(share)
      return PublishOutcome.Published(compacted?.version ?: head.version, fresh.size, bytes)
    }
    val complete = done.map { it.first.cls.segmentClass }.toSet() - plan.first.drop(done.size).map { it.cls.segmentClass }.toSet()
    val left = store.refs(share, AiRowState.OUTBOX).size
    return PublishOutcome.Partial(complete, PendingCounts(records = 0, blobs = left, bytes = 0), spaceFailure)
  }

  /**
   * R14: la compattazione della testa propria di [share], se e' il momento: richiesta (revoca), troppi segmenti o l'ultimo controllo piu'
   * vecchio di [compactEveryMillis] (che toglie anche le voci superate: lo stesso contenuto ripubblicato in un segmento piu' nuovo). Stessi
   * cancelli della pubblicazione. Null = niente di nuovo (o il NAS non ha risposto: si riprova dopo).
   */
  private suspend fun compactIfDue(share: String): OwnHead? {
    if (rules.blockFor(share) != null) return null
    val own = store.ownHead(share) ?: return null
    val registry = identity.writableRegistry() ?: return null
    val now = clock.nowMillis()
    val asked = requests.get()
    val requested = synchronized(satisfied) { (satisfied[share.lowercase(Locale.ROOT)] ?: 0L) < asked }
    val segments = own.segments.size
    val last = compactions.last(share)
    val every = compactEveryMillis
    val due = requested || segments >= COMPACT_SEGMENTS || (every != null && (last == null || now - last >= every || now < last))
    if (!due) return null
    // I corpi si guardano solo dopo una revoca (R5): la compattazione di ogni giorno toglie le voci non piu' della share e le superate.
    val result = compaction.compact(share, fileSystem(share), registry, own, heads, now, mergeAll = segments >= COMPACT_SEGMENTS, checkBodies = requested)
    if (result is AiOwnCompaction.Result.Failed) return null
    compactions.done(share, now)
    synchronized(satisfied) { satisfied[share.lowercase(Locale.ROOT)] = asked }
    return (result as? AiOwnCompaction.Result.Done)?.head
  }

  /**
   * Il piano deterministico (una ripetizione dopo una morte produce gli stessi segmenti): per classe (`vec`, poi `ocr`), la riga
   * piu' recente per chiave; quelle con un corpo identico alla voce viva si consumano senza ripubblicarle. M3 di nuovo (difesa in
   * profondita'): le righe di chiavi non piu' della share si tolgono. Restituisce i segmenti da scrivere e le righe da consumare
   * comunque (superate o identiche).
   */
  private suspend fun plan(share: String, rows: List<AiOutboxRow>): Pair<List<Chunk>, Set<Long>> {
    val owned = content.owned(share, rows.mapTo(HashSet()) { it.staged.contentKey })
    val foreign = rows.filter { it.staged.contentKey !in owned }
    if (foreign.isNotEmpty()) {
      store.discard(share, foreign.mapTo(HashSet()) { it.id })
      counters.foreign.addAndGet(foreign.size)
    }
    val valid = rows.filter { it.staged.contentKey in owned }
    val live = store.live(share, valid.mapTo(HashSet()) { it.staged.key })
    val chunks = ArrayList<Chunk>()
    val consumedOnly = HashSet<Long>()
    for (cls in AiClass.entries) {
      val groups = valid.filter { it.staged.cls == cls }.groupBy { it.staged.contentKey }
      val winners = ArrayList<Pair<AiOutboxRow, List<AiOutboxRow>>>()
      for ((_, group) in groups) {
        val latest = group.maxBy { it.id }
        if (live[latest.staged.key]?.digest == latest.staged.digest) {
          group.forEach { consumedOnly += it.id }
        } else {
          winners += latest to group
        }
      }
      winners.sortWith { a, b -> IndexKeys.compare(IndexKeys.content(a.first.staged.contentKey), IndexKeys.content(b.first.staged.contentKey)) }
      for (group in Chunking.split(winners) { it.first.staged.size.toLong() }) {
        chunks += Chunk(cls, group.map { it.first }, group.flatMap { it.second })
      }
    }
    return chunks to consumedOnly
  }

  private fun build(chunk: Chunk): EncodedSegment =
    AiSegments.encodeBodies(chunk.cls, chunk.rows.map { it.staged.contentKey to it.staged.body })

  private suspend fun refreshPending() {
    pending.value = store.pending()
  }

  private fun AiEntry.body() = when (this) {
    is AiEntry.Vector -> body
    is AiEntry.Text -> body
  }

  companion object {
    const val DEFAULT_MAX_ROWS_PER_FLUSH: Int = 4 * TrustLimits.MAX_SEGMENT_ENTRIES

    /** R14: le voci vive si ricontrollano al piu' una volta al giorno (ora del NAS) per share. */
    const val DEFAULT_COMPACT_EVERY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** R14: oltre questi segmenti si uniscono anche quelli mezzi vuoti (il formato ne ammette 8.192 per testa). */
    const val COMPACT_SEGMENTS: Int = 512

  }
}
