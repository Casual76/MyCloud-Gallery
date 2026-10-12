package dev.pampa.mycloudgallery.core.trust.codec

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.violation

/**
 * Lettore tipizzato e stretto dei campi di un oggetto JSON letto dal NAS. Ogni campo ha tipo, tetto e
 * intervallo; [end] rifiuta i campi sconosciuti: nella stessa versione del formato un campo in piu' non
 * e' "ignorato", e' un errore (una novita' aumenta la versione, E-I3).
 */
class Fields private constructor(private val obj: JsonObject) {

  private val read = HashSet<String>()

  private fun take(key: String): JsonValue? {
    read.add(key)
    return obj.members[key]
  }

  private fun required(key: String): JsonValue = take(key) ?: violation(RejectReason.MALFORMED, "campo obbligatorio assente")

  fun string(key: String, maxChars: Int): String = optString(key, maxChars) ?: violation(RejectReason.MALFORMED, "campo obbligatorio assente")

  fun optString(key: String, maxChars: Int): String? {
    val v = take(key) ?: return null
    if (v !is JsonString) violation(RejectReason.MALFORMED, "campo: stringa attesa")
    if (v.value.length > maxChars) violation(RejectReason.TOO_LARGE, "campo: testo troppo lungo")
    return v.value
  }

  fun long(key: String, range: LongRange): Long {
    val v = required(key)
    if (v !is JsonInt) violation(RejectReason.MALFORMED, "campo: intero atteso")
    if (v.value !in range) violation(RejectReason.INVALID_FIELD, "campo: intero fuori intervallo")
    return v.value
  }

  fun time(key: String): Long = long(key, TrustLimits.MIN_PLAUSIBLE_TIME_MILLIS..TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS)

  fun bool(key: String): Boolean {
    val v = required(key)
    if (v !is JsonBool) violation(RejectReason.MALFORMED, "campo: booleano atteso")
    return v.value
  }

  fun array(key: String, maxItems: Int): List<JsonValue> = optArray(key, maxItems) ?: violation(RejectReason.MALFORMED, "campo obbligatorio assente")

  fun optArray(key: String, maxItems: Int): List<JsonValue>? {
    val v = take(key) ?: return null
    if (v !is JsonArray) violation(RejectReason.MALFORMED, "campo: array atteso")
    if (v.items.size > maxItems) violation(RejectReason.TOO_LARGE, "campo: troppi elementi")
    return v.items
  }

  fun <T> obj(key: String, block: (Fields) -> T): T {
    val v = required(key)
    return read(v, block)
  }

  /** Rifiuta ogni campo non letto. Va chiamato alla fine di ogni oggetto. */
  fun end() {
    if (!read.containsAll(obj.members.keys)) violation(RejectReason.MALFORMED, "campo sconosciuto")
  }

  companion object {
    /** Legge [value] come oggetto con [block] e controlla che non restino campi sconosciuti. */
    fun <T> read(value: JsonValue, block: (Fields) -> T): T {
      if (value !is JsonObject) violation(RejectReason.MALFORMED, "oggetto atteso")
      val fields = Fields(value)
      val result = block(fields)
      fields.end()
      return result
    }

    fun asString(value: JsonValue, maxChars: Int): String {
      if (value !is JsonString) violation(RejectReason.MALFORMED, "stringa attesa")
      if (value.value.length > maxChars) violation(RejectReason.TOO_LARGE, "testo troppo lungo")
      return value.value
    }
  }
}
