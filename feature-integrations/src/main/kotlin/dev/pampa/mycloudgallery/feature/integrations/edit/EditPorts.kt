package dev.pampa.mycloudgallery.feature.integrations.edit

import dev.pampa.mycloudgallery.core.backup.contract.EditSession
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionId
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionState
import dev.pampa.mycloudgallery.core.backup.contract.EditTarget
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import java.io.File

// Fase 5B (P11, docs/fase5-progetto.md §1.2 "Sessioni (5B)", §2.7 "Modifica con…", D18, D25, D31, E-E3, E-E7, E-X1, E-X3): le PORTE delle sessioni di
// modifica. `feature-integrations` non vede il catalogo (`core-database`), il NAS ne' il diario: le vede `:app`, che le lega in P14. Qui ci sono le
// forme di cio' che serve alle sessioni; la logica (cosa e' cambiato, cosa e' una versione e cosa un media nuovo) e' di [DefaultEditSessions].
//
// Il MediaStore, invece, si legge direttamente con i suoi contratti (`MediaStoreSource`, `MediaStoreRowLookup` di core-mediastore): non c'e' niente
// da inventare. Gli istanti sono **ora del NAS** (D31): una sessione sopravvive alla morte del processo mentre l'utente modifica in un'altra app.

/** Che cosa il catalogo dice di un media che si vuole modificare. Nessun nome di file o percorso. */
class EditMedia(
  val mediaId: MediaId,
  /** Il media e' di questo account: le versioni di un media altrui non si creano. */
  val mine: Boolean,
  val isVideo: Boolean,
  /** La versione attuale: da qui parte la sessione. */
  val currentVersion: VersionId,
  /** Le copie presenti sul telefono della versione attuale (le righe del MediaStore da aprire in Samsung Gallery). */
  val phoneRefs: List<PhoneRef>,
  /** Una copia sul NAS leggibile (privata o comune, con i diritti): serve per modificare un media solo-NAS. */
  val hasNasCopy: Boolean,
  /** La data di scatto del media (UTC): riconosce la copia che un editor salva altrove quando il media non ha una riga sul telefono. */
  val takenAtUtcMs: Long? = null,
) {
  override fun toString(): String = "EditMedia(${if (isVideo) "video" else "foto"}, ${phoneRefs.size} copie sul telefono)"
}

/** La versione attuale di un media e di che tipo e' (serve a sapere se un cambiamento e' una modifica, un ripristino o una correzione). */
class EditCurrent(val versionId: VersionId, val kind: VersionKind) {
  override fun toString(): String = "EditCurrent(${kind.code})"
}

/** Il catalogo visto dalle sessioni di modifica. **Ogni decisione parte da qui o dal MediaStore, mai da un dato del NAS** (E-S11). */
interface EditCatalog {

  /** Il media con le sue copie, o null se non c'e'. */
  suspend fun media(id: MediaId): EditMedia?

  /** Mette la lettura del telefono in pari con il MediaStore (una scansione incrementale): dopo, il catalogo vede cio' che l'editor ha scritto. */
  suspend fun refreshPhone()

  /** La versione attuale adesso, o null se il media non c'e' piu'. */
  suspend fun current(id: MediaId): EditCurrent?

  /** Il media che il catalogo ha per quella riga del telefono, o null se non la conosce (ancora). */
  suspend fun mediaOfPhoneRow(ref: PhoneRef): MediaId?

  /**
   * E-X3: [copy] e' il media nato dalla riga che l'editor ha scritto come copia; era in realta' una versione di [into]. Lo ricollega: la copia
   * diventa una versione `EDITED` figlia di [parent], le sue posizioni passano a [into], la sua identita' e' un alias. Restituisce la versione
   * nuova, o null se il catalogo non puo' o non vuole (hash completi che dimostrano altro, un media nascosto, un altro proprietario).
   * **Mai una sostituzione**: l'originale resta com'e'.
   */
  suspend fun linkCopyAsVersion(into: MediaId, copy: MediaId, parent: VersionId): VersionId?

