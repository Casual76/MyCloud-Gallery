package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryLiveAnnouncement
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.countText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Le frasi dello stato del NAS e del backup (G2, docs/grafica-progetto.md §12; Fase 4 §1.5; Fase 5A §1.5, D16). Fino alla beta erano
// tre voci sorelle in fondo alla Libreria (la rete, il NAS, il backup); **dalla beta (B2) le tre voci non ci sono piu'**: lo stato lo
// dice la nuvola nella capsula in alto a destra e le frasi stanno nel suo pop-up (`LibraryCloud.kt`). Cosa dire lo decide il
// contratto (`LibraryNasStatus`, la regola e' `LibraryNasStatusComposer`; `LibraryBackupStatus`, che `:app` riduce da `BackupStatus`);
// qui solo come, con i componenti del design system: nessuno stile nuovo (D30).

/**
 * L'annuncio per TalkBack dello stato del NAS e del backup ([StatusAnnouncer]): era nel piede, ora vive accanto alla nuvola e si
 * annuncia ovunque si stia guardando la Libreria. Legge gli stati qui dentro, cosi' un avanzamento che cambia ricompone solo lui.
 */
@Composable
internal fun LibraryCloudAnnouncer(state: LibraryTabState) {
  val nas by state.nas.collectAsState()
  val backup by state.backup.collectAsState()
  StatusAnnouncer(listOf(nasFooterNote(nas), backupFooterNote(backup)))
}

/**
 * Fase 10 P8 (D23 (6), E-AC9): lo stato che cambia da solo si **annuncia una volta**, con una regione viva educata, e **non a ogni numero**: "312 in
 * coda", "311 in coda"... sono lo stesso stato. Si annuncia quando cambia il *tipo* di stato (la frase senza le cifre) e il testo detto e' quello del
 * momento del cambio. Gli stati tranquilli (tutto al sicuro, collegato) non sono una notizia e non si annunciano.
 */
@Composable
internal fun StatusAnnouncer(notes: List<GalleryFooterNote?>) {
  val news = notes.filterNotNull().filter { it.tone != GalleryFooterTone.Calm }
  val kind = news.joinToString("|") { it.label.replace(Digits, "#") }
  val spoken = remember(kind) { news.joinToString(". ") { listOfNotNull(it.label, it.detail).joinToString(", ") } }
  if (kind.isNotEmpty()) GalleryLiveAnnouncement(spoken)
}

private val Digits = Regex("[0-9.,]+")

/**
 * Che cosa il piede dice del backup (Fase 5A, §2.6), o null se non c'e' niente da dire (backup non configurato). Le frasi brevi e i
 * dettagli sono quelli del progetto: "Tutto al sicuro" · "Oggi alle 03:12"; "312 in coda" · "Attesa carica"; "Primo backup: 1.240
 * di 6.658" · "circa 1 h 20 min"; "Quota esaurita" · "Chiedi all'admin del NAS"; "Backup in prova" · "Si scrive solo nelle share di
 * prova". I toni sono quelli del piede, gia' verificati su ogni accento.
 */
@Composable
internal fun backupFooterNote(status: LibraryBackupStatus): GalleryFooterNote? = when (status) {
  LibraryBackupStatus.Idle -> null
  LibraryBackupStatus.TestOnly -> footerNote(BackupFooterProblems.of("test_only"), count = 0)
  is LibraryBackupStatus.Comparing -> GalleryFooterNote(
    tone = GalleryFooterTone.Working,
    label = stringResource(R.string.backup_footer_comparing),
    busy = true,
    detail = status.pending.takeIf { it != null && it > 0 }?.let { pending ->
      pluralStringResource(R.plurals.backup_footer_comparing_count, pending, GalleryNumbers.format(pending))
    } ?: stringResource(R.string.backup_footer_comparing_search),
  )
  is LibraryBackupStatus.AllSafe -> GalleryFooterNote(
    tone = GalleryFooterTone.Calm,
    label = stringResource(R.string.backup_footer_all_safe),
    glyph = Icons.Rounded.GppGood,
    detail = status.atNasMillis?.let { backupUpdatedAt(it) },
  )
  is LibraryBackupStatus.Queued -> GalleryFooterNote(
    tone = GalleryFooterTone.Waiting,
    label = stringResource(R.string.backup_footer_queued, GalleryNumbers.format(status.items)),
    glyph = Icons.Rounded.Schedule,
    detail = stringResource(waitRes(status.wait)),
  )
  is LibraryBackupStatus.FirstBackup -> workingNote(
    label = stringResource(R.string.backup_footer_first, GalleryNumbers.format(status.done), GalleryNumbers.format(status.total)),
    done = status.done,
    total = status.total,
    etaMillis = status.etaMillis,
  )
  is LibraryBackupStatus.Running -> workingNote(
    label = stringResource(R.string.backup_footer_running, GalleryNumbers.format(status.done), GalleryNumbers.format(status.total)),
    done = status.done,
    total = status.total,
    etaMillis = status.etaMillis,
  )
  // CA-1 (Fase 10): le scritture ferme dal manifest firmato dicono la loro causa.
  is LibraryBackupStatus.Problem -> footerNote(
    BackupFooterProblems.of(status.code, dev.pampa.mycloudgallery.core.designsystem.state.LocalWritesHeldByRelease.current),
    status.count,
  )
}

