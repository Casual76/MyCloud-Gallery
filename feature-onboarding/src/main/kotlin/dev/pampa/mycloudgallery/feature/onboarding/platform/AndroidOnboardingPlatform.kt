package dev.pampa.mycloudgallery.feature.onboarding.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.pampa.mycloudgallery.core.security.device.ScreenLockCheck

/**
 * L'implementazione vera di [OnboardingPlatform]: permessi di Android, impostazioni di sistema, blocco schermo.
 *
 * I permessi si leggono da `checkSelfPermission` (e le notifiche da `NotificationManagerCompat`, che vale a ogni
 * versione). Lo stato e' osservabile: [permissions] legge un contatore che [refresh] incrementa a ogni ritorno in
 * primo piano e a ogni esito di un dialogo, quindi la pagina si aggiorna da sola (E-O4, E-P1).
 *
 * Ogni "apri" prova l'impostazione piu' precisa e, se quel telefono non ce l'ha, ripiega su una piu' generica (fino
 * alle impostazioni dell'app): un'impostazione sparita da una versione di One UI non deve fermare la configurazione.
 */
internal class AndroidOnboardingPlatform(
  private val context: Context,
  private val sdkInt: Int = Build.VERSION.SDK_INT,
) : OnboardingPlatform {

  private val tick = mutableIntStateOf(0)
  private var requestedKinds by mutableStateOf(emptySet<PermissionKind>())

  /** Lo imposta `rememberAndroidPlatform`: lancia il dialogo di sistema per i permessi indicati. */
  var launcher: (Array<String>) -> Unit = {}

  fun refresh() {
    tick.intValue++
  }

  override fun permissions(): PermissionsSnapshot {
    tick.intValue // sottoscrive chi legge: si ricompone quando Android cambia qualcosa
    return PermissionsSnapshot(
      states = mapOf(
        PermissionKind.NOTIFICATIONS to notifications(),
        PermissionKind.MEDIA to media(),
        PermissionKind.MEDIA_LOCATION to if (granted(Manifest.permission.ACCESS_MEDIA_LOCATION)) PermissionState.GRANTED else PermissionState.DENIED,
        PermissionKind.LOCAL_NETWORK to PermissionState.NOT_NEEDED,
      ),
      requested = requestedKinds,
      screenLock = ScreenLockCheck.status(context),
    )
  }

  private fun granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  private fun notifications(): PermissionState =
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) PermissionState.GRANTED else PermissionState.DENIED

  /** Tutto, "foto selezionate" (E-P1), o niente. */
  private fun media(): PermissionState = when {
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> when {
      granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> PermissionState.GRANTED
      granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PermissionState.PARTIAL
      else -> PermissionState.DENIED
    }
    sdkInt >= Build.VERSION_CODES.TIRAMISU ->
      if (granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)) PermissionState.GRANTED else PermissionState.DENIED
    else -> if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) PermissionState.GRANTED else PermissionState.DENIED
  }

  override fun requestPermission(kind: PermissionKind) {
    requestedKinds = requestedKinds + kind
    when (kind) {
      PermissionKind.NOTIFICATIONS ->
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) launcher(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else openNotificationSettings()
      PermissionKind.MEDIA -> launcher(mediaPermissions())
      PermissionKind.MEDIA_LOCATION -> launcher(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
      PermissionKind.LOCAL_NETWORK -> openAppSettings()
    }
  }

  private fun mediaPermissions(): Array<String> = when {
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
      Manifest.permission.READ_MEDIA_IMAGES,
      Manifest.permission.READ_MEDIA_VIDEO,
      Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
  }

  private fun openNotificationSettings(): Boolean = tryOpen(
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
  ) || openAppSettings()

  override fun openAppSettings(): Boolean =
    tryOpen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))

  override fun openSecuritySettings(): Boolean =
    tryOpen(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)) || tryOpen(Intent(Settings.ACTION_SECURITY_SETTINGS))

  override fun openWifiSettings(): Boolean = tryOpen(Intent(Settings.ACTION_WIFI_SETTINGS))

  override fun open(intent: Intent): Boolean = tryOpen(intent)

  /** "Batteria: Illimitata": prima il dialogo diretto, poi l'elenco delle ottimizzazioni, poi l'app. */
  @SuppressLint("BatteryLife")
  override fun openBatteryExemption(): Boolean =
    tryOpen(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) ||
      tryOpen(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
      openAppSettings()

  /** "App mai in sospensione" e' una pagina di One UI (Device Care): se manca, le impostazioni della batteria. */
  override fun openBackgroundLimits(): Boolean =
    tryOpen(Intent().setClassName(SAMSUNG_DEVICE_CARE, SAMSUNG_BATTERY_ACTIVITY)) ||
      tryOpen(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) ||
      openAppSettings()

  /** "Pausa attivita' se inutilizzata": la pagina che Android indica per questa app, o le impostazioni dell'app. */
  override fun openUnusedAppSettings(): Boolean {
    val intent = IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
    return tryOpen(intent) || openAppSettings()
  }

  override fun isTablet(): Boolean = context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_DP

  private fun tryOpen(intent: Intent): Boolean {
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      context.startActivity(intent)
      true
    } catch (_: ActivityNotFoundException) {
      false
    } catch (_: SecurityException) {
      false
    }
  }

  private companion object {
    const val SAMSUNG_DEVICE_CARE = "com.samsung.android.lool"
    const val SAMSUNG_BATTERY_ACTIVITY = "com.samsung.android.sm.ui.battery.BatteryActivity"
    const val TABLET_MIN_DP = 600
  }
}

/**
 * La piattaforma vera, legata alla composizione: il dialogo dei permessi e il ritorno in primo piano la
 * aggiornano da soli.
 */
@Composable
internal fun rememberAndroidPlatform(): AndroidOnboardingPlatform {
  val context = LocalContext.current
  val platform = remember(context) { AndroidOnboardingPlatform(context) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    platform.refresh()
  }
  platform.launcher = { permissions -> launcher.launch(permissions) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { platform.refresh() }
  return platform
}
