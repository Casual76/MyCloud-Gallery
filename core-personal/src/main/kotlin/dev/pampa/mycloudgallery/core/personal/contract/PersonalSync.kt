package dev.pampa.mycloudgallery.core.personal.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
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

  /** Il dispositivo non e' (piu') approvato o il registro non e' fresco (E-S14): si legge, non si pubblica. */
  NOT_TRUSTED("not_trusted"),

  /** NAS o quota pieni (E-I6): lo stato non si e' potuto pubblicare, resta sul telefono e si riprova. */
  NAS_FULL("nas_full"),
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
  /** Segmenti e teste scartati interi (firma, impronta, forma, tetti: E-I14) o assenti; mai il contenuto, solo il conto. */
  val rejectedSegments: Int = 0,
  val rejectedHeads: Int = 0,
  /** Voci dello stato unito che non si possono scrivere (ora fuori dal periodo, testo con caratteri vietati): restano sul telefono. */
  val notPublishable: Int = 0,
  /** M3: una testa ogni 5 minuti; se lo stato locale e' piu' nuovo di quello pubblicato, quando si potra' (ora del NAS). */
  val publishRetryAtNas: Long? = null,
  /** Voci applicate allo stato ma non ancora al catalogo (media non ancora noto a questo dispositivo): si riprovano. */
  val unresolved: Int = 0,
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

  /**
   * Come [entries], ma con sopra le modifiche locali ancora "da datare" (senza l'ora del NAS): **il flusso** di [effective]. E' lo stato
   * che l'utente vede su QUESTO dispositivo, quello che le impostazioni personali devono leggere: una scelta fatta fuori casa, senza ora
   * del NAS, vale subito anche se non e' ancora nello stato che si pubblica (R6 della revisione di sicurezza della Fase 6, LT-1, CE-3,
   * VM-2). Di serie [entries] (gli store che datano sempre non hanno niente da aggiungere).
   */
  val effectiveEntries: StateFlow<Map<PersonalKey, PersonalEntry>> get() = entries

  suspend fun get(key: PersonalKey): PersonalEntry?

  /** Una modifica dell'utente su questo dispositivo. */
  suspend fun set(key: PersonalKey, value: PersonalValue, fingerprint: PersonalFingerprint? = null)

  /** Applica voci gia' giudicate valide ([PersonalValidity]): per ogni chiave resta la piu' recente ([PersonalMerge]). */
  suspend fun applyRemote(entries: List<PersonalEntry>): PersonalApplyResult

  /** Le modifiche locali ancora senza ora del NAS (non si pubblicano finche' non sono datate). */
  suspend fun undated(): List<PersonalKey>

  /** Data le modifiche "da datare" con [nasNowMs]. */
  suspend fun stamp(nasNowMs: Long)

  /**
   * Lo stato come lo vede l'utente su questo dispositivo **adesso**: lo stato unito con sopra le sue modifiche ancora "da datare" (che
   * in [entries] non ci sono: senza ora del NAS non si pubblicano, ma valgono subito). Le voci da datare hanno l'ora piu' alta
   * possibile: una scelta appena fatta vince su cio' che si sa. Lo leggono le scelte che non devono perdersi (una separazione di copie
   * simili fatta senza rete: il gruppo non si deve riformare, E-L13). Di serie lo stato unito.
   */
  suspend fun effective(): Map<PersonalKey, PersonalEntry> = entries.value

  /**
   * L'impronta (dimensione e `qh1`) del media [mediaId] del catalogo di questo dispositivo, per una voce che lo nomina senza esserne
   * il soggetto: l'altro media di una separazione fra copie simili ([PersonalValue.Apart]). Null se il catalogo non lo conosce. Di
   * serie null (nessun catalogo).
   */
  suspend fun fingerprintOfMedia(mediaId: String): PersonalFingerprint? = null

  /**
   * Il media del catalogo di questo dispositivo con l'impronta [fingerprint], **solo se e' uno** (§2.9: due media con la stessa impronta
   * rapida, L32, non si confondono mai): per ritrovare il media di una voce scritta da un altro dispositivo con un id che qui non c'e'.
   * Null se non c'e' o se sono piu' di uno. Di serie null (nessun catalogo).
   */
  suspend fun mediaWithFingerprint(fingerprint: PersonalFingerprint): MediaId? = null
}

/** L'ultima testa `personal` pubblicata da questo dispositivo: serve a non ripubblicare cio' che e' gia' sul NAS e a rispettare M3. */
data class PersonalPublication(
  val version: Long,
  val mark: HeadMark,
  /** Ora del NAS in cui e' stata scritta (M3: al massimo una testa ogni 5 minuti). */
  val publishedAtNas: Long,
  /** I nomi (impronte) dei segmenti elencati dalla testa: lo stato pubblicato. */
  val segments: Set<String>,
  val entries: Int,
)

/**
 * Cio' che il motore di sincronizzazione chiede allo store oltre a [PersonalStore]: le teste gia' accettate (anti-rollback, E-I5), la
 * pubblicazione propria e la revisione dello stato quando il registro cambia. Le scritture che vanno insieme (le voci applicate e la
 * testa da cui vengono) sono **una** operazione: un crash non lascia mai una testa "gia' letta" senza le sue voci.
 */
interface PersonalSyncStore : PersonalStore {
  /** L'ultima testa accettata per dispositivo (tutti i dispositivi dell'account, anche questo). */
  suspend fun marks(): Map<String, HeadMark>

  /**
   * Applica voci giudicate valide, lette dalla testa di [deviceId], e (se [mark] non e' null) ricorda quella testa. [mark] resta null
   * quando la lettura non e' finita o ha scartato voci che potrebbero valere piu' tardi (nel futuro): la testa si rilegge al giro dopo.
   */
  suspend fun applyFrom(deviceId: String, entries: List<PersonalEntry>, mark: HeadMark?): PersonalApplyResult

  /** Toglie dallo stato le voci che [rules] non ammettono piu' (autore revocato dopo che le avevamo accettate). Quante. */
  suspend fun purgeInvalid(rules: PersonalRules): Int

  /** Lo stato unito adesso: cio' che si pubblica. */
  suspend fun state(): Map<PersonalKey, PersonalEntry>

  suspend fun published(): PersonalPublication?

  suspend fun setPublished(publication: PersonalPublication)

  /** Riprova ad applicare al catalogo cio' che non si e' potuto (il media non c'era ancora). Quante voci restano senza media. */
  suspend fun reproject(): Int

  /**
   * Adotta come proprie le modifiche locali che non hanno una voce: preferiti e nascosti accesi nel catalogo prima che la
   * sincronizzazione esistesse, o cambiati dalla Libreria senza passare dallo store. Quante. Idempotente.
   */
  suspend fun adoptLocal(): Int
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
