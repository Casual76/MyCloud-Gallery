package dev.pampa.mycloudgallery.feature.backup.first

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.SummaryCount
import dev.pampa.mycloudgallery.core.backup.contract.SummaryWarning
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.glyph
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion
import kotlinx.coroutines.delay
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
// la stima onesta con le regole di adesso e le due scelte: "Stanotte" (la predefinita) e "Inizia ora". Solo componenti G2:
// `GalleryConfirmSheet` (con lo scudo contro le sovrapposizioni, R7 della 4B) e `GallerySheetScaffold`; solo numeri (mai nomi di file o
// cartelle dei media).
//
// Fase I (12/11, dal telefono dell'utente: «Sto confrontando 1.450 foto…» fermo per un'ora, i tasti spenti, «questa incertezza non sarebbe da
// lasciare ad un utente»). Le scelte aspettano **solo** la scansione dell'area privata, lo stesso cancello del piano: finche' si cerca il foglio
// mostra una riga con il numero dei file guardati e una barra che avanza (i numeri arrivano a ricerca finita). Il confronto delle copie simili
// non ferma niente (quelle foto si caricano comunque, D36): a ricerca finita i numeri ci sono subito, i tasti sono accesi e una riga piccola
// dice quante foto restano da confrontare. Se il lavoro e' fermo per una regola (il telefono caldo…), la riga dice quale, con la clessidra.

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
  val ready = summary.canStart
  GallerySheetScaffold(
    icon = Icons.Rounded.CloudUpload,
    tone = GallerySheetTone.Brand,
    title = stringResource(R.string.backup_first_title),
    message = headline(summary),
    modifier = modifier,
    body = {
      if (!ready) {
        // Finche' si cerca sul NAS i numeri non ci sono ancora: una riga sola, con la barra.
        SearchRow(summary.existing)
      } else {
        NumbersGroup(summary)
        if (summary.similarToCompare > 0) CompareRow(summary.similarToCompare, summary.existing.heldBy)
        if (summary.inCommonArea.items > 0) {
          BackupNote(pluralStringResource(R.plurals.backup_first_common, summary.inCommonArea.items, BackupText.count(summary.inCommonArea.items)))
        }
        EstimateGroup(summary)
        if (summary.warnings.isNotEmpty()) WarningsGroup(summary.warnings)
        if (summary.bySource.isNotEmpty()) SourcesGroup(summary)
      }
    },
    actions = {
      // "Stanotte" e' la scelta predefinita: il tasto pieno. Le due scelte aspettano solo la fine della ricerca sul NAS (Fase I).
      FluidButton(
        text = stringResource(R.string.backup_first_tonight),
        onClick = onTonight,
        size = FluidButtonSize.Large,
        enabled = ready,
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.backup_first_now),
        onClick = onNow,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        enabled = ready,
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
 * scorre sotto. Finche' si cerca sul NAS dice perche' prima si cerca; se le regole non lasciano partire il backup, lo dice.
 */
@Composable
private fun headline(summary: BackupSummary): String {
  if (!summary.canStart) return stringResource(R.string.backup_first_headline_search)
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

/**
 * Fase I: la ricerca sul NAS prima del primo backup, in una riga: quanti file del NAS ha gia' guardato (o che cosa fa) e una barra che avanza;
 * ferma per una regola, quale regola e che riprende da sola, con la clessidra e senza barra.
 */
@Composable
private fun SearchRow(existing: ExistingCopiesState) {
  val held = existing.heldBy
  FluidListGroup {
    FluidListRow(
      title = if (held != null) stringResource(heldRes(held)) else searchText(existing),
      subtitle = stringResource(if (held != null) R.string.backup_first_search_held else R.string.backup_first_search_detail),
      tone = FluidTone.Info,
      leading = { Icon(imageVector = if (held != null) GalleryIconStatus.Waiting.glyph() else Icons.Rounded.Search, contentDescription = null) },
      disclosure = false,
      badge = if (held != null) null else { { SearchBar(existing) } },
    )
  }
}

/**
 * Fase I: quanto la ricerca sul NAS e' avanti, per la riga del foglio e per la testata: "1.234 di 5.559 file del NAS guardati", "1.234 file del NAS
 * guardati" (il totale non si sa ancora), o che cosa fa finche' non c'e' un numero.
 */
@Composable
internal fun searchText(existing: ExistingCopiesState): String {
  val seen = existing.scanSeen ?: 0
  val total = existing.scanTotal
  return when {
    seen <= 0 -> stringResource(R.string.backup_first_searching)
    total != null && total >= seen -> stringResource(R.string.backup_first_search_of, BackupText.count(seen), BackupText.count(total))
    else -> pluralStringResource(R.plurals.backup_first_search_seen, seen, BackupText.count(seen))
  }
}

/**
 * La barra della ricerca, **a poco prezzo** (punto 3: il foglio e' di vetro e sta sopra una pagina sfocata): con il totale si allunga con la molla fino
 * alla parte guardata; a ogni numero nuovo (ogni 10 s circa) un tratto la percorre una volta. Senza ancora un numero il tratto passa ogni
 * [SearchIdleMillis]. Fra un passaggio e l'altro **nessun fotogramma**: niente ciclo infinito. Tutto nel disegno e nel suo layer, cosi' non ridisegna il resto
 * del foglio. Con «riduci animazioni» la barra e' ferma alla sua misura.
 */
@Composable
private fun SearchBar(existing: ExistingCopiesState) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val color = galleryRingColor(GalleryIconFamily.Backup)
  val track = color.copy(alpha = GalleryProgressRingDefaults.TrackAlpha)
  val seen = existing.scanSeen ?: 0
  val total = existing.scanTotal ?: 0
  val target = if (seen > 0 && total > 0) (seen.toFloat() / total).coerceIn(0f, 1f) else 0f
  val fill = remember { Animatable(0f) }
  val sweep = remember { Animatable(SweepRest) }
  LaunchedEffect(target, reduced) { if (reduced) fill.snapTo(target) else fill.animateTo(target, FluidMotion.smooth()) }
  LaunchedEffect(seen, reduced) {
    if (reduced) return@LaunchedEffect
    do {
      sweep.snapTo(0f)
      sweep.animateTo(1f, GalleryMotion.sweepSpec())
      sweep.snapTo(SweepRest)
      if (seen <= 0) delay(SearchIdleMillis)
    } while (seen <= 0)
  }
  Canvas(modifier = Modifier.width(BackupDimens.SearchBarWidth).height(BackupDimens.SearchBarHeight).graphicsLayer()) {
    val radius = CornerRadius(size.height / 2f, size.height / 2f)
    drawRoundRect(color = track, cornerRadius = radius)
    val filled = size.width * fill.value
    if (filled > 0f) drawRoundRect(color = color, size = Size(filled.coerceAtLeast(size.height), size.height), cornerRadius = radius)
    val at = sweep.value
    if (at in 0f..1f) {
      val segment = size.width * SearchSegment
      val x = (size.width + segment) * at - segment
      val start = x.coerceAtLeast(0f)
      val end = (x + segment).coerceAtMost(size.width)
      if (end > start) drawRoundRect(color = color, topLeft = Offset(start, 0f), size = Size(end - start, size.height), cornerRadius = radius)
    }
  }
}

/** Il tratto a riposo: fuori dalla pista, non si disegna. */
private const val SweepRest = -1f

/** Quanto della pista e' il tratto che la percorre. */
private const val SearchSegment = 0.35f

/** Senza ancora un numero, ogni quanto il tratto ripassa (un passaggio dura `GalleryMotion.ShimmerMillis`). */
private const val SearchIdleMillis = 2_600L

/**
 * Fase I: le foto che aspettano ancora il confronto con le copie simili. Una riga piccola che **non ferma niente**: dice quante, che il confronto
 * continua da solo (o la regola che lo tiene fermo adesso) e che non cambia che cosa si carica.
 */
@Composable
private fun CompareRow(count: Int, held: WaitReason?) {
  FluidListGroup {
    FluidListRow(
      title = pluralStringResource(R.plurals.backup_first_compare, count, BackupText.count(count)),
      subtitle = if (held != null) {
        stringResource(R.string.backup_first_compare_held, stringResource(heldRes(held)))
      } else {
        stringResource(R.string.backup_first_compare_detail)
      },
      tone = FluidTone.Info,
      leading = { Icon(imageVector = if (held != null) GalleryIconStatus.Waiting.glyph() else Icons.Rounded.Compare, contentDescription = null) },
      disclosure = false,
    )
  }
}

/** Fase I: perche' un lavoro e' fermo adesso, con le parole della Diagnostica (Attivita'). */
internal fun heldRes(reason: WaitReason): Int = when (reason) {
  WaitReason.CHARGING -> R.string.backup_held_charging
  WaitReason.WIFI -> R.string.backup_held_wifi
  WaitReason.HOME_WIFI -> R.string.backup_held_home_wifi
  WaitReason.NIGHT_WINDOW -> R.string.backup_held_night_window
  WaitReason.BATTERY -> R.string.backup_held_battery
  WaitReason.POWER_SAVER -> R.string.backup_held_power_saver
  WaitReason.THERMAL -> R.string.backup_held_thermal
  WaitReason.DATA_CAP -> R.string.backup_held_data_cap
  WaitReason.NAS -> R.string.backup_held_nas
  WaitReason.NAS_BUSY -> R.string.backup_held_nas_busy
  WaitReason.COMPARING, WaitReason.NEXT_RUN -> R.string.backup_held_other
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
