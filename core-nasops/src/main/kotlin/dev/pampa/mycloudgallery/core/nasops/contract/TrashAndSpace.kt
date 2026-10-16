package dev.pampa.mycloudgallery.core.nasops.contract

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.data.KeepReason
import dev.pampa.mycloudgallery.core.trust.data.OwnKeepReason
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// Fase 4B (docs/fase4-progetto.md §1.2 "Cestino", "Spazio", "Pulizia propria", §1.5, §2.7, D25, Q8): "Eliminati di recente" del NAS,
// "Spazio NAS" e la pulizia dei propri file. Contratti, stati e porte di P0-B; la logica e' di P12.
//
// Tutto cio' che questi contratti LEGGONO dal NAS (record firmati del cestino, indice) e' solo da mostrare. Le uniche azioni sono:
//  - ripristinare / "elimina subito": operazioni con la loro conferma ([OpRequest.Restore], [OpRequest.PurgeNow]);
//  - la scadenza dei 30 giorni ([NasTrash.purgeExpired]): parte solo dal diario locale di questo dispositivo, mai da un record remoto.

/** Un file o una cartella nel cestino del NAS, come "Eliminati di recente" lo mostra (§1.5). Il nome e i percorsi sono dati dell'utente. */
data class NasTrashItem(
  val share: String,
  val ownerDeviceId: String,
  val trashId: String,
  /** Il nome com'era; null se il record non c'e' o non vale. */
  val name: String?,
  /** La cartella d'origine; null se il record non c'e' o non vale. */
  val originalFolder: SharePath?,
  val originalPath: SharePath?,
  val size: Long,
  /** Giorni rimasti prima della scadenza, a ora del NAS; null se non lo si sa (record mancante, ora non ancorata). */
  val daysLeft: Int?,
  /** Il nome del dispositivo che ha cestinato, se il registro lo conosce. */
  val author: String?,
  val reason: TrashReason?,
  val isFolder: Boolean,
  val fileCount: Int?,
  /** Il record firmato non c'e' o non vale: compare come "senza record valido" e decide l'utente (formato §8.3). */
  val withoutValidRecord: Boolean,
  /** Si puo' ripristinare: il record e' valido, la share e' scrivibile (E-N9). */
  val restorable: Boolean,
  /**
   * Si puo' "elimina subito" (Q8): solo le voci **proprie**, cioe' presenti nel diario locale di questo dispositivo. Le voci di altri
   * dispositivi si possono solo ripristinare (D25).
   */
  val deletableNow: Boolean,
) {
  /** Mai il nome ne' i percorsi nei log. */
  override fun toString(): String = "NasTrashItem(folder=$isFolder, size=$size, restorable=$restorable)"
}

/** Lo stato di "Eliminati di recente" del NAS. */
sealed interface NasTrashState {
  data object Loading : NasTrashState

  /** [measuredAtNas] e' l'ora del NAS della lettura; [writable] falso se nessuna share e' scrivibile (azioni spente, E-N9). */
  data class Ready(val items: List<NasTrashItem>, val measuredAtNas: Long?, val writable: Boolean) : NasTrashState

  /** Il NAS non risponde: le voci del diario locale (le proprie) restano visibili, senza i record remoti. */
  data class Offline(val cachedOwn: List<NasTrashItem>) : NasTrashState

  data object Denied : NasTrashState

  data class Error(val code: OpErrorCode) : NasTrashState
}

/** Il resoconto di un giro di pulizia del cestino. */
data class PurgeReport(
  val purged: Int,
  val bytes: Long,
  /** Perche' le voci rimaste non sono state toccate (non scadute, cambiate...). */
  val kept: Map<KeepReason, Int>,
  val alreadyGone: Int,
  val deferred: Int,
  val stoppedBy: PurgeStop,
)

enum class PurgeStop(override val code: String) : StableCode {
  COMPLETE("complete"),
  BUDGET("budget"),
  NAS_UNREACHABLE("nas_unreachable"),

  /** Congelamento, salto dell'ora o emergenza ([GateStatus.blocksScheduled]): nessuna pulizia. */
  BLOCKED("blocked"),

