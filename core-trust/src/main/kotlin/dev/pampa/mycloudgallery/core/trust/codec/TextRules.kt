package dev.pampa.mycloudgallery.core.trust.codec

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.violation

/**
 * Caratteri che non entrano mai in un testo letto dal NAS (nomi da mostrare, componenti di percorso):
 * controlli, controlli bidirezionali, caratteri invisibili e "non caratteri". L'elenco e' esplicito e
 * non dipende dalla versione di Unicode del dispositivo, cosi' tutti i dispositivi della famiglia
 * decidono allo stesso modo.
 *
 * Il ZERO WIDTH JOINER (U+200D) e i selettori di variante restano ammessi: servono alle emoji composte
 * ("Vacanze 👨‍👩‍👧", E-N6).
 */
object TextRules {

  fun isForbiddenCodePoint(cp: Int): Boolean =
    cp <= 0x1F || cp in 0x7F..0x9F || // controlli C0, DEL, C1
      cp == 0x00AD || // trattino morbido (invisibile)
      cp == 0x061C || // segno di lettera araba (bidi)
      cp == 0x180E || // separatore mongolo (invisibile)
      cp in 0x200B..0x200C || // zero width space / non-joiner
      cp in 0x200E..0x200F || // LRM, RLM
      cp in 0x2028..0x202E || // separatori di riga e paragrafo, LRE/RLE/PDF/LRO/RLO
      cp in 0x2060..0x206F || // word joiner, operatori invisibili, isolati bidi
      cp == 0xFEFF || // BOM / zero width no-break space
      cp in 0xFFF9..0xFFFB || // annotazioni interlineari
      cp in 0xFDD0..0xFDEF || (cp and 0xFFFE) == 0xFFFE || // non caratteri
      cp in 0xE0000..0xE007F // tag invisibili

  /** Vero se [text] contiene un carattere vietato o un surrogato spaiato. */
  fun hasForbidden(text: String): Boolean {
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return true
        if (isForbiddenCodePoint(Character.toCodePoint(c, text[i + 1]))) return true
        i += 2
        continue
      }
      if (Character.isLowSurrogate(c) || isForbiddenCodePoint(c.code)) return true
      i++
    }
    return false
  }

  /**
   * Un testo da mostrare (nome del dispositivo, modello, nome di un album): non vuoto, senza spazi ai
   * bordi, entro [maxChars], senza caratteri vietati.
   */
  fun displayText(text: String, maxChars: Int): String {
    if (text.isEmpty() || text.length > maxChars) violation(RejectReason.INVALID_FIELD, "testo: lunghezza non ammessa")
    if (text.trim() != text) violation(RejectReason.INVALID_FIELD, "testo: spazi ai bordi")
    if (hasForbidden(text)) violation(RejectReason.INVALID_FIELD, "testo: caratteri non ammessi")
    return text
  }

  /**
   * Nome di un account del NAS: ASCII stretto. Gli account si confrontano senza maiuscole/minuscole
   * ([sameAccount]) come fa Samba.
   */
  fun nasAccount(text: String): String {
    val ok = text.length in 1..64 &&
      text[0].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } &&
      text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
    if (!ok) violation(RejectReason.INVALID_FIELD, "account: nome non ammesso")
    return text
  }

  fun sameAccount(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

  /** Nome di host del NAS: lettere, cifre, punti, trattini; oppure un indirizzo IPv4/IPv6 letterale. */
  fun hostName(text: String): String {
    val ok = text.length in 1..253 &&
      text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == ':' || it == '[' || it == ']' }
    if (!ok) violation(RejectReason.INVALID_FIELD, "host: nome non ammesso")
    return text
  }
}
