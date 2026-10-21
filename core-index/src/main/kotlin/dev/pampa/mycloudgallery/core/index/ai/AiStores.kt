package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.index.replica.store.HeadRow
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.index.store.OwnHead
import dev.pampa.mycloudgallery.core.index.store.OwnSegment
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import java.util.Locale
import java.util.TreeMap

// Le PORTE di persistenza dei dati AI dell'indice condiviso (Fase 6, P2; D7, D8, D37). Lo stato vive nel database dello schema v5
// (P1): gli adattatori Room li lega P11 (o P1, se consegna prima). Qui le firme e le implementazioni in memoria, che rispettano lo
// stesso contratto e che i test usano anche come "database che sopravvive alla morte del processo".
//
// Regola d'oro (come le porte della Fase 4): **ogni metodo e' una transazione**. Le share si passano gia' come chiavi
// (`share.lowercase(Locale.ROOT)`): Samba non distingue le maiuscole.
//
// Tabelle attese (schema v5, D37):
// - `ai_pending_publish(id PK autoincrement, share, state, cls, content_key, recipe, body BLOB, digest)`: l'outbox della testa `ai`
//   (state = `outbox`) e i risultati in attesa di una share pubblicabile (state = `held`, D8): una tabella sola, cosi' spostare una
//   voce e' un UPDATE;
// - `ai_own_head(share PK, version, head_hash, published_at, segments)` e `ai_own_segment(share, hash, cls, size, entries)`: la testa
//   `ai` propria e i suoi segmenti;
// - `ai_own_live(share, cls, content_key, recipe, digest, segment, PRIMARY KEY(share, cls, content_key))`: la voce viva di ogni
//   (classe, chiave) nelle teste proprie (per non ripubblicare un corpo identico);
// - `ai_replica_head(share, device, version, content_hash, registry_version, created_at, PK(share, device))` e
//   `ai_replica_segment(share, device, hash, cls, size, state, PK(share, device, hash))`;
// - `ai_replica_entry(share, device, segment, cls, content_key, recipe, digest, body BLOB, PK(share, device, segment, cls,
//   content_key))`, indice su `(share, cls, content_key)`: i candidati della fusione (D7);
// - `ai_replica_applied(share, cls, content_key, device, recipe, digest, PK(share, cls, content_key))`: il vincitore consegnato
//   all'indice locale, e `ai_replica_dirty(share, cls, content_key, PK)`: le chiavi da rifondere.

// =====================================================================================================================
// Pubblicazione
// =====================================================================================================================

/** Dove sta una voce propria non ancora pubblicata. */
enum class AiRowState(override val code: String) : StableCode {
  /** Nell'outbox: si pubblica al prossimo [AiIndexPublisher.flush] permesso. */
  OUTBOX("outbox"),

  /** In attesa di una share pubblicabile (D8, `ai_pending_publish`): F4-D31 su una share vera, area in sola lettura. */
  HELD("held"),
}

/**
 * Una voce propria da pubblicare: classe, chiave di contenuto intera, ricetta e corpo **gia' codificato** (con i controlli di chi
 * legge, `AiSegments.bodyOrNull`). Il corpo si conserva cosi' com'e': pubblicarlo dopo non richiede di ricalcolare niente (D8).
 */
class AiStaged(val cls: AiClass, val contentKey: ContentKey, val recipe: Int, body: ByteArray) {
  private val raw: ByteArray = body.copyOf()

  init {
    require(recipe in 1..255 && raw.isNotEmpty()) { "Voce non valida." }
  }

  val body: ByteArray get() = raw.copyOf()

  val size: Int get() = raw.size

  /** L'impronta del corpo (`AiSegments.digest`). */
  val digest: String = AiSegments.digest(raw)

  val key: AiKey get() = AiKey(contentKey, cls)

  override fun toString(): String = "AiStaged(${cls.wire}, r$recipe, ${raw.size} B)"
}

/** Una riga del pubblicatore: [id] cresce con l'ordine di arrivo (per (classe, chiave) vince la piu' recente). */
class AiOutboxRow(val id: Long, val state: AiRowState, val staged: AiStaged) {
  override fun toString(): String = "AiOutboxRow($id, ${state.code}, $staged)"
}

/** Una riga del pubblicatore senza il corpo (per decidere senza caricare i byte: doppioni, rilascio, conteggi). */
data class AiRowRef(val id: Long, val key: AiKey, val digest: String)

/** La voce viva di (classe, chiave) nella testa `ai` propria: il segmento che la contiene, la ricetta e l'impronta del corpo. */
data class AiLiveEntry(val key: AiKey, val recipe: Int, val digest: String, val segment: String)

