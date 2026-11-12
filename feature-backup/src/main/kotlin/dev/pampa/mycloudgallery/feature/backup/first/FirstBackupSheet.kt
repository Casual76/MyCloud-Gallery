package dev.pampa.mycloudgallery.feature.backup.first

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRowValue
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
import dev.pampa.mycloudgallery.core.designsystem.R as DesignR
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.glyph
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.ui.BackupNote
import dev.pampa.mycloudgallery.feature.backup.ui.BackupSectionTitle
import dev.pampa.mycloudgallery.feature.backup.ui.BackupText
import dev.pampa.mycloudgallery.feature.backup.ui.sourceNameById
import kotlinx.coroutines.delay

// Il foglio del primo backup (docs/fase5-progetto.md §1.5 `BackupSummary`, §2.11 punti 4 e 6, D11, D30): prima di caricare qualunque
// cosa dice quanto c'e' da fare, in **tre numeri** ("N gia' sul NAS (collegati), M con una copia simile sul NAS, K nuovi"), quanto pesa,
// il traffico (senza una durata: Fase I, BF-2) e le due scelte: "Stanotte" (la predefinita) e "Inizia ora". Solo componenti G2:
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
 * La frase in cima al foglio, che dice **subito** quanto c'e' da fare senza scorrere: "Da caricare: 1.546 foto e video, 3,0 GB." Il foglio e' alto al piu'
 * 640 dp e i tasti restano in vista: il resto (i tre numeri, il traffico, gli avvisi) scorre sotto. Finche' si cerca sul NAS dice che prima guarda; se le
 * regole non lasciano partire il backup, lo dice. **Nessuna durata** (Fase I, BF-2): il modello di serie era ottimista di circa tre volte.
 */
@Composable
private fun headline(summary: BackupSummary): String {
  if (!summary.canStart) return stringResource(R.string.backup_first_headline_search)
  val upload = summary.toUpload
  val base = stringResource(R.string.backup_first_headline, BackupText.count(upload.items), BackupText.size(upload.bytes))
  return when {
    summary.estimate?.neverFinishes == true -> "$base ${stringResource(R.string.backup_first_headline_never)}"
    // Giro 2 (BF2-6): senza numero, una frase sola se il lavoro e' grande; sotto soglia niente.
    LargeFirstBackup.of(summary) -> "$base ${stringResource(R.string.backup_first_long)}"
    else -> base
  }
}

/**
 * Giro 2 (BF2-5, BF2-6): il primo backup e' **grande** (ore, non minuti)? Una soglia sulle quantita', non sul modello di serie (ottimista di circa tre
 * volte): almeno [ITEMS] foto e video o [BYTES] byte, contando anche quelli gia' sul NAS (si rileggono per controllarli). Sul telefono dell'utente 7.105
 * media: oltre un'ora e tre quarti al ritmo misurato (65 al minuto). Lo stesso giudizio nel foglio e nella pagina.
 */
internal object LargeFirstBackup {
  const val ITEMS: Int = 2_000
  const val BYTES: Long = 10_000_000_000L

  fun isLarge(items: Int, bytes: Long): Boolean = items >= ITEMS || bytes >= BYTES

  fun of(summary: BackupSummary): Boolean =
    isLarge(summary.toUpload.items + summary.linked.items, summary.toUpload.bytes + summary.linked.bytes)
}

/**
 * Fase I: la ricerca sul NAS prima del primo backup, in una riga (BF-4, BF-9, BF-10):
 * - **gira** (lo scanner lavora): quanti file del NAS ha gia' guardato, come numero, e sotto la barra a tutta larghezza;
 * - **ferma per una regola**: quale, con la clessidra, e come riprende solo dove e' vero ([resumeRes]);
 * - **ferma per un motivo che il backup non nomina** (congelamento, attivita' spenta, profilo, limiti di Android): «Ricerca in pausa», il motivo e' nella
 *   Diagnostica, con il segno della pausa;
 * - **non gira ancora** (aspetta il suo lavoro, o il NAS appena aperta l'app): «Cerco… · Inizia fra poco», ferma.
 * Niente si anima se non gira niente.
 */
