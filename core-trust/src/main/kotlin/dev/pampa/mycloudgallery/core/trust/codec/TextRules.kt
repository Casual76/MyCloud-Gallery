package dev.pampa.mycloudgallery.core.trust.codec

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.violation

/**
 * Caratteri che non entrano mai in un testo letto dal NAS (nomi da mostrare, componenti di percorso):
 * controlli, controlli bidirezionali, caratteri invisibili e "non caratteri". L'elenco e' esplicito e
 * non dipende dalla versione di Unicode del dispositivo, cosi' tutti i dispositivi della famiglia
 * decidono allo stesso modo.
 *
 * Il ZERO WIDTH JOINER (U+200D) e i selettori di presentazione U+FE0E/U+FE0F restano ammessi: servono
 * alle emoji composte (E-N6). Due nomi che differiscono solo per questi caratteri si scoprono con
 * `LookAlike` (revisione 1.4, B2).
 */
object TextRules {

  fun isForbiddenCodePoint(cp: Int): Boolean =
    cp <= 0x1F || cp in 0x7F..0x9F || // controlli C0, DEL, C1
      cp == 0x00AD || // trattino morbido (invisibile)
      cp == 0x034F || // combining grapheme joiner (invisibile)
      cp == 0x061C || // segno di lettera araba (bidi)
      cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || // riempitivi Hangul (sembrano vuoti)
      cp == 0x17B4 || cp == 0x17B5 || // vocali khmer invisibili
      cp in 0x180B..0x180F || // selettori e separatore mongoli
      cp == 0x2800 || // braille vuoto (sembra uno spazio)
      cp in 0xFE00..0xFE0D || // selettori di variante (restano FE0E/FE0F, testo/emoji)
      cp in 0xE0100..0xE01EF || // selettori di variante supplementari
      cp in 0x1D173..0x1D17A || // controlli di formato musicali (invisibili)
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
    val ok = text.length in 1..TrustLimits.MAX_NAS_ACCOUNT_CHARS &&
      text[0].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } &&
      text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
    if (!ok) violation(RejectReason.INVALID_FIELD, "account: nome non ammesso")
    return text
  }

  fun sameAccount(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

  /**
   * Nome di host del NAS: etichette di lettere, cifre e trattini separate da punti (vale anche per un IPv4),
   * oppure un IPv6 letterale tra parentesi quadre (solo cifre esadecimali, `:` e `.`).
   */
  fun hostName(text: String): String {
    val ok = text.length in 1..TrustLimits.MAX_HOST_CHARS && (isDnsName(text) || isIpv6Literal(text))
    if (!ok) violation(RejectReason.INVALID_FIELD, "host: nome non ammesso")
    return text
  }

  private fun isDnsName(text: String): Boolean = text.split('.').all { label ->
    label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
      label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
  }

  private fun isIpv6Literal(text: String): Boolean =
    text.length >= 4 && text.first() == '[' && text.last() == ']' && text.count { it == ':' } >= 2 &&
      text.substring(1, text.length - 1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
}
