package dev.pampa.mycloudgallery.feature.search.models

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidPressGrip
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.core.designsystem.icon.galleryStatusMarkSize
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryStatusColors
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelDiag
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelRole
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelWait
import dev.pampa.mycloudgallery.feature.search.R
import dev.pampa.mycloudgallery.feature.search.contract.ModelRow
import dev.pampa.mycloudgallery.feature.search.contract.ModelsState
import dev.pampa.mycloudgallery.feature.search.sizeText
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens

// I MODELLI (Fase 6, P8, §1.5 `ModelsState`, D20; I2 della fase I). Ogni modello e' **un anello** con il suo segno: pieno e con la spunta quando e' pronto,
// che si riempie mentre arriva, vuoto con la freccia quando manca, con il segno d'attenzione o di problema quando serve qualcosa. Sotto, il nome corto e la
// misura. Il tocco apre il dettaglio (lo stato per intero, da dove viene, il motivo di un problema) con le sue azioni: "Scarica ora", "Scarica con i dati
// mobili", "Riprova", "Diagnostica", "Libera spazio", "Rimuovi" (che dice la conseguenza prima di farlo, D20).

/** Il codice stabile di un motivo di fallimento di un modello (§2.6): la pagina ne ricava messaggio e azioni dal catalogo. */
fun failureCode(code: ModelFailCode): DiagCode = when (code) {
  ModelFailCode.FETCH_FAILED, ModelFailCode.NETWORK_REFUSED -> ModelDiag.AI_MODEL_FETCH_FAILED
  ModelFailCode.MISMATCH, ModelFailCode.VERIFY_FAILED -> ModelDiag.AI_MODEL_MISMATCH
  ModelFailCode.NO_SPACE -> ModelDiag.AI_MODEL_NO_SPACE
}

internal fun modelTone(state: ModelState): FluidTone = when (state) {
  ModelState.Absent -> FluidTone.Neutral
  is ModelState.Queued, is ModelState.Fetching, ModelState.Verifying -> FluidTone.Info
  is ModelState.Ready -> FluidTone.Success
  is ModelState.Failed, is ModelState.NeedsConfirmation -> FluidTone.Warning
}

/** Il segno di stato di un modello sull'anello (null = nessun segno: in arrivo, l'anello stesso dice quanto). */
internal fun modelMark(state: ModelState): GalleryIconStatus? = when (state) {
  is ModelState.Ready -> GalleryIconStatus.Done
  // In arrivo: l'anello stesso dice quanto; in verifica l'arco gira (un lavoro senza misura).
  is ModelState.Fetching, ModelState.Verifying -> null
  is ModelState.Queued -> GalleryIconStatus.Paused
  ModelState.Absent -> GalleryIconStatus.Stopped
  is ModelState.NeedsConfirmation -> GalleryIconStatus.Attention
  is ModelState.Failed -> GalleryIconStatus.Problem
}

private fun glyphOf(role: ModelRole?): ImageVector = when (role) {
  ModelRole.QUERY_TEXT -> Icons.Rounded.Search
  // CU-25: il chip (questo dispositivo calcola), come l'interruttore dell'analisi; le scintille sono i Ricordi.
  ModelRole.INDEX_MULTIMODAL -> Icons.Rounded.Memory
  ModelRole.CUTOUT -> Icons.Rounded.ContentCut
  // Fase 8 (P0): i modelli dei volti stanno nell'APK e non compaiono in questa pagina (si gestiscono da Impostazioni -> Persone, P7);
  // i rami ci sono perche' il `when` resti completo.
  ModelRole.FACE_DETECT, ModelRole.FACE_EMBED -> Icons.Rounded.Face
  null -> Icons.Rounded.Warning
}

@Composable
private fun nameOf(role: ModelRole?): String = stringResource(
  when (role) {
    ModelRole.QUERY_TEXT -> R.string.models_model_query
    ModelRole.INDEX_MULTIMODAL, null -> R.string.models_model_index
    ModelRole.CUTOUT -> R.string.models_model_cutout
    ModelRole.FACE_DETECT, ModelRole.FACE_EMBED -> R.string.models_model_faces
  },
)

@Composable
private fun shortNameOf(role: ModelRole?): String = stringResource(
  when (role) {
    ModelRole.QUERY_TEXT -> R.string.models_model_query_short
    ModelRole.INDEX_MULTIMODAL, null -> R.string.models_model_index_short
    ModelRole.CUTOUT -> R.string.models_model_cutout_short
    ModelRole.FACE_DETECT, ModelRole.FACE_EMBED -> R.string.models_model_faces
  },
)

