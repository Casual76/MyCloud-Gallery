@file:OptIn(ExperimentalLayoutApi::class)

package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.R

/** Il tema: il marchio, le superfici, i raggi e la tipografia, tutti dall'engine. */
internal fun themeSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_theme_accent, R.string.sec_theme_accent_detail) { AccentRows() },
  CatalogSection(R.string.sec_theme_surfaces, R.string.sec_theme_surfaces_detail) { SurfaceSwatches() },
  CatalogSection(R.string.sec_theme_radii, R.string.sec_theme_radii_detail) { RadiusSamples() },
  CatalogSection(R.string.sec_theme_type, R.string.sec_theme_type_detail) { TypeSamples() },
)

@Composable
private fun AccentRows() {
  val scheme = MaterialTheme.colorScheme
  FluidListGroup {
    AccentRow(R.string.theme_primary, R.string.theme_primary_detail, scheme.primary)
    FluidListDivider()
    AccentRow(R.string.theme_secondary, R.string.theme_secondary_detail, scheme.secondary)
    FluidListDivider()
    AccentRow(R.string.theme_tertiary, R.string.theme_tertiary_detail, scheme.tertiary)
  }
}

@Composable
private fun AccentRow(title: Int, detail: Int, swatch: Color) {
  FluidListRow(
    title = stringResource(title),
    subtitle = stringResource(detail),
    disclosure = false,
    // Il colore sta sulla piastrella, mai sullo sfondo della riga (regola 2 del design system).
    leading = {
      Box(
        Modifier
          .size(SwatchGlyph)
          .clip(ContinuousCornerShape(FluidRadius.Small))
          .background(swatch),
      )
    },
  )
}

@Composable
private fun SurfaceSwatches() {
  val s = MaterialTheme.colorScheme
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(SwatchGap),
    verticalArrangement = Arrangement.spacedBy(SwatchGap),
  ) {
    Swatch(R.string.swatch_background, s.background)
    Swatch(R.string.swatch_surface, s.surfaceContainerLow)
    Swatch(R.string.swatch_container, s.surfaceContainerHigh)
    Swatch(R.string.swatch_primary_container, s.primaryContainer)
    Swatch(R.string.swatch_secondary_container, s.secondaryContainer)
    Swatch(R.string.swatch_tertiary_container, s.tertiaryContainer)
    Swatch(R.string.swatch_error_container, s.errorContainer)
  }
}

@Composable
private fun Swatch(label: Int, color: Color) {
  val shape = ContinuousCornerShape(FluidRadius.Control)
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(
      Modifier
        .size(SwatchSize)
        .clip(shape)
        .background(color)
        .border(width = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = shape),
    )
    Text(
      text = stringResource(label),
      modifier = Modifier.width(SwatchLabelWidth),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun RadiusSamples() {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(SwatchGap),
    verticalArrangement = Arrangement.spacedBy(SwatchGap),
  ) {
    RadiusSample(FluidRadius.Small)
    RadiusSample(FluidRadius.Control)
    RadiusSample(FluidRadius.Card)
    RadiusSample(FluidRadius.Group)
    RadiusSample(FluidRadius.Sheet)
  }
}

@Composable
private fun RadiusSample(radius: Dp) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(
      Modifier
        .size(RadiusSampleSize)
        .clip(ContinuousCornerShape(radius))
        .background(MaterialTheme.colorScheme.primaryContainer),
    )
    Text(
      text = radius.value.toInt().toString(),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun TypeSamples() {
  val s = MaterialTheme.colorScheme
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(R.string.type_title), style = MaterialTheme.typography.headlineLarge, color = s.onSurface)
    Text(stringResource(R.string.type_section), style = MaterialTheme.typography.titleLarge, color = s.onSurface)
    Text(stringResource(R.string.type_date), style = MaterialTheme.typography.titleMedium, color = s.onSurface)
    Text(stringResource(R.string.type_place), style = MaterialTheme.typography.bodyMedium, color = s.onSurfaceVariant)
    Text(stringResource(R.string.type_label), style = MaterialTheme.typography.labelMedium, color = s.onSurfaceVariant)
  }
}

private val SwatchSize = 64.dp
private val SwatchLabelWidth = 88.dp
private val RadiusSampleSize = 88.dp
private val SwatchGap = 14.dp
private val SwatchGlyph = 32.dp
