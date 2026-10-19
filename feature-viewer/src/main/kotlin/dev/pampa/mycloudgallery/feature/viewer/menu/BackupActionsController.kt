package dev.pampa.mycloudgallery.feature.viewer.menu

import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Fase 5B (P12, docs/fase5-progetto.md §1.3 "viewer", §2.4, §2.7, D22, D26): la macchina sottile delle voci del backup nel viewer. Per il media che il
// viewer mostra chiede a `:app` il piano delle versioni (solo catalogo) e ne fa le voci del menu; **non scrive mai niente e non decide niente**: ogni
// voce, quando l'utente la tocca, e' una richiesta a `:app`, che apre il foglio giusto sopra il viewer. Non tiene conferme: le conferme nascono nei
// fogli di feature-backup, da un tocco.

/**
 * La macchina delle voci del backup. Il viewer chiama [bind] quando cambia pagina e legge [target] per costruire il menu; [perform], [share] e
 * [deleteFromPhone] sono i tocchi.
 *
 * @param scope lo scope che vive quanto il viewer: se si chiude a meta' di una lettura, non resta niente di avviato.
 */
class BackupActionsController(
  private val actions: BackupMediaActions,
  private val scope: CoroutineScope,
) {

  private val targetFlow = MutableStateFlow<BackupMenuTarget?>(null)

  /** Cosa si sa del media di adesso (le versioni): null finche' non risponde il custode, o se non c'e'. */
  val target: StateFlow<BackupMenuTarget?> = targetFlow

  /** Cosa il backup sa fare: le voci compaiono solo per cio' che c'e'. */
  val capabilities: BackupMenuCapabilities get() = actions.capabilities

  /** "Condividi senza posizione" e' acceso: la voce lo dice. */
  val removeLocation: StateFlow<Boolean> get() = actions.removeLocation

  /** Lo stato di "Condividi". */
  val shareState: StateFlow<BackupShareUi> get() = actions.shareState

  /** Una frase breve dopo un'azione. */
  val notice: StateFlow<BackupNotice?> get() = actions.notice

  private var current: MediaId? = null
  private var job: Job? = null

  /** Il viewer e' passato a [id] ([isVideo] dice se e' un video: un video non si ruota). null = nessun media. */
  fun bind(id: MediaId?, isVideo: Boolean) {
    job?.cancel()
    current = id
    if (id == null) {
      targetFlow.value = null
      return
    }
    // Finche' il custode non risponde il menu ha le voci che non dipendono dalle versioni; non c'e' una voce che sparisce e ricompare per nulla.
    targetFlow.value = BackupMenuTarget(plan = null, isVideo = isVideo)
    job = scope.launch { targetFlow.value = BackupMenuTarget(actions.versionPlan(id), isVideo) }
  }

  /** Una voce del menu toccata dall'utente. */
  fun perform(action: BackupMenuAction) {
    val id = current ?: return
    actions.perform(action, id)
  }

  /** "Condividi", dal menu o dalla barra. */
  fun share() {
    val id = current ?: return
    actions.share(listOf(id))
  }

  /**
   * "Elimina solo dal telefono": se il backup sa valutarlo (D22: verifica sul momento, timbro, dialogo del sistema) lo fa il foglio di feature-backup;
   * altrimenti [fallback] (il cestino del viewer di prima).
   */
  fun deleteFromPhone(fallback: () -> Unit) {
    val id = current
    if (id != null && actions.capabilities.phoneOnlyDelete) actions.perform(BackupMenuAction.PHONE_ONLY_DELETE, id) else fallback()
  }

  /** "Condividi con la posizione": solo dopo che l'utente l'ha scelto nel foglio di un rifiuto. */
  fun shareWithLocation() = actions.shareWithLocation()

  /** "Annulla" / "Ho capito" nel foglio o nell'avviso di una condivisione. */
  fun dismissShare() = actions.dismissShare()

  fun dismissNotice() = actions.dismissNotice()

  /** Le copie in piu' che "Ovunque" toglie per il media di adesso (le versioni precedenti), per il foglio "Elimina…". */
  suspend fun everywhere(): EverywhereSummary? {
    val id = current ?: return null
    return actions.everywhere(id)
  }
}
