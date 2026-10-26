package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Il riordino delle sezioni nella schermata "Modifica Raccolte" (Fase 7, P11, D33): lo stato del trascinamento (`CollectionsReorderTest`
// lo prova su JVM) e i due modificatori che lo collegano alle righe.
//
// Il trascinamento si fa dalla maniglia di una riga; chi non puo' trascinare (TalkBack, interruttori, tastiera) ha "Sposta su" e
// "Sposta giu'" come azioni della riga: [ReorderState.moveBy]. Ogni rilascio e ogni azione chiamano `onCommit` con l'ordine nuovo, una volta.

/** Lo stato del riordino: l'ordine mostrato adesso, la riga che si trascina e di quanto, le altezze misurate delle righe. */
@Stable
internal class ReorderState(
  initial: List<String>,
  private val scope: CoroutineScope,
  private val settle: suspend (from: Float, apply: (Float) -> Unit) -> Unit = ::settleWithMotion,
  private val onCommit: (List<String>) -> Unit,
) {
  /** L'ordine mostrato adesso (cambia mentre si trascina, prima del salvataggio). */
  var order: List<String> by mutableStateOf(initial)
    private set

  /** La riga che si sta trascinando, o che sta tornando al suo posto dopo il rilascio. */
  var dragging: String? by mutableStateOf(null)
    private set

  private var shift: Float by mutableFloatStateOf(0f)

  /** Di quanti pixel la riga trascinata e' lontana dal suo posto (in verticale). */
  val dragOffset: Float get() = shift

  private var held = false
  private val heights = HashMap<String, Int>()

  fun reportHeight(code: String, height: Int) {
    heights[code] = height
  }

  /** L'ordine che arriva da fuori (salvato, o cambiato da un altro dispositivo): lo si segue solo quando non si sta trascinando. */
  fun follow(external: List<String>) {
    if (dragging == null && external != order) order = external
  }

  fun begin(code: String) {
    if (code !in order || dragging != null) return
    dragging = code
    held = true
    shift = 0f
  }

  /** Il dito si e' mosso di [dy] pixel: la riga lo segue, e scambia posto con una vicina quando ne passa il centro. */
  fun dragBy(dy: Float) {
    val code = dragging ?: return
    if (!held) return
    var moved = shift + dy
    var index = order.indexOf(code)
    val own = height(code)
    var next = order
    while (index < next.lastIndex) {
      val below = height(next[index + 1])
      if (moved <= (own + below) / 2f) break
      next = swapped(next, index, index + 1)
      moved -= below
      index++
    }
    while (index > 0) {
      val above = height(next[index - 1])
      if (moved >= -(own + above) / 2f) break
      next = swapped(next, index - 1, index)
      moved += above
      index--
    }
    // La prima riga non sale sopra la cima e l'ultima non scende sotto il fondo: la riga che il dito tiene resta dentro l'elenco.
    if (index == 0) moved = maxOf(moved, 0f)
    if (index == next.lastIndex) moved = minOf(moved, 0f)
    if (next != order) order = next
    shift = moved
  }

  /** Il dito si e' sollevato: l'ordine si salva e la riga si assesta nel suo posto. */
  fun end() {
    val code = dragging ?: return
    if (!held) return
    held = false
    onCommit(order)
    scope.launch {
      settle(shift) { shift = it }
      shift = 0f
      if (dragging == code && !held) dragging = null
    }
  }

  /** Il gesto e' stato interrotto: come un rilascio (l'ordine mostrato e' quello che si vede). */
  fun cancel() = end()

  /** "Sposta su" (-1) e "Sposta giu'" (+1): il nuovo ordine, o null se la riga e' gia' in cima o in fondo. */
  fun moveBy(code: String, delta: Int): List<String>? {
    if (dragging != null) return null
    val next = CollectionsLayout.moved(order, code, delta) ?: return null
    order = next
    onCommit(next)
    return next
  }

  private fun height(code: String): Int = heights[code] ?: 0

  private fun swapped(list: List<String>, a: Int, b: Int): List<String> = list.toMutableList().also {
    val keep = it[a]
    it[a] = it[b]
    it[b] = keep
  }
}

/** La riga rilasciata scivola nel suo posto con il movimento dell'engine (i test lo sostituiscono con uno scatto). */
internal suspend fun settleWithMotion(from: Float, apply: (Float) -> Unit) {
  Animatable(from).animateTo(0f, FluidMotion.snappy()) { apply(value) }
}

@Composable
internal fun rememberReorderState(external: List<String>, onCommit: (List<String>) -> Unit): ReorderState {
  val scope = rememberCoroutineScope()
  val commit by rememberUpdatedState(onCommit)
  val state = remember(scope) { ReorderState(external, scope, onCommit = { commit(it) }) }
  state.follow(external)
  return state
}

/** Il trascinamento dalla maniglia: un dito che si muove in verticale muove la riga, senza far scorrere la pagina. */
internal fun Modifier.dragHandle(code: String, state: ReorderState): Modifier = pointerInput(code, state) {
  detectDragGestures(
    onDragStart = { state.begin(code) },
    onDrag = { change, amount ->
      change.consume()
      state.dragBy(amount.y)
    },
    onDragEnd = { state.end() },
    onDragCancel = { state.cancel() },
  )
}

/**
 * Lo scivolamento di una riga che cambia posto: dove stava l'ultima volta, e lo scarto che si annulla con il movimento dell'engine.
 * Uno per riga, tenuto da [rememberSlotShift] con la chiave della riga (cosi' segue la riga e non il posto).
 */
@Stable
internal class SlotShift(private val scope: CoroutineScope) {
  val shift = Animatable(0f)
  private var last = Int.MIN_VALUE

  /** La riga e' stata messa a [top] (in pixel, dentro il suo contenitore). Se si e' spostata e [enabled], scivola dal posto di prima. */
  fun placed(top: Int, enabled: Boolean) {
    val before = last
    last = top
    if (enabled && before != Int.MIN_VALUE && before != top) {
      scope.launch {
        shift.snapTo(shift.value + (before - top))
        shift.animateTo(0f, FluidMotion.snappy())
      }
    }
  }
}

@Composable
internal fun rememberSlotShift(): SlotShift {
  val scope = rememberCoroutineScope()
  return remember(scope) { SlotShift(scope) }
}

/**
 * Una riga che cambia posto scivola dal vecchio al nuovo invece di saltare. Con [enabled] falso (la riga che il dito tiene, o
 * "Rimuovi animazioni") si limita a ricordare dove sta.
 */
internal fun Modifier.slotShift(slot: SlotShift, enabled: Boolean): Modifier = this
  .onPlaced { coordinates -> slot.placed(coordinates.positionInParent().y.roundToInt(), enabled) }
  .offset { IntOffset(0, slot.shift.value.roundToInt()) }
