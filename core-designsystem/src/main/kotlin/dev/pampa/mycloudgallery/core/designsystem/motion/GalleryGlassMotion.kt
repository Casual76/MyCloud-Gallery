package dev.pampa.mycloudgallery.core.designsystem.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy

/** Quale misura di un pezzo di vetro cresce quando compare: nessuna (solo dissolvenza), la larghezza (un badge in una riga) o l'altezza (una barra in una colonna). */
enum class GalleryGlassGrowth { None, Width, Height }

/**
 * Come entra e come esce un pezzo di vetro **sopra una foto** (G3, B4: il badge "Movimento", la barra del video, le note e gli avvisi del viewer): con i token di [FluidMotion] e non di colpo.
 * Una dissolvenza (`FluidMotion.fadeIn` / `fadeOut`), e per chi sta in una riga o in una colonna anche la misura che cresce (`FluidMotion.smooth`), cosi' quello che gli sta accanto non salta.
 * Con il movimento ridotto del sistema compare e sparisce di colpo, come il chrome.
 */
object GalleryGlassMotion {

  @Composable
  fun enter(growth: GalleryGlassGrowth = GalleryGlassGrowth.None): EnterTransition {
    if (LocalFluidMotionPolicy.current.reducedMotion) return fadeIn(snap())
    val fade = fadeIn(FluidMotion.fadeIn())
    return when (growth) {
      GalleryGlassGrowth.None -> fade
      GalleryGlassGrowth.Width -> fade + expandHorizontally(FluidMotion.smooth())
      GalleryGlassGrowth.Height -> fade + expandVertically(FluidMotion.smooth())
    }
  }

  @Composable
  fun exit(growth: GalleryGlassGrowth = GalleryGlassGrowth.None): ExitTransition {
    if (LocalFluidMotionPolicy.current.reducedMotion) return fadeOut(snap())
    val fade = fadeOut(FluidMotion.fadeOut())
    return when (growth) {
      GalleryGlassGrowth.None -> fade
      GalleryGlassGrowth.Width -> fade + shrinkHorizontally(FluidMotion.smooth())
      GalleryGlassGrowth.Height -> fade + shrinkVertically(FluidMotion.smooth())
    }
  }
}
