package dev.pampa.mycloudgallery.core.describe.rules

import dev.pampa.mycloudgallery.core.describe.contract.DescribeFact
import dev.pampa.mycloudgallery.core.describe.contract.DescribeLimits
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurfaces
import java.text.Normalizer

// LE REGOLE DELLE DESCRIZIONI (docs/fase10-progetto.md §1.2 "Regole", §2.6, D22; P7): cosa entra per superficie, la ripulitura del testo letto e
// dei nomi, i tetti. Funzioni pure, senza stato e senza orologio: le usano `DefaultMediaDescriber` e, in prova, le porte finte.

object DescriptionRules {

  /** Un nome di persona ripulito non supera questi caratteri. */
  const val MAX_NAME_CHARS: Int = 40

  /** Quanti nomi si tengono in memoria per un media: bastano per dire "e altri N" senza portarsi dietro un elenco senza fine. */
  const val MAX_NAMES_KEPT: Int = 50

  /** I pezzi che una superficie puo' dire (la tabella del §2.6). Notifica: nessuno; widget: tipo e data; dentro l'app e Nascosti: tutti. */
  fun facts(surface: DescribeSurface): Set<DescribeFact> = DescribeSurfaces.facts(surface)

  /** Le persone e il testo letto escono **solo** dentro l'app e nei Nascosti (D22): mai nel widget, mai in una notifica. */
  fun allowsPersonalFacts(surface: DescribeSurface): Boolean {
    val allowed = facts(surface)
    return DescribeFact.PEOPLE in allowed || DescribeFact.TEXT in allowed
  }

  /**
   * Il testo ripulito: NFC; via controlli, caratteri di direzione e di formato (zero width, isolati bidi), caratteri privati o spaiati e le
   * virgolette basse (che chiuderebbero la scritta); a capo, tabulazioni e spazi di ogni genere diventano un solo spazio. Al piu' [maxWords]
   * parole, con "…" se ce n'erano di piu'. Null se non resta niente.
   */
  fun cleanText(raw: String, maxWords: Int = DescribeLimits.MAX_WORDS): String? {
    if (maxWords <= 0) return null
    val words = words(raw) ?: return null
    if (words.size <= maxWords) return words.joinToString(" ")
    return words.take(maxWords).joinToString(" ") + "…"
  }

  /** Un nome di persona ripulito (al piu' [MAX_NAME_CHARS] caratteri, senza spezzare una coppia di surrogati). Null se non resta niente. */
  fun cleanName(raw: String): String? {
    val clean = words(raw)?.joinToString(" ") ?: return null
    if (clean.length <= MAX_NAME_CHARS) return clean
    var end = MAX_NAME_CHARS
    if (Character.isHighSurrogate(clean[end - 1])) end--
    return clean.substring(0, end).trimEnd().ifEmpty { null }
  }

  private fun words(raw: String): List<String>? {
    if (raw.isEmpty()) return null
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
    val out = StringBuilder(normalized.length)
    var i = 0
    while (i < normalized.length) {
      val cp = normalized.codePointAt(i)
      i += Character.charCount(cp)
      val type = Character.getType(cp)
      val drop = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
        type == Character.PRIVATE_USE.toInt() || cp == '«'.code || cp == '»'.code
      val space = drop || Character.isWhitespace(cp) || type == Character.SPACE_SEPARATOR.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
        type == Character.PARAGRAPH_SEPARATOR.toInt()
      if (space) out.append(' ') else out.appendCodePoint(cp)
    }
    val list = out.split(' ').filter { it.isNotEmpty() }
    return list.ifEmpty { null }
  }
}
