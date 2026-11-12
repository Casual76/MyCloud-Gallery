package dev.pampa.mycloudgallery.feature.search.models

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoDetail
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTileDefaults
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryStatusColors
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryRingPercent
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
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
//    ricerca) e l'interruttore «questo dispositivo analizza»; che cosa fa adesso questo telefono come un segno solo (in corso, fatto, in pausa, in attesa)
//    con due parole o il tempo che manca, la misura del dispositivo come segno; un tasto solo, le altre azioni dietro il tocco sulla riga dello stato;
//  - la lettura del testo come una riga con il suo segno di stato e un tasto solo; le altre scelte dietro il tocco sulla riga;
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
 * La copertura in testa alla pagina: l'anello della galleria ([GalleryProgressRing], misura di testata come quella del Backup) con la percentuale dentro;
 * sotto, a tutta larghezza, quante foto la ricerca conosce (grande) su quante (piccolo), cosi' i numeri non vanno mai a capo; poi un segno con un numero
 * per ogni motivo di cio' che manca. Le frasi (la copertura per intero, che cosa vuol dire analizzare, «niente esce da qui») stanno dietro la «i»
 * della galleria nell'angolo, che le apre **sotto, nella pagina** ([GalleryInfoDetail]): una spiegazione sola per la pagina. TalkBack sente la copertura
 * e «niente esce da qui» dall'anello, cio' che vuol dire analizzare dall'interruttore.
 */
