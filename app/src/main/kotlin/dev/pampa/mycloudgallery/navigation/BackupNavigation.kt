package dev.pampa.mycloudgallery.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.folder.DeviceFolderRoute
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageRoute
import dev.pampa.mycloudgallery.feature.backup.settings.BackupSettingsRoute

// Fase 5A (P7, docs/fase5-progetto.md §1.1, §1.5): le schermate di feature-backup nella navigazione dell'app. Le rotte sono le stringhe
// stabili di `BackupScreen` (mai un percorso o un nome di cartella dentro una rotta); le schermate della 5B (`phase5B`) non si registrano.

/** Le quattro schermate della 5A: la pagina Backup, il riepilogo del primo backup (la stessa pagina con il foglio aperto), le impostazioni e la cartella del dispositivo. */
fun NavGraphBuilder.backupGraph(nav: NavController) {
  val back: () -> Unit = { nav.popBackStack() }
  composable(BackupScreen.PAGE.route) { BackupPageRoute(onBack = back) }
  composable(BackupScreen.FIRST_BACKUP.route) { BackupPageRoute(onBack = back, openFirstBackup = true) }
  composable(BackupScreen.SETTINGS.route) { BackupSettingsRoute(onBack = back) }
  composable(BackupScreen.DEVICE_FOLDER.route) { DeviceFolderRoute(onBack = back) }
}

/** Apre la pagina Backup (dal piede della Libreria e dalla voce delle Impostazioni). */
fun NavController.openBackupPage() {
  navigate(BackupScreen.PAGE.route)
}
