package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.designsystem.R

/**
 * Il gruppo **Viewer** del catalogo (Fase 3, P8): le barre di vetro sulla foto con `galleryTintOnPhoto`, la barra del
 * video e la sezione informazioni (`designsystem/viewer/`). **Stub di P0**, gia' registrato in [catalogSections]
 * (`CatalogGroup.Viewer`): P8 sostituisce il contenuto di questa funzione con le sezioni vere e non deve toccare
 * `CatalogGroup.kt` ne' `ComponentCatalog.kt`. Le stringhe del gruppo stanno in `values/strings_catalog_viewer.xml`.
 */
internal fun viewerSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_viewer_todo, R.string.sec_viewer_todo_detail) { ViewerPlaceholder() },
)

@Composable
private fun ViewerPlaceholder() {
  Text(
    text = stringResource(R.string.sec_viewer_todo_body),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
