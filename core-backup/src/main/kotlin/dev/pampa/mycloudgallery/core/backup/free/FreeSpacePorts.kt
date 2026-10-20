package dev.pampa.mycloudgallery.core.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunId
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.PhonePlanHash
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRowNow
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 5B, P9 (docs/fase5-progetto.md §2.5, D21, D22, D31, E-S11): le PORTE di "Libera spazio" verso il resto dell'app. Le lega `:app`
// (P14): il catalogo a `MediaCatalog` (P1), il diario alle tabelle `free_space_run` e `free_space_item` dello schema v4, il conteggio dei
// riavvii a `Settings.Global.BOOT_COUNT`. Fake in `free/testing`. **Ogni decisione parte dal catalogo locale** (E-S11): il NAS si legge solo
// per rileggere le copie gia' scelte e per il loro timbro, e una lettura puo' togliere un elemento, mai aggiungerne.

/**
 * Un media come lo vede "Libera spazio": il grafo del catalogo ([media]: versioni con le impronte, posizioni con il timbro e l'istante
 * della verifica), piu' cio' che il grafo non porta. Nessun nome di file nei `toString`.
 *
 * @property localDay il giorno di scatto nell'ora del luogo (`aaaammgg`), null = senza data: i filtri "ultimi 30 giorni" e "piu' vecchi
 *   di un anno" (Q9).
 * @property sortTime l'ordine della Libreria (dal piu' recente): l'ordine dei lotti.
 * @property problem il problema del backup del media (`BACKUP_PROBLEM`, D15), se c'e'.
 * @property mime il tipo del media (serve alla UI per l'URI del dialogo di sistema).
 */
class FreeSpaceMedia(
  val media: Media,
  val localDay: Int?,
  val sortTime: Long,
  val problem: BackupProblemCode? = null,
  val mime: String? = null,
) {
  val id: MediaId get() = media.id

  override fun toString(): String = "FreeSpaceMedia(${media.kind.code}, ${media.versions.size} versioni)"
}

/** Il cursore delle pagine del catalogo: `sort_time` decrescente, poi id (come i candidati del backup). */
data class FreeSpaceCursor(val sortTime: Long, val mediaId: MediaId) {
  override fun toString(): String = "FreeSpaceCursor"
}

/**
 * Il catalogo locale visto da "Libera spazio" e da "Elimina solo dal telefono" (P14 la lega a `MediaCatalog`): **l'unica fonte** degli
 * elenchi (E-S11). Le copie "verificate" sono quelle con `verified_at` (una rilettura fatta qui, L28); la verifica sul momento le rilegge
 * comunque tutte.
 */
interface FreeSpaceCatalog {

  /**
   * Una pagina dei media con almeno una copia **presente** sul telefono, dal piu' recente ([FreeSpaceMedia.sortTime] decrescente, poi id),
   * dopo [after]. Tutti i media, propri e no: le regole decidono. Vuota a fine corsa.
   */
  suspend fun phoneMedia(after: FreeSpaceCursor?, limit: Int): List<FreeSpaceMedia>

  /** I media [ids] come sono adesso (un id che non c'e' piu' manca; un alias vale per il sopravvissuto). */
  suspend fun media(ids: Collection<MediaId>): List<FreeSpaceMedia>

  /**
   * L'esito della rilettura completa della copia [location] (come `BackupCatalog.markVerified`): il catalogo confronta lo SHA-256 **letto
   * qui** ([readBack]) con quello della versione; vero = verificata adesso ([atMillis], ora del NAS), falso = fuori da "al sicuro" (E-B8:
   * il backup la rifa' finche' il telefono ce l'ha).
   */
  suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean

  /**
   * Android ha tolto queste copie del telefono (confermato dal dialogo di sistema, o provato dalla lettura del telefono dopo un esito
   * sconosciuto): la Libreria le mostra subito "solo NAS" (§2.5 punto 7). Il diario ([FreeSpaceDiary]) lo sa gia' prima: questo e' solo un
   * avviso al catalogo, e una chiamata persa non perde niente.
   */
  suspend fun freedFromPhone(locations: List<LocationId>)

  /**
   * LS-12 della verifica funzionale 5B: dove sta adesso la riga del telefono della versione [versionId] di [mediaId], per un elemento consegnato
   * ad Android la cui riga [stale] non vale piu' (`PhoneLookup.Stale`: MediaStore ricostruito, `_ID` rinumerati). E' la stessa ricetta del
   * backup (`BackupCatalog.phoneRowOf`): [PhoneRowNow.Absent] solo se la lettura del telefono ha riletto **tutto** il volume dopo il cambio e la
   * versione non ha piu' una riga presente. Null = non si sa (l'elemento resta in dubbio): e' il default, prudente.
   */
  suspend fun phoneRowOf(mediaId: MediaId, versionId: VersionId, stale: PhoneRef): PhoneRowNow? = null
}

