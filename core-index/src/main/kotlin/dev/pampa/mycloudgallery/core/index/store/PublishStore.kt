package dev.pampa.mycloudgallery.core.index.store

import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.PendingCounts
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Le PORTE di persistenza della pubblicazione (P3). Lo stato vive nel database (schema v3 di P2); gli adattatori sui
// DAO li scrive P11. Qui le firme e un'implementazione in memoria (`InMemoryIndexPublishStore`) che rispetta lo stesso
// contratto e che i test usano anche come "database che sopravvive alla morte del processo".
//
// Regola d'oro: **ogni metodo e' una transazione**. Una morte del processo prima del ritorno lascia lo stato di prima,
// dopo il ritorno lo stato di dopo; mai una via di mezzo. Le share si confrontano senza maiuscole (Samba): l'adattatore
// usa come chiave `share.lowercase(Locale.ROOT)` e ricorda il nome com'e' stato scritto.
//
// Tabelle attese (P2/P11):
// - `index_outbox_record(id PK autoincrement, share, seq, path_key, record BLOB canonico, sunk)` con UNIQUE(share, seq);
// - `index_outbox_blob(id PK autoincrement, share, cls, content_key, length, handle, sunk)`; i byte nel pacco (OutboxBlobStore);
// - `index_own_share(share PK, share_name, next_seq, head_version, head_hash, head_published_at)`;
// - `index_own_segment(share, hash, cls, size, entries, listed, PRIMARY KEY(share, hash))`: anche il diario dei segmenti
//   propri per la pulizia della 4B (`OwnFilesPolicy`: "presenza dell'impronta nel diario locale");
// - `index_own_record(share, path_key, path, seq, gone, content_key, segment, PRIMARY KEY(share, path_key))`: il record
//   vivo di ogni percorso nelle teste proprie (al massimo 262.144 per share, M9);
// - `index_own_blob(share, cls, content_key, segment, PRIMARY KEY(share, cls, content_key))`.

/** Un blob in attesa: i byte stanno in [OutboxBlobStore] sotto [handle]; nel database solo la riga. */
data class StagedBlob(val cls: SegmentClass, val contentKey: ContentKey, val length: Int, val handle: String) {
  init {
    require(cls.isContentBlob && length > 0 && handle.isNotEmpty()) { "Blob in attesa non valido." }
  }
}

/** Una riga dell'outbox: un record con il suo `seq` gia' assegnato. */
data class OutboxRecord(val id: Long, val record: MetaRecord)

/** Una riga dell'outbox: un blob. */
data class OutboxBlob(val id: Long, val blob: StagedBlob)

/** Il contenuto dell'outbox di una share in un istante: le righe con i loro id (si consumano solo quelle). */
class OutboxSnapshot(val share: String, val records: List<OutboxRecord>, val blobs: List<OutboxBlob>) {
  val isEmpty: Boolean get() = records.isEmpty() && blobs.isEmpty()

  override fun toString(): String = "OutboxSnapshot(${records.size} record, ${blobs.size} blob)"
}

/** Un segmento proprio pubblicato, con quante voci contiene (per la compattazione). */
data class OwnSegment(val segment: ClassedSegment, val entries: Int) {
  val hash: String get() = segment.ref.hash
}

/** La testa corrente di questo dispositivo in una share: la memoria di chi pubblica (D5: lo stato e' la testa). */
data class OwnHead(
  val version: Long,
  val mark: HeadMark,
  /** Ora del NAS della pubblicazione: M3, una testa ogni 5 minuti. */
  val publishedAtNas: Long,
  /** In ordine di impronta, come nella testa. */
  val segments: List<OwnSegment>,
) {
  val classed: List<ClassedSegment> get() = segments.map { it.segment }
}

/**
 * Il record vivo di un percorso nelle teste proprie: quello con il `seq` piu' alto (D5), con il segmento che lo contiene.
 * [pathKey] e' `IndexKeys.metaHex(path)` (percorso piegato: due nomi diversi solo per maiuscole sono un posto solo).
 */
data class LiveRecord(
  val pathKey: String,
  val path: SharePath,
  val seq: Long,
  val gone: Boolean,
  /** La chiave di contenuto del record presente (null per una lapide). */
  val contentKey: ContentKey?,
  val segment: String,
)

/** Il blob vivo di una (classe, chiave di contenuto) nelle teste proprie: quello pubblicato per ultimo. */
data class LiveBlob(val cls: SegmentClass, val contentKey: ContentKey, val segment: String)

/** Le righe appena messe nell'outbox. */
class StagedBatch(val records: List<OutboxRecord>, val blobs: List<OutboxBlob>)