private fun sourceText(resources: Resources, kind: ModelSourceKind?): String? = when (kind) {
  ModelSourceKind.NAS -> resources.getString(R.string.models_source_nas)
  ModelSourceKind.HTTPS -> resources.getString(R.string.models_source_https)
  ModelSourceKind.PC_FOLDER -> resources.getString(R.string.models_source_pc)
  ModelSourceKind.BUNDLED -> resources.getString(R.string.models_source_bundled)
  null -> null
}

@Composable
private fun stateLabel(state: ModelState): String = stringResource(
  when (state) {
    ModelState.Absent -> R.string.models_state_absent
    // MO-8: un rifiuto della rete e' "in coda" con il suo motivo, non "non riuscito".
    is ModelState.Queued -> when (state.waiting) {
      ModelWait.NETWORK -> R.string.models_state_wait_network
      ModelWait.WIFI -> R.string.models_state_wait_wifi
      ModelWait.ROAMING -> R.string.models_state_wait_roaming
      null -> R.string.models_state_queued
    }
    // MO-2: sui dati mobili serve la conferma con la dimensione.
    is ModelState.NeedsConfirmation -> R.string.models_state_confirm
    is ModelState.Fetching -> R.string.models_state_fetching_short
    ModelState.Verifying -> R.string.models_state_verifying
    is ModelState.Ready -> R.string.models_state_ready
    is ModelState.Failed -> R.string.models_state_failed
  },
)

/** La sezione "Modelli": gli anelli dei modelli affiancati e, sotto, lo spazio dell'indice con il suo segno. */
fun LazyListScope.modelsSection(models: ModelsState, handlers: SearchSettingsHandlers) {
  item(key = "models-header") { GallerySectionHeader(title = stringResource(R.string.models_section_models)) }
  item(key = "models") { ModelTiles(models.models, handlers) }
  item(key = "models-space") { SpaceLine(models) }
}

@Composable
private fun ModelTiles(rows: List<ModelRow>, handlers: SearchSettingsHandlers) {
  var open by remember { mutableStateOf<ModelId?>(null) }
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SearchDimens.TileGap)) {
    rows.forEach { row -> ModelTile(row, onOpen = { open = row.id }, modifier = Modifier.weight(1f)) }
  }
  val shown = open?.let { id -> rows.firstOrNull { it.id == id } }
  if (shown != null) ModelSheet(shown, handlers, onDismiss = { open = null })
}

/**
 * Un modello: l'anello (pronto pieno, in arrivo che avanza, assente vuoto) con il glifo del suo ruolo e il segno di stato, il nome corto e la misura.
 * Un tasto: apre il dettaglio. TalkBack dice nome, stato, misura e sorgente.
 */
