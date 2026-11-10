package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluidphysics.FluidCornerRadii
import dev.antigravity.fluidengine.ui.fluidphysics.FluidForm
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsClip
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidPhysicsState
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryMediaCell
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryDismissSpec
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryExpandSpec
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridPinchState
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryViewerTransitionState
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Il movimento: le tre demo vive (da provare con le dita) e i loro fotogrammi fermi (per la revisione). */
internal fun motionSections(): List<CatalogSection> = listOf(
  CatalogSection(R.string.sec_motion_expand, R.string.sec_motion_expand_detail) { ExpandDemo() },
  CatalogSection(R.string.sec_motion_expand_frames, R.string.sec_motion_expand_frames_detail) { ExpandFrames() },
  CatalogSection(R.string.sec_motion_dismiss_frames, R.string.sec_motion_dismiss_frames_detail) { DismissFrames() },
  CatalogSection(R.string.sec_motion_pinch, R.string.sec_motion_pinch_detail) { PinchDemo() },
  CatalogSection(R.string.sec_motion_pinch_levels, R.string.sec_motion_pinch_levels_detail) { PinchLevels() },
)

// ---------------------------------------------------------------------------------------------
// Espansione dalla miniatura + swipe giu' per chiudere
// ---------------------------------------------------------------------------------------------

