package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryActionBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.page.ZoomableMediaState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Il testo dal vivo disegnato sopra la foto della pagina corrente (D43): il velo sulle righe ("Testo"), la selezione con le maniglie e la
 * barra "Copia · Seleziona tutto · Condividi testo". Sta **fuori** dalla trasformazione dello zoom e ne rifa i conti a ogni
 * fotogramma ([ZoomableMediaState.contentMapping]): le maniglie restano da 48 dp a ogni livello di zoom e seguono lo spostamento (E-V13).
 *
 * Non fa niente se lo stato non e' di questa pagina: il testo di un media non compare mai su un altro (E-H1).
 */
@Composable
internal fun LiveTextOverlay(
  controller: LiveTextController,
  id: MediaId,
  zoom: ZoomableMediaState,
  aspect: Float,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
) {
  if (controller.owner != id) return
  val state = controller.state
  if (state !is LiveTextState.Highlighted && state !is LiveTextState.Selecting && state !is LiveTextState.Actions) return
  val geometry = controller.geometry(id, aspect) ?: return
  BoxWithConstraints(modifier.fillMaxSize()) {
    val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
    val mapping = zoom.contentMapping(size, aspect)
    val veil = MaterialTheme.colorScheme.primary.copy(alpha = VEIL_ALPHA)
    val selected = MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_ALPHA)
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val pad = mapping.pxToImageHeights(PAD_DP * density)
    when (state) {
      is LiveTextState.Highlighted -> Canvas(Modifier.fillMaxSize()) {
        for (line in state.layout.lines.indices) fillQuad(geometry.lineQuad(line, pad), mapping, veil)
      }
      is LiveTextState.Selecting -> SelectionLayer(controller, geometry, state.selection, mapping, aspect, selected, backdrop, showBar = false)
      is LiveTextState.Actions -> SelectionLayer(controller, geometry, state.selection, mapping, aspect, selected, backdrop, showBar = true)
      else -> Unit
    }
  }
}

@Composable
private fun SelectionLayer(
  controller: LiveTextController,
  geometry: LiveTextGeometry,
  selection: TextSelection,
  mapping: ContentMapping,
  aspect: Float,
  color: androidx.compose.ui.graphics.Color,
  backdrop: GlassBackdropState,
  showBar: Boolean,
) {
  val density = androidx.compose.ui.platform.LocalDensity.current.density
  val pad = mapping.pxToImageHeights(PAD_DP * density)
  val quads = TextSelections.spans(geometry, selection).map { (line, first, last) -> geometry.quad(line, first, last, pad) }
  Canvas(Modifier.fillMaxSize()) { quads.forEach { fillQuad(it, mapping, color) } }
  LiveTextHandle(HandleSide.Start, geometry.handle(selection.from, atEnd = false), mapping, aspect, controller)
  LiveTextHandle(HandleSide.End, geometry.handle(selection.to, atEnd = true), mapping, aspect, controller)
  if (showBar) {
    val screen = quads.flatten().map(mapping::imageToScreen)
    if (screen.isNotEmpty()) {
      val bounds = androidx.compose.ui.geometry.Rect(
        screen.minOf { it.x }, screen.minOf { it.y }, screen.maxOf { it.x }, screen.maxOf { it.y },
      )
      LiveTextBar(controller, aspect, bounds, mapping.size, backdrop)
    }
  }
}

