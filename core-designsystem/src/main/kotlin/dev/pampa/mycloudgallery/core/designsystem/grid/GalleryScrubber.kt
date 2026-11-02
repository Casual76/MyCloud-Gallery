package dev.pampa.mycloudgallery.core.designsystem.grid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.GlassTint
import dev.pampa.mycloudgallery.core.designsystem.a11y.galleryGlass
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lo stato dello scrubber: se si vede, se lo si sta trascinando e quanto e' comparso (0..1).
 *
 * Compare **quando serve** (`show`): con uno scorrimento veloce, o quando lo si tocca. Sparisce da solo dopo
 * [GalleryScrubberDefaults.HideDelayMillis] di quiete, mai mentre lo si trascina.
 */
@Stable
class GalleryScrubberState {

  /** Vero mentre un dito trascina la maniglia. */
  var dragging: Boolean by mutableStateOf(false)
    internal set

  /** Quanto e' comparso (0 nascosto, 1 visibile): una dissolvenza da chrome. */
  val appearance: Animatable<Float, AnimationVector1D> = Animatable(0f)

  private var hideJob: Job? = null

  /** Lo fa comparire (se non c'e' gia') e annulla un'uscita in attesa. */
  suspend fun show(reducedMotion: Boolean) {
    hideJob?.cancel()
    if (appearance.targetValue == 1f && (appearance.isRunning || appearance.value == 1f)) return
    if (reducedMotion) appearance.snapTo(1f) else appearance.animateTo(1f, FluidMotion.fadeIn())
  }

  /** Lo nasconde dopo [GalleryScrubberDefaults.HideDelayMillis] di quiete, mai mentre lo si trascina. */
  fun hideLater(scope: CoroutineScope, reducedMotion: Boolean) {
    hideJob?.cancel()
    hideJob = scope.launch {
      delay(GalleryScrubberDefaults.HideDelayMillis)
      if (!dragging) {
        if (reducedMotion) appearance.snapTo(0f) else appearance.animateTo(0f, FluidMotion.fadeOut())
      }
    }
  }
}

/** Le misure dello scrubber. */
object GalleryScrubberDefaults {

  /** La quiete dopo la quale lo scrubber sparisce. */
  const val HideDelayMillis: Long = 1_400L

  /** La maniglia: una capsula di vetro alta quanto un pollice. */
  val ThumbWidth: Dp = 36.dp
  val ThumbHeight: Dp = 52.dp

  /** Quanto la maniglia sta lontana dal bordo destro. */
  val EdgeInset: Dp = 4.dp

  /**
   * La striscia di accessibilita' lungo il bordo (TalkBack la trova anche quando la maniglia non si vede). Fase 10 P8 (D27): larga **48 dp**, il
   * bersaglio minimo; non disegna niente e non ha tocchi propri (passano alla griglia sotto), quindi allargarla non cambia ne' la grafica ne' i gesti.
   */
  val TrackWidth: Dp = 48.dp
}

/**
 * Lo **scrubber** della timeline (docs/fase3-progetto.md §2.4): una maniglia di vetro sul bordo destro che porta in un
 * colpo in qualsiasi punto della Libreria, con una bolla che dice dove si e' ("Marzo 2024").
 *
 *  - **Posizione senza ricomposizione**: [fraction] (0 in cima, 1 in fondo) si legge nella fase di layout, cosi' la
 *    maniglia segue lo scorrimento senza ricomporre niente; la bolla si ricompone solo quando [label] cambia.
 *  - **Trascinamento**: la maniglia non salta sotto il dito, si sposta di quanto il dito si sposta; [onScrub] riceve la
 *    frazione a ogni passo e la griglia salta li' (aritmetica sulle somme prefisse, nessuna misura). A ogni cambio di
 *    [label] durante il trascinamento un tick aptico (`FluidHapticEvent.Tick`).
 *  - **Accessibilita'**: per TalkBack e' un cursore regolabile, sempre presente anche quando la maniglia e' nascosta,
 *    con il mese come stato ("Marzo 2024") e [setProgress] che salta.
 *
 * Il vetro c'e' solo quando la maniglia si vede: a scrubber nascosto non costa niente.
 *
 * @param backdrop il fondale della griglia, che maniglia e bolla rifrangono.
 * @param tint la pellicola di maniglia e bolla (G2: quella del vetro adattivo per un contenuto qualunque); null = la
 *   famiglia `floatingTint`.
 * @param ink l'inchiostro di glifo e mese; null = `onSurface`.
 */
