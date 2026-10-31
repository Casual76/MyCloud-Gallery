package dev.pampa.mycloudgallery.window

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import dev.pampa.mycloudgallery.MainActivity
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.common.log.GalleryLog

/** L'esito di "Apri in una nuova finestra". */
enum class WindowOpenResult {
  /** La finestra e' partita (nascera' dalla pagina scelta). */
  OPENED,

  /** Le finestre sono gia' due (E-T6): si e' portata davanti quella che c'e', con un messaggio. */
  TOO_MANY,

  /** Non si puo' (Android sotto la 12, nessuna attivita' che risponde): un messaggio, niente altro. */
  UNAVAILABLE,
}

/**
 * Apre e riporta davanti le finestre dell'app (Fase 10, P4, D16, E-T6; docs/fase10-progetto.md §2.4). La seconda finestra e' un **task nuovo** di `MainActivity`
 * (`singleInstancePerTask`) avviato con `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK | FLAG_ACTIVITY_LAUNCH_ADJACENT` e **l'intent pulito del launcher**: la pagina da cui
 * parte non e' nell'intent ma in [WindowStarts] (un evento interno). Il tetto di due finestre non si fida di questo percorso: lo applica `MainActivity.onCreate` con
 * [WindowRegistry.admit], perche' un'altra app puo' avviare `MainActivity` con gli stessi flag.
 */
object WindowLauncher {

  private const val TAG = "WindowLauncher"

  /** L'intent della finestra nuova: quello del launcher (nessun extra, nessun dato, nessun componente altrui) piu' i tre flag. Funzione pura, provata. */
  fun newWindowIntent(context: Context): Intent =
    MainActivity.launcherIntent(context)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

  /**
   * I task di `MainActivity` che il sistema elenca adesso (le finestre vive, e quelle ancora nei recenti): serve a togliere dal [WindowRegistry] i posti che nessuno ha
   * liberato. `null` se il sistema non risponde: nessuna potatura. I task delle altre activity dell'app (selettore, ricezione) non sono finestre e non contano.
   */
  fun liveWindowTasks(context: Context): Set<Int>? = try {
    val manager = context.getSystemService(ActivityManager::class.java) ?: return null
    val main = MainActivity::class.java.name
    manager.appTasks.mapNotNullTo(HashSet()) { task ->
      val info = task.taskInfo
      // Un task senza activity (una finestra rifiutata alla nascita, finita ma rimasta fra i recenti) non e' una finestra: conta solo chi ha ancora delle activity.
      if (info.baseIntent.component?.className == main && info.numActivities > 0) info.taskId else null
    }
  } catch (e: RuntimeException) {
    GalleryLog.w(TAG) { "elenco dei task non disponibile" }
    null
  }

  /** Porta davanti il task [taskId] (la sua activity si ricrea se serve). Falso se il sistema non lo elenca. */
  fun bringToFront(context: Context, taskId: Int): Boolean = try {
    val manager = context.getSystemService(ActivityManager::class.java)
    val task = manager?.appTasks?.firstOrNull { it.taskInfo.taskId == taskId }
    task?.moveToFront()
    task != null
  } catch (e: RuntimeException) {
    GalleryLog.w(TAG) { "impossibile portare davanti la finestra" }
    false
  }

  /**
   * Chiude le finestre dell'app **diverse da quella del task [keep]** (e le toglie dai recenti): serve all'azzeramento, all'uscita dalla famiglia e a "Configura di nuovo" (D42: "la
   * seconda finestra si chiude"), che devono lasciare una finestra sola. Vero per ogni finestra chiusa; non tocca l'activity di [keep]. Funziona anche per le finestre addormentate nei recenti.
   */
  fun closeOtherWindows(context: Context, keep: Int): Int = try {
    val main = MainActivity::class.java.name
    val manager = context.getSystemService(ActivityManager::class.java)
    var closed = 0
    manager?.appTasks?.forEach { task ->
      val info = task.taskInfo
      if (info.baseIntent.component?.className == main && info.taskId != keep) {
        task.finishAndRemoveTask()
        closed++
      }
    }
    closed
  } catch (e: RuntimeException) {
    GalleryLog.w(TAG) { "impossibile chiudere le altre finestre" }
    0
  }

  /** "Hai gia' due finestre": messaggio breve, uguale per chi lo chiede dal menu e per un avvio rifiutato alla nascita. */
  fun tellTooMany(context: Context) {
    Toast.makeText(context.applicationContext, R.string.window_too_many, Toast.LENGTH_SHORT).show()
  }

  /**
   * "Apri in una nuova finestra" da [activity] (che e' una finestra: ha un task). Con le finestre gia' al tetto non ne apre una terza: porta davanti l'altra e lo dice
   * ([WindowOpenResult.TOO_MANY]). Sotto Android 12 non c'e' una seconda finestra ([WindowOpenResult.UNAVAILABLE], L546).
   */
  fun open(
    activity: Activity,
    target: WindowTarget,
    registry: WindowRegistry,
    starts: WindowStarts,
    sdkInt: Int = Build.VERSION.SDK_INT,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
    /** I task che il sistema elenca adesso (per i test; di serie li chiede al sistema). */
    live: Set<Int>? = liveWindowTasks(activity),
    /** Porta davanti un task (per i test; di serie lo chiede al sistema). */
    front: (Int) -> Boolean = { bringToFront(activity, it) },
  ): WindowOpenResult {
    val max = WindowPolicy.maxWindows(sdkInt)
    if (max <= 1) return WindowOpenResult.UNAVAILABLE
    val requester = activity.taskId
    if (!registry.hasRoom(max, live, requester)) {
      registry.othersThan(requester, live).firstOrNull()?.let(front)
      tellTooMany(activity)
      return WindowOpenResult.TOO_MANY
    }
    starts.offer(target, nowMs())
    return try {
      activity.startActivity(newWindowIntent(activity))
      WindowOpenResult.OPENED
    } catch (e: ActivityNotFoundException) {
      starts.cancel()
      Toast.makeText(activity.applicationContext, R.string.window_unavailable, Toast.LENGTH_SHORT).show()
      WindowOpenResult.UNAVAILABLE
    } catch (e: SecurityException) {
      starts.cancel()
      Toast.makeText(activity.applicationContext, R.string.window_unavailable, Toast.LENGTH_SHORT).show()
      WindowOpenResult.UNAVAILABLE
    }
  }
}
