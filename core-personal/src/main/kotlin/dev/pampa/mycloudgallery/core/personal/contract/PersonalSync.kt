package dev.pampa.mycloudgallery.core.personal.contract

import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// Fase 4B (docs/fase4-progetto.md §1.2 "core-personal", §2.9): la sincronizzazione dei dati personali, lo store locale e le impostazioni
// personali. Interfacce e stati di P0-B; la logica e' di P13.

/** Perche' i dati personali non si sincronizzano adesso. */
enum class PersonalUnavailable(override val code: String) : StableCode {
  NAS_UNREACHABLE("nas_unreachable"),

  /** Nessuna area privata dichiarata per questo account (dispositivo non configurato, area uguale alla comune: L23). */
  NO_PRIVATE_AREA("no_private_area"),

  /** L'area privata e' in sola lettura per questo account (E-N9): si legge, non si pubblica. */
  READ_ONLY("read_only"),

  /** Salto dell'ora del NAS oltre 5 minuti (D32): senza ora fidata non si datano le modifiche ne' si pubblica. */
  CLOCK_JUMP("clock_jump"),

  /** Congelamento (D10) o "Ferma le scritture sul NAS": si legge, non si pubblica. */
  WRITES_BLOCKED("writes_blocked"),
}

/** Lo stato della sincronizzazione, per Impostazioni e Diagnostica. */
sealed interface PersonalSyncStatus {
  data object Idle : PersonalSyncStatus

  data object Syncing : PersonalSyncStatus

  /** [atNas] e' l'ora del NAS dell'ultima sincronizzazione riuscita. */
  data class UpToDate(val atNas: Long) : PersonalSyncStatus

  data class Unavailable(val reason: PersonalUnavailable) : PersonalSyncStatus

  /** D31: sulle share vere i dati personali restano sul telefono finche' l'utente non chiude la 4A. */
  data object LocalOnly : PersonalSyncStatus
}

/** Perche' un giro si e' fermato. */
enum class PersonalStop(override val code: String) : StableCode {
  COMPLETE("complete"),
  BUDGET("budget"),
  UNAVAILABLE("unavailable"),
}

/**
 * Il resoconto di un giro. [rejected] conta le voci scartate per motivo ([PersonalVerdict], mai `VALID`); [stillUndated] sono le
 * modifiche locali ancora senza ora del NAS.
 */
data class PersonalSyncReport(
  val published: Int,
  val applied: Int,
  val rejected: Map<PersonalVerdict, Int>,
  val stillUndated: Int,
  val stoppedBy: PersonalStop,
)

/**
 * La sincronizzazione dei dati personali fra i miei dispositivi (D26): pubblica nella propria area privata lo stato unito che
 * conosce, legge gli stati pubblicati dagli altri dispositivi dello stesso account, scarta cio' che non vale e fonde il resto con
 * [PersonalMerge]. Gira dopo una modifica (accorpata 30 s, app aperta, NAS raggiungibile) e in `INDEX_SYNC`.
 */
interface PersonalSync {
  val status: StateFlow<PersonalSyncStatus>

  suspend fun sync(budget: Duration): PersonalSyncReport
}

/** L'esito dell'applicazione di voci arrivate da altri dispositivi. */
data class PersonalApplyResult(val applied: Int, val ignored: Int)

/**
 * Lo stato locale dei dati personali: la porta sul catalogo (che ha gia' i `*_changed_at` dello schema v2) e sulle impostazioni. Una
 * modifica locale prende l'ora del NAS se c'e', altrimenti resta "da datare" e la prende alla prima sincronizzazione (§2.9).
 */
interface PersonalStore {
  /** Lo stato unito, per chiave. */
  val entries: StateFlow<Map<PersonalKey, PersonalEntry>>

  suspend fun get(key: PersonalKey): PersonalEntry?

  /** Una modifica dell'utente su questo dispositivo. */
  suspend fun set(key: PersonalKey, value: PersonalValue, fingerprint: PersonalFingerprint? = null)

  /** Applica voci gia' giudicate valide ([PersonalValidity]): per ogni chiave resta la piu' recente ([PersonalMerge]). */
  suspend fun applyRemote(entries: List<PersonalEntry>): PersonalApplyResult

  /** Le modifiche locali ancora senza ora del NAS (non si pubblicano finche' non sono datate). */
  suspend fun undated(): List<PersonalKey>

  /** Data le modifiche "da datare" con [nasNowMs]. */
  suspend fun stamp(nasNowMs: Long)
}

/**
 * Le impostazioni personali che viaggiano fra i miei dispositivi (Q9 (a)): le esclusioni dell'area privata e "Mostra le copertine degli
 * album". **Non** i preset, la qualita' delle anteprime, i ruoli: dipendono dal dispositivo (batteria, spazio).
 */
interface PersonalSettings {
  val showAlbumCovers: StateFlow<Boolean>

  suspend fun setShowAlbumCovers(show: Boolean)

  /** Le cartelle dell'area privata escluse dalla scansione (E-N15), come percorsi di share. */
  val privateAreaExclusions: StateFlow<Set<SharePath>>

  suspend fun setPrivateAreaExclusions(folders: Set<SharePath>)
}
