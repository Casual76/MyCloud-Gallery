package dev.pampa.mycloudgallery.core.designsystem.adaptive

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// IL GESTO DEL TRASCINAMENTO VERSO UN'ALTRA APP (Fase 10, P6; docs/fase10-progetto.md §1.2 "core-designsystem/adaptive", §2.5, D20, E-T12, E-T13, E-T17, E-T18).
// Solo il gesto: i dati li da' la porta [MediaDragPort] (URI del provider dei documenti, sola lettura sul singolo URI), preparati **dopo** la pressione lunga e
// **prima** che il sistema cominci il trascinamento. Mai dai Nascosti: le loro celle non hanno questo modificatore (E-T18).
//
// **Come parte.** Un `pointerInput` nel passaggio `Initial`, che **non consuma niente finche' non scatta** (le griglie e il viewer hanno i loro gesti: tocco, selezione,
// pressione lunga del testo): al tocco chiede alla schermata cosa si trascinerebbe ([DragSubject]); se niente, non fa niente. Poi:
//  - **tocco e penna**: pressione lunga ferma (il tempo di sistema); un dito che si muove prima e' uno scorrimento, due dita sono un pizzico;
//  - **mouse**: tasto principale, trascinando oltre la soglia **o** tenendolo premuto fermo (D15: il clic destro e' un'altra cosa e non parte).
// Scattato, **consuma il resto del gesto** (la lista non scorre sotto il dito) e prepara con la porta: se serve piu' di 300 ms la porta mostra "Preparo..." e **il
// trascinamento parte quando e' pronto**, purche' il dito sia ancora giu'. Un dito alzato prima annulla la preparazione (le copie fatte si tolgono, E-T17); se il
// sistema non accetta il gesto, o la porta dice no, [MediaDrag.discard] toglie subito le copie e gli id.

/**
 * Rende trascinabile verso un'altra app l'elemento. [subject] e' chiamato **al tocco** (prima della pressione lunga: la selezione e' quella di quel momento) con la
 * posizione nelle coordinate di chi porta il modificatore, e da' cio' che si trascina, o null (niente trascinamento da qui). [origin] e' il codice di `DragOrigin`.
 * Con [enabled] falso, o senza una porta installata ([LocalMediaDragPort], [MediaDragPorts]), il modificatore non fa niente.
 */
@Composable
fun Modifier.mediaDragSource(enabled: Boolean, origin: String, subject: (DragTouch) -> DragSubject?): Modifier {
  val port = LocalMediaDragPort.current ?: MediaDragPorts.installed
  val view = LocalView.current
  val configuration = LocalViewConfiguration.current
  val density = LocalDensity.current
  val colors = MaterialTheme.colorScheme
  val shadowPx = with(density) { SHADOW_SIDE.roundToPx() }
  val injected = LocalMediaDragStarter.current
  val subjectNow by rememberUpdatedState(subject)
  val originNow by rememberUpdatedState(origin)
  if (!enabled || port == null) return this
  val slop = configuration.touchSlop
  val timeout = configuration.longPressTimeoutMillis
  return pointerInput(port, injected, colors, shadowPx, slop, timeout) {
    coroutineScope {
      val scope = this
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val pointer = when (down.type) {
          PointerType.Mouse -> DragPointer.MOUSE
          PointerType.Stylus, PointerType.Eraser -> DragPointer.PEN
          else -> DragPointer.TOUCH
        }
        // Il clic destro e' il menu della cella (D15), non un trascinamento.
        if (pointer == DragPointer.MOUSE && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        val picked = subjectNow(DragTouch(down.position, pointer)) ?: return@awaitEachGesture
        if (picked.ids.isEmpty()) return@awaitEachGesture
        if (!awaitDragStart(down, slop, timeout, fireOnMove = pointer == DragPointer.MOUSE)) return@awaitEachGesture

        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        var held = true
        val job = scope.launch {
          val drag = port.prepare(originNow, picked.ids) ?: return@launch
          // Il dito si e' alzato mentre si preparava (la porta non ha guardato la cancellazione): niente parte e niente resta.
          if (!held) {
            drag.discard()
            return@launch
          }
          // L'avvio vero si costruisce solo ora (una cella non paga niente finche' non si trascina): i colori dell'ombra sono quelli del tema di adesso.
          val starter = injected ?: ViewMediaDragStarter(view, shadowPx, ShadowColors(colors.surfaceVariant.toArgb(), colors.primary.toArgb(), colors.onPrimary.toArgb()))
          if (!starter.start(drag, MediaDragShadow(picked.thumbnail, drag.count))) drag.discard()
        }
        try {
          // Da qui il gesto e' nostro: si consuma finche' il dito e' giu' (la lista non scorre, il rilascio non e' un tocco). Se il sistema comincia il trascinamento,
          // annulla lui il gesto e il ciclo finisce.
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            event.changes.forEach { it.consume() }
            if (change == null || !change.pressed) break
          }
        } finally {
          held = false
          if (job.isActive) job.cancel()
        }
      }
    }
  }
}

/**
 * Aspetta che il gesto scatti. Falso se si annulla: il dito si alza o si muove oltre la soglia prima del tempo (per il tocco: e' uno scorrimento), un secondo dito
 * (e' un pizzico). Vero allo scadere del tempo di pressione lunga con il dito fermo, e (con [fireOnMove], il mouse) anche appena si trascina oltre la soglia.
 */
private suspend fun AwaitPointerEventScope.awaitDragStart(down: PointerInputChange, slop: Float, timeoutMs: Long, fireOnMove: Boolean): Boolean {
  val outcome = withTimeoutOrNull(timeoutMs) {
    var verdict: Boolean? = null
    while (verdict == null) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      val change = event.changes.firstOrNull { it.id == down.id }
      verdict = when {
        change == null || !change.pressed || event.changes.count { it.pressed } > 1 -> false
        fireOnMove && event.buttons.isSecondaryPressed -> false
        (change.position - down.position).getDistance() > slop -> fireOnMove
        else -> null
      }
    }
    verdict
  }
  // `withTimeoutOrNull` da' null solo allo scadere del tempo: il dito e' ancora li', fermo.
  return outcome ?: true
}

/** Il lato dell'ombra: una miniatura da cella (circa 96 dp). */
private val SHADOW_SIDE = 96.dp
