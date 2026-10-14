package dev.pampa.mycloudgallery.feature.library.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Science
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterEntry
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
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

// Il piede della Libreria (G2, docs/grafica-progetto.md §12; Fase 4 §1.5): sotto i piu' recenti, sulla pagina, la rete
// (l'indicatore globale di :app, `statusFooter`) e il NAS (`LibraryTabState.nas`) come due voci sorelle, una sotto
// l'altra: una capsula tonale con la frase breve e, sotto, il dettaglio (avanzamento, quando, perche'). Cosa dire lo
// decide il contratto (`LibraryNasStatus`, la regola e' `LibraryNasStatusComposer`); qui solo come.

/**
 * La voce della rete ([statusFooter], da `:app`) e sotto quella del NAS, centrate, ognuna con il suo dettaglio. Toccare la
 * capsula del NAS apre i dettagli ([onNasClick]: la Diagnostica, dove sta anche "Ho controllato, continua" dei lavori
 * fermi). Una sotto l'altra e non affiancate: una frase lunga ("Rete non riconosciuta") non tronca l'altra.
 */
@Composable
internal fun LibraryStatusBlock(nas: LibraryNasStatus, statusFooter: @Composable () -> Unit, onNasClick: () -> Unit, modifier: Modifier = Modifier) {
  val note = nasFooterNote(nas)
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GroupGap)) {
    statusFooter()
    if (note != null) GalleryFooterEntry(note = note, onClick = onNasClick)
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
private fun withEta(text: String, etaMillis: Long?): String {
  if (etaMillis == null) return text
  val minutes = ((etaMillis + MinuteMs - 1) / MinuteMs).toInt()
  val eta = when {
    etaMillis < MinuteMs -> stringResource(R.string.nas_footer_eta_under_minute)
    minutes < MinutesPerHour -> stringResource(R.string.nas_footer_eta_minutes, GalleryNumbers.format(minutes))
    else -> stringResource(R.string.nas_footer_eta_hours, GalleryNumbers.format(minutes / MinutesPerHour), GalleryNumbers.format(minutes % MinutesPerHour))
  }
  return stringResource(R.string.nas_footer_with_eta, text, eta)
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
private val GroupGap = 10.dp
