package dev.pampa.mycloudgallery.navigation

import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.free.FreeSpaceRoute
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTasksRoute
import dev.pampa.mycloudgallery.feature.backup.folder.DeviceFolderRoute
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageRoute
import dev.pampa.mycloudgallery.feature.backup.settings.BackupSettingsRoute

// Fase 5A (P7, docs/fase5-progetto.md §1.1, §1.5): le schermate di feature-backup nella navigazione dell'app. Le rotte sono le stringhe
// stabili di `BackupScreen` (mai un percorso o un nome di cartella dentro una rotta). Dalla 5B (P14) anche "Libera spazio" e "Da decidere": la
// selezione di "Libera spazio" non viaggia nella rotta (sarebbero id dentro una rotta) ma si legge entrando ([freeSpaceSelection]).

/**
 * Le quattro schermate della 5A (la pagina Backup, il riepilogo del primo backup, le impostazioni, la cartella del dispositivo) e le due della 5B
 * (P14): "Libera spazio" (con la selezione della Libreria, se c'e') e "Da decidere".
 */
fun NavGraphBuilder.backupGraph(nav: NavController, freeSpaceSelection: () -> Set<MediaId>? = { null }) {
  val back: () -> Unit = { nav.popBackStack() }
  composable(BackupScreen.PAGE.route) { BackupPageRoute(onBack = back) }
  composable(BackupScreen.FIRST_BACKUP.route) { BackupPageRoute(onBack = back, openFirstBackup = true) }
  composable(BackupScreen.SETTINGS.route) { BackupSettingsRoute(onBack = back) }
  composable(BackupScreen.DEVICE_FOLDER.route) { DeviceFolderRoute(onBack = back) }
  composable(BackupScreen.FREE_SPACE.route) {
    val selection = remember { freeSpaceSelection() }
    FreeSpaceRoute(onBack = back, selection = selection)
  }
  composable(BackupScreen.PENDING_TASKS.route) { PendingTasksRoute(onBack = back) }
}

/** Apre la pagina Backup (dal piede della Libreria e dalla voce delle Impostazioni). */
fun NavController.openBackupPage() {
  navigate(BackupScreen.PAGE.route)
}
