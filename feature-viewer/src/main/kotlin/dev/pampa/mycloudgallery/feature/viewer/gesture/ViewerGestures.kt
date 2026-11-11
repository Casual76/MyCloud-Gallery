package dev.pampa.mycloudgallery.feature.viewer.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.math.abs
import kotlin.math.sign

/**
 * Che cosa fa un trascinamento verticale per il pannello informazioni: verso l'alto lo apre (sul telefono), verso il basso lo chiude, verso
 * l'alto a pannello aperto lo fa scorrere (H2: un pannello lungo cresce oltre la meta' e porta con se' la foto). Il pannello lo tratta come un
 * gesto solo ([dev.pampa.mycloudgallery.feature.viewer.info.InfoPanelState.drag]): l'intenzione dice solo da dove e' partito.
 */
internal enum class InfoIntent { Open, Close, Scroll }

/**
 * Chi prende un gesto sul contenuto del viewer. Lo decide [ViewerGestureArbiter] con la sola geometria, **prima dei figli** (il pager e lo
 * zoom), appena il dito supera la soglia di tocco.
 */
internal enum class GestureClaim {
  /** Il dito non ha ancora superato la soglia: non si sa. */
  Undecided,

  /** Non e' del viewer: lo prendono i figli (il pager di lato, lo zoom e lo spostamento a foto ingrandita, il pizzico che ingrandisce). */
  Children,

  /** La chiusura: il trascinamento verso il basso o il pizzico che stringe a zoom minimo. Una macchina sola ([ViewerDismissState]). */
  Dismiss,

  /** Il pannello informazioni: si apre (verso l'alto) o si chiude (verso il basso, a pannello aperto). */
  OpenInfo,
  CloseInfo,

  /** H2: a pannello aperto, il dito che sale sulla foto fa scorrere il pannello (cresce oltre la meta', poi le informazioni scorrono). */
  ScrollInfo,

  /** H2: due dita a pannello aperto (telefono): il pannello si chiude; ingrandire e' il gesto dopo, sulla foto intera. */
  InfoPinch,

  /**
   * Un trascinamento verticale che il viewer non usa (verso l'alto sul tablet, che ha la colonna laterale): non e' un tocco. Compose conta
   * come tocco anche un dito che si muove, se nessuno lo consuma: lo si consuma, o telephoto nasconderebbe le barre.
   */
  Dead,
}

/** Quello che il viewer sa quando decide di chi e' un gesto. */
@Immutable
internal data class GestureContext(
  /** Il viewer prende gesti: aperto, non in chiusura, nessuna selezione del testo ne' soggetto staccato aperti (D45, E-V13). */
  val enabled: Boolean,
  /** La foto (o il video) e' ingrandita: un dito la sposta, due la ingrandiscono o la rimpiccioliscono. */
  val zoomed: Boolean,
  val infoOpen: Boolean,
  /** Le informazioni stanno in una colonna laterale (da 840 dp): lo swipe in su non le apre. */
  val sidePanel: Boolean,
  /** Il contenuto sta tornando al suo posto dopo una chiusura annullata: solo un'altra chiusura puo' riprenderlo. */
  val returning: Boolean,
)

/**
 * **L'arbitraggio dei gesti del viewer** (H1), scritto in un posto solo. Chi prende cosa:
 *
 *  - **un dito, di lato** (prevale l'orizzontale): il pager, che passa all'elemento vicino; a foto ingrandita lo zoom, che la sposta fino al
 *    bordo e da li' lascia il resto al pager;
 *  - **un dito, verso il basso**: la chiusura (a pannello aperto: la chiusura del pannello);
 *  - **un dito, verso l'alto**: il pannello informazioni sul telefono; a pannello aperto lo fa scorrere (H2); con la colonna laterale
 *    (tablet, telefono in orizzontale) niente ([GestureClaim.Dead]), anche verso il basso: la colonna si chiude con il tasto Informazioni o
 *    con la sua X;
 *  - **due dita che si stringono a zoom minimo**: la chiusura, la stessa del trascinamento (il contenuto segue il punto medio delle dita);
 *  - **due dita a pannello aperto** (telefono): appena si appoggia il secondo dito il pannello si chiude ([GestureClaim.InfoPinch]) e il resto
 *    del gesto non e' di nessuno; lo zoom e' il gesto dopo, sulla foto intera;
 *  - **due dita che si allargano**, o qualsiasi gesto **a foto ingrandita**: lo zoom (telephoto);
 *  - **tocco, doppio tocco, pressione lunga**: non superano la soglia di movimento, quindi qui restano "non si sa" fino alla fine e vanno ai
 *    figli (tocco = barre, doppio tocco = zoom, pressione lunga = testo dal vivo o ritaglio);
 *  - **il gesto Indietro di sistema dal bordo**: e' orizzontale (quindi mai una chiusura o un pannello), e quando il sistema lo prende manda un
 *    annullamento con le dita gia' consumate, che [viewerGestures] tratta come tale (mai come un rilascio, mai come un tocco).
 *
 * Funzione pura: le prove la leggono come una tabella.
 *
 * @param pan lo spostamento del primo dito dall'inizio del gesto (per un dito solo).
 * @param spanChange quanto e' cambiata la misura delle dita (la distanza media dal punto medio, come la misura il pizzico di telephoto) da
 *   quando sono due: negativa = si stringono.
 */
