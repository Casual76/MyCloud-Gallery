package dev.pampa.mycloudgallery.feature.viewer.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.livetext.TextPosition
import kotlinx.coroutines.withTimeoutOrNull

// L'ARBITRO DELLA PRESSIONE PROLUNGATA (docs/fase6-progetto.md §2.10, D45; Fase 6, P12).
//
// Un solo gesto, deciso da **dove cade il dito**: su una riga di testo letto e' il testo dal vivo (P12); altrove su una foto e' il
// ritaglio del soggetto (P13); su un video niente. Le foto in movimento si comportano come le altre foto (E-V12): il video parte solo
// dal badge "Movimento" (D51). La decisione e' pura ([LongPressArbiter.decide]); il tempo e le dita li misura [viewerLongPress].
//
// **Come si aggancia P13.** `ViewerController.onSubjectLongPress` e' vuoto: P13 lo imposta (punto normalizzato 0..1 sull'immagine
// raddrizzata, dopo la trasformazione dello zoom) e l'arbitro gli passa tutte le pressioni prolungate su una foto che non cadono sul
// testo. Non serve toccare questo file.

/** Cosa fa una pressione prolungata. */
internal sealed interface LongPressOutcome {

  /** Cade su una parola di testo letto: si seleziona. */
  data class Text(val position: TextPosition) : LongPressOutcome

  /** Cade su una foto fuori dal testo: il ritaglio (P13), con il punto normalizzato sull'immagine raddrizzata. */
  data class Subject(val x: Float, val y: Float) : LongPressOutcome

  /** Niente: un video, un punto fuori dall'immagine, nessuna funzione acceso qui. */
  data object None : LongPressOutcome
}

/** Il testo sotto un punto (normalizzato): la posizione della parola, o null. Puo' leggere al volo (D42), ma solo se la lettura e' attiva. */
internal fun interface TextClaim {
  suspend fun claim(point: Offset): TextPosition?
}

/** La decisione della tabella "Gesti" (§2.10). */
internal object LongPressArbiter {

  /**
   * @param kind il tipo di media sotto il dito; null = non si sa (nessun gesto).
   * @param point il punto, normalizzato (0..1) sull'immagine raddrizzata, **dopo** la trasformazione dello zoom.
   * @param text il testo letto sotto il dito; null = la funzione non c'e' in questo viewer (D13).
   * @param subject il ritaglio e' collegato (P13)?
   */
  suspend fun decide(kind: MediaKind?, point: Offset, text: TextClaim?, subject: Boolean): LongPressOutcome {
    if (kind == null || kind == MediaKind.VIDEO) return LongPressOutcome.None
    if (point.x !in 0f..1f || point.y !in 0f..1f) return LongPressOutcome.None
    val onText = text?.claim(point)
    return when {
      onText != null -> LongPressOutcome.Text(onText)
      subject -> LongPressOutcome.Subject(point.x, point.y)
      else -> LongPressOutcome.None
    }
  }
}

/**
 * La pressione prolungata, come la misurava `holdToPlay`: nel passaggio `Initial`, senza consumare finche' non scade il tempo di
 * sistema. Un secondo dito (e' un pizzico) o un movimento oltre la soglia **annullano**: e' un trascinamento del pager o dello zoom.
 *
 * Scaduto il tempo chiama [onLongPress] con la posizione del dito (nelle coordinate di chi porta il modificatore) e la sua misura;
 * da li' **consuma** il resto del gesto, cosi' il rilascio non diventa un tocco sulla foto (che nasconderebbe le barre) e il pager non
 * si muove. La soglia di movimento e' in pixel dello schermo: l'arbitro sta fuori dalla trasformazione dello zoom.
 */
@Composable
internal fun Modifier.viewerLongPress(
  enabled: () -> Boolean,
  onLongPress: (position: Offset, size: IntSize) -> Unit,
): Modifier {
  val enabledNow by rememberUpdatedState(enabled)
  val onLongPressNow by rememberUpdatedState(onLongPress)
  val configuration = LocalViewConfiguration.current
  val slop = configuration.touchSlop
  val timeout = configuration.longPressTimeoutMillis
  return pointerInput(slop, timeout) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      if (!enabledNow()) return@awaitEachGesture
      var position = down.position
      val cancelled = withTimeoutOrNull(timeout) {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
          position = change.position
          if (!change.pressed || event.changes.count { it.pressed } > 1) return@withTimeoutOrNull true
          if ((change.position - down.position).getDistance() > slop) return@withTimeoutOrNull true
        }
      }
      // `withTimeoutOrNull` restituisce null solo allo scadere del tempo: il dito e' ancora li', fermo.
      if (cancelled != null) return@awaitEachGesture
      onLongPressNow(position, size)
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id }
        event.changes.forEach { it.consume() }
        if (change == null || !change.pressed) break
      }
    }
  }
}
