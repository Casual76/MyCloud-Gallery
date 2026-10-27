package dev.pampa.mycloudgallery.core.ai.testing

import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import java.util.Random
import kotlin.math.sqrt

// LE IDENTITA' SINTETICHE dei volti (docs/fase8-progetto.md §5.2, "Dati senza persone vere"): vettori a 128 dimensioni generati da un
// seme, con dispersione realistica, coppie "gemelli" vicine, deriva lenta per l'eta' e volti deboli piu' rumorosi. **Nessun volto, nessuna
// immagine, nessun nome di persone vere**: solo numeri. In src/main/testing per i test di P2, P4, P5, P9, P11, P12 (anche dal package
// `testing` di core-faces); mai nella release (tools/check.ps1).
//
// Le misure orientative con i valori di partenza: due volti CORE della stessa identita' hanno coseno ~0,5 (rumore 0,9), due identita'
// diverse ~0 +- 0,09; un gemello ha il centro a coseno [SyntheticIdentity.twinCosine] dall'altro.

/**
 * Un'identita' sintetica. [twinOf] = un'altra identita' a cui somiglia (centro a coseno [twinCosine]: gemelli, fratelli, E-F1);
 * [driftPerYear] = quanto il centro si sposta in un anno (bambini che crescono, E-F2).
 */
data class SyntheticIdentity(
  val id: Int,
  val twinOf: Int? = null,
  val twinCosine: Float = 0.7f,
  val driftPerYear: Float = 0.08f,
) {
  init {
    require(id >= 0) { "Identita' negativa." }
    require(twinOf == null || twinOf != id) { "Un'identita' non e' gemella di se stessa." }
    require(twinCosine in 0f..0.99f && driftPerYear >= 0f) { "Parametri fuori intervallo." }
  }
}

/** Il generatore deterministico dei vettori: stesso [seed], stesse identita', stessi vettori, su ogni macchina. */
class SyntheticIdentities(
  private val seed: Long,
  val dimension: Int = FaceSpaceCode.CURRENT.dimension,
) {
  private val defined = HashMap<Int, SyntheticIdentity>()
  private val centers = HashMap<Int, FloatArray>()
  private val computing = HashSet<Int>()

  /** Dichiara un'identita' (facoltativo: un id mai dichiarato e' un'identita' qualunque, senza gemelli). */
  @Synchronized
  fun define(identity: SyntheticIdentity): SyntheticIdentities {
    defined[identity.id] = identity
    centers.remove(identity.id)
    return this
  }

  /** Il centro normalizzato dell'identita' [id]. */
  @Synchronized
  fun center(id: Int): FloatArray = centers.getOrPut(id) { computeCenter(id) }.copyOf()

  private fun computeCenter(id: Int): FloatArray {
    val base = gaussian(Random(mix(seed, id.toLong(), 0x1d)))
    val twin = defined[id]?.twinOf ?: return normalize(base)
    // Un ciclo di gemelli (A di B, B di A) non ha un punto fisso: l'identita' gia' in calcolo resta un'identita' qualunque.
    if (!computing.add(id)) return normalize(base)
    // Un gemello: il centro dell'altro piu' una parte ortogonale, cosi' che il coseno fra i due centri sia twinCosine.
    val other = center(twin)
    val ortho = orthogonalize(base, other)
    computing.remove(id)
    val c = defined.getValue(id).twinCosine
    val s = sqrt(1f - c * c)
    return normalize(FloatArray(dimension) { other[it] * c + ortho[it] * s })
  }

  /**
   * Un volto dell'identita' [id]: centro + deriva di [ageYears] anni + rumore [noise] (deterministico per [sample]), normalizzato in
   * float32 e passato per float16 come si salva (§5.2). Rumore 0,9 = un volto CORE tipico, 1,4 = un volto WEAK (piccolo, mosso).
   */
  fun vector(id: Int, sample: Long, noise: Float, ageYears: Float = 0f): FloatArray {
    val center = center(id)
    val drift = defined[id]?.driftPerYear ?: DEFAULT_DRIFT
    val driftDir = normalize(orthogonalize(gaussian(Random(mix(seed, id.toLong(), 0x5e))), center))
    val n = gaussian(Random(mix(seed, id.toLong(), sample)))
    val scale = noise / sqrt(dimension.toFloat())
    val raw = FloatArray(dimension) { center[it] + driftDir[it] * drift * ageYears + n[it] * scale }
    return Float16.decode(Float16.encode(normalize(raw)))
  }

  private fun gaussian(random: Random): FloatArray = FloatArray(dimension) { random.nextGaussian().toFloat() }

  companion object {
    const val DEFAULT_DRIFT: Float = 0.08f

    /** Il rumore tipico di un volto CORE e di un volto WEAK. */
    const val CORE_NOISE: Float = 0.9f
    const val WEAK_NOISE: Float = 1.4f

    fun cosine(a: FloatArray, b: FloatArray): Float {
      require(a.size == b.size) { "Dimensioni diverse." }
      var dot = 0.0
      var na = 0.0
      var nb = 0.0
      for (i in a.indices) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
      }
      return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    fun normalize(v: FloatArray): FloatArray {
      var sum = 0.0
      for (x in v) sum += x * x
      val norm = sqrt(sum).toFloat()
      return FloatArray(v.size) { v[it] / norm }
    }

    private fun orthogonalize(v: FloatArray, against: FloatArray): FloatArray {
      var dot = 0f
      for (i in v.indices) dot += v[i] * against[i]
      return normalize(FloatArray(v.size) { v[it] - dot * against[it] })
    }

    /** Un seme da tre numeri (SplitMix64): nessuna correlazione fra identita' e campioni vicini. */
    internal fun mix(a: Long, b: Long, c: Long): Long {
      var z = a * -0x61c8864680b583ebL + b * 0x5851f42d4c957f2dL + c
      z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
      z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
      return z xor (z ushr 31)
    }
  }
}

/**
 * Un volto in una scena sintetica: l'identita', il riquadro in **frazioni** dell'immagine (0..1, cosi' la stessa scena vale a 1024 e a
 * 2048 px), il punteggio del rilevatore, l'imbardata, la nitidezza (0..255), il rumore del vettore, l'eta' e il fotogramma (0 per le foto).
 */
data class SyntheticFace(
  val identity: Int,
  val x0: Float,
  val y0: Float,
  val x1: Float,
  val y1: Float,
  val score: Float = 0.95f,
  val yaw: Int = 0,
  val sharpness: Int = 200,
  val noise: Float = SyntheticIdentities.CORE_NOISE,
  val ageYears: Float = 0f,
  val frame: Int = 0,
) {
  init {
    require(x0 in 0f..1f && x1 in 0f..1f && y0 in 0f..1f && y1 in 0f..1f && x0 < x1 && y0 < y1) { "Riquadro fuori dall'immagine." }
    require(score in 0f..1f && yaw in -90..90 && sharpness in 0..255 && noise >= 0f && frame in 0..8) { "Volto fuori intervallo." }
  }

  override fun toString(): String = "SyntheticFace($identity)"
}

/** Una scena (una foto o un video con i suoi fotogrammi): [id] unico nel motore finto, [width] x [height] le dimensioni di base. */
data class SyntheticScene(val id: Int, val width: Int, val height: Int, val faces: List<SyntheticFace>) {
  init {
    require(id >= 0 && width in 1..2048 && height in 1..2048) { "Scena fuori intervallo." }
    require(faces.size <= 64 * 9) { "Troppi volti." }
  }
}
