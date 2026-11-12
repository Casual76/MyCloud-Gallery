package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoButton
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoDetail
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryFigureState
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryIllustrationTone

/**
 * Uno stato vuoto o d'attesa di queste pagine, nella forma unica della galleria ([GalleryFigureState]; fase I, giro 1, CU-23): la figura, il titolo
 * e al piu' un tasto, nessuna frase in vista. Il perche' ([detail]), dove serve davvero (lo sblocco dei Nascosti, i 30 giorni del cestino, come si
 * aggiunge un preferito), sta dietro la «i» ([GalleryInfoButton]): un tocco lo apre sotto, nella pagina; TalkBack lo dice gia' nella descrizione
 * della «i». Senza [detail], niente «i».
 */
@Composable
internal fun CollectionEmptyState(
  icon: ImageVector,
  title: String,
  detail: String? = null,
  modifier: Modifier = Modifier,
  tone: GalleryIllustrationTone = GalleryIllustrationTone.Brand,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  actionStyle: FluidButtonStyle = FluidButtonStyle.Filled,
) {
  val action = if (actionLabel != null && onAction != null) actionLabel to onAction else null
  GalleryFigureState(
    icon = icon,
    title = title,
    tone = tone,
    fill = false,
    modifier = modifier.fillMaxWidth().padding(vertical = CollectionsDimens.StatePadding),
    action = if (detail == null && action == null) {
      null
    } else {
      {
        if (detail != null) EmptyStateWhy(detail)
        if (action != null) FluidButton(text = action.first, onClick = action.second, size = FluidButtonSize.Large, style = actionStyle)
      }
    },
  )
}

/** La «i» di uno stato vuoto e, aperta, la frase sotto. */
@Composable
private fun EmptyStateWhy(detail: String) {
  var open by rememberSaveable(detail) { mutableStateOf(false) }
  GalleryInfoButton(label = detail, open = open, onToggle = { open = !open })
  GalleryInfoDetail(text = detail, visible = open)
}