internal object ViewerGestureArbiter {

  /**
   * Il pizzico che stringe si riconosce a meta' della soglia di tocco: prima di telephoto, che lo vede alla soglia intera. Cosi' il pizzico in
   * chiusura non passa mai per lo zoom (che a zoom minimo non rimpicciolisce comunque).
   */
  const val PinchSlopShare: Float = 0.5f

  fun decide(context: GestureContext, fingers: Int, pan: Offset, spanChange: Float, slop: Float): GestureClaim {
    if (!context.enabled || context.zoomed) return GestureClaim.Children
    if (fingers >= 2) {
      // H2: a pannello aperto il secondo dito che si appoggia chiude il pannello, subito (senza aspettare che le dita si muovano): lo zoom
      // non vede mai l'inizio di un pizzico su una foto spostata e tagliata. Con la colonna laterale la foto accanto e' intera: e' dello zoom.
      if (context.infoOpen) return if (context.sidePanel) GestureClaim.Children else GestureClaim.InfoPinch
      return when {
        spanChange <= -slop * PinchSlopShare -> GestureClaim.Dismiss
        spanChange >= slop * PinchSlopShare -> GestureClaim.Children
        else -> GestureClaim.Undecided
      }
    }
    val dx = abs(pan.x)
    val dy = abs(pan.y)
    if (dx > slop && dx >= dy) return GestureClaim.Children
    if (dy > slop && dy > dx) {
      if (pan.y > 0f) {
        return when {
          !context.infoOpen -> GestureClaim.Dismiss
          context.sidePanel -> GestureClaim.Dead
          else -> GestureClaim.CloseInfo
        }
      }
      return when {
        context.returning -> GestureClaim.Children
        context.sidePanel -> GestureClaim.Dead
        context.infoOpen -> GestureClaim.ScrollInfo
        else -> GestureClaim.OpenInfo
      }
    }
    return GestureClaim.Undecided
  }
}

/** Cosa fa il viewer dei gesti che [ViewerGestureArbiter] gli assegna. */
internal class ViewerGestureCallbacks(
  val context: () -> GestureContext,
  val onDismissStart: () -> Unit,
  /** Un passo: [pan] lo spostamento del punto medio, [zoom] il fattore delle dita (1 con un dito), [centroid] il punto medio adesso. */
  val onDismissMove: (pan: Offset, zoom: Float, centroid: Offset) -> Unit,
  /** Le dita si alzano: [velocity] del punto medio in px/s, [pinchRate] del pizzico in frazioni al secondo (negativa = stringe). */
  val onDismissEnd: (velocity: Offset, pinchRate: Float) -> Unit,
  val onDismissCancel: () -> Unit,
  val onInfoDrag: (InfoIntent, dy: Float) -> Unit,
  val onInfoEnd: (InfoIntent, velocityY: Float) -> Unit,
  val onInfoCancel: (InfoIntent) -> Unit,
  /** H2: due dita a pannello aperto: il pannello si chiude (una volta per gesto). */
  val onInfoPinch: () -> Unit = {},
)

/**
 * I gesti del viewer sul contenuto, **osservati prima dei figli** (passaggio `Initial`): il pager e lo zoom stanno sotto e vedono il gesto
 * solo se questo non lo reclama ([ViewerGestureArbiter]). Reclamato, ogni cambiamento delle dita viene consumato: il pager e lo zoom lo
 * vedono gia' consumato e non reagiscono (il pizzico di telephoto, se era partito, si ferma).
 *
 * Perche' `Initial` e non il passaggio normale: i figli leggono per primi, e un pager che ha gia' preso un dito che sta andando in diagonale
 * non lo molla piu'.
 *
 * Il modificatore va messo **prima** del `graphicsLayer` che sposta il contenuto: le posizioni delle dita sono quelle dello schermo, non
 * quelle del contenuto che si rimpicciolisce sotto di loro.
 */
