package dev.pampa.mycloudgallery.feature.settings.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// LA PAGINA "WIDGET" DELLE IMPOSTAZIONI (Fase 10, P3, docs/fase10-progetto.md D3, D6, §1.4, §2.2; risposte Q1 (a), Q2 (a), Q3 (c), Q4 (a)): che cosa mostra il widget e ogni quanto
// cambia, e in chiaro cio' che il widget **non** fa (mai un nascosto, mai un nome, mai sulla schermata di blocco, con il blocco dell'app solo foto anche sul telefono e nessun
// titolo). Nessuna scelta per il blocco e nessuna per la schermata di blocco: sono regole, non preferenze. Righe di Fluid come le altre pagine, nessuno stile nuovo; nessun dato
// personale: la pagina non mostra mai una foto, un titolo o un luogo.

@HiltViewModel
class WidgetSettingsViewModel @Inject constructor(private val settings: WidgetSettings) : ViewModel() {

  val content: StateFlow<WidgetContent> = settings.content
  val period: StateFlow<WidgetRefresh> = settings.period

  fun setContent(content: WidgetContent) {
    viewModelScope.launch { settings.setContent(content) }
  }

  fun setPeriod(period: WidgetRefresh) {
    viewModelScope.launch { settings.setPeriod(period) }
  }
}

@Composable
internal fun WidgetSettingsRoute(onBack: () -> Unit, viewModel: WidgetSettingsViewModel = hiltViewModel()) {
  val content by viewModel.content.collectAsStateWithLifecycle()
  val period by viewModel.period.collectAsStateWithLifecycle()
  WidgetSettingsScreen(content = content, period = period, onBack = onBack, onContent = viewModel::setContent, onPeriod = viewModel::setPeriod)
}

/** La pagina per uno stato dato: cosa mostra, ogni quanto cambia, e cio' che il widget non fa. Nessun effetto. */
@Composable
fun WidgetSettingsScreen(
  content: WidgetContent,
  period: WidgetRefresh,
  onBack: () -> Unit,
  onContent: (WidgetContent) -> Unit,
  onPeriod: (WidgetRefresh) -> Unit,
) {
  FluidScreen(title = stringResource(R.string.widget_title), onBack = onBack) {
    item { FluidSectionHeader(title = stringResource(R.string.widget_section_content)) }
    item {
      FluidListGroup {
        WidgetContent.entries.forEachIndexed { index, option ->
          if (index > 0) FluidListDivider()
          ChoiceRow(
            title = stringResource(contentTitle(option)),
            detail = stringResource(contentDetail(option)),
            icon = contentIcon(option),
            selected = option == content,
            onSelect = { if (option != content) onContent(option) },
          )
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.widget_content_footnote)) }

    item { FluidSectionHeader(title = stringResource(R.string.widget_section_period)) }
    item {
      FluidListGroup {
        WidgetRefresh.entries.forEachIndexed { index, option ->
          if (index > 0) FluidListDivider()
          ChoiceRow(
            title = stringResource(periodTitle(option)),
            detail = null,
            icon = Icons.Rounded.Schedule,
            selected = option == period,
            onSelect = { if (option != period) onPeriod(option) },
          )
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.widget_period_footnote)) }

    item { FluidSectionHeader(title = stringResource(R.string.widget_section_privacy)) }
    item { FluidSectionFootnote(text = stringResource(R.string.widget_privacy_hidden)) }
    item { FluidSectionFootnote(text = stringResource(R.string.widget_privacy_lock)) }
    item { FluidSectionFootnote(text = stringResource(R.string.widget_privacy_home)) }
    item { FluidSectionHeader(title = stringResource(R.string.widget_section_add)) }
    item { FluidSectionFootnote(text = stringResource(R.string.widget_add_how)) }
  }
}