@Composable
private fun ModelTile(row: ModelRow, onOpen: () -> Unit, modifier: Modifier) {
  val spec = ModelCatalog.byId(row.id)
  val resources = LocalResources.current
  val state = row.state
  val name = nameOf(spec?.role)
  val label = stateLabel(state)
  val summary = listOfNotNull(name, label, sizeText(row.sizeBytes), sourceText(resources, row.source)).joinToString(" · ")
  val openLabel = stringResource(R.string.models_details)
  val searchHue = galleryRingColor(GalleryIconFamily.Search)
  val readyHue = rememberGalleryStatusColors(GalleryIconStatus.Done).top
  val (fraction, hue) = when (state) {
    is ModelState.Ready -> 1f to readyHue
    is ModelState.Fetching -> (if (state.total > 0) state.done.toFloat() / state.total else 0f) to searchHue
    else -> 0f to searchHue
  }
  FluidListGroup(modifier = modifier) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clearAndSetSemantics {
          contentDescription = summary
          role = Role.Button
          onClick(label = openLabel) { onOpen(); true }
        }
        .fluidPressable(onClick = onOpen, role = Role.Button)
        .padding(SearchDimens.TilePadding),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box {
        // L'anello della galleria alla misura delle schede; in verifica (senza misura) l'arco gira.
        GalleryProgressRing(
          progress = if (state == ModelState.Verifying) null else fraction,
          color = hue,
          strokeWidth = GalleryProgressRingDefaults.stroke(GalleryProgressRingDefaults.Card),
          animate = true,
          modifier = Modifier.size(GalleryProgressRingDefaults.Card),
        ) {
          Icon(glyphOf(spec?.role), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(SearchDimens.ModelGlyph))
        }
        val mark = modelMark(state)
        if (mark != null) {
          val side = galleryStatusMarkSize(GalleryProgressRingDefaults.Card)
          GalleryStatusMark(status = mark, size = side, contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd))
        }
      }
      Spacer(Modifier.height(SearchDimens.TightGap))
      Text(
        text = shortNameOf(spec?.role),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
      )
      Text(
        text = if (state is ModelState.Fetching) stringResource(R.string.models_percent, (fraction * PERCENT).toInt()) else sizeText(row.sizeBytes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/** Lo spazio dell'indice su questo dispositivo: un segno e il totale; la divisione (vettori, testo) per TalkBack. */
@Composable
private fun SpaceLine(models: ModelsState) {
  val sentence = stringResource(R.string.models_space, sizeText(models.vectorBytes), sizeText(models.textBytes))
  Row(
    modifier = Modifier.padding(top = SearchDimens.TightGap, start = SearchDimens.TilePadding).clearAndSetSemantics { contentDescription = sentence },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Icons.Rounded.SdStorage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(SearchDimens.InlineGlyph))
    Spacer(Modifier.width(SearchDimens.TightGap))
    Text(
      text = stringResource(R.string.models_index_space_short, sizeText(models.vectorBytes + models.textBytes)),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * Il dettaglio di un modello (dietro il tocco sull'anello): lo stato per intero con la misura e la sorgente, il motivo di un problema o la domanda dei
 * dati mobili, e le sue azioni. "Rimuovi" apre la sua conferma (D20).
 */
@Composable
private fun ModelSheet(row: ModelRow, handlers: SearchSettingsHandlers, onDismiss: () -> Unit) {
  val spec = ModelCatalog.byId(row.id)
  val resources = LocalResources.current
  val state = row.state
  var removing by remember { mutableStateOf(false) }
  val title = nameOf(spec?.role)
  val detail = listOfNotNull(stateLabel(state), sizeText(row.sizeBytes), sourceText(resources, row.source)).joinToString(" · ")
  val note = when (state) {
    is ModelState.Fetching -> stringResource(R.string.models_state_fetching, sizeText(state.done), sizeText(state.total))
    is ModelState.Failed -> SearchCodes.message(resources, failureCode(state.code), sizeText(row.sizeBytes))
    is ModelState.NeedsConfirmation -> stringResource(R.string.models_confirm_note, sizeText(state.bytes))
    else -> null
  }
  if (removing && spec != null) {
    RemoveSheet(spec.role, onConfirm = { removing = false; onDismiss(); handlers.onRemoveModel(row.id) }, onDismiss = { removing = false })
    return
  }
  GalleryConfirmSheet(visible = true, onDismissRequest = onDismiss, paneTitle = title) {
    GallerySheetScaffold(
      icon = glyphOf(spec?.role),
      tone = if (state is ModelState.Failed || state is ModelState.NeedsConfirmation) GallerySheetTone.Caution else GallerySheetTone.Brand,
      title = title,
      message = if (note != null) "$detail\n\n$note" else detail,
      actions = {
        fun act(block: () -> Unit): () -> Unit = { onDismiss(); block() }
        when (state) {
          ModelState.Absent -> SheetButton(R.string.models_download_now, Icons.Rounded.Download, act { handlers.onDownloadModel(row.id) })
          is ModelState.NeedsConfirmation -> SheetButton(R.string.models_confirm_action, Icons.Rounded.Download, act { handlers.onConfirmMobileDownload(row.id) })
          is ModelState.Failed -> for (action in SearchCodes.actions(failureCode(state.code))) {
            SheetButton(action.label, codeActionIcon(action), act {
              when (action) {
                CodeAction.DOWNLOAD_NOW, CodeAction.RETRY -> handlers.onDownloadModel(row.id)
                CodeAction.DIAGNOSTICS -> handlers.onOpenDiagnostics()
                CodeAction.FREE_SPACE -> handlers.onFreeSpace()
                CodeAction.ENABLE_INDEXER -> handlers.onSetIndexer(true)
              }
            })
          }
          is ModelState.Ready -> if (row.removable) SheetButton(R.string.models_remove, Icons.Rounded.Delete, { removing = true })
          else -> Unit
        }
        FluidButton(text = stringResource(R.string.models_close), onClick = onDismiss, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
      },
    )
  }
}

@Composable
private fun SheetButton(label: Int, icon: ImageVector, onClick: () -> Unit) {
  ActionButton(stringResource(label), icon, onClick, fill = true)
}

/** "Rimuovi" dice la conseguenza prima di farlo (D20). */
@Composable
private fun RemoveSheet(role: ModelRole, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  val title = stringResource(R.string.models_remove_title)
  GalleryConfirmSheet(visible = true, onDismissRequest = onDismiss, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.Warning,
      tone = GallerySheetTone.Caution,
      title = title,
      message = stringResource(if (role == ModelRole.QUERY_TEXT) R.string.models_remove_message_query else R.string.models_remove_message_index),
      actions = {
        FluidButton(text = stringResource(R.string.models_remove_confirm), onClick = onConfirm, style = FluidButtonStyle.Destructive, size = FluidButtonSize.Large, fillWidth = true)
        FluidButton(text = stringResource(R.string.models_cancel), onClick = onDismiss, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
      },
    )
  }
}

private const val PERCENT = 100f
