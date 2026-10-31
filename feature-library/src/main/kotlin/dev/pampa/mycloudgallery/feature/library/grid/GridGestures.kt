package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.FluidHaptics
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Cosa fanno i gesti della griglia, oltre a muoverla. */
internal class GridGestureCallbacks(
  val onOpen: (MediaId) -> Unit,
  val reducedMotion: () -> Boolean,
  val haptics: FluidHaptics,
  /** Il livello si e' assestato dopo un pinch (per ricordarlo). */
  val onLevelSettled: () -> Unit,
)

private enum class GestureMode { Undecided, Scroll, DragSelect, Pinch }

/**
 * **Un solo `pointerInput` per tutta la griglia** (§2.4, "Tocchi"): tocco, pressione lunga, trascinamento per
 * selezionare e pinch, con l'hit test fatto sul layout. Le celle non hanno tocchi: leggono solo chi e' premuto.
 *
 * Gli eventi si leggono nel passaggio `Initial` (prima della lista): cosi' la griglia puo' prendersi un trascinamento
 * prima che la lista lo trasformi in scorrimento (selezione, pinch), e lasciarle tutto il resto senza consumarlo.
 *
 *  - **Tocco**: apre (o, in modalita' selezione, seleziona). La pressione si vede dopo un istante (un dito che parte
 *    per scorrere non fa lampeggiare la cella) e arriva sempre in fondo, anche per un tocco di 20 ms.
 *  - **Pressione lunga**: entra in modalita' selezione con la cella e trascinando seleziona l'intervallo.
 *  - **In modalita' selezione** un trascinamento che parte orizzontale (sotto i 30°) seleziona, uno verticale scorre.
 *  - **Scorrimento automatico** vicino ai bordi (64 dp), con velocita' proporzionale alla profondita'.
 *  - **Pinch**: due dita cambiano livello, ancorate al punto fra le dita.
 */
internal fun Modifier.gridGestures(
  state: MediaGridState,
  scope: CoroutineScope,
  callbacks: GridGestureCallbacks,
): Modifier = pointerInput(state, callbacks) {
  val slop = viewConfiguration.touchSlop
  val edge = AutoScrollEdge.toPx()
  val maxSpeed = AutoScrollMaxSpeed.toPx()
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    // Un dito che ferma un lancio non e' un tocco su una cella.
    val stoppedFling = state.listState.isScrollInProgress
    val start = down.position
    val hit = if (stoppedFling || state.pinching) -1 else state.positionAt(start)
    val hitId = if (hit >= 0) state.idAt(hit) else null
    // Il clic destro del mouse (D15) fa quello che fa la pressione prolungata: entra in selezione con la cella (le azioni stanno nella barra). Non apre e non trascina.
    if (hitId != null && down.type == PointerType.Mouse && currentEvent.buttons.isSecondaryPressed) {
      callbacks.haptics.play(FluidHapticEvent.Threshold)
      if (!state.selecting) state.startSelecting()
      if (hitId !in state.selection.selected.value.ids) state.selection.toggle(hitId)
      down.consume()
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) break
      }
      return@awaitEachGesture
    }
    var mode = GestureMode.Undecided
    var pressJob: Job? = if (hitId != null) {
      scope.launch {
        delay(PressDelayMillis)
        state.interaction.press(hitId, callbacks.reducedMotion())
      }
    } else {
      null
    }
    val longPressAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
    var pointer = start
    var lastPosition = hit
    var autoScroll: Job? = null
    var tapped = false

    fun cancelPress() {
      pressJob?.cancel()
      pressJob = null
      state.interaction.cancelPress()
    }

    fun beginDragSelect(position: Int) {
      mode = GestureMode.DragSelect
      if (!state.selecting) state.startSelecting()
      state.selection.beginDrag(position, state.positionIds)
      lastPosition = position
      cancelPress()
      autoScroll = scope.launch {
        var last = withFrameNanos { it }
        while (isActive) {
          val now = withFrameNanos { it }
          val dt = (now - last) / NanosPerSecond
          last = now
          val speed = autoScrollSpeed(pointer.y, state.viewport, edge, maxSpeed)
          if (speed != 0f) {
            state.userScrolled = true
            state.listState.scrollBy(speed * dt)
            val position = state.positionAt(pointer)
            if (position >= 0 && position != lastPosition) {
              lastPosition = position
              state.selection.dragTo(position)
            }
          }
        }
      }
    }

    try {
      while (true) {
        val waitForLongPress = mode == GestureMode.Undecided && hit >= 0
        val event = if (waitForLongPress) {
          val remaining = longPressAt - currentEvent.changes.first().uptimeMillis
          withTimeoutOrNull(remaining.coerceAtLeast(0L)) { awaitPointerEvent(PointerEventPass.Initial) }
        } else {
          awaitPointerEvent(PointerEventPass.Initial)
        }
        if (event == null) {
          // Pressione lunga: si entra in modalita' selezione dalla cella, e il dito che resta giu' trascina.
          callbacks.haptics.play(FluidHapticEvent.Threshold)
          beginDragSelect(hit)
          continue
        }
        val pressed = event.changes.count { it.pressed }
        if (pressed >= 2 || mode == GestureMode.Pinch) {
          if (mode != GestureMode.Pinch) {
            if (mode == GestureMode.DragSelect) state.selection.endDrag()
            mode = GestureMode.Pinch
            cancelPress()
            autoScroll?.cancel()
          }
          if (pressed >= 2) {
            val zoom = event.calculateZoom()
            val centroid = event.calculateCentroid(useCurrent = true)
            if (zoom != 1f && centroid.isSpecified) {
              // Stesso evento, stesso fotogramma: il cambio di livello e il suo ancoraggio partono subito.
              scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val before = state.level
                state.pinchBy(zoom, centroid)
                if (state.level != before) callbacks.haptics.play(FluidHapticEvent.Tick)
              }
            }
          }
          // Anche un dito rimasto solo non fa scorrere la lista finche' il gesto non e' finito.
          event.changes.forEach { if (it.positionChanged()) it.consume() }
          if (pressed == 0) break
          continue
        }
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) {
          if (mode == GestureMode.Undecided && hitId != null) {
            tapped = true
            tap(state, scope, callbacks, hitId, pressJob)
          }
          break
        }
        pointer = change.position
        if (mode == GestureMode.Undecided) {
          val delta = change.position - start
          if (delta.getDistance() > slop) {
            val horizontal = abs(delta.y) <= abs(delta.x) * Tan30
            if (state.selecting && horizontal && hit >= 0) {
              beginDragSelect(hit)
            } else {
              mode = GestureMode.Scroll
              state.userScrolled = true
              cancelPress()
            }
          }
        }
        if (mode == GestureMode.DragSelect) {
          change.consume()
          val position = state.positionAt(pointer)
          if (position >= 0 && position != lastPosition) {
            lastPosition = position
            state.selection.dragTo(position)
          }
        }
      }
    } finally {
      autoScroll?.cancel()
      if (mode == GestureMode.DragSelect) state.selection.endDrag()
      if (mode == GestureMode.Pinch) {
        scope.launch {
          state.endPinch(callbacks.reducedMotion())
          callbacks.onLevelSettled()
        }
      }
      if (!tapped) cancelPress()
    }
  }
}

