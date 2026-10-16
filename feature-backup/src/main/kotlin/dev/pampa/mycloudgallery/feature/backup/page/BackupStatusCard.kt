package dev.pampa.mycloudgallery.feature.backup.page

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.backup.ui.BackupButton
import dev.pampa.mycloudgallery.feature.backup.ui.BackupActionTexts
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCard
import dev.pampa.mycloudgallery.feature.backup.ui.BackupProblemTexts
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.BackupTone
import dev.pampa.mycloudgallery.feature.backup.ui.atText
import dev.pampa.mycloudgallery.feature.backup.ui.etaText
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers

// La scheda in cima alla pagina Backup: una composable per ogni caso di `BackupStatus` (docs/fase5-progetto.md §1.5, §2.6). Dice cosa sta
// succedendo, perche' e cosa si puo' fare; i tasti sono quelli del progetto ("Fai ora", "Ferma", e le azioni dei problemi). Solo numeri e
// codici: nessun nome di file, percorso o share.

/** Lo stato del backup in una scheda. [onOpenSummary] apre il foglio del primo backup. */
@Composable
internal fun BackupStatusCard(status: BackupStatus, actions: BackupPageActions, onOpenSummary: () -> Unit, modifier: Modifier = Modifier) {
  when (status) {
    is BackupStatus.NotConfigured -> NotConfiguredCard(status, actions, modifier)
    BackupStatus.TestOnly -> ProblemCard(BackupProblemCode.TEST_ONLY, count = 0, actions = actions, modifier = modifier)
    is BackupStatus.FirstBackupReady -> FirstBackupReadyCard(status, onOpenSummary, modifier)
    is BackupStatus.UpToDate -> UpToDateCard(status, actions, modifier)
    is BackupStatus.Waiting -> WaitingCard(status, actions, modifier)
    is BackupStatus.Running -> RunningCard(status, actions, modifier)
    is BackupStatus.Stopped -> StoppedCard(status, actions, modifier)
    is BackupStatus.Problem -> ProblemCard(status.code, status.count, actions, modifier)
  }
}

/** "Fai ora" / "Riprendi": il tasto che avvia un giro adesso (proposta 10). */
@Composable
private fun DoNowButton(label: Int, actions: BackupPageActions, style: FluidButtonStyle = FluidButtonStyle.Filled) {
  BackupButton(label = stringResource(label), onClick = actions.backupNow, style = style)
}

@Composable
private fun NotConfiguredCard(status: BackupStatus.NotConfigured, actions: BackupPageActions, modifier: Modifier) {
  // Cosa manca, nell'ordine dei passi del contratto: una riga per cosa, e un tasto per ogni cosa che da qui si puo' sistemare.
  val missing = BackupSetupStep.entries.filter { it in status.missing }
  val lines = missing.map { "• " + stringResource(setupRes(it)) }.joinToString("\n")
  BackupCard(
    tone = BackupTone.Attention,
    glyph = Icons.Rounded.Backup,
    title = stringResource(R.string.backup_status_not_configured_title),
    detail = lines.ifEmpty { stringResource(R.string.backup_status_not_configured_detail) },
    modifier = modifier,
    actions = {
      // Il primo tasto e' quello pieno, gli altri tonali: una sola cosa e' "la prossima".
      val buttons = buildList<Pair<Int, () -> Unit>> {
        if (BackupSetupStep.MEDIA_PERMISSION in missing) add(R.string.backup_action_grant_media to { actions.perform(BackupAction.GRANT_MEDIA) })
        if (BackupSetupStep.PRIVATE_AREA in missing) add(R.string.backup_action_areas_settings to { actions.perform(BackupAction.AREAS_SETTINGS) })
        if (BackupSetupStep.SOURCES in missing || BackupSetupStep.DEVICE_FOLDER in missing) {
          add(R.string.backup_status_open_settings to { actions.open(BackupScreen.SETTINGS) })
        }
      }
      buttons.forEachIndexed { index, (label, onClick) ->
        BackupButton(label = stringResource(label), onClick = onClick, style = if (index == 0) FluidButtonStyle.Filled else FluidButtonStyle.Tinted)
      }
    },
  )
}