/**
 * L'esito di una pubblicazione (o di una compattazione), da applicare **in una transazione**: la testa nuova, le righe
 * dell'outbox consumate, i segmenti nuovi (anche nel diario), i record e i blob vivi che ora stanno in quei segmenti,
 * le lapidi tolte (solo la compattazione completa della classe `meta`).
 */
class PublishCommit(
  val head: OwnHead,
  val consumedRecords: Set<Long>,
  val consumedBlobs: Set<Long>,
  val newSegments: List<OwnSegment>,
  val records: List<LiveRecord>,
  val blobs: List<LiveBlob>,
  val droppedPaths: Set<String> = emptySet(),
)

/** Lo stato della pubblicazione di questo dispositivo (outbox + stato proprio pubblicato), per share. */
interface IndexPublishStore {

  /**
   * Una transazione: assegna a ogni cambiamento il `seq` successivo della share (mai riusato, anche dopo che la riga e'
   * stata consumata; oltre `TrustLimits.MAX_RECORD_SEQ` lancia `IllegalStateException`) e inserisce righe di record e
   * di blob, segnate "non ancora passate alla replica locale".
   */
  suspend fun stage(share: String, changes: List<MetaChange>, blobs: List<StagedBlob>): StagedBatch

  /** Le righe sono state passate alla replica locale (`OwnIndexSink`). */
  suspend fun markSunk(share: String, recordIds: Set<Long>, blobIds: Set<Long>)

  /** Le righe di tutte le share non ancora passate alla replica locale (ripresa dopo una morte del processo). */
  suspend fun unsunk(): List<OutboxSnapshot>

  /** L'outbox di una share adesso. */
  suspend fun outbox(share: String): OutboxSnapshot

  /** Toglie righe non pubblicabili (blob spariti o corrotti, M3 alla pubblicazione). */
  suspend fun discard(share: String, recordIds: Set<Long>, blobIds: Set<Long>)

  /** Le righe in attesa in tutte le share (per `IndexPublisher.pending`). */
  suspend fun pending(): PendingCounts

  /** La testa propria corrente in [share], o null se questo dispositivo non vi ha mai pubblicato. */
  suspend fun ownHead(share: String): OwnHead?

  /** Fra [pathKeys], quelli di cui questo dispositivo ha detto qualcosa in [share] (record vivo o riga in attesa). */
  suspend fun knownPaths(share: String, pathKeys: Set<String>): Set<String>

  /**
   * Fra [keys], le chiavi di contenuto di file di [share] secondo questo dispositivo: record presenti vivi o righe
   * `Put` in attesa. E' la regola M3 per i blob.
   */
  suspend fun knownContentKeys(share: String, keys: Set<ContentKey>): Set<ContentKey>

  /** I record vivi che stanno nei segmenti [segments]. */
  suspend fun liveRecordsIn(share: String, segments: Set<String>): List<LiveRecord>

  /** I blob vivi che stanno nei segmenti [segments]. */
  suspend fun liveBlobsIn(share: String, segments: Set<String>): List<LiveBlob>

  /** Quante voci vive ci sono in ogni segmento elencato dalla testa propria. */
  suspend fun liveCounts(share: String): Map<String, Int>

  /** Quanti record vivi (presenti o lapidi) ha questo dispositivo in [share] (tetto M9). */
  suspend fun liveRecordCount(share: String): Int

  /** Il diario: le impronte di tutti i segmenti che questo dispositivo ha pubblicato in [share]. */
  suspend fun publishedSegments(share: String): Set<String>

  /** Applica [commit] in una transazione. */
  suspend fun commit(share: String, commit: PublishCommit)

  /** Gli handle dei blob ancora citati dall'outbox (per togliere i byte orfani dopo una morte del processo). */
  suspend fun referencedHandles(): Set<String>
}

/**
 * I byte dei blob in attesa ("pacco temporaneo", `noBackupFilesDir/index-outbox/`). Si scrivono **prima** della riga
 * del database (una morte fra le due lascia solo byte orfani, che [retainOnly] toglie alla ripresa) e si tolgono
 * **dopo** il commit della pubblicazione.
 */
interface OutboxBlobStore {
  /** Scrive [bytes] in modo durevole e restituisce l'handle (unico: due blob uguali hanno due handle). */
  suspend fun write(share: String, cls: SegmentClass, bytes: ByteArray): String

  /** I byte di [handle], o null se non ci sono piu' (o sono rovinati). */
  suspend fun read(handle: String): ByteArray?

  suspend fun delete(handles: Collection<String>)

  /** Toglie tutto cio' che non e' in [referenced]. */
  suspend fun retainOnly(referenced: Set<String>)

  /** Gli handle presenti (per i test e la Diagnostica). */
  suspend fun handles(): Set<String>
}
