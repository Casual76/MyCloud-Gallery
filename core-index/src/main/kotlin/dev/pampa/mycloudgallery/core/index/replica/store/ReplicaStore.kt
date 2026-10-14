package dev.pampa.mycloudgallery.core.index.replica.store

import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef

// La PERSISTENZA della replica locale (docs/fase4-progetto.md §2.2, D5, D15; P4). Porta scritta da P4 perche' lo schema
// v3 lo scrive P2 in parallelo: P11 la lega ai DAO di core-database (tabelle attese nel KDoc di ogni riga), questo modulo
// la usa e la prova con [MemoryReplicaStore]. Le porte di P3 (outbox) stanno in `core-index/.../store/**`: nomi diversi.
//
// Regola di tutte le operazioni che scrivono: **una chiamata = una transazione**. Cosi' l'adattatore Room e' un metodo
// `@Transaction` per operazione e un crash lascia sempre uno stato coerente (D5: lo stato di un dispositivo e' funzione
// della sua testa; il `HeadMark` si salva nella stessa transazione che applica la testa, E-I5).
//
// Le share si passano gia' in forma di chiave (minuscolo, `Locale.ROOT`): Samba non distingue le maiuscole.

/** Lo stato di un segmento elencato (o trattenuto, E-I17) per un dispositivo in una share. */
enum class SegmentState(override val code: String) : StableCode {
  /** `meta`: record nel database; immagini: pacco scritto e voci indicizzate. */
  APPLIED("applied"),

  /** Classe richiesta ma non ancora scaricato (budget finito, dati mobili, rete): il punto di ripresa. */
  PENDING("pending"),

  /** Classe non richiesta dal livello di qualita' (D16): noto dalla testa, mai scaricato o tolto dalla convergenza. */
  SKIPPED("skipped"),

  /** Elencato ma assente sul NAS (E-I17): si riprova solo con una testa nuova del dispositivo. */
  MISSING("missing"),

  /** Rifiutato dal codec o dall'impronta (E-I5, E-I14): si riprova solo con una testa nuova del dispositivo. */
  REJECTED("rejected"),
  ;

  /** Il segmento ha dati nella replica. */
  val hasData: Boolean get() = this == APPLIED

  /** Il dispositivo lo elenca ma qui non c'e' e non ci sara' finche' non ripubblica (E-I17). */
  val unavailable: Boolean get() = this == MISSING || this == REJECTED

  companion object {
    fun fromCode(code: String): SegmentState? = entries.firstOrNull { it.code == code }
  }
}

/**
 * La testa accettata di un dispositivo in una share (tabella `index_head`, PK `(share, device)`): l'anti-rollback (E-I5)
 * e cio' che serve a riscaricare i segmenti senza rileggere la testa.
 */
data class HeadRow(
  val share: String,
  val deviceId: String,
  val version: Long,
  val contentHash: String,
  val registryVersion: Long,
  val createdAtNasMillis: Long,
) {
  val mark: HeadMark get() = HeadMark(version, contentHash)
}

/**
 * Un segmento di un dispositivo in una share (tabella `index_segment`, PK `(share, device, hash)`, indice su `state`).
 *
 * @property listed elencato dalla testa corrente; `false` = **trattenuto** (E-I17): la testa nuova ha segmenti della
 *   stessa classe assenti o rifiutati, quindi i dati vecchi restano finche' una testa completa non li sostituisce.
 * @property packId il pacco (D15) di un segmento d'immagini applicato.
 */
data class SegmentRow(
  val share: String,
  val deviceId: String,
  val hash: String,
  val cls: SegmentClass,
  val size: Long,
  val state: SegmentState,
  val listed: Boolean = true,
  val packId: Long? = null,
) {
  val ref: ClassedSegment get() = ClassedSegment(cls, SegmentRef(hash, size))

  override fun toString(): String = "SegmentRow(${cls.wire}, $state, listed=$listed)"
}

/**
 * Un record `meta` di un dispositivo, con il segmento da cui viene (D5; tabella `index_record`, PK
 * `(share, device, segment, path_key)`, indice su `(share, path_key)`). Il record si salva come JSON canonico
 * (`MetaRecord.encode()` di P3, ≤ 4 KiB) piu' le colonne che servono alle query: `seq`, `gone`, `size`, `mtime`, `fid`,
 * `content_key`, `media`.
 *
 * [segment] e' l'impronta del segmento, oppure [LOCAL_SEGMENT] per cio' che questo dispositivo ha preparato o scoperto e
 * non ha ancora letto dalla propria testa (`OwnIndexSink`, D31).
 */
data class RecordRow(
  val deviceId: String,
  val segment: String,
  val pathKey: String,
  val record: MetaRecord,
) {
  override fun toString(): String = "RecordRow(seq=${record.seq}, local=${segment == LOCAL_SEGMENT})"

  companion object {
    /** Il "segmento" dei dati propri non ancora letti dalla testa: non e' un'impronta (52 caratteri base32). */
    const val LOCAL_SEGMENT: String = "local"
  }
}