/** Una scelta: la riga con l'interruttore a sinistra del suo stato; per TalkBack e' una scelta, con il suo nome e se e' quella attiva. */
@Composable
private fun ChoiceRow(title: String, detail: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onSelect: () -> Unit) {
  FluidListRow(
    title = title,
    subtitle = detail.orEmpty(),
    tone = if (selected) FluidTone.Primary else FluidTone.Neutral,
    leading = { RowGlyph(icon) },
    disclosure = false,
    titleMaxLines = 3,
    badge = { FluidSwitch(checked = selected, onCheckedChange = null) },
    selected = selected,
    onClick = onSelect,
    modifier = Modifier.semantics(mergeDescendants = true) {
      role = Role.RadioButton
      this.selected = selected
      contentDescription = if (detail == null) title else "$title. $detail"
    },
  )
}

private fun contentTitle(option: WidgetContent): Int = when (option) {
  WidgetContent.MEMORY_THEN_PHOTOS -> R.string.widget_content_memory_then_photos
  WidgetContent.PHOTOS_ONLY -> R.string.widget_content_photos_only
  WidgetContent.MEMORIES_ONLY -> R.string.widget_content_memories_only
}

private fun contentDetail(option: WidgetContent): Int = when (option) {
  WidgetContent.MEMORY_THEN_PHOTOS -> R.string.widget_content_memory_then_photos_detail
  WidgetContent.PHOTOS_ONLY -> R.string.widget_content_photos_only_detail
  WidgetContent.MEMORIES_ONLY -> R.string.widget_content_memories_only_detail
}

private fun contentIcon(option: WidgetContent) = when (option) {
  WidgetContent.MEMORY_THEN_PHOTOS -> Icons.Rounded.AutoAwesome
  WidgetContent.PHOTOS_ONLY -> Icons.Rounded.Collections
  WidgetContent.MEMORIES_ONLY -> Icons.Rounded.Today
}

private fun periodTitle(option: WidgetRefresh): Int = when (option) {
  WidgetRefresh.HOURS_3 -> R.string.widget_period_hours_3
  WidgetRefresh.HOURS_6 -> R.string.widget_period_hours_6
  WidgetRefresh.DAILY -> R.string.widget_period_daily
}

// --- La riga nella pagina principale -----------------------------------------------------------------------------------------------------

/** Cio' che la riga "Widget" dice: le due scelte in una frase. */
data class WidgetEntrySummary(val content: WidgetContent, val period: WidgetRefresh)

/** Il valore per la pagina principale. */
@HiltViewModel
class WidgetEntryViewModel @Inject constructor(settings: WidgetSettings) : ViewModel() {
  val content: StateFlow<WidgetContent> = settings.content
  val period: StateFlow<WidgetRefresh> = settings.period
}

/** La riga "Widget" nella pagina principale: il titolo e le due scelte in una frase ("Il ricordo del giorno, ogni 3 ore"). */
@Composable
internal fun WidgetEntryRow(summary: WidgetEntrySummary, onClick: () -> Unit) {
  FluidListRow(
    title = stringResource(R.string.widget_title),
    subtitle = stringResource(R.string.widget_entry_summary, stringResource(entryContent(summary.content)), stringResource(entryPeriod(summary.period))),
    tone = FluidTone.Info,
    leading = { RowGlyph(Icons.Rounded.Widgets) },
    onClick = onClick,
  )
}

private fun entryContent(option: WidgetContent): Int = when (option) {
  WidgetContent.MEMORY_THEN_PHOTOS -> R.string.widget_entry_memory_then_photos
  WidgetContent.PHOTOS_ONLY -> R.string.widget_entry_photos_only
  WidgetContent.MEMORIES_ONLY -> R.string.widget_entry_memories_only
}

private fun entryPeriod(option: WidgetRefresh): Int = when (option) {
  WidgetRefresh.HOURS_3 -> R.string.widget_entry_hours_3
  WidgetRefresh.HOURS_6 -> R.string.widget_entry_hours_6
  WidgetRefresh.DAILY -> R.string.widget_entry_daily
}
