package dev.pampa.mycloudgallery.notify

import android.Manifest
import android.content.Intent
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyAccess
import dev.pampa.mycloudgallery.core.memories.notify.NotifyAccessState
import dev.pampa.mycloudgallery.feature.memories.contract.NotificationAccess
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.main.PermissionAsks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Cosa succede a un tocco su "Consenti" o sul canale (D18): il dialogo di sistema, le impostazioni dell'app, quelle del canale, o niente. */
enum class NotifyPermissionStep {
  /** Android puo' ancora chiedere il permesso: il dialogo di sistema, **da un tocco dell'utente**. */
  ASK_SYSTEM,

  /** Negato per sempre, o le notifiche dell'app sono spente: le impostazioni di Android dell'app. */
  OPEN_APP_SETTINGS,

  /** Il canale "Ricordi" e' spento: le impostazioni di Android di quel canale. */
  OPEN_CHANNEL_SETTINGS,

  NONE,
}

/**
 * **Il flusso del permesso delle notifiche** (Fase 9, P10; docs/fase9-progetto.md D18, E-NT1, E-NT2; E-O4): la regola in un posto solo, **mai una richiesta che l'utente
 * non ha chiesto**. La pagina delle impostazioni (P9, `MemoriesSettingsController`) legge [NotificationPermissionAccess] e al tocco chiama [NotifyPermissionActions], che
 * P13 lega a `MemoriesActions.requestNotificationPermission` e `openNotificationChannel`. Il lavoro e la notifica ([MemoryNotifier]) non chiedono mai niente: con il
 * permesso negato o il canale spento non partono richieste automatiche, solo un codice in Diagnostica.
 */
object NotifyPermissionFlow {

  /**
   * Lo stato del sistema ([state]) nei quattro stati della pagina. [deniedForGood]: il permesso e' stato chiesto e Android non lo chiede piu' (`PermissionAsks`); sulla
   * pagina vuol dire "Apri le impostazioni di Android" invece di "Consenti".
   */
  fun permissionOf(state: NotifyAccessState, deniedForGood: Boolean): NotifyPermission = when (state) {
    NotifyAccessState.GRANTED -> NotifyPermission.GRANTED
    NotifyAccessState.PERMISSION_MISSING -> if (deniedForGood) NotifyPermission.DENIED else NotifyPermission.ASKABLE
    NotifyAccessState.APP_OFF -> NotifyPermission.DENIED
    NotifyAccessState.CHANNEL_OFF -> NotifyPermission.CHANNEL_OFF
  }

  /** Il passo di un tocco su "Consenti" per lo stato [permission] della pagina. */
  fun stepFor(permission: NotifyPermission): NotifyPermissionStep = when (permission) {
    NotifyPermission.GRANTED -> NotifyPermissionStep.NONE
    NotifyPermission.ASKABLE -> NotifyPermissionStep.ASK_SYSTEM
    NotifyPermission.DENIED -> NotifyPermissionStep.OPEN_APP_SETTINGS
    NotifyPermission.CHANNEL_OFF -> NotifyPermissionStep.OPEN_CHANNEL_SETTINGS
  }
}

/**
 * [NotificationAccess] di `feature-memories` sopra lo stato del sistema ([MemoryNotifier] come [MemoryNotifyAccess]) e `PermissionAsks`: distingue "Consenti" (Android
 * lo chiede ancora) da "Apri le impostazioni" (negato per sempre). [shouldExplain] dice, per un permesso, se Android propone ancora di spiegare (`null` senza activity: nel
 * dubbio "si puo' chiedere"). Si rilegge solo quando la pagina lo chiede ([refresh]): nessuna richiesta, nessun polling.
 */
internal class NotificationPermissionAccess(
  private val system: MemoryNotifyAccess,
  private val asks: PermissionAsks,
  private val shouldExplain: (String) -> Boolean? = { null },
) : NotificationAccess {

  private val flow = MutableStateFlow(read())

  override val state: StateFlow<NotifyPermission> = flow

  override fun refresh() {
    system.refresh()
    flow.value = read()
  }

  private fun read(): NotifyPermission {
    val now = system.state.value
    val permission = listOf(Manifest.permission.POST_NOTIFICATIONS)
    if (now == NotifyAccessState.GRANTED) {
      // Un permesso concesso non e' piu' "negato" (GA-4): se l'utente lo toglie dalle impostazioni, il dialogo di sistema torna ad aprirsi.
      asks.forgetGranted(permission)
      return NotifyPermission.GRANTED
    }
    val forGood = now == NotifyAccessState.PERMISSION_MISSING && PermissionAsks.deniedForGood(permission, asks::asked, shouldExplain)
    return NotifyPermissionFlow.permissionOf(now, forGood)
  }
}

/**
 * Cosa fa la pagina al tocco (`MemoriesActions.requestNotificationPermission` e `openNotificationChannel`, che P13 delega qui): **solo** [requestNotificationPermission] e
 * [openNotificationChannel] aprono qualcosa, e solo perche' l'utente ha toccato. [askSystem] lancia il dialogo di sistema (`RequestPermission` per `POST_NOTIFICATIONS`, nel
 * contesto che ha l'activity); [open] avvia una pagina delle impostazioni di Android. Prima di chiedere si ricorda di aver chiesto (`PermissionAsks`), e dopo si rilegge lo stato.
 */
internal class NotifyPermissionActions(
  private val access: NotificationPermissionAccess,
  private val notifier: MemoryNotifier,
  private val asks: PermissionAsks,
  private val askSystem: () -> Unit,
  private val open: (Intent) -> Unit,
) {

  /** "Consenti": il dialogo di sistema se Android lo permette ancora, altrimenti le impostazioni dell'app (D18). */
  fun requestNotificationPermission() {
    when (NotifyPermissionFlow.stepFor(access.state.value)) {
      NotifyPermissionStep.ASK_SYSTEM -> {
        asks.markAsked(listOf(Manifest.permission.POST_NOTIFICATIONS))
        askSystem()
      }
      NotifyPermissionStep.OPEN_APP_SETTINGS -> open(notifier.appNotificationSettingsIntent())
      NotifyPermissionStep.OPEN_CHANNEL_SETTINGS -> open(notifier.channelSettingsIntent())
      NotifyPermissionStep.NONE -> Unit
    }
  }

  /** Il canale "Ricordi" nelle impostazioni di Android (E-NT2). */
  fun openNotificationChannel() = open(notifier.channelSettingsIntent())

  /** La risposta del dialogo di sistema e' arrivata: si rilegge lo stato, la pagina lo vede da sola. */
  fun onSystemAnswer() = access.refresh()
}
