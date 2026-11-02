package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidPressImmediate
import dev.pampa.mycloudgallery.core.designsystem.a11y.galleryGlass
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction

/**
 * La barra delle azioni della selezione **al posto della pillola** (G1, G2): stessa altezza della pillola aperta
 * (`FluidFloatingTabBarDefaults.OpenHeight`) e stesso posto, quindi chi entra in selezione vede la pillola diventare la
 * barra, non una barra che le si posa sopra. Una capsula sola; le azioni sono bersagli di 48 dp con 8 dp fra l'uno e
 * l'altro (non si tocca il vicino per sbaglio).
 *
 * Lo stile e' il vetro adattivo della chrome (pellicola e inchiostro dal contenuto che passa sotto). L'azione distruttiva
 * usa [GalleryChromeStyle.destructiveInk], gia' portata a 3:1 sulla pellicola.
 */
@Composable
fun GalleryChromeActionBar(
  actions: List<GalleryBarAction>,
  style: GalleryChromeStyle,
  backdrop: GlassBackdropState?,
  modifier: Modifier = Modifier,
) {
  val surface = if (backdrop != null) {
    Modifier.galleryGlass(state = backdrop, tint = style.tint, shape = FluidCapsuleShape, role = GlassRole.Floating)
  } else {
    Modifier.background(style.tint.fallback, FluidCapsuleShape)
  }
  CompositionLocalProvider(LocalGalleryChromeInk provides style.ink, LocalFluidPressImmediate provides true) {
    Row(
      modifier = modifier
        .height(ActionBarHeight)
        .then(surface)
        .padding(horizontal = ActionBarPadding),
      horizontalArrangement = Arrangement.spacedBy(ActionGap),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      actions.forEach { action ->
        GalleryChromeIconButton(
          icon = action.icon,
          contentDescription = action.contentDescription,
          onClick = action.onClick,
          enabled = action.enabled,
          tint = if (action.destructive) style.destructiveInk else style.ink,
        )
      }
    }
  }
}

/** L'altezza della barra delle azioni: quella della pillola aperta, di cui prende il posto. */
val ActionBarHeight: Dp = FluidFloatingTabBarDefaults.OpenHeight

private val ActionBarPadding = 10.dp
private val ActionGap = 8.dp
