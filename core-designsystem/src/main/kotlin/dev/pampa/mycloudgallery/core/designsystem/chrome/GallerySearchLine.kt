package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconPalette
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryProgressRingDefaults
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryMotion

// LA RIGA SOPRA LA BARRA DI RICERCA, NELLA FORMA DELLA FASE I (I2, 11/11 sera). L'utente: dopo una ricerca gli avvisi «occupano troppo»; «non tutto e'
// indicizzato» va detto «con una riga sola che indica con una barra di avanzamento animata (una cosa in positivo per il feeling dell'utente) che avanza».
// Quindi la riga e' un segno (o un'anteprima), al piu' due parole, e quando qualcosa avanza una barra corta che si allunga con la molla di FluidMotion e
// ogni tanto un riflesso che la percorre. TalkBack dice la frase intera.

/**
 * Che cosa mostra la riga sopra la barra di ricerca.
 *
 * @property description la frase intera, per TalkBack (e per chi la legge nei test).
 * @property text l'etichetta corta che si vede (al piu' due parole o un numero: «37%», «Simili»); null = solo il segno e la barra.
 * @property icon il segno davanti (se non c'e' [art]).
 * @property progress la frazione della barra (0..1); null = nessuna barra.
 * @property busy una barra che lavora senza sapere quanto manca (il motore che si carica): un riflesso che la percorre finche' la riga c'e'.
 * @property art un'anteprima tonda al posto del segno (la foto di "Trova simili"), gia' ritagliata dalla riga.
 */
@Immutable
class GallerySearchLineSpec(
  val description: String,
  val text: String? = null,
  val icon: ImageVector? = null,
  val progress: Float? = null,
  val busy: Boolean = false,
  val art: (@Composable () -> Unit)? = null,
) {
  override fun equals(other: Any?): Boolean =
    other is GallerySearchLineSpec && other.description == description && other.text == text && other.icon == icon && other.progress == progress &&
      other.busy == busy && (other.art == null) == (art == null)

  override fun hashCode(): Int = description.hashCode() * 31 + (text?.hashCode() ?: 0)
}

/**
 * La barra della riga sopra la barra di ricerca (I2): lo stesso colore e lo stesso tratto dell'anello della galleria (giro 1, «un solo anello»: il colore
 * della famiglia Ricerca, [galleryRingColor], la pista dello stesso colore al 28%, il tratto dell'anello di una riga). Il
 * riempimento **si allunga con la molla** di FluidMotion fino a [progress] (anche la prima volta, da zero: la riga che compare racconta che la ricerca
 * cresce), e a ogni passo avanti un riflesso lo percorre due volte. Con [progress] null la barra lavora senza misura: il riflesso percorre tutta la pista
 * finche' la barra c'e' (dura quanto il motore che si carica, mezzo secondo).
 *
 * **Costo**: tutto nel disegno (le animazioni si leggono nel `Canvas`, nessuna ricomposizione) e nessun fotogramma quando niente si muove: i riflessi sono
 * corse finite, non un ciclo infinito. Con «riduci animazioni» la barra e' ferma alla sua misura, senza riflessi.
 */
@Composable
fun GallerySearchProgress(progress: Float?, modifier: Modifier = Modifier) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val fillColor = galleryRingColor(GalleryIconFamily.Search)
  val track = fillColor.copy(alpha = GalleryProgressRingDefaults.TrackAlpha)
  val gleam = GalleryIconPalette.TileGlyph.copy(alpha = GleamAlpha)
  val target = progress?.coerceIn(0f, 1f)
  val fill = remember { Animatable(0f) }
  val sweep = remember { Animatable(SweepRest) }
  LaunchedEffect(target, reduced) {
    if (target == null) return@LaunchedEffect
    if (reduced) {
      fill.snapTo(target)
      sweep.snapTo(SweepRest)
      return@LaunchedEffect
    }
    fill.animateTo(target, FluidMotion.fluid())
  }
  LaunchedEffect(target, reduced) {
    if (target == null || reduced) return@LaunchedEffect
    repeat(SweepsPerStep) {
      sweep.snapTo(0f)
      sweep.animateTo(1f, GalleryMotion.sweepSpec())
    }
    sweep.snapTo(SweepRest)
  }
  // Senza misura (il motore che si carica): il riflesso percorre la pista finche' la barra c'e'.
  val busySweep = if (target == null && !reduced) {
    rememberInfiniteTransition(label = "riga che lavora").animateFloat(0f, 1f, GalleryMotion.shimmerSpec(), label = "riflesso")
  } else {
    null
  }
  Canvas(modifier = modifier.height(BarHeight)) {
    val radius = CornerRadius(size.height / 2f, size.height / 2f)
    drawRoundRect(color = track, cornerRadius = radius)
    val width = if (target == null) size.width else size.width * fill.value.coerceIn(0f, 1f)
    if (target != null && width > 0f) {
      drawRoundRect(
        color = fillColor,
        size = Size(width.coerceAtLeast(size.height), size.height),
        cornerRadius = radius,
      )
    }
    val at = busySweep?.value ?: sweep.value
    if (at in 0f..1f && width > 0f) {
      val band = size.width * GleamWidth
      val x = -band + (width + band) * at
      clipRect(right = width) {
        drawRect(
          brush = Brush.horizontalGradient(listOf(Color.Transparent, gleam, Color.Transparent), startX = x, endX = x + band),
          topLeft = Offset(x, 0f),
          size = Size(band, size.height),
        )
      }
    }
  }
}

/** L'altezza della barra: il tratto dell'anello di una riga (4 dp), come l'anello del pop-up della nuvola. */
private val BarHeight: Dp = GalleryProgressRingDefaults.stroke(GalleryProgressRingDefaults.Row)

private const val GleamAlpha = 0.65f

/** Il riflesso e' largo un terzo della pista. */
private const val GleamWidth = 0.35f

/** Quante volte il riflesso percorre la barra a ogni passo avanti. */
private const val SweepsPerStep = 2

/** Il riflesso fermo: fuori dalla barra. */
private const val SweepRest = 2f