@Composable
internal fun Modifier.viewerGestures(callbacks: ViewerGestureCallbacks): Modifier {
  val latest by rememberUpdatedState(callbacks)
  val slop = LocalViewConfiguration.current.touchSlop
  return pointerInput(slop) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      val cb = latest
      var claim = GestureClaim.Undecided
      var pan = Offset.Zero
      var spanStart = -1f
      var finished = false
      // La velocita' del punto medio: le sue posizioni sommano solo gli spostamenti delle dita rimaste giu' (`calculatePan`), cosi' un dito
      // che si aggiunge o si alza non fa saltare la velocita'.
      val tracker = VelocityTracker()
      var virtual = down.position
      tracker.addPosition(down.uptimeMillis, virtual)
      val pinchTracker = VelocityTracker1D(isDataDifferential = false)
      var pinchTotal = 1f
      pinchTracker.addDataPoint(down.uptimeMillis, pinchTotal)
      var infoIntent = InfoIntent.Open
      try {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val pressed = event.changes.count { it.pressed }
          val time = event.changes.firstOrNull()?.uptimeMillis ?: 0L
          // Un annullamento (il sistema prende il gesto Indietro dal bordo, un'altra finestra prende le dita): Compose fa "alzare" le dita gia'
          // consumate. Non e' un rilascio: il contenuto torna al suo posto, niente chiude.
          val canceled = pressed == 0 && event.changes.all { it.isConsumed }
          when (claim) {
            GestureClaim.Undecided -> {
              if (pressed == 0) break
              val first = event.changes.firstOrNull { it.id == down.id }
              if (first == null || first.isConsumed) break
              var spanChange = 0f
              if (pressed >= 2) {
                val size = event.calculateCentroidSize(useCurrent = true)
                if (size > 0f && spanStart < 0f) spanStart = size
                if (spanStart > 0f) spanChange = size - spanStart
              } else {
                pan += first.positionChange()
              }
              when (val next = ViewerGestureArbiter.decide(cb.context(), pressed, pan, spanChange, slop)) {
                GestureClaim.Undecided -> Unit
                GestureClaim.Children -> break
                GestureClaim.Dead -> {
                  claim = next
                  event.changes.forEach { it.consume() }
                }
                GestureClaim.InfoPinch -> {
                  // Il resto del gesto non e' di nessuno: il pannello si chiude e le dita non ingrandiscono la foto che sta tornando.
                  claim = GestureClaim.Dead
                  cb.onInfoPinch()
                  event.changes.forEach { it.consume() }
                }
                GestureClaim.Dismiss -> {
                  claim = next
                  // Le velocita' si misurano da qui: il punto medio delle dita (con due dita non e' dove si e' appoggiato il primo dito).
                  val centroid = event.calculateCentroid(useCurrent = true)
                  virtual = if (centroid.isSpecified) centroid else first.position
                  tracker.resetTracking()
                  tracker.addPosition(time, virtual)
                  pinchTracker.resetTracking()
                  pinchTotal = 1f
                  pinchTracker.addDataPoint(time, pinchTotal)
                  cb.onDismissStart()
                  // Il primo passo parte da dove la soglia e' stata superata: niente salto sotto il dito.
                  if (pressed == 1) cb.onDismissMove(Offset(0f, pan.y - sign(pan.y) * slop), 1f, first.position)
                  event.changes.forEach { it.consume() }
                }
                GestureClaim.OpenInfo, GestureClaim.CloseInfo, GestureClaim.ScrollInfo -> {
                  claim = next
                  infoIntent = when (next) {
                    GestureClaim.OpenInfo -> InfoIntent.Open
                    GestureClaim.CloseInfo -> InfoIntent.Close
                    else -> InfoIntent.Scroll
                  }
                  tracker.resetTracking()
                  tracker.addPosition(first.uptimeMillis, first.position)
                  cb.onInfoDrag(infoIntent, pan.y - sign(pan.y) * slop)
                  first.consume()
                }
              }
            }
            GestureClaim.Dead -> {
              event.changes.forEach { it.consume() }
              if (pressed == 0) break
            }
            GestureClaim.Dismiss -> {
              if (canceled) {
                finished = true
                cb.onDismissCancel()
                break
              }
              val panStep = event.calculatePan()
              val zoomStep = event.calculateZoom()
              virtual += panStep
              tracker.addPosition(time, virtual)
              pinchTotal *= zoomStep
              pinchTracker.addDataPoint(time, pinchTotal)
              if (pressed == 0) {
                finished = true
                val velocity = tracker.calculateVelocity()
                cb.onDismissEnd(Offset(velocity.x, velocity.y), pinchTracker.calculateVelocity())
                break
              }
              val centroid = event.calculateCentroid(useCurrent = true)
              if (centroid.isSpecified) cb.onDismissMove(panStep, zoomStep, centroid)
              event.changes.forEach { it.consume() }
            }
            GestureClaim.OpenInfo, GestureClaim.CloseInfo, GestureClaim.ScrollInfo -> {
              val change = event.changes.firstOrNull { it.id == down.id }
              if (canceled || change == null) {
                finished = true
                cb.onInfoCancel(infoIntent)
                break
              }
              tracker.addPosition(change.uptimeMillis, change.position)
              // Una seconda dita, o il dito che si alza: il pannello va dove lo porta la velocita'.
              if (pressed > 1 || !change.pressed) {
                finished = true
                cb.onInfoEnd(infoIntent, tracker.calculateVelocity().y)
                break
              }
              cb.onInfoDrag(infoIntent, change.positionChange().y)
              change.consume()
            }
            GestureClaim.Children, GestureClaim.InfoPinch -> break
          }
        }
      } finally {
        if (!finished) {
          when (claim) {
            GestureClaim.Dismiss -> cb.onDismissCancel()
            GestureClaim.OpenInfo, GestureClaim.CloseInfo, GestureClaim.ScrollInfo -> cb.onInfoCancel(infoIntent)
            else -> Unit
          }
        }
      }
    }
  }
}