/**
 * Il conteggio dei riavvii del telefono (`Settings.Global.BOOT_COUNT`), null se non si legge. Con l'orologio monotono dice se una verifica
 * e' "di questo avvio" (D21, D31): l'orologio monotono riparte da zero a ogni riavvio, quindi da solo non basta. Mai l'orologio del telefono.
 */
fun interface BootCounter {
  fun current(): Long?
}

// --- Il diario (`free_space_run`, `free_space_item`) -------------------------------------------------------------------------------

/** Lo stato di un elemento di un giro (`free_space_item.state`). I codici non cambiano. */
enum class FreeItemState(override val code: String, val handedToAndroid: Boolean = false) : StableCode {
  /** Aspetta il guardiano (D9, E-B14): una versione da proteggere ha solo la copia comune. */
  GUARD("guard"),

  /** Aspetta la verifica sul momento. */
  PLANNED("planned"),

  /** Ogni copia privata da proteggere e' stata riletta per intero con lo SHA-256 giusto (le copie in [FreeItemRecord.checks]). */
  VERIFIED("verified"),

  /** Uscito dal giro ([FreeItemRecord.reason]): non arriva mai a un dialogo. */
  DROPPED("dropped"),

  /** **Scritto prima** di consegnare il lotto alla UI: e' (o sta per essere) nel dialogo di sistema. */
  IN_DIALOG("in_dialog", handedToAndroid = true),

  /** Android l'ha tolto dal telefono (confermato, o la riga non c'e' piu' dopo un esito sconosciuto). */
  FREED("freed", handedToAndroid = true),

  /** Resta sul telefono: dialogo rifiutato, non confermato, o la riga c'e' ancora dopo un esito sconosciuto. */
  KEPT("kept", handedToAndroid = true),

  /** L'esito non si sa e il telefono non lo dice ancora (riga rinumerata, permessi): lo risolve la lettura del telefono. */
  IN_DOUBT("in_doubt", handedToAndroid = true),
}

/**
 * Le transizioni ammesse di un elemento (oltre a restare com'e'):
 * - `GUARD` → `PLANNED`, `DROPPED`; `PLANNED` → `VERIFIED`, `DROPPED`; `VERIFIED` → `IN_DIALOG`, `DROPPED`;
 * - `IN_DIALOG` → `FREED`, `KEPT`, `IN_DOUBT`, `DROPPED` (ricontrollo di un lotto riproposto dallo stesso processo);
 * - `IN_DOUBT` → `FREED`, `KEPT`;
 * - `FREED`, `KEPT`, `DROPPED`: finali.
 */
object FreeItemTransitions {
  private val allowed: Map<FreeItemState, Set<FreeItemState>> = mapOf(
    FreeItemState.GUARD to setOf(FreeItemState.PLANNED, FreeItemState.DROPPED),
    FreeItemState.PLANNED to setOf(FreeItemState.VERIFIED, FreeItemState.DROPPED),
    FreeItemState.VERIFIED to setOf(FreeItemState.IN_DIALOG, FreeItemState.DROPPED),
    FreeItemState.IN_DIALOG to setOf(FreeItemState.FREED, FreeItemState.KEPT, FreeItemState.IN_DOUBT, FreeItemState.DROPPED),
    FreeItemState.IN_DOUBT to setOf(FreeItemState.FREED, FreeItemState.KEPT),
  )

  fun canTransition(from: FreeItemState, to: FreeItemState): Boolean = from == to || to in allowed[from].orEmpty()
}

/** Una copia privata riletta dalla verifica sul momento: posizione del catalogo, percorso e timbro **visto all'apertura**. */
class FreeCheck(val versionId: VersionId, val location: LocationId, val share: String, val path: SharePath, val stamp: NasStamp) {
  override fun equals(other: Any?): Boolean =
    other is FreeCheck && other.versionId == versionId && other.location == location && other.share == share && other.path == path &&
      other.stamp == stamp

  override fun hashCode(): Int = location.hashCode() * 31 + stamp.hashCode()

  override fun toString(): String = "FreeCheck(${stamp.size} B)"
}

/**
 * Da quando vale la verifica sul momento (D21, D31): l'istante **monotono** in cui il giro e' cominciato e il conteggio dei riavvii di
 * allora. Vale [dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules.VERIFICATION_VALID_MS] da [startMonotonicMs], solo nello
 * stesso avvio: ogni rilettura del giro e' successiva a [startMonotonicMs], quindi nessuna verifica valida ha piu' di 6 ore.
 */
data class FreeValidity(val startMonotonicMs: Long, val bootCount: Long?)

/** Un giro da creare. */
class NewFreeRun(
  val state: FreeSpaceRunState,
  val kind: FreeSpaceKind,
  val excludeRecentDays: Int,
  val permanent: Boolean,
  val planHash: PhonePlanHash,
  val validity: FreeValidity?,
  val blocked: FreeSpaceBlock? = null,
)

/**
 * Un giro nel diario (`free_space_run`). [validity] e [blocked] sono **facoltativi** per chi conserva il diario: un adattatore che non li
 * salva li restituisce null, e il motore tratta un giro senza validita' come scaduto (fallisce chiuso).
 */