/** Un lavoro del backup in corso: l'anello nella capsula e la barra sotto se il totale e' noto, il tempo che manca se si sa stimare. */
@Composable
private fun workingNote(label: String, done: Int, total: Int, etaMillis: Long?): GalleryFooterNote {
  val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
  return GalleryFooterNote(
    tone = GalleryFooterTone.Working,
    label = label,
    detail = etaMillis?.let { withEta(null, it) },
    progress = progress,
    busy = progress == null,
  )
}

/** Un problema del §2.6 come lo dice il piede: la frase breve (con il numero, per un problema di un media) e cosa fare. */
@Composable
private fun footerNote(problem: FooterProblem, count: Int): GalleryFooterNote {
  val withCount = problem.labelCount
  return GalleryFooterNote(
    tone = problem.tone,
    label = if (withCount != null && count > 0) pluralStringResource(withCount, count, GalleryNumbers.format(count)) else stringResource(problem.label),
    glyph = problem.glyph,
    detail = stringResource(problem.detail),
  )
}

private fun waitRes(wait: LibraryBackupWait): Int = when (wait) {
  LibraryBackupWait.CHARGING -> R.string.backup_footer_wait_charging
  LibraryBackupWait.WIFI -> R.string.backup_footer_wait_wifi
  LibraryBackupWait.HOME_WIFI -> R.string.backup_footer_wait_home_wifi
  LibraryBackupWait.NIGHT_WINDOW -> R.string.backup_footer_wait_night_window
  LibraryBackupWait.BATTERY -> R.string.backup_footer_wait_battery
  LibraryBackupWait.POWER_SAVER -> R.string.backup_footer_wait_power_saver
  LibraryBackupWait.NAS -> R.string.backup_footer_wait_nas
  LibraryBackupWait.NEXT_RUN -> R.string.backup_footer_wait_next_run
  LibraryBackupWait.NAS_BUSY -> R.string.backup_footer_wait_nas_busy
  LibraryBackupWait.OTHER -> R.string.backup_footer_wait_other
}

