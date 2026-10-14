package dev.pampa.mycloudgallery.core.index.contract

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// La REPLICA LOCALE e la sua sincronizzazione (docs/fase4-progetto.md §2.2, §2.5; D5, D6, D7, D15, D16, D17, D23).
// Contratti di P0, implementazione di P4 (`replica/`, `sync/`, `quality/`). La usano la Libreria e le miniature (P7,
// tramite ReplicaBlobs e ProvisionalThumbnails), il viewer (P8: l'HQ dalla replica), la scansione (P5: ReplicaRecords
// per adottare senza leggere il file) e le Impostazioni (P11: qualita').

/**
 * Il livello di qualita' delle anteprime tenute sul telefono (D16, Q2): quali classi si scaricano. Le anteprime di
 * analisi e i fotogrammi non si scaricano mai nella Fase 4 (Indicizzatori, Fase 6).
 */
enum class QualityLevel(override val code: String, val classes: Set<SegmentClass>) : StableCode {
  /** Risparmio: miniature S (~100 MB per 13 mila media). */
  SAVER("saver", setOf(SegmentClass.THUMB_S)),

  /** Standard (default): S + M (~380 MB). */
  STANDARD("standard", setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M)),

  /** Alta: S + M + HQ, viewer nitido anche offline (~5,6 GB). */
  HIGH("high", setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.HQ)),
  ;

  companion object {
    val DEFAULT: QualityLevel = STANDARD
  }
}

/** Una stima di spazio: dai byte delle classi elencati nelle teste, conosciuti **prima** di scaricare (E-I9, E-L6). */
data class SpaceEstimate(val bytes: Long, val items: Int) {
  init {
    require(bytes >= 0 && items >= 0) { "Stima negativa." }
  }
}

/** Dove sta un blob nella replica: un pacco immutabile in `noBackupFilesDir/replica/packs/` (D15) e il tratto. */
data class BlobLocation(val packId: Long, val offset: Long, val length: Int, val cls: SegmentClass) {
  init {
    require(offset >= 0 && length > 0) { "Posizione non valida." }
  }
}

/**
 * Dove cercare il blob di un media (R1 della revisione 4A): le share in cui il media ha una posizione, **la sua area per
 * prima**, e, se chi chiama la conosce, la chiave di contenuto **intera** del media (dal catalogo). Un blob di un'altra share
 * o con un'altra chiave intera (stessi primi 64 bit) non vale per quel media.
 */
class BlobScope(shares: List<String>, val fullKey: ContentKey? = null) {
  /** Le share in minuscolo (Samba non distingue le maiuscole), nell'ordine dato, senza doppioni. */
  val shareKeys: List<String> = shares.map { it.lowercase(java.util.Locale.ROOT) }.distinct()

  override fun toString(): String = "BlobScope(${shareKeys.size} share, chiave=${fullKey != null})"
}

/**
 * I blob delle classi d'immagine nella replica locale (D15): nessun file per miniatura, pacchi letti con mappatura in
 * memoria. Chiamate **bloccanti** e veloci (indice in memoria): si fanno dal dispatcher delle miniature.
 *
 * Solo blob **attestati** (R1 della revisione 4A): un blob vale se il dispositivo che l'ha pubblicato ha, nella stessa share,
 * un record presente con la stessa chiave intera.
 */
interface ReplicaBlobs {
  /** Il blob della chiave [contentKey] (forma ridotta, `ContentKey.short`) e della classe [cls], se c'e'. */
  fun locate(contentKey: Long, cls: SegmentClass): BlobLocation?

  /**
   * Come [locate], ma solo dentro [scope] (le share del media, la sua area per prima, e la chiave intera se nota). Chi non
   * sa distinguere le share (la replica sintetica dei benchmark) risponde come [locate].
   */
  fun locate(contentKey: Long, cls: SegmentClass, scope: BlobScope): BlobLocation? = locate(contentKey, cls)

  /**
   * I byte del blob, in sola lettura e senza copie (mappatura). Lancia `IOException` se il pacco non c'e' piu'
   * (replica azzerata nel frattempo): chi legge torna al segnaposto.
   */
  fun read(location: BlobLocation): ByteBuffer

  /** Quanto occuperebbe la replica a [quality] (per la scelta del livello, E-L6). */
  suspend fun estimate(quality: QualityLevel): SpaceEstimate
}

/** Lo stato della qualita' per le Impostazioni (§1.5, "ReplicaQualityState"). */
data class ReplicaQualityState(
  val level: QualityLevel,
  val estimates: Map<QualityLevel, SpaceEstimate>,
  /** Lo spazio occupato adesso dalla replica (pacchi + database dell'indice). */
  val currentBytes: Long,
  /** Sta scaricando o togliendo per convergere al livello scelto (al massimo 200 MB tolti per giro, §2.5). */
  val converging: Boolean,
)

