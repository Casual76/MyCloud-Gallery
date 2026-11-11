package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
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
  /**
   * Fase 10 (P6, D20): questo tocco comincia un **trascinamento verso un'altra app** (`mediaDragSource`)? Se si', la pressione lunga e' sua e la griglia non entra in selezione ne' tratta il
   * rilascio come un tocco. Il dito (o il mouse) sulla cella: [Offset] e se e' un mouse. Di serie no.
   */
  val ownsLongPress: (Offset, Boolean) -> Boolean = { _, _ -> false },
  /**
   * Fase H (D1): il punto [Offset] della griglia sta nella fascia laterale dei gesti di sistema (`WindowInsets.systemGestures`, il bordo da cui
   * parte l'Indietro). Un dito che parte li' e va di lato e' del sistema: non comincia una selezione a trascinamento. Di serie nessuna fascia.
   */
  val inSystemGestureBand: (Offset) -> Boolean = { false },
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
 *
 * **Un tocco vale solo se il dito si alza davvero** (fase H, D1, 11/11). Quando il sistema si prende il gesto (l'Indietro dal bordo, anche quello
 * predittivo) manda alla finestra un annullamento (`ACTION_CANCEL`); Compose lo traduce in un evento sintetico con il dito **alzato ma gia'
 * consumato** (`SuspendingPointerInputModifierNodeImpl.onCancelPointerInput`, verificato sul bytecode di ui 1.10.5). Il sistema lo decide prima
 * che il dito superi lo slop della griglia (la soglia dell'Indietro e' tre quarti dello slop), quindi il gesto era ancora "indeciso" e quel
 * rilascio finto si leggeva come un tocco: sull'S25 un Indietro partito sopra una foto la apriva. Ora un rilascio consumato (annullamento, o un
 * altro nodo che si e' preso il gesto) non apre e non seleziona niente. Un dito partito nella fascia dei gesti di sistema che va di lato non
 * comincia nemmeno una selezione a trascinamento ([GridGestureCallbacks.inSystemGestureBand]); un tocco fermo nella fascia resta un tocco, come
 * nelle gallerie di sistema (la cella al bordo si apre).
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
    // Fase 10 (P6): se la pressione lunga e' del trascinamento verso un'altra app (la cella e' gia' selezionata, o e' un mouse), la griglia non seleziona e il rilascio non e' un tocco.
    val mouse = down.type == PointerType.Mouse
    val dragOwned = hitId != null && callbacks.ownsLongPress(start, mouse)
    // D1: un dito che parte dal bordo dei gesti di sistema e va di lato e' l'Indietro, non una selezione.
    val fromSystemEdge = !mouse && callbacks.inSystemGestureBand(start)
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
        val waitForLongPress = mode == GestureMode.Undecided && hit >= 0 && !dragOwned
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
          // D1: solo un rilascio vero. Quello sintetico dell'annullamento (il sistema si e' preso il gesto) arriva gia' consumato.
          if (mode == GestureMode.Undecided && hitId != null && isRealRelease(change) && !(dragOwned && gestureLongEnough(down.uptimeMillis, change.uptimeMillis, viewConfiguration.longPressTimeoutMillis))) {
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
            // Il mouse che trascina una cella (o la selezione) la porta verso un'altra app: non e' una selezione a trascinamento (P6).
            if (state.selecting && horizontal && hit >= 0 && !(dragOwned && mouse) && !fromSystemEdge) {
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

/**
 * Il dito si e' alzato **davvero** (D1): non un annullamento. Compose traduce `ACTION_CANCEL` in un rilascio sintetico gia' consumato, e un
 * rilascio che un altro nodo ha consumato non e' nemmeno lui un tocco della griglia.
 */
internal fun isRealRelease(change: PointerInputChange): Boolean = !change.pressed && !change.isConsumed

/** Il dito e' rimasto giu' almeno quanto la pressione lunga: il gesto era un trascinamento verso un'altra app (anche se non e' partito), non un tocco. */
private fun gestureLongEnough(downAt: Long, upAt: Long, longPressMillis: Long): Boolean = upAt - downAt >= longPressMillis

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
