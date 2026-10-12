package dev.pampa.mycloudgallery

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.catalog.ComponentCatalogPlaceholder

/**
 * SOLO build debug: la schermata di partenza piu' un ingresso al "Catalogo componenti" di
 * `:core-designsystem`. La versione release di questo file (src/release) mostra soltanto la
 * schermata vuota: il catalogo non e' raggiungibile da nessuna parte e, non essendo referenziato,
 * R8 lo toglie dall'APK.
 */
@Composable
internal fun AppRoot() {
  var showCatalog by rememberSaveable { mutableStateOf(false) }

  if (showCatalog) {
    BackHandler { showCatalog = false }
    ComponentCatalogPlaceholder(onBack = { showCatalog = false })
  } else {
    HomeScreen {
      item { FluidSectionHeader(title = stringResource(R.string.debug_section)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.debug_catalog_title),
            subtitle = stringResource(R.string.debug_catalog_detail),
            onClick = { showCatalog = true },
          )
        }
      }
    }
  }
}
