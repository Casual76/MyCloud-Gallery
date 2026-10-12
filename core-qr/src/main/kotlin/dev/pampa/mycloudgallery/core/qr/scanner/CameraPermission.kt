package dev.pampa.mycloudgallery.core.qr.scanner

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.pampa.mycloudgallery.core.common.log.GalleryLog

/** Quante volte il sistema ha risposto "no" alla richiesta, da quel che si sa in questa schermata. */
internal enum class Denial {
  /** Nessuna risposta negativa (mai chiesto, o concesso). */
  None,

  /** Negato, ma il sistema chiedera' ancora (e c'e' una spiegazione da dare). */
  Once,

  /** Negato e il sistema non chiedera' piu': "non chiedere piu'", o secondo no (Android 11+). */
  Permanent,
}

/**
 * Cosa ricordare dopo la risposta del sistema. Il sistema non dice mai "e' per sempre": lo si deduce.
 * Dopo un no, `shouldShowRequestPermissionRationale` e' vero se la domanda comparira' ancora (la spiegazione
 * serve) e falso se l'utente ha scelto di non essere piu' disturbato.
 */
internal fun denialAfter(granted: Boolean, canAskAgain: Boolean): Denial = when {
  granted -> Denial.None
  canAskAgain -> Denial.Once
  else -> Denial.Permanent
}

/** Lo stato mostrato all'utente. Senza fotocamera nel dispositivo non ha senso chiedere il permesso. */
internal fun accessOf(hasCamera: Boolean, granted: Boolean, denial: Denial): CameraAccess = when {
  !hasCamera -> CameraAccess.NoCamera
  granted -> CameraAccess.Granted
  denial == Denial.Permanent -> CameraAccess.DeniedPermanently
  denial == Denial.Once -> CameraAccess.Denied
  else -> CameraAccess.NotAsked
}

/** Il permesso della fotocamera, con le due cose che si possono fare: chiederlo, o aprire le impostazioni. */
@Stable
internal class CameraPermission(
  val access: CameraAccess,
  val request: () -> Unit,
  val openSettings: () -> Unit,
)

/**
 * Il permesso `CAMERA` come stato della schermata.
 *
 * Si rilegge a ogni ritorno in primo piano: chi e' andato nelle impostazioni a sbloccarlo trova lo scanner
 * gia' acceso. Il "no" si ricorda anche se il processo viene riavviato mentre la schermata e' aperta.
 */
@Composable
internal fun rememberCameraPermission(): CameraPermission {
  val context = LocalContext.current
  val hasCamera = remember(context) { hasCameraHardware(context) }
  var granted by remember { mutableStateOf(isCameraGranted(context)) }
  var denial by rememberSaveable { mutableStateOf(Denial.None) }

  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    granted = ok
    denial = denialAfter(ok, canAskAgain = canAskCameraAgain(context))
  }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    granted = isCameraGranted(context)
    if (granted) denial = Denial.None
  }

  val access = accessOf(hasCamera, granted, denial)
  return remember(access, launcher, context) {
    CameraPermission(
      access = access,
      request = { launcher.launch(Manifest.permission.CAMERA) },
      openSettings = { openAppSettings(context) },
    )
  }
}

internal fun hasCameraHardware(context: Context): Boolean =
  context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

internal fun isCameraGranted(context: Context): Boolean =
  ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Se manca l'activity non si puo' sapere: si lascia riprovare (e' l'errore meno costoso). */
private fun canAskCameraAgain(context: Context): Boolean {
  val activity = context.findActivity() ?: return true
  return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

/** La pagina del sistema con i permessi dell'app: l'unico posto dove sbloccare un "non chiedere piu'". */
private fun openAppSettings(context: Context) {
  val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
  if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  try {
    context.startActivity(intent)
  } catch (_: ActivityNotFoundException) {
    GalleryLog.w(TAG) { "impostazioni dell'app non apribili" }
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

private const val TAG = "CameraPermission"
