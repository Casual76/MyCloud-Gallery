package dev.pampa.mycloudgallery.feature.search.models

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
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

// I MODELLI (Fase 6, P8, §1.5 `ModelsState`, D20): stato, dimensione, sorgente, "Scarica ora", "Rimuovi" (che dice la conseguenza) e lo spazio
// dell'indice. Un colore e un glifo per ogni stato (assente, in coda, in scaricamento, in verifica, pronto, errore): da rifinire (§12.12).

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

private fun glyphOf(role: ModelRole?): ImageVector = when (role) {
  ModelRole.QUERY_TEXT -> Icons.Rounded.Search
  ModelRole.INDEX_MULTIMODAL -> Icons.Rounded.AutoAwesome
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

/** La sezione "Modelli": un gruppo per modello, con il suo avanzamento e le sue azioni, e la riga dello spazio. */
fun LazyListScope.modelsSection(models: ModelsState, handlers: SearchSettingsHandlers) {
  item(key = "models-header") { FluidSectionHeader(title = stringResource(R.string.models_section_models)) }
  models.models.forEach { row -> item(key = "model-${row.id.value}") { ModelBlock(row, handlers) } }
  item(key = "models-space") { SpaceNote(models) }
}

@Composable
private fun SpaceNote(models: ModelsState) {
  FluidSectionFootnote(text = stringResource(R.string.models_space, sizeText(models.vectorBytes), sizeText(models.textBytes)))
}

@Composable
private fun ModelBlock(row: ModelRow, handlers: SearchSettingsHandlers) {
  val spec = ModelCatalog.byId(row.id)
  val resources = LocalContext.current.resources
  var removing by remember { mutableStateOf(false) }
  val state = row.state
  val detail = listOfNotNull(sizeText(row.sizeBytes), sourceText(resources, row.source)).joinToString(" · ")
  Column(
    modifier = Modifier.padding(bottom = SearchDimens.TightGap),
    verticalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
  ) {
    FluidListGroup {
      FluidListRow(
        title = nameOf(spec?.role),
        subtitle = detail,
        tone = modelTone(state),
        leading = { Icon(glyphOf(spec?.role), contentDescription = null) },
        disclosure = false,
        titleMaxLines = 3,
        badge = { FluidStatusBadge(label = stateLabel(state), tone = modelTone(state)) },
      )
    }
    when (state) {
      is ModelState.Fetching -> {
        FluidProgressBar(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f }, modifier = Modifier.padding(horizontal = GalleryDimens.Gutter))
        FluidSectionFootnote(text = stringResource(R.string.models_state_fetching, sizeText(state.done), sizeText(state.total)))
      }
      is ModelState.Failed -> FailureNote(row.id, failureCode(state.code), state.code, row.sizeBytes, handlers)
      is ModelState.NeedsConfirmation -> {
        FluidSectionFootnote(text = stringResource(R.string.models_confirm_note, sizeText(state.bytes)))
        ActionRow {
          FluidButton(
            text = stringResource(R.string.models_confirm_action),
            onClick = { handlers.onConfirmMobileDownload(row.id) },
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
          )
        }
      }
      ModelState.Absent -> ActionRow { Download(row.id, handlers) }
      is ModelState.Ready -> if (row.removable) ActionRow { Remove { removing = true } }
      else -> Unit
    }
  }
  if (removing && spec != null) {
    RemoveSheet(spec.role, onConfirm = { removing = false; handlers.onRemoveModel(row.id) }, onDismiss = { removing = false })
  }
}

@Composable
private fun FailureNote(id: ModelId, code: DiagCode, fail: ModelFailCode, size: Long, handlers: SearchSettingsHandlers) {
  val resources = LocalContext.current.resources
  FluidSectionFootnote(text = SearchCodes.message(resources, code, sizeText(size)))
  ActionRow {
    for (action in SearchCodes.actions(code)) {
      FluidButton(
        text = stringResource(action.label),
        onClick = {
          when (action) {
            CodeAction.DOWNLOAD_NOW, CodeAction.RETRY -> handlers.onDownloadModel(id)
            CodeAction.DIAGNOSTICS -> handlers.onOpenDiagnostics()
            CodeAction.FREE_SPACE -> handlers.onFreeSpace()
            CodeAction.ENABLE_INDEXER -> handlers.onSetIndexer(true)
          }
        },
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
      )
    }
  }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
  Row(
    modifier = Modifier.padding(horizontal = GalleryDimens.Gutter),
    horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
  ) { content() }
}

@Composable
private fun Download(id: ModelId, handlers: SearchSettingsHandlers) {
  FluidButton(text = stringResource(R.string.models_download_now), onClick = { handlers.onDownloadModel(id) }, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large)
}

@Composable
private fun Remove(onClick: () -> Unit) {
  FluidButton(text = stringResource(R.string.models_remove), onClick = onClick, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large)
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
