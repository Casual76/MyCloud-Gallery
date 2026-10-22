package dev.pampa.mycloudgallery.feature.viewer.motion

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.currentGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
import dev.pampa.mycloudgallery.core.designsystem.chrome.galleryBarInk
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.viewer.GalleryViewerNotice
import dev.pampa.mycloudgallery.feature.viewer.R

/**
 * Il badge "Movimento" (D51): icona e scritta, nella barra in alto del viewer, visibile con le barre. Un tocco riproduce il video
 * intero una volta, con l'audio; un altro tocco lo ferma. Il badge c'e' anche con l'interruttore "Animazione delle foto in movimento"
 * spento: funziona sempre. L'aspetto preciso lo decide la grafica (G3): qui un vetro come gli altri tasti della barra.
 *
 * **Accessibilita'**: l'etichetta e' "Riproduci il movimento" ("Ferma il movimento" mentre gira), lo stato dice se sta girando o se
 * "non e' disponibile ora"; il bersaglio e' alto almeno 48 dp ([GalleryDimens.MinTouchTarget]).
 *
 * Su un telefono, dove il titolo centrato della barra non lascia spazio, il badge e' solo l'icona; dai 600 dp c'e' anche la scritta.
 *
 * @param playing il video intero sta girando: il tocco lo ferma.
 * @param unavailable il video non si puo' avere adesso (rete): il badge e' attenuato e il tocco ci riprova.
 * @param loading il video di un media del NAS si sta leggendo (VM-3): il badge lo dice con un indicatore e lo stato "In caricamento", e il tocco non fa niente
 *   finche' non ha finito (prima non c'era nessun segno e un secondo tocco ricominciava la lettura da capo).
 */
@Composable
internal fun MotionBadge(
  playing: Boolean,
  unavailable: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  backdrop: GlassBackdropState = currentGlassBackdrop(),
  loading: Boolean = false,
) {
  val label = stringResource(if (playing) R.string.motion_stop else R.string.motion_play)
  val state = when {
    playing -> stringResource(R.string.motion_state_playing)
    loading -> stringResource(R.string.motion_state_loading)
    unavailable -> stringResource(R.string.motion_state_unavailable)
    else -> null
  }
  Box(
    modifier = modifier
      .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
      .alpha(if (unavailable) UnavailableAlpha else 1f)
      .semantics {
        contentDescription = label
        if (state != null) stateDescription = state
      }
      .fluidPressable(onClick = onClick, pressedScale = 1f, role = Role.Button, focusShape = FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    // Il titolo della barra e' centrato: su un telefono la scritta ci finirebbe sopra. Con poco spazio resta l'icona (e l'etichetta per TalkBack),
    // dai 600 dp in su (tablet, telefono piegato) c'e' anche "Movimento".
    val showLabel = LocalConfiguration.current.screenWidthDp >= LabelMinWidthDp
    Row(
      modifier = Modifier
        .then(if (showLabel) Modifier else Modifier.size(BadgeLens))
        .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape, interactive = true)
        .then(if (showLabel) Modifier.padding(horizontal = BadgeHorizontal, vertical = BadgeVertical) else Modifier),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = if (showLabel) Arrangement.spacedBy(BadgeGap) else Arrangement.Center,
    ) {
      if (loading) {
        CircularProgressIndicator(modifier = Modifier.size(BadgeGlyph), strokeWidth = 2.dp, color = galleryBarInk())
      } else {
        Icon(
          imageVector = if (playing) Icons.Rounded.Stop else Icons.Rounded.MotionPhotosOn,
          contentDescription = null,
          tint = galleryBarInk(),
          modifier = Modifier.size(BadgeGlyph),
        )
      }
      if (showLabel) {
        Text(
          text = stringResource(R.string.motion_badge),
          color = galleryBarInk(),
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * L'avviso che il tocco sul badge puo' aprire per un media del NAS (E-V15, D51): i dati mobili da confermare, con la dimensione del
 * file, oppure "non disponibile ora". Sta in basso come le altre note del viewer; chi lo ospita lo spegne dopo un po' o a ogni cambio.
 *
 * @param onConfirmMobileData null se `:app` non ha collegato la conferma (come E-V2): la nota non propone il tasto.
 */
@Composable
internal fun MotionNoticeView(
  notice: MotionNotice,
  backdrop: GlassBackdropState,
  onConfirmMobileData: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  when (notice) {
    is MotionNotice.NeedsMobileData -> {
      val size = Formatter.formatShortFileSize(LocalContext.current, notice.sizeBytes)
      GalleryViewerNotice(
        icon = Icons.Rounded.SignalCellularAlt,
        title = stringResource(R.string.motion_notice_mobile_title),
        detail = stringResource(R.string.motion_notice_mobile_detail_size, size),
        backdrop = backdrop,
        modifier = modifier,
        actionLabel = if (onConfirmMobileData != null) stringResource(R.string.motion_notice_mobile_action) else null,
        onAction = onConfirmMobileData,
      )
    }
    is MotionNotice.Unavailable -> GalleryViewerNotice(
      icon = Icons.Rounded.CloudOff,
      title = stringResource(R.string.motion_notice_unavailable_title),
      detail = stringResource(R.string.motion_notice_unavailable_detail),
      backdrop = backdrop,
      modifier = modifier,
    )
  }
}

private const val UnavailableAlpha = 0.5f
private const val LabelMinWidthDp = 600
private val BadgeLens = 40.dp
private val BadgeHorizontal = 12.dp
private val BadgeVertical = 8.dp
private val BadgeGap = 6.dp
private val BadgeGlyph = 20.dp
