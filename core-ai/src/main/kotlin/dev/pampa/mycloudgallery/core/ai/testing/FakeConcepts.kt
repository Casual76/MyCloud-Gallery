package dev.pampa.mycloudgallery.core.ai.testing

import java.text.Normalizer
import java.util.Locale
import java.util.Random
import kotlin.math.sqrt

/**
 * I "concetti" dei motori finti (§5.2): una tabella di sinonimi italiani (cane, cani, cagnolino -> lo stesso concetto) proiettati in
 * modo **deterministico** in vettori a 768 dimensioni. Due testi con gli stessi concetti danno vettori quasi uguali; concetti diversi
 * danno vettori quasi ortogonali (punteggi relativi come quelli veri, non assoluti). Nessun dato personale: parole generiche.
 */
object FakeConcepts {

  const val DIMENSION: Int = 768

  /** Sinonimi e forme -> concetto. Le parole fuori tabella sono un concetto ciascuna ("w:<parola>"). */
  val SYNONYMS: Map<String, String> = buildMap {
    fun concept(name: String, vararg words: String) = words.forEach { put(it, name) }
    concept("cane", "cane", "cani", "cagnolino", "cagnolini", "cucciolo", "cuccioli", "dog")
    concept("gatto", "gatto", "gatti", "gattino", "gattini", "micio", "mici")
    concept("spiaggia", "spiaggia", "spiagge", "mare", "lido", "sabbia", "riva")
    concept("montagna", "montagna", "montagne", "monti", "monte", "vetta", "neve", "sci")
    concept("documento", "documento", "documenti", "foglio", "fogli", "carta", "modulo", "moduli")
    concept("scontrino", "scontrino", "scontrini", "ricevuta", "ricevute", "fattura", "fatture")
    concept("tramonto", "tramonto", "tramonti", "alba", "crepuscolo")
    concept("torta", "torta", "torte", "dolce", "dolci", "compleanno", "candeline")
    concept("auto", "auto", "macchina", "macchine", "automobile", "automobili")
    concept("bicicletta", "bicicletta", "biciclette", "bici")
    concept("fiore", "fiore", "fiori", "rosa", "rose", "margherita", "margherite")
    concept("albero", "albero", "alberi", "bosco", "boschi", "foresta")
    concept("pizza", "pizza", "pizze", "pizzeria")
    concept("neve", "nevicata", "nevicate", "pupazzo")
    concept("bambino", "bambino", "bambini", "bambina", "bambine", "neonato", "bimbo", "bimba")
    concept("screenshot", "screenshot", "schermata", "schermate")
  }

  /** Le parole che non portano contenuto (escono dai concetti, come i modelli veri le pesano poco). */
  val STOPWORDS: Set<String> = setOf(
    "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "di", "del", "della", "dei", "delle", "a", "al", "alla", "da", "in",
    "nel", "nella", "con", "su", "sul", "sulla", "per", "e", "o", "che", "foto", "immagine",
  )

  /** Minuscolo, senza accenti, apostrofi separati (come la normalizzazione locale del testo, D13). */
  fun tokens(text: String): List<String> {
    val plain = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    return plain.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
  }

  /** I concetti di un testo, senza parole vuote (un testo vuoto o fatto solo di parole vuote ha il concetto "vuoto"). */
  fun conceptsOf(text: String): List<String> {
    val concepts = tokens(text).filter { it !in STOPWORDS }.map { SYNONYMS[it] ?: "w:$it" }.distinct()
    return concepts.ifEmpty { listOf("vuoto") }
  }

  private val cache = HashMap<String, FloatArray>()

  /** Il vettore (normalizzato) di un concetto: gaussiano con un seme dal nome, sempre lo stesso. */
  fun vectorOf(concept: String): FloatArray = synchronized(cache) {
    cache.getOrPut(concept) { normalized(gaussian(seedOf(concept))) }.copyOf()
  }

  /** La somma normalizzata dei concetti, piu' un rumore deterministico di peso [noise] con il seme [noiseSeed]. */
  fun mix(concepts: List<String>, noise: Float = 0f, noiseSeed: String = ""): FloatArray {
    val out = FloatArray(DIMENSION)
    for (c in concepts) {
      val v = vectorOf(c)
      for (i in out.indices) out[i] += v[i]
    }
    val base = normalized(out)
    if (noise <= 0f) return base
    val n = normalized(gaussian(seedOf("noise:$noiseSeed")))
    return normalized(FloatArray(DIMENSION) { base[it] * (1 - noise) + n[it] * noise })
  }

  fun cosine(a: FloatArray, b: FloatArray): Float {
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

  fun normalized(v: FloatArray): FloatArray {
    val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
  }

  private fun gaussian(seed: Long): FloatArray {
    val random = Random(seed)
    return FloatArray(DIMENSION) { random.nextGaussian().toFloat() }
  }

  /** FNV-1a a 64 bit del nome: stabile fra JVM e Android. */
  private fun seedOf(name: String): Long {
    var h = -0x340d631b7bdddcdbL
    for (b in name.toByteArray(Charsets.UTF_8)) {
      h = h xor (b.toLong() and 0xff)
      h *= 0x100000001b3L
    }
    return h
  }
}
