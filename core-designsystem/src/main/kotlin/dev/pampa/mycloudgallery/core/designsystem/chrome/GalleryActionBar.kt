package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

/** Un'azione della barra azioni: un'icona con la sua descrizione. */
@Immutable
data class GalleryBarAction(
  val icon: ImageVector,
  val contentDescription: String,
  val onClick: () -> Unit,
  val destructive: Boolean = false,
  val enabled: Boolean = true,
)

/**
 * La barra azioni in vetro: una capsula flottante con poche azioni, per il viewer (condividi,
 * preferito, informazioni, elimina) e per la selezione multipla in Libreria.
 *
 * Un solo pezzo di vetro, la capsula; le azioni sono icone semplici dentro, non una lente ciascuna
 * (vetro su vetro non rifrange niente di leggibile, e ogni lente e' un layer). Ognuna ha 48 x 48 dp di
 * bersaglio, la descrizione per il lettore di schermo, e l'azione distruttiva usa il colore di errore.
 *
 * [onPhoto] sceglie la tinta della capsula come la barra superiore: sopra una foto a tutta pagina,
 * quella di `floatingTintOnPhoto`.
 */
@Composable
fun GalleryActionBar(
  actions: List<GalleryBarAction>,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  onPhoto: Boolean = false,
) {
  val scheme = MaterialTheme.colorScheme
  val tint = if (onPhoto) galleryTintOnPhoto(floating = true) else GlassDefaults.floatingTint()
  Row(
    modifier = modifier
      .glassSurface(
        state = backdrop,
        tint = tint,
        shape = FluidCapsuleShape,
        role = GlassRole.Floating,
      )
      .padding(horizontal = BarPadding),
    horizontalArrangement = Arrangement.spacedBy(BarSpacing),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    actions.forEach { action ->
      Box(
        modifier = Modifier
          .size(GalleryDimens.MinTouchTarget)
          .alpha(if (action.enabled) 1f else DisabledAlpha)
          .semantics { contentDescription = action.contentDescription }
          .fluidPressable(
            onClick = action.onClick,
            enabled = action.enabled,
            pressedScale = PressedScale,
            role = Role.Button,
            focusShape = FluidCapsuleShape,
          ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = action.icon,
          contentDescription = null,
          modifier = Modifier.size(ActionGlyph),
          tint = if (action.destructive) scheme.error else if (onPhoto) scheme.onSurface else scheme.primary,
        )
      }
    }
  }
}

private val BarPadding = 6.dp
private val BarSpacing = 4.dp
private val ActionGlyph = 24.dp
private const val DisabledAlpha = 0.38f
private const val PressedScale = 0.9f
