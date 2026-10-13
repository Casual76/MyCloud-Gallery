package dev.pampa.mycloudgallery.feature.viewer.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sign

/**
 * Cosa fa un trascinamento verticale con un dito sulla foto (a 1x di zoom).
 *
 * Verso il basso **chiude** il viewer (la foto segue il dito e torna alla miniatura) o, con il pannello aperto, lo
 * chiude; verso l'alto **apre le informazioni** sul telefono. Lo decide [viewerVerticalDrag] dalla direzione.
 */
internal enum class VerticalIntent { Dismiss, OpenInfo, CloseInfo }

/** Quanto deve prevalere la componente verticale su quella orizzontale perche' il gesto sia del viewer e non del pager. */
private const val VerticalBias = 1.0f

/**
 * Il gesto verticale del viewer, **osservato prima dei figli** (passaggio `Initial`): il pager e lo zoom stanno sotto
 * e vedono il gesto solo se questo non lo reclama.
 *
 * Perche' `Initial` e non il passaggio normale: i figli leggono per primi, e un pager che ha gia' preso un dito che sta
 * andando in diagonale non lo molla piu'. Qui si decide **con la sola geometria**, prima di loro: dopo la soglia di
 * tocco (touch slop) il gesto e' del viewer se e' piu' verticale che orizzontale, del pager altrimenti. Reclamato, ogni
 * cambiamento del dito viene consumato: il pager e lo zoom lo vedono gia' consumato e non reagiscono.
 *
 *  - **Un dito solo.** Con due dita (un pizzico) il gesto non e' nostro e non lo si tocca; una seconda dita che arriva
 *    a gesto reclamato lo termina li'.
 *  - **Non a zoom.** Ingrandita, la foto si sposta con un dito: [enabled] e' falso e il gesto va allo zoom.
 *  - **Il rilascio** porta la velocita' verticale in pixel al secondo (positiva = verso il basso).
 *
 * @param intentFor da cosa il viewer fa con una direzione ([dy] > 0 e' verso il basso); `null` = niente per questa direzione.
 */
@Composable
internal fun Modifier.viewerVerticalDrag(
  enabled: () -> Boolean,
  intentFor: (dy: Float) -> VerticalIntent?,
  onStart: (VerticalIntent) -> Unit,
  onDrag: (VerticalIntent, Offset) -> Unit,
  onEnd: (VerticalIntent, velocityY: Float) -> Unit,
  onCancel: (VerticalIntent) -> Unit,
): Modifier {
  val enabledNow by rememberUpdatedState(enabled)
  val intentForNow by rememberUpdatedState(intentFor)
  val onStartNow by rememberUpdatedState(onStart)
  val onDragNow by rememberUpdatedState(onDrag)
  val onEndNow by rememberUpdatedState(onEnd)
  val onCancelNow by rememberUpdatedState(onCancel)
  val slop = LocalViewConfiguration.current.touchSlop
  return pointerInput(slop) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      val tracker = VelocityTracker()
      tracker.addPosition(down.uptimeMillis, down.position)
      var total = Offset.Zero
      var intent: VerticalIntent? = null
      var finished = false
      // Un trascinamento verticale che il viewer non usa (su tablet lo swipe in su): non e' un tocco. Compose conta come
      // tocco anche un dito che si muove, se nessuno consuma e resta dentro i bordi: lo si consuma qui, o telephoto
      // lo prenderebbe per un tocco sulla foto e nasconderebbe le barre.
      var dead = false
      try {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id } ?: break
          val fingers = event.changes.count { it.pressed }
          val claimed = intent
          if (claimed == null && fingers > 1) break
          tracker.addPosition(change.uptimeMillis, change.position)
          if (claimed != null && (fingers > 1 || !change.pressed)) {
            finished = true
            onEndNow(claimed, tracker.calculateVelocity().y)
            break
          }
          if (!change.pressed) break
          val delta = change.positionChange()
          total += delta
          if (dead) {
            change.consume()
            continue
          }
          if (claimed == null) {
            if (change.isConsumed) break
            val dx = abs(total.x)
            val dy = abs(total.y)
            if (dx > slop && dx >= dy) break
            if (dy > slop && dy > dx * VerticalBias && enabledNow()) {
              val chosen = intentForNow(total.y)
              if (chosen == null) {
                dead = true
                change.consume()
                continue
              }
              intent = chosen
              onStartNow(chosen)
              // Il primo passo parte da dove la soglia e' stata superata: niente salto sotto il dito.
              onDragNow(chosen, Offset(0f, total.y - sign(total.y) * slop))
              change.consume()
            }
          } else {
            onDragNow(claimed, delta)
            change.consume()
          }
        }
      } finally {
        val claimed = intent
        if (claimed != null && !finished) onCancelNow(claimed)
      }
    }
  }
}

/**
 * La pressione lunga della motion photo: [onHold] quando il dito e' rimasto fermo per il tempo della pressione lunga di
 * sistema, [onRelease] quando si alza (o quando un secondo dito arriva: da li' e' un pizzico).
 *
 * **Non consuma niente**: lo osserva soltanto, nel passaggio `Initial`. Il tocco singolo (barre) e il doppio tocco
 * (zoom) restano di telephoto, e un dito che si muove oltre la soglia non e' mai una pressione lunga (e' un trascinamento
 * del pager o dello zoom). Per questo la pressione lunga di telephoto (`onLongClick`) non basta: non dice quando il dito
 * si alza, e la motion photo si ferma proprio li'.
 */
@Composable
internal fun Modifier.holdToPlay(
  enabled: () -> Boolean,
  onHold: () -> Unit,
  onRelease: () -> Unit,
): Modifier {
  val enabledNow by rememberUpdatedState(enabled)
  val onHoldNow by rememberUpdatedState(onHold)
  val onReleaseNow by rememberUpdatedState(onRelease)
  val configuration = LocalViewConfiguration.current
  val slop = configuration.touchSlop
  val timeout = configuration.longPressTimeoutMillis
  return pointerInput(slop, timeout) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      if (!enabledNow()) return@awaitEachGesture
      val held = withTimeoutOrNull(timeout) {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
          if (!change.pressed || event.changes.count { it.pressed } > 1) return@withTimeoutOrNull true
          if ((change.position - down.position).getDistance() > slop) return@withTimeoutOrNull true
        }
      }
      // withTimeoutOrNull restituisce null solo allo scadere del tempo: il dito e' ancora li', fermo.
      if (held != null) return@awaitEachGesture
      onHoldNow()
      try {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val change = event.changes.firstOrNull { it.id == down.id }
          if (change == null || !change.pressed || event.changes.count { it.pressed } > 1) break
        }
      } finally {
        onReleaseNow()
      }
    }
  }
}