/** L'esito di una pubblicazione `ai`, da applicare **in una transazione**. */
class AiPublishCommit(
  val head: OwnHead,
  /** Le righe dell'outbox consumate (pubblicate o superate da una piu' recente o identiche alla voce viva). */
  val consumed: Set<Long>,
  val newSegments: List<OwnSegment>,
  val live: List<AiLiveEntry>,
)

/** Lo stato della pubblicazione dei dati AI di questo dispositivo, per share. */
interface AiPublishStore {
  /** Una transazione: righe nuove in [state], con id crescenti. */
  suspend fun stage(share: String, state: AiRowState, entries: List<AiStaged>): List<AiOutboxRow>

  /** Le righe di [share] in [state] con i corpi, in ordine di id, al piu' [limit] (le piu' vecchie). */
  suspend fun rows(share: String, state: AiRowState, limit: Int = Int.MAX_VALUE): List<AiOutboxRow>

  /** Le righe di [share] in [state] senza i corpi, in ordine di id. */
  suspend fun refs(share: String, state: AiRowState): List<AiRowRef>

  /** Sposta le righe [ids] di [share] in [to] (outbox ↔ in attesa). */
  suspend fun move(share: String, ids: Set<Long>, to: AiRowState)

  /** Toglie righe che non si pubblicheranno mai (M3 non piu' vera, corpo rovinato, share non piu' configurata). */
  suspend fun discard(share: String, ids: Set<Long>)

  /** Quante righe aspettano, in tutte le share. */
  suspend fun pending(): AiPendingCounts

  /** La testa `ai` propria corrente in [share], o null se non vi ha mai pubblicato. */
  suspend fun ownHead(share: String): OwnHead?

  /** Le voci vive di [keys] nelle teste proprie di [share]. */
  suspend fun live(share: String, keys: Collection<AiKey>): Map<AiKey, AiLiveEntry>

  /** Applica [commit] in una transazione. */
  suspend fun commit(share: String, commit: AiPublishCommit)

  /** Le share (come chiavi) con righe, in qualunque stato. */
  suspend fun shares(): Set<String>
}

/** [AiPublishStore] in memoria: ogni metodo e' atomico (sincronizzato, calcola tutto prima di cambiare qualcosa). */
class InMemoryAiPublishStore : AiPublishStore {

  private class ShareState {
    val rows = TreeMap<Long, AiOutboxRow>()
    var head: OwnHead? = null
    val live = HashMap<AiKey, AiLiveEntry>()
  }

  private val lock = Any()
  private var nextId = 1L
  private val shares = LinkedHashMap<String, ShareState>()

  /** Quante transazioni di scrittura sono state applicate (per i test di "morte a ogni passo"). */
  @Volatile var transactions: Int = 0
    private set

  private fun state(share: String): ShareState = shares.getOrPut(share.lowercase(Locale.ROOT)) { ShareState() }

  override suspend fun stage(share: String, state: AiRowState, entries: List<AiStaged>): List<AiOutboxRow> = synchronized(lock) {
    val s = state(share)
    var id = nextId
    val rows = entries.map { AiOutboxRow(id++, state, it) }
    nextId = id
    rows.forEach { s.rows[it.id] = it }
    transactions++
    rows
  }

  override suspend fun rows(share: String, state: AiRowState, limit: Int): List<AiOutboxRow> = synchronized(lock) {
    state(share).rows.values.asSequence().filter { it.state == state }.take(limit).toList()
  }

  override suspend fun refs(share: String, state: AiRowState): List<AiRowRef> = synchronized(lock) {
    state(share).rows.values.filter { it.state == state }.map { AiRowRef(it.id, it.staged.key, it.staged.digest) }
  }

  override suspend fun move(share: String, ids: Set<Long>, to: AiRowState): Unit = synchronized(lock) {
    val s = state(share)
    for (id in ids) s.rows[id]?.let { s.rows[id] = AiOutboxRow(id, to, it.staged) }
    transactions++
  }

  override suspend fun discard(share: String, ids: Set<Long>): Unit = synchronized(lock) {
    val s = state(share)
    ids.forEach { s.rows.remove(it) }
    transactions++
  }

  override suspend fun pending(): AiPendingCounts = synchronized(lock) {
    val all = shares.values.flatMap { it.rows.values }
    AiPendingCounts(staged = all.count { it.state == AiRowState.OUTBOX }, waitingForShare = all.count { it.state == AiRowState.HELD })
  }

  override suspend fun ownHead(share: String): OwnHead? = synchronized(lock) { state(share).head }

  override suspend fun live(share: String, keys: Collection<AiKey>): Map<AiKey, AiLiveEntry> = synchronized(lock) {
    val s = state(share)
    keys.mapNotNull { k -> s.live[k]?.let { k to it } }.toMap()
  }

  override suspend fun commit(share: String, commit: AiPublishCommit): Unit = synchronized(lock) {
    val s = state(share)
    commit.consumed.forEach { s.rows.remove(it) }
    s.head = commit.head
    commit.live.forEach { s.live[it.key] = it }
    transactions++
  }

