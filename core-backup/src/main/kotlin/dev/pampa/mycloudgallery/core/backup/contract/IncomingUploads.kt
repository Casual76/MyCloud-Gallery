package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.InputStream

// Fase 5B (docs/fase5-progetto.md §1.2 "Arrivi (5B)", §1.5 `IncomingState`, §2.7 "Ricevere condivisioni", D24, C18, E-X2, E-X8, E-S11; riga
// P0-B del §4.4): i FILE IN ARRIVO da un'altra app ("Salva sul NAS" dal foglio di condivisione di Samsung Gallery, I miei file, Chrome).
// Contratto, null-object e fake di P0-B; la copia immediata, i tetti, il tipo dai byte e il caricamento (elemento `INCOMING` del diario) sono di
// P11, le activity d'ingresso (`ShareReceiverActivity`) di P11 e P14.
//
// **Input non fidato** (C18): quel che un'altra app consegna puo' essere enorme, falso, un percorso del nostro telefono, un'infinita' di
// elementi. Quindi (1) i flussi si aprono e si copiano **subito**, finche' l'activity che li ha ricevuti vive: il permesso di lettura non dura
// oltre (`android.md` 5.3); (2) i tetti ([IncomingLimits]) valgono prima di allocare o scrivere; (3) il tipo si legge dai byte, mai dal nome o
// dal tipo dichiarato; (4) niente parte da solo: ricevere una condivisione non avvia nessun caricamento (E-S11), serve il tocco su "Salva sul
// NAS", cioe' una [PhoneConfirmation] sul [IncomingSavePlan].

/** I tetti di D24 (valgono prima di scrivere un solo byte). */
object IncomingLimits {
  /** 2 GiB per file. */
  const val MAX_FILE_BYTES: Long = 2L * 1024 * 1024 * 1024

  /** 4 GiB per condivisione. */
  const val MAX_STAGE_BYTES: Long = 4L * 1024 * 1024 * 1024

  /** 500 elementi per condivisione. */
  const val MAX_ITEMS: Int = 500

  /** Dopo la copia deve restare almeno 1 GiB libero sul telefono. */
  const val MIN_FREE_AFTER_BYTES: Long = 1L * 1024 * 1024 * 1024

  /** Sopra questa dimensione il caricamento e' un lavoro avviato dall'utente (UIDT) invece di una tappa normale. */
  const val USER_INITIATED_JOB_BYTES: Long = 256L * 1024 * 1024
}

/** Da dove viene un flusso in arrivo, come lo dice l'indirizzo che l'altra app ci ha dato (la activity lo classifica prima di aprirlo). */
enum class IncomingOrigin(override val code: String) : StableCode {
  /** Un `content://` di un'altra app: l'unico ammesso. */
  CONTENT_OF_ANOTHER_APP("content_of_another_app"),

  /** Un `file://`: l'altra app ci indica un percorso, non ci da' un flusso. Rifiutato (E-X2). */
  FILE_SCHEME("file_scheme"),

  /** Un `content://` di un provider dell'app stessa (il nostro selettore, il nostro `FileProvider`): ci manderebbe a leggere i nostri file. Rifiutato. */
  OWN_PROVIDER("own_provider"),
}

/**
 * Un flusso in arrivo, aperto da `ShareReceiverActivity` finche' ha il permesso di lettura. [open] restituisce il flusso (una volta sola,
 * chiuso da chi lo usa); [declaredSize] e [declaredMime] sono quello che l'altra app dichiara, da non credere: servono solo a rifiutare
 * presto. Il nome ([displayName]) e' un dato dell'utente: mai nei log, mai usato come nome locale (i nomi locali derivano dall'hash).
 */
class IncomingStream(
  val origin: IncomingOrigin,
  val displayName: String?,
  val declaredMime: String?,
  val declaredSize: Long?,
  val open: () -> InputStream,
) {
  override fun toString(): String = "IncomingStream(${origin.code})"
}

/** Perche' un flusso in arrivo e' stato rifiutato (E-X2: "rifiuto motivato, niente copiato"). */
enum class IncomingReject(override val code: String) : StableCode {
  FILE_SCHEME("file_scheme"),
  OWN_PROVIDER("own_provider"),

  /** Oltre i 2 GiB. */
  TOO_LARGE("too_large"),

  /** Oltre i 500 elementi di una condivisione. */
  TOO_MANY("too_many"),

  /** Oltre i 4 GiB della condivisione. */
  STAGE_TOO_BIG("stage_too_big"),

  /** Dopo la copia resterebbe meno di 1 GiB libero. */
  NO_SPACE("no_space"),

  /** Il tipo letto dai byte non e' un'immagine o un video che l'app tratta (un `.jpg` falso). */
  NOT_MEDIA("not_media"),

  /** Il flusso non si apre o si interrompe. */
  UNREADABLE("unreadable"),

  /** Nessuna implementazione legata (null-object). */
  NOT_AVAILABLE("not_available"),
}

/** Un flusso copiato in `noBackupFilesDir/incoming/`, con il nome derivato dall'hash. [name] e' interno, non il nome dell'utente. */
class IncomingItem(val name: String, val sha256: Sha256, val size: Long, val mime: String, val displayName: String?) {
  init {
    require(size >= 0) { "Dimensione negativa." }
  }

  override fun toString(): String = "IncomingItem($mime, $size B)"
}

