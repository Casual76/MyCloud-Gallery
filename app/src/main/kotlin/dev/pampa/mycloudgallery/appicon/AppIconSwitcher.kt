package dev.pampa.mycloudgallery.appicon

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconArt
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconChoice
import dev.pampa.mycloudgallery.feature.settings.appicon.AppIconSettings
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// L'ICONA DELL'APP A SCELTA (I5, 12/11/2026). Su Android l'icona si cambia abilitando un ingresso del launcher diverso: il manifest ha tre
// `activity-alias` della MainActivity (automatica, chiara, scura), uno solo abilitato alla volta, di serie l'automatica. Qui:
//
// - la scelta si ricorda subito nelle preferenze dell'app (la pagina la segna al tocco);
// - l'ingresso del launcher cambia quando l'app passa in SECONDO PIANO: per Android un'activity aperta da un alias porta il nome dell'alias, e
//   disabilitarlo la chiude (anche con DONT_KILL_APP). Cambiare con l'app aperta la farebbe sparire davanti all'utente; uscendo dall'app nessuno la vede;
// - l'ordine: prima si abilita il nuovo, poi si disabilitano gli altri, mai zero ingressi; se qualcosa va storto si torna all'automatica;
// - all'avvio si riallinea: nessun ingresso abilitato (non dovrebbe succedere) -> si abilita subito quello scelto (abilitare non chiude niente);
//   uno stato diverso dalla scelta (un processo chiuso prima del cambio, un aggiornamento) -> si corregge al primo passaggio in secondo piano;
// - le chiamate al PackageManager girano fuori dal thread principale, una alla volta (l'avvio non le aspetta);
// - (giro 2 della fase I, CG-5) un cambio accodato non si fida dell'uscita che l'ha accodato: subito prima di ogni scrittura si ricontrolla che
//   nessuna activity sia avviata. Se l'utente e' tornato davanti (esce e rientra subito, anche mentre il filo legge il PackageManager), non si tocca
//   piu' niente: la scelta resta in attesa e si fa alla prossima uscita. Se il processo muore a meta'
//   (il nuovo abilitato, il vecchio non ancora spento) restano due ingressi, mai zero: il riallineamento dell'avvio lo vede e finisce il cambio alla
//   prima uscita.

/** I nomi degli ingressi del launcher: gli `activity-alias` del manifest (nomi completi, quelli che il PackageManager vuole). */
object LauncherAliases {
  const val AUTO: String = "dev.pampa.mycloudgallery.LauncherAuto"
  const val LIGHT: String = "dev.pampa.mycloudgallery.LauncherLight"
  const val DARK: String = "dev.pampa.mycloudgallery.LauncherDark"

  val all: List<String> = listOf(AUTO, LIGHT, DARK)

  fun of(choice: AppIconChoice): String = when (choice) {
    AppIconChoice.AUTO -> AUTO
    AppIconChoice.LIGHT -> LIGHT
    AppIconChoice.DARK -> DARK
  }
}

/** Il PackageManager visto dal cambio d'icona: se un ingresso e' abilitato, e abilitarlo o disabilitarlo senza chiudere il processo. */
interface LauncherComponents {
  fun isEnabled(className: String): Boolean

  fun setEnabled(className: String, enabled: Boolean)
}

/** In produzione: `getComponentEnabledSetting` (con il valore del manifest se nessuno l'ha mai cambiato) e `setComponentEnabledSetting(DONT_KILL_APP)`. */
class AndroidLauncherComponents(private val context: Context) : LauncherComponents {

  private fun component(className: String) = ComponentName(context.packageName, className)

  override fun isEnabled(className: String): Boolean {
    val pm = context.packageManager
    return when (pm.getComponentEnabledSetting(component(className))) {
      PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
      PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled(pm, className)
      else -> false
    }
  }

  override fun setEnabled(className: String, enabled: Boolean) {
    val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    context.packageManager.setComponentEnabledSetting(component(className), state, PackageManager.DONT_KILL_APP)
  }

  /** Il valore scritto nel manifest (`android:enabled`): la forma con l'intero vale su ogni versione di Android (quella con `ComponentInfoFlags` e' solo da 13). */
  @Suppress("DEPRECATION")
  private fun manifestEnabled(pm: PackageManager, className: String): Boolean =
    pm.getActivityInfo(component(className), PackageManager.MATCH_DISABLED_COMPONENTS).enabled
}

/** Dove si ricorda la scelta: un file privato dell'app, un valore. Nessun dato personale, mai sincronizzato (vale per questo dispositivo). */
interface AppIconStore {
  fun read(): AppIconChoice

  fun write(choice: AppIconChoice)
}

class PrefsAppIconStore(context: Context) : AppIconStore {
  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  override fun read(): AppIconChoice = AppIconChoice.ofCode(prefs.getString(KEY, null))

  override fun write(choice: AppIconChoice) {
    prefs.edit().putString(KEY, choice.code).apply()
  }

  companion object {
    const val FILE: String = "mcg_app_icon"
    const val KEY: String = "choice"
  }
}

