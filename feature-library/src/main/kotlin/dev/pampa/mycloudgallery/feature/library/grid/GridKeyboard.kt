package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryKey
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryKeys
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// TASTIERA E MOUSE NELLA GRIGLIA (Fase 10, P4, D15, E-T10; docs/fase10-progetto.md §2.4): passaggio del puntatore = cella evidenziata, Invio = apri, Ctrl+A = seleziona tutto,
// Canc = **apre il foglio di conferma** (mai un'eliminazione diretta). Il clic destro e la rotella stanno altrove: il clic destro in `gridGestures`, la rotella e' lo scorrimento
// della lista (gia' suo). Esc = Indietro lo fa `MainActivity` per tutta l'app.

/** Cosa fanno i tasti della griglia fuori dalla griglia stessa. */
internal class GridKeyCallbacks(
  /** Invio sull'elemento evidenziato, o sull'unico selezionato. */
  val onOpen: (MediaId) -> Unit,
  /** Canc: apre il foglio di conferma dell'eliminazione della selezione. **Mai** elimina. Null: questa griglia non elimina (Canc non fa niente). */
  val onDeleteSheet: (() -> Unit)?,
)

/**
 * La cella evidenziata dal puntatore del mouse (D15) e cio' che fanno i tasti. Non e' stato di composizione: la legge solo chi preme un tasto. La cella si evidenzia con la
 * stessa **pressione parziale** che le celle gia' disegnano ([GalleryGridInteractionState]): nessun disegno nuovo nella cella (`GalleryGridCell` non cambia).
 */
internal class GridPointerKeys(private val state: MediaGridState, private val scope: CoroutineScope) {

  /** La cella sotto il mouse adesso, o null. */
  var hovered: MediaId? = null
    private set

  private var lastPosition: Offset? = null

  /** Il mouse e' sopra [position] (pixel della griglia). */
  fun moveTo(position: Offset) {
    lastPosition = position
    val hit = state.positionAt(position)
    setHovered(if (hit >= 0) state.idAt(hit) else null)
  }

  /** Il mouse e' uscito dalla griglia. */
  fun clear() {
    lastPosition = null
    setHovered(null)
  }

  /** Dopo un gesto o uno scorrimento la cella sotto il mouse puo' essere un'altra, anche se il mouse non si e' mosso. */
  fun refresh() {
    lastPosition?.let { moveTo(it) }
  }

  private fun setHovered(next: MediaId?) {
    if (next == hovered) return
    val previous = hovered
    hovered = next
    val interaction = state.interaction
    // Un dito o un clic veri hanno la precedenza: l'evidenziazione c'e' solo se la cella premuta e' la nostra (o nessuna).
    val busy = interaction.pressedKey != null && interaction.pressedKey != previous
    if (busy) return
    if (next == null) {
      interaction.cancelPress()
    } else {
      scope.launch {
        interaction.pressedKey = next
        interaction.pressProgress.snapTo(HoverProgress)
      }
    }
  }

  /**
   * Un tasto della griglia (D15). Vero se l'ha consumato. **Nessun tasto elimina**: Canc apre il foglio, che e' quello della barra delle azioni.
   *  - Invio: apre la cella evidenziata o l'unica selezionata; senza nessuna delle due non fa niente.
   *  - Ctrl+A: modalita' selezione con tutta la vista.
   *  - Canc: con una selezione apre il foglio; senza, sulla cella evidenziata la seleziona e apre il foglio; altrimenti non fa niente.
   */
  fun onKey(key: GalleryKey, callbacks: GridKeyCallbacks): Boolean = when (key) {
    GalleryKey.OPEN -> {
      val id = hovered ?: state.selection.selected.value.ids.singleOrNull()
      if (id != null) callbacks.onOpen(id)
      id != null
    }
    GalleryKey.SELECT_ALL -> {
      state.selectAllInView()
      true
    }
    GalleryKey.DELETE -> {
      val openSheet = callbacks.onDeleteSheet
      val selected = state.selection.selected.value.ids
      val target = hovered
      when {
        openSheet == null -> false
        selected.isNotEmpty() -> {
          openSheet()
          true
        }
        target != null -> {
          state.startSelecting()
          state.selection.toggle(target)
          openSheet()
          true
        }
        else -> false
      }
    }
    GalleryKey.PREVIOUS, GalleryKey.NEXT -> false
  }

  companion object {
    /** Quanto si vede la cella sotto il mouse: una pressione a un terzo (la pressione piena e' per il clic). */
    const val HoverProgress: Float = 0.35f
  }
}

/**
 * Il mouse e la tastiera della griglia: il puntatore evidenzia, i tasti comandano. La griglia prende il **fuoco** quando il mouse la tocca o ci si clicca (mai da sola, per non
 * rubarlo a un campo di testo di un'altra scheda); i tasti arrivano solo se nessun campo di testo li ha gia' consumati.
 */
@Composable
internal fun Modifier.gridKeyboard(state: MediaGridState, callbacks: GridKeyCallbacks): Modifier {
  val focus = remember { FocusRequester() }
  val scope = rememberCoroutineScope()
  val keys = remember(state, scope) { GridPointerKeys(state, scope) }
  val currentCallbacks by rememberUpdatedState(callbacks)
  return this
    .focusRequester(focus)
    .onKeyEvent { event ->
      val key = GalleryKeys.of(event) ?: return@onKeyEvent false
      keys.onKey(key, currentCallbacks)
    }
    .focusTarget()
    .pointerInput(state, keys) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val change = event.changes.firstOrNull() ?: continue
          // Un tocco prende il fuoco (con una tastiera hardware e il dito i tasti funzionano), ma non evidenzia niente: l'evidenziazione e' del solo mouse.
          if (change.type != PointerType.Mouse) {
            keys.clear()
            if (event.type == PointerEventType.Press) runCatching { focus.requestFocus() }
            continue
          }
          when (event.type) {
            PointerEventType.Enter, PointerEventType.Move -> {
              runCatching { focus.requestFocus() }
              keys.moveTo(change.position)
            }
            PointerEventType.Press -> runCatching { focus.requestFocus() }
            PointerEventType.Release -> keys.moveTo(change.position)
            PointerEventType.Scroll -> scope.launch {
              // La lista scorre sotto il mouse fermo: la cella evidenziata e' un'altra, ma solo dopo il fotogramma.
              withFrameNanos { }
              keys.refresh()
            }
            PointerEventType.Exit -> keys.clear()
          }
        }
      }
    }
}