/** "Oggi alle 03:12", "12 ottobre 2026 alle 03:12": l'ultimo giro del backup, in ora del NAS mostrata come ora di casa. */
@Composable
private fun backupUpdatedAt(atNasMillis: Long): String {
  val at = Instant.ofEpochMilli(atNasMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
  val time = GalleryDates.time(at)
  return if (at.toLocalDate() == LocalDate.now()) {
    stringResource(R.string.backup_footer_all_safe_today, time)
  } else {
    stringResource(R.string.backup_footer_all_safe_day, GalleryDates.date(at), time)
  }
}

/** Che cosa il piede dice del NAS, o null se non c'e' niente da dire (nessun NAS). */
@Composable
internal fun nasFooterNote(status: LibraryNasStatus): GalleryFooterNote? = when (status) {
  LibraryNasStatus.Idle -> null
  is LibraryNasStatus.FirstScan -> {
    val total = status.total
    val progress = if (total != null && total > 0) (status.done.toFloat() / total).coerceIn(0f, 1f) else null
    val count = if (total != null && total > 0) {
      stringResource(R.string.nas_footer_scan_of, GalleryNumbers.format(status.done), GalleryNumbers.format(total))
    } else {
      countText(R.plurals.nas_footer_scan_seen, status.done)
    }
    GalleryFooterNote(
      tone = GalleryFooterTone.Working,
      label = stringResource(R.string.nas_footer_scanning),
      detail = withEta(count, status.etaMillis),
      progress = progress,
      busy = progress == null,
    )
  }
  is LibraryNasStatus.Aligning -> {
    val progress = if (status.bytesTotal > 0) (status.bytesDone.toFloat() / status.bytesTotal).coerceIn(0f, 1f) else null
    GalleryFooterNote(
      tone = GalleryFooterTone.Working,
      label = stringResource(R.string.nas_footer_aligning),
      detail = withEta(stringResource(R.string.nas_footer_scan_of, bytes(status.bytesDone), bytes(status.bytesTotal)), status.etaMillis),
      progress = progress,
      busy = progress == null,
    )
  }
  is LibraryNasStatus.UpToDate -> GalleryFooterNote(
    tone = GalleryFooterTone.Calm,
    label = stringResource(R.string.nas_footer_up_to_date),
    glyph = Icons.Rounded.CloudDone,
    detail = updatedAt(status.atNasMillis),
  )
  is LibraryNasStatus.Frozen -> GalleryFooterNote(
    tone = GalleryFooterTone.Alarm,
    label = stringResource(R.string.nas_footer_frozen),
    glyph = Icons.Rounded.PauseCircle,
    detail = stringResource(
      R.string.nas_footer_frozen_detail,
      stringResource(
        when (status.reason) {
          FreezeReason.VANISHED -> R.string.nas_footer_frozen_vanished
          FreezeReason.CHANGED -> R.string.nas_footer_frozen_changed
          FreezeReason.NOT_MEDIA -> R.string.nas_footer_frozen_not_media
        },
      ),
    ),
  )
  is LibraryNasStatus.AreasUnavailable -> {
    val label = when {
      status.areas.size == 1 && NasArea.COMMON in status.areas -> stringResource(R.string.nas_footer_common_unavailable)
      status.areas.size == 1 && NasArea.PRIVATE in status.areas -> stringResource(R.string.nas_footer_private_unavailable)
      else -> stringResource(R.string.nas_footer_unavailable)
    }
    GalleryFooterNote(
      tone = GalleryFooterTone.Attention,
      label = label,
      glyph = Icons.Rounded.CloudOff,
      detail = stringResource(R.string.nas_footer_unavailable_detail),
    )
  }
  LibraryNasStatus.LocalOnly -> GalleryFooterNote(
    tone = GalleryFooterTone.Waiting,
    label = stringResource(R.string.nas_footer_local_only),
    glyph = Icons.Rounded.Science,
    detail = stringResource(R.string.nas_footer_local_only_detail),
  )
}

/** "1.200 di 12.000 file · circa 4 min": il tempo che manca si aggiunge solo quando il compositore lo sa stimare. */
@Composable
private fun withEta(text: String?, etaMillis: Long?): String {
  if (etaMillis == null) return text.orEmpty()
  val minutes = ((etaMillis + MinuteMs - 1) / MinuteMs).toInt()
  val eta = when {
    etaMillis < MinuteMs -> stringResource(R.string.nas_footer_eta_under_minute)
    minutes < MinutesPerHour -> stringResource(R.string.nas_footer_eta_minutes, GalleryNumbers.format(minutes))
    else -> stringResource(R.string.nas_footer_eta_hours, GalleryNumbers.format(minutes / MinutesPerHour), GalleryNumbers.format(minutes % MinutesPerHour))
  }
  return if (text == null) eta else stringResource(R.string.nas_footer_with_eta, text, eta)
}

/** "Oggi alle 14:32", "12 ottobre 2026 alle 14:32": l'istante e' in ora del NAS, mostrato come ora di casa. */
@Composable
private fun updatedAt(atNasMillis: Long): String {
  val at = Instant.ofEpochMilli(atNasMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
  val time = GalleryDates.time(at)
  return if (at.toLocalDate() == LocalDate.now()) {
    stringResource(R.string.nas_footer_up_to_date_today, time)
  } else {
    stringResource(R.string.nas_footer_up_to_date_day, GalleryDates.date(at), time)
  }
}

/** I byte come li dice il piede: "640 MB", "1,2 GB" (unita' decimali, come il NAS). */
@Composable
private fun bytes(value: Long): String = if (value >= GigaByte) {
  val tenths = (value * TenthsPerUnit / GigaByte).toInt()
  stringResource(R.string.nas_footer_gigabytes, GalleryNumbers.format(tenths / TenthsPerUnit) + DecimalComma + (tenths % TenthsPerUnit))
} else {
  stringResource(R.string.nas_footer_megabytes, GalleryNumbers.format(value / MegaByte))
}

private const val MinuteMs = 60_000L
private const val MinutesPerHour = 60
private const val MegaByte = 1_000_000L
private const val GigaByte = 1_000_000_000L
private const val TenthsPerUnit = 10
private const val DecimalComma = ","
