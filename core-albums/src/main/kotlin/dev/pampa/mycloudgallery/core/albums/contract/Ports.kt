package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// LE PORTE DELLA CONDIVISIONE (docs/fase7-progetto.md §1.2 "Porte", §1.4, D1, D2, D12, D28, D29; C26). core-albums non vede
// core-nasops ne' core-backup: sposta, carica e chiede il guardiano solo da qui, e :app (P13) le lega a `NasOperations` (con
// `ConfirmationIssuer` sullo stesso piano), a `IncomingUploads`/`IncomingDestination.AlbumFolder` e a `SafeCopyRequests`. Le porte che
// spostano o caricano **vogliono la conferma** nella firma (Phase7ArchitectureTest la inchioda). Non per la UI: feature-albums non le vede.

/** Un file del NAS (share e percorso relativo). Dato dell'utente: mai nei log. */
data class NasItemRef(val share: String, val path: SharePath) {
  override fun toString(): String = "NasItemRef"
}

/** Una cartella di destinazione (la cartella dell'album nell'area comune, o la cartella d'origine nell'area privata per "Riporta"). */
data class ShareFolder(val share: String, val path: SharePath) {
  override fun toString(): String = "ShareFolder"
}

/**
 * La chiave di un trasferimento, scelta dall'orchestratore e salvata nel diario **prima** di chiamare la porta: la porta e' idempotente
 * per chiave (la stessa chiave due volte = un trasferimento solo) e [TicketStatus.find] ritrova il biglietto dopo la morte del processo.
 */
@JvmInline
value class TransferKey(val value: String) {
  init {
    require(value.isNotEmpty() && value.length <= 96 && value.all { it.isLetterOrDigit() || it == '-' || it == ':' }) { "Chiave non valida." }
  }

  override fun toString(): String = "TransferKey"
}

/** Il biglietto di un trasferimento avviato (un'operazione di core-nasops o un lotto di `INCOMING`). Opaco per core-albums. */
@JvmInline
value class TransferTicket(val value: String) {
  override fun toString(): String = "TransferTicket"
}

/** Perche' una porta non ha avviato il trasferimento (niente si e' mosso). */
enum class TransferRefusal(override val code: String) : StableCode {
  NO_SPACE("no_space"),
  WRITES_BLOCKED("writes_blocked"),
  READ_ONLY("read_only"),
  NAS_UNREACHABLE("nas_unreachable"),

  /** La conferma non vale (scaduta, di un altro piano): serve un foglio nuovo. */
  CONFIRMATION_REJECTED("confirmation_rejected"),

  /** Il piano di core-nasops non torna con i file (cambiati, spariti). */
  PLAN_INVALID("plan_invalid"),
}

sealed interface TransferStart {
  data class Started(val ticket: TransferTicket) : TransferStart

  data class Refused(val reason: TransferRefusal) : TransferStart
}

/** L'esito di un file di un trasferimento finito (nell'ordine della richiesta). */
enum class TransferItemOutcome(override val code: String) : StableCode {
  /** Il file e' a destinazione, verificato, e il catalogo lo sa (porta di completamento, D29). */
  ARRIVED("arrived"),

  /** Era gia' a destinazione con la stessa chiave di contenuto (E-SH12): nessuna copia. */
  ALREADY_THERE("already_there"),

  /** E-SH18: la sorgente e' cambiata; entrambe le copie restano. */
  SOURCE_CHANGED("source_changed"),

  SOURCE_GONE("source_gone"),
  FAILED("failed"),
  CANCELLED("cancelled"),
}

/** Lo stato di un trasferimento. */
sealed interface TicketState {
  data class InProgress(val done: Int, val total: Int) : TicketState

  /** Fermo per un motivo che passa (spazio, congelamento, file aperti, NAS): si riprende da solo. */
  data class Waiting(val reason: ShareWait, val done: Int, val total: Int) : TicketState

  /** Finito: un esito per file, nell'ordine della richiesta. */
  data class Ended(val outcomes: List<TransferItemOutcome>) : TicketState

  /** La porta non conosce il biglietto (diario di core-nasops azzerato): i file si ricontrollano sul catalogo. */
  data object Unknown : TicketState
}

