package dev.pampa.mycloudgallery.core.designsystem.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryIconColors
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion

// L'ANELLO DELLA GALLERIA (fase I, «una sola grammatica»: un solo anello). Un lavoro che avanza si mostra con questo anello, e basta: lo stile e' quello
// del pop-up della nuvola. Traccia dello stesso colore dell'arco al 28%, arco con gli estremi arrotondati che parte da ore dodici, tratto di un dodicesimo
// del lato per gli anelli che hanno qualcosa al centro ([GalleryProgressRingDefaults.stroke]; 2 dp per i segni piccoli dei badge), colore della famiglia
// ([galleryRingColor]) o l'accento. Con [animate] l'arco avanza con la molla di FluidMotion letta nel disegno (nessuna ricomposizione a ogni passo) e con
// «riduci animazioni» scatta; senza misura (`progress = null`) un arco corto gira. Il centro lo riempie chi lo usa ([GalleryRingPercent], un glifo).

/** Le misure dell'anello. */
object GalleryProgressRingDefaults {
  /** L'anello di una riga (il pop-up della nuvola, una voce di un elenco). */
  val Row: Dp = 48.dp

  /** L'anello di una scheda (un'operazione del NAS, la condivisione di un album). */
  val Card: Dp = 52.dp

  /** L'anello della testata di una pagina (Backup, Ricerca e modelli). */
  val Hero: Dp = 132.dp

  /** Il tratto di un anello di lato [size]: un dodicesimo (4 dp a 48 dp, 11 dp a 132 dp), mai sotto i 2 dp. */
  fun stroke(size: Dp): Dp = maxOf(size / StrokeRatio, MinStroke)

  /** La traccia: lo stesso colore dell'arco a questa opacita'. */
  const val TrackAlpha: Float = 0.28f

  /** Il tratto dei segni piccoli (i badge sulle celle): il valore di serie, quello di prima. */
  val Thin: Dp = 2.dp

  private const val StrokeRatio = 12f
  private val MinStroke: Dp = 2.dp
}

/**
 * Un anello di avanzamento: una traccia e un arco che parte da ore dodici.
 *
 * @param progress quanto e' fatto (0..1); null = un lavoro senza misura: un arco corto gira (fermo con «riduci animazioni»).
 * @param color il colore dell'arco: l'accento di serie, o quello di una famiglia ([galleryRingColor]).
 * @param trackColor la traccia: di serie lo stesso colore al 28%.
 * @param strokeWidth il tratto: [GalleryProgressRingDefaults.stroke] della misura per un anello con il centro, `Thin` (di serie) per un badge.
 * @param animate vero per far avanzare l'arco con la molla (il pop-up, le pagine); falso (di serie) per i segni che si ridisegnano spesso.
 * @param content al centro (il percento, un glifo); null = solo l'anello. La descrizione per TalkBack la da' chi lo usa.
 */
@Composable
fun GalleryProgressRing(
  progress: Float?,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.primary,
  trackColor: Color = color.copy(alpha = GalleryProgressRingDefaults.TrackAlpha),
  strokeWidth: Dp = GalleryProgressRingDefaults.Thin,
  animate: Boolean = false,
  content: (@Composable BoxScope.() -> Unit)? = null,
) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val target = progress?.coerceIn(0f, 1f) ?: 0f
  val shown: () -> Float
  if (animate && progress != null) {
    val spring = remember { Animatable(target) }
    LaunchedEffect(target, reduced) {
      if (reduced) spring.snapTo(target) else spring.animateTo(target, FluidMotion.smooth())
    }
    shown = { spring.value }
  } else {
    shown = { target }
  }
  val spin: (() -> Float)?
  if (progress == null && !reduced) {
    val turn = rememberInfiniteTransition(label = "anello senza misura").animateFloat(0f, FullCircle, GalleryMotion.shimmerSpec(), label = "giro")
    spin = { turn.value }
  } else {
    spin = null
  }
  if (content == null) {
    Canvas(modifier = modifier) { drawRing(progress == null, shown, spin, color, trackColor, strokeWidth) }
  } else {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
      Canvas(modifier = Modifier.matchParentSize()) { drawRing(progress == null, shown, spin, color, trackColor, strokeWidth) }
      content()
    }
  }
}

private fun DrawScope.drawRing(busy: Boolean, shown: () -> Float, spin: (() -> Float)?, color: Color, track: Color, strokeWidth: Dp) {
  val stroke = strokeWidth.toPx()
  val inset = stroke / 2f
  val arcSize = Size(size.width - stroke, size.height - stroke)
  val topLeft = Offset(inset, inset)
  drawArc(color = track, startAngle = 0f, sweepAngle = FullCircle, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = stroke))
  if (busy) {
    val start = StartAtTop + (spin?.invoke() ?: 0f)
    drawArc(color = color, startAngle = start, sweepAngle = BusySweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
    return
  }
  val fraction = shown().coerceIn(0f, 1f)
  if (fraction > 0f) {
    drawArc(
      color = color,
      startAngle = StartAtTop,
      sweepAngle = FullCircle * fraction,
      useCenter = false,
      topLeft = topLeft,
      size = arcSize,
      style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
  }
}

/** Il colore dell'arco per una famiglia: lo stesso della sua piastrella ([rememberGalleryIconColors]). */
@Composable
fun galleryRingColor(family: GalleryIconFamily): Color = rememberGalleryIconColors(family).top

/**
 * Il percento al centro dell'anello («18%»), in cifre tabellari e in grassetto; si vede ma TalkBack non lo rilegge (la descrizione la da' la riga).
 * [style] di serie e' quello dell'anello di una riga.
 */
@Composable
fun GalleryRingPercent(progress: Float, style: TextStyle = MaterialTheme.typography.labelSmall) {
  Text(
    text = "${(progress.coerceIn(0f, 1f) * PercentScale).toInt()}%",
    style = style.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
    color = MaterialTheme.colorScheme.onSurface,
    maxLines = 1,
    modifier = Modifier.semantics { hideFromAccessibility() },
  )
}

private const val FullCircle = 360f
private const val StartAtTop = -90f
private const val BusySweep = 80f
private const val PercentScale = 100f
