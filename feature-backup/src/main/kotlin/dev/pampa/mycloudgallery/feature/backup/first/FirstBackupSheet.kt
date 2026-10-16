package dev.pampa.mycloudgallery.feature.backup.first

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.SummaryCount
import dev.pampa.mycloudgallery.core.backup.contract.SummaryWarning
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.ui.BackupNote
import dev.pampa.mycloudgallery.feature.backup.ui.BackupSectionTitle
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.etaText
import dev.pampa.mycloudgallery.feature.backup.ui.sourceNameById

// Il foglio del primo backup (docs/fase5-progetto.md §1.5 `BackupSummary`, §2.11 punti 4 e 6, D11, D30): prima di caricare qualunque
// cosa dice quanto c'e' da fare, in **tre numeri** ("N gia' sul NAS (collegati), M con una copia simile sul NAS, K nuovi"), quanto pesa,
// la stima onesta con le regole di adesso e le due scelte: "Stanotte" (la predefinita) e "Inizia ora". Finche' la scansione dell'area
// privata e il confronto delle copie simili non sono finiti dice "Sto cercando cosa c'e' gia' sul NAS…": i numeri sono provvisori e le
// scelte aspettano. Solo componenti G2: `GalleryConfirmSheet` (con lo scudo contro le sovrapposizioni, R7 della 4B) e
// `GallerySheetScaffold`; solo numeri (mai nomi di file o cartelle dei media).

/**
 * Il foglio del primo backup nel suo pop-up. [onTonight] e [onNow] sono le due scelte (le esegue `BackupActions.startFirstBackup`); chiudere
 * il foglio non sceglie niente: il riepilogo resta in pagina.
 */
@Composable
internal fun FirstBackupSheet(
  visible: Boolean,
  summary: BackupSummary,
  onTonight: () -> Unit,
  onNow: () -> Unit,
  onDismissRequest: () -> Unit,
) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismissRequest, paneTitle = stringResource(R.string.backup_first_title)) {
    FirstBackupSheetContent(summary = summary, onTonight = onTonight, onNow = onNow, onCancel = onDismissRequest)
  }
}

/** Il contenuto del foglio (anche per gli screenshot e i test, senza il pop-up). */
@Composable
internal fun FirstBackupSheetContent(
  summary: BackupSummary,
  onTonight: () -> Unit,
  onNow: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val comparing = summary.comparing
  GallerySheetScaffold(
    icon = Icons.Rounded.CloudUpload,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.backup_first_title),
    message = headline(summary),
    modifier = modifier,
    body = {
      if (comparing) ComparingRow(summary)
      NumbersGroup(summary)
      if (summary.inCommonArea.items > 0) {
        BackupNote(pluralStringResource(R.plurals.backup_first_common, summary.inCommonArea.items, BackupText.count(summary.inCommonArea.items)))
      }
      EstimateGroup(summary)
      if (summary.warnings.isNotEmpty()) WarningsGroup(summary.warnings)
      if (summary.bySource.isNotEmpty()) SourcesGroup(summary)
    },
    actions = {
      // "Stanotte" e' la scelta predefinita: il tasto pieno. Con i numeri ancora provvisori le due scelte aspettano.
      FluidButton(
        text = stringResource(R.string.backup_first_tonight),
        onClick = onTonight,
        size = FluidButtonSize.Large,
        enabled = !comparing,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.backup_first_now),
        onClick = onNow,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        enabled = !comparing,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.backup_first_later),
        onClick = onCancel,
        style = FluidButtonStyle.Plain,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    },
  )
}

/**
 * La frase in cima al foglio, che dice **subito** quanto c'e' da fare senza scorrere: "Da caricare: 5.418 foto e video, 38 GB. Stanotte:
 * circa 2 h 36 min." Il foglio e' alto al piu' 640 dp e i tasti restano in vista: il resto (i tre numeri, la stima per esteso, gli avvisi)
 * scorre sotto. Finche' si confronta con il NAS i numeri sono provvisori e lo dice; se le regole non lasciano partire il backup, lo dice.
 */
