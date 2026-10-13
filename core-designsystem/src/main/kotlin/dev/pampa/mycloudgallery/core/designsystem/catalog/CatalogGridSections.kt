package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.designsystem.R

/**
 * Il gruppo **Griglia** del catalogo (Fase 3, P7): la cella leggera della griglia, lo scrubber, il menu del filtro e i
 * livelli per larghezza (`designsystem/grid/`). **Stub di P0**, gia' registrato in [catalogSections] (`CatalogGroup.Grid`):
 * P7 sostituisce il contenuto di questa funzione con le sezioni vere e non deve toccare `CatalogGroup.kt` ne'
 * `ComponentCatalog.kt`. Le stringhe del gruppo stanno in `values/strings_catalog_grid.xml`.
 */
internal fun gridSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_grid_todo, R.string.sec_grid_todo_detail) { GridPlaceholder() },
)

@Composable
private fun GridPlaceholder() {
  Text(
    text = stringResource(R.string.sec_grid_todo_body),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
