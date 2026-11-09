package dev.pampa.mycloudgallery.core.designsystem.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryActionBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryNavigationIcon
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTopBar
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGlassGrowth
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGlassMotion
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion

/** Le misure del chrome del viewer. */
object GalleryViewerChromeDefaults {

  /** Quanto la barra azioni sta sopra il bordo basso (oltre la barra di navigazione di sistema). */
  val BottomMargin: Dp = 14.dp

  /** Lo spazio fra la barra del video e la barra azioni. */
  val BarGap: Dp = 10.dp

  /** Sotto questa opacita' il chrome non si disegna: niente da vedere e niente da toccare. */
  const val InvisibleBelow: Float = 0.004f
}

/**
 * Il chrome del viewer: le barre di vetro **sopra la foto**.
 *
 * In alto la barra con il tasto per chiudere, la data come titolo, "12:41 · Oslo" come sottotitolo e le azioni
 * (informazioni, menu ⋮); in basso, a capsula, le azioni rapide (condividi, preferito, informazioni, elimina) e, se
 * serve, un pezzo in piu' sopra di esse (la barra del video). Tutto usa la tinta "sopra una foto"
 * (`galleryTintOnPhoto`): una foto puo' essere bianca o nera sia in chiaro che in scuro, e il vetro che segue il tema
 * sparirebbe sulla foto sbagliata.
 *
 * **Sfuma, perche' e' chrome e non una pagina** (regola 3 delle regole Fluid): un tocco sulla foto lo nasconde e lo
 * mostra con le curve di [FluidMotion]. Le due barre vanno per conto loro ([visible] e [bottomVisible]): aprire il
 * pannello informazioni toglie quella in basso e lascia quella in alto. Sotto una soglia di opacita' non si disegna
 * piu' niente, cosi' un chrome nascosto non costa ne' vetro ne' tocchi.
 *
 * Il vetro rifrange [backdrop], cioe' la foto, mai la barra stessa. Con `reducedMotion` compare e sparisce di colpo.
 *
 * @param bottomContent un pezzo di chrome sopra la barra azioni (la barra del video), con la stessa dissolvenza.
 */
@Composable
fun GalleryViewerChrome(
  visible: Boolean,
  title: String,
  backdrop: GlassBackdropState,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  navigationIcon: GalleryNavigationIcon = GalleryNavigationIcon.Close,
  bottomVisible: Boolean = visible,
  topActions: @Composable RowScope.() -> Unit = {},
  bottomActions: List<GalleryBarAction> = emptyList(),
  bottomContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val topAlpha by animateFloatAsState(
    targetValue = if (visible) 1f else 0f,
    animationSpec = chromeSpec(visible, reducedMotion),
    label = "viewer top chrome",
  )
  val bottomAlpha by animateFloatAsState(
    targetValue = if (bottomVisible) 1f else 0f,
    animationSpec = chromeSpec(bottomVisible, reducedMotion),
    label = "viewer bottom chrome",
  )
  Box(modifier = modifier.fillMaxSize()) {
    if (topAlpha > GalleryViewerChromeDefaults.InvisibleBelow) {
      GalleryTopBar(
        title = title,
        subtitle = subtitle,
        backdrop = backdrop,
        onPhoto = true,
        onNavigate = onClose,
        navigationIcon = navigationIcon,
        // Il tasto di chiusura e il ⋮ sono lo stesso vetro (B4): il tasto di vetro dell'engine, che prende da solo il fondale della barra.
        fluidButtons = true,
        actions = topActions,
        modifier = Modifier
          .align(Alignment.TopCenter)
          .graphicsLayer { alpha = topAlpha },
      )
    }
    // Il pezzo sopra la barra azioni (la barra del video) compare e sparisce con i token di FluidMotion invece di farsi spazio di colpo: la barra azioni che aveva sotto non salta.
    // Mentre esce il suo contenuto non c'e' piu' (null): si tiene l'ultimo, solo per il tempo dell'uscita.
    val lastBottomContent = remember { LastContent() }
    if (bottomContent != null) lastBottomContent.content = bottomContent
    val hasBottom = bottomActions.isNotEmpty() || bottomContent != null
    if (hasBottom && bottomAlpha > GalleryViewerChromeDefaults.InvisibleBelow) {
      Column(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(bottom = GalleryViewerChromeDefaults.BottomMargin)
          .graphicsLayer { alpha = bottomAlpha },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        AnimatedVisibility(
          visible = bottomContent != null,
          enter = GalleryGlassMotion.enter(GalleryGlassGrowth.Height),
          exit = GalleryGlassMotion.exit(GalleryGlassGrowth.Height),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            lastBottomContent.content?.invoke(this)
            Spacer(Modifier.height(GalleryViewerChromeDefaults.BarGap))
          }
        }
        if (bottomActions.isNotEmpty()) {
          GalleryActionBar(actions = bottomActions, backdrop = backdrop, onPhoto = true)
        }
      }
    }
  }
}

/** L'ultimo pezzo mostrato sopra la barra azioni: un contenitore semplice (scriverlo in composizione non invalida niente), per il tempo dell'uscita. */
private class LastContent {
  var content: (@Composable ColumnScope.() -> Unit)? = null
}

private fun chromeSpec(appearing: Boolean, reducedMotion: Boolean) = when {
  reducedMotion -> snap<Float>()
  appearing -> GalleryMotion.chromeAppearSpec<Float>()
  else -> FluidMotion.fadeOut<Float>()
}
