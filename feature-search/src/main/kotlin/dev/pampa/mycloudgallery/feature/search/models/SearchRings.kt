package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion
import dev.pampa.mycloudgallery.feature.search.R

// GLI ANELLI E I SEGNI DELLA PAGINA RICERCA E MODELLI (I2 della fase I). «Stato senza frasi: anelli e barre che avanzano, segni, numeri soli». Un anello
// dice quanto e' fatto (la copertura, l'analisi, lo scaricamento di un modello) e si riempie con la molla di FluidMotion; le spiegazioni lunghe stanno
// dietro il segno «i» ([InfoButton] -> [InfoSheet]).

/**
 * Un anello che avanza fino a [fraction] (0..1) nel colore [color], sulla pista dell'inchiostro della pagina; [busy] = sta lavorando senza sapere quanto
 * manca (una verifica): un arco corto gira. Il [content] sta al centro. Le animazioni si leggono nel disegno (nessuna ricomposizione); con «riduci
 * animazioni» l'anello e' fermo alla sua misura.
 */
@Composable
internal fun SearchRing(
  fraction: Float,
  color: Color,
  size: Dp,
  stroke: Dp,
  modifier: Modifier = Modifier,
  busy: Boolean = false,
  content: @Composable BoxScope.() -> Unit = {},
) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val track = MaterialTheme.colorScheme.onSurface.copy(alpha = TrackAlpha)
  val target = fraction.coerceIn(0f, 1f)
  val shown = remember { Animatable(if (reduced) target else 0f) }
  LaunchedEffect(target, reduced) { if (reduced) shown.snapTo(target) else shown.animateTo(target, FluidMotion.fluid()) }
  val spin = if (busy && !reduced) {
    rememberInfiniteTransition(label = "anello che lavora").animateFloat(0f, FullTurn, GalleryMotion.shimmerSpec(), label = "giro")
  } else {
    null
  }
  Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val width = stroke.toPx()
      val inset = width / 2f
      val arcSize = Size(this.size.width - width, this.size.height - width)
      val topLeft = Offset(inset, inset)
      drawArc(color = track, startAngle = 0f, sweepAngle = FullTurn, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width))
      val turning = spin?.value
      if (turning != null) {
        drawArc(color = color, startAngle = turning - QuarterTurn, sweepAngle = BusySweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width, cap = StrokeCap.Round))
      } else {
        val sweep = FullTurn * shown.value.coerceIn(0f, 1f)
        if (sweep > 0f) {
          drawArc(color = color, startAngle = -QuarterTurn, sweepAngle = sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width, cap = StrokeCap.Round))
        }
      }
    }
    content()
  }
}

private const val TrackAlpha = 0.10f
private const val FullTurn = 360f
private const val QuarterTurn = 90f
private const val BusySweep = 80f

/** Il segno «i»: un tasto da 48 dp che apre la spiegazione lunga. [label] dice che cosa spiega. */
@Composable
internal fun InfoButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .size(GalleryDimens.MinTouchTarget)
      .semantics {
        contentDescription = label
        role = Role.Button
      }
      .fluidPressable(onClick = onClick, role = Role.Button, focusShape = FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** La spiegazione lunga dietro un «i»: un foglio con il titolo, il testo e «Chiudi». */
@Composable
internal fun InfoSheet(title: String, message: String, onDismiss: () -> Unit) {
  GalleryConfirmSheet(visible = true, onDismissRequest = onDismiss, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.Info,
      tone = GallerySheetTone.Brand,
      title = title,
      message = message,
      actions = {
        FluidButton(text = stringResource(R.string.models_close), onClick = onDismiss, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
      },
    )
  }
}
