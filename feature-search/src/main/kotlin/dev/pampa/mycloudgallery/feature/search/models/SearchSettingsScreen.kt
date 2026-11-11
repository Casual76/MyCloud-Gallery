package dev.pampa.mycloudgallery.feature.search.models

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryIconColors
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryStatusColors
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.feature.search.R
import dev.pampa.mycloudgallery.feature.search.contract.AreaKind
import dev.pampa.mycloudgallery.feature.search.contract.IndexerState
import dev.pampa.mycloudgallery.feature.search.contract.SearchSettingsState
import dev.pampa.mycloudgallery.feature.search.sizeText
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens

// LA PAGINA IMPOSTAZIONI → RICERCA E MODELLI (Fase 6, P8, docs/fase6-progetto.md §2.6; D15, D20, D31, D41; Q3, Q13). I2 della fase I («parla troppo»): era
// una pagina di frasi; adesso **si capisce guardandola**:
//  - in testa la copertura come **un anello** che avanza, con il numero delle foto che la ricerca conosce e il segno «solo sul telefono»; sotto, i motivi
//    di cio' che manca come segni con un numero;
//  - **chi analizza**: le due aree (comune, privata) come piastrelle con il loro segno (✓ qualcuno le analizza, ! nessuno: qui e solo qui, non piu' nella
//    ricerca) e l'interruttore «questo dispositivo analizza»; lo stato dell'analisi come anello o segno con un numero, la misura del dispositivo come segno;
//  - la lettura del testo come una riga con il suo segno di stato e i suoi tasti;
//  - i modelli come **anelli** (pronto, in arrivo con l'avanzamento, assente, problema): il dettaglio e le azioni dietro un tocco;
//  - le ricerche recenti, con quante sono.
// Le spiegazioni lunghe stanno dietro il segno «i». La logica (ruoli, richieste dei modelli, misura legata a STARTED, primo avvio di HR) non cambia: cambia
// solo come si mostra. Le sezioni non collegate ([hasIndexer]...) non si disegnano. TalkBack dice le frasi intere.

@Composable
fun SearchSettingsScreen(
  state: SearchSettingsState,
  measuring: Boolean,
  hasIndexer: Boolean,
  hasTextReading: Boolean,
  hasModels: Boolean,
  handlers: SearchSettingsHandlers,
  onBack: () -> Unit,
  probeFailed: Boolean = false,
  probeHot: Boolean = false,
  probeBusy: Boolean = false,
) {
  FluidScreen(title = stringResource(R.string.models_title), onBack = onBack) {
    item(key = "coverage") { CoverageHero(state.coverage, explainIndexer = hasIndexer) }
    if (hasIndexer) {
      item(key = "indexer-header") { GallerySectionHeader(title = stringResource(R.string.models_section_indexer)) }
      item(key = "areas") { AreaTiles(state.indexer.areasWithoutIndexer) }
      item(key = "indexer") { IndexerBlock(state, measuring, handlers, probeFailed, probeHot, probeBusy) }
    }
    if (hasTextReading) {
      item(key = "reading-header") { GallerySectionHeader(title = stringResource(R.string.models_section_reading)) }
      item(key = "reading") { ReadingBlock(state, handlers) }
    }
    if (hasModels) modelsSection(state.models, handlers)
    item(key = "history-header") { GallerySectionHeader(title = stringResource(R.string.models_section_history)) }
    item(key = "history") { HistoryBlock(state.historyCount, handlers) }
  }
}

// --- La copertura --------------------------------------------------------------------------------------------------------------------------------------

/**
 * La copertura in testa alla pagina: un anello (la frazione delle foto che la ricerca per contenuto conosce) con la percentuale dentro e accanto quante
 * (grande) su quante (piccolo); sotto, un segno con un numero per ogni motivo di cio' che manca. Le frasi (la copertura per intero, «niente esce da qui»,
 * che cosa vuol dire analizzare) stanno dietro la «i» nell'angolo: una spiegazione sola per la pagina.
 */
