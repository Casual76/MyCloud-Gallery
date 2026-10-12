package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.violation

/**
 * Le forme dei campi nuovi della configurazione di famiglia (`docs/formato-nas.md` §5.3, Fase 2): ognuna ha
 * **una sola scrittura** ammessa, cosi' due dispositivi non leggono lo stesso valore in due modi e la
 * configurazione resta canonica. Chi scrive la configurazione (fondazione, Impostazioni) passa prima dalle
 * funzioni `canonical`/`check` qui sotto; chi legge rifiuta tutto il resto con [RejectReason.INVALID_FIELD].
 */
object UlaPrefix {

  private val CANONICAL = Regex("fd[0-9a-f]{2}:[0-9a-f]{4}:[0-9a-f]{4}::/48")

  /** La forma del formato: `fdxx:xxxx:xxxx::/48`, quattro cifre esadecimali minuscole per gruppo. */
  fun isCanonical(text: String): Boolean = CANONICAL.matches(text)

  fun check(text: String): String {
    if (!isCanonical(text)) violation(RejectReason.INVALID_FIELD, "configurazione: prefisso ULA non valido")
    return text
  }

  /**
   * Converte una forma testuale qualunque di un prefisso ULA /48 (compressa alla RFC 5952, con maiuscole, con
   * zeri iniziali) nella forma del formato. Null se non e' un prefisso `fd00::/8` di lunghezza 48 con i bit
   * oltre il /48 a zero. Niente DNS, niente `InetAddress`: solo testo.
   */
  fun canonical(text: String): String? {
    val trimmed = text.trim().lowercase(java.util.Locale.ROOT)
    if (!trimmed.endsWith("/48") || trimmed.length > 64) return null
    val groups = expand(trimmed.removeSuffix("/48")) ?: return null
    if (groups[0] ushr 8 != 0xfd) return null
    if ((3 until 8).any { groups[it] != 0 }) return null
    return String.format(java.util.Locale.ROOT, "%04x:%04x:%04x::/48", groups[0], groups[1], groups[2])
  }

  /** Gli otto gruppi a 16 bit di un indirizzo IPv6 testuale, oppure null. */
  private fun expand(address: String): IntArray? {
    val halves = address.split("::")
    if (halves.size > 2) return null
    fun groupsOf(part: String): List<Int>? {
      if (part.isEmpty()) return emptyList()
      return part.split(':').map { g ->
        if (g.length !in 1..4 || !g.all { it in '0'..'9' || it in 'a'..'f' }) return null
        g.toInt(16)
      }
    }
    val head = groupsOf(halves[0]) ?: return null
    val tail = if (halves.size == 2) groupsOf(halves[1]) ?: return null else emptyList()
    val missing = 8 - head.size - tail.size
    if (halves.size == 1 && missing != 0) return null
    if (halves.size == 2 && missing < 1) return null
    return (head + List(missing) { 0 } + tail).toIntArray()
  }
}

/** Il GUID del server SMB (dalla NEGOTIATE), nella forma di `java.util.UUID.toString()`: minuscolo, 36 caratteri. */
object NasGuids {
  private val FORM = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

  fun isValid(text: String): Boolean = FORM.matches(text)

  fun check(text: String): String {
    if (!isValid(text)) violation(RejectReason.INVALID_FIELD, "configurazione: GUID del NAS non valido")
    return text
  }
}

/**
 * L'alimentazione del NAS (piano, proposta 14): `always` (sempre acceso, la scelta del 12/10) oppure una fascia
 * `HH:MM-HH:MM` in cui e' acceso (ora locale di casa, inizio diverso dalla fine). Da qui deriva la fascia notte.
 */
object NasPowerRule {
  const val ALWAYS: String = "always"
  private val WINDOW = Regex("([01][0-9]|2[0-3]):[0-5][0-9]-([01][0-9]|2[0-3]):[0-5][0-9]")

  fun isValid(text: String): Boolean =
    text == ALWAYS || (WINDOW.matches(text) && text.substring(0, 5) != text.substring(6))

  fun check(text: String): String {
    if (!isValid(text)) violation(RejectReason.INVALID_FIELD, "configurazione: alimentazione del NAS non valida")
    return text
  }
}

/** Il MAC per il Wake-on-LAN (inserito a mano, Fase 2.6): `aa:bb:cc:dd:ee:ff`, minuscolo. */
object MacAddresses {
  private val FORM = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

  fun isValid(text: String): Boolean = FORM.matches(text)

  fun check(text: String): String {
    if (!isValid(text)) violation(RejectReason.INVALID_FIELD, "configurazione: MAC non valido")
    return text
  }
}

/**
 * Un valore enumerato scritto dall'app nella configurazione (preset, qualita'): minuscolo ASCII, al massimo
 * [TrustLimits.MAX_ENUM_CHARS] caratteri. `core-trust` non conosce gli enum di `core-rules`: li valida come
 * forma, chi li usa li traduce (un valore sconosciuto vale "default").
 */
object ConfigTokens {
  private val FORM = Regex("[a-z][a-z0-9_]{0,${TrustLimits.MAX_ENUM_CHARS - 1}}")

  fun isValid(text: String): Boolean = FORM.matches(text)

  fun check(text: String): String {
    if (!isValid(text)) violation(RejectReason.INVALID_FIELD, "configurazione: valore non ammesso")
    return text
  }
}
