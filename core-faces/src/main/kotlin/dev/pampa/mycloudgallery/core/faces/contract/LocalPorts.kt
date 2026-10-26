package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.StableCode
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// Le altre porte di core-faces (docs/fase8-progetto.md §1.2 "Porte", §1.4, §2.1, §2.8): cancellazione sul NAS, album smart, cronologia
// della ricerca, regole del calcolo, orologi, requisiti dell'attivazione e lo stato locale del dispositivo. Le lega :app (P13).

/** Perche' si cancella sul NAS (D31), come `FacesEraseReason` di core-nasops (che core-faces non vede). */
enum class NasEraseReason(override val code: String) : StableCode {
  USER_CONFIRMED("user_confirmed"),
  NOT_CURRENT("not_current"),
  DEVICE_REVOKED("device_revoked"),
}

/** Un giro di cancellazione sul NAS, visto da core-faces. */
sealed interface NasEraseRound {
  /** Niente piu' sotto il bersaglio. */
  data class Done(val removedFiles: Int) : NasEraseRound

  /** Tetto del giro: si continua al giro dopo. */
  data class More(val removedFiles: Int, val remainingFiles: Int?) : NasEraseRound

  /** In attesa con un motivo dichiarato (NAS, congelamento, sola lettura, salto dell'ora, emergenza, fiducia). */
  data class Waiting(val reason: EraseWait) : NasEraseRound

  /** La regola di D31 non lo ammette (generazione corrente, dispositivo non revocato, area non privata): niente tolto. */
  data object NotAllowed : NasEraseRound

  /** Lo stato dell'account non e' fresco o la voce e' sconosciuta: nel dubbio niente. */
  data object Unsure : NasEraseRound
}

/** La verifica (§2.8 passo 9): la cartella della generazione c'e' ancora? Il cestino di rete ne tiene una copia (E-F34)? */
data class NasEraseCheck(val generationPresent: Boolean, val recycleBinCopy: Boolean)

/**
 * La cancellazione stretta dei dati dei volti sul NAS (D31), legata da :app a `FacesNasEraser` di core-nasops sull'area privata dichiarata.
 * core-faces non nomina mai un percorso: dice generazione, dispositivo e motivo; la regola (e `NasLayout`) fanno il resto. Null da
 * [generations] e [verify] = non si sa adesso (NAS, cancello).
 */
interface FacesEraseOps {
  suspend fun generations(): List<FaceGeneration>?

  suspend fun eraseGeneration(generation: FaceGeneration, reason: NasEraseReason, budget: Duration): NasEraseRound

  suspend fun eraseDevice(generation: FaceGeneration, deviceId: String, reason: NasEraseReason, budget: Duration): NasEraseRound

  suspend fun verify(generation: FaceGeneration): NasEraseCheck?
}

/**
 * Gli album smart con le persone (D27, §2.8 passo 3), legati da :app a core-albums: quanti sono, e "sciogliere" la regola delle persone con
 * la scelta dell'utente ([PeopleAlbumsChoice.KEEP_PHOTOS]: gli elementi attuali diventano voci `item:` con impronta, la regola si toglie,
 * un album che aveva solo quella diventa manuale; [PeopleAlbumsChoice.DROP_RULE]: solo la regola). Si fa **prima** di cancellare i dati dei
 * volti (servono per sapere gli elementi). Idempotente.
 */
interface SmartAlbumsPeople {
  suspend fun albumsWithPeople(): Int

  suspend fun release(choice: PeopleAlbumsChoice): Int
}

/**
 * La cronologia della ricerca (D26, D29 passo 4), legata da :app a `SearchHistory` di core-search: toglie le ricerche recenti che contengono
 * uno dei [names] (anche i nomi vecchi, tenuti cifrati nel database dei volti finche' c'e'). Quante ne ha tolte. Idempotente.
 */
fun interface SearchHistoryPurge {
  suspend fun purge(names: Collection<String>): Int
}

/** Il permesso di lavorare adesso (le regole di `AI_COMPUTE`, E-A5, E-A17). */
sealed interface FacesPermit {
  /** Si puo'; [reducedPace] = col caldo da `LIGHT`: lotti da 5 con 2 s di pausa. */
  data class Go(val reducedPace: Boolean) : FacesPermit

  data class Stop(val stop: FacesStop) : FacesPermit
}

/** Le regole del calcolo dei volti (le stesse di `AI_COMPUTE`), legate da :app al motore delle regole. Si chiede prima di ogni lotto. */
fun interface FacesRunControl {
  suspend fun permit(): FacesPermit
}

/** Gli orologi (D36): monotono per durate, freschezza e scadenze; l'ora del NAS per voci, teste e prenotazioni (null = non ancora presa). */
interface FacesClock {
  fun monotonicNanos(): Long

  fun nasNowMillis(): Long?
}

/** L'area privata dell'account per Persone (D5, L23). */
sealed interface PrivateAreaState {
  data class Declared(val share: String) : PrivateAreaState {
    override fun toString(): String = "PrivateAreaState.Declared"
  }

  /** Nessuna area privata dichiarata. */
  data object Missing : PrivateAreaState

  /** L'area privata dichiarata e' l'area comune: per Persone non c'e' un'area privata. */
  data object SameAsCommon : PrivateAreaState
}

/** Gli indicizzatori dell'account (D5): quanti negli ultimi 7 giorni (stati firmati, F6-D14), se lo e' questo, se puo' diventarlo. */
data class IndexerState(val accountIndexers: Int, val thisDevice: Boolean, val canBecomeIndexer: Boolean)

/**
 * I requisiti dell'attivazione e il cancello della recinzione (D5, D34), legati da :app al profilo del dispositivo, al registro e ai flag.
 * [localOnly] = la recinzione vale per l'area privata (sulle share vere: tutto in locale).
 */
interface FacesRequirements {
  suspend fun privateArea(): PrivateAreaState

  suspend fun indexers(): IndexerState

  suspend fun localOnly(share: String): Boolean
}

/**
 * Lo stato **locale** di Persone su questo dispositivo (D4, file `faces-local` in `noBackupFilesDir`, mai una voce): l'esclusione, l'avviso
 * gia' visto (per quale generazione e quale versione del testo), la generazione dei dati locali e una cancellazione in corso. Lo tolgono
 * l'azzeramento e, a cancellazione finita, P8.
 */
data class FacesLocal(
  val excluded: Boolean = false,
  val noticedGeneration: FaceGeneration? = null,
  val noticedTextVersion: Int? = null,
  val dataGeneration: FaceGeneration? = null,
  val erasing: FaceGeneration? = null,
) {
  override fun toString(): String = "FacesLocal(escluso=$excluded, avviso=$noticedTextVersion, dati=${dataGeneration != null}, cancella=${erasing != null})"

  companion object {
    val EMPTY = FacesLocal()
  }
}

/** Dove sta [FacesLocal] (P7 lo scrive su file; i fake in memoria). Ogni [update] e' atomico e durevole prima di tornare. */
interface FacesLocalStore {
  val local: StateFlow<FacesLocal>

  suspend fun update(transform: (FacesLocal) -> FacesLocal): FacesLocal
}
