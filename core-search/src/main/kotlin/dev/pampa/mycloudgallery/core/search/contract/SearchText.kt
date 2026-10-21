package dev.pampa.mycloudgallery.core.search.contract

import java.text.Normalizer
import java.util.Locale

/**
 * La normalizzazione **locale** del testo per cercare (D13, D25): minuscolo, senza accenti, apostrofi ed elisioni separati
 * ("dell'Esselunga" -> "dell", "esselunga"), "€" -> "euro", cifre come parole. La stessa per il testo letto (P1, indice FTS4 con il
 * tokenizzatore `simple`) e per le parole delle query (P7): uguale su ogni dispositivo e in Robolectric. Puo' cambiare senza
 * ricalcolare niente (il testo pubblicato resta com'e').
 */
object SearchText {

  fun normalize(text: String): String {
    val euro = text.replace("€", " euro ")
    val plain = Normalizer.normalize(euro.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")
    return plain.replace(NON_WORD, " ").trim().replace(SPACES, " ")
  }

  /** Le parole di [text] normalizzato, nell'ordine. */
  fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

  /** Le parole vuote che escono dal testo cercato (restano nel contenuto: il modello le usa, §2.5). */
  val STOPWORDS: Set<String> = setOf(
    "il", "lo", "la", "i", "gli", "le", "l", "un", "uno", "una", "di", "del", "dello", "della", "dei", "degli", "delle", "dell",
    "a", "al", "allo", "alla", "ai", "agli", "alle", "all", "da", "dal", "dalla", "dai", "in", "nel", "nello", "nella", "nei",
    "negli", "nelle", "nell", "con", "su", "sul", "sulla", "sui", "per", "tra", "fra", "e", "o", "ed", "che", "foto",
  )

  private val MARKS = Regex("\\p{M}+")
  private val NON_WORD = Regex("[^a-z0-9]+")
  private val SPACES = Regex(" +")
}
