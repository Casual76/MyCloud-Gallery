package dev.pampa.mycloudgallery.feature.diagnostics.actions

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.IntentCompat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.network.diagnostics.NetworkDiagActions
import dev.pampa.mycloudgallery.feature.diagnostics.R

/**
 * Le azioni suggerite della Diagnostica: quali eseguiamo noi (aprire una schermata di Android, lanciare il test)
 * e quali spettano a `:app` (aprire la finestra dei dispositivi, riprendere l'onboarding: `feature-*` non si
 * conoscono fra loro, il passaggio e' una callback, docs/fase2-progetto.md §1.2).
 *
 * [DiagAction] e' un valore aperto di proposito (core-common): le tre azioni sotto sono di questo modulo, e un'azione
 * sconosciuta si mostra senza tasto.
 */
object DiagnosticsActions {

  /** «Pausa attivita' app se non utilizzata» (androidx: `createManageUnusedAppRestrictionsIntent`). */
  val OPEN_UNUSED_APP_SETTINGS = DiagAction("open_unused_app_settings")

  /** La lista «Mai in sospensione» di Samsung (docs/android.md 3.1: `activity_type` 2), con ripiego sulla batteria. */
  val OPEN_NEVER_SLEEPING_APPS = DiagAction("open_never_sleeping_apps")

  /** Le impostazioni di sicurezza di Android (blocco schermo). */
  val OPEN_SECURITY_SETTINGS = DiagAction("open_security_settings")

  /** Fase 4A (§1.4): "Ferma le scritture sul NAS" (l'emergenza: niente indice ne' prenotazioni). La esegue :app. */
  val NAS_STOP_WRITES = DiagAction("nas_stop_writes")

  /** Fase 4A: riattiva le scritture fermate dall'emergenza (D31 resta: solo le aree di prova). La esegue :app. */
  val NAS_RESUME_WRITES = DiagAction("nas_resume_writes")

  /** Fase 4A (D10): "Ho controllato, continua" su un'area congelata, con la conferma del numero. La esegue :app. */
  val NAS_RELEASE_FREEZE = DiagAction("nas_release_freeze")

  /** Le azioni che questo modulo esegue da solo con un intent verso Android. */
  val INTENT_ACTIONS: Set<DiagAction> = setOf(
    DiagAction.OPEN_BATTERY_SETTINGS,
    DiagAction.OPEN_APP_DETAILS,
    DiagAction.OPEN_NOTIFICATION_SETTINGS,
    DiagAction.OPEN_WIREGUARD,
    OPEN_UNUSED_APP_SETTINGS,
    OPEN_NEVER_SLEEPING_APPS,
    OPEN_SECURITY_SETTINGS,
  )

  /** Le azioni che si risolvono dentro la Diagnostica (il test di connessione). */
  val LOCAL_ACTIONS: Set<DiagAction> = setOf(DiagAction.RUN_CONNECTION_TEST)

  /** L'etichetta del tasto, o 0 per un'azione che non conosciamo (resta senza tasto). */
  @StringRes
  fun labelRes(action: DiagAction): Int = when (action) {
    DiagAction.OPEN_WIREGUARD -> R.string.diagnostics_action_open_wireguard
    DiagAction.OPEN_BATTERY_SETTINGS -> R.string.diagnostics_action_open_battery
    DiagAction.OPEN_APP_DETAILS -> R.string.diagnostics_action_open_app_details
    DiagAction.OPEN_NOTIFICATION_SETTINGS -> R.string.diagnostics_action_open_notifications
    DiagAction.RUN_CONNECTION_TEST -> R.string.diagnostics_action_run_test
    DiagAction.REENTER_NAS_PASSWORD -> R.string.diagnostics_action_reenter_password
    DiagAction.OPEN_DEVICES -> R.string.diagnostics_action_open_devices
    DiagAction.RESUME_ONBOARDING -> R.string.diagnostics_action_resume_onboarding
    OPEN_UNUSED_APP_SETTINGS -> R.string.diagnostics_action_open_unused_apps
    OPEN_NEVER_SLEEPING_APPS -> R.string.diagnostics_action_open_never_sleeping
    OPEN_SECURITY_SETTINGS -> R.string.diagnostics_action_open_security
    NAS_STOP_WRITES -> R.string.diagnostics_action_nas_stop_writes
    NAS_RESUME_WRITES -> R.string.diagnostics_action_nas_resume_writes
    NAS_RELEASE_FREEZE -> R.string.diagnostics_action_nas_release_freeze
    // Verifica funzionale, RE-10 e RE-1/RE-2: portano a Impostazioni → Rete e VPN, dove si fanno. Le esegue :app.
    NetworkDiagActions.RECAPTURE_HOME -> R.string.diagnostics_action_recapture_home
    NetworkDiagActions.OPEN_VPN_CONTROL -> R.string.diagnostics_action_open_vpn_control
    else -> 0
  }