@Composable
private fun headline(summary: BackupSummary): String {
  if (summary.comparing) return stringResource(R.string.backup_first_headline_comparing)
  val upload = summary.toUpload
  val base = stringResource(R.string.backup_first_headline, BackupText.count(upload.items), BackupText.size(upload.bytes))
  val tonight = summary.estimate
  val tail = when {
    tonight == null -> null
    tonight.neverFinishes -> stringResource(R.string.backup_first_headline_never)
    else -> stringResource(R.string.backup_first_headline_eta, etaText(tonight.durationMillis))
  }
  return if (tail == null) base else "$base $tail"
}

/** "Sto cercando cosa c'e' gia' sul NAS…" (scansione) o "Sto confrontando N foto con le copie sul NAS…" (copie simili). */
@Composable
private fun ComparingRow(summary: BackupSummary) {
  val existing = summary.existing
  val title = if (!existing.privateAreaScanned) {
    stringResource(R.string.backup_first_searching)
  } else {
    pluralStringResource(R.plurals.backup_first_comparing, existing.similarPending, BackupText.count(existing.similarPending))
  }
  FluidListGroup {
    FluidListRow(
      title = title,
      subtitle = stringResource(R.string.backup_first_provisional),
      tone = FluidTone.Info,
      leading = { FluidSpinner(size = BackupDimens.RowGlyph) },
      disclosure = false,
    )
  }
}

/** I tre numeri del §2.11 punto 6, ognuno con il suo peso. */
@Composable
private fun NumbersGroup(summary: BackupSummary) {
  FluidListGroup {
    NumberRow(R.string.backup_first_linked, R.string.backup_first_linked_detail, summary.linked, FluidTone.Success)
    FluidListDivider()
    NumberRow(R.string.backup_first_similar, R.string.backup_first_similar_detail, summary.similar, FluidTone.Info)
    FluidListDivider()
    NumberRow(R.string.backup_first_fresh, R.string.backup_first_fresh_detail, summary.fresh, FluidTone.Primary)
  }
}

@Composable
private fun NumberRow(titleRes: Int, detailRes: Int, count: SummaryCount, tone: FluidTone) {
  FluidListRow(
    title = stringResource(titleRes, BackupText.count(count.items)),
    subtitle = stringResource(detailRes),
    tone = tone,
    disclosure = false,
    badge = { FluidRowValue(text = BackupText.size(count.bytes)) },
  )
}

/**
 * Quanto c'e' da caricare e la stima con le regole di adesso (§2.9): una riga per ognuna delle due scelte ("Stanotte" segue il preset,
 * "Inizia ora" parte subito e scavalca fascia e carica), poi traffico e batteria. Se le regole non lasciano partire il backup
 * (`neverFinishes`) lo dice invece di "0 notti".
 */
