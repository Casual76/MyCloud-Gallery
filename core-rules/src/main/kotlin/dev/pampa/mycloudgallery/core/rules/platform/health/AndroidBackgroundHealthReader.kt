package dev.pampa.mycloudgallery.core.rules.platform.health

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.rules.platform.crash.AnrTrace
import dev.pampa.mycloudgallery.core.rules.platform.crash.CrashCause
import dev.pampa.mycloudgallery.core.rules.platform.crash.CrashKind
import dev.pampa.mycloudgallery.core.rules.platform.crash.CrashLogStore
import dev.pampa.mycloudgallery.core.rules.platform.crash.CrashRecord
import dev.pampa.mycloudgallery.core.rules.platform.crash.ThreadKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * La lettura vera della salute del background (P8, docs/fase2-progetto.md §2.6 "Avvisi Android", docs/android.md
 * temi 1-3 e 6 e appendice A). Ogni campo di [BackgroundHealth] ha qui la sua fonte:
 *
 * | Campo | Fonte | Da quando |
 * |---|---|---|
 * | `ignoringBatteryOpt` | `PowerManager.isIgnoringBatteryOptimizations` ("Illimitata" su One UI) | 23 |
 * | `standbyBucket` | `UsageStatsManager.getAppStandbyBucket()` (la propria app, senza permessi) | 28 |
 * | `backgroundRestricted` | `ActivityManager.isBackgroundRestricted()` | 28 |
 * | `unusedAppRestrictions` | `PackageManagerCompat.getUnusedAppRestrictionsStatus` | 30 (backport 23) |
 * | `permissions` | `checkSelfPermission` e `areNotificationsEnabled` | |
 * | `userUnlocked` | `UserManager.isUserUnlocked()` | 24 |
 * | `bootCount` | `Settings.Global.BOOT_COUNT` | 24 |
 * | `lastExitReasons` | `ActivityManager.getHistoricalProcessExitReasons` | 30 |
 * | `isSystemUser` | `UserManager.isSystemUser` | 23 |
 * | `recentCrashes` | il registro locale dei crash (`CrashLogStore`) e `ApplicationExitInfo.getTraceInputStream()` per gli ANR | 30 per gli ANR |
 *
 * **Niente permessi speciali e niente supposizioni.** Se Android non risponde (servizio assente, eccezione,
 * API che sul telefono non esiste) il campo resta `null`/`UNKNOWN`/vuoto: un avviso sbagliato insegna a
 * ignorare gli avvisi. Una lettura che fallisce non porta con se' le altre.
 *
 * **Mai sul thread del disegno**: la lettura tocca sei servizi di sistema e, su Android 10, aspetta la
 * risposta dell'app di verifica dei permessi; per questo e' una `suspend` che passa dall'I/O e rinuncia a
 * quell'ultima attesa dopo [restrictionsTimeout].
 *
 * @param sdkInt la versione di Android da usare per scegliere le API. Di norma e' quella del telefono; i test
 *   la fissano per provare i rami che Robolectric non puo' girare (qui c'e' solo l'SDK 35).
 */
