package dev.pampa.mycloudgallery.feature.diagnostics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidGroupSegment
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.SaveResult
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportDocument

/**
 * L'anteprima dell'esportazione e il tasto che la salva (docs/fase2-progetto.md §2.6): prima si vede il file
 * **intero**, poi si sceglie dove metterlo con il selettore di sistema (`ACTION_CREATE_DOCUMENT`: nessun
 * FileProvider, nessun permesso di archiviazione). Il testo che si legge qui e' esattamente quello che viene scritto.
 *
 * L'anteprima e' spezzata in gruppi di [LINES_PER_GROUP] righe, uno per `item`: la lista le disegna solo quando
 * servono, e un gruppo troppo alto sarebbe una texture troppo grande (regole Fluid, «un pannello di vetro e' una
 * texture»).
 *
 * @param document il file, o null se non e' pronto o e' stato rifiutato ([refused]).
 */
@Composable
fun DiagnosticsExportScreen(
  document: ExportDocument?,
  refused: Boolean,
  saveResult: SaveResult?,
  onBack: () -> Unit,
  onSave: () -> Unit,
) {
  FluidScreen(
    title = stringResource(R.string.diagnostics_export_title),
    onBack = onBack,
  ) {
    item {
      FluidInlineMessage(
        title = stringResource(R.string.diagnostics_export_contents_title),
        message = stringResource(R.string.diagnostics_export_contents_message),
        tone = FluidTone.Info,
      )
    }
    when {
      refused -> item {
        FluidInlineMessage(
          title = stringResource(R.string.diagnostics_export_refused_title),
          message = stringResource(R.string.diagnostics_export_refused_message),
          tone = FluidTone.Danger,
        )
      }
      document == null -> item { FluidLoadingBlock() }
      else -> {
        item { SaveBar(saveResult, onSave) }
        item { FluidSectionHeader(title = stringResource(R.string.diagnostics_export_preview)) }
        val chunks = document.lines.chunked(LINES_PER_GROUP)
        chunks.forEachIndexed { index, lines ->
          item(key = "preview-$index") {
            FluidListGroup(segment = segmentOf(index, chunks.size)) {
              Text(
                text = lines.joinToString("\n"),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
        item { FluidSectionFootnote(text = stringResource(R.string.diagnostics_export_preview_footnote)) }
      }
    }
  }
}

@Composable
private fun SaveBar(saveResult: SaveResult?, onSave: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FluidScreenDefaults.ItemSpacing)) {
    FluidButton(
      text = stringResource(R.string.diagnostics_export_save),
      onClick = onSave,
      size = FluidButtonSize.Large,
      fillWidth = true,
      leading = { Icon(imageVector = Icons.Rounded.Save, contentDescription = null) },
    )
    when (saveResult) {
      SaveResult.SAVED -> FluidInlineMessage(
        title = stringResource(R.string.diagnostics_export_saved_title),
        message = stringResource(R.string.diagnostics_export_saved_message),
        tone = FluidTone.Success,
      )
      SaveResult.FAILED -> FluidInlineMessage(
        title = stringResource(R.string.diagnostics_export_failed_title),
        message = stringResource(R.string.diagnostics_export_failed_message),
        tone = FluidTone.Danger,
      )
      null -> Unit
    }
  }
}

private fun segmentOf(index: Int, count: Int): FluidGroupSegment = when {
  count == 1 -> FluidGroupSegment.Whole
  index == 0 -> FluidGroupSegment.First
  index == count - 1 -> FluidGroupSegment.Last
  else -> FluidGroupSegment.Middle
}

/** Quante righe del file in un gruppo dell'anteprima. */
const val LINES_PER_GROUP: Int = 30
