package dev.pampa.mycloudgallery.core.library.duplicates

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId

// Fase 7, P8 (docs/fase7-progetto.md D25, §2.1.1; E-U1, E-U2): i DOPPIONI IDENTICI. Questi sono i tipi; le regole pure sono in
// `IdenticalCleanup`, la lettura del catalogo in `CatalogIdenticalDuplicates`.
//
// Un doppione identico e' una versione di un media con piu' copie **presenti dalla stessa parte** (due sul telefono, o due sul NAS: area
// privata e comune insieme). Le copie sono gli stessi byte: una di loro basta. Niente di quello che segue cancella qualcosa: descrive cosa
// l'app *proporrebbe*; cancellare e' un'azione locale confermata dal foglio di eliminazione (`NasOperations` per il NAS, il dialogo di
// sistema per il telefono), mai definitiva (i cestini tengono 30 giorni).

/** Dove sta una copia (D25). Una copia nell'area di un altro account ([OTHERS]) non si propone mai: nessun permesso (E-U1). */
enum class CopyArea(override val code: String) : StableCode {
  PHONE("phone"),
  MY_PRIVATE("my_private"),
  COMMON("common"),
  OTHERS("others"),
}

/** Da che parte sta un gruppo di copie: il telefono, o il NAS (area privata e comune insieme). */
enum class CopyPart(override val code: String) : StableCode {
  PHONE("phone"),
  NAS("nas"),
}

/**
 * Una copia di un doppione identico. [path] e' un dato dell'utente (cartella e nome sul telefono, percorso nella share sul NAS): mai nei log.
 *
 * @property verifiedAt l'istante (ora del NAS) in cui i byte di questa copia sono stati riletti e sono risultati quelli della versione;
 *   null = mai riletta. Una copia privata verificata e' "al sicuro" (D8).
 * @property backupFolder la cartella `Backup/<dispositivo>/` di un'area privata in cui sta (forma di confronto), o null: e' la copia fatta dal
 *   backup di un dispositivo.
 * @property inSharedAlbum e' un elemento di un album condiviso: sta nella cartella degli album (`Album condivisi/<album>/...` dell'area comune, Q1) **oppure**
 *   il media e' attestato da un album condiviso anche se il file sta altrove (un riferimento, E-SH11; una cartella adottata, E-SH16): eliminarla farebbe
 *   sparire l'elemento dall'album per tutti (D9, E-SH17).
 * @property guardian e' la copia di sicurezza di un file dell'area comune (`Backup/Area comune/...` dell'area privata, D28): nasce proprio per proteggere
 *   quel file, e non e' la sua "gemella" da pulire.
 */
@Immutable
class IdentCopy(
  val location: LocationId,
  val area: CopyArea,
  val share: String?,
  val path: String,
  val size: Long,
  val modifiedAt: Long,
  val verifiedAt: Long?,
  val backupFolder: String?,
  val inSharedAlbum: Boolean,
  val guardian: Boolean = false,
) {
  /** La copia e' nell'area privata, riletta e verificata: quella che rende il media "al sicuro" (D8). */
  val isSafeCopy: Boolean get() = area == CopyArea.MY_PRIVATE && verifiedAt != null

  override fun toString(): String = "IdentCopy(${area.code}, size=$size)"
}

/**
 * Una versione con piu' copie presenti dalla stessa [part].
 *
 * @property fullHash l'hash completo della versione, se noto. Senza (video del NAS prima di `FULL_HASH`, D27) le copie hanno solo lo stesso
 *   `qh1` e la stessa dimensione: sono "da verificare", e la pulizia non le propone.
 * @property safe il media ha una copia privata verificata ("al sicuro"): serve all'avviso E-D14 sulle copie del telefono.
 */
@Immutable
class IdenticalGroup(
  val media: MediaId,
  val version: VersionId,
  val part: CopyPart,
  val fullHash: Sha256?,
  val copies: List<IdentCopy>,
  val safe: Boolean,
  val isVideo: Boolean,
) {
  init {
    require(copies.size >= 2) { "Un doppione ha almeno due copie." }
  }

  /** La chiave stabile del gruppo fra una lettura e l'altra (la scelta dell'utente in "Rivedi" la ricorda). */
  val key: GroupKey get() = GroupKey(media, version, part)

  val verified: Boolean get() = fullHash != null

  override fun toString(): String = "IdenticalGroup(${part.code}, ${copies.size} copie, verified=$verified)"
}

/** L'identita' di un gruppo: media, versione e parte. */
data class GroupKey(val media: MediaId, val version: VersionId, val part: CopyPart) {
  override fun toString(): String = "GroupKey(${part.code})"
}

/** Perche' una copia non si propone per l'eliminazione. I codici non cambiano: li leggono i test e le schermate. */
enum class BlockReason(override val code: String) : StableCode {
  /** E-U1: la copia sta nell'area di un altro account, non c'e' il permesso. */
  OTHER_AREA("other_area"),

  /** Mai l'ultima copia privata: se tutte le copie private fossero da togliere, la migliore resta. */
  LAST_PRIVATE("last_private"),

  /** La copia e' un elemento di un album condiviso: toglierla lo toglie a tutti i familiari. */
  SHARED_ALBUM("shared_album"),

  /** DP-2: la copia dell'area comune ha la copia di sicurezza del guardiano (D28): e' proprio il file che quella copia protegge, e toglierlo lo toglie a tutti. */
  GUARDIAN_TWIN("guardian_twin"),