/** Il tocco: la pressione si vede tutta (anche se il dito e' stato giu' 20 ms), poi si apre o si seleziona. */
private fun tap(state: MediaGridState, scope: CoroutineScope, callbacks: GridGestureCallbacks, id: MediaId, pressJob: Job?) {
  val reduced = callbacks.reducedMotion()
  scope.launch {
    pressJob?.cancel()
    state.interaction.press(id, reduced)
    state.interaction.release(reduced)
  }
  if (state.selecting) {
    state.selection.toggle(id)
  } else {
    callbacks.onOpen(id)
  }
}

/** La velocita' dello scorrimento automatico per un dito a [y]: proporzionale a quanto e' dentro la fascia del bordo. */
internal fun autoScrollSpeed(y: Float, viewport: GridViewport, edge: Float, maxSpeed: Float): Float {
  if (edge <= 0f) return 0f
  val top = viewport.padTop + edge
  val bottom = viewport.height - viewport.padBottom - edge
  return when {
    y < top -> -maxSpeed * ((top - y) / edge).coerceIn(0f, 1f)
    y > bottom -> maxSpeed * ((y - bottom) / edge).coerceIn(0f, 1f)
    else -> 0f
  }
}

/** La fascia dei bordi in cui un trascinamento di selezione fa scorrere la griglia (§2.4). */
internal val AutoScrollEdge: Dp = 64.dp

/** La velocita' massima dello scorrimento automatico, a dito tutto dentro la fascia. */
internal val AutoScrollMaxSpeed: Dp = 1_400.dp

/** Quanto aspetta la pressione prima di vedersi: un dito che parte per scorrere non fa lampeggiare la cella. */
private const val PressDelayMillis = 80L

/** tan(30°): sotto questa inclinazione un trascinamento e' orizzontale. */
private const val Tan30 = 0.57735f

private const val NanosPerSecond = 1_000_000_000f