data class FreeRunRecord(
  val id: FreeSpaceRunId,
  val state: FreeSpaceRunState,
  val kind: FreeSpaceKind,
  val excludeRecentDays: Int,
  val permanent: Boolean,
  val planHash: PhonePlanHash,
  val batchesDone: Int,
  val validity: FreeValidity?,
  val blocked: FreeSpaceBlock? = null,
) {
  init {
    require(batchesDone >= 0) { "Lotti negativi." }
  }

  override fun toString(): String = "FreeRunRecord(#$id, ${state.code}, lotti=$batchesDone)"
}

/**
 * Un elemento di un giro (`free_space_item`): una copia del telefono da liberare. Le colonne v4 coprono [state], [mediaId], [versionId],
 * [phoneLocation], la copia privata della versione del telefono ([checks] primo elemento: posizione e timbro verificato), [size], [batchIndex]
 * e [reason]. **Facoltativi** per chi conserva il diario (null se non salvati): [phone], [phoneStamp], [mime], [checks] completi. Il motore
 * non consegna mai a un dialogo un elemento a cui manca un campo che serve al controllo finale: lo fa uscire o chiude il giro (fallisce chiuso).
 *
 * @property plannedCopy il timbro della copia privata della versione del telefono quando l'utente ha confermato (null = la crea il guardiano).
 * @property checks le copie private rilette (una per versione da proteggere), scritte con [FreeItemState.VERIFIED].
 * @property batchIndex il lotto in cui l'elemento e' stato consegnato al dialogo (da 1).
 */
data class FreeItemRecord(
  val index: Int,
  val state: FreeItemState,
  val mediaId: MediaId,
  val versionId: VersionId,
  val phoneLocation: LocationId,
  val phone: PhoneRef?,
  val phoneStamp: FileStamp?,
  val mime: String?,
  val size: Long,
  val needsGuardian: Boolean,
  val plannedCopy: NasStamp?,
  val checks: List<FreeCheck>? = null,
  val batchIndex: Int? = null,
  val reason: FreeSpaceExclusion? = null,
) {
  init {
    require(index >= 0 && size >= 0) { "Elemento non valido." }
    require((state == FreeItemState.DROPPED) == (reason != null)) { "Un motivo c'e' se e solo se l'elemento e' uscito." }
    require(!state.handedToAndroid || batchIndex != null) { "Un elemento consegnato ad Android ha il suo lotto." }
  }

  override fun toString(): String = "FreeItemRecord(#$index, ${state.code}, $size B)"
}

/**
 * Il diario di "Libera spazio" (D21, §2.5 punto 7): sopravvive alla morte del processo e sa **prima della lettura del telefono** quali copie
 * sono state consegnate a un dialogo e quali Android ha tolto. Ogni scrittura e' una transazione; il motore scrive **prima** di usare
 * (un lotto e' `IN_DIALOG` prima di arrivare alla UI).
 */
interface FreeSpaceDiary {

  /** Crea il giro e i suoi elementi in un passo solo. */
  suspend fun create(run: NewFreeRun, items: List<FreeItemRecord>): FreeRunRecord

  suspend fun run(id: FreeSpaceRunId): FreeRunRecord?

  /** Gli elementi del giro, per indice. */
  suspend fun items(id: FreeSpaceRunId): List<FreeItemRecord>

  /** In un passo solo: il giro e gli elementi cambiati (per indice, gli altri restano). */
  suspend fun update(run: FreeRunRecord, items: List<FreeItemRecord> = emptyList())

  /** Tutti i giri, dal piu' vecchio (per risolvere gli esiti in dubbio dopo la lettura del telefono). */
  suspend fun runs(): List<FreeRunRecord>

  /**
   * Le copie del telefono **consegnate ad Android** e non ancora risolte (`IN_DIALOG`, `IN_DOUBT`) in qualunque giro: non entrano in un piano
   * nuovo (mai una riga in due dialoghi, mai una riga forse gia' nel cestino). Con Room una query sola su `free_space_item.state`.
   */
  suspend fun handedPhoneLocations(): Set<LocationId> = runs().flatMapTo(HashSet()) { run ->
    items(run.id).filter { it.state == FreeItemState.IN_DIALOG || it.state == FreeItemState.IN_DOUBT }.map { it.phoneLocation }
  }

  /** LS-2: i giri non finiti, dal piu' vecchio (quello da riprendere all'apertura della pagina o dal lavoro dopo la morte del processo). */
  suspend fun openRuns(): List<FreeRunRecord> = runs().filter { !it.state.terminal }

  /**
   * LS-15: i giri con elementi consegnati ad Android e non risolti (`IN_DIALOG`, `IN_DOUBT`), dal piu' vecchio: solo questi servono a `settle`,
   * che prima scorreva tutti i giri della storia (non si potano mai). Con Room una query sull'indice di `free_space_item.state`.
   */
  suspend fun runsToSettle(): List<FreeRunRecord> = runs().filter { run ->
    items(run.id).any { it.state == FreeItemState.IN_DIALOG || it.state == FreeItemState.IN_DOUBT }
  }
}
