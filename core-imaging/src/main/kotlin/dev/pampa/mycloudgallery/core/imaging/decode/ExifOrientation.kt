package dev.pampa.mycloudgallery.core.imaging.decode

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Gli otto orientamenti EXIF (E-V3, E-M15). Una foto si vede **sempre dritta**: chi decodifica ci arriva con il
 * proprio modo (JPEG: lo applica `ImageDecoder`; DNG e HEIC senza `irot`: lo applichiamo noi, vedi
 * [OrientationPolicy]), ma il risultato e' uno solo.
 *
 * Il valore e' quello del tag EXIF `Orientation`, come in `ExifInterface.ORIENTATION_*`.
 *
 * | EXIF | nome | trasformazione |
 * |---|---|---|
 * | 1 | [NORMAL] | nessuna |
 * | 2 | [FLIP_HORIZONTAL] | specchio orizzontale |
 * | 3 | [ROTATE_180] | 180 gradi |
 * | 4 | [FLIP_VERTICAL] | specchio verticale |
 * | 5 | [TRANSPOSE] | specchio sulla diagonale |
 * | 6 | [ROTATE_90] | 90 gradi in senso orario |
 * | 7 | [TRANSVERSE] | specchio sull'altra diagonale |
 * | 8 | [ROTATE_270] | 270 gradi in senso orario |
 */
enum class ExifOrientation(val exif: Int, val rotationDegrees: Int, val mirrored: Boolean) {
  NORMAL(1, 0, false),
  FLIP_HORIZONTAL(2, 0, true),
  ROTATE_180(3, 180, false),
  FLIP_VERTICAL(4, 180, true),
  TRANSPOSE(5, 90, true),
  ROTATE_90(6, 90, false),
  TRANSVERSE(7, 270, true),
  ROTATE_270(8, 270, false),
  ;

  /** Vero se larghezza e altezza si scambiano (5, 6, 7, 8): la proporzione di una foto di lato dipende da qui. */
  val swapsSides: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

  /** Le dimensioni dopo la trasformazione. */
  fun uprightSize(width: Int, height: Int): Pair<Int, Int> = if (swapsSides) height to width else width to height

  /**
   * Dove va a finire il pixel `(x, y)` di un'immagine `width` x `height`: le coordinate nell'immagine dritta. E'
   * l'**oracolo** dei test (la definizione dei sette orientamenti non banali) e serve anche a [transformPixels].
   */
  fun map(x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> = when (this) {
    NORMAL -> x to y
    FLIP_HORIZONTAL -> (width - 1 - x) to y
    ROTATE_180 -> (width - 1 - x) to (height - 1 - y)
    FLIP_VERTICAL -> x to (height - 1 - y)
    TRANSPOSE -> y to x
    ROTATE_90 -> (height - 1 - y) to x
    TRANSVERSE -> (height - 1 - y) to (width - 1 - x)
    ROTATE_270 -> y to (width - 1 - x)
  }

  /** Trasforma una matrice di pixel `width` x `height` (riga per riga). Per le bitmap piccole e per i test. */
  fun transformPixels(pixels: IntArray, width: Int, height: Int): IntArray {
    require(pixels.size == width * height) { "I pixel non corrispondono alle dimensioni." }
    if (this == NORMAL) return pixels.copyOf()
    val (outW, _) = uprightSize(width, height)
    val out = IntArray(pixels.size)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val (nx, ny) = map(x, y, width, height)
        out[ny * outW + nx] = pixels[y * width + x]
      }
    }
    return out
  }

  /**
   * La matrice equivalente: prima la rotazione, poi lo specchio orizzontale (cosi' 5 e' la trasposta e 7 la
   * trasversa). `Bitmap.createBitmap(.., matrix, ..)` ritraduce da solo l'origine.
   */
  fun matrix(): Matrix = Matrix().also {
    if (rotationDegrees != 0) it.postRotate(rotationDegrees.toFloat())
    if (mirrored) it.postScale(-1f, 1f)
  }

  /**
   * Applica l'orientamento a [bitmap] e restituisce la bitmap dritta. Se non c'e' niente da fare restituisce la
   * stessa; altrimenti ne crea una nuova (software) e **libera la vecchia** solo se [recycleSource] (e' di chi chiama
   * decidere: una bitmap che si sta ancora mostrando non si libera).
   */
  fun applyTo(bitmap: Bitmap, recycleSource: Boolean = false): Bitmap {
    if (this == NORMAL) return bitmap
    val rotated = try {
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix(), true)
    } catch (e: RuntimeException) {
      // Una bitmap con una gain map che il sistema non sa trasformare: si ruota senza (la foto resta SDR, mai nera).
      if (!GainmapFallback.drop(bitmap)) throw e
      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix(), true)
    }
    if (recycleSource && rotated !== bitmap) bitmap.recycle()
    return rotated
  }

  companion object {
    /** Dal valore del tag EXIF; un valore assente (0) o fuori scala e' [NORMAL]. */
    fun fromExif(value: Int): ExifOrientation = entries.firstOrNull { it.exif == value } ?: NORMAL
  }
}

/** Toglie la gain map da una bitmap (API 34+): serve solo come ripiego quando il sistema non sa trasformarla. */
internal object GainmapFallback {
  fun drop(bitmap: Bitmap): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !bitmap.hasGainmap()) return false
    bitmap.gainmap = null
    return true
  }
}