  /**
   * E-E3: il risultato di un editor su un media solo-NAS ([sha256], [quickHash], [size] dei byte, gia' tenuti come file preparato) diventa una
   * versione `EDITED` figlia di [parent], senza posizione (la porta il lavoro `VERSION`). Null se il media non c'e' piu'.
   */
  suspend fun addEditedVersion(into: MediaId, parent: VersionId, sha256: Sha256, quickHash: QuickHash, size: Long): VersionId?
}

/** Cio' che serve a riconoscere un cambiamento: la riga di partenza (anche per la ripresa dopo una morte del processo). Corrisponde a `edit_session`. */
class EditSnapshot(
  val startSize: Long,
  val startModifiedMs: Long,
  val startGeneration: Long?,
  /** La cartella della riga (come la scrive il MediaStore), o null per un media solo-NAS. */
  val relativePath: String?,
  /** `DATE_TAKEN` della riga di partenza (UTC), o null se non c'e': senza non si riconosce una copia. */
  val takenAtUtcMs: Long?,
  /** L'impronta della copia in cache al momento in cui l'editor l'ha avuta ([EditTarget.NAS_CACHE]). */
  val cacheSha256: Sha256?,
) {
  override fun toString(): String = "EditSnapshot(${startSize} B)"
}

/** Una sessione da registrare (tutto tranne l'id, che lo da' il database). */
class EditSessionDraft(
  val mediaId: MediaId,
  val versionId: VersionId,
  val target: EditTarget,
  val phone: PhoneRef?,
  val cacheName: String?,
  val startedAtNasMillis: Long,
  val expiresAtNasMillis: Long,
  val snapshot: EditSnapshot,
)

/** Una sessione registrata e il suo punto di partenza. */
class StoredEditSession(val session: EditSession, val snapshot: EditSnapshot) {
  override fun toString(): String = "StoredEditSession($session)"
}

/** Le sessioni nel database (`edit_session`): sopravvivono alla morte del processo. */
interface EditSessionStore {
  suspend fun create(draft: EditSessionDraft): EditSession

  suspend fun find(id: EditSessionId): StoredEditSession?

  suspend fun setState(id: EditSessionId, state: EditSessionState)

  /** Le sessioni aperte (`OPEN`), dalla piu' vecchia. */
  suspend fun open(): List<StoredEditSession>
}

/** L'esito di una copia del media solo-NAS in cache. */
sealed interface NasCopy {
  class Done(val sha256: Sha256, val size: Long) : NasCopy

  /** Il NAS non risponde: errore subito con il motivo, mai un'attesa infinita (E-X1). */
  data object Unreachable : NasCopy

  /** Il media non si legge (non c'e' piu', senza diritti). */
  data object Unavailable : NasCopy

  /** Il telefono non ha spazio. */
  data object Full : NasCopy
}

/** Legge l'originale di un media solo-NAS nella copia in cache (`:app` lo lega a `NasMediaReader`). Il file [target] non esiste: lo crea chi lo scrive. */
interface EditNasCopies {
  suspend fun copyCurrent(mediaId: MediaId, target: File): NasCopy
}

/** L'esito di "Scarica e modifica in Galleria". */
sealed interface GalleryDownload {
  /** La copia e' in `Pictures/MyCloud Gallery`, gia' legata al media del NAS (F4-D20): [ref] e' la sua riga. */
  class Saved(val ref: PhoneRef) : GalleryDownload

  data object Unreachable : GalleryDownload

  data object Unavailable : GalleryDownload

  data object Full : GalleryDownload
}

/** "Scarica sul telefono" della Fase 4 (D20), usato per "Scarica e modifica in Galleria" (E-E7). */
interface GalleryDownloads {
  suspend fun downloadForEdit(mediaId: MediaId): GalleryDownload
}

/** Trasforma una versione registrata nel catalogo in un lavoro `VERSION` (`NasVersionEngine.planStaged`, P8). Falso se non si puo' (il backup la porta poi). */
fun interface StagedVersionPlanner {
  suspend fun plan(mediaId: MediaId, versionId: VersionId, sha256: Sha256, size: Long): Boolean
}

/** Nessun pianificatore legato: la versione resta nel catalogo e il file preparato aspetta il giro del backup (`recoverStaged`). */
object NoStagedVersionPlanner : StagedVersionPlanner {
  override suspend fun plan(mediaId: MediaId, versionId: VersionId, sha256: Sha256, size: Long): Boolean = false
}
