package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId

// IL VIEWER AFFIANCATO ALLA LIBRERIA (Fase 10, P5, D13 (5); docs/fase10-progetto.md §2.3). Da finestra ampia (>= 1200 dp) e solo a scelta, il viewer sta a destra al 60% e la Libreria a sinistra
// al 40%, viva: lo scorrimento della griglia resta, un tocco su una cella cambia la foto del viewer **senza animazione d'apertura** (`ViewerJump`). Il viewer non conosce la finestra ne' le
// preferenze: chi lo ospita (`:app`) gli da' questa porta, e il viewer offre "Affianca" / "Schermo intero" nel suo menu e segue i salti. Senza porta (test, anteprime) niente di tutto questo.

/** Un cambio di foto chiesto dalla griglia a lato: [serial] distingue due richieste sulla stessa foto. Mai l'id nel `toString()` (C-log). */
@Immutable
data class ViewerJump(val media: MediaId, val serial: Long) {
  override fun toString(): String = "ViewerJump#$serial"
}

/** La porta del viewer affiancato, data dall'ospite del viewer. */
@Stable
interface ViewerDock {
  /** "Affianca" si puo' offrire: finestra ampia e viewer aperto dalla Libreria (mai dai Nascosti, mai da altre griglie). */
  val offered: Boolean

  /** Il viewer e' affiancato alla Libreria adesso. */
  val docked: Boolean

  /** "Affianca" ([docked] vero) o "Schermo intero" (falso). La scelta si ricorda per profilo (DataStore locale della Libreria). */
  fun chooseDocked(docked: Boolean)

  /** L'ultimo cambio di foto chiesto dalla griglia a lato, finche' il viewer non l'ha eseguito. */
  val jump: ViewerJump?

  /** Il viewer ha eseguito (o scartato) [jump]. */
  fun jumpHandled(jump: ViewerJump)
}

/** La porta del viewer affiancato della finestra che si sta componendo; null = non c'e' (test, anteprime). La mette l'ospite del viewer. */
val LocalViewerDock = staticCompositionLocalOf<ViewerDock?> { null }

/** Dove sta una foto in una vista: la pagina del viewer, o null se la vista non l'ha (un nascosto non e' mai nella vista viva). */
object ViewerJumps {
  fun pageOf(view: TimelineView, media: MediaId): Int? = view.viewIndexOf(media).takeIf { it >= 0 }
}

/** La voce del menu "Affianca" / "Schermo intero": [docked] dice quale delle due si offre. */
internal class ViewerDockEntry(val docked: Boolean, val onToggle: () -> Unit)

/** La voce da offrire adesso, o null: serve la porta, che offra (o sia gia' affiancata), e mai per i Nascosti. */
internal fun ViewerDock?.entry(secure: Boolean): ViewerDockEntry? {
  val dock = this ?: return null
  if (secure || !(dock.offered || dock.docked)) return null
  return ViewerDockEntry(docked = dock.docked, onToggle = { dock.chooseDocked(!dock.docked) })
}
