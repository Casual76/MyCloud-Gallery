package dev.pampa.mycloudgallery.feature.viewer.livetext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.feature.viewer.ScriptedImages
import dev.pampa.mycloudgallery.feature.viewer.Step

/**
 * Una foto di prova con il testo **disegnato davvero** (un foglio con righe dritte e inclinate), per gli screenshot: si vede se l'evidenziazione
 * e le maniglie cadono sul testo. Ogni parola e' disegnata negli estremi che la riga pubblica, quindi coincide con la geometria. Testi generici,
 * nessun dato personale.
 */
internal object LiveTextTestImage {

  const val WIDTH: Int = 1600
  const val HEIGHT: Int = 900

  fun bitmap(layout: ViewerTextLayout, width: Int = WIDTH, height: Int = HEIGHT): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(PAPER)
    val aspect = width.toFloat() / height
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = INK
      isSubpixelText = true
    }
    layout.lines.forEach { line ->
      val frame = LineFrame(line.box, line.angleDegrees, aspect)
      val length = frame.length * height
      val thickness = frame.thickness * height
      canvas.save()
      canvas.translate(frame.center.x * height, frame.center.y * height)
      canvas.rotate(line.angleDegrees.toFloat())
      paint.textSize = thickness * TEXT_FILL
      val words = TextSelections.wordsOf(line)
      line.words.forEachIndexed { index, span ->
        val word = words.getOrNull(index) ?: return@forEachIndexed
        val left = (span.start - 0.5f) * length
        val wordWidth = ((span.end - span.start) * length).coerceAtLeast(1f)
        paint.textScaleX = 1f
        paint.textScaleX = wordWidth / paint.measureText(word).coerceAtLeast(1f)
        canvas.drawText(word, left, thickness * BASELINE, paint)
      }
      canvas.restore()
    }
    return bitmap.asImageBitmap()
  }

  /** L'immagine che il viewer riceve per ogni media: la miniatura e poi la nitida, con il testo disegnato. */
  fun source(layout: ViewerTextLayout): ScriptedImages {
    val preview = bitmap(layout, WIDTH / 4, HEIGHT / 4)
    val sharp = bitmap(layout)
    return ScriptedImages {
      listOf(Step.Emit(ViewerImageState.Preview(preview)), Step.Emit(ViewerImageState.Sharp(sharp, hdr = false, colorSpace = null)))
    }
  }

  private const val PAPER = 0xFFF2EBDD.toInt()
  private const val INK = 0xFF2B2B2B.toInt()
  private const val TEXT_FILL = 0.8f
  private const val BASELINE = 0.3f
}