@Composable
private fun SearchRow(existing: ExistingCopiesState) {
  val held = existing.heldBy
  val scanning = held == null && existing.scanSeen != null
  val seen = existing.scanSeen ?: 0
  val total = existing.scanTotal
  FluidListGroup {
    FluidListRow(
      title = when {
        held == WaitReason.NEXT_RUN -> stringResource(R.string.backup_first_search_paused)
        held != null -> stringResource(waitTitleRes(held))
        scanning && seen > 0 && total != null && total >= seen -> stringResource(R.string.backup_first_search_count_of, BackupText.count(seen), BackupText.count(total))
        scanning && seen > 0 -> BackupText.count(seen)
        else -> stringResource(R.string.backup_first_searching)
      },
      subtitle = when {
        held == WaitReason.NEXT_RUN -> stringResource(R.string.backup_paused_elsewhere)
        held != null -> resumeRes(held)?.let { stringResource(it) }.orEmpty()
        scanning && seen > 0 -> pluralStringResource(R.plurals.backup_first_search_files, seen)
        scanning -> ""
        else -> stringResource(R.string.backup_first_search_soon)
      },
      tone = FluidTone.Info,
      leading = {
        val glyph = when {
          held == WaitReason.NEXT_RUN -> GalleryIconStatus.Paused.glyph()
          held != null -> GalleryIconStatus.Waiting.glyph()
          else -> Icons.Rounded.Search
        }
        Icon(imageVector = glyph, contentDescription = null)
      },
      disclosure = false,
    )
    if (scanning) {
      SearchBar(
        existing,
        Modifier
          .fillMaxWidth()
          .padding(start = BackupDimens.CardPadding, end = BackupDimens.CardPadding, bottom = BackupDimens.CardPadding),
      )
    }
  }
}

/**
 * Fase I: quanto la ricerca sul NAS e' avanti, per la testata: "1.234 di 5.559 file del NAS guardati", "1.234 file del NAS guardati" (il totale non si sa
 * ancora), o che cosa fa finche' non c'e' un numero.
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
 * La barra della ricerca, sotto il numero e a tutta larghezza (BF-10), **a poco prezzo** (il foglio e' di vetro sopra una pagina sfocata): con il totale si
 * allunga con la molla fino alla parte guardata; a ogni numero nuovo (ogni 10 s circa) un tratto la percorre una volta; senza il totale il tratto passa
 * ogni [SearchIdleMillis]. Fra un passaggio e l'altro nessun fotogramma. La pista e' il testo al [GalleryProgressRingDefaults.TrackAlpha], leggibile anche
 * nello scuro. Tutto nel disegno e nel suo layer. Con «riduci animazioni» la barra e' ferma alla sua misura.
 */