/** La qualita' scelta (E-L6): cambiarla mostra la stima e converge piano, secondo le regole delle attivita'. */
interface ReplicaQuality {
  val state: StateFlow<ReplicaQualityState>

  suspend fun set(level: QualityLevel)
}

/**
 * Cosa sincronizzare in un giro (§2.2).
 *
 * @property classes le classi da scaricare oltre a `meta` (sempre): di norma quelle del [QualityLevel].
 * @property visibleOnly dati mobili con l'app aperta (D23, Q7): solo metadati e cio' che e' sullo schermo.
 */
data class SyncScope(
  val shares: Set<String>,
  val classes: Set<SegmentClass>,
  val visibleOnly: Boolean = false,
)

/** Lo stato della sincronizzazione, per il piede della Libreria (`LibraryNasStatus`) e la Diagnostica. */
sealed interface IndexSyncStatus {
  data object Idle : IndexSyncStatus

  /** Primo allineamento o arretrato (E-I9, E-I10): byte noti dalle teste. [etaMillis] null = non stimabile ancora. */
  data class Aligning(val bytesDone: Long, val bytesTotal: Long, val etaMillis: Long?) : IndexSyncStatus

  /** Tutto applicato fino a [atNasMillis] (ora del NAS). */
  data class UpToDate(val atNasMillis: Long) : IndexSyncStatus

  /** Il NAS non risponde o nega l'accesso: la replica resta com'e' (E-L4). */
  data class Unavailable(val failure: RemoteFailure) : IndexSyncStatus
}

/** Il resoconto di un giro di sincronizzazione (solo numeri, per la Diagnostica). */
data class SyncReport(
  val headsRead: Int,
  val headsApplied: Int,
  val headsRejected: Map<RejectReason, Int>,
  val segmentsDownloaded: Int,
  val segmentsRejected: Map<RejectReason, Int>,
  val segmentsMissing: Int,
  val bytesDownloaded: Long,
  val recordsApplied: Int,
  /** D7: adozioni d'identita' rifiutate perche' l'id remoto e' gia' legato qui a un contenuto diverso. */
  val identityConflicts: Int,
  /** Tutte le share e i dispositivi visti fino in fondo, entro il tempo. */
  val complete: Boolean,
) {
  companion object {
    val EMPTY = SyncReport(0, 0, emptyMap(), 0, emptyMap(), 0, 0, 0, 0, complete = true)
  }
}

/**
 * La sincronizzazione dell'indice (§2.2): teste per dispositivo, differenze, scaricamento delle classi, applicazione al
 * database nella stessa transazione del `HeadMark`, fusione D6, adozione D7. Uno per processo. [sync] rispetta [budget]
 * (tappe da 8 minuti, D22) e lascia il punto di ripresa nel database.
 */
interface IndexSync {
  val status: StateFlow<IndexSyncStatus>

  suspend fun sync(scope: SyncScope, budget: Duration): SyncReport
}

/**
 * I record dell'indice gia' nella replica, per la scansione (§2.3: "record di un dispositivo approvato con lo stesso
 * timbro → adozione senza leggere il file"). Per ogni percorso chiesto restituisce il record **vincente** secondo D6 fra
 * quelli il cui timbro coincide con [stamps] (dimensione e data uguali, `fileId` uguale se noto da entrambe le parti):
 * piu' classi preparate, poi l'id di dispositivo minore. I percorsi senza un record valido mancano dalla mappa.
 *
 * Un record adottato da qui serve a mostrare e a non rileggere (D8): l'unione con un media del telefono richiede
 * sempre l'impronta letta qui.
 */
interface ReplicaRecords {
  suspend fun matching(share: String, stamps: Map<SharePath, NasStamp>): Map<SharePath, MetaRecord.Present>
}

/**
 * Le miniature **provvisorie** (D17): la miniatura EXIF letta al rilevamento (P5) e, con l'app aperta, la decodifica
 * dell'originale come contenuto visibile (P7). Restano **solo locali**, non si pubblicano, e lasciano il posto alla
 * miniatura preparata appena la replica ce l'ha. Implementazione di P4 (nella replica, stesso azzeramento).
 */
interface ProvisionalThumbnails {
  suspend fun put(contentKey: ContentKey, jpeg: ByteArray)

  /** Bloccante e veloce, come [ReplicaBlobs.locate]. */
  fun get(contentKey: Long): ByteArray?

  suspend fun forget(contentKey: Long)
}