  override suspend fun shares(): Set<String> = synchronized(lock) { shares.filterValues { it.rows.isNotEmpty() }.keys.toSet() }
}

// =====================================================================================================================
// Replica
// =====================================================================================================================

/**
 * Un segmento `ai` di un dispositivo in una share: [SegmentState.APPLIED], `PENDING` (budget del giro), `MISSING`, `REJECTED`.
 * [entries] e' quante voci ha dato (applicato): il tetto delle voci per dispositivo e share.
 */
data class AiSegmentRow(val deviceId: String, val hash: String, val cls: AiClass, val size: Long, val state: SegmentState, val entries: Int = 0) {
  override fun toString(): String = "AiSegmentRow(${cls.wire}, $state)"
}

/** Una voce di un segmento `ai` nella replica: autore, segmento, (chiave, classe), ricetta, impronta e corpo come pubblicato. */
class AiReplicaEntry(val deviceId: String, val segment: String, val key: AiKey, val recipe: Int, val digest: String, body: ByteArray) {
  private val raw: ByteArray = body.copyOf()

  val body: ByteArray get() = raw.copyOf()

  val size: Int get() = raw.size

  val candidate: AiCandidate get() = AiCandidate(deviceId, segment, key, recipe, digest)

  override fun toString(): String = "AiReplicaEntry(${key.cls.wire}, r$recipe)"
}

/** Un candidato alla fusione (D7), senza il corpo. */
data class AiCandidate(val deviceId: String, val segment: String, val key: AiKey, val recipe: Int, val digest: String)

/** Il vincitore consegnato all'indice locale per (chiave, classe): autore, ricetta e impronta del corpo. */
data class AiAppliedMark(val key: AiKey, val deviceId: String, val recipe: Int, val digest: String)

/**
 * L'applicazione di una testa `ai` accettata (o di segmenti scaricati dopo, con la stessa testa), **atomica**: salva la testa
 * ([head], col `HeadMark`), sostituisce l'elenco dei segmenti del dispositivo con [segments], inserisce le voci dei segmenti appena
 * scaricati ([entries], per impronta), toglie i segmenti non piu' elencati con le loro voci e segna "da rifondere" ogni chiave toccata.
 */
class AiHeadApplication(val head: HeadRow, val segments: List<AiSegmentRow>, val entries: Map<String, List<AiReplicaEntry>>)

/** La replica dei dati AI degli altri dispositivi (e delle proprie teste rilette), con lo stato della consegna all'indice locale. */
interface AiReplicaStore {
  suspend fun heads(share: String): List<HeadRow>

  suspend fun segments(share: String, deviceId: String): List<AiSegmentRow>

  /** Applica una testa (o segmenti scaricati dopo) in una transazione; restituisce le chiavi toccate (gia' segnate da rifondere). */
  suspend fun applyHead(application: AiHeadApplication): Set<AiKey>

  /** Toglie tutti i dati di [deviceId] in [share] (revocato, perso, sconosciuto); le chiavi toccate diventano da rifondere. */
  suspend fun forgetDevice(share: String, deviceId: String): Set<AiKey>

  /** Toglie tutti i dati di [share], consegne comprese (E-I8). */
  suspend fun forgetShare(share: String)

  /** I candidati di [keys] (di ogni dispositivo e segmento). */
  suspend fun candidates(share: String, keys: Collection<AiKey>): List<AiCandidate>

  /** Il corpo di un candidato, o null se non c'e' piu'. */
  suspend fun body(share: String, candidate: AiCandidate): ByteArray?

  /** Tutte le (chiave, classe) con almeno un candidato in [share]. */
  suspend fun keys(share: String): Set<AiKey>

  /** Le chiavi da rifondere di [share]. */
  suspend fun dirty(share: String): Set<AiKey>

  suspend fun markDirty(share: String, keys: Collection<AiKey>)

  /** I vincitori consegnati all'indice locale per [keys]. */
  suspend fun applied(share: String, keys: Collection<AiKey>): Map<AiKey, AiAppliedMark>

  /** Tutti i vincitori consegnati di [share]. */
  suspend fun allApplied(share: String): List<AiAppliedMark>

  /** Una transazione: i vincitori di [keys] diventano [marks] (le chiavi senza segno escono) e [keys] non sono piu' da rifondere. */
  suspend fun commitApplied(share: String, keys: Collection<AiKey>, marks: List<AiAppliedMark>)

  /** Le share con dati. */
  suspend fun shares(): Set<String>
}

/** [AiReplicaStore] in memoria: una transazione per chiamata (`synchronized`). */
class InMemoryAiReplicaStore : AiReplicaStore {

  private class ShareState {
    val heads = HashMap<String, HeadRow>()
    val segments = HashMap<String, LinkedHashMap<String, AiSegmentRow>>()