@Composable
private fun SearchBar(existing: ExistingCopiesState, modifier: Modifier) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val color = galleryRingColor(GalleryIconFamily.Backup)
  val track = MaterialTheme.colorScheme.onSurface.copy(alpha = GalleryProgressRingDefaults.TrackAlpha)
  val seen = existing.scanSeen ?: 0
  val total = existing.scanTotal ?: 0
  val known = seen > 0 && total > 0
  val target = if (known) (seen.toFloat() / total).coerceIn(0f, 1f) else 0f
  val fill = remember { Animatable(0f) }
  val sweep = remember { Animatable(SweepRest) }
  LaunchedEffect(target, reduced) { if (reduced) fill.snapTo(target) else fill.animateTo(target, FluidMotion.smooth()) }
  LaunchedEffect(seen, known, reduced) {
    if (reduced) return@LaunchedEffect
    do {
      sweep.snapTo(0f)
      sweep.animateTo(1f, GalleryMotion.sweepSpec())
      sweep.snapTo(SweepRest)
      if (!known) delay(SearchIdleMillis)
    } while (!known)
  }
  Canvas(modifier = modifier.height(BackupDimens.SearchBarHeight).graphicsLayer()) {
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

/** Senza il totale, ogni quanto il tratto ripassa (un passaggio dura `GalleryMotion.ShimmerMillis`). */
private const val SearchIdleMillis = 2_600L

/**
 * Fase I: le foto che aspettano ancora il confronto con le copie simili. Una riga piccola che **non ferma niente** e non promette niente che non sia vero.
 * Giro 2 (BS2-4): quando il confronto aspetta anteprime che non ci sono, la riga lo dice **senza una causa** (non sempre e' la Preparazione: puo' mancare
 * solo la copia sul telefono) e senza promettere che riprende da sola: «Alcune anteprime non sono ancora pronte».
 */
@Composable
private fun CompareRow(count: Int, held: WaitReason?) {
  FluidListGroup {
    FluidListRow(
      title = pluralStringResource(R.plurals.backup_first_compare, count, BackupText.count(count)),
      subtitle = stringResource(if (held == null) R.string.backup_first_compare_detail else R.string.backup_first_compare_waiting),
      tone = FluidTone.Info,
      leading = { Icon(imageVector = if (held != null) GalleryIconStatus.Waiting.glyph() else Icons.Rounded.Compare, contentDescription = null) },
      disclosure = false,
    )
  }
}

/**
 * Fase I (BF-8): perche' un lavoro e' fermo adesso, con **la formula comune** (`gallery_wait_*` di core-designsystem: la stessa del piede e dei titoli della
 * Diagnostica). [WaitReason.NEXT_RUN] e i motivi che il backup non nomina non arrivano qui (li dice la riga della pausa).
 */
internal fun waitTitleRes(reason: WaitReason): Int = when (reason) {
  WaitReason.CHARGING -> DesignR.string.gallery_wait_charging
  WaitReason.WIFI -> DesignR.string.gallery_wait_wifi
  WaitReason.HOME_WIFI -> DesignR.string.gallery_wait_home_wifi
  WaitReason.NIGHT_WINDOW -> DesignR.string.gallery_wait_night_window
  WaitReason.BATTERY -> DesignR.string.gallery_wait_battery
  WaitReason.POWER_SAVER -> DesignR.string.gallery_wait_power_saver
  WaitReason.THERMAL -> DesignR.string.gallery_wait_thermal
  WaitReason.DATA_CAP -> DesignR.string.gallery_wait_data_cap
  WaitReason.NAS -> DesignR.string.gallery_wait_nas
  WaitReason.NAS_BUSY -> R.string.backup_wait_nas_busy
  WaitReason.NOT_ON_PHONE -> R.string.backup_wait_not_on_phone
  WaitReason.COMPARING, WaitReason.NEXT_RUN -> R.string.backup_first_search_paused
}

/**
 * Fase I (BF-9, giro 2 BF2-1): **come** riprende la ricerca ferma per [reason], solo dove e' vero. Da sola per la fascia, la carica, la batteria e il caldo (i
 * lavori del NAS si riprogrammano da soli, `RuleWaits.followUpOf`); con il risparmio energetico **quando lo si spegne** (finche' e' acceso i tentativi
 * trovano lo stesso blocco). Null per la rete, il NAS spento e il tetto dei dati: si dice la condizione e basta.
 */
internal fun resumeRes(reason: WaitReason): Int? = when (reason) {
  WaitReason.CHARGING, WaitReason.BATTERY, WaitReason.NIGHT_WINDOW, WaitReason.THERMAL -> R.string.backup_first_search_resumes
  WaitReason.POWER_SAVER -> R.string.backup_first_search_resumes_power_saver
  WaitReason.WIFI, WaitReason.HOME_WIFI, WaitReason.NAS, WaitReason.DATA_CAP, WaitReason.NAS_BUSY, WaitReason.COMPARING, WaitReason.NEXT_RUN,
  WaitReason.NOT_ON_PHONE,
  -> null
}

/** La stessa promessa nella riga della testata («Il telefono è troppo caldo: la ricerca riprende da sola»), o null: solo il motivo. */
internal fun heroResumeRes(reason: WaitReason): Int? = when (resumeRes(reason)) {
  R.string.backup_first_search_resumes -> R.string.backup_hero_search_held
  R.string.backup_first_search_resumes_power_saver -> R.string.backup_hero_search_held_power_saver
  else -> null
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
 * Quanto c'e' da caricare e che cosa costa, **in quantita'** (Fase I, BF-2): nessuna durata, ne' per «Stanotte» ne' per «Inizia ora», ne' la batteria che ne
 * deriva. Restano il totale da caricare, il traffico (anche la rilettura delle copie gia' sul NAS), i dati mobili di «Inizia ora» e, se le regole non lasciano
 * partire il backup (`neverFinishes`), il perche'.
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
    if (tonight?.neverFinishes == true) {
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.backup_first_tonight),
        subtitle = stringResource(R.string.backup_first_never),
        tone = FluidTone.Warning,
        disclosure = false,
        badge = { FluidRowValue(text = stringResource(R.string.backup_first_none)) },
      )
    }
    if (now != null && now.mobileBytes > 0) {
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.backup_first_now),
        subtitle = stringResource(R.string.backup_first_now_mobile, BackupText.size(now.mobileBytes)),
        disclosure = false,
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