  /** L'ora del NAS non e' ancorata: senza non si decide niente (D32). */
  NO_NAS_TIME("no_nas_time"),

  /**
   * R4 (revisione 4B): il tetto del giro (200 file e 20 GiB, formato §9.3) e' raggiunto. Non e' "tempo finito": il lavoro giornaliero non
   * ne chiede un altro subito, il resto aspetta il giro del giorno dopo.
   */
  ROUND_CAP("round_cap"),
}

/**
 * Il cestino del NAS dell'app (D25): cartella `.mycloudgallery/trash/<dispositivo>/` della stessa share, record firmato per voce.
 * Ripristino e "elimina subito" passano da [NasOperations]; qui lo stato da mostrare e la scadenza.
 */
interface NasTrash {

  val items: StateFlow<NasTrashState>

  /** Rilegge i record del cestino delle share dell'utente (non distruttivo). */
  suspend fun refresh()

  /**
   * Elimina le voci **proprie** scadute: `TrashPurgePolicy` di core-trust sul diario locale ([OpsStore.expiredTrash]), ora del NAS,
   * 30 giorni, 200 file e 20 GiB per giro, `deleteIfUnchanged` con un solo handle. Nessuna conferma per giro: l'utente l'ha
   * data eliminando. Si ferma con congelamento, salto dell'ora o emergenza. Nessun record remoto fra gli ingressi: un finto cestino
   * non fa eliminare nulla (E-S11, F20).
   */
  suspend fun purgeExpired(budget: Duration): PurgeReport
}

// --- Spazio NAS -------------------------------------------------------------------------------------------------------------

/** Un file fra i piu' grandi (dal catalogo: nessuna chiamata al NAS). */
data class LargestFile(val share: String, val path: SharePath, val size: Long, val stamp: NasStamp?) {
  override fun toString(): String = "LargestFile(size=$size)"
}

/**
 * "Spazio NAS" (§1.5, §2.7): si calcola solo quando l'utente apre la pagina.
 *
 * @property quotaBytes lo spazio libero **per l'account** se e' minore di quello del disco (quota, E-N13), altrimenti null.
 * @property versionsBytes 0 fino alla Fase 5 ("—" nella pagina).
 * @property wdTrashMayHoldSpace sulla share `NAS` il cestino del WD e' acceso: lo spazio dei file eliminati per sempre si libera
 *   solo quando lo svuota il WD, e la pagina lo dice.
 */
data class NasSpaceDetails(
  val share: String,
  val freeBytes: Long,
  val totalBytes: Long,
  val quotaBytes: Long?,
  val largest: List<LargestFile>,
  val trashBytes: Long,
  val indexBytes: Long,
  val versionsBytes: Long,
  val wdTrashMayHoldSpace: Boolean,
  val measuredAtNas: Long?,
) {
  init {
    require(largest.size <= MAX_LARGEST) { "Al massimo $MAX_LARGEST file fra i piu' grandi." }
  }

  companion object {
    const val MAX_LARGEST: Int = 50
  }
}

sealed interface NasSpaceState {
  data object Loading : NasSpaceState

  data class Ready(val details: NasSpaceDetails) : NasSpaceState

  /** Il NAS non risponde o la share non c'e': i dati del catalogo (file piu' grandi) si mostrano comunque, quelli del disco no. */
  data class Unavailable(val code: OpErrorCode, val largest: List<LargestFile>) : NasSpaceState
}

interface NasSpace {
  /** Calcola lo stato di [share]: `space()` sulla share, file piu' grandi dal catalogo, cestino e indice dai loro conteggi. */
  suspend fun load(share: String): NasSpaceState
}

// --- Pulizia dei propri file -----------------------------------------------------------------------------------------------

/** Il resoconto di un giro di pulizia dei propri file (formato §2.3, M6). */
data class CleanReport(
  val removed: Int,
  val kept: Map<OwnKeepReason, Int>,
  val failed: Int,
  val stoppedBy: PurgeStop,
)