@Composable
private fun CoverageHero(coverage: SearchCoverage, explainIndexer: Boolean) {
  val resources = LocalResources.current
  var explaining by remember { mutableStateOf(false) }
  val searchHue = rememberGalleryIconColors(GalleryIconFamily.Search).top
  val doneHue = rememberGalleryStatusColors(GalleryIconStatus.Done).top
  val sentence = coverageText(resources, coverage)
  FluidListGroup {
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(SearchDimens.HeroPadding),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val (fraction, hue) = when (coverage) {
        SearchCoverage.Complete -> 1f to doneHue
        is SearchCoverage.Partial -> coverage.fraction to searchHue
        is SearchCoverage.Updating -> coverage.percent / PERCENT to searchHue
        is SearchCoverage.ModelMissing -> 0f to searchHue
      }
      SearchRing(
        fraction = fraction,
        color = hue,
        size = SearchDimens.HeroRing,
        stroke = SearchDimens.HeroRingStroke,
        modifier = Modifier.clearAndSetSemantics { contentDescription = sentence },
      ) {
        when (coverage) {
          SearchCoverage.Complete -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = doneHue, modifier = Modifier.size(SearchDimens.ModelGlyph))
          is SearchCoverage.ModelMissing -> Icon(Icons.Rounded.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(SearchDimens.ModelGlyph))
          else -> Text(
            text = stringResource(R.string.models_percent, (fraction * PERCENT).toInt()),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
      Spacer(Modifier.width(SearchDimens.HeroPadding))
      // Accanto all'anello il numero grande e, sotto, piccolo, il resto: due righe che non vanno mai a capo a 360 dp (la «i» sta nell'angolo).
      Column(modifier = Modifier.weight(1f).padding(end = GalleryDimens.MinTouchTarget).clearAndSetSemantics { }) {
        val (glyph, headline, detail) = when (coverage) {
          SearchCoverage.Complete -> Triple(Icons.Rounded.PhotoLibrary, stringResource(R.string.models_coverage_all), null)
          is SearchCoverage.Partial -> Triple(
            Icons.Rounded.PhotoLibrary,
            GalleryNumbers.format(coverage.covered),
            stringResource(R.string.models_count_of_total, GalleryNumbers.format(coverage.total)),
          )
          is SearchCoverage.Updating -> Triple(Icons.Rounded.Sync, stringResource(R.string.models_coverage_updating_short), null)
          // La freccia e' gia' nell'anello: qui niente segno, per non dirla due volte.
          is SearchCoverage.ModelMissing -> Triple(null, stringResource(R.string.models_coverage_missing_short), sizeText(coverage.sizeBytes))
        }
        GlyphLine(glyph, headline, MaterialTheme.typography.titleLarge, MaterialTheme.colorScheme.onSurface)
        if (detail != null) {
          Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = if (glyph != null) Modifier.padding(start = SearchDimens.InlineGlyph + SearchDimens.TightGap) else Modifier,
          )
        }
      }
    }
    InfoButton(
      label = stringResource(R.string.models_coverage_info),
      onClick = { explaining = true },
      modifier = Modifier.align(Alignment.TopEnd).padding(SearchDimens.TightGap),
    )
    }
    if (coverage is SearchCoverage.Partial) {
      coverage.reasons.entries.filter { it.value > 0 }.sortedByDescending { it.value }.forEach { (reason, count) ->
        FluidListDivider()
        ReasonRow(reason, count, resources)
      }
    }
  }
  if (explaining) {
    val note = if (coverage is SearchCoverage.Partial && CoverageReason.TEXT_READING_OFF in coverage.reasons) "\n\n" + stringResource(R.string.models_reading_coverage_note) else ""
    val indexer = if (explainIndexer) "\n\n" + stringResource(R.string.models_indexer_detail) else ""
    InfoSheet(
      title = stringResource(R.string.models_section_coverage),
      message = sentence + note + indexer + "\n\n" + stringResource(R.string.models_subtitle),
      onDismiss = { explaining = false },
    )
  }
}