private fun DrawScope.fillQuad(quad: List<Offset>, mapping: ContentMapping, color: androidx.compose.ui.graphics.Color) {
  if (quad.size < 3) return
  val path = Path()
  quad.map(mapping::imageToScreen).forEachIndexed { index, p -> if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
  path.close()
  drawPath(path, color)
}

/**
 * Una maniglia: il bersaglio e' da 48 dp (D43, D46), la parte che si vede e' una goccia sotto il bordo del testo. Il dito la tira
 * dal punto della riga che ha toccato: la selezione si aggancia alla parola piu' vicina, anche su un'altra riga, nell'ordine di lettura.
 */
@Composable
private fun LiveTextHandle(side: HandleSide, handle: LiveTextGeometry.Handle, mapping: ContentMapping, aspect: Float, controller: LiveTextController) {
  val currentMapping by rememberUpdatedState(mapping)
  val currentHandle by rememberUpdatedState(handle)
  val tip = mapping.imageToScreen(handle.tip)
  val label = stringResource(if (side == HandleSide.Start) R.string.livetext_handle_start else R.string.livetext_handle_end)
  val color = MaterialTheme.colorScheme.primary
  Box(
    modifier = Modifier
      .offset { IntOffset((tip.x - HANDLE_TARGET.toPx() / 2f).roundToInt(), (tip.y + HANDLE_GAP.toPx() - HANDLE_TARGET.toPx() / 2f).roundToInt()) }
      .size(HANDLE_TARGET)
      .semantics { contentDescription = label }
      .pointerInput(side) {
        var anchor = Offset.Zero
        var total = Offset.Zero
        detectDragGestures(
          onDragStart = {
            anchor = currentMapping.imageToScreen(currentHandle.anchor)
            total = Offset.Zero
            controller.beginDrag(side)
          },
          onDrag = { change, amount ->
            change.consume()
            total += amount
            controller.dragTo(currentMapping.screenToImage(anchor + total), aspect)
          },
          onDragEnd = { controller.endDrag() },
          onDragCancel = { controller.endDrag() },
        )
      },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val center = Offset(size.width / 2f, size.height / 2f)
      drawCircle(color, radius = HANDLE_RADIUS.toPx(), center = center)
    }
  }
}

/** La barra sopra la selezione (sotto, se non c'e' posto): tre azioni da 48 dp con la loro etichetta per TalkBack. */
@Composable
private fun LiveTextBar(
  controller: LiveTextController,
  aspect: Float,
  bounds: androidx.compose.ui.geometry.Rect,
  page: Size,
  backdrop: GlassBackdropState,
) {
  val clipLabel = stringResource(R.string.livetext_clip_label)
  val copy = stringResource(R.string.livetext_bar_copy)
  val selectAll = stringResource(R.string.livetext_bar_select_all)
  val share = stringResource(R.string.livetext_bar_share)
  val actions = remember(copy, selectAll, share, clipLabel, controller, aspect) {
    listOf(
      GalleryBarAction(Icons.Rounded.ContentCopy, copy, { controller.copySelection(clipLabel) }),
      GalleryBarAction(Icons.Rounded.SelectAll, selectAll, { controller.selectAll(aspect) }),
      GalleryBarAction(Icons.Rounded.IosShare, share, controller::shareSelection),
    )
  }
  val density = androidx.compose.ui.platform.LocalDensity.current.density
  val gap = BAR_GAP.value * density
  val margin = BAR_MARGIN.value * density
  val handleRoom = (HANDLE_GAP + HANDLE_TARGET).value * density
  GalleryActionBar(
    actions = actions,
    backdrop = backdrop,
    onPhoto = true,
    modifier = Modifier.layout { measurable, constraints ->
      val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
      val x = (bounds.center.x - placeable.width / 2f).coerceIn(margin, max(margin, page.width - placeable.width - margin))
      val above = bounds.top - placeable.height - gap
      val y = if (above >= margin) above else min(bounds.bottom + handleRoom, page.height - placeable.height - margin)
      layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
    },
  )
}

/** Il bersaglio di una maniglia: 48 dp (D43, D46). */
internal val HANDLE_TARGET: Dp = 48.dp
private val HANDLE_GAP: Dp = 4.dp
private val HANDLE_RADIUS: Dp = 7.dp
private val BAR_GAP: Dp = 12.dp
private val BAR_MARGIN: Dp = 8.dp
private const val PAD_DP = 2f
private const val VEIL_ALPHA = 0.16f
private const val SELECTED_ALPHA = 0.34f
