package dev.pampa.mycloudgallery.feature.viewer.menu

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 5B (P12, docs/fase5-progetto.md §1.3 "viewer", §1.5, §2.4, §2.7, D17-D19, D22, D25, D26, E-D15, E-V7; riga P12 del §4.4): le voci del menu ⋮ del
// viewer per il backup — Versioni, Vedi originale, Ripristina originale, Correggi data e luogo, Ruota, Modifica con…, Condividi (con la posizione secondo
// l'impostazione) — e le porte che le servono. Come `NasActions` per la 4B, questo file sono i **dati e le porte**: cosa il viewer chiede a `:app`
// ([BackupMediaActions]) e come si disegnano le voci ([backupMenuEntries]). **Il viewer non scrive mai niente**: ogni voce e' una richiesta a `:app`, che
// apre i fogli di feature-backup (versioni, correzioni, "Libera spazio") o le activity delle integrazioni (modifica con…) sopra il viewer; i feature non
// si conoscono. Una capacita' che manca non da' una voce morta (D13 della Fase 3).

/** Cosa fa partire una voce del menu. Tutte arrivano a `:app` ([BackupMediaActions.perform]) dopo un tocco dell'utente. */
enum class BackupMenuAction {
  /** Il foglio delle versioni di feature-backup. */
  VERSIONS,

  /** Guardare la versione originale nel viewer (D17). */
  VIEW_ORIGINAL,

  /** Il foglio delle versioni, direttamente al riepilogo di "Ripristina originale". */
  RESTORE_ORIGINAL,

  /** Il foglio "Correggi data e luogo" di feature-backup. */
  CORRECT,

  /** Lo stesso foglio, solo la rotazione. */
  ROTATE,

  /** "Modifica con…" (D25): Samsung Gallery per un media del telefono, un editor per un solo-NAS. */
  EDIT_WITH,

  /** "Elimina solo dal telefono" (D22): il foglio di feature-backup che valuta il media e apre "Libera spazio". */
  PHONE_ONLY_DELETE,
}

/** Che cosa `:app` sa fare per questo viewer. Un campo falso = la voce non compare. */
@Immutable
data class BackupMenuCapabilities(
  /** C'e' il servizio delle correzioni: "Correggi data e luogo" e "Ruota". */
  val correct: Boolean = false,
  /** "Modifica con…". */
  val editWith: Boolean = false,
  /** Si puo' guardare la versione originale nel viewer. */
  val viewOriginal: Boolean = false,
  /** "Condividi" passa da `:app` (con "Rimuovi la posizione" e i solo-NAS). */
  val share: Boolean = false,
  /** "Elimina solo dal telefono" passa dal foglio di feature-backup (verifica sul momento, timbro, dialogo del sistema). */
  val phoneOnlyDelete: Boolean = false,
)

/**
 * Lo stato di "Condividi" per l'interfaccia (§1.5 `ShareOutState`): `:app` lo mappa su quello di `feature-integrations` (P11, `LocationFreeShare`) e
 * scrive le frasi, perche' i feature non si conoscono. Solo numeri e frasi fisse: mai il nome di un file.
 */
@Immutable
sealed interface BackupShareUi {
  data object Idle : BackupShareUi

  /** Si prepara la copia: [done] di [total] fatti; [removingLocation] = si toglie la posizione (altrimenti si scarica soltanto, E-V7). */
  data class Preparing(val done: Int, val total: Int, val removingLocation: Boolean) : BackupShareUi

  /** Pronta e **ricontrollata**: [verified] file senza posizione, [direct] condivisi come sono (impostazione spenta). Il sistema apre la scelta dell'app. */
  data class Ready(val verified: Int, val direct: Int) : BackupShareUi

  /**
   * Fermata (D26, L41): [message] e' la frase per l'utente (la scrive `:app` dal motivo). Con [canShareWithLocation] il foglio offre "Condividi con la
   * posizione" — una scelta esplicita dell'utente, mai un ripiego automatico; altrimenti solo "Ho capito".
   */
  data class Refused(val message: String, val canShareWithLocation: Boolean) : BackupShareUi
}

/** Una frase breve dopo un'azione (la sessione di "Modifica con…", un esito): il testo lo scrive `:app`, e' una frase fissa e mai un dato. */
@Immutable
class BackupNotice(val text: String, val ok: Boolean) {
  override fun toString(): String = "BackupNotice(ok=$ok)"
}

/**
 * Le versioni di un media dal punto di vista del menu: quante sono, se l'originale e' gia' quello di adesso e se si puo' ripristinare. Viene dal piano
 * del custode (`VersionKeeper.plan`, solo catalogo): [plan] null = nessun custode legato.
 */
@Immutable
class BackupMenuTarget(
  val plan: NasVersionPlan?,
  val isVideo: Boolean,
) {
  val versions: Int get() = plan?.versions?.size ?: 0

  /** L'originale e' gia' la versione di adesso, o non c'e': niente da ripristinare e niente da guardare a parte. */
  val originalIsCurrent: Boolean get() = plan?.original?.isCurrent != false

  /** Un media di un altro account (D35): non si corregge, non si ripristina. */
  val owned: Boolean get() = plan?.restore != RestoreAvailability.NOT_MINE

  /** "Ripristina originale" ha senso proporlo: se non si puo' adesso, il foglio dice perche'. */
  val restoreOffered: Boolean
    get() = when (plan?.restore) {
      null, RestoreAvailability.ONLY_ONE_VERSION, RestoreAvailability.ALREADY_ORIGINAL, RestoreAvailability.NOT_MINE, RestoreAvailability.NOT_AVAILABLE -> false
      RestoreAvailability.AVAILABLE, RestoreAvailability.ORIGINAL_NOT_SAFE, RestoreAvailability.NAS_UNREACHABLE, RestoreAvailability.FROZEN,
      RestoreAvailability.READ_ONLY_AREA, RestoreAvailability.BUSY,
      -> true
    }

  override fun toString(): String = "BackupMenuTarget($versions versioni, video=$isVideo)"
}