/** La demo viva: tocca una miniatura (si apre), trascina verso il basso o tocca (si chiude). */
@Composable
private fun ExpandDemo() {
  val state = remember { GalleryViewerTransitionState() }
  val scope = rememberCoroutineScope()
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  var openIndex by remember { mutableIntStateOf(0) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    ViewerStage(
      state = state,
      originIndex = openIndex,
      aspect = LiveAspect,
      interactive = true,
      onThumbnail = { index ->
        openIndex = index
        scope.launch { state.open(reducedMotion) }
      },
    )
    Text(
      text = stringResource(R.string.motion_expand_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Tre fotogrammi dell'apertura: miniatura, a meta' strada, aperto. */
@Composable
private fun ExpandFrames() {
  Row(horizontalArrangement = Arrangement.spacedBy(FrameGap), modifier = Modifier.fillMaxWidth()) {
    listOf(0f, 0.45f, 1f).forEach { progress ->
      FrameWithCaption(caption = stringResource(R.string.percent_value, (progress * 100).roundToInt()), modifier = Modifier.weight(1f)) {
        ViewerStage(
          state = remember { GalleryViewerTransitionState(initialProgress = progress) },
          originIndex = FrameOrigin,
          aspect = FrameAspect,
          interactive = false,
        )
      }
    }
  }
}

/** Tre fotogrammi dello swipe: il dito non ha ancora trascinato, a meta', quasi in fondo. */
@Composable
private fun DismissFrames() {
  Row(horizontalArrangement = Arrangement.spacedBy(FrameGap), modifier = Modifier.fillMaxWidth()) {
    listOf(0f, 0.5f, 1f).forEach { drag ->
      FrameWithCaption(caption = stringResource(R.string.percent_value, (drag * 100).roundToInt()), modifier = Modifier.weight(1f)) {
        ViewerStage(
          state = remember { GalleryViewerTransitionState(initialProgress = 1f) },
          originIndex = FrameOrigin,
          aspect = FrameAspect,
          interactive = false,
          staticDragProgress = drag,
        )
      }
    }
  }
}

@Composable
private fun FrameWithCaption(caption: String, modifier: Modifier, content: @Composable () -> Unit) {
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    content()
    Text(
      text = caption,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * Il palco dell'espansione: la pagina con sei miniature e, sopra, il viewer.
 *
 * Il viewer e' **opaco dal primo fotogramma**: non sfuma mai (regola 3). Quello che si muove e' la sua
 * sagoma, che dal rettangolo della miniatura arriva a quello del palco e perde il raggio strada
 * facendo: e' un `FluidPhysicsState` guidato dall'orologio di [state] (`driveExternally`), e il
 * contenuto si ritaglia sulla silhouette viva con `fluidPhysicsClip`. Il raggio non e' animato a mano.
 *
 * Il dito guida lo stesso orologio: verso il basso il viewer si rimpicciolisce e segue il dito
 * (`graphicsLayer` di traslazione, nessun re-layout), e al rilascio [GalleryViewerTransitionState]
 * decide se chiudere o tornare a posto.
 */
@Composable
private fun ViewerStage(
  state: GalleryViewerTransitionState,
  originIndex: Int,
  aspect: Float,
  interactive: Boolean,
  modifier: Modifier = Modifier,
  onThumbnail: (Int) -> Unit = {},
  staticDragProgress: Float? = null,
) {
  val scheme = MaterialTheme.colorScheme
  val density = LocalDensity.current
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val scope = rememberCoroutineScope()

  BoxWithConstraints(
    modifier = modifier
      .fillMaxWidth()
      .aspectRatio(aspect)
      .clip(ContinuousCornerShape(FluidRadius.Group))
      .background(scheme.surfaceContainerLow),
  ) {
    val widthPx = constraints.maxWidth.toFloat()
    val heightPx = constraints.maxHeight.toFloat()
    val padPx = with(density) { StagePadding.toPx() }
    val gapPx = with(density) { StageGap.toPx() }
    val cellPx = (widthPx - 2f * padPx - (StageColumns - 1) * gapPx) / StageColumns
    val cellDp = with(density) { cellPx.toDp() }

    fun cellRect(index: Int): Rect {
      val column = index % StageColumns
      val row = index / StageColumns
      val left = padPx + column * (cellPx + gapPx)
      val top = padPx + row * (cellPx + gapPx)
      return Rect(left, top, left + cellPx, top + cellPx)
    }

    // La pagina: le miniature.
    repeat(StageCells) { index ->
      val rect = cellRect(index)
      Box(
        modifier = Modifier
          .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
          .size(cellDp),
      ) {
        GalleryMediaCell(
          contentDescription = stringResource(R.string.sample_photo, index + 1),
          onClick = if (interactive) ({ onThumbnail(index) }) else null,
        ) { SampleThumb(seed = index + 1) }
      }
    }

    // Il viewer.
    val origin = cellRect(originIndex)
    val full = Rect(0f, 0f, widthPx, heightPx)
    val thumbRadius = with(density) { FluidRadius.Small.toPx() }
    val thumbForm = remember(origin, thumbRadius) { FluidForm.Slab(origin, FluidCornerRadii.all(thumbRadius)) }
    val fullForm = remember(widthPx, heightPx) { FluidForm.Slab(full) }
    val physics = rememberFluidPhysicsState(thumbForm)
    SideEffect {
      physics.driveExternally(from = thumbForm, to = fullForm, progress = { state.progress.value })
    }
    if (staticDragProgress != null) {
      LaunchedEffect(staticDragProgress, widthPx, heightPx) {
        state.drag.snapTo(Offset(0f, staticDragProgress * heightPx * GalleryDismissSpec.TravelFraction))
        state.progress.snapTo(GalleryDismissSpec.viewerProgress(staticDragProgress))
      }
    }

    val shown by remember(state) { derivedStateOf { state.progress.value > 0f || state.progress.isRunning } }
    if (shown) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .then(
            if (interactive) {
              Modifier
                .pointerInput(state) {
                  detectTapGestures { scope.launch { state.close(reducedMotion) } }
                }
                .pointerInput(state, heightPx) {
                  val tracker = VelocityTracker()
                  detectDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDrag = { change, amount ->
                      change.consume()
                      tracker.addPosition(change.uptimeMillis, change.position)
                      scope.launch { state.dragBy(amount, heightPx) }
                    },
                    onDragEnd = {
                      val velocity = tracker.calculateVelocity().y
                      scope.launch { state.release(velocity, density.density, heightPx, reducedMotion) }
                    },
                    onDragCancel = {
                      scope.launch { state.release(0f, density.density, heightPx, reducedMotion) }
                    },
                  )
                }
            } else {
              Modifier
            },
          )
          .graphicsLayer {
            translationX = state.drag.value.x
            translationY = state.drag.value.y
          },
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .fluidPhysicsClip(physics) { state.progress.value < FullyOpen || state.dragging },
        ) {
          // La pagina del viewer: opaca, dal primo fotogramma.
          Box(Modifier.fillMaxSize().background(scheme.background))
          // La foto, nel rettangolo di questo istante.
          Box(
            modifier = Modifier.layout { measurable, constraints ->
              val frame = GalleryExpandSpec.frame(state.progress.value.coerceIn(0f, 1f), origin, full)
              val placeable = measurable.measure(
                Constraints.fixed(
                  width = frame.width.roundToInt().coerceAtLeast(1),
                  height = frame.height.roundToInt().coerceAtLeast(1),
                ),
              )
              layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(frame.left.roundToInt(), frame.top.roundToInt())
              }
            },
          ) { SampleThumb(seed = originIndex + 1) }
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Pinch fra i livelli della griglia
// ---------------------------------------------------------------------------------------------

/** La demo viva: pizzica la griglia (le colonne cambiano, ancorate al dito); o scegli il livello a mano. */
@Composable
private fun PinchDemo() {
  val state = remember { GalleryGridPinchState(initialLevel = DemoLevel) }
  val scope = rememberCoroutineScope()
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val labels = state.levels.map { stringResource(it.label) }
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(PinchStageHeight)
        .clip(ContinuousCornerShape(FluidRadius.Group))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .pinchBetweenLevels(state, reducedMotion),
    ) {
      LazyVerticalGrid(
        columns = GridCells.Fixed(state.columns),
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer {
            val scale = state.scale.value
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(state.focal.x, state.focal.y)
          },
        horizontalArrangement = Arrangement.spacedBy(PinchGap),
        verticalArrangement = Arrangement.spacedBy(PinchGap),
        userScrollEnabled = !state.pinching,
      ) {
        items(PinchItems) { index ->
          GalleryMediaCell(contentDescription = stringResource(R.string.sample_photo, index + 1)) {
            SampleThumb(seed = index + 1)
          }
        }
      }
    }
    // L'alternativa al gesto: chi non puo' pizzicare sceglie il livello. Lo stesso movimento, con la molla.
    FluidSegmentedControl(
      options = state.levels.indices.toList(),
      selected = state.level,
      onSelect = { index -> scope.launch { state.jumpTo(index, reducedMotion) } },
      label = { index -> labels[index] },
    )
    Text(
      text = stringResource(R.string.motion_pinch_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** I quattro livelli fianco a fianco, fermi: quante colonne, quanto grandi le celle. */
@Composable
private fun PinchLevels() {
  val levels = remember { GalleryGridPinchState().levels }
  Row(horizontalArrangement = Arrangement.spacedBy(FrameGap), modifier = Modifier.fillMaxWidth()) {
    levels.forEach { level ->
      Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        BoxWithConstraints(
          modifier = Modifier
            .fillMaxWidth()
            .height(LevelStripHeight)
            .clip(ContinuousCornerShape(FluidRadius.Small))
            .clipToBounds(),
        ) {
          val rows = ceil(LevelStripHeight.value / (maxWidth.value / level.columns)).toInt() + 1
          CatalogStaticGrid(
            columns = level.columns,
            rows = rows,
            firstSeed = 1,
            modifier = Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true),
          )
        }
        Text(
          text = stringResource(level.label),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = level.columns.toString(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * Il gesto del pinch: osservato sul passaggio `Initial` e consumato **solo** con due dita giu',
 * cosi' un dito solo scorre la lista come sempre. Il fattore di zoom e il baricentro vanno allo stato,
 * che li trasforma in livello e scala; al rilascio la scala torna a 1 con la molla.
 */
@Composable
private fun Modifier.pinchBetweenLevels(state: GalleryGridPinchState, reducedMotion: Boolean): Modifier {
  val scope = rememberCoroutineScope()
  return pointerInput(state, reducedMotion) {
    awaitEachGesture {
      awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.count { it.pressed } >= 2) {
          val zoom = event.calculateZoom()
          val centroid = event.calculateCentroid(useCurrent = true)
          if (zoom != 1f && centroid != Offset.Unspecified && size.width > 0 && size.height > 0) {
            val focal = Offset(centroid.x / size.width, centroid.y / size.height)
            scope.launch { state.onPinch(zoom, focal) }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
          }
        }
      } while (event.changes.any { it.pressed })
      scope.launch { state.onRelease(reducedMotion) }
    }
  }
}

private val StagePadding = 10.dp
private val StageGap = 6.dp
private val FrameGap = 10.dp
private val PinchStageHeight = 300.dp
private val PinchGap = 2.dp
private val LevelStripHeight = 110.dp
private const val DemoLevel = 2
private const val FrameOrigin = 1
private const val StageColumns = 3
private const val StageCells = 6
private const val LiveAspect = 1.4f
private const val FrameAspect = 0.9f
private const val FullyOpen = 0.999f
private const val PinchItems = 80
