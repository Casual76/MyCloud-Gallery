package dev.pampa.mycloudgallery.feature.backup.received

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.model.StableCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// "Ricevuti in coda" (correzioni 5B, IN-7 della verifica funzionale; docs/fase5-progetto.md §2.7 "Ricevere condivisioni", D24, E-X8): i file che
// un'altra app ha condiviso con MyCloud Gallery e che l'utente ha mandato sul NAS con "Salva sul NAS". Finche' non sono caricati il loro media
// e' invisibile (presenza `none`): la Libreria non li mostra, e questa e' l'unica schermata che dice a che punto sono ("con lo stato", E-X8).
// La porta e' di questo modulo e la lega `:app` (`AppReceivedQueue`, sul diario degli arrivi `incoming_item` e sul diario del backup); le schermate
// non decidono niente: leggono l'elenco e inoltrano due tocchi, "Riprova" e "Scarta". Nessun tipo qui porta un nome di file, un percorso di share
// o un account: la cartella di destinazione e' un dato dell'utente che si mostra a schermo e non va in nessun log.

/** Un file ricevuto in coda: la riga di `incoming_item`, un numero (mai un nome o un percorso). */
@JvmInline
value class ReceivedId(val value: Long) {
  override fun toString(): String = "ReceivedId"
}

/** In che punto e' un file ricevuto, per i conti della pagina Backup e per l'ordine dell'elenco (i problemi per primi). */
enum class ReceivedPhase { PROBLEM, UPLOADING, WAITING }

/**
 * A che punto e' un file ricevuto, in una parola che la schermata trasforma in una frase chiara. [phase] dice dove si conta: "in attesa",
 * "in caricamento" o "con un problema". Codici stabili; un valore nuovo non compila finche' non ha il suo testo (`when` esaustivo nella schermata).
 */
enum class ReceivedStatus(override val code: String, val phase: ReceivedPhase) : StableCode {
  // --- In attesa: il file e' in coda, la copia sul telefono c'e', il motore lo carica appena puo' --------------------------------------
  /** In coda, niente lo ferma: parte al prossimo giro. */
  QUEUED("queued", ReceivedPhase.WAITING),

  /** Il NAS non risponde, o manca la rete giusta (Wi-Fi, Wi-Fi di casa, tetto dei dati mobili). */
  NETWORK("network", ReceivedPhase.WAITING),

  /** D12, R2: "Backup in prova", in questa versione non si scrive sul NAS. */
  TRIAL("trial", ReceivedPhase.WAITING),

  /** La cartella di destinazione non si puo' scrivere adesso (sola lettura, area non piu' dell'utente, fuori dalla zona ammessa). */
  FOLDER_LOCKED("folder_locked", ReceivedPhase.WAITING),

  /** Il NAS e' pieno. */
  NAS_FULL("nas_full", ReceivedPhase.WAITING),

  /** La quota dell'account e' finita. */
  QUOTA("quota", ReceivedPhase.WAITING),

  /** Serve la password del NAS. */
  PASSWORD("password", ReceivedPhase.WAITING),

  /** Congelamento o scritture ferme sull'area: il motore non scrive finche' l'utente non controlla. */
  FROZEN("frozen", ReceivedPhase.WAITING),

  /** L'utente ha messo il backup in pausa ("Ferma"). */
  PAUSED("paused", ReceivedPhase.WAITING),

  /** L'app non e' pronta a scrivere sul NAS (famiglia non pronta, nessuna area privata). */
  UNAVAILABLE("unavailable", ReceivedPhase.WAITING),

  // --- In caricamento ---------------------------------------------------------------------------------------------------------------
  /** Il file sta salendo sul NAS adesso. */
  UPLOADING("uploading", ReceivedPhase.UPLOADING),

  /** E' salito e si controlla (rilettura) o si pubblica: fra poco e' sul NAS. */
  VERIFYING("verifying", ReceivedPhase.UPLOADING),

  // --- Con un problema: il motore ha rinunciato, la copia sul telefono resta -----------------------------------------------------------
  /** La copia sul telefono non si legge o e' cambiata dopo il tocco su "Salva sul NAS". */
  COPY_CHANGED("copy_changed", ReceivedPhase.PROBLEM),

  /** Sul NAS il file non e' arrivato intero (verifica fallita tre volte). */
  NOT_INTACT("not_intact", ReceivedPhase.PROBLEM),

  /** La cartella di destinazione non puo' ricevere il file (fuori dalle radici ammesse). */
  FOLDER_REFUSED("folder_refused", ReceivedPhase.PROBLEM),

  /** La copia sul telefono non c'e' piu': niente da caricare. */
  COPY_MISSING("copy_missing", ReceivedPhase.PROBLEM),

  /** Un errore che nessuna regola prevedeva. */
  FAILED("failed", ReceivedPhase.PROBLEM),
}