class AndroidBackgroundHealthReader(
  context: Context,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
  private val sdkInt: Int = Build.VERSION.SDK_INT,
  private val restrictionsTimeoutMillis: Long = RESTRICTIONS_TIMEOUT_MILLIS,
  private val maxExitRecords: Int = MAX_EXIT_RECORDS,
  crashLogStore: CrashLogStore? = null,
) : BackgroundHealthReader {

  private val app: Context = context.applicationContext ?: context
  private val packageName: String = app.packageName

  /** Il registro dei crash (C472): la cartella si apre alla prima lettura, mai alla costruzione (il grafo si costruisce sul thread principale all'avvio). */
  private val crashLog: CrashLogStore by lazy(LazyThreadSafetyMode.NONE) { crashLogStore ?: CrashLogStore.inNoBackupDir(app) }

  override suspend fun read(): BackgroundHealth = withContext(dispatchers.io) {
    BackgroundHealth(
      ignoringBatteryOpt = readIgnoringBatteryOpt(),
      standbyBucket = readStandbyBucket(),
      backgroundRestricted = readBackgroundRestricted(),
      unusedAppRestrictions = readUnusedAppRestrictions(),
      permissions = readPermissions(),
      userUnlocked = readUserUnlocked(),
      bootCount = readBootCount(),
      lastExitReasons = readExitReasons(),
      isSystemUser = readIsSystemUser(),
      recentCrashes = readRecentCrashes(),
    )
  }

  // --- Batteria, bucket, limitazione ----------------------------------------------------------------

  internal fun readIgnoringBatteryOpt(): Boolean? = safe {
    app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName)
  }

  internal fun readStandbyBucket(): StandbyBucket {
    if (sdkInt < Build.VERSION_CODES.P) return StandbyBucket.UNKNOWN
    val raw = safe { app.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket }
    return if (raw == null) StandbyBucket.UNKNOWN else HealthMappers.standbyBucket(raw)
  }

  internal fun readBackgroundRestricted(): Boolean? {
    if (sdkInt < Build.VERSION_CODES.P) return null
    return safe { app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted }
  }

  // --- "Pausa se inutilizzata" ----------------------------------------------------------------------

  internal suspend fun readUnusedAppRestrictions(): UnusedAppRestrictions {
    val future = try {
      PackageManagerCompat.getUnusedAppRestrictionsStatus(app)
    } catch (e: Exception) {
      return UnusedAppRestrictions.UNKNOWN
    }
    return try {
      // Su Android 11+ il risultato e' gia' pronto; sull'10 puo' dover interrogare un'altra app: non si
      // aspetta oltre il tempo concesso (una risposta tardiva non vale un'interfaccia bloccata).
      val raw = runInterruptible { future.get(restrictionsTimeoutMillis, TimeUnit.MILLISECONDS) }
      HealthMappers.unusedAppRestrictions(raw)
    } catch (e: CancellationException) {
      future.cancel(true)
      throw e
    } catch (e: Exception) {
      future.cancel(true)
      UnusedAppRestrictions.UNKNOWN
    }
  }

  // --- Permessi -------------------------------------------------------------------------------------

  /** Tutti i permessi di [HealthPermission], sempre: chi legge non deve distinguere "assente" da "negato". */
  internal fun readPermissions(): Map<HealthPermission, PermissionGrant> =
    HealthPermission.entries.associateWith { permission -> grantOf(permission) }

  // Le costanti READ_MEDIA_* sono inlined dal compilatore e si leggono solo se `sdkInt` le ammette (`checked`): lint non vede la guardia.
  @SuppressLint("InlinedApi")
  private fun grantOf(permission: HealthPermission): PermissionGrant = try {
    when (permission) {
      // `areNotificationsEnabled` unisce il permesso (Android 13+) e l'interruttore dell'utente: e' quello
      // che decide se una notifica arriva, ed esiste anche prima di POST_NOTIFICATIONS.
      HealthPermission.NOTIFICATIONS -> {
        val enabled = app.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()
        if (enabled == null) PermissionGrant.DENIED else if (enabled) PermissionGrant.GRANTED else PermissionGrant.DENIED
      }
      // Sotto Android 13 READ_MEDIA_* non esiste (e l'app non dichiara READ_EXTERNAL_STORAGE, docs/sicurezza.md
      // 4-bis): non c'e' nulla da concedere, non e' un rifiuto.
      HealthPermission.MEDIA_IMAGES -> checked(Manifest.permission.READ_MEDIA_IMAGES, sinceSdk = Build.VERSION_CODES.TIRAMISU)
      HealthPermission.MEDIA_VIDEO -> checked(Manifest.permission.READ_MEDIA_VIDEO, sinceSdk = Build.VERSION_CODES.TIRAMISU)
      HealthPermission.MEDIA_PARTIAL ->
        checked(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, sinceSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
      HealthPermission.MEDIA_LOCATION -> checked(Manifest.permission.ACCESS_MEDIA_LOCATION, sinceSdk = Build.VERSION_CODES.Q)
      HealthPermission.CAMERA -> checked(Manifest.permission.CAMERA, sinceSdk = 1)
      HealthPermission.WIREGUARD_CONTROL -> wireGuardControl()
    }
  } catch (e: Exception) {
    // Nel dubbio il permesso non c'e': per un permesso e' la risposta sicura (l'avviso invita a controllare).
    PermissionGrant.DENIED
  }

  private fun checked(name: String, sinceSdk: Int): PermissionGrant = when {
    sdkInt < sinceSdk -> PermissionGrant.NOT_APPLICABLE
    ContextCompat.checkSelfPermission(app, name) == PackageManager.PERMISSION_GRANTED -> PermissionGrant.GRANTED
    else -> PermissionGrant.DENIED
  }

  /**
   * `CONTROL_TUNNELS` e' un permesso che dichiara l'app WireGuard: se WireGuard non e' installato il permesso
   * non esiste e non c'e' niente da concedere (la Diagnostica della rete dice "WireGuard non installato"). Se
   * esiste, e' concesso o negato come qualunque altro (E-R3, E-P20: ogni profilo concede il suo).
   */
  private fun wireGuardControl(): PermissionGrant {
    try {
      app.packageManager.getPermissionInfo(WIREGUARD_CONTROL_TUNNELS, 0)
    } catch (_: PackageManager.NameNotFoundException) {
      return PermissionGrant.NOT_APPLICABLE
    }
    return checked(WIREGUARD_CONTROL_TUNNELS, sinceSdk = 1)
  }

  // --- Sblocco, riavvii, uscite, profilo ------------------------------------------------------------

  /** Senza risposta si presume sbloccato (come [BackgroundHealth.UNKNOWN]): un avviso "in attesa di sblocco" sbagliato e' peggio di nessuno. */
  internal fun readUserUnlocked(): Boolean =
    safe { app.getSystemService(UserManager::class.java)?.isUserUnlocked } ?: true

  internal fun readBootCount(): Int? = safe {
    try {
      Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Settings.SettingNotFoundException) {
      null
    }
  }

  // getHistoricalProcessExitReasons esiste dall'API 30 (minSdk 29): la guardia e' `sdkInt`, che lint non vede.
  @SuppressLint("NewApi")
  internal fun readExitReasons(): List<ExitRecord> {
    if (sdkInt < Build.VERSION_CODES.R) return emptyList()
    return safe {
      app.getSystemService(ActivityManager::class.java)
        ?.getHistoricalProcessExitReasons(packageName, 0, maxExitRecords)
        ?.map { info -> ExitRecord(HealthMappers.exitKind(info.reason), info.timestamp) }
        ?.sortedByDescending { it.atMillis }
    } ?: emptyList()
  }

  /**
   * I crash e gli ANR recenti con il punto del codice (C472): il registro locale dei crash e, da Android 11, la traccia dell'ANR che il sistema conserva (solo le cornici
   * nostre del thread principale, [AnrTrace]). Mai un messaggio, mai un percorso. Dal piu' recente, al piu' [MAX_CRASH_RECORDS].
   */
  internal fun readRecentCrashes(): List<CrashRecord> {
    val crashes = safe { crashLog.read() } ?: emptyList()
    return (crashes + readAnrRecords()).sortedByDescending { it.atMillis }.take(MAX_CRASH_RECORDS)
  }

  // getTraceInputStream esiste dall'API 30 (minSdk 29): la guardia e' `sdkInt`, che lint non vede.
  @SuppressLint("NewApi")
  private fun readAnrRecords(): List<CrashRecord> {
    if (sdkInt < Build.VERSION_CODES.R) return emptyList()
    return safe {
      app.getSystemService(ActivityManager::class.java)
        ?.getHistoricalProcessExitReasons(packageName, 0, maxExitRecords)
        ?.filter { it.reason == android.app.ApplicationExitInfo.REASON_ANR }
        ?.take(MAX_ANR_TRACES)
        ?.mapNotNull { info ->
          val frames = safe { info.traceInputStream?.let { AnrTrace.mainThreadFrames(it) } } ?: return@mapNotNull null
          CrashRecord(
            kind = CrashKind.ANR,
            atMillis = info.timestamp,
            // La versione dell'ANR non si conosce (il sistema non la da'): 0 vuol dire "non nota".
            versionCode = 0L,
            thread = ThreadKind.MAIN,
            mainProcess = true,
            chain = listOf(CrashCause(ANR_CLASS, frames)),
          )
        }
    } ?: emptyList()
  }

  /** Il profilo principale. Senza risposta si presume di si': l'avviso sul profilo secondario non si inventa. */
  internal fun readIsSystemUser(): Boolean =
    safe { app.getSystemService(UserManager::class.java)?.isSystemUser } ?: true

  /** Esegue [block]; qualunque guasto del sistema (servizio morto, permesso mancante, API assente) diventa `null`. */
  private inline fun <T> safe(block: () -> T?): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  companion object {
    /** Il permesso con cui WireGuard lascia comandare i suoi tunnel (docs/sicurezza.md 4-bis). */
    const val WIREGUARD_CONTROL_TUNNELS: String = "com.wireguard.android.permission.CONTROL_TUNNELS"

    /** Quanto si aspetta l'app di verifica dei permessi su Android 10. */
    const val RESTRICTIONS_TIMEOUT_MILLIS: Long = 2_000L

    /** Quante uscite del processo si leggono: bastano a vedere una serie di crash o di arresti forzati. */
    const val MAX_EXIT_RECORDS: Int = 8

    /** Quanti crash e ANR si riportano alla Diagnostica (il registro ne tiene dieci). */
    const val MAX_CRASH_RECORDS: Int = 10

    /** Le tracce di ANR che si leggono (ognuna e' un testo di molti thread): le piu' recenti bastano a capire se si ripete. */
    private const val MAX_ANR_TRACES = 3

    /** La "classe" che descrive un ANR: non c'e' un'eccezione, c'e' il thread principale fermo. */
    private const val ANR_CLASS = "android.app.ApplicationNotResponding"
  }
}