class AppIconSwitcher(
  private val store: AppIconStore,
  private val components: LauncherComponents,
  override val art: AppIconArt,
  /** Dove girano le chiamate al PackageManager: una alla volta, fuori dal thread principale (nei test, subito). */
  private val worker: Executor,
) : AppIconSettings, Application.ActivityLifecycleCallbacks {

  // Letta alla prima richiesta (di solito il riallineamento, fuori dal thread principale): l'avvio dell'app non aspetta le preferenze.
  private val state: MutableStateFlow<AppIconChoice> by lazy { MutableStateFlow(store.read()) }
  override val choice: StateFlow<AppIconChoice> get() = state.asStateFlow()

  /** Le activity avviate: zero = l'app e' in secondo piano (una rotazione non conta). Scritto solo dal thread principale, letto anche dal filo. */
  @Volatile
  private var started = 0

  /** C'e' un cambio da fare al prossimo passaggio in secondo piano. */
  @Volatile
  private var pending = false

  /** Da `Application.onCreate`, prima di qualunque activity. */
  fun install(application: Application) {
    application.registerActivityLifecycleCallbacks(this)
    worker.execute { realign() }
  }

  override fun choose(choice: AppIconChoice) {
    store.write(choice)
    state.value = choice
    if (started == 0) worker.execute { apply() } else pending = true
  }

  /** CG-5: l'app e' fuori dal primo piano adesso (nessuna activity avviata). Si guarda subito prima di ogni scrittura, non solo all'accodamento. */
  private fun away(): Boolean = started == 0

  /** All'avvio: mai zero ingressi (si abilita subito quello scelto); uno stato diverso dalla scelta si corregge in secondo piano. */
  internal fun realign() {
    val wanted = LauncherAliases.of(state.value)
    var enabled = enabledAliases() ?: return
    if (enabled.isEmpty()) {
      GalleryLog.w(TAG) { "nessun ingresso del launcher abilitato: abilito quello scelto" }
      runCatching { components.setEnabled(wanted, true) }.onFailure { GalleryLog.w(TAG) { "abilitazione non riuscita: ${it.javaClass.simpleName}" } }
      enabled = enabledAliases() ?: return
    }
    if (enabled != listOf(wanted)) pending = true
  }

  /**
   * Il cambio: prima il nuovo, poi gli altri; nessuna scrittura se lo stato e' gia' quello; qualunque errore -> l'automatica. Prima di ogni
   * scrittura si ricontrolla che l'app sia ancora fuori ([away]): se e' rientrata, la scelta torna in attesa ([deferred]).
   */
  internal fun apply() {
    val wanted = LauncherAliases.of(state.value)
    val enabled = enabledAliases() ?: return
    if (enabled == listOf(wanted)) return
    if (!away()) return deferred()
    try {
      components.setEnabled(wanted, true)
      for (other in LauncherAliases.all.filter { it != wanted }) {
        // Disabilitare l'ingresso da cui l'activity e' aperta la chiude: con l'app rientrata non si spegne niente (restano due ingressi, mai zero).
        if (!away()) return deferred()
        components.setEnabled(other, false)
      }
      check(enabledAliases() == listOf(wanted)) { "stato degli ingressi diverso dopo il cambio" }
    } catch (e: RuntimeException) {
      GalleryLog.w(TAG) { "cambio d'icona non riuscito (${e.javaClass.simpleName}): torno all'automatica" }
      backToAuto()
    }
  }

  /** L'app e' rientrata prima che il cambio finisse: si rifa' alla prossima uscita (lo stato resta coerente: almeno un ingresso abilitato). */
  private fun deferred() {
    GalleryLog.d(TAG) { "l'app e' rientrata in primo piano: il cambio d'icona aspetta la prossima uscita" }
    pending = true
  }

  private fun backToAuto() {
    runCatching {
      components.setEnabled(LauncherAliases.AUTO, true)
      LauncherAliases.all.filter { it != LauncherAliases.AUTO }.forEach { components.setEnabled(it, false) }
    }
    store.write(AppIconChoice.AUTO)
    state.value = AppIconChoice.AUTO
  }

  /** Gli ingressi abilitati, nell'ordine di [LauncherAliases.all]; null se il PackageManager non risponde (non si tocca niente). */
  private fun enabledAliases(): List<String>? =
    runCatching { LauncherAliases.all.filter { components.isEnabled(it) } }.getOrNull()

  internal fun onStarted() {
    started++
  }

  internal fun onStopped(changingConfigurations: Boolean) {
    started = (started - 1).coerceAtLeast(0)
    if (started == 0 && !changingConfigurations && pending) {
      pending = false
      worker.execute { apply() }
    }
  }

  override fun onActivityStarted(activity: Activity) = onStarted()

  override fun onActivityStopped(activity: Activity) = onStopped(activity.isChangingConfigurations)

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

  override fun onActivityResumed(activity: Activity) = Unit

  override fun onActivityPaused(activity: Activity) = Unit

  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

  override fun onActivityDestroyed(activity: Activity) = Unit

  companion object {
    private const val TAG = "AppIcon"

    /** Le risorse vere dell'icona per le anteprime delle Impostazioni (il primo piano e i due sfondi fissi). */
    val ART: AppIconArt = AppIconArt(
      foreground = R.mipmap.ic_launcher_foreground,
      lightBackground = R.color.ic_launcher_background_light,
      darkBackground = R.color.ic_launcher_background_dark,
    )

    /** Un filo solo, che nasce quando serve e muore dopo qualche secondo: il cambio d'icona capita di rado. */
    fun serialWorker(): Executor =
      ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable -> Thread(runnable, "mcg-icona-app") }
  }
}
