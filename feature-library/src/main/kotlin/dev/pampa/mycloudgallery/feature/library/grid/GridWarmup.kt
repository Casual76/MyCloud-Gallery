package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.ui.unit.Dp
import dev.pampa.mycloudgallery.core.common.perf.AvvioTrace
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.grid.forWidth
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridLevels
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * **Il layout della Libreria preparato prima della prima composizione** (Avvio istantaneo, 28/10).
 *
 * Una vista grande (100 mila elementi) si impagina fuori dal thread principale ([GridLayoutStore]): al primo avvio la griglia
 * nasceva vuota e le celle arrivavano un fotogramma dopo, quando il primo fotogramma (quello che compila la grafica) era finito:
 * sull'S25 (28/10) la griglia compariva a 540 ms dal tocco, 340 ms dopo la prima composizione. Qui l'app impagina il livello
 * ricordato **mentre il processo nasce** (subito dopo l'istantanea su file), con gli stessi ingressi che `MediaGrid` calcolera'
 * ([inputsFor]); la prima composizione lo trova pronto e disegna subito le celle, e la finestra delle miniature parte subito.
 *
 * Se qualcosa non coincide (un'altra larghezza, una vista diversa, un altro livello) non si usa: la griglia fa come prima. Si
 * usa una volta sola. Nessun dato nuovo: lo stesso layout che la griglia avrebbe calcolato un attimo dopo.
 */
object GridWarmup {

  private class Prepared(val inputs: GridLayoutInputs, val level: GridLevel, val layout: GridLayout)

  @Volatile private var prepared: Prepared? = null

  /**
   * Impagina (bloccante: fuori dal thread principale) il livello [level] di [view] come lo farebbe la griglia in una finestra larga
   * [widthPx] con densita' [density] e scala del testo [fontScale].
   */
  fun prepare(
    view: TimelineView,
    style: GridStyle,
    level: GridLevel,
    widthPx: Int,
    density: Float,
    fontScale: Float,
    engine: GridLayoutEngine,
    gap: Dp = GalleryWall.Gap,
  ) {
    if (view.size == 0 || widthPx <= 0) return
    val inputs = inputsFor(view, style, widthPx, density, fontScale, gap)
    val layout = AvvioTrace.section("MCG:griglia:layoutPreparato") { engine.layout(view, inputs.spec(level)) }
    prepared = Prepared(inputs, level, layout)
  }

  /** Il layout preparato per questi ingressi e questo livello, una volta sola; null se non c'e' o non coincide. */
  internal fun take(inputs: GridLayoutInputs, level: GridLevel): GridLayout? {
    val p = prepared ?: return null
    if (p.inputs != inputs || p.level != level) return null
    prepared = null
    return p.layout
  }

  /** Dimentica il layout preparato (un azzeramento, un test). */
  fun clear() {
    prepared = null
  }

  /**
   * Gli ingressi del layout come li calcola `MediaGrid` (stessa larghezza in dp per i livelli, stessa fessura arrotondata, stessa
   * densita' delle intestazioni): se cambia uno dei due, cambia anche l'altro.
   */
  internal fun inputsFor(view: TimelineView, style: GridStyle, widthPx: Int, density: Float, fontScale: Float, gap: Dp): GridLayoutInputs {
    val levels = GalleryGridLevels.forWidth(widthPx / density)
    val gapPx = (gap.value * density).roundToInt()
    val headerDensity = density * max(1f, fontScale)
    return GridLayoutInputs(view, style, widthPx, gapPx, headerDensity, levels.map { it.columns })
  }
}