  /** R18: la copia e' l'unica verificata ("al sicuro") del gruppo: "Rivedi" non la manda nel cestino per tenere una copia mai riletta. */
  LAST_VERIFIED("last_verified"),
}

/** Una copia che la proposta lascia dov'e' e perche'. */
class BlockedCopy(val copy: IdentCopy, val reason: BlockReason) {
  override fun toString(): String = "BlockedCopy(${reason.code})"
}

/** Cosa si puo' fare di un gruppo. */
enum class IdenticalStatus(override val code: String) : StableCode {
  /** C'e' almeno una copia da togliere, tutte le regole rispettate. */
  CLEANABLE("cleanable"),

  /** Senza hash completo (D27): le copie sono probabilmente uguali, non provate. Niente da togliere finche' `FULL_HASH` non decide. */
  TO_VERIFY("to_verify"),

  /** Niente da togliere: una sola copia con il permesso, o tutte protette dalle regole "mai". */
  NOTHING_TO_REMOVE("nothing_to_remove"),
}

/**
 * La proposta per un gruppo (D25): **una** copia da tenere per parte, le altre preselezionate per l'eliminazione, e le copie che le regole
 * lasciano dove sono. Cambiare la copia da tenere ("Rivedi") rifa' la proposta con la stessa regola, non la scavalca.
 */
@Immutable
class GroupProposal(
  val group: IdenticalGroup,
  val status: IdenticalStatus,
  val keep: IdentCopy?,
  val remove: List<IdentCopy>,
  val blocked: List<BlockedCopy>,
) {
  /** Quanti byte libererebbe: le copie da togliere. */
  val reclaimableBytes: Long get() = remove.sumOf { it.size }

  /** Quante copie nell'area di un altro account (E-U1): "1 copia in un'area di un altro familiare". */
  val inOthersArea: Int get() = blocked.count { it.reason == BlockReason.OTHER_AREA }

  override fun toString(): String = "GroupProposal(${status.code}, remove=${remove.size}, blocked=${blocked.size})"
}

/**
 * Un riferimento al telefono da mandare nel cestino di sistema (dialogo di sistema, mai senza), **con il timbro visto dal catalogo** (R18): dimensione e
 * data di modifica della riga del MediaStore al momento del piano, e la versione del MediaStore con cui l'`_ID` valeva. Prima di chiedere il dialogo la
 * pagina rilegge la riga: se non e' piu' quella (stesso `_ID`, byte nuovi: un file sovrascritto dopo la scansione) la copia non va nel cestino. E'
 * l'equivalente per il telefono del timbro delle copie del NAS ([NasTrashRef], E-D13).
 */
@Immutable
class PhoneTrashRef(
  val ref: MediaRef.Phone,
  val stamp: FileStamp,
  val mediaStoreVersion: String,
  /** La posizione del catalogo di questa copia: la pagina la toglie dalla vista appena la pulizia e' accettata (DP-3), senza aspettare la scansione. */
  val location: LocationId? = null,
) {
  val size: Long get() = stamp.size

  override fun toString(): String = "PhoneTrashRef(size=$size)"
}

/**
 * Un file del NAS da mandare nel cestino del NAS (30 giorni, `NasOperations`): [path] e [modifiedAt] e [fileId] sono il timbro visto dal
 * catalogo, perche' l'operazione si fermi se il file non e' piu' quello (E-D13).
 */
@Immutable
class NasTrashRef(
  val share: String,
  val path: String,
  val size: Long,
  val modifiedAt: Long,
  val fileId: Long?,
  val area: CopyArea,
  /** La posizione del catalogo di questa copia (DP-3), se la sa chi ha fatto il piano. */
  val location: LocationId? = null,
) {
  override fun toString(): String = "NasTrashRef(${area.code}, size=$size)"
}

/**
 * Il piano di una pulizia: le copie da mandare nei cestini. Nasce solo da una scelta dell'utente e non cancella niente: lo esegue il foglio di
 * eliminazione (dialogo di sistema per [phone], `NasOperations` con la conferma per [nas]).
 *
 * @property groups quanti gruppi tocca.
 * @property unsafePhone quante copie del telefono sono di media senza una copia al sicuro (E-D14): il foglio lo dice.
 * @property skipped copie che si sarebbero dovute togliere ma non si possono indicare adesso (il telefono non conosce ancora la loro riga).
 */
@Immutable
class CleanupPlan(
  val groups: Int,
  val phone: List<PhoneTrashRef>,
  val nas: List<NasTrashRef>,
  val unsafePhone: Int,
  val skipped: Int,
) {
  val copies: Int get() = phone.size + nas.size

  val bytes: Long get() = phone.sumOf { it.size } + nas.sumOf { it.size }

  val isEmpty: Boolean get() = copies == 0

  /** Le posizioni del catalogo delle copie del piano (quelle che le sanno). */
  val locations: Set<LocationId> get() = (phone.mapNotNull { it.location } + nas.mapNotNull { it.location }).toSet()

  override fun toString(): String = "CleanupPlan(groups=$groups, phone=${phone.size}, nas=${nas.size}, unsafePhone=$unsafePhone)"

  companion object {
    val EMPTY: CleanupPlan = CleanupPlan(0, emptyList(), emptyList(), 0, 0)
  }
}