/**
 * La pulizia dei **propri** file in `.mycloudgallery/` (teste superate, segmenti non piu' elencati, generazioni di prenotazione
 * superate, temporanei): `OwnFilesPolicy` di core-trust decide file per file, questa li toglie con `deleteIfUnchanged`. Accesa
 * con la compattazione dell'indice, a un giro al giorno al massimo, con le condizioni del cestino ([GateStatus.blocksScheduled]).
 */
interface OwnFilesCleaning {
  suspend fun clean(shares: List<String>, budget: Duration): CleanReport
}

/**
 * Fase 5A, D33 (`docs/fase5-progetto.md` §0.1, formato §2.3 N2): i temporanei propri citati da un diario **diverso** da quello delle
 * operazioni, cioe' dal diario del backup (`BackupStore.citedTemps` di core-backup: `.mycloudgallery/tmp/<io>-bk-<id>` degli elementi
 * non finiti). La pulizia dei propri file toglie un temporaneo solo se **nessuno** dei due diari lo cita: un video a meta' con il
 * telefono fuori casa da giorni (E-B21) resta finche' la ripresa non lo finisce o lo toglie.
 *
 * La compone `:app` (P7): core-nasops non vede core-backup ne' il contrario. I percorsi valgono in qualunque share (nel dubbio si
 * tiene di piu'). Se la porta fallisce (eccezione), in quel giro **non si toglie nessun temporaneo**: senza la prova che un
 * temporaneo non serve, resta.
 */
fun interface CitedTemps {
  suspend fun cited(): Set<SharePath>

  companion object {
    /** Nessun altro diario (prima che il backup sia legato): valgono solo le citazioni del diario delle operazioni. */
    val NONE: CitedTemps = CitedTemps { emptySet() }
  }
}

// --- Cartelle NAS ------------------------------------------------------------------------------------------------------------

/** Una voce di una cartella del NAS, per "Cartelle NAS". */
data class NasFolderEntry(
  val path: SharePath,
  val isFolder: Boolean,
  val size: Long,
  val modifiedAtNas: Long,
  val fileId: Long?,
  /** Per una cartella: quanti file contiene, se il catalogo lo sa. */
  val fileCount: Int? = null,
) {
  val name: String get() = path.name

  /** L'elemento che un'operazione puo' avere come ingresso, con il timbro visto qui. */
  fun toItem(share: String): NasItem = NasItem(
    share = share,
    path = path,
    isFolder = isFolder,
    size = size,
    stamp = if (isFolder) null else NasStamp(size, modifiedAtNas, fileId),
    fileCount = fileCount,
  )

  override fun toString(): String = "NasFolderEntry(folder=$isFolder, size=$size)"
}

/** Lo stato di una schermata "Cartelle NAS" (§1.5). */
sealed interface NasFolderState {
  data object Loading : NasFolderState

  /**
   * L'elenco. [readOnly]: la cartella e' in sola lettura per questo account (E-N9), le azioni di modifica si spengono con la
   * spiegazione. [cachedAt] e' l'ora del NAS dell'elenco se viene dalla cache e non dal NAS.
   */
  data class Listing(val entries: List<NasFolderEntry>, val readOnly: Boolean, val cachedAt: Long?) : NasFolderState

  /** Il NAS non risponde: l'ultimo elenco noto, se c'e'. */
  data class Offline(val cached: List<NasFolderEntry>?, val cachedAt: Long?) : NasFolderState

  data object Denied : NasFolderState

  data class Error(val code: OpErrorCode) : NasFolderState
}

/**
 * La lettura delle cartelle del NAS per la UI (sola lettura). **Non e' assegnata dal §4.4:** il contratto e il fake sono di P0-B, la
 * realizzazione sopra `NasMediaReader` e il catalogo e' da assegnare (proposta: P12, in `core-nasops/.../folders`).
 */
interface NasFolders {
  /** L'elenco di [dir] (null = radice) di [share]: prima `Loading`, poi l'elenco o il motivo per cui non c'e'. */
  fun observe(share: String, dir: SharePath?): Flow<NasFolderState>

  /** Rilegge dal NAS (l'utente ha tirato per aggiornare, o un'operazione e' finita). */
  suspend fun refresh(share: String, dir: SharePath?)
}
