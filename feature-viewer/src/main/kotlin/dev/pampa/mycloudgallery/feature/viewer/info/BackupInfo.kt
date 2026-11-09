package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.MediaBackupState
import dev.pampa.mycloudgallery.core.backup.contract.NotOnNasReason
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryInfoRow
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

// Fase 5A (docs/fase5-progetto.md §1.5 `MediaBackupState`, §2.6, D15, D16; riga P5 del §4.4): la riga "Backup" del pannello informazioni
// del viewer. "In coda", "in caricamento" e "in verifica" stanno solo qui e nella pagina Backup, mai nei bit della cella (D15: un segno che
// cambia a ogni file farebbe ricostruire l'indice migliaia di volte durante il primo backup): il viewer guarda un media solo, e il pannello
// aperto segue lo stato nel tempo. Il pannello usa le righe del design system (`GalleryInfoRow`): nessuno stile nuovo (D30). Le copie
// simili (P15) hanno la loro sezione, non questa.

/**
 * Lo stato del backup di un media, per il pannello informazioni. `:app` la lega al catalogo (`BackupCatalog.mediaSafety`), al diario
 * (`BackupStore.openFor`) e allo stato del motore; senza ([dev.pampa.mycloudgallery.feature.viewer.ViewerServices.backup] null) il
 * pannello non ha la riga, come per ogni capacita' assente (D13).
 */
fun interface MediaBackupStates {

  /**
   * Lo stato di [id] adesso e ogni volta che cambia (l'avanzamento di un caricamento, la verifica che finisce). Si ascolta solo con il
   * pannello aperto.
   */
  fun observe(id: MediaId): Flow<MediaBackupState>
}

/**
 * Lo stato del backup del media [id] mentre il pannello e' aperto ([active]); null se non c'e' una fonte, se il pannello e' chiuso o
 * finche' la fonte non ha risposto. Cambia di media: si riparte.
 */
@Composable
internal fun rememberBackupState(source: MediaBackupStates?, id: MediaId, active: Boolean): State<MediaBackupState?> {
  if (source == null || !active) return remember { mutableStateOf<MediaBackupState?>(null) }
  return produceState<MediaBackupState?>(initialValue = null, source, id) {
    source.observe(id).collect { value = it }
  }
}

/** La sezione "Backup" del pannello: una riga che dice lo stato del media. */
internal fun backupInfoSection(format: ViewerFormat, state: MediaBackupState): InfoSectionModel =
  InfoSectionModel(
    header = format.resources.getString(R.string.viewer_backup_header),
    rows = listOf(backupInfoRow(format, state)),
  )

/**
 * La riga per uno [state]: il titolo dice lo stato in una frase breve, la didascalia il perche' o i numeri. Un caso del contratto ha
 * sempre la sua riga (`when` esaustivo): un caso nuovo non compila finche' non ha il suo testo.
 */
internal fun backupInfoRow(format: ViewerFormat, state: MediaBackupState): GalleryInfoRow {
  val res = format.resources
  return when (state) {
    MediaBackupState.NotMine -> GalleryInfoRow(
      title = res.getString(R.string.viewer_backup_not_mine),
      caption = res.getString(R.string.viewer_backup_not_mine_caption),
      icon = Icons.Rounded.Group,
    )
    is MediaBackupState.NotOnNas -> GalleryInfoRow(
      title = res.getString(R.string.viewer_backup_not_on_nas),
      caption = res.getString(
        when (state.reason) {
          NotOnNasReason.SOURCE_OFF -> R.string.viewer_backup_not_on_nas_source_off
          NotOnNasReason.BACKUP_OFF -> R.string.viewer_backup_not_on_nas_backup_off
          NotOnNasReason.NOT_YET -> R.string.viewer_backup_not_on_nas_not_yet
        },
      ),
      icon = Icons.Rounded.PhoneAndroid,
    )
    is MediaBackupState.Queued -> GalleryInfoRow(
      title = res.getString(R.string.viewer_backup_queued),
      caption = res.getString(queuedReasonRes(state.reason)),
      icon = Icons.Rounded.Schedule,
    )
    is MediaBackupState.Uploading -> GalleryInfoRow(
      title = res.getString(R.string.viewer_backup_uploading),
      caption = progressCaption(format, state.bytesDone, state.bytesTotal),
      icon = Icons.Rounded.CloudUpload,
      tone = FluidTone.Info,
    )
    is MediaBackupState.Verifying -> GalleryInfoRow(
      title = res.getString(R.string.viewer_backup_verifying),
      caption = progressCaption(format, state.bytesDone, state.bytesTotal),
      icon = Icons.Rounded.HourglassTop,
      tone = FluidTone.Info,
    )
    is MediaBackupState.Safe -> GalleryInfoRow(
      title = res.getString(R.string.viewer_backup_safe),
      caption = res.getString(R.string.viewer_backup_safe_caption, verifiedAt(format, state.verifiedAtMillis)),
      icon = Icons.Rounded.GppGood,
      tone = FluidTone.Success,
    )
    MediaBackupState.OnlyCommonCopy -> GalleryInfoRow(
      title = res.getString(R.string.viewer_backup_only_common),
      caption = res.getString(R.string.viewer_backup_only_common_caption),
      icon = Icons.Rounded.Group,
      tone = FluidTone.Warning,
    )
    MediaBackupState.Unverified -> GalleryInfoRow(
      title = res.getString(R.string.viewer_backup_unverified),
      caption = res.getString(R.string.viewer_backup_unverified_caption),
      icon = Icons.Rounded.HourglassTop,
      tone = FluidTone.Warning,
    )
    is MediaBackupState.Problem -> GalleryInfoRow(
      title = res.getString(R.string.viewer_backup_problem),
      caption = res.getString(problemCaptionRes(state.code)),
      icon = Icons.Rounded.Warning,
      tone = FluidTone.Danger,
    )
    MediaBackupState.LostBeforeBackup -> GalleryInfoRow(
      title = res.getString(R.string.viewer_backup_lost),
      caption = res.getString(R.string.viewer_backup_lost_caption),
      icon = Icons.Rounded.Warning,
      tone = FluidTone.Warning,
    )
  }
}

