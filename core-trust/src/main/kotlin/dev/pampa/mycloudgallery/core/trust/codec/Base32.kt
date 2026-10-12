package dev.pampa.mycloudgallery.core.trust.codec

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.violation

/**
 * Base32 di Crockford, minuscolo e senza riempimento: l'unica codifica binario-testo del formato (id,
 * impronte, chiavi pubbliche, nomi di file). L'alfabeto esclude `i l o u`, quindi un id e' sempre un nome
 * di file sicuro su Windows e su Linux e non si confonde a voce o su carta.
 *
 * La decodifica e' STRETTA: un solo modo di scrivere ogni valore (lunghezza esatta, bit di coda a zero),
 * cosi' lo stesso dato non ha due nomi.
 */
object Base32 {

  private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"
  private val DECODE = IntArray(128) { -1 }.also { table ->
    ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
  }

  fun encode(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size * 8 + 4) / 5)
    var buffer = 0
    var bits = 0
    for (b in bytes) {
      buffer = (buffer shl 8) or (b.toInt() and 0xff)
      bits += 8
      while (bits >= 5) {
        out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
        bits -= 5
      }
    }
    if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
    return out.toString()
  }

  /** Lunghezza in caratteri della codifica di [byteCount] byte. */
  fun encodedLength(byteCount: Int): Int = (byteCount * 8 + 4) / 5

  /** Decodifica stretta; lancia una violazione [RejectReason.INVALID_FIELD] se non e' canonica. */
  fun decode(text: String): ByteArray {
    val byteCount = text.length * 5 / 8
    if (encodedLength(byteCount) != text.length) violation(RejectReason.INVALID_FIELD, "base32: lunghezza non valida")
    val out = ByteArray(byteCount)
    var buffer = 0
    var bits = 0
    var index = 0
    for (c in text) {
      val v = if (c.code < 128) DECODE[c.code] else -1
      if (v < 0) violation(RejectReason.INVALID_FIELD, "base32: carattere non ammesso")
      buffer = (buffer shl 5) or v
      bits += 5
      if (bits >= 8) {
        out[index++] = (buffer shr (bits - 8)).toByte()
        bits -= 8
      }
      buffer = buffer and 0xff
    }
    if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) {
      violation(RejectReason.INVALID_FIELD, "base32: bit di coda non nulli")
    }
    return out
  }

  /** Decodifica stretta che pretende esattamente [byteCount] byte. */
  fun decodeExact(text: String, byteCount: Int): ByteArray {
    if (text.length != encodedLength(byteCount)) violation(RejectReason.INVALID_FIELD, "base32: lunghezza inattesa")
    return decode(text)
  }

  fun isCanonical(text: String, byteCount: Int): Boolean =
    try {
      decodeExact(text, byteCount)
      true
    } catch (e: Exception) {
      false
    }

  /**
   * Lettura tollerante per i codici scritti a mano (kit di recupero, impronta inserita a mano): ignora
   * spazi e trattini, accetta le maiuscole e gli alias di Crockford (O→0, I/L→1). Il risultato poi passa
   * comunque dalla decodifica stretta.
   */
  fun normalizeHumanInput(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text) {
      when (c) {
        ' ', '-', '\t' -> Unit
        'O', 'o' -> sb.append('0')
        'I', 'i', 'L', 'l' -> sb.append('1')
        else -> sb.append(c.lowercaseChar())
      }
    }
    return sb.toString()
  }
}