/**
 * Ogni dito che si appoggia sulla pagina, osservato **senza consumare niente** (passaggio `Initial`): [onTouch] a ogni primo dito
 * giu' mentre [enabled]. Lo usa l'animazione d'apertura delle foto in movimento (D50, E-V14): un tocco, un pizzico, un doppio tocco o
 * l'inizio di un trascinamento la fermano subito, e poi il gesto vale come sempre (tocco singolo, doppio tocco e zoom restano di
 * telephoto, il pager e il trascinamento verso il basso restano del loro gesto).
 *
 * **Non e' una pressione lunga**: D45 toglie la riproduzione dalla pressione lunga, che resta libera per il testo dal vivo e il
 * ritaglio (P12, P13). Qui non si misura nessun tempo.
 */
@Composable
internal fun Modifier.onTouchDown(
  enabled: () -> Boolean,
  onTouch: () -> Unit,
): Modifier {
  val enabledNow by rememberUpdatedState(enabled)
  val onTouchNow by rememberUpdatedState(onTouch)
  return pointerInput(Unit) {
    awaitEachGesture {
      awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      if (enabledNow()) onTouchNow()
      // Si aspetta che tutte le dita si alzino: un secondo dito (il pizzico) e' lo stesso gesto, non ne comincia un altro.
      do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
      } while (event.changes.any { it.pressed })
    }
  }
}

/**
 * Osserva ogni gesto sulla pagina **senza consumare niente** (passaggio `Initial`) e dice se il dito si e' mosso: [onDown] a ogni primo dito
 * giu', [onMoved] la prima volta che nello stesso gesto si supera la soglia di movimento o arriva un secondo dito (un pizzico).
 *
 * Serve al testo dal vivo (D45, E-V13): Compose conta come tocco anche un trascinamento che nessuno consuma, e con una selezione aperta
 * quel "tocco" chiuderebbe la selezione. Chi decide cosa fare di un tocco chiede prima se il gesto era un movimento.
 */
@Composable
internal fun Modifier.touchMovement(
  onDown: () -> Unit,
  onMoved: () -> Unit,
): Modifier {
  val onDownNow by rememberUpdatedState(onDown)
  val onMovedNow by rememberUpdatedState(onMoved)
  val slop = LocalViewConfiguration.current.touchSlop
  return pointerInput(slop) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      onDownNow()
      var moved = false
      do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (!moved) {
          val change = event.changes.firstOrNull { it.id == down.id }
          if (event.changes.count { it.pressed } > 1 || (change != null && (change.position - down.position).getDistance() > slop)) {
            moved = true
            onMovedNow()
          }
        }
      } while (event.changes.any { it.pressed })
    }
  }
}