/**
 * Un file ricevuto in coda. [folder] e' la cartella di destinazione come la scrive l'utente ("Viaggi/Estate"; vuota = la cartella principale
 * dell'area). [canRetry] e [canDiscard] dicono quali tasti hanno senso adesso (uno che sta salendo non si scarta; uno senza copia sul telefono non si riprova).
 */
@Immutable
data class ReceivedItem(
  val id: ReceivedId,
  val status: ReceivedStatus,
  val folder: String,
  val isVideo: Boolean,
  val size: Long,
  val canRetry: Boolean = status.phase != ReceivedPhase.UPLOADING && status != ReceivedStatus.COPY_MISSING,
  val canDiscard: Boolean = status.phase != ReceivedPhase.UPLOADING,
) {
  val phase: ReceivedPhase get() = status.phase

  override fun toString(): String = "ReceivedItem(${status.code})"
}

/** Quanti file ricevuti per ognuno dei tre punti: la scheda della pagina Backup li conta, e non si mostra senza almeno uno. */
@Immutable
data class ReceivedCounts(val waiting: Int, val uploading: Int, val problems: Int) {
  init {
    require(waiting >= 0 && uploading >= 0 && problems >= 0) { "Conteggi negativi." }
  }

  val total: Int get() = waiting + uploading + problems

  companion object {
    val NONE: ReceivedCounts = ReceivedCounts(0, 0, 0)

    fun of(items: List<ReceivedItem>): ReceivedCounts = ReceivedCounts(
      waiting = items.count { it.phase == ReceivedPhase.WAITING },
      uploading = items.count { it.phase == ReceivedPhase.UPLOADING },
      problems = items.count { it.phase == ReceivedPhase.PROBLEM },
    )
  }
}

/** L'esito di "Riprova". */
sealed interface ReceivedRetry {
  /** Rimesso in coda: il lavoro dell'utente e' stato chiesto (come "Salva sul NAS") e parte appena i cancelli lo lasciano. */
  data object Queued : ReceivedRetry

  /** Un cancello lo vieta adesso (D12, R2, D13, la share, il congelamento): niente e' cambiato, [by] dice quale. */
  data class Blocked(val by: ReceivedStatus) : ReceivedRetry

  /** La copia sul telefono non c'e' piu': non c'e' niente da riprovare. */
  data object NoCopy : ReceivedRetry

  /** E' gia' sul NAS (o sta per esserlo): non c'e' niente da riprovare. */
  data object AlreadySaved : ReceivedRetry

  /** Il file non e' piu' in coda. */
  data object Unknown : ReceivedRetry
}

/** L'esito di "Scarta". Il NAS non si tocca mai. */
sealed interface ReceivedDiscard {
  /** Tolto dal diario degli arrivi, con la sua copia sul telefono. */
  data object Discarded : ReceivedDiscard

  /** Era gia' stato pubblicato (o lo sta per essere): non e' "scartabile", e' "caricato". Niente e' cambiato. */
  data object AlreadySaved : ReceivedDiscard

  /** Sta salendo adesso: si scarta dopo. */
  data object Uploading : ReceivedDiscard

  /** Il file non e' piu' in coda. */
  data object Unknown : ReceivedDiscard
}

/**
 * I file ricevuti in coda (la porta di "Ricevuti in coda"). Lo stato e' un flusso che si aggiorna da solo; i due tocchi sono azioni dell'utente, mai
 * automatiche. Nei log solo numeri.
 */
interface ReceivedQueue {

  /** I file in attesa, in caricamento o con un problema: prima i problemi, poi quelli che salgono, poi gli altri; a pari punto dal piu' vecchio. */
  val items: StateFlow<List<ReceivedItem>>

  /** La miniatura dalla copia locale (lato lungo circa [sidePx] pixel), o null se non c'e' o non si legge. Gira fuori dal thread principale. */
  suspend fun thumbnail(id: ReceivedId, sidePx: Int): ImageBitmap?

  /**
   * "Riprova": rimette in coda il file e chiede il lavoro dell'utente, con gli stessi cancelli di "Salva sul NAS" (D12, R2, D13, la share, il
   * congelamento). Non scrive niente se un cancello lo vieta.
   */
  suspend fun retry(id: ReceivedId): ReceivedRetry

  /**
   * "Scarta": toglie il file dal diario degli arrivi e la sua copia sul telefono. **Non tocca mai il NAS**: un file gia' pubblicato non si scarta
   * ([ReceivedDiscard.AlreadySaved]).
   */
  suspend fun discard(id: ReceivedId): ReceivedDiscard
}

/** Nessuna coda dei ricevuti legata (varianti senza NAS): niente da mostrare, niente da fare. */
object NoReceivedQueue : ReceivedQueue {
  override val items: StateFlow<List<ReceivedItem>> = MutableStateFlow(emptyList())

  override suspend fun thumbnail(id: ReceivedId, sidePx: Int): ImageBitmap? = null

  override suspend fun retry(id: ReceivedId): ReceivedRetry = ReceivedRetry.Unknown

  override suspend fun discard(id: ReceivedId): ReceivedDiscard = ReceivedDiscard.Unknown
}
