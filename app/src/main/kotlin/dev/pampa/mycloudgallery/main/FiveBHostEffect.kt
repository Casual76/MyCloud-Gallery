package dev.pampa.mycloudgallery.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pampa.mycloudgallery.backup.FiveBLaunch
import dev.pampa.mycloudgallery.backup.FiveBSheet
import dev.pampa.mycloudgallery.backup.FiveBUi
import dev.pampa.mycloudgallery.backup.VersionDeleteRequest
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.free.PhoneOnlySheetHost
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionMode
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSheetHost
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsSheetHost
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsStart
import dev.pampa.mycloudgallery.feature.integrations.edit.EditIntents
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionSheets
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionsController
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * I fogli e gli intenti della **5B** alla radice della galleria (P14; P12 "fogli alla radice"): finche' la galleria e' visibile.
 * - i fogli che il viewer chiede ([FiveBUi.sheet]): Versioni (anche al riepilogo di "Ripristina originale"), Correggi / Ruota, "Elimina solo dal
 *   telefono" (che apre "Libera spazio" con la selezione);
 * - il foglio "Elimina…" della 4B sulle copie di versioni precedenti ("Da decidere", "Elimina le intermedie…"): la conferma e' del foglio;
 * - gli intenti verso le altre app (l'editor di "Modifica con…", la scelta dell'app di "Condividi"), lanciati dall'activity;
 * - la pagina "Libera spazio" quando una selezione la chiede;
 * - al ritorno dell'utente: i permessi della condivisione si revocano e le sessioni di modifica si leggono.
 *
 * Niente parte da solo: tutto e' la risposta a un tocco gia' avvenuto in un foglio o nel viewer.
 */
@Composable
internal fun FiveBHostEffect(ui: FiveBUi?, nav: NavController) {
  if (ui == null) return
  val context = LocalContext.current
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ui.onResume() }

  // Gli intenti: un'activity viva li lancia, una volta sola ciascuno.
  val launch by ui.launches.collectAsStateWithLifecycle()
  LaunchedEffect(launch) {
    val request = launch ?: return@LaunchedEffect
    val ok = when (request) {
      is FiveBLaunch.Edit -> EditIntents.launch(context, request.intent)
      is FiveBLaunch.Share -> EditIntents.launch(context, request.intent)
    }
    ui.launched(request, ok)
  }

  // "Libera spazio" con una selezione (dalla Libreria o dal foglio "Elimina solo dal telefono").
  LaunchedEffect(ui) {
    var seen = ui.freeSpaceRequests.value
    ui.freeSpaceRequests.collect { serial ->
      if (serial != seen) {
        seen = serial
        nav.navigate(BackupScreen.FREE_SPACE.route)
      }
    }
  }

  val sheet by ui.sheet.collectAsStateWithLifecycle()
  val current = sheet
  VersionsSheetHost(
    mediaId = (current as? FiveBSheet.Versions)?.mediaId,
    onDismiss = ui::dismissSheet,
    start = (current as? FiveBSheet.Versions)?.start ?: VersionsStart.LIST,
  )
  CorrectionSheetHost(
    subject = (current as? FiveBSheet.Correction)?.subject,
    onDismiss = ui::dismissSheet,
    mode = (current as? FiveBSheet.Correction)?.mode ?: CorrectionMode.ALL,
  )
  PhoneOnlySheetHost(
    ids = (current as? FiveBSheet.PhoneOnly)?.ids,
    onDismiss = ui::dismissSheet,
    onFree = { filter ->
      ui.dismissSheet()
      ui.openFreeSpace(filter.selection)
    },
  )

  val delete by ui.deleteRequest.collectAsStateWithLifecycle()
  delete?.let { request -> VersionDeleteSheet(ui, request) }
}

/** Il foglio "Elimina…" della 4B per le copie di versioni precedenti: piano, conferma del foglio, cestino del NAS (30 giorni). */
@Composable
private fun VersionDeleteSheet(ui: FiveBUi, request: VersionDeleteRequest) {
  val scope = rememberCoroutineScope()
  val actions = remember(request) { ui.deleteActions(request) } ?: return
  val controller = remember(request) { NasActionsController(actions, scope) }
  LaunchedEffect(controller) {
    // Il bersaglio e' gia' deciso: il riferimento serve solo a far risolvere il controller.
    controller.bind(MediaRef.Nas(locationId = 1, contentKey = 0, mime = null))
    controller.target.filterNotNull().first()
    controller.requestDelete()
  }
  LaunchedEffect(controller) {
    controller.notice.filterNotNull().first { it.started }
    ui.deleteStarted(request)
  }
  NasActionSheets(controller = controller, onDeleteFromPhone = {})
}
