package dev.pampa.mycloudgallery.core.designsystem.adaptive

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

// TASTIERA PER LE SCHERMATE (Fase 10, P4, D15, E-T10; docs/fase10-progetto.md §0.1 D15, §2.4). **File nuovo** in `adaptive/`. Una tabella sola dei tasti, per la Libreria e per il viewer, provata
// sulla JVM: una tastiera hardware (DeX, tablet con tastiera) comanda l'app senza che nessun tasto faccia una cosa distruttiva da solo.
// Esc = Indietro non e' qui: lo fa `MainActivity` per tutta l'app (come il gesto Indietro), cosi' vale anche con un foglio aperto.

/** I comandi da tastiera delle schermate (D15). */
enum class GalleryKey {
  /** Invio: apre l'elemento evidenziato o, se ce n'e' uno solo selezionato, quello. */
  OPEN,

  /** Ctrl+A (Cmd+A): seleziona tutto nella griglia. */
  SELECT_ALL,

  /** Canc: **apre il foglio di conferma** dell'eliminazione, mai un'eliminazione diretta (D15). */
  DELETE,

  /** Freccia sinistra: la foto prima, nel viewer. */
  PREVIOUS,

  /** Freccia destra: la foto dopo, nel viewer. */
  NEXT,
}

object GalleryKeys {

  /**
   * Il comando di un tasto, o `null` se non e' nostro (e allora non si consuma, e un campo di testo con il fuoco lo riceve com'e').
   * Conta solo la pressione (`KeyDown`); i comandi che cambiano qualcosa ([GalleryKey.OPEN], [GalleryKey.SELECT_ALL], [GalleryKey.DELETE]) **non si ripetono** tenendo
   * premuto, le frecce si'. Un tasto con un modificatore che non gli appartiene (Maiusc, Alt) non e' il nostro.
   */
  fun of(event: KeyEvent): GalleryKey? {
    if (event.type != KeyEventType.KeyDown) return null
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    val others = event.isAltPressed || event.isShiftPressed
    if (others) return null
    val repeated = event.nativeRepeatCount > 0
    return when (event.key) {
      Key.Enter, Key.NumPadEnter -> if (!ctrl && !repeated) GalleryKey.OPEN else null
      Key.A -> if (ctrl && !repeated) GalleryKey.SELECT_ALL else null
      Key.Delete -> if (!ctrl && !repeated) GalleryKey.DELETE else null
      Key.DirectionLeft -> if (!ctrl) GalleryKey.PREVIOUS else null
      Key.DirectionRight -> if (!ctrl) GalleryKey.NEXT else null
      else -> null
    }
  }
}

/** Il numero di ripetizioni di un tasto tenuto premuto (0 alla prima pressione). */
private val KeyEvent.nativeRepeatCount: Int get() = nativeKeyEvent.repeatCount
