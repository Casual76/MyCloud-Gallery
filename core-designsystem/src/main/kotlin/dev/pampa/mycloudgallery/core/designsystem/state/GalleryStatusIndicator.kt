package dev.pampa.mycloudgallery.core.designsystem.state

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
import dev.pampa.mycloudgallery.core.designsystem.R
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRing
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

/**
 * Lo stato globale della galleria, quello che dice se i ricordi sono al sicuro.
 *
 * Sono tipi di *interfaccia*: sapere che "serve la carica" e' del motore delle regole (1.6), qui si
 * decide solo come si dice (piano, 5.6: "Tutto al sicuro", "312 in coda, attesa carica", "Errore:
 * quota esaurita").
 */
@Immutable
sealed interface GalleryStatus {

  /** Ogni media e' al sicuro. Lo stato di tutti i giorni, quindi il piu' quieto. */
  data object AllSafe : GalleryStatus

  /** Il backup sta girando: [done] su [total]. */
  data class BackingUp(val done: Int, val total: Int) : GalleryStatus

  /** Il lavoro aspetta le condizioni (qui: la carica) e ripartira' da solo. */
  data object WaitingForCharge : GalleryStatus

  /** Il NAS non risponde: la galleria lavora su cio' che ha in locale. */
  data object NasUnreachable : GalleryStatus

  /** Il NAS si sta svegliando dallo standby. */
  data object WakingNas : GalleryStatus

  /** Qualcosa e' andato storto: [reason] e' la frase breve ("quota esaurita"), gia' tradotta. */
  data class Error(val reason: String) : GalleryStatus
}

/**
 * L'indicatore di stato globale: una pillola compatta e tappabile (apre i dettagli del backup).
 *
 * I toni vengono dai **ruoli dello schema**, non da verdi e arancioni scritti a mano (quelli
 * dell'engine, `FluidTone.Success` e `Warning`, sono privati): lo stato di tutti i giorni ("Tutto al
 * sicuro") e' la lavata del marchio, il lavoro in corso e' il secondario, l'attesa e' neutra,
 * "NAS non raggiungibile" e' il terziario (quello che dice "guarda"), l'errore e' `error`. Con Material
 * You o un altro preset cambiano insieme al resto.
 *
 * Due forme:
 *  - **tonale** (default): una pillola piena del tono, per una pagina piatta; il bersaglio di tocco e'
 *    48 dp anche se la pillola ne e' alta 36;
 *  - **di vetro**, con un [backdrop]: una lente 48 dp sopra la timeline (una pillola piena sopra una
 *    foto sarebbe un segnale piu' forte di quello che dice).
 *
 * Il colore e il glifo non sono mai l'unico segnale: ogni stato ha la sua forma (spunta, anello che
 * si riempie, batteria, nuvola barrata, cerchio che gira, punto esclamativo) e la sua frase.
 * [compact] toglie la frase e lascia il glifo, per una barra stretta; la frase resta nella
 * descrizione per il lettore di schermo.
 */
