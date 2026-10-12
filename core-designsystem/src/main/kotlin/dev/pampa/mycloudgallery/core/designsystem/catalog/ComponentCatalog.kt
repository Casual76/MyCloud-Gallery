package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.R

/**
 * Il "Catalogo componenti" interno (piano 1.2): una pagina che mostra ogni componente della
 * galleria in chiaro e scuro, per la revisione sugli screenshot. Si apre solo dalla build debug.
 *
 * Segnaposto della Fase 1.1: per ora mostra soltanto i tre ruoli di colore del tema Ametista, cosi'
 * si vede subito che il marchio e' quello giusto. La 1.2 ci mette sopra celle, badge, barre e sheet.
 */
@Composable
fun ComponentCatalogPlaceholder(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  FluidScreen(
    title = stringResource(R.string.catalog_title),
    subtitle = stringResource(R.string.catalog_subtitle),
    onBack = onBack,
    modifier = modifier,
  ) {
    item { FluidSectionHeader(title = stringResource(R.string.catalog_section_palette)) }
    item {
      FluidListGroup {
        PaletteRow(
          title = stringResource(R.string.catalog_palette_primary),
          detail = stringResource(R.string.catalog_palette_primary_detail),
          swatch = colors.primary,
        )
        FluidListDivider()
        PaletteRow(
          title = stringResource(R.string.catalog_palette_secondary),
          detail = stringResource(R.string.catalog_palette_secondary_detail),
          swatch = colors.secondary,
        )
        FluidListDivider()
        PaletteRow(
          title = stringResource(R.string.catalog_palette_tertiary),
          detail = stringResource(R.string.catalog_palette_tertiary_detail),
          swatch = colors.tertiary,
        )
      }
    }
  }
}

@Composable
private fun PaletteRow(title: String, detail: String, swatch: Color) {
  FluidListRow(
    title = title,
    subtitle = detail,
    disclosure = false,
    // Il colore sta sulla piastrella, mai sullo sfondo della riga (regola 2 del design system).
    leading = {
      Box(
        Modifier
          .size(32.dp)
          .clip(ContinuousCornerShape(FluidRadius.Small))
          .background(swatch),
      )
    },
  )
}
