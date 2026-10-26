package dev.pampa.mycloudgallery.core.library.duplicates

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlin.math.sqrt

// Fase 7, P8 (docs/fase7-progetto.md D25, §2.1.1): COME SI GIUDICA la somiglianza di due scatti. Due strade, la prima che c'e' vince:
//  1. i **vettori** della Fase 6 (coseno ≥ `Sdup`): vedono anche scatti con una composizione un poco diversa;
//  2. la **firma visiva** della Fase 5 (F5-D36) con soglie piu' larghe di quelle delle copie simili: stessa inquadratura, soggetto che si muove.
// Se uno dei due scatti non ha ne' vettore ne' firma, il giudizio e' [Affinity.UNKNOWN]: mai "simili" nel dubbio.

/**
 * I vettori (gia' in memoria) di una manciata di scatti: sola lettura, uno per media, **normalizzati** a lunghezza 1. Un vettore di lunghezza
 * diversa da un altro non si confronta (un altro spazio vettoriale, E-I4). `:app` li legge dall'indice della Fase 6 (`LocalAiIndex`).
 */
class ShotVectors(vectors: Map<MediaId, FloatArray>) {
  private val unit: Map<MediaId, FloatArray> = vectors.mapNotNull { (id, v) -> normalized(v)?.let { id to it } }.toMap()

  val size: Int get() = unit.size

  operator fun contains(media: MediaId): Boolean = media in unit

  operator fun get(media: MediaId): FloatArray? = unit[media]

  override fun toString(): String = "ShotVectors($size)"

  companion object {
    val NONE: ShotVectors = ShotVectors(emptyMap())

    private fun normalized(v: FloatArray): FloatArray? {
      if (v.isEmpty()) return null
      var sum = 0.0
      for (x in v) {
        if (x.isNaN() || x.isInfinite()) return null
        sum += x.toDouble() * x
      }
      if (sum <= 0.0) return null
      val norm = sqrt(sum).toFloat()
      return FloatArray(v.size) { v[it] / norm }
    }
  }
}

/** Il giudizio dei vettori: coseno ≥ [sdup]. */
class VectorAffinity(private val vectors: ShotVectors, private val sdup: Float = SimilarShotsRules.SDUP) : ShotAffinity {

  override fun between(a: Shot, b: Shot): Affinity {
    val va = vectors[a.media] ?: return Affinity.UNKNOWN
    val vb = vectors[b.media] ?: return Affinity.UNKNOWN
    if (va.size != vb.size) return Affinity.UNKNOWN
    return if (SimilarShotsRules.cosine(va, vb) >= sdup) Affinity.SIMILAR else Affinity.DIFFERENT
  }
}

/** Il giudizio della firma visiva con le soglie degli scatti simili (`SimilarShotsRules.SIGNATURE_*`). */
class SignatureAffinity(private val signatures: Map<MediaId, VisualSignature>) : ShotAffinity {

  override fun between(a: Shot, b: Shot): Affinity {
    val sa = signatures[a.media] ?: return Affinity.UNKNOWN
    val sb = signatures[b.media] ?: return Affinity.UNKNOWN
    return SimilarShotsRules.signatureAffinity(sa, sb)
  }
}

/** Il primo giudizio che dice qualcosa: vettori, poi firma. */
class CombinedAffinity(private val first: ShotAffinity, private val second: ShotAffinity) : ShotAffinity {

  override fun between(a: Shot, b: Shot): Affinity {
    val verdict = first.between(a, b)
    return if (verdict == Affinity.UNKNOWN) second.between(a, b) else verdict
  }
}
