package dev.pampa.mycloudgallery.main

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
 * - l'intento verso l'editor di "Modifica con…", lanciato dall'activity (la scelta dell'app di "Condividi" la lancia `ShareHostEffect`);
 * - la pagina "Libera spazio" quando una selezione la chiede, **sopra** tutto (correzioni 5B, LS-3: il viewer si chiude prima, altrimenti la pagina
 *   si apriva sotto di lui e non si vedeva), e quando la notifica "Pronto" l'ha annunciata e l'utente torna (LS-2);
 * - il cestino di Android chiesto da "Elimina solo dal telefono" per un media che non e' sul NAS (LS-1) e da "Ovunque" (LS-14);
 * - al ritorno dell'utente: le sessioni di modifica si leggono (le copie condivise le rilascia il custode di `AppShares`, MC-1).
 *
 * Niente parte da solo: tutto e' la risposta a un tocco gia' avvenuto in un foglio o nel viewer.
 */
@Composable
internal fun FiveBHostEffect(ui: FiveBUi?, nav: NavController) {
  if (ui == null) return
  val context = LocalContext.current
  val viewer = LocalViewerHost.current
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ui.onResume() }

  // Gli intenti: un'activity viva li lancia, una volta sola ciascuno.
  val launch by ui.launches.collectAsStateWithLifecycle()
  LaunchedEffect(launch) {
    val request = launch ?: return@LaunchedEffect
    // Fase 10 (P13, D18): con due finestre la richiesta la lancia una sola (la prima che la prende; l'altra la trova gia' presa).
    if (ui.launches.value !== request) return@LaunchedEffect
    // "Condividi" non passa piu' di qui: lo lancia `ShareHostEffect`, che c'e' anche senza backup (correzioni 5B, R4).
    val ok = when (request) {
      is FiveBLaunch.Edit -> EditIntents.launch(context, request.intent)
    }
    ui.launched(request, ok)
  }

  // "Libera spazio" con una selezione (dalla Libreria o dal foglio "Elimina solo dal telefono"). LS-3: il viewer e' disegnato sopra la
  // navigazione; si chiude prima, cosi' la pagina si vede.
  LaunchedEffect(ui) {
    var seen = ui.freeSpaceRequests.value
    ui.freeSpaceRequests.collect { serial ->
      if (serial != seen) {
        seen = serial
        viewer?.close()
        nav.navigate(BackupScreen.FREE_SPACE.route)
      }
    }
  }

  // IN-7: la notifica ha detto che dei file ricevuti non si sono salvati: quando l'utente torna, la pagina Backup si apre (la scheda "Ricevuti in coda"
  // sta in cima). Se e' gia' su una pagina del backup non si sposta.
  LaunchedEffect(ui) {
    var seen = ui.receivedRequests.value
    ui.receivedRequests.collect { serial ->
      if (serial != seen) {
        seen = serial
        val here = nav.currentDestination?.route
        if (here != BackupScreen.PAGE.route && here != BackupScreen.RECEIVED.route) {
          viewer?.close()
          nav.navigate(BackupScreen.PAGE.route)
        }
      }
    }
  }

  // LS-2: la verifica di "Libera spazio" e' pronta e la notifica l'ha detto: quando l'utente torna, la pagina si apre (con la selezione del giro).
  val readyRun by ui.freeRuns.openRequest.collectAsStateWithLifecycle()
  LaunchedEffect(readyRun) {
    val id = readyRun ?: return@LaunchedEffect
    ui.freeRuns.consumeOpen(id)
    if (nav.currentDestination?.route != BackupScreen.FREE_SPACE.route) ui.openFreeSpace(ui.freeRuns.selection)
  }

  // LS-1, LS-14: il cestino di Android (30 giorni), chiesto dopo un tocco su un foglio; il permesso lo da' l'utente nel dialogo del sistema.
  val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
  val trash by ui.trashRequests.collectAsStateWithLifecycle()
  LaunchedEffect(trash) {
    val request = trash ?: return@LaunchedEffect
    ui.trashLaunched(request)
    trashLauncher.launch(IntentSenderRequest.Builder(request.sender).build())
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
  val phoneOnly = current as? FiveBSheet.PhoneOnly
  PhoneOnlySheetHost(
    ids = phoneOnly?.ids,
    onDismiss = ui::dismissSheet,
    onFree = { filter ->
      ui.dismissSheet()
      ui.openFreeSpace(filter.selection)
    },
    // LS-1 ("Elimina con avviso"): dal viewer, un media che non risulta al sicuro sul NAS va nel cestino di Android dopo l'avviso del foglio.
    onTrash = if (phoneOnly?.fromViewer == true) ui::trashFromPhone else null,
    isVideo = phoneOnly?.isVideo == true,
  )

  val delete by ui.deleteRequest.collectAsStateWithLifecycle()
  delete?.let { request -> VersionDeleteSheet(ui, request) }

  // VE-6: le frasi delle azioni nate fuori dal viewer ("Da decidere", "Elimina le intermedie…") si dicono adesso, dove l'utente e'.
  val rootNotice by ui.rootNotice.collectAsStateWithLifecycle()
  LaunchedEffect(rootNotice) {
    val text = rootNotice ?: return@LaunchedEffect
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    ui.dismissRootNotice()
  }
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
  val resources = LocalResources.current
  LaunchedEffect(controller) {
    // VE-6: un piano rifiutato (NAS che non risponde, sola lettura) si dice e il foglio si chiude; prima restava aperto con il tasto spento.
    val notice = controller.notice.filterNotNull().first()
    if (notice.started) {
      ui.deleteStarted(request)
    } else {
      controller.dismissNotice()
      controller.dismiss()
      ui.deleteRefused(request, resources.getString(notice.text))
    }
  }
  NasActionSheets(controller = controller, onDeleteFromPhone = {})
}
