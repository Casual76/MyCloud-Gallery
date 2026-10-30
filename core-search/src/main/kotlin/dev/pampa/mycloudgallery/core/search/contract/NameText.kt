package dev.pampa.mycloudgallery.core.search.contract

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * La normalizzazione Unicode dei NOMI delle persone (Fase 8, correzioni D; R9, RI-1, R2): **una sola**, per il dizionario della ricerca
 * ([PeopleDictionary.normalize]), per i suggerimenti e per la cronologia ([dev.pampa.mycloudgallery.core.search.history.HistoryNames]).
 *
 * Un nome e' fatto di **parole**: tratti di lettere e cifre Unicode (`\p{L}\p{N}`), in minuscolo e senza accento ("Nicolò" -> "nicolo",
 * "Анна" -> "анна", "李明" -> "李明"); tutto il resto (spazi, apostrofi, emoji, simboli, "?", il giunto ZWJ e i selettori di variante) separa.
 * Un nome senza nemmeno una parola ("❤", "?", un nome fatto solo di ZWJ e selettori) si normalizza a **vuoto**: [isSearchable] lo dice, e chi
 * costruisce il dizionario lo salta, **senza** spegnere la ricerca degli altri nomi.
 *
 * E' la stessa piegatura carattere per carattere del tokenizzatore delle query ([foldChar]): il nome scritto in Cerca e il nome salvato
 * diventano le stesse parole, in qualunque alfabeto. Non e' [SearchText.normalize] (solo `[a-z0-9]`, "€" -> "euro"), che resta la
 * normalizzazione del testo letto e dei luoghi. Puro e JVM: niente log, niente nomi in nessun messaggio (D37).
 */
object NameText {

  /** Le parole di [text], nell'ordine (vuota se il testo non ha lettere ne' cifre). */
  fun words(text: String): List<String> {
    val out = ArrayList<String>(4)
    val word = StringBuilder()
    for (c in text) {
      val f = foldChar(c)
      if (f.isLetterOrDigit()) {
        word.append(f)
      } else if (word.isNotEmpty()) {
        out += word.toString()
        word.setLength(0)
      }
    }
    if (word.isNotEmpty()) out += word.toString()
    return out
  }

  /** Le parole di [text] separate da uno spazio: la forma in cui i nomi stanno nel dizionario. Vuota se non ce ne sono. */
  fun normalize(text: String): String = words(text).joinToString(" ")

  /** Vero se il nome ha almeno una parola: un nome senza lettere ne' cifre non si puo' cercare per nome (resta testo). */
  fun isSearchable(name: String): Boolean = normalize(name).isNotEmpty()

  private val FOLD_CACHE = ConcurrentHashMap<Char, Char>()

  /** Un carattere -> un carattere: minuscolo e senza accento ("è" -> "e"); gli apostrofi e il resto non cambiano e poi separano. Lo usa anche `QueryTokenizer`. */
  internal fun foldChar(c: Char): Char {
    if (c in 'a'..'z' || c in '0'..'9') return c
    if (c in 'A'..'Z') return c + 32
    if (c.code < 128) return c
    return FOLD_CACHE.getOrPut(c) {
      val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
      val first = base.firstOrNull() ?: c
      first.lowercaseChar()
    }
  }
}
