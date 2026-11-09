package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingCapsuleDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingCapsulePlacement
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy

// IL POSTO DELLA PILLOLA (G2, B3 della fase grafica). In fondo allo schermo c'e' un posto solo: o la pillola delle schede (con il cerchio
// di Cerca), o la barra delle azioni di una selezione. Prima la barra delle azioni di una griglia nel pannello del tablet si posava
// **sopra** la pillola, e i comandi si compenetravano; adesso chi entra in selezione prende il posto e la pillola esce.

/**
 * Chi tiene il posto della pillola. La pagina dentro una scheda lo prende finche' mostra la sua barra delle azioni ([TakeGalleryTabBarSlot]);
 * lo scaffold toglie la pillola finche' [taken] e' vero. Un contatore, non un vero/falso: due pagine composte insieme (il pannello e la
 * transizione) non si tolgono il posto a vicenda.
 */
@Stable
class GalleryTabBarSlot {
  private var holders by mutableIntStateOf(0)

  /** Qualcuno ha preso il posto: la pillola non si mostra. */
  val taken: Boolean get() = holders > 0

  internal fun take() {
    holders += 1
  }

  internal fun release() {
    holders = (holders - 1).coerceAtLeast(0)
  }
}

/** Il posto della pillola della scheda che si sta componendo; null fuori dallo scaffold (le rotte a schermo intero: la pillola non c'e'). */
val LocalGalleryTabBarSlot = staticCompositionLocalOf<GalleryTabBarSlot?> { null }

/**
 * Prende il posto della pillola finche' [active] e' vero (la barra delle azioni della selezione si vede). Fuori dallo scaffold non fa
 * niente. Si legge una volta per cambio di selezione, non per fotogramma.
 */
@Composable
fun TakeGalleryTabBarSlot(active: Boolean) {
  val slot = LocalGalleryTabBarSlot.current ?: return
  if (!active) return
  DisposableEffect(slot) {
    slot.take()
    onDispose { slot.release() }
  }
}

/**
 * Un pezzo di vetro nel posto della pillola, con l'entrata e l'uscita della capsula dell'engine (`FluidFloatingCapsule` con
 * `ReplacingTabBar`): sale da sotto il bordo dello schermo con la molla del chrome e scende piu' svelto; con le animazioni ridotte solo
 * la dissolvenza. I margini (barra di sistema, 14 dp ai lati, 16 dp in basso: i numeri di `fluidCapsuleInsets(ReplacingTabBar)`) stanno
 * **dentro** l'animazione, come nella capsula: la pillola e la barra delle azioni sono alla stessa altezza e nello stesso posto, quindi la
 * pagina sotto non cambia lo spazio che si lascia.
 *
 * La barra di sistema si legge **grezza** (`WindowInsets.navigationBars`), non con `navigationBarsPadding`: una barra delle azioni dentro un
 * contenitore che ha gia' consumato gli inset (il pannello di dettaglio del tablet) deve stare alla stessa altezza della pillola, che sta fuori.
 *
 * Lo usano la pillola (che esce quando arriva una selezione) e la barra delle azioni (che entra al suo posto). Va allineato dal chiamante
 * (`Modifier.align(Alignment.BottomCenter)` dentro un `Box`).
 */
@Composable
fun GalleryTabBarSlotVisibility(
  visible: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  AnimatedVisibility(
    visible = visible,
    modifier = modifier,
    enter = FluidFloatingCapsuleDefaults.enter(reduced),
    exit = FluidFloatingCapsuleDefaults.exit(reduced),
  ) {
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
      modifier = Modifier.padding(
        start = FluidFloatingTabBarDefaults.HorizontalMargin,
        end = FluidFloatingTabBarDefaults.HorizontalMargin,
        bottom = navigation + FluidFloatingCapsuleDefaults.bottomOffset(FluidFloatingCapsulePlacement.ReplacingTabBar),
      ),
      contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }
}
