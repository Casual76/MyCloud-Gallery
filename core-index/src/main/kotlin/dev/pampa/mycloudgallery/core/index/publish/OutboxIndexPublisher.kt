package dev.pampa.mycloudgallery.core.index.publish

import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.IndexPublisher
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.OwnIndexSink
import dev.pampa.mycloudgallery.core.index.contract.PendingCounts
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.store.IndexPublishStore
import dev.pampa.mycloudgallery.core.index.store.LiveBlob
import dev.pampa.mycloudgallery.core.index.store.LiveRecord
import dev.pampa.mycloudgallery.core.index.store.OutboxBlob
import dev.pampa.mycloudgallery.core.index.store.OutboxBlobStore
import dev.pampa.mycloudgallery.core.index.store.OutboxRecord
import dev.pampa.mycloudgallery.core.index.store.OutboxSnapshot
import dev.pampa.mycloudgallery.core.index.store.OwnHead
import dev.pampa.mycloudgallery.core.index.store.OwnSegment
import dev.pampa.mycloudgallery.core.index.store.PublishCommit
import dev.pampa.mycloudgallery.core.index.store.StagedBlob
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.BlobEntry
import dev.pampa.mycloudgallery.core.trust.data.EncodedSegment
import dev.pampa.mycloudgallery.core.trust.data.FramesBody
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.MetaRecords
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentEntry
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il pubblicatore dell'indice di questo dispositivo (docs/fase4-progetto.md §2.1 "Pubblicazione"; formato-nas.md §5.5
 * M3, §5.5-bis). Uno per processo.
 *
 * **Outbox che sopravvive alla morte del processo a ogni passo.**
 * - [stage]: byte dei blob nel pacco ([OutboxBlobStore], durevoli) → righe e `seq` nel database in una transazione →
 *   replica locale ([OwnIndexSink]) → riga segnata "passata alla replica". Una morte fra due passi lascia byte orfani
 *   (tolti da [recover]) o righe non ancora nella replica (ripassate da [recover]); mai una riga senza i suoi byte.
 * - [flush]: si leggono le righe (niente si toglie) → segmenti con nome = impronta (una ripetizione trova "esiste gia'"
 *   con gli stessi byte = successo) → testa `version + 1` con **tutti** i segmenti validi (D5) → in una transazione
 *   testa nuova, righe consumate (solo quelle lette), record e blob vivi → byte dei blob tolti dal pacco. Una morte
 *   prima del commit lascia l'outbox intatta: il giro dopo ripubblica gli stessi segmenti e, se trova gia' sul NAS una
 *   testa propria con gli stessi segmenti, la **adotta** invece di scriverne un'altra (pubblicazione idempotente).
 *
 * **Regole** (prima di firmare): D31 dentro ([IndexWriteRules]) e poi il cancello di :app (congelamento, salto
 * dell'ora, area in sola lettura, emergenza); dispositivo approvato; una testa ogni 5 minuti salvo la fine di un'unita'
 * (M3); M3 sulla provenienza in [stage] (rifiuto) e di nuovo in [flush] (scarto). E-I6: con il NAS pieno si pubblica
 * per priorita' (`meta` > `thumbS` > `thumbM` > `analysis` > `frames` > `hq`) e il resto aspetta.
 *
 * **Niente arretrato senza tetto** (R2 della revisione 4A):
 * - con un blocco **permanente** (D31 su una share vera, area in sola lettura: [IndexWriteRules.permanentBlockFor]) [stage]
 *   non mette niente nell'outbox: record e immagini vanno solo alla replica locale ([OwnIndexSink], che tiene le classi del
 *   livello di qualita': mai l'anteprima di analisi ne' i fotogrammi). Le miniature servono alla Libreria anche senza
 *   pubblicare; quando D31 si spegnera' la preparazione si rifa' per pubblicare (nessun arretrato vecchio di mesi);
 * - con un blocco temporaneo l'outbox aspetta, ma i byte dei blob in attesa non superano [maxOutboxBlobBytes]: oltre, le
 *   immagini nuove vanno solo alla replica locale (gli altri dispositivi, che non credono a una classe senza il suo blob, R5,
 *   le prepareranno loro);
 * - [purgeOutbox] toglie l'outbox delle share non piu' configurate o non piu' pubblicabili (all'avvio).
 */