@Composable
private fun CoverageHero(coverage: SearchCoverage, explainIndexer: Boolean) {
  val resources = LocalResources.current
  var explaining by remember { mutableStateOf(false) }
  val searchHue = galleryRingColor(GalleryIconFamily.Search)
  val doneHue = rememberGalleryStatusColors(GalleryIconStatus.Done).top
  val sentence = coverageText(resources, coverage)
  val subtitle = stringResource(R.string.models_subtitle)
  val note = if (coverage is SearchCoverage.Partial && CoverageReason.TEXT_READING_OFF in coverage.reasons) "\n\n" + stringResource(R.string.models_reading_coverage_note) else ""
  val indexer = if (explainIndexer) "\n\n" + stringResource(R.string.models_indexer_detail) else ""
  val (fraction, hue) = when (coverage) {
    SearchCoverage.Complete -> 1f to doneHue
    is SearchCoverage.Partial -> coverage.fraction to searchHue
    is SearchCoverage.Updating -> coverage.percent / PERCENT to searchHue
    is SearchCoverage.ModelMissing -> 0f to searchHue
  }
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
  FluidListGroup {
    Box(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth().padding(SearchDimens.HeroPadding), horizontalAlignment = Alignment.CenterHorizontally) {
        GalleryProgressRing(
          progress = fraction,
          color = hue,
          strokeWidth = GalleryProgressRingDefaults.stroke(GalleryProgressRingDefaults.Hero),
          animate = true,
          modifier = Modifier.size(GalleryProgressRingDefaults.Hero).clearAndSetSemantics { contentDescription = "$sentence $subtitle" },
        ) {
          when (coverage) {
            SearchCoverage.Complete -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = doneHue, modifier = Modifier.size(SearchDimens.HeroGlyph))
            is SearchCoverage.ModelMissing -> Icon(Icons.Rounded.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(SearchDimens.HeroGlyph))
            else -> GalleryRingPercent(fraction, MaterialTheme.typography.headlineMedium)
          }
        }
        Spacer(Modifier.height(SearchDimens.TightGap))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics { }) {
          if (glyph != null) {
            Icon(glyph, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(SearchDimens.InlineGlyph))
            Spacer(Modifier.width(SearchDimens.TightGap))
          }
          Text(text = headline, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
          if (detail != null) {
            Spacer(Modifier.width(SearchDimens.TightGap))
            Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
          }
        }
        GalleryInfoDetail(
          text = sentence + note + indexer + "\n\n" + subtitle,
          visible = explaining,
          modifier = Modifier.fillMaxWidth().padding(top = SearchDimens.BlockGap),
        )
      }
      GalleryInfoButton(
        label = stringResource(R.string.models_coverage_info),
        open = explaining,
        onToggle = { explaining = !explaining },
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

/**
 * Chi analizza (I2; giro 1: CU-19, CU-25 e i segni di stato comuni). L'interruttore con il segno dell'analisi (il chip: questo dispositivo calcola; le
 * scintille sono i Ricordi). Sotto, **che cosa fa adesso questo telefono** come un segno solo (in corso, fatto, in pausa con il suo perche', in attesa con
 * la clessidra, un problema) con due parole o il tempo che manca: il numero dell'avanzamento non si ripete, in testa c'e' gia' quanto la ricerca conosce.
 * Poi la misura come segno. **Un tasto solo**, l'azione che serve adesso (il rimedio di un problema, «Riprova» la misura, o «Indicizza ora»); le altre,
 * con le frasi intere, dietro il tocco sulla riga dello stato.
 */
@Composable
private fun IndexerBlock(state: SearchSettingsState, measuring: Boolean, handlers: SearchSettingsHandlers, probeFailed: Boolean, probeHot: Boolean, probeBusy: Boolean) {
  val indexer = state.indexer
  val resources = LocalResources.current
  val title = stringResource(R.string.models_indexer_switch)
  val detail = stringResource(R.string.models_indexer_detail)
  var details by remember { mutableStateOf(false) }
  val problem = (indexer.status as? AiStatus.Problem)?.code
  val probeRetry = probeFailed && !probeHot && !probeBusy &&
    state.models.models.any { it.id == dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.INDEX_440M.id && it.state is dev.pampa.mycloudgallery.core.models.contract.ModelState.Ready }
  val actions = buildList {
    problem?.let { code -> SearchCodes.actions(code).forEach { add(IndexerAction.Code(it)) } }
    if (probeRetry) add(IndexerAction.RetryProbe)
    add(IndexerAction.IndexNow)
  }
  // Finita l'analisi, «Fatto» basta: la riga della misura direbbe la stessa cosa («Niente da analizzare»). Lo stato si dice una volta.
  val allDone = indexer.toAnalyze <= 0 && !measuring && !probeFailed && !probeHot && !probeBusy &&
    (indexer.status is AiStatus.Idle || indexer.status is AiStatus.BufferOnlyDone)
  FluidListGroup {
    FluidListRow(
      title = title,
      subtitle = "",
      // Il segno dello stato e' uno solo, sulla riga dello stato qui sotto: la piastrella dell'interruttore non lo ripete.
      leading = { GalleryIconTile(icon = Icons.Rounded.Memory, family = GalleryIconFamily.Search) },
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
      StatusRow(indexer.status, resources, onOpen = { details = true })
      if (!allDone) {
        FluidListDivider()
        MeasureRow(indexer, measuring, probeFailed, probeHot, probeBusy, resources)
      }
    }
  }
  if (state.offerRemoveIndexModel) RemoveIndexModelSheet(handlers)
  if (indexer.indexer) {
    val primary = actions.first()
    Row(modifier = Modifier.padding(top = SearchDimens.TightGap, start = GalleryDimens.Gutter, end = GalleryDimens.Gutter)) {
      ActionButton(primary.label(), primary.icon(), onClick = { primary.run(handlers) })
    }
  }
  if (details) {
    val sentences = listOf(
      statusText(resources, indexer.status),
      when {
        probeHot -> resources.getString(R.string.models_probe_hot)
        probeBusy -> resources.getString(R.string.models_probe_busy)
        probeFailed -> resources.getString(R.string.models_probe_failed)
        else -> listOf(estimateText(resources, indexer.toAnalyze, indexer.nights, measuring), probeText(resources, indexer, measuring)).filter { it.isNotEmpty() }.joinToString(". ")
      },
    ).filter { it.isNotEmpty() }.joinToString("\n\n")
    ChoiceSheet(
      title = stringResource(R.string.models_section_indexer),
      message = sentences,
      onDismiss = { details = false },
      icon = Icons.Rounded.Memory,
    ) {
      // Tutte le azioni, la principale compresa: qui si leggono con il loro perche'.
      actions.forEach { action -> ActionButton(action.label(), action.icon(), onClick = { details = false; action.run(handlers) }, fill = true) }
    }
  }
}

/** Le azioni dell'analisi: il rimedio di un codice, «Riprova» la misura, «Indicizza ora». */
private sealed interface IndexerAction {
  data class Code(val action: CodeAction) : IndexerAction

  data object RetryProbe : IndexerAction

  data object IndexNow : IndexerAction
}

@Composable
private fun IndexerAction.label(): String = when (this) {
  is IndexerAction.Code -> stringResource(action.label)
  IndexerAction.RetryProbe -> stringResource(R.string.models_probe_retry)
  IndexerAction.IndexNow -> stringResource(R.string.models_index_now)
}

private fun IndexerAction.icon(): ImageVector = when (this) {
  is IndexerAction.Code -> codeActionIcon(action)
  IndexerAction.RetryProbe -> Icons.Rounded.Refresh
  IndexerAction.IndexNow -> Icons.Rounded.PlayArrow
}

private fun IndexerAction.run(handlers: SearchSettingsHandlers) = when (this) {
  is IndexerAction.Code -> runCodeAction(action, handlers)
  IndexerAction.RetryProbe -> handlers.onRetryProbe()
  IndexerAction.IndexNow -> handlers.onIndexNow()
}

/** L'icona del tasto di un'azione dei codici (CU-19: ogni tasto ha la sua). */
internal fun codeActionIcon(action: CodeAction): ImageVector = when (action) {
  CodeAction.DIAGNOSTICS -> Icons.Rounded.MonitorHeart
  CodeAction.FREE_SPACE -> Icons.Rounded.CleaningServices
  CodeAction.ENABLE_INDEXER -> Icons.Rounded.PowerSettingsNew
  CodeAction.DOWNLOAD_NOW -> Icons.Rounded.Download
  CodeAction.RETRY -> Icons.Rounded.Refresh
}

/**
 * Il segno di uno stato dell'analisi (le regole comuni del giro 1): **in corso** = il chip con `Running`; **fatto** = `Done`; **in pausa** per una
 * condizione (le regole, la rete, il NAS, lo spazio, il caldo) = il segno del perche' con `Paused`; **in attesa** che le foto siano preparate o della
 * prossima tappa = la clessidra, e solo quella; il modello che manca = la freccia con `Attention`; spento = `Stopped`; un problema = `Problem`.
 */
internal fun statusSign(status: AiStatus): Triple<ImageVector, GalleryIconFamily, GalleryIconStatus?> = when (status) {
  is AiStatus.Running -> Triple(Icons.Rounded.Memory, GalleryIconFamily.Search, GalleryIconStatus.Running)
  is AiStatus.Idle, is AiStatus.BufferOnlyDone -> Triple(Icons.Rounded.Memory, GalleryIconFamily.Search, GalleryIconStatus.Done)
  is AiStatus.Waiting -> when (status.reason) {
    AiWaitReason.PREPARATION, AiWaitReason.NEXT_STAGE -> Triple(Icons.Rounded.HourglassTop, GalleryIconFamily.System, null)
    AiWaitReason.RULES -> Triple(Icons.AutoMirrored.Rounded.Rule, GalleryIconFamily.Backup, GalleryIconStatus.Paused)
    AiWaitReason.NO_NETWORK -> Triple(Icons.Rounded.WifiOff, GalleryIconFamily.Network, GalleryIconStatus.Paused)
    AiWaitReason.NAS_UNREACHABLE -> Triple(Icons.Rounded.CloudOff, GalleryIconFamily.Network, GalleryIconStatus.Paused)
    AiWaitReason.STORAGE_LOW -> Triple(Icons.Rounded.SdStorage, GalleryIconFamily.System, GalleryIconStatus.Paused)
    AiWaitReason.HOT -> Triple(Icons.Rounded.Thermostat, GalleryIconFamily.System, GalleryIconStatus.Paused)
  }
  AiStatus.ModelMissing -> Triple(Icons.Rounded.Download, GalleryIconFamily.Search, GalleryIconStatus.Attention)
  AiStatus.Off -> Triple(Icons.Rounded.Memory, GalleryIconFamily.Search, GalleryIconStatus.Stopped)
  is AiStatus.Problem -> Triple(Icons.Rounded.Warning, GalleryIconFamily.Danger, GalleryIconStatus.Problem)
}

/**
 * Che cosa fa adesso questo telefono, in una riga: il segno dello stato e due parole (in corso: il tempo che manca). Il tocco apre le frasi intere e le
 * azioni ([IndexerBlock]). TalkBack dice la frase intera.
 */
@Composable
private fun StatusRow(status: AiStatus, resources: Resources, onOpen: () -> Unit) {
  val sentence = statusText(resources, status)
  val openLabel = stringResource(R.string.models_details)
  val (glyph, family, mark) = statusSign(status)
  val title = when (status) {
    is AiStatus.Running -> status.etaMinutes?.let { stringResource(R.string.models_eta_short, it) } ?: stringResource(R.string.models_status_running_short)
    is AiStatus.Idle -> stringResource(R.string.models_status_done_short)
    is AiStatus.BufferOnlyDone -> stringResource(R.string.models_status_buffer_short, GalleryNumbers.format(status.items))
    is AiStatus.Waiting -> stringResource(waitLabel(status.reason))
    AiStatus.ModelMissing -> stringResource(R.string.models_status_model_missing_short)
    AiStatus.Off -> stringResource(R.string.models_status_off_short)
    is AiStatus.Problem -> SearchCodes.message(resources, status.code)
  }
  FluidListRow(
    title = title,
    subtitle = "",
    leading = { GalleryIconTile(icon = glyph, family = family, status = mark, statusDescription = null) },
    leadingTile = false,
    disclosure = true,
    titleMaxLines = 3,
    onClick = onOpen,
    modifier = Modifier.clearAndSetSemantics {
      contentDescription = sentence
      role = Role.Button
      onClick(label = openLabel) { onOpen(); true }
    },
  )
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
 * La misura del dispositivo come segno (I2): in corso (`Running`), caldo (in pausa), occupata (la clessidra), non riuscita, o fatta (le foto al secondo)
 * con la stima delle notti accanto a una luna. Niente sigle (CS-15): il processore non si nomina. TalkBack dice le frasi intere di prima.
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
    stringResource(R.string.models_probe_short, "%.1f".format(java.util.Locale.ITALIAN, probe.imagesPerSecond))
  } else {
    null
  }
  val (glyph, mark) = when {
    measuring -> Icons.Rounded.Speed to GalleryIconStatus.Running
    probeHot -> Icons.Rounded.Thermostat to GalleryIconStatus.Paused
    probeBusy -> Icons.Rounded.HourglassTop to null
    probeFailed -> Icons.Rounded.Speed to GalleryIconStatus.Problem
    indexer.toAnalyze <= 0 -> Icons.Rounded.CheckCircle to null
    else -> Icons.Rounded.Bedtime to null
  }
  FluidListRow(
    title = title,
    subtitle = subtitle,
    meta = speed,
    leading = { GalleryIconTile(icon = glyph, family = GalleryIconFamily.System, status = mark, statusDescription = null) },
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
      // CU-19: senza ricerche recenti nessun numero (un «0» accanto a un tasto spento non dice niente).
      meta = if (count > 0) GalleryNumbers.format(count) else null,
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
