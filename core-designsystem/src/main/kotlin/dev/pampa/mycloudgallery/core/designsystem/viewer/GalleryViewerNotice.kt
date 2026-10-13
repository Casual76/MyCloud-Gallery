package dev.pampa.mycloudgallery.core.designsystem.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidIndeterminateBar
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.pampa.mycloudgallery.core.designsystem.chrome.galleryTintOnPhoto

/** Come il viewer mostra l'avanzamento di un caricamento che puo' non finire subito. */
sealed interface GalleryNoticeProgress {

  /** Non si sa a che punto e': una barra che scorre. */
  data object Indeterminate : GalleryNoticeProgress

  /** Si sa: da 0 a 1. */
  data class Determinate(val fraction: Float) : GalleryNoticeProgress
}

/**
 * Una nota di vetro sopra la foto: dice **perche'** il viewer mostra solo l'anteprima o niente ("Il NAS non risponde",
 * "Servono i dati mobili", "Non riesco ad aprire questo file") e cosa si puo' fare.
 *
 * Il viewer non resta mai muto: se l'originale non c'e' (NAS lento o irraggiungibile, rete non consentita, microSD
 * tolta, formato non decodificabile) la miniatura resta dov'e' e la nota sta in basso, senza spostare niente. Il vetro
 * e' un contenitore solo, con la tinta "sopra una foto"; il testo ha il colore pieno (`onSurface`) perche' quello
 * secondario non regge su una foto qualunque. L'azione, se c'e', e' un tasto da 50 dp.
 *
 * E' una regione viva per il lettore di schermo: quando compare viene annunciata.
 */
@Composable
fun GalleryViewerNotice(
  icon: ImageVector,
  title: String,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  detail: String? = null,
  progress: GalleryNoticeProgress? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  val scheme = MaterialTheme.colorScheme
  Column(
    modifier = modifier
      .padding(horizontal = NoticeSideMargin)
      .widthIn(max = NoticeMaxWidth)
      .fillMaxWidth()
      .glassSurface(
        state = backdrop,
        tint = galleryTintOnPhoto(floating = true),
        shape = ContinuousCornerShape(FluidRadius.Group),
        role = GlassRole.Floating,
      )
      .padding(NoticePadding)
      .semantics { liveRegion = LiveRegionMode.Polite },
    verticalArrangement = Arrangement.spacedBy(NoticeGap),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(NoticeGap),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(NoticeIconTile)
          .clip(ContinuousCornerShape(FluidRadius.Control))
          .background(scheme.onSurface.copy(alpha = TileAlpha)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(NoticeGlyph), tint = scheme.onSurface)
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TextGap)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
        if (!detail.isNullOrBlank()) {
          Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        }
      }
    }
    when (progress) {
      is GalleryNoticeProgress.Determinate ->
        FluidProgressBar(progress = { progress.fraction }, color = scheme.onSurface)
      GalleryNoticeProgress.Indeterminate -> FluidIndeterminateBar(color = scheme.onSurface)
      null -> Unit
    }
    if (actionLabel != null && onAction != null) {
      FluidButton(
        text = actionLabel,
        onClick = onAction,
        style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large,
        fillWidth = true,
      )
    }
  }
}

private val NoticeSideMargin: Dp = 16.dp
private val NoticeMaxWidth: Dp = 460.dp
private val NoticePadding: Dp = 16.dp
private val NoticeGap: Dp = 12.dp
private val TextGap: Dp = 2.dp
private val NoticeIconTile: Dp = 40.dp
private val NoticeGlyph: Dp = 22.dp
private const val TileAlpha = 0.12f