@Composable
fun GalleryStatusIndicator(
  status: GalleryStatus,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  backdrop: GlassBackdropState? = null,
  compact: Boolean = false,
) {
  val label = status.label()
  val style = status.style()
  val container by animateColorAsState(style.container, FluidMotion.color(), label = "status container")
  val text by animateColorAsState(style.text, FluidMotion.color(), label = "status text")
  val glyph by animateColorAsState(
    targetValue = if (backdrop != null) style.glassGlyph else style.glyph,
    animationSpec = FluidMotion.color(),
    label = "status glyph",
  )
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion

  val content: @Composable () -> Unit = {
    AnimatedContent(
      targetState = status,
      transitionSpec = {
        if (reducedMotion) {
          EnterTransition.None togetherWith ExitTransition.None
        } else {
          fadeIn(FluidMotion.fadeIn(ContentFadeInMillis)) togetherWith fadeOut(FluidMotion.fadeOut(ContentFadeOutMillis))
        }
      },
      contentKey = { it::class },
      label = "status content",
    ) { shown ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(StatusGap),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        StatusGlyph(shown, glyph)
        if (!compact) {
          Text(
            text = shown.label(),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = if (backdrop != null) MaterialTheme.colorScheme.onSurface else text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }

  val accessibility = Modifier.semantics(mergeDescendants = true) { contentDescription = label }
  if (backdrop != null) {
    Box(
      modifier = modifier
        .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
        .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape)
        .then(accessibility)
        // Il vetro risponde da solo al dito (si china, si accende): la scala della pressione sta a 1,
        // come nel tasto di vetro dell'engine, o il fondale campionato si muoverebbe sotto la lente.
        .fluidPressable(onClick = onClick, pressedScale = 1f, role = Role.Button, focusShape = FluidCapsuleShape)
        .padding(horizontal = if (compact) 0.dp else GlassHorizontalPadding),
      contentAlignment = Alignment.Center,
    ) { content() }
  } else {
    Box(
      modifier = modifier
        .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
        .then(accessibility)
        .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        modifier = Modifier
          .defaultMinSize(minWidth = TonalHeight, minHeight = TonalHeight)
          .clip(FluidCapsuleShape)
          .background(container)
          .padding(horizontal = if (compact) 0.dp else TonalHorizontalPadding),
        contentAlignment = Alignment.Center,
      ) { content() }
    }
  }
}

/** I colori di un tono: il riempimento, il testo, il glifo (e il glifo quando sta su vetro). */
@Immutable
internal data class StatusStyle(
  val container: Color,
  val text: Color,
  val glyph: Color,
  val glassGlyph: Color,
)

@Composable
private fun GalleryStatus.style(): StatusStyle = galleryStatusStyle(this, MaterialTheme.colorScheme)

/** Il tono di uno stato, risolto da uno schema (pura: si verifica il contrasto di ogni coppia senza comporre). */
internal fun galleryStatusStyle(status: GalleryStatus, s: ColorScheme): StatusStyle {
  return when (status) {
    GalleryStatus.AllSafe -> StatusStyle(s.primaryContainer, s.onPrimaryContainer, s.onPrimaryContainer, s.onSurface)
    is GalleryStatus.BackingUp, GalleryStatus.WakingNas ->
      StatusStyle(s.secondaryContainer, s.onSecondaryContainer, s.onSecondaryContainer, s.onSurface)
    GalleryStatus.WaitingForCharge ->
      StatusStyle(s.surfaceContainerHighest, s.onSurface, s.onSurfaceVariant, s.onSurfaceVariant)
    GalleryStatus.NasUnreachable ->
      StatusStyle(s.tertiaryContainer, s.onTertiaryContainer, s.onTertiaryContainer, s.onSurface)
    is GalleryStatus.Error -> StatusStyle(s.errorContainer, s.onErrorContainer, s.error, s.error)
  }
}

@Composable
private fun GalleryStatus.label(): String = when (this) {
  GalleryStatus.AllSafe -> stringResource(R.string.status_all_safe)
  is GalleryStatus.BackingUp -> stringResource(R.string.status_backing_up, done, total)
  GalleryStatus.WaitingForCharge -> stringResource(R.string.status_waiting_charge)
  GalleryStatus.NasUnreachable -> stringResource(R.string.status_nas_unreachable)
  GalleryStatus.WakingNas -> stringResource(R.string.status_waking_nas)
  is GalleryStatus.Error -> stringResource(R.string.status_error, reason)
}

@Composable
private fun StatusGlyph(status: GalleryStatus, color: Color) {
  when (status) {
    GalleryStatus.AllSafe -> StatusIcon(Icons.Rounded.CheckCircle, color)
    is GalleryStatus.BackingUp -> GalleryProgressRing(
      progress = if (status.total > 0) status.done.toFloat() / status.total else 0f,
      modifier = Modifier.size(StatusRing),
      color = color,
      trackColor = color.copy(alpha = RingTrackAlpha),
    )
    GalleryStatus.WaitingForCharge -> StatusIcon(Icons.Rounded.BatteryChargingFull, color)
    GalleryStatus.NasUnreachable -> StatusIcon(Icons.Rounded.CloudOff, color)
    GalleryStatus.WakingNas -> FluidSpinner(size = StatusGlyphSize, color = color)
    is GalleryStatus.Error -> StatusIcon(Icons.Rounded.ErrorOutline, color)
  }
}

@Composable
private fun StatusIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
  Icon(
    imageVector = icon,
    contentDescription = null,
    modifier = Modifier.size(StatusGlyphSize),
    tint = color,
  )
}

private val StatusGap = 8.dp
private val StatusGlyphSize = 18.dp
private val StatusRing = 18.dp
private val TonalHeight = 36.dp
private val TonalHorizontalPadding = 12.dp
private val GlassHorizontalPadding = 16.dp
private const val RingTrackAlpha = 0.28f
private const val ContentFadeInMillis = 180
private const val ContentFadeOutMillis = 120
