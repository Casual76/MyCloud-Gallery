package dev.pampa.mycloudgallery.feature.settings.backup

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.feature.settings.ui.RowValue
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// La voce "Backup" delle Impostazioni (Fase 5A, docs/fase5-progetto.md §1.3, §1.5, D16; riga P5 del §4.4): una riga con lo stato del
// backup in una frase e un tocco che porta alla pagina Backup. La pagina e le sue impostazioni sono di feature-backup; i feature non si
// conoscono, quindi il tocco e' una callback scritta da `:app` (`SettingsLinks.openBackup`): finche' `:app` non la scrive, la voce non
// compare (nessuna riga che non porta da nessuna parte). Righe di Fluid come tutta la pagina: nessuno stile nuovo (D30).

/**
 * Lo stato del backup per la voce delle Impostazioni. `:app` la lega a `BackupEngine.status`. **Opzionale per Dagger**
 * ([BackupEntryOptionalModule]): senza, la voce c'e' (se `openBackup` c'e') con la frase di riposo.
 */
interface BackupEntryStatus {
  val status: StateFlow<BackupStatus>
}

/** Il modulo Hilt delle porte opzionali della voce "Backup". */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupEntryOptionalModule {

  @BindsOptionalOf
  abstract fun backupEntryStatus(): BackupEntryStatus
}

@HiltViewModel
class BackupEntryViewModel @Inject constructor(
  entry: Optional<BackupEntryStatus>,
) : ViewModel() {

  /** Lo stato di adesso, o null finche' `:app` non ha legato il backup. */
  val status: StateFlow<BackupStatus?> = entry.orElse(null)?.status ?: MutableStateFlow<BackupStatus?>(null)
}

/**
 * La riga "Backup" (I3, fase I): la piastrella del backup con il segno dello stato (la spunta, le frecce che girano, la pausa, il punto esclamativo) e
 * lo stato **corto a destra** («Al sicuro», «In pausa», «312 in coda»), come nella pagina Backup. [status] null = il backup non e' ancora legato.
 */
@Composable
internal fun BackupEntryRow(status: BackupStatus?, onClick: () -> Unit) {
  FluidListRow(
    title = stringResource(R.string.settings_backup),
    subtitle = "",
    leading = { RowTile(Icons.Rounded.CloudUpload, status = backupEntryMark(status)) },
    leadingTile = false,
    badge = { RowValue(backupEntrySubtitle(status)) },
    onClick = onClick,
  )
}

/** Il segno sulla piastrella: lo stesso della testata della pagina Backup. */
internal fun backupEntryMark(status: BackupStatus?): GalleryIconStatus? = when (status) {
  null -> null
  is BackupStatus.UpToDate -> GalleryIconStatus.Done
  is BackupStatus.Running -> GalleryIconStatus.Running
  is BackupStatus.Problem, is BackupStatus.NotConfigured -> GalleryIconStatus.Attention
  // Fase I (BF-5): lo stesso segno della testata della pagina Backup. Pronto: «tocca a te» (non il «!» d'allarme); si cerca: in corso solo se la scansione
  // gira, se no l'attesa; appena aperta l'app (riepilogo non ancora calcolato) nessun segno.
  is BackupStatus.FirstBackupReady -> when {
    !status.summary.counted -> null
    status.summary.canStart -> GalleryIconStatus.YourTurn
    status.summary.existing.heldBy == null && status.summary.existing.scanSeen != null -> GalleryIconStatus.Running
    status.summary.existing.heldBy == WaitReason.NEXT_RUN -> GalleryIconStatus.Paused
    else -> GalleryIconStatus.Waiting
  }
  BackupStatus.TestOnly -> GalleryIconStatus.Paused
  // Giro 2 (CU-7): l'attesa (la carica, il Wi-Fi) ha il segno della clessidra del componente comune; la pausa resta le due barre.
  is BackupStatus.Waiting -> GalleryIconStatus.Waiting
  is BackupStatus.Stopped -> if (status.by == StopBy.RESET) GalleryIconStatus.Stopped else GalleryIconStatus.Paused
}

/** Lo stato corto della riga, dallo stato del contratto: solo numeri, mai nomi di file o cartelle. */
@Composable
internal fun backupEntrySubtitle(status: BackupStatus?): String = BackupEntryTexts.subtitleOf(LocalContext.current, status)

/** Le frasi della voce, da funzioni pure (si provano senza comporre): un caso del contratto ha sempre la sua (`when` esaustivo). */
internal object BackupEntryTexts {

  fun subtitleOf(context: Context, status: BackupStatus?): String {
    val res = context.resources
    return when (status) {
      null -> res.getString(R.string.settings_backup_detail)
      is BackupStatus.NotConfigured -> res.getString(R.string.settings_backup_not_configured)
      BackupStatus.TestOnly -> res.getString(R.string.settings_backup_test_only)
      is BackupStatus.FirstBackupReady ->
        // Fase I (12/11): «Preparo» solo finche' la ricerca sul NAS non e' finita; il confronto delle copie simili non ferma il primo backup. BF-5: appena
        // aperta l'app, finche' il riepilogo non c'e', nessuna parola provvisoria.
        when {
          !status.summary.counted -> ""
          !status.summary.canStart -> res.getString(R.string.settings_backup_first_comparing)
          else -> res.getString(R.string.settings_backup_first_ready)
        }
      is BackupStatus.UpToDate -> res.getString(R.string.settings_backup_up_to_date)
      is BackupStatus.Waiting -> res.getString(R.string.settings_backup_waiting, ItalianCount.format(status.items))
      // Giro 2 (BF2-4): chiesto adesso e accettato, il motore non l'ha ancora preso: «Sto partendo» come la pagina e il piede, mai «0 / N».
      is BackupStatus.Running -> if (status.starting) {
        res.getString(R.string.settings_backup_starting)
      } else {
        res.getString(R.string.settings_backup_running, ItalianCount.format(status.done), ItalianCount.format(status.total))
      }
      is BackupStatus.Stopped -> res.getString(if (status.by == StopBy.USER) R.string.settings_backup_paused else R.string.settings_backup_stopped)
      is BackupStatus.Problem -> res.getString(R.string.settings_backup_problem)
    }
  }
}

/**
 * Un conteggio come lo scrive tutta la galleria (il punto delle migliaia, da 1.000 in su, qualunque lingua abbia il telefono). E' la
 * stessa regola di `GalleryNumbers` di core-library, che questo modulo non vede: qui se ne copia la sola funzione che serve.
 */
internal object ItalianCount {

  fun format(value: Int): String {
    val digits = Math.abs(value.toLong()).toString()
    val grouped = digits.reversed().chunked(GROUP).joinToString(".").reversed()
    return if (value < 0) "-$grouped" else grouped
  }

  private const val GROUP = 3
}
