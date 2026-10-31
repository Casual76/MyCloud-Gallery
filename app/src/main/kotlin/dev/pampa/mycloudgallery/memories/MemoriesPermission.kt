package dev.pampa.mycloudgallery.memories

import android.content.Context
import android.content.Intent
import dev.pampa.mycloudgallery.feature.memories.contract.NotificationAccess
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.main.PermissionAsks
import dev.pampa.mycloudgallery.notify.MemoryNotifier
import dev.pampa.mycloudgallery.notify.NotificationPermissionAccess
import dev.pampa.mycloudgallery.notify.NotifyPagePermission
import dev.pampa.mycloudgallery.notify.NotifyPermissionActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Il permesso e il canale delle notifiche per la pagina Impostazioni -> Ricordi e notifiche (Fase 9, P13; P10: "un `NotificationAccess` di tre righe sopra
 * `NotificationPermissionAccess`", D18, E-NT1, E-NT2, E-O4). I quattro stati di `:app` ([NotifyPagePermission]) diventano quelli della pagina
 * ([NotifyPermission]) **per codice** (`NotifyPermissionFlowTest` prova che sono gli stessi). Si legge alla prima apertura della pagina (un file di
 * preferenze piccolo: mai all'avvio) e si rilegge solo quando la pagina lo chiede. **Non chiede mai niente**: il dialogo di sistema parte solo da [actions],
 * da un tocco dell'utente.
 *
 * [explain] dice, per un permesso, se Android propone ancora di spiegare: lo aggiorna l'host della navigazione con l'activity di adesso (senza activity
 * "nel dubbio si puo' chiedere").
 */
class AppNotificationAccess(context: Context, notifier: () -> MemoryNotifier, private val scope: CoroutineScope) : NotificationAccess {

  @Volatile
  var explain: (String) -> Boolean? = { null }

  private val asks by lazy { PermissionAsks(context.applicationContext) }
  private val system by lazy { NotificationPermissionAccess(notifier(), asks) { permission -> explain(permission) } }
  private val stateHolder = lazy { system.state.map(::pageOf).stateIn(scope, SharingStarted.Eagerly, pageOf(system.state.value)) }
  private val notifierRef = notifier

  override val state: StateFlow<NotifyPermission> get() = stateHolder.value

  override fun refresh() = system.refresh()

  /**
   * Le azioni della pagina al tocco (P10, `NotifyPermissionActions`): "Consenti" (il dialogo di sistema se Android lo propone ancora, altrimenti le impostazioni
   * dell'app), il canale "Ricordi" e la risposta del dialogo. [askSystem] lancia il dialogo per `POST_NOTIFICATIONS`; [open] avvia una pagina di Android.
   */
  fun actions(askSystem: () -> Unit, open: (Intent) -> Unit): MemoriesPermissionActions =
    MemoriesPermissionActions(NotifyPermissionActions(system, notifierRef(), asks, askSystem, open))

  override fun toString(): String = "AppNotificationAccess"

  companion object {
    fun pageOf(page: NotifyPagePermission): NotifyPermission = NotifyPermission.entries.first { it.code == page.code }
  }
}

/** Le tre azioni della pagina sul permesso, senza i tipi interni del flusso di P10. */
class MemoriesPermissionActions internal constructor(private val flow: NotifyPermissionActions) {
  fun requestNotificationPermission() = flow.requestNotificationPermission()

  fun openNotificationChannel() = flow.openNotificationChannel()

  fun onSystemAnswer() = flow.onSystemAnswer()
}
