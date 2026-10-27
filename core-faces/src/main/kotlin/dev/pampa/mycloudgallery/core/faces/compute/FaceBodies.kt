package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.ai.contract.FloatBox
import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.model.FaceBox
import java.security.MessageDigest
import kotlin.math.roundToInt

// IL CORPO `faces` E LE SUE MISURE (docs/fase8-progetto.md §2.4 "Corpo faces", D19): da cio' che dicono i motori (pixel dell'immagine su cui
// hanno lavorato) a cio' che si salva (frazioni di 1/65535 dell'immagine raddrizzata, uguali su ogni sorgente) e l'impronta del corpo.

/**
 * La codifica del corpo `faces` del §2.4, big endian, **solo per calcolarne l'impronta** (la scrittura sul NAS e' del codec di P3,
 * `FacesDocs`): versione, spazio, rilevatore, ricetta, sorgente, N volti; per ogni volto fotogramma, riquadro, cinque punti, punteggio,
 * "livello e motivi" (2 bit di livello, i motivi sopra), lato corto, imbardata, nitidezza e, solo per CORE e WEAK, 128 componenti float16.
 */
internal object FaceBodyEncoding {
  const val VERSION: Int = 1

  fun encode(body: FaceBodyDraft): ByteArray {
    val out = java.io.ByteArrayOutputStream(6 + body.faces.size * FACE_BYTES_WITH_VECTOR)
    out.write(VERSION)
    out.write(body.space.wire)
    out.write(body.detector.wire)
    out.write(body.recipe)
    out.write(body.source.wire)
    out.write(body.faces.size)
    for (face in body.faces) {
      out.write(face.frame)
      val box = face.box
      u16(out, box.x0)
      u16(out, box.y0)
      u16(out, box.x1)
      u16(out, box.y1)
      for (point in face.landmarks) u16(out, point.toInt() and 0xFFFF)
      out.write(face.score)
      out.write((face.reasons shl 2) or face.tier.wire)
      u16(out, face.sidePx)
      out.write(face.yaw)
      out.write(face.sharpness)
      face.vector?.forEach { half -> u16(out, half.toInt() and 0xFFFF) }
    }
    return out.toByteArray()
  }

  private fun u16(out: java.io.ByteArrayOutputStream, value: Int) {
    out.write((value shr 8) and 0xFF)
    out.write(value and 0xFF)
  }

  /** Byte di un volto senza vettore e con (§2.4: 6 + 64 x 291 sta nei 20 KiB di A6). */
  const val FACE_BYTES_WITHOUT_VECTOR: Int = 35
  const val FACE_BYTES_WITH_VECTOR: Int = 35 + 256
}

/** L'impronta del §2.4: le prime 8 byte (big endian) dello SHA-256 del corpo codificato. */
object Sha256FaceBodyDigest : FaceBodyDigest {
  override fun digestOf(body: FaceBodyDraft): Long {
    val hash = MessageDigest.getInstance("SHA-256").digest(FaceBodyEncoding.encode(body))
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (hash[i].toLong() and 0xFF)
    return value
  }
}

/** Le misure in pixel dei motori portate nelle frazioni del corpo `faces` (1/65535 dell'immagine su cui hanno lavorato). */
internal object FaceGeometry {
  private const val MAX = FaceBox.MAX

  fun fraction(value: Float, size: Int): Int = (value / size * MAX).roundToInt().coerceIn(0, MAX)

  /** Il riquadro del rilevatore in frazioni, sempre con `x0 < x1` e `y0 < y1` (anche per un riquadro piu' sottile di un'unita'). */
  fun box(box: FloatBox, width: Int, height: Int): FaceBox {
    var x0 = fraction(box.x0, width)
    var y0 = fraction(box.y0, height)
    var x1 = fraction(box.x1, width)
    var y1 = fraction(box.y1, height)
    if (x1 <= x0) {
      if (x0 < MAX) {
        x1 = x0 + 1
      } else {
        x0 = MAX - 1
        x1 = MAX
      }
    }
    if (y1 <= y0) {
      if (y0 < MAX) {
        y1 = y0 + 1
      } else {
        y0 = MAX - 1
        y1 = MAX
      }
    }
    return FaceBox(x0, y0, x1, y1)
  }

  /** I cinque punti (x, y alternati) in frazioni, come `Short` senza segno (i bit di 0..65535). */
  fun landmarks(points: FloatArray, width: Int, height: Int): ShortArray =
    ShortArray(points.size) { i -> fraction(points[i], if (i % 2 == 0) width else height).toShort() }

  fun score(score: Float): Int = (score * 255f).roundToInt().coerceIn(0, 255)
}