/** L'id di una condivisione in attesa (`incoming_item.stage_id`): un token opaco, mai un percorso. */
@JvmInline
value class IncomingStageId(val value: String) {
  init {
    require(value.isNotEmpty() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '-' }) { "Id di condivisione non valido." }
  }

  override fun toString(): String = "IncomingStageId"
}

/**
 * Cio' che [IncomingUploads.stage] ha copiato e cio' che ha rifiutato. [accepted] sono gia' sul telefono (nostro, `noBackupFilesDir`);
 * [rejected] dice quanti per motivo. Una condivisione tutta rifiutata ha [accepted] vuoto.
 */
class IncomingStage(val id: IncomingStageId, val accepted: List<IncomingItem>, val rejected: Map<IncomingReject, Int>) {
  val totalBytes: Long get() = accepted.sumOf { it.size }

  /** Se il caricamento e' grande abbastanza da chiedere un lavoro avviato dall'utente. */
  val needsUserInitiatedJob: Boolean get() = totalBytes >= IncomingLimits.USER_INITIATED_JOB_BYTES

  override fun toString(): String = "IncomingStage(${accepted.size} accettati, ${rejected.values.sum()} rifiutati, $totalBytes B)"
}

/** In quale area va la condivisione. */
enum class DestinationArea(override val code: String) : StableCode {
  /** L'area privata dell'utente. */
  PRIVATE("private"),

  /** L'area comune: il foglio avvisa "Lo vedranno tutti i familiari" (D24). */
  COMMON("common"),
}

/**
 * La cartella scelta con Cartelle NAS per "Salva sul NAS": share, cartella e area. Fa parte del piano, quindi della conferma: un'altra cartella
 * vuole un altro tocco. Percorso e share sono dati dell'utente: mai nei log.
 */
class NasDestination(val share: String, val folder: SharePath, val area: DestinationArea) {
  override fun equals(other: Any?): Boolean = other is NasDestination && other.share == share && other.folder == folder && other.area == area

  override fun hashCode(): Int = (share.hashCode() * 31 + folder.hashCode()) * 31 + area.hashCode()

  override fun toString(): String = "NasDestination(${area.code})"
}

/**
 * Il piano di "Salva sul NAS": la condivisione copiata e la cartella scelta. E' il piano che la conferma firma; la UI lo costruisce per
 * mostrare il foglio (con l'avviso dell'area comune) e lo passa, con la [PhoneConfirmation], a [IncomingUploads.save].
 */
class IncomingSavePlan(val stage: IncomingStage, val destination: NasDestination) : ConfirmablePlan {

  override val requiredConfirmations: Int = 1

  /** L'area comune va detta: la UI mostra "Lo vedranno tutti i familiari". */
  val needsCommonNotice: Boolean get() = destination.area == DestinationArea.COMMON

  override val hash: PhonePlanHash = PhonePlanHash.builder("incoming-save")
    .string(stage.id.value)
    .string(destination.share)
    .string(destination.folder.value)
    .string(destination.area.code)
    .list(stage.accepted) { item ->
      string(item.sha256.hex)
      long(item.size)
    }
    .build()

  override fun toString(): String = "IncomingSavePlan(${stage.accepted.size} elementi, ${destination.area.code})"
}

/** L'esito di [IncomingUploads.save]. */
sealed interface IncomingSaveOutcome {
  /**
   * Gli elementi sono nel diario del backup (`INCOMING`) e il motore li carica: appena possibile se il NAS risponde, altrimenti restano in coda
   * con la copia locale (E-X8). [userInitiated] dice se il caricamento e' un lavoro avviato dall'utente (grande).
   */
  data class Queued(val items: Int, val userInitiated: Boolean) : IncomingSaveOutcome

  /** La conferma non vale per il piano. */
  data class Rejected(val check: PhoneConfirmationCheck) : IncomingSaveOutcome

  /** La destinazione non e' ammessa: area di sola lettura (E-N9), area dell'app, cartella che non si puo' creare. */
  data object DestinationNotAllowed : IncomingSaveOutcome

  /** La condivisione non c'e' piu' (scartata, ripulita). */
  data object StageGone : IncomingSaveOutcome

  /** Nessuna implementazione legata (null-object). */
  data object Unavailable : IncomingSaveOutcome
}

/** I file in arrivo da altre app (D24). */
interface IncomingUploads {

  /**
   * Copia **subito** i flussi di una condivisione in `noBackupFilesDir/incoming/` con i tetti di [IncomingLimits], nomi derivati dall'hash e il
   * tipo letto dai byte. Non avvia nessun caricamento. Chi chiama (`ShareReceiverActivity`) ha ancora il permesso di lettura: dopo non l'avrebbe.
   */
  suspend fun stage(streams: List<IncomingStream>): IncomingStage

  /**
   * "Salva sul NAS": solo dopo il tocco dell'utente ([confirmation], legata a `IncomingSavePlan(stage, dest)`) crea gli elementi `INCOMING` del
   * diario, nella cartella [dest], con la stessa verifica e lo stesso "mai sostituire" di ogni caricamento (nome occupato → "nome (2)").
   */
  suspend fun save(stage: IncomingStage, dest: NasDestination, confirmation: PhoneConfirmation): IncomingSaveOutcome

  /** L'utente rinuncia (o la condivisione e' vecchia): le copie locali si tolgono. */
  suspend fun discard(stage: IncomingStageId)
}