@Composable
fun GalleryScrubber(
  state: GalleryScrubberState,
  fraction: () -> Float,
  label: String,
  contentDescription: String,
  onScrub: (Float) -> Unit,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  reducedMotion: Boolean = false,
  onScrubStarted: () -> Unit = {},
  onScrubStopped: () -> Unit = {},
  tint: GlassTint? = null,
  ink: Color? = null,
) {
  val scope = rememberCoroutineScope()
  val glassTint = tint ?: GlassDefaults.floatingTint()
  val inkColor = ink ?: MaterialTheme.colorScheme.onSurface
  val haptics = rememberFluidHaptics()
  val currentFraction by rememberUpdatedState(fraction)
  val currentOnScrub by rememberUpdatedState(onScrub)
  val currentLabel by rememberUpdatedState(label)
  val density = LocalDensity.current

  BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
    val thumbHeightPx = with(density) { GalleryScrubberDefaults.ThumbHeight.toPx() }
    val travel = (constraints.maxHeight - thumbHeightPx).coerceAtLeast(1f)

    // La striscia per TalkBack: sempre li', senza tocchi propri (i tocchi passano alla griglia sotto).
    Box(
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .fillMaxHeight()
        .width(GalleryScrubberDefaults.TrackWidth)
        .semantics {
          this.contentDescription = contentDescription
          stateDescription = currentLabel
          progressBarRangeInfo = ProgressBarRangeInfo(currentFraction().coerceIn(0f, 1f), 0f..1f)
          setProgress { target ->
            currentOnScrub(target.coerceIn(0f, 1f))
            true
          }
        },
    )

    val visible by remember(state) { derivedStateOf { state.appearance.value > 0f || state.dragging } }
    if (visible) {
      val thumbOffset: () -> Int = { (currentFraction().coerceIn(0f, 1f) * travel).roundToInt() }
      // La bolla, a sinistra della maniglia, solo mentre la si trascina.
      if (state.dragging) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset(0, thumbOffset()) }
            .padding(end = GalleryScrubberDefaults.ThumbWidth + GalleryScrubberDefaults.EdgeInset + BubbleGap)
            .layout { measurable, constraints ->
              val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
              val y = ((thumbHeightPx - placeable.height) / 2f).roundToInt()
              layout(placeable.width, thumbHeightPx.roundToInt()) { placeable.place(0, y) }
            }
            .clearAndSetSemantics { },
        ) {
          Box(
            modifier = Modifier
              .wrapContentSize()
              .scrubberMaterial(backdrop, glassTint)
              .padding(horizontal = BubblePaddingH, vertical = BubblePaddingV),
          ) {
            Text(
              text = label,
              style = MaterialTheme.typography.titleSmall,
              color = inkColor,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      // La maniglia.
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .offset { IntOffset(0, thumbOffset()) }
          .padding(end = GalleryScrubberDefaults.EdgeInset)
          .graphicsLayer {
            val amount = if (state.dragging) 1f else state.appearance.value
            alpha = amount
            translationX = (1f - amount) * SlideIn.toPx()
          }
          .drawWithContent { if (state.appearance.value > HiddenThreshold || state.dragging) drawContent() }
          .size(GalleryScrubberDefaults.ThumbWidth, GalleryScrubberDefaults.ThumbHeight)
          .clearAndSetSemantics { }
          .pointerInput(travel) {
            var start = 0f
            var dragged = 0f
            detectVerticalDragGestures(
              onDragStart = {
                start = currentFraction().coerceIn(0f, 1f)
                dragged = 0f
                state.dragging = true
                haptics.play(FluidHapticEvent.GestureStart)
                onScrubStarted()
              },
              onDragEnd = {
                state.dragging = false
                haptics.play(FluidHapticEvent.GestureEnd)
                onScrubStopped()
                state.hideLater(scope, reducedMotion)
              },
              onDragCancel = {
                state.dragging = false
                onScrubStopped()
                state.hideLater(scope, reducedMotion)
              },
              onVerticalDrag = { change, dy ->
                change.consume()
                dragged += dy
                currentOnScrub((start + dragged / travel).coerceIn(0f, 1f))
              },
            )
          }
          .scrubberMaterial(backdrop, glassTint),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.UnfoldMore,
          contentDescription = null,
          modifier = Modifier.size(ThumbGlyph),
          tint = inkColor,
        )
      }
    }
  }

  // Un tick a ogni mese attraversato mentre si trascina: si sente dove si e' senza guardare.
  LaunchedEffect(label) {
    if (state.dragging) haptics.play(FluidHapticEvent.Tick)
  }
}

/** La maniglia e la bolla: vetro, con la pellicola data o quella della famiglia. */
@Composable
private fun Modifier.scrubberMaterial(backdrop: GlassBackdropState, tint: GlassTint): Modifier =
  galleryGlass(state = backdrop, tint = tint, shape = FluidCapsuleShape, role = GlassRole.Floating)

/** La griglia ha superato una schermata intera di scorrimento in un gesto: e' uno scorrimento "veloce". */
fun galleryScrubberShouldShow(scrolledPx: Float, viewportPx: Float): Boolean =
  viewportPx > 0f && kotlin.math.abs(scrolledPx) >= viewportPx * GalleryScrubberShowAfterScreens

/** Quante schermate di scorrimento in un gesto fanno comparire lo scrubber. */
const val GalleryScrubberShowAfterScreens: Float = 0.75f

private val BubbleGap = 8.dp
private val BubblePaddingH = 14.dp
private val BubblePaddingV = 8.dp
private val ThumbGlyph = 22.dp
private val SlideIn = 12.dp
private const val HiddenThreshold = 0.004f