/** Un segno e un testo corto sulla stessa riga. */
@Composable
private fun GlyphLine(glyph: ImageVector?, text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    if (glyph != null) {
      Icon(glyph, contentDescription = null, tint = color, modifier = Modifier.size(SearchDimens.InlineGlyph))
      Spacer(Modifier.width(SearchDimens.TightGap))
    }
    Text(text = text, style = style, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}

/** Un motivo di cio' che la ricerca non conosce ancora: il suo segno, due parole, il numero. TalkBack dice la frase intera. */
@Composable
private fun ReasonRow(reason: CoverageReason, count: Int, resources: Resources) {
  val (glyph, family, label) = when (reason) {
    CoverageReason.PREPARATION -> Triple(Icons.Rounded.HourglassTop, GalleryIconFamily.Photos, R.string.models_reason_preparation_short)
    CoverageReason.INDEXER_RULES -> Triple(Icons.AutoMirrored.Rounded.Rule, GalleryIconFamily.Backup, R.string.models_reason_indexer_rules_short)
    CoverageReason.NO_INDEXER_FOR_AREA -> Triple(Icons.Rounded.Groups, GalleryIconFamily.People, R.string.models_reason_no_indexer_short)
    CoverageReason.MODEL_MISSING -> Triple(Icons.Rounded.Download, GalleryIconFamily.Search, R.string.models_reason_model_missing_short)
    CoverageReason.PHONE_ONLY_NOT_INDEXER -> Triple(Icons.Rounded.PhoneAndroid, GalleryIconFamily.System, R.string.models_reason_phone_only_short)
    CoverageReason.UNSUPPORTED -> Triple(Icons.Rounded.BrokenImage, GalleryIconFamily.System, R.string.models_reason_unsupported_short)
    CoverageReason.TEXT_READING_OFF -> Triple(Icons.Rounded.TextFields, GalleryIconFamily.Search, R.string.models_reason_text_reading_off_short)
  }
  val sentence = reasonText(resources, reason, count)
  FluidListRow(
    title = stringResource(label),
    subtitle = "",
    meta = GalleryNumbers.format(count),
    leading = { GalleryIconTile(icon = glyph, family = family) },
    leadingTile = false,
    disclosure = false,
    titleMaxLines = 2,
    modifier = Modifier.clearAndSetSemantics { contentDescription = sentence },
  )
}

internal fun coverageText(resources: Resources, coverage: SearchCoverage): String = when (coverage) {
  SearchCoverage.Complete -> resources.getString(R.string.models_coverage_complete)
  is SearchCoverage.Partial -> resources.getString(R.string.models_coverage_partial, GalleryNumbers.format(coverage.covered), GalleryNumbers.format(coverage.total))
  is SearchCoverage.ModelMissing -> resources.getString(R.string.models_coverage_model_missing, resources.getString(R.string.search_size_mb, GalleryNumbers.format((coverage.sizeBytes + HALF_MIB) / MIB)))
  is SearchCoverage.Updating -> resources.getString(R.string.models_coverage_updating, coverage.percent)
}

private fun reasonText(resources: Resources, reason: CoverageReason, count: Int): String = resources.getString(
  when (reason) {
    CoverageReason.PREPARATION -> R.string.models_reason_preparation
    CoverageReason.INDEXER_RULES -> R.string.models_reason_indexer_rules
    CoverageReason.NO_INDEXER_FOR_AREA -> R.string.models_reason_no_indexer
    CoverageReason.MODEL_MISSING -> R.string.models_reason_model_missing
    CoverageReason.PHONE_ONLY_NOT_INDEXER -> R.string.models_reason_phone_only
    CoverageReason.UNSUPPORTED -> R.string.models_reason_unsupported
    CoverageReason.TEXT_READING_OFF -> R.string.models_reason_text_reading_off
  },
  GalleryNumbers.format(count),
)

// --- Chi analizza --------------------------------------------------------------------------------------------------------------------------------------

/**
 * Le due aree come piastrelle (I2: «nessun dispositivo analizza quest'area» **solo qui**): l'area comune e la tua area privata, ognuna con il suo segno
 * (✓ un dispositivo la analizza, ! nessuno). L'interruttore che le copre e' subito sotto, «questo dispositivo analizza» (il ruolo e' uno solo: copre tutte
 * e due le aree del tuo account).
 */
@Composable
private fun AreaTiles(uncovered: List<AreaKind>) {
  Row(modifier = Modifier.fillMaxWidth().padding(bottom = SearchDimens.TightGap), horizontalArrangement = Arrangement.spacedBy(SearchDimens.TileGap)) {
    AreaTile(AreaKind.COMMON_AREA, AreaKind.COMMON_AREA in uncovered, Modifier.weight(1f))
    AreaTile(AreaKind.MY_PRIVATE_AREA, AreaKind.MY_PRIVATE_AREA in uncovered, Modifier.weight(1f))
  }
}

@Composable
private fun AreaTile(area: AreaKind, uncovered: Boolean, modifier: Modifier) {
  val common = area == AreaKind.COMMON_AREA
  val description = stringResource(
    when {
      common && uncovered -> R.string.models_area_common
      common -> R.string.models_area_common_covered
      uncovered -> R.string.models_area_private
      else -> R.string.models_area_private_covered
    },
  )
  FluidListGroup(modifier = modifier) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(SearchDimens.TilePadding).clearAndSetSemantics { contentDescription = description },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      GalleryIconTile(
        icon = if (common) Icons.Rounded.FamilyRestroom else Icons.Rounded.Lock,
        family = if (common) GalleryIconFamily.People else GalleryIconFamily.Security,
        size = GalleryIconTileDefaults.Medium,
        status = if (uncovered) GalleryIconStatus.Attention else GalleryIconStatus.Done,
        statusDescription = null,
      )
      Spacer(Modifier.width(SearchDimens.TileGap))
      Text(
        text = stringResource(if (common) R.string.models_area_common_short else R.string.models_area_private_short),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndexerBlock(state: SearchSettingsState, measuring: Boolean, handlers: SearchSettingsHandlers, probeFailed: Boolean, probeHot: Boolean, probeBusy: Boolean) {
  val indexer = state.indexer
  val resources = LocalResources.current
  val title = stringResource(R.string.models_indexer_switch)
  val detail = stringResource(R.string.models_indexer_detail)
  FluidListGroup {
    FluidListRow(
      title = title,
      subtitle = "",
      leading = {
        GalleryIconTile(
          icon = Icons.Rounded.AutoAwesome,
          family = GalleryIconFamily.Search,
          status = if (indexer.indexer) statusMarkOf(indexer.status) else null,
          statusDescription = null,
        )
      },
      leadingTile = false,
      disclosure = false,
      // Tre parole intere anche a testo grande (a 1,3x vanno su tre righe accanto all'interruttore, mai con i puntini).
      titleMaxLines = 3,
      // Che cosa vuol dire analizzare lo dice TalkBack qui e la «i» della copertura, in testa alla pagina.
      badge = { FluidSwitch(checked = indexer.indexer, onCheckedChange = null) },
      onClick = { handlers.onSetIndexer(!indexer.indexer) },
      modifier = Modifier.semantics(mergeDescendants = true) {
        role = Role.Switch
        toggleableState = ToggleableState(indexer.indexer)
        contentDescription = "$title. $detail"
      },
    )
    if (indexer.indexer) {
      FluidListDivider()
      StatusRow(indexer.status, resources)
      // Finita l'analisi, «Fatto» basta: la riga della misura direbbe la stessa cosa («Niente da analizzare»). Lo stato si dice una volta.
      val allDone = indexer.toAnalyze <= 0 && !measuring && !probeFailed && !probeHot && !probeBusy &&
        (indexer.status is AiStatus.Idle || indexer.status is AiStatus.BufferOnlyDone)
      if (!allDone) {
        FluidListDivider()
        MeasureRow(indexer, measuring, probeFailed, probeHot, probeBusy, resources)
      }
    }
  }
  if (state.offerRemoveIndexModel) RemoveIndexModelSheet(handlers)
  if (indexer.indexer) {
    val problem = (indexer.status as? AiStatus.Problem)?.code
    // K4: i tasti vanno a capo interi se non entrano affiancati ("Libera spazio" a testo grande si spezzava in "Libera spazi/o").
    FlowRow(
      modifier = Modifier.padding(top = SearchDimens.TightGap, start = GalleryDimens.Gutter, end = GalleryDimens.Gutter),
      horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
      verticalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
    ) {
      if (probeFailed && !probeHot && !probeBusy && state.models.models.any { it.id == dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.INDEX_440M.id && it.state is dev.pampa.mycloudgallery.core.models.contract.ModelState.Ready }) FluidButton(text = stringResource(R.string.models_probe_retry), onClick = handlers::onRetryProbe, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large)
      FluidButton(
        text = stringResource(R.string.models_index_now),
        onClick = handlers::onIndexNow,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        leading = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
      )
      if (problem != null) {
        for (action in SearchCodes.actions(problem)) {
          FluidButton(text = stringResource(action.label), onClick = { runCodeAction(action, handlers) }, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large)
        }
      }
    }
  }
}

/** Il segno dello stato dell'analisi sulla piastrella dell'interruttore. */
private fun statusMarkOf(status: AiStatus): GalleryIconStatus = when (status) {
  is AiStatus.Running -> GalleryIconStatus.Running
  is AiStatus.Idle, is AiStatus.BufferOnlyDone -> GalleryIconStatus.Done
  is AiStatus.Waiting -> GalleryIconStatus.Paused
  AiStatus.ModelMissing -> GalleryIconStatus.Attention
  is AiStatus.Problem -> GalleryIconStatus.Problem
  AiStatus.Off -> GalleryIconStatus.Stopped
}

/**
 * Lo stato dell'analisi in una riga: in corso un **anello** con fatti su quanti (e i minuti che mancano), fermo o finito un segno con due parole, un
 * problema il suo messaggio corto (i tasti sono sotto). TalkBack dice la frase intera.
 */
@Composable
private fun StatusRow(status: AiStatus, resources: Resources) {
  val sentence = statusText(resources, status)
  val searchHue = rememberGalleryIconColors(GalleryIconFamily.Search).top
  val (title, meta) = when (status) {
    is AiStatus.Running -> stringResource(R.string.models_count_of, GalleryNumbers.format(status.done), GalleryNumbers.format(status.total)) to
      status.etaMinutes?.let { stringResource(R.string.models_eta_short, it) }
    is AiStatus.Idle -> stringResource(R.string.models_status_done_short) to null
    is AiStatus.BufferOnlyDone -> stringResource(R.string.models_status_buffer_short, GalleryNumbers.format(status.items)) to null
    is AiStatus.Waiting -> stringResource(waitLabel(status.reason)) to null
    AiStatus.ModelMissing -> stringResource(R.string.models_status_model_missing_short) to null
    AiStatus.Off -> stringResource(R.string.models_status_off_short) to null
    is AiStatus.Problem -> SearchCodes.message(resources, status.code) to null
  }
  FluidListRow(
    title = title,
    subtitle = "",
    meta = meta,
    leading = {
      if (status is AiStatus.Running) {
        SearchRing(
          fraction = if (status.total > 0) status.done.toFloat() / status.total else 0f,
          color = searchHue,
          size = SearchDimens.RowRing,
          stroke = SearchDimens.RowRingStroke,
        )
      } else {
        GalleryIconTile(icon = statusGlyph(status), family = statusFamily(status), status = statusMarkOf(status), statusDescription = null)
      }
    },
    leadingTile = false,
    disclosure = false,
    titleMaxLines = 3,
    modifier = Modifier.clearAndSetSemantics { contentDescription = sentence },
  )
}

private fun statusGlyph(status: AiStatus): ImageVector = when (status) {
  is AiStatus.Waiting -> when (status.reason) {
    AiWaitReason.HOT -> Icons.Rounded.Thermostat
    AiWaitReason.NEXT_STAGE -> Icons.Rounded.HourglassTop
    else -> Icons.Rounded.HourglassTop
  }
  AiStatus.ModelMissing -> Icons.Rounded.Download
  is AiStatus.Problem -> Icons.Rounded.Warning
  else -> Icons.Rounded.AutoAwesome
}

private fun statusFamily(status: AiStatus): GalleryIconFamily = when (status) {
  is AiStatus.Problem -> GalleryIconFamily.Danger
  is AiStatus.Waiting -> GalleryIconFamily.System
  else -> GalleryIconFamily.Search
}

private fun waitLabel(reason: AiWaitReason): Int = when (reason) {
  AiWaitReason.RULES -> R.string.models_wait_rules_short
  AiWaitReason.NO_NETWORK -> R.string.models_wait_network_short
  AiWaitReason.NAS_UNREACHABLE -> R.string.models_wait_nas_short
  AiWaitReason.STORAGE_LOW -> R.string.models_wait_storage_short
  AiWaitReason.HOT -> R.string.models_wait_hot_short
  AiWaitReason.PREPARATION -> R.string.models_wait_preparation_short
  AiWaitReason.NEXT_STAGE -> R.string.models_wait_next_stage_short
}

/**
 * La misura del dispositivo come segno (I2): in corso, caldo, occupato, non riuscita, o fatta (le foto al secondo) con la stima delle notti accanto a una
 * luna. TalkBack dice le frasi intere di prima.
 */
@Composable
private fun MeasureRow(indexer: IndexerState, measuring: Boolean, probeFailed: Boolean, probeHot: Boolean, probeBusy: Boolean, resources: Resources) {
  val sentence = when {
    probeHot -> resources.getString(R.string.models_probe_hot)
    probeBusy -> resources.getString(R.string.models_probe_busy)
    probeFailed -> resources.getString(R.string.models_probe_failed)
    else -> listOf(estimateText(resources, indexer.toAnalyze, indexer.nights, measuring), probeText(resources, indexer, measuring)).filter { it.isNotEmpty() }.joinToString(". ")
  }
  val probe = indexer.probe
  val (title, subtitle) = when {
    measuring -> stringResource(R.string.models_measuring_short) to ""
    probeHot -> stringResource(R.string.models_probe_hot_short) to ""
    probeBusy -> stringResource(R.string.models_probe_busy_short) to ""
    probeFailed -> stringResource(R.string.models_probe_failed_short) to ""
    indexer.toAnalyze <= 0 -> stringResource(R.string.models_nothing_short) to ""
    indexer.nights != null -> pluralStringResource(R.plurals.models_nights_short, indexer.nights, indexer.nights) to
      stringResource(R.string.models_to_analyze_short, GalleryNumbers.format(indexer.toAnalyze))
    else -> stringResource(R.string.models_to_analyze_short, GalleryNumbers.format(indexer.toAnalyze)) to ""
  }
  val speed = if (!measuring && !probeHot && !probeBusy && !probeFailed && probe != null) {
    stringResource(
      R.string.models_probe_short,
      "%.1f".format(java.util.Locale.ITALIAN, probe.imagesPerSecond),
      stringResource(if (probe.backend == dev.pampa.mycloudgallery.core.ai.contract.AiBackend.GPU) R.string.models_backend_gpu_short else R.string.models_backend_cpu_short),
    )
  } else {
    null
  }
  val (glyph, mark) = when {
    measuring -> Icons.Rounded.Speed to GalleryIconStatus.Running
    probeHot -> Icons.Rounded.Thermostat to GalleryIconStatus.Paused
    probeBusy -> Icons.Rounded.HourglassTop to GalleryIconStatus.Paused
    probeFailed -> Icons.Rounded.Speed to GalleryIconStatus.Problem
    indexer.toAnalyze <= 0 -> Icons.Rounded.CheckCircle to GalleryIconStatus.Done
    else -> Icons.Rounded.Bedtime to null
  }
  FluidListRow(
    title = title,
    subtitle = subtitle,
    meta = speed,
    leading = {
      if (measuring) {
        FluidSpinner(size = GalleryIconTileDefaults.Row)
      } else {
        GalleryIconTile(icon = glyph, family = GalleryIconFamily.System, status = mark, statusDescription = null)
      }
    },
    leadingTile = false,
    disclosure = false,
    titleMaxLines = 2,
    modifier = Modifier.clearAndSetSemantics { contentDescription = sentence },
  )
}

/** IX-8: spento il ruolo, "Togliere anche il modello di analisi?" (D15): 370 MB che il dispositivo non usa piu'. */
@Composable
private fun RemoveIndexModelSheet(handlers: SearchSettingsHandlers) {
  val title = stringResource(R.string.models_index_off_title)
  GalleryConfirmSheet(visible = true, onDismissRequest = handlers::onKeepIndexModel, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.Warning,
      tone = GallerySheetTone.Caution,
      title = title,
      message = stringResource(R.string.models_index_off_message),
      actions = {
        FluidButton(
          text = stringResource(R.string.models_remove_confirm),
          onClick = { handlers.onRemoveModel(dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.INDEX_440M.id) },
          style = FluidButtonStyle.Destructive,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
        FluidButton(text = stringResource(R.string.models_index_off_keep), onClick = handlers::onKeepIndexModel, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
      },
    )
  }
}

internal fun runCodeAction(action: CodeAction, handlers: SearchSettingsHandlers) = when (action) {
  CodeAction.DIAGNOSTICS -> handlers.onOpenDiagnostics()
  CodeAction.FREE_SPACE -> handlers.onFreeSpace()
  CodeAction.ENABLE_INDEXER -> handlers.onSetIndexer(true)
  CodeAction.DOWNLOAD_NOW, CodeAction.RETRY -> handlers.onIndexNow()
}

/** Lo stato del calcolo in una frase (TalkBack): solo numeri e codici, mai nomi (D40). */
internal fun statusText(resources: Resources, status: AiStatus): String = when (status) {
  AiStatus.Off -> resources.getString(R.string.models_status_off)
  AiStatus.ModelMissing -> resources.getString(R.string.models_status_model_missing)
  is AiStatus.Idle -> resources.getString(R.string.models_status_idle)
  is AiStatus.Running -> status.etaMinutes?.let {
    resources.getString(R.string.models_status_running_eta, GalleryNumbers.format(status.done), GalleryNumbers.format(status.total), it)
  } ?: resources.getString(R.string.models_status_running, GalleryNumbers.format(status.done), GalleryNumbers.format(status.total))
  is AiStatus.Waiting -> resources.getString(
    when (status.reason) {
      AiWaitReason.RULES -> R.string.models_status_waiting_rules
      AiWaitReason.NO_NETWORK -> R.string.models_status_waiting_network
      AiWaitReason.NAS_UNREACHABLE -> R.string.models_status_waiting_nas
      AiWaitReason.STORAGE_LOW -> R.string.models_status_waiting_storage
      AiWaitReason.HOT -> R.string.models_status_waiting_hot
      AiWaitReason.PREPARATION -> R.string.models_status_waiting_preparation
      AiWaitReason.NEXT_STAGE -> R.string.models_status_next_stage
    },
  )
  is AiStatus.BufferOnlyDone -> resources.getString(R.string.models_status_buffer_only, GalleryNumbers.format(status.items))
  is AiStatus.Problem -> SearchCodes.message(resources, status.code)
}

/** "Analizzare N media richiede circa K notti" (D15): dalla misura e dal lavoro vero che resta. */
internal fun estimateText(resources: Resources, toAnalyze: Int, nights: Int?, measuring: Boolean = false): String = when {
  measuring -> resources.getString(R.string.models_indexer_measuring)
  toAnalyze <= 0 -> resources.getString(R.string.models_indexer_nothing)
  nights != null -> resources.getQuantityString(R.plurals.models_indexer_estimate, nights, GalleryNumbers.format(toAnalyze), nights)
  else -> resources.getString(R.string.models_indexer_no_estimate, GalleryNumbers.format(toAnalyze))
}

private fun probeText(resources: Resources, indexer: IndexerState, measuring: Boolean): String {
  val probe = indexer.probe
  return when {
    measuring -> ""
    probe == null -> ""
    else -> resources.getString(
      R.string.models_indexer_probe,
      "%.1f".format(java.util.Locale.ITALIAN, probe.imagesPerSecond),
      resources.getString(if (probe.backend == dev.pampa.mycloudgallery.core.ai.contract.AiBackend.GPU) R.string.models_backend_gpu else R.string.models_backend_cpu),
    )
  }
}

// --- Le ricerche recenti -------------------------------------------------------------------------------------------------------------------------------

@Composable
private fun HistoryBlock(count: Int, handlers: SearchSettingsHandlers) {
  val resources = LocalResources.current
  val title = stringResource(R.string.models_history_clear)
  val detail = if (count == 0) stringResource(R.string.models_history_none) else resources.getQuantityString(R.plurals.models_history_detail, count, GalleryNumbers.format(count))
  FluidListGroup {
    FluidListRow(
      title = title,
      subtitle = "",
      meta = GalleryNumbers.format(count),
      leading = { GalleryIconTile(icon = Icons.Rounded.History, family = GalleryIconFamily.Search) },
      leadingTile = false,
      disclosure = false,
      titleMaxLines = 2,
      onClick = if (count > 0) handlers::onClearHistory else null,
      modifier = Modifier.semantics { contentDescription = "$title. $detail" },
    )
  }
}

private const val PERCENT = 100f
private const val MIB = 1_048_576L
private const val HALF_MIB = 524_288L
