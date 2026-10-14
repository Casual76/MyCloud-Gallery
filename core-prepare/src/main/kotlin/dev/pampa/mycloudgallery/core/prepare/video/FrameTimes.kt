package dev.pampa.mycloudgallery.core.prepare.video

import android.graphics.Bitmap
import android.graphics.Matrix
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import kotlin.math.abs
import kotlin.math.ceil

/** Gli istanti da cui prendere i fotogrammi: la copertina e i fotogrammi chiave, in ordine di tempo, senza doppioni. */
internal class FramePlan(val coverMs: Long, val keyMs: List<Long>) {
  /** Tutti gli istanti, dalla copertina in poi, in ordine crescente. */
  val all: List<Long> get() = (listOf(coverMs) + keyMs).distinct().sorted()
}

internal object FrameTimes {
  /** Due istanti a meno di cosi' danno quasi sempre lo stesso fotogramma chiave: se ne prende uno. */
  const val MIN_GAP_MS = 250L

  /** La copertina sta al piu' a 1 s (il primo fotogramma e' spesso nero) o a meta' di un video piu' corto. */
  const val COVER_MAX_MS = 1_000L

  /**
   * §2.4, E-A9: "un fotogramma ogni 30 s, da 1 a 8, piu' la copertina". I fotogrammi chiave sono [PrepSpec.maxFrames] al piu',
   * uno ogni [PrepSpec.frameEverySec] secondi di video; sopra gli otto si distribuiscono su tutto il video (a un video di
   * un'ora non si guardano solo i primi quattro minuti). Stanno al centro di fette uguali.
   *
   * @param durationMs la durata del video; 0 o ignota = solo la copertina.
   * @param withKeys falso se serve solo la copertina (mancano solo le miniature).
   */
  fun plan(durationMs: Long, spec: PrepSpec, withKeys: Boolean): FramePlan {
    if (durationMs <= 0) return FramePlan(0, emptyList())
    val cover = minOf(COVER_MAX_MS, durationMs / 2)
    if (!withKeys) return FramePlan(cover, emptyList())
    val step = spec.frameEverySec * 1000L
    val count = ceil(durationMs.toDouble() / step).toInt().coerceIn(1, spec.maxFrames)
    val keys = ArrayList<Long>(count)
    for (i in 0 until count) {
      val t = ((i + 0.5) * durationMs / count).toLong().coerceIn(0, durationMs - 1)
      if (abs(t - cover) < MIN_GAP_MS || keys.any { abs(it - t) < MIN_GAP_MS }) continue
      keys += t
    }
    return FramePlan(cover, keys)
  }
}

/**
 * La rotazione di un fotogramma (§2.4: il video dritto, come la foto). `MediaMetadataRetriever` a volte restituisce il
 * fotogramma gia' ruotato e a volte no, secondo il decoder e la versione di Android; qui si decide **dalle proporzioni**:
 *
 * - rotazione 90 o 270 e video non quadrato: se il fotogramma ha lo stesso verso del video memorizzato non e' stato ruotato
 *   (si ruota qui), altrimenti lo ha gia' fatto il sistema. Vale per ogni fotogramma, qualunque sia il decoder;
 * - rotazione 180 o video quadrato: le proporzioni non dicono nulla; si usa quello che si e' visto l'ultima volta
 *   ([platformRotates]) e, se non si e' visto niente, si suppone che il sistema ruoti (e' il caso del decoder hardware).
 *   **Da verificare sul telefono (F6)** con un video girato in verticale e uno a testa in giu'.
 */
internal object FrameOrientation {
  class Decision(val degrees: Int, val platformRotated: Boolean?)

  fun decide(storedWidth: Int?, storedHeight: Int?, rotation: Int, frameWidth: Int, frameHeight: Int, learned: Boolean?): Decision {
    if (rotation == 0) return Decision(0, null)
    val sideways = rotation == 90 || rotation == 270
    if (sideways && storedWidth != null && storedHeight != null && storedWidth != storedHeight && frameWidth != frameHeight) {
      val sameVerse = (storedWidth > storedHeight) == (frameWidth > frameHeight)
      return if (sameVerse) Decision(rotation, false) else Decision(0, true)
    }
    return if (learned == false) Decision(rotation, null) else Decision(0, null)
  }

  /** Ruota [frame] di [degrees] gradi orari in una bitmap nuova. L'originale non si tocca: lo libera chi tiene il registro delle bitmap. */
  fun rotate(frame: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return frame
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
  }
}
