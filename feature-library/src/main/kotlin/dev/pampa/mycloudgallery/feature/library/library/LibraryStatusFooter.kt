package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryLiveAnnouncement
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.glyph
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
  // CU-1: lo stesso stato detto da due parti (la recinzione del NAS e del backup) si annuncia una volta.
  val news = notes.filterNotNull().filter { it.tone != GalleryFooterTone.Calm }.distinctBy { it.label to it.detail }
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
  is LibraryBackupStatus.Comparing -> searchNote(status)
  is LibraryBackupStatus.AllSafe -> GalleryFooterNote(
    tone = GalleryFooterTone.Calm,
    label = stringResource(R.string.backup_footer_all_safe),
    glyph = Icons.Rounded.GppGood,
    detail = status.atNasMillis?.let { backupUpdatedAt(it) },
  )
  // Fase I (BF-6): il primo backup pronto non e' «in coda al prossimo giro»: e' da avviare (tocca a te), e il piede porta alla pagina.
  is LibraryBackupStatus.Queued -> if (status.wait == LibraryBackupWait.FIRST_BACKUP) {
    GalleryFooterNote(
      tone = GalleryFooterTone.Waiting,
      label = stringResource(R.string.backup_footer_first_ready),
      glyph = GalleryIconStatus.YourTurn.glyph(),
      detail = pluralStringResource(R.plurals.backup_footer_first_ready_detail, status.items, GalleryNumbers.format(status.items)),
    )
  } else {
    GalleryFooterNote(
      tone = GalleryFooterTone.Waiting,
      label = stringResource(R.string.backup_footer_queued, GalleryNumbers.format(status.items)),
      // CU-33: l'attesa e' la clessidra, sempre.
      glyph = Icons.Rounded.HourglassTop,
      detail = stringResource(waitRes(status.wait)),
    )
  }
  // Giro 2 (BF2-4): chiesto adesso e accettato, il motore non l'ha ancora preso: «Sto partendo» come la pagina e le Impostazioni, mai «0 di N».
  is LibraryBackupStatus.FirstBackup -> if (status.starting) {
    startingNote()
  } else {
    workingNote(
      label = stringResource(R.string.backup_footer_first, GalleryNumbers.format(status.done), GalleryNumbers.format(status.total)),
      done = status.done,
      total = status.total,
      etaMillis = status.etaMillis,
    )
  }
  is LibraryBackupStatus.Running -> if (status.starting) {
    startingNote()
  } else {
    workingNote(
      label = stringResource(R.string.backup_footer_running, GalleryNumbers.format(status.done), GalleryNumbers.format(status.total)),
      done = status.done,
      total = status.total,
      etaMillis = status.etaMillis,
    )
  }
  // OA-10: il backup fermo si dice. La pausa dell'utente dura fino a "Riprendi" (nella pagina Backup: il tasto del pop-up porta li');
  // il lavoro fermato da Android riprende da solo, come una coda.
  is LibraryBackupStatus.Paused -> GalleryFooterNote(
    tone = GalleryFooterTone.Waiting,
    label = stringResource(if (status.byUser) R.string.library_cloud_paused_label else R.string.library_cloud_interrupted_label),
    // CS-25 (fase I): la pausa ha le due barre, il lavoro fermato da Android (riparte da solo) la clessidra.
    glyph = if (status.byUser) GalleryIconStatus.Paused.glyph() else Icons.Rounded.HourglassTop,
    detail = stringResource(if (status.byUser) R.string.library_cloud_paused_detail else R.string.library_cloud_interrupted_detail),
  )
  // CA-1 (Fase 10): le scritture ferme dal manifest firmato dicono la loro causa.
  is LibraryBackupStatus.Problem -> footerNote(
    BackupFooterProblems.of(status.code, dev.pampa.mycloudgallery.core.designsystem.state.LocalWritesHeldByRelease.current),
    status.count,
  )
}