/**
 * Tutto cio' che le voci del backup chiedono a `:app`. Un'istanza per app, costruita dal grafo del backup; i test usano un finto. Senza di lei (`null` nei
 * servizi del viewer) le voci non compaiono.
 */
interface BackupMediaActions {

  /** Cosa si sa fare (le voci compaiono solo per cio' che c'e'). */
  val capabilities: BackupMenuCapabilities

  /** Il piano delle versioni di [id] (solo catalogo e diario locale: nessuna lettura del NAS), o null se non c'e' un custode. */
  suspend fun versionPlan(id: MediaId): NasVersionPlan? = null

  /**
   * Una voce scelta dall'utente: `:app` apre il foglio o l'activity giusta sopra il viewer. Solo da un tocco: il viewer non la chiama mai da solo.
   */
  fun perform(action: BackupMenuAction, id: MediaId)

  /** "Rimuovi la posizione" e' acceso (Impostazioni → Condivisioni): "Condividi" dice che toglie la posizione. */
  val removeLocation: StateFlow<Boolean> get() = AlwaysOff

  /** Lo stato dell'ultima condivisione. */
  val shareState: StateFlow<BackupShareUi> get() = NoShare

  /** "Condividi": prepara e consegna [ids] secondo l'impostazione. Parte da un tocco sulla voce del menu o sull'azione in basso. */
  fun share(ids: List<MediaId>) = Unit

  /** "Condividi con la posizione": la scelta esplicita dell'utente dopo un rifiuto (D26, L41). */
  fun shareWithLocation() = Unit

  /** Chiude un rifiuto o annulla la preparazione. */
  fun dismissShare() = Unit

  /** Una frase breve per l'utente dopo un'azione (es. la sessione di "Modifica con…"). */
  val notice: StateFlow<BackupNotice?> get() = NoNotice

  fun dismissNotice() = Unit

  /**
   * I posti di una copia che "Ovunque" toglie e che il foglio "Elimina…" non conosce: le versioni precedenti in `Versioni/`, le altre aree (E-D15).
   * Dal catalogo locale. Null = non si sa (nessun custode): il foglio non dice niente di piu'.
   */
  suspend fun everywhere(id: MediaId): EverywhereSummary? = null
}

// I valori di riposo delle porte che `:app` puo' non legare (un flusso che non cambia mai).
private val AlwaysOff: StateFlow<Boolean> = MutableStateFlow(false)
private val NoShare: StateFlow<BackupShareUi> = MutableStateFlow(BackupShareUi.Idle)
private val NoNotice: StateFlow<BackupNotice?> = MutableStateFlow(null)

/**
 * Cio' che "Ovunque" (D22, E-D15) toglie oltre alla copia di adesso, in numeri: le versioni precedenti (`Versioni/`) e quante copie stanno
 * nell'area comune. `:app` lo costruisce da `EverywherePlan` (core-backup, P9); il foglio lo elenca prima di confermare.
 */
@Immutable
data class EverywhereSummary(
  /** Le versioni precedenti che hanno almeno una copia sul NAS (l'originale e le intermedie, non l'attuale). */
  val previousVersions: Int,
  /** Quante copie stanno nell'area comune: le vedono tutti i familiari. */
  val commonCopies: Int = 0,
) {
  val isEmpty: Boolean get() = previousVersions == 0 && commonCopies == 0
}

/** Cosa succede toccando una voce. */
internal class BackupMenuHandlers(val onAction: (BackupMenuAction) -> Unit)

/**
 * Le voci del backup per il media di adesso, nell'ordine del §1.3: Versioni, Vedi originale, Ripristina originale, Correggi data e luogo, Ruota,
 * Modifica con…. Vanno nel gruppo di Condividi, Copia e Informazioni. Solo cio' che si puo' fare adesso: nessuna voce spenta senza una ragione.
 */
internal fun backupMenuEntries(
  resources: Resources,
  capabilities: BackupMenuCapabilities,
  target: BackupMenuTarget?,
  handlers: BackupMenuHandlers,
): List<GalleryMenuEntry> = buildList {
  val onAction = handlers.onAction
  val versions = target?.versions ?: 0
  if (versions >= 2) {
    add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_backup_action_versions), { onAction(BackupMenuAction.VERSIONS) }, Icons.Rounded.Layers))
    if (capabilities.viewOriginal && target?.originalIsCurrent == false) {
      add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_backup_action_view_original), { onAction(BackupMenuAction.VIEW_ORIGINAL) }, Icons.Rounded.Photo))
    }
    if (target?.restoreOffered == true) {
      add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_backup_action_restore), { onAction(BackupMenuAction.RESTORE_ORIGINAL) }, Icons.Rounded.Restore))
    }
  }
  val owned = target?.owned != false
  if (capabilities.correct && owned) {
    add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_backup_action_correct), { onAction(BackupMenuAction.CORRECT) }, Icons.Rounded.EditCalendar))
    if (target?.isVideo != true) {
      add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_backup_action_rotate), { onAction(BackupMenuAction.ROTATE) }, Icons.Rounded.RotateRight))
    }
  }
  if (capabilities.editWith && owned) {
    add(GalleryMenuEntry.Item(resources.getString(R.string.viewer_backup_action_edit_with), { onAction(BackupMenuAction.EDIT_WITH) }, Icons.Rounded.Edit))
  }
}
