package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CloudDone
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
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.MediaBackupState
import dev.pampa.mycloudgallery.core.backup.contract.NotOnNasReason
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import kotlinx.coroutines.flow.Flow

// Fase 5A (docs/fase5-progetto.md §1.5 `MediaBackupState`, §2.6, D15, D16; riga P5 del §4.4): la riga "Backup" del pannello informazioni
// del viewer. "In coda", "in caricamento" e "in verifica" stanno solo qui e nella pagina Backup, mai nei bit della cella (D15: un segno che
// cambia a ogni file farebbe ricostruire l'indice migliaia di volte durante il primo backup): il viewer guarda un media solo, e il pannello
// aperto segue lo stato nel tempo. H2: il pannello lo dice con la capsula di stato del design system (`GalleryFooterNote`, la stessa
// della nuvola della Libreria): un'icona e due parole, nessuno stile nuovo (D30). Le copie simili (P15) hanno la loro sezione, non questa.

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

/**
 * Il backup del media nel pannello (H2): **una capsula, un'icona e due parole** ("Telefono e NAS", "Solo sul telefono", "In coda"), con i
 * toni dello stato della Libreria (`GalleryFooterNote`: calmo, al lavoro, in attesa, da guardare, allarme). Sotto, piccolo, il perche' solo
 * quando serve saperlo (un'attesa, un problema, una copia che non conta ancora); quando tutto e' a posto, niente spiegazioni. Un caso del
 * contratto ha sempre la sua capsula (`when` esaustivo): un caso nuovo non compila finche' non ha il suo testo.
 *
 * @param onPhone il media ha una copia sul telefono: al sicuro e' "Telefono e NAS", altrimenti "Sul NAS".
 */
internal fun backupNote(format: ViewerFormat, state: MediaBackupState, onPhone: Boolean): GalleryFooterNote {
  val res = format.resources
  fun note(tone: GalleryFooterTone, label: Int, glyph: ImageVector?, detail: String? = null, progress: Float? = null, busy: Boolean = false): GalleryFooterNote {
    val text = res.getString(label)
    val spoken = listOfNotNull(res.getString(R.string.viewer_info_badge_spoken, text), detail).joinToString(". ")
    return GalleryFooterNote(tone = tone, label = text, glyph = glyph, detail = detail, progress = progress, busy = busy, description = spoken)
  }
  return when (state) {
    MediaBackupState.NotMine -> note(GalleryFooterTone.Waiting, R.string.viewer_info_badge_not_mine, Icons.Rounded.Group)
    is MediaBackupState.NotOnNas -> note(
      GalleryFooterTone.Attention,
      R.string.viewer_info_badge_phone_only,
      Icons.Rounded.PhoneAndroid,
      detail = res.getString(
        when (state.reason) {
          NotOnNasReason.SOURCE_OFF -> R.string.viewer_backup_not_on_nas_source_off
          NotOnNasReason.BACKUP_OFF -> R.string.viewer_backup_not_on_nas_backup_off
          NotOnNasReason.NOT_YET -> R.string.viewer_backup_not_on_nas_not_yet
        },
      ),
    )
    is MediaBackupState.Queued -> note(GalleryFooterTone.Waiting, R.string.viewer_info_badge_queued, Icons.Rounded.Schedule, detail = res.getString(queuedReasonRes(state.reason)))
    is MediaBackupState.Uploading -> note(
      GalleryFooterTone.Working,
      R.string.viewer_info_badge_uploading,
      Icons.Rounded.CloudUpload,
      detail = progressCaption(format, state.bytesDone, state.bytesTotal),
      progress = fraction(state.bytesDone, state.bytesTotal),
    )
    is MediaBackupState.Verifying -> note(GalleryFooterTone.Working, R.string.viewer_info_badge_verifying, Icons.Rounded.HourglassTop, busy = true)
    is MediaBackupState.Safe -> note(
      GalleryFooterTone.Calm,
      if (onPhone) R.string.viewer_info_badge_both else R.string.viewer_info_badge_nas,
      Icons.Rounded.CloudDone,
    )
    MediaBackupState.OnlyCommonCopy -> note(
      GalleryFooterTone.Attention,
      R.string.viewer_info_badge_common,
      Icons.Rounded.Group,
      detail = res.getString(R.string.viewer_info_badge_common_detail),
    )
    MediaBackupState.Unverified -> note(
      GalleryFooterTone.Waiting,
      R.string.viewer_info_badge_unverified,
      Icons.Rounded.HourglassTop,
      detail = res.getString(R.string.viewer_info_badge_unverified_detail),
    )
    is MediaBackupState.Problem -> note(GalleryFooterTone.Alarm, R.string.viewer_info_badge_problem, Icons.Rounded.Warning, detail = res.getString(problemCaptionRes(state.code)))
    MediaBackupState.LostBeforeBackup -> note(
      GalleryFooterTone.Alarm,
      R.string.viewer_info_badge_lost,
      Icons.Rounded.Warning,
      detail = res.getString(R.string.viewer_backup_lost_caption),
    )
  }
}

private fun fraction(done: Long, total: Long): Float? = if (total > 0) (done.coerceIn(0, total).toFloat() / total) else null

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

private const val PERCENT = 100L
