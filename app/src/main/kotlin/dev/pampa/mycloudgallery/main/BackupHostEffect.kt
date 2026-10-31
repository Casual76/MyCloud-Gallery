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
import androidx.compose.runtime.LaunchedEffect
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
import dev.pampa.mycloudgallery.core.designsystem.state.LocalPhoneTrash
import dev.pampa.mycloudgallery.core.designsystem.state.PhoneTrash
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.diagnostics.DiagnosticsGraphRoute
import dev.pampa.mycloudgallery.feature.library.collections.CollectionKind
import dev.pampa.mycloudgallery.feature.library.collections.openCollection
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.feature.nas.hub.openNas
import dev.pampa.mycloudgallery.feature.settings.SettingsPasswordRoute
import dev.pampa.mycloudgallery.window.WindowHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
  val phoneTrash = LocalPhoneTrash.current
  val latestOnAccessChanged = rememberUpdatedState(onAccessChanged)
  // GA-4: il ricordo "ho gia' chiesto il permesso" sta su un file, non in `remember` (si perdeva con la rotazione e il riavvio del processo, e
  // il primo "Consenti" lanciava un dialogo che Android scarta in silenzio): vedi `PermissionAsks`.
  val asks = remember(context) { PermissionAsks(context) }
  LaunchedEffect(asks) { withContext(Dispatchers.IO) { asks.warm() } }
  val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    latestOnAccessChanged.value()
  }
  val host = remember(context, nav, phoneTrash) {
    object : BackupActionsHost {
      // Fase 5B (P14): anche "Libera spazio" e "Da decidere" sono rotte registrate (`backupGraph`).
      override fun openScreen(screen: BackupScreen) = nav.navigate(screen.route)

      override fun openDiagnostics() = nav.navigate(DiagnosticsGraphRoute)

      override fun openPassword() = nav.navigate(SettingsPasswordRoute)

      override fun openNas(screen: NasScreen) = nav.openNas(screen)

      /**
       * "Apri il cestino di Galleria" (E-B4). D-3 (prove sul campo): su One UI il cestino e' quello di Galleria e la pagina "Eliminati di
       * recente" dell'app non lo vede: si apre Galleria (l'app delle foto predefinita), da cui si va in Cestino. Se non si apre, la pagina
       * dell'app, che lo spiega. Su Android "puro" il cestino di sistema e' proprio quella pagina.
       */
      override fun openGalleryTrash() {
        if (phoneTrash == PhoneTrash.GALLERY && tryStart(context, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))) return
        nav.openCollection(CollectionKind.RECENTLY_DELETED)
      }

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
        val activity = context.findActivity()
        when (permissionStep(missing, needsPartialUpgrade(context, full), asks, full.filter { granted(context, it) }) { activity?.shouldShowRequestPermissionRationale(it) }) {
          PermissionStep.GRANTED -> latestOnAccessChanged.value()
          PermissionStep.SETTINGS ->
            tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
          PermissionStep.ASK -> {
            asks.markAsked(wanted.toList())
            permissions.launch(wanted)
          }
        }
      }
    }
  }
  // Fase 10 (P4, D18): con due finestre l'ospite e' quello della finestra che ha il fuoco (`WindowHost`), non dell'ultima composta.
  WindowHost(host = host, key = actions, current = { actions.host }, set = { actions.host = it })
  BackupConfirmSheet(actions)
}

/** Cosa fare a un tocco su "Consenti" (BK-3, GA-4). */
internal enum class PermissionStep {
  /** Niente manca: l'accesso si rilegge e basta. */
  GRANTED,

  /** Android non apre piu' il dialogo (negato per sempre): il tocco porta alle Impostazioni dell'app. */
  SETTINGS,

  /** Il dialogo di sistema si apre. */
  ASK,
}

/**
 * Il passo di un tocco su "Consenti": pura, provata su JVM. Il ricordo di aver gia' chiesto sta su file ([PermissionAsks]): un processo nuovo
 * (rotazione, riavvio) sa com'era andata, e un permesso negato per sempre manda alle Impostazioni invece di un dialogo che non si apre.
 * Un permesso concesso e poi tolto dalle Impostazioni non e' piu' "negato": [granted] lo toglie dal ricordo.
 */
internal fun permissionStep(
  missing: List<String>,
  partialUpgrade: Boolean,
  asks: PermissionAsks,
  granted: List<String>,
  shouldExplain: (String) -> Boolean?,
): PermissionStep {
  asks.forgetGranted(granted)
  if (missing.isEmpty() && !partialUpgrade) return PermissionStep.GRANTED
  return if (PermissionAsks.deniedForGood(missing, asks::asked, shouldExplain)) PermissionStep.SETTINGS else PermissionStep.ASK
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
