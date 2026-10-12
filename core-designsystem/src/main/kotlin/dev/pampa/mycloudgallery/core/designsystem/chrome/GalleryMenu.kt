package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.fluidRowPressable
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

/** Una voce del menu ⋮: un'azione, oppure l'interruzione fra due gruppi di azioni. */
@Immutable
sealed interface GalleryMenuEntry {

  /**
   * Un'azione. [destructive] la disegna col colore di errore, e vale al massimo una per menu: e' la
   * voce che cancella, e sta in fondo, dopo un'interruzione di gruppo.
   */
  data class Item(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
  ) : GalleryMenuEntry

  /** L'interruzione fra due gruppi: una banda piena, piu' marcata del filo fra due voci. */
  data object GroupBreak : GalleryMenuEntry
}

/**
 * Il contenuto del menu ⋮: le voci in colonna, un filo fra due voci vicine, una banda fra due gruppi.
 *
 * E' **solo il contenuto**: il vetro lo mette chi lo ospita. Dentro un [GalleryMenuButton] lo mette
 * il pop-up dell'engine (`FluidGlassModalPortal`); fuori, [GalleryMenuSurface] disegna la stessa
 * pellicola. Ogni riga e' alta almeno 48 dp (bersaglio di tocco), l'etichetta sta a sinistra e
 * l'icona a destra come nel menu contestuale dell'engine, la voce distruttiva usa il colore di
 * errore dello schema.
 *
 * @param onItemSelected chiamata subito prima dell'azione di ogni voce abilitata: il menu si chiude.
 */
@Composable
fun GalleryMenuPanel(
  entries: List<GalleryMenuEntry>,
  modifier: Modifier = Modifier,
  onItemSelected: () -> Unit = {},
) {
  Column(
    modifier = modifier
      .widthIn(min = MenuMinWidth, max = MenuMaxWidth)
      .width(IntrinsicSize.Max),
  ) {
    entries.forEachIndexed { index, entry ->
      when (entry) {
        is GalleryMenuEntry.Item -> {
          if (entries.getOrNull(index - 1) is GalleryMenuEntry.Item) FluidHairline(startInset = MenuRowInset)
          GalleryMenuRow(entry, onItemSelected)
        }
        GalleryMenuEntry.GroupBreak -> GroupBand()
      }
    }
  }
}

@Composable
private fun GalleryMenuRow(item: GalleryMenuEntry.Item, onSelected: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val color = if (item.destructive) scheme.error else scheme.onSurface
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = GalleryDimens.MinTouchTarget)
      .semantics(mergeDescendants = true) { role = Role.Button }
      .fluidRowPressable(
        onClick = {
          onSelected()
          item.onClick()
        },
        enabled = item.enabled,
      )
      .padding(horizontal = MenuRowInset, vertical = MenuRowVerticalPadding)
      .alpha(if (item.enabled) 1f else DisabledAlpha),
    horizontalArrangement = Arrangement.spacedBy(MenuRowGap),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = item.label,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Medium,
      color = color,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    item.icon?.let {
      Icon(
        imageVector = it,
        contentDescription = null,
        modifier = Modifier.size(MenuGlyph),
        tint = color,
      )
    }
  }
}

@Composable
private fun GroupBand() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(GroupBandHeight)
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = GroupBandAlpha)),
  )
}

/**
 * La pellicola di vetro del menu, per chi mostra [GalleryMenuPanel] fuori da un pop-up: stesso
 * materiale (`Modal`), stesso raggio (`FluidRadius.Group`) del pannello che l'engine apre da un tasto.
 */
@Composable
fun GalleryMenuSurface(
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val shape = ContinuousCornerShape(FluidRadius.Group)
  Box(
    modifier = modifier
      .glassSurface(
        state = backdrop,
        tint = GlassDefaults.modalTint(),
        shape = shape,
        role = GlassRole.Modal,
      )
      .clip(shape),
  ) { content() }
}

/**
 * Il tasto ⋮: una lente di vetro nella barra che, toccata, apre il menu **sul tasto stesso** (il
 * pop-up dell'engine cresce dal rettangolo del tasto, e il tasto gli cede il posto).
 *
 * Il pop-up vive alla radice dell'app (`FluidGlassModalHost`): senza un host nello scope il tocco non
 * apre niente, ed e' la degradazione giusta, non un crash. Per il menu in un tasto che si trasforma
 * al *tenere premuto*, e senza separatori, c'e' `FluidBarAction(actions = ...)`.
 */
@Composable
fun GalleryMenuButton(
  entries: List<GalleryMenuEntry>,
  modifier: Modifier = Modifier,
  contentDescription: String = stringResource(R.string.topbar_more),
  backdrop: GlassBackdropState = currentGlassBackdrop(),
) {
  var open by remember { mutableStateOf(false) }
  var bounds by remember { mutableStateOf<Rect?>(null) }
  GalleryBarButton(
    icon = Icons.Rounded.MoreVert,
    contentDescription = contentDescription,
    onClick = { open = true },
    modifier = modifier.onGloballyPositioned { bounds = it.boundsInRoot() },
    backdrop = backdrop,
  )
  FluidGlassModalPortal(
    visible = open,
    onDismissRequest = { open = false },
    origin = { bounds },
    presentation = FluidGlassModalPresentation.Popover,
    paneTitle = contentDescription,
  ) {
    GalleryMenuPanel(entries = entries, onItemSelected = { open = false })
  }
}

private val MenuMinWidth = 220.dp
private val MenuMaxWidth = 320.dp
private val MenuRowInset = 16.dp
private val MenuRowVerticalPadding = 12.dp
private val MenuRowGap = 12.dp
private val MenuGlyph = 20.dp
private val GroupBandHeight = 8.dp
private const val GroupBandAlpha = 0.07f
private const val DisabledAlpha = 0.38f