class OutboxIndexPublisher(
  private val identity: IndexIdentity,
  private val clock: NasClock,
  private val fileSystem: (String) -> NasFileSystem,
  private val store: IndexPublishStore,
  private val blobStore: OutboxBlobStore,
  private val catalog: ShareCatalog,
  private val rules: IndexWriteRules,
  private val sink: OwnIndexSink,
  private val locks: ShareLocks = ShareLocks(),
  private val io: CoroutineDispatcher = Dispatchers.IO,
  /** Byte di blob al massimo per pubblicazione (i record `meta` passano sempre): una pubblicazione non dura ore. */
  private val maxBlobBytesPerFlush: Long = DEFAULT_MAX_BLOB_BYTES_PER_FLUSH,
  /** R2: byte di blob in attesa al massimo, in tutte le share; oltre, le immagini nuove restano solo nella replica locale. */
  private val maxOutboxBlobBytes: Long = DEFAULT_MAX_OUTBOX_BLOB_BYTES,
) : IndexPublisher {

  /** Contatori per la Diagnostica (solo numeri). */
  class Stats {
    /** Cambiamenti scartati perche' il timbro non e' piu' quello del catalogo (una scansione e' passata nel frattempo). */
    val staleChanges = AtomicInteger()

    /** Lapidi per percorsi mai pubblicati qui (anche dopo una compattazione che le ha gia' tolte): scartate. */
    val pointlessRemoves = AtomicInteger()

    /** Record o blob che chi legge rifiuterebbe (oltre i tetti, formato non ammesso): mai pubblicati. */
    val unpublishable = AtomicInteger()

    /** Righe tolte alla pubblicazione: byte spariti dal pacco o blob senza un record della share (M3). */
    val discardedAtFlush = AtomicInteger()

    /** Nomi di testa occupati da file non validi, scavalcati. */
    val junkSkipped = AtomicInteger()

    /** R2: cambiamenti tenuti solo nella replica locale perche' la share non e' pubblicabile (D31, sola lettura). */
    val localOnly = AtomicInteger()

    /** R2: immagini tenute solo nella replica locale perche' l'outbox era al tetto. */
    val overCap = AtomicInteger()
  }

  val stats = Stats()

  override val pending: MutableStateFlow<PendingCounts> = MutableStateFlow(PendingCounts.EMPTY)

  private val heads = OwnHeadWriter(identity, io, stats.junkSkipped)

  /** [stage] e [recover] non si sovrappongono: [recover] toglie i byte che nessuna riga cita. */
  private val stageLock = Mutex()

  /**
   * Da chiamare all'avvio, prima di ogni [stage]: toglie i byte orfani di una morte durante [stage] o dopo un commit,
   * ripassa alla replica locale le righe che non c'erano arrivate, aggiorna [pending].
   */
  suspend fun recover() {
    stageLock.withLock {
      blobStore.retainOnly(store.referencedHandles())
      for (snapshot in store.unsunk()) {
        val puts = snapshot.blobs.mapNotNull { row -> blobStore.read(row.blob.handle)?.let { toBlobPut(row.blob, it) } }
        sink.applyOwn(snapshot.share, snapshot.records.map { it.record }, puts)
        store.markSunk(snapshot.share, snapshot.records.mapTo(HashSet()) { it.id }, snapshot.blobs.mapTo(HashSet()) { it.id })
      }
    }
    refreshPending()
  }

  override suspend fun stage(share: String, changes: List<MetaChange>, blobs: List<BlobPut>) {
    if (changes.isEmpty() && blobs.isEmpty()) return
    require(isShareName(share)) { "Nome di share non valido." }
    val accepted = checkProvenance(share, changes, blobs)
    val records = accepted.changes.filter { change ->
      encodable(change).also { if (!it) stats.unpublishable.incrementAndGet() }
    }
    val bodies = accepted.blobs.mapNotNull { put ->
      val body = bodyOf(put)?.takeIf { IndexSegments.admits(put.cls, it) }
      if (body == null) stats.unpublishable.incrementAndGet()
      body?.let { put to it }
    }
    if (records.isEmpty() && bodies.isEmpty()) return
    if (rules.permanentBlockFor(share) != null) {
      // R2: share non pubblicabile: solo la replica locale, con i `seq` della share (nessuna riga, nessun byte nell'outbox).
      stageLock.withLock {
        val first = store.reserveSeqs(share, records.size)
        val local = records.mapIndexed { i, change -> recordOf(change, first + i) }
        sink.applyOwn(share, local, bodies.map { it.first })
      }
      stats.localOnly.addAndGet(records.size + bodies.size)
      return
    }
    stageLock.withLock {
      // R2: il tetto dei byte in attesa (l'ultimo conteggio, aggiornato alla fine di ogni stage, flush e recover). Oltre, le
      // immagini vanno solo alla replica locale (i record, piccoli, passano).
      val room = maxOutboxBlobBytes - pending.value.bytes
      var used = 0L
      val queued = ArrayList<Pair<BlobPut, ByteArray>>(bodies.size)
      for (item in bodies) {
        if (used + item.second.size > room) continue
        used += item.second.size
        queued += item
      }
      if (queued.size < bodies.size) stats.overCap.addAndGet(bodies.size - queued.size)
      val staged = queued.map { (put, body) -> StagedBlob(put.cls, put.contentKey, body.size, blobStore.write(share, put.cls, body)) }
      val batch = store.stage(share, records, staged)
      sink.applyOwn(share, batch.records.map { it.record }, bodies.map { it.first })
      store.markSunk(share, batch.records.mapTo(HashSet()) { it.id }, batch.blobs.mapTo(HashSet()) { it.id })
    }
    refreshPending()
  }

  /**
   * R2: toglie l'outbox (righe e byte) delle share che non vanno piu' pubblicate: non piu' configurate, o con un blocco
   * permanente ([IndexWriteRules.permanentBlockFor]). Quello che c'era e' gia' nella replica locale (passato da [stage]).
   * Da chiamare all'avvio, dopo [recover]. Restituisce quante righe ha tolto.
   */
  suspend fun purgeOutbox(configured: Set<String>): Int {
    val keep = configured.mapTo(HashSet()) { it.lowercase(java.util.Locale.ROOT) }
    var removed = 0
    for (share in store.outboxShares()) {
      if (share.lowercase(java.util.Locale.ROOT) in keep && rules.permanentBlockFor(share) == null) continue
      locks.withLock(share) {
        stageLock.withLock {
          val snapshot = store.outbox(share)
          store.discard(share, snapshot.records.mapTo(HashSet()) { it.id }, snapshot.blobs.mapTo(HashSet()) { it.id })
          blobStore.delete(snapshot.blobs.map { it.blob.handle })
          removed += snapshot.records.size + snapshot.blobs.size
        }
      }
    }
    refreshPending()
    return removed
  }

  /** Vero se qualcuna di [keys] aspetta ancora nell'outbox di [share] (record presenti o blob): per `LeasedWorkUnits`. */
  suspend fun hasPending(share: String, keys: Set<ContentKey>): Boolean {
    val snapshot = store.outbox(share)
    return snapshot.blobs.any { it.blob.contentKey in keys } ||
      snapshot.records.any { (it.record as? MetaRecord.Present)?.file?.contentKey in keys }
  }

  override suspend fun flush(share: String, reason: FlushReason): PublishOutcome = locks.withLock(share) {
    flushLocked(share, reason).also { refreshPending() }
  }

  // --- Regola M3 ----------------------------------------------------------------------------------------------------

  private class Accepted(val changes: List<MetaChange>, val blobs: List<BlobPut>)

  /**
   * M3 (C19): ogni percorso e' un file di [share] nel catalogo locale, ogni blob ha la chiave di contenuto di un file di
   * [share]. Una violazione e' un errore di chi chiama e niente si mette nell'outbox. Una lapide vale solo per un
   * percorso di cui questo dispositivo ha gia' detto qualcosa in [share]; le altre si scartano.
   *
   * Un record il cui timbro o la cui impronta rapida non sono quelli del catalogo per quel percorso non si pubblica
   * (si scarta e si conta): o il file e' cambiato dopo che chi chiama l'aveva letto, o i dati vengono da un altro file
   * con lo stesso percorso in un'altra share (stessa dimensione e data, impronta diversa). Lo stesso per un'impronta che
   * il catalogo non conosce ancora.
   */
  private suspend fun checkProvenance(share: String, changes: List<MetaChange>, blobs: List<BlobPut>): Accepted {
    val puts = changes.filterIsInstance<MetaChange.Put>()
    val known = if (puts.isEmpty()) emptyMap() else catalog.files(share, puts.map { it.path }.distinct())
    val stale = HashSet<MetaChange.Put>()
    for (put in puts) {
      val file = known[put.path] ?: throw IndexProvenanceException("M3: un percorso che non e' un file di questa share.")
      if (!file.stamp.sameAs(put.file.stamp) || file.quickHash != put.file.quickHash) stale += put
    }
    // Una lapide per un percorso di cui questo dispositivo non ha mai detto niente in questa share non cambia lo stato
    // (non c'e' un record da togliere) e porterebbe nell'indice il nome di un file forse di un'altra share: si scarta.
    val removes = changes.filterIsInstance<MetaChange.Remove>()
    val pointless = HashSet<MetaChange.Remove>()
    if (removes.isNotEmpty()) {
      val keys = removes.mapTo(HashSet()) { IndexKeys.metaHex(it.path) }
      val known = store.knownPaths(share, keys) + puts.map { IndexKeys.metaHex(it.path) }
      removes.filterTo(pointless) { IndexKeys.metaHex(it.path) !in known }
    }
    val fresh = puts.filterNot { it in stale }.mapTo(HashSet()) { it.file.contentKey }
    val staleKeys = stale.mapTo(HashSet()) { it.file.contentKey }
    val blobKeys = blobs.mapTo(HashSet()) { it.contentKey }
    val toCheck = blobKeys - fresh
    val ownKeys = if (toCheck.isEmpty()) emptySet() else store.knownContentKeys(share, toCheck)
    val droppedKeys = HashSet<ContentKey>()
    for (key in toCheck - ownKeys) {
      if (key !in staleKeys) throw IndexProvenanceException("M3: un blob che non viene da un file di questa share.")
      droppedKeys += key
    }
    stats.staleChanges.addAndGet(stale.size)
    stats.pointlessRemoves.addAndGet(pointless.size)
    return Accepted(changes.filterNot { it in stale || it in pointless }, blobs.filterNot { it.contentKey in droppedKeys })
  }

  // --- Pubblicazione ----------------------------------------------------------------------------------------------

  private suspend fun flushLocked(share: String, reason: FlushReason): PublishOutcome {
    val snapshot = store.outbox(share)
    if (snapshot.isEmpty) return PublishOutcome.NothingPending
    rules.blockFor(share)?.let { return PublishOutcome.Blocked(it) }
    val registry = identity.writableRegistry() ?: return PublishOutcome.Blocked(WriteBlock.NOT_TRUSTED)
    val own = store.ownHead(share)
    val now = clock.nowMillis()
    if (reason != FlushReason.UNIT_FINISHED && own != null) {
      val next = own.publishedAtNas + TrustLimits.HEAD_MIN_INTERVAL_MILLIS
      if (now < next) return PublishOutcome.TooSoon(next)
    }
    val plan = plan(share, snapshot)
    if (plan.isEmpty()) return PublishOutcome.NothingPending
    val newPaths = plan.flatMap { it.records }.size
    if (store.liveRecordCount(share) + newPaths > TrustLimits.MAX_RECORDS_PER_DEVICE_SHARE) return PublishOutcome.Failed(RemoteFailure.OTHER)

    val fs = fileSystem(share)
    // Solo i riferimenti dei segmenti scritti: i byte si lasciano andare subito (in memoria al piu' un segmento).
    val done = ArrayList<Pair<Chunk, OwnSegment>>()
    val donePlanned = HashSet<Int>()
    var bytes = 0L
    var spaceFailure: RemoteFailure? = null
    for ((index, planned) in plan.withIndex()) {
      val (chunk, encoded) = build(share, planned) ?: continue
      when (val result = nasCall(io) { fs.publishExclusive(NasLayout.segmentFile(identity.deviceId, HeadKind.INDEX.wire, encoded.ref.hash), encoded.bytes) }) {
        is Nas.Ok -> {
          done += chunk to OwnSegment(encoded.classed, encoded.entries)
          donePlanned += index
          bytes += encoded.bytes.size
        }
        is Nas.Failed -> {
          if (!RemoteFailures.isSpace(result.failure)) return PublishOutcome.Failed(result.failure)
          spaceFailure = result.failure
          break
        }
      }
    }
    if (done.isEmpty()) return spaceFailure?.let { PublishOutcome.Failed(it) } ?: PublishOutcome.NothingPending

    val fresh = done.map { it.second }
    val listed = (own?.segments.orEmpty() + fresh).distinctBy { it.hash }.sortedBy { it.hash }
    if (listed.size > TrustLimits.MAX_HEAD_SEGMENTS) return PublishOutcome.Failed(RemoteFailure.OTHER)
    val head = when (val result = heads.publish(fs, registry, own?.version ?: 0, listed.map { it.segment }, now)) {
      is OwnHeadWriter.Result.Done -> result
      is OwnHeadWriter.Result.Failed -> return PublishOutcome.Failed(result.failure)
    }

    val records = ArrayList<LiveRecord>()
    val liveBlobs = ArrayList<LiveBlob>()
    for ((chunk, segment) in done) {
      chunk.records.forEach { row ->
        val r = row.record
        records += LiveRecord(IndexKeys.metaHex(r.path), r.path, r.seq, r is MetaRecord.Gone, (r as? MetaRecord.Present)?.file?.contentKey, segment.hash)
      }
      chunk.blobs.forEach { row -> liveBlobs += LiveBlob(row.blob.cls, row.blob.contentKey, segment.hash) }
    }
    val consumedBlobs = done.flatMap { it.first.consumedBlobs }
    store.commit(
      share,
      PublishCommit(
        head = OwnHead(head.version, head.mark, now, listed),
        consumedRecords = done.flatMapTo(HashSet()) { it.first.consumedRecords.map(OutboxRecord::id) },
        consumedBlobs = consumedBlobs.mapTo(HashSet()) { it.id },
        newSegments = fresh,
        records = records,
        blobs = liveBlobs,
      ),
    )
    blobStore.delete(consumedBlobs.map { it.blob.handle })
    if (spaceFailure == null) return PublishOutcome.Published(head.version, fresh.size, bytes)
    val complete = plan.indices.groupBy { plan[it].cls }.filterValues { indices -> indices.all { it in donePlanned } }.keys
    return PublishOutcome.Partial(complete, store.pending(), spaceFailure)
  }

  // --- Piano dei segmenti -------------------------------------------------------------------------------------------

  /**
   * Un segmento da scrivere: le righe vincenti (il `seq` piu' alto per percorso, la riga piu' recente per blob) e tutte
   * le righe che la sua pubblicazione consuma (anche quelle superate dalle vincenti).
   */
  private class Chunk(
    val cls: SegmentClass,
    val records: List<OutboxRecord>,
    val consumedRecords: List<OutboxRecord>,
    val blobs: List<OutboxBlob>,
    val consumedBlobs: List<OutboxBlob>,
  )

  private suspend fun plan(share: String, snapshot: OutboxSnapshot): List<Chunk> {
    val chunks = ArrayList<Chunk>()
    // Record: il seq piu' alto per percorso (piegato) vince; un record non rappresentabile si toglie con il suo gruppo.
    val groups = snapshot.records.groupBy { IndexKeys.metaHex(it.record.path) }
    val discardRecords = HashSet<Long>()
    val winners = ArrayList<Triple<OutboxRecord, ByteArray, List<OutboxRecord>>>()
    for ((_, rows) in groups) {
      val winner = rows.maxBy { it.record.seq }
      val body = MetaRecords.encodeOrNull(winner.record)
      if (body == null) {
        rows.forEach { discardRecords += it.id }
      } else {
        winners += Triple(winner, body, rows)
      }
    }
    winners.sortWith { a, b -> IndexKeys.compare(IndexKeys.meta(a.first.record.path), IndexKeys.meta(b.first.record.path)) }
    for (group in Chunking.split(winners) { it.second.size.toLong() }) {
      chunks += Chunk(SegmentClass.META, group.map { it.first }, group.flatMap { it.third }, emptyList(), emptyList())
    }

    // Blob: M3 di nuovo (difesa in profondita'), poi la riga piu' recente per (classe, chiave).
    val blobKeys = snapshot.blobs.mapTo(HashSet()) { it.blob.contentKey }
    val ownKeys = if (blobKeys.isEmpty()) emptySet() else store.knownContentKeys(share, blobKeys)
    val discardBlobs = snapshot.blobs.filter { it.blob.contentKey !in ownKeys }.mapTo(HashSet()) { it.id }
    var budget = maxBlobBytesPerFlush
    for (cls in PRIORITY) {
      val rows = snapshot.blobs.filter { it.blob.cls == cls && it.id !in discardBlobs }
      if (rows.isEmpty()) continue
      val byKey = rows.groupBy { it.blob.contentKey }
      val latest = byKey.values.map { group -> group.maxBy { it.id } to group }
        .sortedWith { a, b -> IndexKeys.compare(IndexKeys.content(a.first.blob.contentKey), IndexKeys.content(b.first.blob.contentKey)) }
      for (group in Chunking.split(latest) { it.first.blob.length.toLong() }) {
        val size = group.sumOf { it.first.blob.length.toLong() }
        if (budget <= 0) break
        budget -= size
        chunks += Chunk(cls, emptyList(), emptyList(), group.map { it.first }, group.flatMap { it.second })
      }
    }
    if (discardRecords.isNotEmpty() || discardBlobs.isNotEmpty()) {
      store.discard(share, discardRecords, discardBlobs)
      stats.discardedAtFlush.addAndGet(discardRecords.size + discardBlobs.size)
      blobStore.delete(snapshot.blobs.filter { it.id in discardBlobs }.map { it.blob.handle })
    }
    return chunks
  }

  /**
   * I byte di un segmento. I blob spariti dal pacco o rovinati si tolgono dall'outbox con il loro gruppo e restano fuori
   * dal segmento (ne' consumati ne' vivi); null se non resta niente.
   */
  private suspend fun build(share: String, chunk: Chunk): Pair<Chunk, EncodedSegment>? {
    if (chunk.cls == SegmentClass.META) {
      val entries = chunk.records.map { SegmentEntry(IndexKeys.meta(it.record.path), MetaRecords.encode(it.record)) }
      return chunk to IndexSegments.encode(SegmentClass.META, entries)
    }
    val items = ArrayList<Pair<ContentKey, ByteArray>>()
    val lost = HashSet<ContentKey>()
    for (row in chunk.blobs) {
      val bytes = blobStore.read(row.blob.handle)?.takeIf { it.size == row.blob.length && IndexSegments.admits(row.blob.cls, it) }
      if (bytes == null) lost += row.blob.contentKey else items += row.blob.contentKey to bytes
    }
    if (lost.isNotEmpty()) {
      val gone = chunk.consumedBlobs.filter { it.blob.contentKey in lost }
      store.discard(share, emptySet(), gone.mapTo(HashSet()) { it.id })
      blobStore.delete(gone.map { it.blob.handle })
      stats.discardedAtFlush.addAndGet(gone.size)
    }
    if (items.isEmpty()) return null
    val kept = Chunk(
      chunk.cls,
      emptyList(),
      emptyList(),
      chunk.blobs.filter { it.blob.contentKey !in lost },
      chunk.consumedBlobs.filter { it.blob.contentKey !in lost },
    )
    return kept to IndexSegments.blobs(chunk.cls, items)
  }

  // --- Varie ----------------------------------------------------------------------------------------------------------

  private suspend fun refreshPending() {
    pending.value = store.pending()
  }

  private fun recordOf(change: MetaChange, seq: Long): MetaRecord = when (change) {
    is MetaChange.Put -> MetaRecord.Present(change.path, seq, change.file)
    is MetaChange.Remove -> MetaRecord.Gone(change.path, seq)
  }

  private fun encodable(change: MetaChange): Boolean {
    // Con il seq piu' lungo possibile: cio' che passa qui passera' con qualunque seq.
    val record = when (change) {
      is MetaChange.Put -> MetaRecord.Present(change.path, TrustLimits.MAX_RECORD_SEQ, change.file)
      is MetaChange.Remove -> MetaRecord.Gone(change.path, TrustLimits.MAX_RECORD_SEQ)
    }
    return MetaRecords.encodeOrNull(record) != null
  }

  companion object {
    /** Un segmento di blob al massimo 12 MiB: per pubblicazione ~10 segmenti di miniature. */
    const val DEFAULT_MAX_BLOB_BYTES_PER_FLUSH: Long = 128L * 1024 * 1024

    /**
     * R2: 512 MiB di blob in attesa in tutto (circa 800 media con S, M, analisi e HQ): basta per giorni di blocco temporaneo
     * sulle share di prova, mai i GB di una notte di preparazione dell'archivio.
     */
    const val DEFAULT_MAX_OUTBOX_BLOB_BYTES: Long = 512L * 1024 * 1024

    /** E-I6: l'ordine in cui si pubblica quando lo spazio puo' finire. */
    val PRIORITY: List<SegmentClass> =
      listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.FRAMES, SegmentClass.HQ)

    internal fun isShareName(share: String): Boolean =
      share.isNotEmpty() && share.length <= TrustLimits.MAX_SHARE_NAME_CHARS && SharePath.isValidComponent(share)

    internal fun bodyOf(put: BlobPut): ByteArray? = when (put) {
      is BlobPut.Image -> put.bytes
      is BlobPut.Frames -> try {
        FramesBody.encode(put.frames.map { it.atMs to it.jpeg })
      } catch (e: IllegalArgumentException) {
        null
      }
    }

    internal fun toBlobPut(blob: StagedBlob, bytes: ByteArray): BlobPut? = when (blob.cls) {
      SegmentClass.FRAMES -> (FramesBody.read(bytes, BlobEntry(blob.contentKey, 0, bytes.size)) as? Verdict.Accepted)?.value?.let { frames ->
        BlobPut.Frames(blob.contentKey, frames.map { VideoFrame(it.atMs, bytes.copyOfRange(it.offset, it.offset + it.length)) })
      }
      in BlobPut.IMAGE_CLASSES -> BlobPut.Image(blob.cls, blob.contentKey, bytes)
      else -> null
    }
  }
}

/** Divide voci gia' ordinate in segmenti: al massimo 4.096 voci e [TrustLimits.SEGMENT_TARGET_BYTES]. */
internal object Chunking {
  fun <T> split(items: List<T>, bodySize: (T) -> Long): List<List<T>> {
    val out = ArrayList<List<T>>()
    var current = ArrayList<T>()
    var bytes = 0L
    for (item in items) {
      val size = bodySize(item)
      val wouldBe = IndexSegments.sizeOf(current.size + 1, bytes + size)
      if (current.isNotEmpty() && (current.size >= TrustLimits.MAX_SEGMENT_ENTRIES || wouldBe > TrustLimits.SEGMENT_TARGET_BYTES)) {
        out += current
        current = ArrayList()
        bytes = 0
      }
      current += item
      bytes += size
    }
    if (current.isNotEmpty()) out += current
    return out
  }
}