/**
 * Sposta [items] nella cartella [dest] (§2.5 passo 4, "Riporta" del §2.6): :app chiede a `NasOperations` un `OpRequest.Move` con [leave]
 * come `leaveBehind` (R11) e lo avvia con una `UserConfirmation` emessa da `ConfirmationIssuer` per **quel** piano, solo se [confirmation]
 * vale. Idempotente per [key]. Copia lato server, verifica SHA-256, pubblicazione esclusiva, porta di completamento, sorgente nel cestino
 * 30 giorni: tutto in core-nasops.
 */
fun interface AlbumMover {
  suspend fun move(key: TransferKey, items: List<NasItemRef>, dest: ShareFolder, leave: Set<NasItemRef>, confirmation: ShareConfirmation): TransferStart
}

/**
 * Carica nella cartella dell'album i media che stanno **solo sul telefono** (E-SH1): :app li mette nel diario del backup come `INCOMING`
 * verso `IncomingDestination.AlbumFolder`, solo se [confirmation] vale. Idempotente per [key]. Lo **stesso** `MediaId` ottiene la posizione
 * nell'area comune alla fine (D29).
 */
fun interface AlbumUploader {
  suspend fun upload(key: TransferKey, media: List<MediaId>, dest: ShareFolder, confirmation: ShareConfirmation): TransferStart
}

/** Lo stato dei trasferimenti avviati. */
interface TicketStatus {
  suspend fun status(ticket: TransferTicket): TicketState

  /** Il biglietto del trasferimento con chiave [key], se la porta l'ha avviato (anche se l'orchestratore e' morto prima di saperlo). */
  suspend fun find(key: TransferKey): TransferTicket?

  /** Ferma il trasferimento: cio' che non e' partito non parte, nessun file resta a meta'. */
  suspend fun cancel(ticket: TransferTicket)
}

/**
 * D28: chiede **subito** la copia privata dei miei media rimasti con la sola copia comune (gia' assenti dal telefono). :app la lega a
 * `SafeCopyRequests.requestNow`. Durevole e idempotente; un'eccezione vuol dire "non registrata" (si riprova).
 */
fun interface SafeCopyRequester {
  suspend fun request(media: List<MediaId>)
}

/**
 * D31: la cartella d'origine (area privata) dei miei media spostati in un album condiviso, voce personale `shared_origin` (`MEDIA_FLAG`,
 * area privata: mai nella definizione comune). [record] si chiama **prima** dello spostamento.
 */
interface SharedOrigins {
  suspend fun record(media: MediaId, origin: ShareFolder)

  suspend fun originOf(media: MediaId): ShareFolder?
}

/** Cio' che l'orchestratore e le schermate sanno della famiglia (registro firmato e impostazioni locali), legato da :app. */
interface AlbumFamily {
  /** Il mio account, null senza famiglia pronta. */
  fun me(): AccountId?

  /** La share dell'area comune del registro (`commonShare`), null se non c'e'. */
  fun commonShare(): String?

  /** La mia area privata (la share dichiarata per il mio account). */
  fun privateShare(): String?

  /** Questo dispositivo e' admin nel registro firmato (eredita', D11). */
  fun amAdmin(): Boolean

  /** D11: l'account [account] non ha piu' nessun dispositivo approvato ne' dismesso nel registro firmato. */
  fun leftFamily(account: AccountId): Boolean

  /** Il nome della cartella `Backup/<dispositivo>/` di questo dispositivo ("Riporta" senza cartella d'origine). */
  fun backupFolderName(): String
}

/** Cio' che l'orchestratore sa degli album condivisi dalla replica (P2): cartella, elementi, stato. */
interface SharedAlbumFacts {
  /** La cartella dell'album nell'area comune, null se l'album non c'e'. */
  suspend fun folder(album: AlbumId): ShareFolder?

  /** Gli elementi dell'album (attestati o no) e chi li ha aggiunti. */
  suspend fun members(album: AlbumId): Map<MediaId, AccountId>

  suspend fun state(album: AlbumId): SharedState?
}

/** Gli orologi (D36): monotono per durate e scadenze, ora del NAS per le voci (null se non ancorata). Mai l'ora del telefono. */
interface AlbumClocks {
  fun monotonicMs(): Long

  fun nasNowMs(): Long?
}