private fun setupRes(step: BackupSetupStep): Int = when (step) {
  BackupSetupStep.NAS -> R.string.backup_setup_nas
  BackupSetupStep.PRIVATE_AREA -> R.string.backup_setup_private_area
  BackupSetupStep.SOURCES -> R.string.backup_setup_sources
  BackupSetupStep.DEVICE_FOLDER -> R.string.backup_setup_device_folder
  BackupSetupStep.MEDIA_PERMISSION -> R.string.backup_setup_media_permission
}

@Composable
private fun FirstBackupReadyCard(status: BackupStatus.FirstBackupReady, onOpenSummary: () -> Unit, modifier: Modifier) {
  val summary = status.summary
  val items = summary.toUpload.items + summary.linked.items
  if (summary.comparing) {
    // §2.11: prima del primo backup si aspetta la fine della scansione dell'area privata e del confronto delle copie simili.
    BackupCard(
      tone = BackupTone.Working,
      glyph = Icons.Rounded.Schedule,
      title = stringResource(R.string.backup_status_first_comparing_title),
      detail = if (!summary.existing.privateAreaScanned) {
        stringResource(R.string.backup_first_searching)
      } else {
        pluralStringResource(R.plurals.backup_first_comparing, summary.existing.similarPending, BackupText.count(summary.existing.similarPending))
      },
      busy = true,
      modifier = modifier,
      actions = { BackupButton(label = stringResource(R.string.backup_status_first_see), onClick = onOpenSummary, style = FluidButtonStyle.Tinted) },
    )
  } else {
    BackupCard(
      tone = BackupTone.Attention,
      glyph = Icons.Rounded.Backup,
      title = stringResource(R.string.backup_status_first_ready_title),
      detail = stringResource(
        R.string.backup_status_first_ready_detail,
        BackupText.count(items),
        BackupText.size(summary.linked.bytes + summary.toUpload.bytes),
      ),
      modifier = modifier,
      actions = { BackupButton(label = stringResource(R.string.backup_status_first_see), onClick = onOpenSummary) },
    )
  }
}

@Composable
private fun UpToDateCard(status: BackupStatus.UpToDate, actions: BackupPageActions, modifier: Modifier) {
  BackupCard(
    tone = BackupTone.Calm,
    glyph = Icons.Rounded.GppGood,
    title = stringResource(R.string.backup_status_up_to_date_title),
    detail = pluralStringResource(R.plurals.backup_status_up_to_date_detail, status.safeCount, BackupText.count(status.safeCount)),
    hint = status.atNasMillis?.let { stringResource(R.string.backup_status_last_run, atText(it)) },
    modifier = modifier,
    actions = { DoNowButton(R.string.backup_do_now, actions, FluidButtonStyle.Tinted) },
  )
}

@Composable
private fun WaitingCard(status: BackupStatus.Waiting, actions: BackupPageActions, modifier: Modifier) {
  BackupCard(
    tone = BackupTone.Waiting,
    glyph = Icons.Rounded.Schedule,
    title = stringResource(R.string.backup_status_waiting_title, BackupText.count(status.items)),
    detail = stringResource(R.string.backup_status_waiting_detail, stringResource(waitRes(status.reason)), BackupText.size(status.bytes)),
    modifier = modifier,
    actions = { DoNowButton(R.string.backup_do_now, actions, FluidButtonStyle.Tinted) },
  )
}

private fun waitRes(reason: WaitReason): Int = when (reason) {
  WaitReason.CHARGING -> R.string.backup_wait_charging
  WaitReason.WIFI -> R.string.backup_wait_wifi
  WaitReason.HOME_WIFI -> R.string.backup_wait_home_wifi
  WaitReason.NIGHT_WINDOW -> R.string.backup_wait_night_window
  WaitReason.BATTERY -> R.string.backup_wait_battery
  WaitReason.THERMAL -> R.string.backup_wait_thermal
  WaitReason.DATA_CAP -> R.string.backup_wait_data_cap
  WaitReason.NAS -> R.string.backup_wait_nas
  WaitReason.COMPARING -> R.string.backup_wait_comparing
  WaitReason.NEXT_RUN -> R.string.backup_wait_next_run
}

