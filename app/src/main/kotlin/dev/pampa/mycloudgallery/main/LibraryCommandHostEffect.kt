package dev.pampa.mycloudgallery.main

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.pampa.mycloudgallery.feature.library.library.LibraryCommands
import dev.pampa.mycloudgallery.gallery.AppLibraryCommands
import dev.pampa.mycloudgallery.gallery.LibraryCommandHost

/**
 * Lega i comandi della Libreria ([AppLibraryCommands]) all'activity finche' la scheda e' visibile: i dialoghi dei permessi,
 * il selettore di condivisione e il dialogo di sistema del cestino.
 *
 * **I permessi partono solo da un tocco** ("Consenti accesso", "Consenti tutto", "Consenti"): nessuna richiesta
 * all'apertura (docs/sicurezza.md §4-bis). Nessun permesso nuovo: sono quelli gia' dichiarati (foto e video, "foto
 * selezionate", posizione dei media; `READ_EXTERNAL_STORAGE` fino ad Android 12). Se Android non mostra piu' il dialogo
 * (rifiutato due volte) il tocco apre le impostazioni dell'app, dove l'utente decide.
 *
 * @param onAccessChanged dopo ogni esito: l'accesso si rilegge (mai memorizzato, E-P1, E-P15).
 */
@Composable
internal fun LibraryCommandHostEffect(commands: LibraryCommands, onAccessChanged: () -> Unit) {
  val app = commands as? AppLibraryCommands ?: return
  val context = LocalContext.current
  val latestOnAccessChanged = rememberUpdatedState(onAccessChanged)
  val asked = remember { HashSet<String>() }
  val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    latestOnAccessChanged.value()
  }
  val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
    latestOnAccessChanged.value()
  }
  val host = remember(context) {
    object : LibraryCommandHost {
      override fun requestMediaAccess() = request(mediaPermissions() + Manifest.permission.ACCESS_MEDIA_LOCATION, full = mediaPermissions())

      override fun requestMediaLocation() = request(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), full = arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))

      override fun startChooser(intent: Intent): Boolean = tryStart(context, Intent.createChooser(intent, null))

      override fun launchTrashRequest(sender: IntentSender): Boolean =
        try {
          trash.launch(IntentSenderRequest.Builder(sender).build())
          true
        } catch (_: RuntimeException) {
          // Troppi URI per la transazione Binder, o il sistema rifiuta la richiesta: la Libreria lo dice (I8).
          false
        }

      /** [wanted] si chiede insieme; basta che manchi uno di [full] perche' la richiesta abbia senso. */
      private fun request(wanted: Array<String>, full: Array<String>) {
        val missing = full.filter { !granted(context, it) }
        if (missing.isEmpty() && !needsPartialUpgrade(context, full)) {
          latestOnAccessChanged.value()
          return
        }
        val activity = context.findActivity()
        // Gia' chiesti in questa sessione e Android non spiega piu' niente: il dialogo non si aprirebbe.
        val blocked = missing.isNotEmpty() && missing.all { it in asked && activity?.shouldShowRequestPermissionRationale(it) == false }
        if (blocked) {
          tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
          return
        }
        asked += wanted
        permissions.launch(wanted)
      }
    }
  }
  DisposableEffect(app, host) {
    app.host = host
    onDispose { if (app.host === host) app.host = null }
  }
}

/** I permessi di lettura dei media per questa versione di Android (gli stessi del passo 9 dell'onboarding). */
private fun mediaPermissions(): Array<String> = when {
  Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
  )
  Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
  else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/**
 * Con "foto selezionate" (Android 14+, E-P1) il permesso parziale e' concesso ma immagini e video no: "Consenti tutto"
 * deve riaprire il dialogo anche se [full] contiene solo permessi parziali gia' dati.
 */
private fun needsPartialUpgrade(context: Context, full: Array<String>): Boolean =
  Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
    Manifest.permission.READ_MEDIA_IMAGES in full &&
    !(granted(context, Manifest.permission.READ_MEDIA_IMAGES) && granted(context, Manifest.permission.READ_MEDIA_VIDEO))

private fun granted(context: Context, permission: String): Boolean =
  ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Avvia [intent]; vero se e' partito. Nessuna eccezione esce da qui: `ActivityNotFoundException` e `SecurityException`, e
 * soprattutto la `RuntimeException` con cui `startActivity` rilancia una `TransactionTooLargeException` (una condivisione di
 * molte migliaia di foto chiudeva l'app, I8). Chi chiama dice all'utente che non e' partito.
 */
private fun tryStart(context: Context, intent: Intent): Boolean {
  if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  return try {
    context.startActivity(intent)
    true
  } catch (_: ActivityNotFoundException) {
    false
  } catch (_: SecurityException) {
    false
  } catch (_: RuntimeException) {
    false
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