/**
 * Fase I (BF-7, BF-4): la ricerca sul NAS prima del primo backup, con le parole della pagina («Cerco cosa c'e' gia' sul NAS»). Gira: i file guardati e la
 * barra (con il totale) o l'anello (senza). Ferma per una regola: quale, con la clessidra (la formula comune). Ferma per un motivo che il backup non nomina:
 * la pausa e la Diagnostica. Non ancora partita: «Inizia fra poco», ferma. Si muove solo se la ricerca gira.
 */
@Composable
private fun searchNote(status: LibraryBackupStatus.Comparing): GalleryFooterNote {
  val wait = status.wait
  val seen = status.seen
  val total = status.total
  return when {
    wait == LibraryBackupWait.OTHER -> GalleryFooterNote(
      tone = GalleryFooterTone.Waiting,
      label = stringResource(R.string.backup_footer_comparing_paused),
      glyph = GalleryIconStatus.Paused.glyph(),
      detail = stringResource(R.string.backup_footer_search_elsewhere),
    )
    wait != null -> GalleryFooterNote(
      tone = GalleryFooterTone.Waiting,
      label = stringResource(R.string.backup_footer_comparing_paused),
      glyph = Icons.Rounded.HourglassTop,
      detail = stringResource(waitRes(wait)),
    )
    seen != null -> GalleryFooterNote(
      tone = GalleryFooterTone.Working,
      label = stringResource(R.string.backup_footer_comparing),
      detail = when {
        seen <= 0 -> null
        total != null && total >= seen -> stringResource(R.string.backup_footer_search_of, GalleryNumbers.format(seen), GalleryNumbers.format(total))
        else -> pluralStringResource(R.plurals.backup_footer_search_seen, seen, GalleryNumbers.format(seen))
      },
      progress = if (total != null && total > 0) (seen.toFloat() / total).coerceIn(0f, 1f) else null,
      busy = total == null || total <= 0,
    )
    else -> GalleryFooterNote(
      tone = GalleryFooterTone.Waiting,
      label = stringResource(R.string.backup_footer_comparing),
      glyph = Icons.Rounded.HourglassTop,
      detail = stringResource(R.string.backup_footer_search_soon),
    )
  }
}

/** Giro 2 (BF2-4): «Sto partendo», l'anello che gira senza misura e nessun numero. */
@Composable
private fun startingNote(): GalleryFooterNote = GalleryFooterNote(
  tone = GalleryFooterTone.Working,
  label = stringResource(R.string.backup_footer_starting),
  busy = true,
)

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
  LibraryBackupWait.THERMAL -> R.string.backup_footer_wait_thermal
  LibraryBackupWait.DATA_CAP -> R.string.backup_footer_wait_data_cap
  LibraryBackupWait.FIRST_BACKUP -> R.string.backup_footer_first_ready
  LibraryBackupWait.NAS -> R.string.backup_footer_wait_nas
  LibraryBackupWait.NEXT_RUN -> R.string.backup_footer_wait_next_run
  LibraryBackupWait.NAS_BUSY -> R.string.backup_footer_wait_nas_busy
  LibraryBackupWait.OTHER -> R.string.backup_footer_wait_other
  LibraryBackupWait.NOT_ON_PHONE -> R.string.backup_footer_wait_not_on_phone
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
  // CU-1, CS-3: la recinzione si chiama «In pausa» ovunque, con le due barre (la stessa voce del backup vero non ancora acceso).
  LibraryNasStatus.LocalOnly -> GalleryFooterNote(
    tone = GalleryFooterTone.Waiting,
    label = stringResource(R.string.nas_footer_local_only),
    glyph = GalleryIconStatus.Paused.glyph(),
    detail = stringResource(R.string.nas_footer_local_only_detail),
  )
}

/** "1.200 di 12.000 file · circa 4 min": il tempo che manca si aggiunge solo quando il compositore lo sa stimare. */
@Composable
internal fun withEta(text: String?, etaMillis: Long?): String {
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