@Composable
private fun RunningCard(status: BackupStatus.Running, actions: BackupPageActions, modifier: Modifier) {
  val progress = if (status.total > 0) (status.done.toFloat() / status.total).coerceIn(0f, 1f) else null
  val kind = stringResource(if (status.kind == BackupKind.VIDEOS) R.string.backup_kind_videos else R.string.backup_kind_photos)
  val files = stringResource(R.string.backup_status_running_files, kind, BackupText.count(status.done), BackupText.count(status.total))
  val bytes = if (status.bytesTotal > 0) {
    stringResource(R.string.backup_status_running_bytes, BackupText.size(status.bytesDone), BackupText.size(status.bytesTotal))
  } else {
    null
  }
  val eta = status.etaMillis?.let { etaText(it) }
  BackupCard(
    tone = BackupTone.Working,
    glyph = Icons.Rounded.Backup,
    title = stringResource(
      when (status.trigger) {
        BackupTrigger.FIRST_BACKUP -> R.string.backup_status_running_first_title
        BackupTrigger.USER_NOW -> R.string.backup_status_running_now_title
        BackupTrigger.SCHEDULED, BackupTrigger.QUICK_NEW_PHOTOS, BackupTrigger.INCOMING -> R.string.backup_status_running_title
      },
    ),
    detail = listOfNotNull(files, bytes, eta).joinToString(" · "),
    progress = progress,
    busy = progress == null,
    modifier = modifier,
    actions = { StopButton(actions) },
  )
}

@Composable
private fun ColumnScope.StopButton(actions: BackupPageActions) {
  BackupButton(label = stringResource(R.string.backup_stop), onClick = actions.stop, style = FluidButtonStyle.Tinted)
}

@Composable
private fun StoppedCard(status: BackupStatus.Stopped, actions: BackupPageActions, modifier: Modifier) {
  BackupCard(
    tone = BackupTone.Waiting,
    glyph = Icons.Rounded.PauseCircle,
    title = stringResource(R.string.backup_status_stopped_title),
    detail = stringResource(
      when (status.by) {
        StopBy.USER -> R.string.backup_status_stopped_user
        StopBy.SYSTEM -> R.string.backup_status_stopped_system
        StopBy.RESET -> R.string.backup_status_stopped_reset
      },
    ),
    modifier = modifier,
    actions = {
      // Un reset riparte dalla configurazione, non da "Riprendi".
      if (status.by != StopBy.RESET) DoNowButton(if (status.by == StopBy.USER) R.string.backup_resume else R.string.backup_do_now, actions)
    },
  )
}

/**
 * Un problema del §2.6 in una scheda: il messaggio (con il numero dei media, se e' un problema di un media), la frase che spiega e le
 * azioni nell'ordine della tabella ("Riprova · Diagnostica"). Un'azione che e' solo una frase ("Chiedi all'admin del NAS") sta nella
 * scheda come testo; le altre sono tasti.
 */
@Composable
internal fun ProblemCard(code: BackupProblemCode, count: Int, actions: BackupPageActions, modifier: Modifier = Modifier) {
  val text = BackupProblemTexts.of(code)
  val countPlural = text.titleCount
  val title = if (countPlural != null && count > 0) {
    pluralStringResource(countPlural, count, GalleryNumbers.format(count))
  } else {
    stringResource(text.title)
  }
  val resolved = code.actions.map { it to BackupActionTexts.of(it) }
  val hint = resolved.firstOrNull { !it.second.isButton }?.let { stringResource(it.second.label) }
  BackupCard(
    tone = text.tone,
    glyph = text.glyph,
    title = title,
    detail = text.detail?.let { stringResource(it) },
    hint = hint,
    modifier = modifier,
    actions = if (resolved.any { it.second.isButton }) {
      {
        resolved.filter { it.second.isButton }.forEachIndexed { index, (action, label) ->
          BackupButton(
            label = stringResource(label.label),
            onClick = { actions.perform(action) },
            style = if (index == 0) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
          )
        }
      }
    } else {
      null
    },
  )
}
