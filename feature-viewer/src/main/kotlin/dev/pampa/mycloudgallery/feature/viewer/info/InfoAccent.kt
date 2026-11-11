package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap

/**
 * Il colore medio di [image] (il segnaposto della foto, gia' decodificato e in memoria: poche decine di pixel per lato), per tingere la scheda
 * dello scatto del pannello informazioni (giro di correzione 1 di H2: "un accento dalla foto, se lo hai senza costo"). Null se non si legge
 * (una bitmap che non si puo' leggere dalla CPU, un'immagine vuota): la scheda resta nel colore del tema.
 */
internal fun averageColor(image: ImageBitmap): Color? {
  if (image.width <= 0 || image.height <= 0) return null
  val pixels = try {
    image.toPixelMap()
  } catch (e: IllegalStateException) {
    return null
  } catch (e: UnsupportedOperationException) {
    return null
  }
  val stepX = maxOf(1, pixels.width / SAMPLES)
  val stepY = maxOf(1, pixels.height / SAMPLES)
  var red = 0f
  var green = 0f
  var blue = 0f
  var count = 0
  var y = 0
  while (y < pixels.height) {
    var x = 0
    while (x < pixels.width) {
      val c = pixels[x, y]
      red += c.red
      green += c.green
      blue += c.blue
      count++
      x += stepX
    }
    y += stepY
  }
  if (count == 0) return null
  return Color(red = red / count, green = green / count, blue = blue / count)
}

/** Al piu' tanti campioni per lato: il segnaposto e' gia' piccolo, e il colore medio non chiede di piu'. */
private const val SAMPLES = 24
