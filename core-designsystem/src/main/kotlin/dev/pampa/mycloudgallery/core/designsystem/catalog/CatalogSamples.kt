package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens

/**
 * Le foto di prova del catalogo: **generate**, mai foto vere (niente dati personali, niente file in
 * piu' nell'APK).
 *
 * Servono a una cosa sola: provare i componenti che stanno *sopra* una foto (badge, vetro) sul peggio
 * che una foto puo' essere. Una foto bianca porta lo scrim a un grigio medio, una nera lo lascia
 * nero, una a righe non da' mai una tinta unita su cui il testo possa appoggiarsi.
 *
 * ECCEZIONE alla regola "nessun colore scritto a mano": le foto di prova non seguono il tema (una
 * foto non lo fa), quindi i loro colori sono *generati* da `Color.hsv` e non vengono dallo schema.
 * Non sono esadecimali e non escono dal catalogo.
 */
enum class SampleScene { Bright, Dark, Sunset, Meadow, Stripes }

private fun hsv(hue: Float, saturation: Float, value: Float): Color =
  Color.hsv(hue = hue.mod(FullTurn), saturation = saturation, value = value)

/** Una foto di prova a pieno spazio. */
@Composable
fun SamplePhoto(scene: SampleScene, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier.fillMaxSize()) {
    when (scene) {
      SampleScene.Bright -> {
        // Cielo coperto e sabbia: quasi bianco, il caso peggiore per un testo bianco.
        drawRect(Brush.verticalGradient(listOf(hsv(205f, 0.07f, 1f), hsv(45f, 0.10f, 0.98f))))
        drawCircle(hsv(48f, 0.10f, 1f), radius = size.minDimension * 0.22f, center = Offset(size.width * 0.72f, size.height * 0.28f))
      }
      SampleScene.Dark -> {
        // Notte: il caso peggiore per le barre, dove il vetro non deve schiarire.
        drawRect(Brush.verticalGradient(listOf(hsv(240f, 0.55f, 0.09f), hsv(268f, 0.45f, 0.22f))))
        drawCircle(hsv(48f, 0.10f, 0.95f), radius = size.minDimension * 0.07f, center = Offset(size.width * 0.78f, size.height * 0.22f))
      }
      SampleScene.Sunset -> {
        drawRect(Brush.verticalGradient(listOf(hsv(30f, 0.85f, 1f), hsv(335f, 0.65f, 0.72f), hsv(255f, 0.55f, 0.34f))))
        drawCircle(hsv(44f, 0.55f, 1f), radius = size.minDimension * 0.13f, center = Offset(size.width * 0.82f, size.height * 0.2f))
      }
      SampleScene.Meadow -> {
        drawRect(Brush.verticalGradient(listOf(hsv(204f, 0.45f, 0.96f), hsv(190f, 0.25f, 0.99f)), endY = size.height * 0.62f))
        drawCircle(hsv(125f, 0.55f, 0.66f), radius = size.width * 0.7f, center = Offset(size.width * 0.2f, size.height * 1.02f))
        drawCircle(hsv(140f, 0.65f, 0.42f), radius = size.width * 0.6f, center = Offset(size.width * 0.9f, size.height * 1.08f))
      }
      SampleScene.Stripes -> {
        // Righe fitte chiare e scure: nessuna tinta unita su cui un testo possa riposare.
        val stripe = 5.dp.toPx()
        var x = 0f
        var light = true
        while (x < size.width) {
          drawRect(
            color = if (light) hsv(0f, 0f, 0.96f) else hsv(0f, 0f, 0.1f),
            topLeft = Offset(x, 0f),
            size = Size(stripe, size.height),
          )
          x += stripe
          light = !light
        }
      }
    }
  }
}

/**
 * Una miniatura di prova: un gradiente di due toni dello stesso colore e un riflesso, il tono
 * deciso dal [seed]. Abbastanza "fotografica" da far leggere una griglia, abbastanza varia da far
 * vedere che i badge si leggono sopra colori diversi.
 */
@Composable
fun SampleThumb(seed: Int, modifier: Modifier = Modifier) {
  val hue = (seed * HueStep).toFloat()
  Canvas(modifier = modifier.fillMaxSize()) {
    drawRect(
      Brush.linearGradient(
        colors = listOf(hsv(hue, 0.55f, 0.9f), hsv(hue + 38f, 0.7f, 0.5f)),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
      ),
    )
    drawCircle(
      color = hsv(hue + 20f, 0.25f, 1f).copy(alpha = 0.55f),
      radius = size.minDimension * 0.28f,
      center = Offset(size.width * (0.3f + (seed % 3) * 0.2f), size.height * 0.32f),
    )
  }
}

/**
 * Una griglia di miniature **statica** (nessuna lista pigra), per i fondali: sotto una barra di vetro
 * serve qualcosa di nitido da rifrangere, e in una pagina non scorrevole una griglia vera non si
 * puo' annidare.
 */
@Composable
fun CatalogStaticGrid(
  columns: Int,
  rows: Int,
  modifier: Modifier = Modifier,
  gap: Dp = GalleryDimens.GridGap,
  firstSeed: Int = 0,
  shape: Shape = RectangleShape,
  cell: @Composable BoxScope.(index: Int) -> Unit = { SampleThumb(seed = it) },
) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
    repeat(rows) { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(columns) { column ->
          val index = firstSeed + row * columns + column
          Box(
            modifier = Modifier
              .weight(1f)
              .aspectRatio(1f)
              .clip(shape),
          ) { cell(index) }
        }
      }
    }
  }
}

/**
 * Il palco di un componente di vetro: una foto di prova che registra se stessa come fondale, e il
 * componente sopra, fuori dalla registrazione (una barra che campionasse se stessa e' il feedback
 * ottico che il vetro non nasconde).
 *
 * [photo] disegna il fondale (di default la foto di prova [scene]); il [content] riceve il fondale
 * registrato e lo passa ai suoi componenti di vetro.
 */
@Composable
fun CatalogPhotoStage(
  scene: SampleScene,
  height: Dp,
  modifier: Modifier = Modifier,
  shape: Shape = ContinuousCornerShape(FluidRadius.Group),
  scrim: Float = 0f,
  photo: @Composable BoxScope.() -> Unit = { SamplePhoto(scene, Modifier.matchParentSize()) },
  content: @Composable BoxScope.(GlassBackdropState) -> Unit,
) {
  val backdrop = rememberGlassBackdrop()
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(height)
      .clip(shape),
  ) {
    Box(modifier = Modifier.matchParentSize().glassBackdropSource(backdrop)) {
      photo()
      // Il velo di un pop-up aperto fa parte di cio che il vetro rifrange.
      if (scrim > 0f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = scrim)))
    }
    content(backdrop)
  }
}

private const val FullTurn = 360f
private const val HueStep = 47