    /** (dispositivo, segmento) → voci. */
    val entries = HashMap<Pair<String, String>, List<AiReplicaEntry>>()

    /** (chiave, classe) → voci di ogni dispositivo e segmento. */
    val byKey = HashMap<AiKey, MutableList<AiReplicaEntry>>()
    val dirty = HashSet<AiKey>()
    val applied = HashMap<AiKey, AiAppliedMark>()
  }

  private val lock = Any()
  private val shares = HashMap<String, ShareState>()

  /** Quante transazioni di scrittura (per i test). */
  @Volatile var transactions: Int = 0
    private set

  private fun state(share: String): ShareState = shares.getOrPut(share.lowercase(Locale.ROOT)) { ShareState() }

  override suspend fun heads(share: String): List<HeadRow> = synchronized(lock) {
    state(share).heads.values.sortedBy { it.deviceId }
  }

  override suspend fun segments(share: String, deviceId: String): List<AiSegmentRow> = synchronized(lock) {
    state(share).segments[deviceId]?.values?.toList().orEmpty()
  }

  override suspend fun applyHead(application: AiHeadApplication): Set<AiKey> = synchronized(lock) {
    val head = application.head
    val s = state(head.share)
    val touched = HashSet<AiKey>()
    val listed = application.segments.associateBy { it.hash }
    val current = s.segments[head.deviceId].orEmpty()
    for (hash in current.keys - listed.keys) dropSegment(s, head.deviceId, hash, touched)
    for ((hash, rows) in application.entries) {
      // Un segmento riscaricato non raddoppia le voci.
      dropSegment(s, head.deviceId, hash, touched)
      s.entries[head.deviceId to hash] = rows
      for (row in rows) {
        s.byKey.getOrPut(row.key) { ArrayList(1) } += row
        touched += row.key
      }
    }
    s.segments[head.deviceId] = LinkedHashMap(listed)
    s.heads[head.deviceId] = head
    s.dirty += touched
    transactions++
    touched
  }

  private fun dropSegment(s: ShareState, device: String, hash: String, touched: MutableSet<AiKey>) {
    val rows = s.entries.remove(device to hash) ?: return
    for (row in rows) {
      val list = s.byKey[row.key] ?: continue
      list.removeAll { it.deviceId == device && it.segment == hash }
      if (list.isEmpty()) s.byKey.remove(row.key)
      touched += row.key
    }
  }

  override suspend fun forgetDevice(share: String, deviceId: String): Set<AiKey> = synchronized(lock) {
    val s = state(share)
    val touched = HashSet<AiKey>()
    s.segments.remove(deviceId)?.keys?.forEach { dropSegment(s, deviceId, it, touched) }
    s.entries.keys.filter { it.first == deviceId }.forEach { dropSegment(s, deviceId, it.second, touched) }
    s.heads.remove(deviceId)
    s.dirty += touched
    transactions++
    touched
  }

  override suspend fun forgetShare(share: String): Unit = synchronized(lock) {
    shares.remove(share.lowercase(Locale.ROOT))
    transactions++
  }

  override suspend fun candidates(share: String, keys: Collection<AiKey>): List<AiCandidate> = synchronized(lock) {
    val s = state(share)
    keys.flatMap { k -> s.byKey[k].orEmpty().map { it.candidate } }
  }

  override suspend fun body(share: String, candidate: AiCandidate): ByteArray? = synchronized(lock) {
    state(share).entries[candidate.deviceId to candidate.segment]?.firstOrNull { it.key == candidate.key }?.body
  }

  override suspend fun keys(share: String): Set<AiKey> = synchronized(lock) { state(share).byKey.keys.toSet() }

  override suspend fun dirty(share: String): Set<AiKey> = synchronized(lock) { state(share).dirty.toSet() }

  override suspend fun markDirty(share: String, keys: Collection<AiKey>): Unit = synchronized(lock) {
    state(share).dirty += keys
    transactions++
  }

  override suspend fun applied(share: String, keys: Collection<AiKey>): Map<AiKey, AiAppliedMark> = synchronized(lock) {
    val s = state(share)
    keys.mapNotNull { k -> s.applied[k]?.let { k to it } }.toMap()
  }

  override suspend fun allApplied(share: String): List<AiAppliedMark> = synchronized(lock) { state(share).applied.values.toList() }

  override suspend fun commitApplied(share: String, keys: Collection<AiKey>, marks: List<AiAppliedMark>): Unit = synchronized(lock) {
    val s = state(share)
    keys.forEach { s.applied.remove(it) }
    marks.forEach { s.applied[it.key] = it }
    s.dirty -= keys.toSet()
    transactions++
  }

  override suspend fun shares(): Set<String> = synchronized(lock) { shares.keys.toSet() }
}
