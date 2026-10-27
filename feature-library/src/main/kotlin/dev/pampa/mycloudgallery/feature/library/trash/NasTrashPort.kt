package dev.pampa.mycloudgallery.feature.library.trash

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 7, P8 (docs/fase7-progetto.md D26): il CESTINO DEL NAS dentro "Eliminati di recente". Una pagina sola per i due cestini: quello di Android (le
// foto del telefono, `RecentlyDeletedSource`) e quello del NAS (30 giorni, voci proprie e dei familiari con il permesso, F4-D25).
//
// **Perche' una porta e non `NasTrash`.** `feature-library` non vede `core-nasops` (Phase4ArchitectureTest: chi mostra la Libreria non raggiunge le
// operazioni distruttive): il cestino del NAS e le sue due azioni (ripristina, "Elimina subito") arrivano da qui, con tipi nostri. L'adattatore sta in
// `:app` (P13): legge `NasTrash.items`, e per ogni gesto chiede il piano a `NasOperations` (`OpRequest.Restore` / `OpRequest.PurgeNow`) e lo avvia con
// la conferma che costruisce **in quel momento**, dopo il tocco di cui questa schermata gli consegna la prova ([UserTap]).
//
// **Niente di cio' che si legge e' un ingresso di un'eliminazione.** Le voci sono da mostrare. Le azioni portano solo le chiavi scelte dall'utente: quello
// che si elimina davvero lo ricava l'adattatore dal diario locale (le sole voci proprie, Q8), mai da una riga letta dal NAS.

/**
 * Una voce del cestino del NAS come la pagina la mostra. Il nome e la cartella sono dati dell'utente: mai nei log (`toString`).
 *
 * @property key identita' stabile (`<proprietario>/<id>`), l'unica cosa che le azioni riportano indietro.
 * @property name il nome com'era; null se il record firmato non c'e' o non vale.
 * @property folder la cartella d'origine; null se non si sa.
 * @property daysLeft i giorni interi rimasti; null se non si sa (l'ora del NAS non e' ancorata).
 * @property restorable si puo' ripristinare (record valido, share scrivibile).
 * @property deletableNow si puo' "eliminare subito": solo le voci **proprie** (Q8, D25).
 */
@Immutable
data class NasTrashRow(
  val key: String,
  val name: String?,
  val folder: String?,
  val sizeBytes: Long,
  val daysLeft: Int?,
  val isFolder: Boolean = false,
  val isVideo: Boolean = false,
  val fileCount: Int? = null,
  val author: String? = null,
  val withoutValidRecord: Boolean = false,
  val restorable: Boolean = true,
  val deletableNow: Boolean = false,
) {
  init {
    require(key.isNotBlank()) { "Una voce ha una chiave." }
    require(sizeBytes >= 0) { "Dimensione negativa." }
  }

  override fun toString(): String = "NasTrashRow(folder=$isFolder, video=$isVideo, restorable=$restorable, own=$deletableNow)"
}

/** Cosa sa dire il cestino del NAS adesso. */
sealed interface NasTrashFeed {

  /** Nessun NAS collegato (o nessun cestino): la sezione non c'e'. */
  data object Unavailable : NasTrashFeed

  data object Loading : NasTrashFeed

  /** [writable] falso: nessuna share e' scrivibile, ripristino ed "Elimina subito" spenti (E-N9). */
  data class Ready(val rows: List<NasTrashRow>, val writable: Boolean) : NasTrashFeed

  /** Il NAS non risponde: restano le voci proprie dell'ultima lettura, senza quelle degli altri. */
  data class Offline(val cachedOwn: List<NasTrashRow>) : NasTrashFeed

  /** Nessun permesso per leggerlo. */
  data object Denied : NasTrashFeed

  data object Failed : NasTrashFeed
}

/** Com'e' andato un gesto sul cestino del NAS. Solo codici: la pagina sceglie le parole. */
enum class NasTrashOutcome {
  /** L'operazione e' partita (o finita): si segue in "Operazioni in corso". */
  STARTED,

  /** Il NAS non ha accettato il piano (voci cambiate, share in sola lettura, nome occupato...). */
  REJECTED,

  /** Le scritture sono ferme adesso (congelamento, emergenza, ora del NAS): si riprova piu' tardi. */
  BLOCKED,

  /** Non e' riuscito. */
  FAILED,
}

/**
 * La prova che l'utente ha toccato: [taps] tocchi di conferma fatti su questa schermata. Il costruttore e' `internal` e lo chiama solo un composable
 * di questo modulo dopo il gesto ([tapOnButton], [SheetTap], [DoubleTapConfirm]; `TrashTapGuardTest` lo controlla sul codice): un controllore non se la
 * fabbrica e `:app` la riceve, non puo' farsela da solo (R18 della revisione di Fase 7: prima il controllore dei Doppioni la fabbricava dentro `confirm()`,
 * e dei falsi pubblici in `src/main` ne davano una a chiunque). Un'azione che toglie dal cestino per sempre ne vuole due (Q8).
 */
class UserTap internal constructor(val taps: Int) {
  override fun toString(): String = "UserTap($taps)"
}

/**
 * Il cestino del NAS, come lo vede questa pagina. Lo lega `:app` a `NasTrash` e `NasOperations` (P13). Nessun metodo lancia per un esito previsto: gli
 * esiti sono [NasTrashOutcome].
 */
interface NasTrashPort {

  /** Le voci e il loro stato. Si legge solo a pagina sbloccata (il cestino puo' avere i nomi di cose nascoste). */
  val feed: StateFlow<NasTrashFeed>

  /** Rilegge dal NAS (non distruttivo). */
  suspend fun refresh()

  /** Ripristina le voci [keys], se hanno un record valido: lo stesso posto, mai sovrascrivendo (nome alternativo). */
  suspend fun restore(keys: Set<String>, tap: UserTap): NasTrashOutcome

  /**
   * Elimina per sempre le voci **proprie** [keys] (Q8): serve la doppia conferma ([tap] con almeno due tocchi). Chiavi di voci non proprie
   * non eliminano niente.
   */
  suspend fun purgeNow(keys: Set<String>, tap: UserTap): NasTrashOutcome
}

/** Nessun cestino del NAS (nessun NAS): la pagina mostra solo quello del telefono. */
object NoNasTrash : NasTrashPort {
  override val feed: StateFlow<NasTrashFeed> = MutableStateFlow(NasTrashFeed.Unavailable)

  override suspend fun refresh() = Unit

  override suspend fun restore(keys: Set<String>, tap: UserTap): NasTrashOutcome = NasTrashOutcome.REJECTED

  override suspend fun purgeNow(keys: Set<String>, tap: UserTap): NasTrashOutcome = NasTrashOutcome.REJECTED
}

/**
 * La doppia conferma di "Elimina subito" come macchina a tocchi (Q8, R6 della 4B): serve un tocco sul primo pannello e un secondo gesto, **dopo**
 * che il pannello nuovo (con l'avviso sul backup esterno) e' comparso. Un tocco arrivato meno di [MIN_GAP_MS] dopo il precedente (un doppio tocco,
 * un tocco registrato due volte) non conta. Pura: la provano i test su JVM; l'orologio e' monotono e lo da' il chiamante.
 */
internal class DoubleTapConfirm(private val shownAtMonotonicMs: Long) {

  /** Quanti tocchi sono stati contati (0, 1 o 2). */
  var taps: Int = 0
    private set

  private var lastCountedMs: Long = shownAtMonotonicMs

  /** Il tocco di conferma all'istante [nowMonotonicMs]. Vero solo quando e' il secondo tocco contato: la prova c'e'. */
  fun tap(nowMonotonicMs: Long): Boolean {
    if (nowMonotonicMs - lastCountedMs in 0 until MIN_GAP_MS) return false
    taps++
    lastCountedMs = nowMonotonicMs
    return taps >= REQUIRED
  }

  /** L'utente ha rinunciato: si ricomincia da capo. */
  fun reset(nowMonotonicMs: Long) {
    taps = 0
    lastCountedMs = nowMonotonicMs
  }

  /** La prova dei tocchi fatti: solo dopo due tocchi contati. */
  internal fun proof(): UserTap? = if (taps >= REQUIRED) UserTap(taps) else null

  companion object {
    const val REQUIRED: Int = 2

    /** Come `ConfirmationRules.MIN_TAP_GAP_MS`: un doppio tocco sta sotto i 300 ms, un secondo gesto voluto sopra. */
    const val MIN_GAP_MS: Long = 600
  }
}

/**
 * La prova di **un** tocco su un pulsante che agisce subito (il "Ripristina" degli Eliminati di recente: non toglie niente e non sovrascrive mai).
 * Si chiama solo dal composable che disegna il pulsante, nel gesto di quel tocco: il controllore riceve la prova, non se la fa.
 */
internal fun tapOnButton(): UserTap = UserTap(1)

/**
 * Il tocco di conferma di un **foglio** che si apre (il foglio "Elimina" dei Doppioni): una macchina nuova a ogni apertura, e un tocco arrivato prima di
 * [MIN_SHOWN_MS] dalla comparsa del foglio non conta (come C34 per la conferma degli album, 600 ms): un tocco che era per quello che c'era dietro, o un
 * doppio tocco, non elimina niente. Pura: la provano i test su JVM; l'orologio e' monotono e lo da' il chiamante.
 */
internal class SheetTap(private val shownAtMonotonicMs: Long) {

  /** Il tocco di conferma all'istante [nowMonotonicMs]: la prova se il foglio e' visibile da abbastanza, altrimenti null. */
  fun confirm(nowMonotonicMs: Long): UserTap? =
    if (nowMonotonicMs - shownAtMonotonicMs >= MIN_SHOWN_MS) UserTap(1) else null

  companion object {
    /** Come `ShareConfirmation` (C34) e `DoubleTapConfirm`: un tocco prima di 600 ms dalla comparsa non e' una scelta. */
    const val MIN_SHOWN_MS: Long = 600
  }
}
