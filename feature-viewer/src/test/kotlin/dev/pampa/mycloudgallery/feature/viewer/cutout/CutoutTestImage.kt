package dev.pampa.mycloudgallery.feature.viewer.cutout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.feature.viewer.ScriptedImages
import dev.pampa.mycloudgallery.feature.viewer.Step

/**
 * Una foto di prova con un soggetto **disegnato davvero** (un piccolo robot arancione su un prato, sotto un cielo), per gli screenshot e per le prove
 * del disegno: la maschera che il ritagliatore finto restituisce e' fatta con gli stessi tratti, quindi il soggetto che si stacca e' quello che si
 * vede. Nessun dato personale: forme geometriche. Proporzione 16:9 come le altre prove del viewer.
 */
internal object CutoutTestImage {

  const val WIDTH: Int = 1600
  const val HEIGHT: Int = 900

  /** Il punto (normalizzato) sul corpo del robot. */
  const val BODY_X: Float = 0.5f
  const val BODY_Y: Float = 0.62f

  /** Un punto sul prato, fuori dal robot. */
  const val GRASS_X: Float = 0.12f
  const val GRASS_Y: Float = 0.85f

  /** Il soggetto con i colori veri (`true`) o in nero pieno, per farne la maschera (`false`). */
  private fun drawRobot(canvas: Canvas, w: Int, h: Int, colored: Boolean) {
    val s = h / 900f
    val cx = w * BODY_X
    fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = if (colored) color else 0xFF000000.toInt() }
    // Antenna, testa, corpo, braccia, gambe.
    canvas.drawRect(cx - 5 * s, 130 * s, cx + 5 * s, 230 * s, paint(0xFF444444.toInt()))
    canvas.drawCircle(cx, 120 * s, 22 * s, paint(0xFFE53935.toInt()))
    canvas.drawRoundRect(RectF(cx - 130 * s, 220 * s, cx + 130 * s, 400 * s), 60 * s, 60 * s, paint(0xFFFFC107.toInt()))
    canvas.drawRoundRect(RectF(cx - 190 * s, 400 * s, cx + 190 * s, 700 * s), 70 * s, 70 * s, paint(0xFFFF7043.toInt()))
    canvas.drawRoundRect(RectF(cx - 270 * s, 430 * s, cx - 190 * s, 620 * s), 40 * s, 40 * s, paint(0xFFFF7043.toInt()))
    canvas.drawRoundRect(RectF(cx + 190 * s, 430 * s, cx + 270 * s, 620 * s), 40 * s, 40 * s, paint(0xFFFF7043.toInt()))
    canvas.drawRoundRect(RectF(cx - 140 * s, 700 * s, cx - 40 * s, 800 * s), 24 * s, 24 * s, paint(0xFF5D4037.toInt()))
    canvas.drawRoundRect(RectF(cx + 40 * s, 700 * s, cx + 140 * s, 800 * s), 24 * s, 24 * s, paint(0xFF5D4037.toInt()))
    if (colored) {
      // Occhi e bottone, solo nei colori veri (sono dentro il soggetto: la maschera li ha gia' coperti).
      canvas.drawCircle(cx - 50 * s, 300 * s, 20 * s, paint(0xFFFFFFFF.toInt()))
      canvas.drawCircle(cx + 50 * s, 300 * s, 20 * s, paint(0xFFFFFFFF.toInt()))
      canvas.drawCircle(cx - 50 * s, 300 * s, 9 * s, paint(0xFF263238.toInt()))
      canvas.drawCircle(cx + 50 * s, 300 * s, 9 * s, paint(0xFF263238.toInt()))
      canvas.drawCircle(cx, 560 * s, 26 * s, paint(0xFF1E88E5.toInt()))
    }
  }

  fun bitmap(width: Int = WIDTH, height: Int = HEIGHT): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val sky = Paint().apply { shader = LinearGradient(0f, 0f, 0f, height * 0.65f, 0xFF4FA3E8.toInt(), 0xFFCDE8FA.toInt(), Shader.TileMode.CLAMP) }
    canvas.drawRect(0f, 0f, width.toFloat(), height * 0.68f, sky)
    val grass = Paint().apply { shader = LinearGradient(0f, height * 0.65f, 0f, height.toFloat(), 0xFF6BBF59.toInt(), 0xFF2E7D32.toInt(), Shader.TileMode.CLAMP) }
    canvas.drawRect(0f, height * 0.65f, width.toFloat(), height.toFloat(), grass)
    // Due alberi e le nuvole: sfondo senza bordi che somiglino al soggetto.
    val trunk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6D4C41.toInt() }
    val leaves = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt() }
    for (x in listOf(0.14f, 0.86f)) {
      canvas.drawRect(width * x - 14, height * 0.5f, width * x + 14, height * 0.7f, trunk)
      canvas.drawCircle(width * x, height * 0.42f, height * 0.12f, leaves)
    }
    val cloud = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    canvas.drawCircle(width * 0.28f, height * 0.16f, height * 0.06f, cloud)
    canvas.drawCircle(width * 0.33f, height * 0.14f, height * 0.08f, cloud)
    canvas.drawCircle(width * 0.39f, height * 0.17f, height * 0.06f, cloud)
    drawRobot(canvas, width, height, colored = true)
    return bitmap.asImageBitmap()
  }

  /** La maschera del soggetto alla taglia [width]x[height], disegnata con gli stessi tratti. */
  fun mask(width: Int = 640, height: Int = 360): ViewerMask {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    drawRobot(Canvas(bitmap), width, height, colored = false)
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    bitmap.recycle()
    return ViewerMask(width, height, ByteArray(width * height) { (pixels[it] ushr 24).toByte() })
  }

  /** Il soggetto con il suo rettangolo (frazioni dell'immagine), come lo darebbe il ritagliatore. */
  fun subject(width: Int = 640, height: Int = 360): ViewerCutout.Subject {
    val mask = mask(width, height)
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (i in 0 until width * height) {
      if (mask.at(i % width, i / width) >= 128) {
        val x = i % width
        val y = i / width
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
      }
    }
    return ViewerCutout.Subject(mask, ViewerRect(left.toFloat() / width, top.toFloat() / height, (right + 1f) / width, (bottom + 1f) / height))
  }

  /** L'immagine che il viewer riceve per ogni media: la miniatura e poi la nitida. */
  fun source(): ScriptedImages {
    val preview = bitmap(WIDTH / 4, HEIGHT / 4)
    val sharp = bitmap()
    return ScriptedImages {
      listOf(Step.Emit(ViewerImageState.Preview(preview)), Step.Emit(ViewerImageState.Sharp(sharp, hdr = false, colorSpace = null)))
    }
  }

  /** Un ritagliatore finto che da' il robot se il punto cade sul robot, altrimenti nessun soggetto (come un modello vero). */
  fun cutter(latencyMs: Long = 0): dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerSubjectCutter {
    val subject = subject()
    return dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerSubjectCutter(
      latencyMs = latencyMs,
      result = { x, y -> if (subject.contains(x, y)) subject else ViewerCutout.NoSubject },
    )
  }
}
