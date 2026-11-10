package dev.pampa.mycloudgallery.feature.viewer.gesture

import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryKey
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryKeys
import kotlin.math.pow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableState

// TASTIERA E MOUSE NEL VIEWER (Fase 10, P4, D15, E-T10; docs/fase10-progetto.md §2.4): frecce sinistra e destra = la foto prima e dopo, rotella = zoom. Esc = Indietro lo fa `MainActivity`
// per tutta l'app (il viewer ha gia' il suo gesto Indietro). Il clic destro non ha una voce nel viewer: la pressione prolungata li' e' la selezione del testo e del soggetto.

/** Cosa fanno le frecce nel viewer: [previous] e [next] spostano di una foto, e rispondono vero se l'hanno fatto (il viewer a riposo, c'e' una foto prima o dopo). */
internal class ViewerKeyCallbacks(val previous: () -> Boolean, val next: () -> Boolean)

/** Cosa fa un tasto nel viewer: vero se l'ha consumato. Funzione pura (provata); le sole frecce, e solo se la foto c'e'. */
internal fun viewerKey(key: GalleryKey, callbacks: ViewerKeyCallbacks): Boolean = when (key) {
  GalleryKey.PREVIOUS -> callbacks.previous()
  GalleryKey.NEXT -> callbacks.next()
  // Invio, Ctrl+A e Canc sono della griglia: nel viewer non fanno niente (e Canc non elimina mai da solo).
  GalleryKey.OPEN, GalleryKey.SELECT_ALL, GalleryKey.DELETE -> false
}

/**
 * Le frecce del viewer. Il viewer prende il **fuoco** quando si apre, cosi' i tasti arrivano senza un clic; i tasti che non sono delle frecce non si consumano (un campo di
 * testo di un foglio li riceve com'e'). Va messo sul contenitore del viewer, dopo che e' composto.
 *
 * @param holdsFocus falso mentre un pop-up del viewer ha il fuoco (il menu ⋮, K1): quando torna vero il viewer si riprende il fuoco, cosi' dopo il menu le frecce
 *   funzionano ancora senza un clic (il fuoco del pop-up se ne va con lui, e senza questo non resterebbe a nessuno). Si legge in un effetto, non nella composizione:
 *   aprire e chiudere il menu non ricompone il viewer.
 */
@Composable
internal fun Modifier.viewerKeyboard(callbacks: ViewerKeyCallbacks, holdsFocus: () -> Boolean = { true }): Modifier {
  val focus = remember { FocusRequester() }
  val current by rememberUpdatedState(callbacks)
  val holds by rememberUpdatedState(holdsFocus)
  LaunchedEffect(focus) {
    snapshotFlow { holds() }.collect { if (it) runCatching { focus.requestFocus() } }
  }
  return this
    .focusRequester(focus)
    .onKeyEvent { event ->
      val key = GalleryKeys.of(event) ?: return@onKeyEvent false
      viewerKey(key, current)
    }
    .focusTarget()
}

/**
 * La rotella del mouse ingrandisce e rimpicciolisce (D15), attorno al puntatore, senza animazione (un tick della rotella e' un passo piccolo e ravvicinato: animarli li
 * accoderebbe). Il tocco ha il suo pizzico. La rotella verso l'alto ingrandisce.
 */
internal fun Modifier.mouseWheelZoom(zoomable: ZoomableState, enabled: Boolean): Modifier {
  if (!enabled) return this
  return pointerInput(zoomable) {
    coroutineScope {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          if (event.type != PointerEventType.Scroll) continue
          // Qualunque puntatore che manda la rotella (un mouse, un touchpad): il dito non la manda mai.
          val change = event.changes.firstOrNull() ?: continue
          val factor = wheelZoomFactor(change.scrollDelta.y)
          if (factor == 1f) continue
          change.consume()
          launch { zoomable.zoomBy(factor, change.position, animationSpec = snap()) }
        }
      }
    }
  }
}

/** Il fattore di zoom di un passo della rotella ([delta]: negativo = verso l'alto = ingrandisce). Funzione pura; 1 per un passo nullo o non finito. */
internal fun wheelZoomFactor(delta: Float): Float {
  if (delta == 0f || !delta.isFinite()) return 1f
  // Un passo di rotella e' circa 1: 25% per passo; un'impennata (touchpad che manda molti passi insieme) e' limitata a 4 passi.
  val steps = delta.coerceIn(-MaxWheelSteps, MaxWheelSteps)
  return WheelZoomPerStep.pow(-steps)
}

private const val WheelZoomPerStep = 1.25f
private const val MaxWheelSteps = 4f
