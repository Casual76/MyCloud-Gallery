package dev.pampa.mycloudgallery.feature.library.utility

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.library.R

// Fase 7, P8: le due schede dei Doppioni ("Identici" e "Simili"). Il controllo a segmenti dell'engine e' alto 32 dp: sotto i 48 dp del bersaglio di tocco
// (accessibilita', `GalleryDimens.MinTouchTarget`). Qui ogni scheda e' alta almeno 48 dp e TalkBack la legge come una scheda scelta o no; i colori
// sono quelli del tema, la forma e' quella continua del design system.

@Composable
internal fun DuplicatesTabs(selected: DuplicatesTab, onSelect: (DuplicatesTab) -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(FluidRadius.Control))
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .padding(UtilityDimens.TabInset)
      .selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(UtilityDimens.TabInset),
  ) {
    for (tab in DuplicatesTab.entries) {
      TabItem(label = stringResource(tab.labelRes), selected = tab == selected, onClick = { onSelect(tab) })
    }
  }
}

internal val DuplicatesTab.labelRes: Int
  get() = when (this) {
    DuplicatesTab.IDENTICAL -> R.string.duplicates_tab_identical
    DuplicatesTab.SIMILAR -> R.string.duplicates_tab_similar
  }

@Composable
private fun RowScope.TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier = Modifier
      .weight(1f)
      .heightIn(min = GalleryDimens.MinTouchTarget)
      .clip(ContinuousCornerShape(FluidRadius.Control))
      .then(if (selected) Modifier.background(scheme.primaryContainer) else Modifier)
      .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      modifier = Modifier.padding(horizontal = UtilityDimens.RowGap),
      style = MaterialTheme.typography.titleSmall,
      color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
    )
  }
}