/** Da dove viene un pacco. */
enum class PackKind(override val code: String) : StableCode {
  /** Un segmento d'immagini scaricato, cosi' com'e': immutabile, nome = impronta del segmento. */
  SEGMENT("segment"),

  /** Le immagini preparate qui (`OwnIndexSink`): file in sola aggiunta, chiuso a `SEGMENT_TARGET_BYTES`. */
  OWN("own"),
}

/**
 * Un pacco in `noBackupFilesDir/replica/packs/` (D15; tabella `replica_pack`, PK `id` autoincrementale, `name` unico).
 * [bytes] e' la lunghezza valida (per un pacco [PackKind.OWN] cresce a ogni aggiunta).
 */
data class PackRow(
  val id: Long,
  val name: String,
  val kind: PackKind,
  val cls: SegmentClass,
  val bytes: Long,
  val share: String?,
  val deviceId: String?,
  val segment: String?,
)

/**
 * Un blob nella replica (tabella `replica_blob`, PK `(content_key, cls, pack_id)`, indice su `(content_key_short, cls)`):
 * la chiave di contenuto intera, il pacco e il tratto. [deviceId] e [segment] servono alla scelta deterministica fra piu'
 * dispositivi che hanno preparato la stessa chiave (D6: "da qualunque dispositivo approvato").
 */
data class BlobRow(
  val contentKey: ContentKey,
  val cls: SegmentClass,
  val packId: Long,
  val offset: Long,
  val length: Int,
  val deviceId: String,
  val segment: String,
)

/**
 * Lo stato d'accesso di una share (E-I8, E-R10; tabella `replica_share_access`, PK `share`): il primo rifiuto
 * `ACCESS_DENIED`/`SHARE_MISSING` e il giorno (ora del NAS) in cui e' arrivato; [dropped] = dati gia' tolti.
 */
data class ShareAccessRow(
  val share: String,
  val failure: RemoteFailure,
  val firstFailureDay: Long,
  val dropped: Boolean,
)

/**
 * L'applicazione di una testa accettata (D5), **atomica**: salva la testa ([head], col `HeadMark`), sostituisce l'elenco
 * dei segmenti del dispositivo con [segments] (elencati e trattenuti), inserisce i record dei segmenti `meta` appena
 * scaricati ([records], per impronta), toglie i segmenti in [dropped] con i loro record, blob e pacchi, e, solo per il
 * proprio dispositivo con una testa `meta` completa, toglie i record [RecordRow.LOCAL_SEGMENT] con `seq` ≤
 * [pruneLocalUpToSeq] (gia' coperti dalla testa).
 */
class HeadApplication(
  val head: HeadRow,
  val segments: List<SegmentRow>,
  val records: Map<String, List<RecordRow>>,
  val dropped: Set<String>,
  val pruneLocalUpToSeq: Long? = null,
)

/** Cio' che una scrittura ha cambiato: i percorsi da rifondere (D6) e i pacchi da cancellare dal disco dopo il commit. */
data class StoreChange(
  val touchedPathKeys: Set<String>,
  val releasedPacks: List<PackRow>,
  val removedBlobs: List<BlobRow>,
) {
  companion object {
    val NONE = StoreChange(emptySet(), emptyList(), emptyList())
  }
}

/**
 * La replica locale nel database. Ogni metodo che scrive e' **una transazione**. P11 la implementa sui DAO di P2
 * (schema v3: `index_head`, `index_segment`, `index_record`, `replica_dirty`, `replica_pack`, `replica_blob`,
 * `replica_share_access`, `replica_setting`); [MemoryReplicaStore] e' l'implementazione in memoria.
 */
interface ReplicaStore {

  // --- Teste e segmenti -------------------------------------------------------------------------------------------

  /** Le teste accettate dei dispositivi di [share]. */
  suspend fun heads(share: String): List<HeadRow>

  /** I segmenti (elencati e trattenuti) di [deviceId] in [share]. */
  suspend fun segments(share: String, deviceId: String): List<SegmentRow>

  /** I segmenti in [states] delle classi [classes], in tutte le share (il lavoro in sospeso, la stima). */
  suspend fun segmentsWhere(states: Set<SegmentState>, classes: Set<SegmentClass>): List<SegmentRow>

  /** Applica una testa (D5) in una transazione. */
  suspend fun applyHead(application: HeadApplication): StoreChange

  /** Cambia lo stato di un segmento (scaricamento fallito, assente, rifiutato, saltato). */
  suspend fun setSegmentState(share: String, deviceId: String, hash: String, state: SegmentState)