@Composable
private fun EstimateGroup(summary: BackupSummary) {
  val upload = summary.toUpload
  val tonight = summary.estimate
  val now = summary.estimateNow
  val basis = tonight?.takeUnless { it.neverFinishes } ?: now
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.backup_first_total),
      subtitle = stringResource(R.string.backup_first_total_detail),
      tone = FluidTone.Primary,
      disclosure = false,
      badge = { FluidRowValue(text = stringResource(R.string.backup_first_total_value, BackupText.count(upload.items), BackupText.size(upload.bytes))) },
    )
    FluidListDivider()
    if (tonight == null && now == null) {
      FluidListRow(
        title = stringResource(R.string.backup_first_time),
        subtitle = stringResource(R.string.backup_first_time_unknown),
        disclosure = false,
        badge = { FluidRowValue(text = stringResource(R.string.backup_first_none)) },
      )
    } else {
      if (tonight != null) {
        FluidListRow(
          title = stringResource(R.string.backup_first_tonight),
          subtitle = if (tonight.neverFinishes) {
            stringResource(R.string.backup_first_never)
          } else {
            pluralStringResource(R.plurals.backup_first_nights, tonight.nights, BackupText.count(tonight.nights))
          },
          tone = if (tonight.neverFinishes) FluidTone.Warning else FluidTone.Neutral,
          disclosure = false,
          badge = { FluidRowValue(text = if (tonight.neverFinishes) stringResource(R.string.backup_first_none) else etaText(tonight.durationMillis)) },
        )
      }
      if (now != null) {
        if (tonight != null) FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.backup_first_now),
          subtitle = if (now.mobileBytes > 0) {
            stringResource(R.string.backup_first_now_mobile, BackupText.size(now.mobileBytes))
          } else {
            stringResource(R.string.backup_first_now_detail)
          },
          disclosure = false,
          badge = { FluidRowValue(text = etaText(now.durationMillis)) },
        )
      }
      if (basis != null) {
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.backup_first_traffic),
          subtitle = if (basis.mobileBytes > 0) {
            stringResource(R.string.backup_first_traffic_mobile, BackupText.size(basis.mobileBytes))
          } else {
            stringResource(R.string.backup_first_traffic_wifi_only)
          },
          disclosure = false,
          badge = { FluidRowValue(text = BackupText.size(basis.wifiBytes)) },
        )
        basis.batteryPercent?.let { percent ->
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.backup_first_battery),
            subtitle = stringResource(R.string.backup_first_battery_detail),
            disclosure = false,
            leading = { Icon(imageVector = Icons.Rounded.Battery5Bar, contentDescription = null) },
            badge = { FluidRowValue(text = stringResource(R.string.backup_first_battery_value, percent)) },
          )
        }
      }
    }
  }
}

/** Gli avvisi: meglio in carica e sul Wi-Fi di casa, dati mobili, memoria, accesso parziale alle foto, posizione, piu' di una notte. */
@Composable
private fun WarningsGroup(warnings: Set<SummaryWarning>) {
  FluidListGroup {
    // Nell'ordine del contratto, non in quello di un insieme: il foglio non cambia da una volta all'altra.
    SummaryWarning.entries.filter { it in warnings }.forEachIndexed { index, warning ->
      if (index > 0) FluidListDivider()
      FluidListRow(
        title = stringResource(warningRes(warning)),
        subtitle = "",
        tone = FluidTone.Warning,
        disclosure = false,
        leading = { Icon(imageVector = Icons.Rounded.Warning, contentDescription = null) },
      )
    }
  }
}

private fun warningRes(warning: SummaryWarning): Int = when (warning) {
  SummaryWarning.MOBILE_DATA -> R.string.backup_first_warning_mobile_data
  SummaryWarning.ON_BATTERY -> R.string.backup_first_warning_on_battery
  SummaryWarning.PHONE_STORAGE_LOW -> R.string.backup_first_warning_storage
  SummaryWarning.PARTIAL_ACCESS -> R.string.backup_first_warning_partial_access
  SummaryWarning.MEDIA_LOCATION -> R.string.backup_first_warning_media_location
  SummaryWarning.LONG_FIRST_BACKUP -> R.string.backup_first_warning_long
}

/** Da caricare per sorgente (`BackupSummary.bySource`): il nome della sorgente, quanti e quanto pesano. */
@Composable
private fun SourcesGroup(summary: BackupSummary) {
  BackupSectionTitle(stringResource(R.string.backup_first_by_source), Modifier.padding(top = BackupDimens.LineGap))
  FluidListGroup {
    summary.bySource.entries.forEachIndexed { index, (id, count) ->
      if (index > 0) FluidListDivider()
      FluidListRow(
        title = sourceNameById(id),
        subtitle = pluralStringResource(R.plurals.backup_first_source_items, count.items, BackupText.count(count.items)),
        disclosure = false,
        badge = { FluidRowValue(text = BackupText.size(count.bytes)) },
      )
    }
  }
}
