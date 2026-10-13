package dev.pampa.mycloudgallery.core.designsystem.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuPanel

/** Una scelta del menu della vista: un'etichetta, se e' quella attiva, cosa fa. */
@Immutable
data class GalleryFilterOption(
  val label: String,
  val selected: Boolean,
  val onSelect: () -> Unit,
)

/**
 * Il menu della **vista** della griglia (D4, D16): il filtro rapido ("Tutto", "Video", "Preferiti"...) e lo stile
 * delle celle ("Quadrata", "Proporzioni originali"), un gruppo per scelta, con la spunta sulla voce attiva. In fondo,
 * [trailing] per le voci che non sono scelte (le Impostazioni).
 *
 * E' il tasto ⋮ della barra (`GalleryMenuButton`), con le stesse regole: il pop-up dell'engine cresce dal tasto, le voci
 * sono quelle di `GalleryMenuPanel` (48 dp, filo fra due voci, banda fra due gruppi). Quando un filtro e' attivo
 * ([active]) il tasto porta un punto dell'accento: la griglia sta mostrando meno di tutto, e lo deve dire anche a
 * menu chiuso.
 *
 * Il pop-up vive nel `FluidGlassModalHost` dello scope: senza, il tocco non apre niente (degradazione, non crash).
 */
@Composable
fun GalleryFilterMenu(
  groups: List<List<GalleryFilterOption>>,
  contentDescription: String,
  modifier: Modifier = Modifier,
  active: Boolean = false,
  trailing: List<GalleryMenuEntry.Item> = emptyList(),
  backdrop: GlassBackdropState = currentGlassBackdrop(),
) {
  var open by remember { mutableStateOf(false) }
  var bounds by remember { mutableStateOf<Rect?>(null) }
  Box(modifier = modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
    GalleryBarButton(
      icon = Icons.Rounded.FilterList,
      contentDescription = contentDescription,
      onClick = { open = true },
      backdrop = backdrop,
    )
    if (active) {
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(top = DotInset, end = DotInset)
          .size(DotSize)
          .background(MaterialTheme.colorScheme.primary, FluidCapsuleShape)
          .clearAndSetSemantics { },
      )
    }
  }
  FluidGlassModalPortal(
    visible = open,
    onDismissRequest = { open = false },
    origin = { bounds },
    presentation = FluidGlassModalPresentation.Popover,
    paneTitle = contentDescription,
  ) {
    GalleryMenuPanel(entries = galleryFilterMenuEntries(groups, trailing), onItemSelected = { open = false })
  }
}

/** Le voci del menu: i gruppi separati da una banda, la spunta sulla voce scelta, poi le voci in coda. */
fun galleryFilterMenuEntries(
  groups: List<List<GalleryFilterOption>>,
  trailing: List<GalleryMenuEntry.Item> = emptyList(),
): List<GalleryMenuEntry> = buildList {
  groups.filter { it.isNotEmpty() }.forEach { group ->
    if (isNotEmpty()) add(GalleryMenuEntry.GroupBreak)
    group.forEach { option ->
      add(GalleryMenuEntry.Item(label = option.label, onClick = option.onSelect, icon = if (option.selected) Icons.Rounded.Check else null))
    }
  }
  if (trailing.isNotEmpty()) {
    if (isNotEmpty()) add(GalleryMenuEntry.GroupBreak)
    addAll(trailing)
  }
}

private val DotSize = 8.dp
private val DotInset = 6.dp