  /**
   * L'azione si puo' proporre: la esegue questo modulo oppure `:app` ha dichiarato di eseguirla ([appActions]).
   * Un tasto che non fa niente e' peggio di nessun tasto.
   */
  fun isAvailable(action: DiagAction, appActions: Set<DiagAction>): Boolean =
    labelRes(action) != 0 && (action in INTENT_ACTIONS || action in LOCAL_ACTIONS || action in appActions)
}

/**
 * Gli intent da provare, nell'ordine, per un'azione: il primo che Android risolve vince. Sono dati puri (nessun
 * `startActivity` qui), cosi' si provano senza uno schermo.
 */
internal object ActionIntents {

  const val SAMSUNG_DEVICE_CARE_PACKAGE = "com.samsung.android.lool"
  const val SAMSUNG_CHECKABLE_LIST_ACTION = "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY"
  const val SAMSUNG_ACTIVITY_TYPE_EXTRA = "activity_type"

  /** `activity_type` 2 = «Mai in sospensione» (docs/android.md 3.1; 0 = sospensione, 1 = sospensione profonda). */
  const val SAMSUNG_NEVER_SLEEPING = 2

  // «BatteryLife» e' la regola del Play Store: l'app e' privata (APK firmato dalla famiglia) e la batteria «Illimitata» e' la
  // condizione del lavoro notturno (docs/android.md 1.3). Il permesso e' dichiarato da :app e riga in sicurezza.md 4-bis.
  @SuppressLint("BatteryLife")
  fun candidates(context: Context, action: DiagAction, wireGuardIntent: Intent?): List<Intent> {
    val pkg = context.packageName
    val appUri = Uri.fromParts("package", pkg, null)
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
    val batteryList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    return when (action) {
      DiagAction.OPEN_WIREGUARD -> listOfNotNull(wireGuardIntent)
      // Prima il dialogo diretto («Consentire sempre in background?»), poi l'elenco, poi la scheda dell'app.
      DiagAction.OPEN_BATTERY_SETTINGS -> listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri),
        batteryList,
        appDetails,
      )
      DiagAction.OPEN_APP_DETAILS -> listOf(appDetails)
      DiagAction.OPEN_NOTIFICATION_SETTINGS ->
        listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg), appDetails)
      DiagnosticsActions.OPEN_UNUSED_APP_SETTINGS -> listOf(
        IntentCompat.createManageUnusedAppRestrictionsIntent(context, pkg),
        appDetails,
      )
      DiagnosticsActions.OPEN_NEVER_SLEEPING_APPS -> listOf(
        Intent(SAMSUNG_CHECKABLE_LIST_ACTION)
          .setPackage(SAMSUNG_DEVICE_CARE_PACKAGE)
          .putExtra(SAMSUNG_ACTIVITY_TYPE_EXTRA, SAMSUNG_NEVER_SLEEPING),
        batteryList,
        appDetails,
      )
      DiagnosticsActions.OPEN_SECURITY_SETTINGS -> listOf(Intent(Settings.ACTION_SECURITY_SETTINGS))
      else -> emptyList()
    }
  }

  /**
   * Lancia il primo intent che parte. Una schermata che non esiste su questo telefono (le impostazioni di Samsung
   * su un Pixel) o un permesso mancante non e' un errore: si passa al ripiego. Restituisce se qualcosa e' partito.
   */
  fun launch(context: Context, action: DiagAction, wireGuardIntent: Intent?): Boolean {
    for (intent in candidates(context, action, wireGuardIntent)) {
      if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
        context.startActivity(intent)
        return true
      } catch (_: ActivityNotFoundException) {
        // la schermata non c'e': prossimo ripiego
      } catch (_: SecurityException) {
        // manca un permesso o l'activity non e' esportata: idem
      }
    }
    return false
  }
}