/** Perche' il media aspetta; senza un motivo noto, "al prossimo giro". */
internal fun queuedReasonRes(reason: WaitReason?): Int = when (reason) {
  WaitReason.CHARGING -> R.string.viewer_backup_wait_charging
  WaitReason.WIFI -> R.string.viewer_backup_wait_wifi
  WaitReason.HOME_WIFI -> R.string.viewer_backup_wait_home_wifi
  WaitReason.NIGHT_WINDOW -> R.string.viewer_backup_wait_night_window
  WaitReason.BATTERY -> R.string.viewer_backup_wait_battery
  WaitReason.POWER_SAVER -> R.string.viewer_backup_wait_power_saver
  WaitReason.THERMAL -> R.string.viewer_backup_wait_thermal
  WaitReason.DATA_CAP -> R.string.viewer_backup_wait_data_cap
  WaitReason.NAS -> R.string.viewer_backup_wait_nas
  WaitReason.COMPARING -> R.string.viewer_backup_wait_comparing
  WaitReason.NEXT_RUN, null -> R.string.viewer_backup_wait_next_run
  WaitReason.NAS_BUSY -> R.string.viewer_backup_wait_nas_busy
}

/**
 * Il perche' di un problema di un media. Quelli che accendono il segno della cella ([BackupProblemCode.marksMedia]) hanno la loro frase;
 * gli altri (problemi di tutto il backup, non di un media) mandano alla pagina Backup, dove ogni codice ha il suo messaggio e la sua azione.
 */
internal fun problemCaptionRes(code: BackupProblemCode): Int = when (code) {
  BackupProblemCode.VERIFY_FAILED -> R.string.viewer_backup_problem_verify_failed
  BackupProblemCode.UNREADABLE -> R.string.viewer_backup_problem_unreadable
  BackupProblemCode.LOST_BEFORE_BACKUP -> R.string.viewer_backup_lost_caption
  BackupProblemCode.UNEXPECTED -> R.string.viewer_backup_problem_unexpected
  // P2 aggiunge `COMMON_COPY_LOST` al contratto: si lega per codice stabile, senza aspettare il caso dell'enumerazione.
  else -> if (code.code == COMMON_COPY_LOST) R.string.viewer_backup_problem_common_copy_lost else R.string.viewer_backup_problem_generic
}

private const val COMMON_COPY_LOST = "common_copy_lost"

private fun progressCaption(format: ViewerFormat, done: Long, total: Long): String {
  val res = format.resources
  if (total <= 0) return res.getString(R.string.viewer_backup_progress_unknown)
  val percent = (done.coerceIn(0, total) * PERCENT / total).toInt()
  return res.getString(R.string.viewer_backup_progress, format.fileSize(done), format.fileSize(total), percent)
}

/** "12 ottobre 2026 alle 03:12": il momento del controllo, dall'ora del catalogo mostrata come ora di casa. */
private fun verifiedAt(format: ViewerFormat, atMillis: Long): String {
  val at = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
  return format.resources.getString(R.string.viewer_backup_at, GalleryDates.date(at), GalleryDates.time(at))
}

private const val PERCENT = 100L