  /**
   * Un segmento d'immagini scaricato: in una transazione registra il pacco (gia' scritto su disco), le voci e lo stato
   * [SegmentState.APPLIED]. Restituisce l'id del pacco. Se il segmento non e' piu' elencato (una testa nuova e' arrivata
   * nel frattempo) non registra niente e restituisce null: il chiamante cancella il file.
   */
  suspend fun applyBlobSegment(pack: PackRow, blobs: List<BlobRow>): Long?

  /** Toglie tutti i dati di [deviceId] in [share] (E-I15: revocato, perso, sconosciuto), testa compresa. */
  suspend fun forgetDevice(share: String, deviceId: String): StoreChange

  /** Toglie tutti i dati di [share] (E-I8), teste comprese: se torna raggiungibile si ricostruisce da zero. */
  suspend fun forgetShare(share: String): StoreChange

  // --- Record -----------------------------------------------------------------------------------------------------

  /** Tutti i record (di ogni dispositivo e segmento) dei percorsi [pathKeys] di [share]. */
  suspend fun records(share: String, pathKeys: Collection<String>): List<RecordRow>

  /**
   * Quante chiavi di contenuto distinte hanno, in un record presente, almeno una delle classi di [classMask] nella
   * maschera `prep` (la stima degli elementi di un livello, E-L6). SQL: `COUNT(DISTINCT content_key) ... WHERE gone = 0
   * AND (prep & :mask) != 0`.
   */
  suspend fun preparedContentCount(classMask: Int): Int

  /** Le chiavi dei percorsi con almeno un record in [share]. */
  suspend fun pathKeys(share: String): Set<String>

  /**
   * Le chiavi di contenuto dei record **presenti** di [deviceId] in [share] (di ogni suo segmento e dei record propri non
   * ancora riletti): R1 della revisione 4A. Un blob di quel dispositivo in quella share vale solo se la sua chiave intera e'
   * fra queste. La chiave di un record e' un SHA-256 di `qh1` e dimensione, non un campo libero: nessuno puo' fabbricarne una
   * con gli stessi primi 64 bit di un'altra. SQL: `SELECT DISTINCT content_key FROM index_record WHERE share_key = ? AND
   * device_id = ? AND gone = 0`.
   */
  suspend fun presentContentKeys(share: String, deviceId: String): Set<ContentKey>

  /**
   * I record propri ricevuti da `OwnIndexSink` ([RecordRow.LOCAL_SEGMENT]): per (dispositivo, percorso) resta il `seq`
   * piu' alto. Restituisce i percorsi toccati.
   */
  suspend fun putLocalRecords(share: String, rows: List<RecordRow>): Set<String>

  // --- Percorsi da rifondere ------------------------------------------------------------------------------------
  //
  // Ogni scrittura che tocca record ([applyHead], [forgetDevice], [putLocalRecords]) segna i percorsi toccati come "da
  // rifondere" (tabella `replica_dirty`, PK `(share, path_key)`) **nella stessa transazione**. La replica li consegna al
  // catalogo (D6, D7) e poi li toglie: un crash fra le due transazioni non lascia il catalogo indietro, il giro dopo
  // riprende da qui.

  /** Al massimo [limit] percorsi da rifondere di [share], in ordine di chiave. */
  suspend fun dirty(share: String, limit: Int): List<String>

  /** Le share con percorsi da rifondere. */
  suspend fun dirtyShares(): Set<String>

  suspend fun clearDirty(share: String, pathKeys: Collection<String>)

  // --- Pacchi e blob ----------------------------------------------------------------------------------------------

  /** Tutti i pacchi (per l'avvio e lo spazio occupato). */
  suspend fun packs(): List<PackRow>

  /** Tutte le voci (per ricostruire l'indice in memoria all'avvio, D15). */
  suspend fun blobs(): List<BlobRow>

  /** Apre un pacco proprio nuovo ([PackKind.OWN], [PackRow.id] ignorato) e ne restituisce l'id. */
  suspend fun openOwnPack(pack: PackRow): Long

  /** Aggiunge voci a un pacco proprio gia' cresciuto su disco fino a [newLength]. */
  suspend fun appendOwnBlobs(packId: Long, newLength: Long, blobs: List<BlobRow>)

  /**
   * Convergenza della qualita' (E-L6): toglie pacchi delle classi **non** in [keep], i piu' grandi prima, finche' la
   * somma non supera [maxBytes] (almeno uno se ce n'e'). I segmenti relativi tornano [SegmentState.SKIPPED].
   */
  suspend fun releasePacksOutside(keep: Set<SegmentClass>, maxBytes: Long): StoreChange

  // --- Accesso alle share e impostazioni --------------------------------------------------------------------------

  suspend fun shareAccess(share: String): ShareAccessRow?

  /** `null` = la share risponde di nuovo: si dimentica il rifiuto. */
  suspend fun setShareAccess(share: String, row: ShareAccessRow?)

  /** Il livello scelto (`replica_setting`, chiave `quality`); `null` = mai scelto. */
  suspend fun quality(): QualityLevel?

  suspend fun setQuality(level: QualityLevel)
}
