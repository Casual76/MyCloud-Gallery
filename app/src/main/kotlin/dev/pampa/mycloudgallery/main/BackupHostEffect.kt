package dev.pampa.mycloudgallery.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.backup.AppBackupActions
import dev.pampa.mycloudgallery.backup.BackupActionsHost
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.library.collections.CollectionKind
import dev.pampa.mycloudgallery.feature.library.collections.openCollection
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.feature.nas.hub.openNas
import dev.pampa.mycloudgallery.feature.settings.SettingsGraphRoute
import dev.pampa.mycloudgallery.feature.settings.SettingsPasswordRoute

/**
 * Lega le azioni del backup ([AppBackupActions]) alla schermata finche' la galleria e' visibile (Fase 5A, P7; docs/fase5-progetto.md §2.6):
 * la navigazione verso Diagnostica, Spazio NAS, cestino e impostazioni, i permessi dei media e la **conferma** per i dati mobili.
 *
 * **I permessi partono solo da un tocco** (le azioni del backup partono da un tocco): nessuna richiesta all'apertura (docs/sicurezza.md
 * §4-bis). Nessun permesso nuovo: sono quelli gia' dichiarati. Se Android non mostra piu' il dialogo il tocco apre le impostazioni dell'app.
 * Senza una schermata registrata, un'azione che ne ha bisogno non fa niente (mai una richiesta senza un tocco e senza un'activity).
 */
@Composable
internal fun BackupHostEffect(actions: AppBackupActions?, nav: NavController, onAccessChanged: () -> Unit) {
  if (actions == null) return
  val context = LocalContext.current
  val latestOnAccessChanged = rememberUpdatedState(onAccessChanged)
  val asked = remember { HashSet<String>() }
  val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    latestOnAccessChanged.value()
  }
  val host = remember(context, nav) {
    object : BackupActionsHost {
      override fun openScreen(screen: BackupScreen) {
        if (!screen.phase5B) nav.navigate(screen.route)
      }

      override fun openDiagnostics() = nav.navigate(DiagnosticsGraphRoute)

      override fun openPassword() = nav.navigate(SettingsPasswordRoute)

      override fun openAreasSettings() = nav.navigate(SettingsGraphRoute)

      override fun openNas(screen: NasScreen) = nav.openNas(screen)

      override fun openGalleryTrash() = nav.openCollection(CollectionKind.RECENTLY_DELETED)

      override fun requestMediaAccess() = request(mediaPermissions() + Manifest.permission.ACCESS_MEDIA_LOCATION, full = mediaPermissions())

      override fun requestMediaLocation() =
        request(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), full = arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))

      override fun openStorageSettings() {
        tryStart(context, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
      }

      override fun openBatteryGuide() {
        tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
      }

      /** [wanted] si chiede insieme; basta che manchi uno di [full] perche' la richiesta abbia senso. */
      private fun request(wanted: Array<String>, full: Array<String>) {
        val missing = full.filter { !granted(context, it) }
        if (missing.isEmpty() && !needsPartialUpgrade(context, full)) {
          latestOnAccessChanged.value()
          return
        }
        val activity = context.findActivity()
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
  DisposableEffect(actions, host) {
    actions.host = host
    onDispose { if (actions.host === host) actions.host = null }
  }
  BackupConfirmSheet(actions)
}

/** La conferma per i dati mobili, il roaming o il tetto (proposta 10): il testo con la stima lo scrivono le regole, il resto e' del design system. */
@Composable
private fun BackupConfirmSheet(actions: AppBackupActions) {
  val prompt by actions.prompt.collectAsStateWithLifecycle()
  val title = stringResource(R.string.backup_confirm_title)
  GalleryConfirmSheet(visible = prompt != null, onDismissRequest = actions::dismissPrompt, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.CloudUpload,
      tone = GallerySheetTone.Caution,
      title = title,
      message = prompt?.message,
      actions = {
        FluidButton(
          text = stringResource(R.string.backup_confirm_go),
          onClick = actions::confirm,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
        FluidButton(
          text = stringResource(R.string.backup_confirm_cancel),
          onClick = actions::dismissPrompt,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      },
    )
  }
}
