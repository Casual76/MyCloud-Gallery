package dev.pampa.mycloudgallery.core.trust.codec

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.violation
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.TreeMap

/**
 * Il sottoinsieme di JSON del formato (`docs/formato-nas.md` §3): oggetti, array, stringhe, interi a 64
 * bit e booleani. Niente `null`, niente numeri con virgola o esponente.
 */
sealed interface JsonValue

data class JsonString(val value: String) : JsonValue

data class JsonInt(val value: Long) : JsonValue

data class JsonBool(val value: Boolean) : JsonValue

data class JsonArray(val items: List<JsonValue>) : JsonValue

/** Le chiavi sono identificatori ASCII ([CanonicalJson.isValidKey]), ordinate. */
class JsonObject(members: Map<String, JsonValue>) : JsonValue {
  val members: Map<String, JsonValue> = TreeMap(members)

  override fun equals(other: Any?): Boolean = other is JsonObject && other.members == members

  override fun hashCode(): Int = members.hashCode()

  override fun toString(): String = "JsonObject(${members.keys})"
}

/** Costruzione comoda di un oggetto: le voci con valore null si omettono (i campi facoltativi). */
fun jsonObject(vararg entries: Pair<String, JsonValue?>): JsonObject =
  JsonObject(entries.filter { it.second != null }.associate { it.first to it.second!! })

fun String.json(): JsonString = JsonString(this)

fun Long.json(): JsonInt = JsonInt(this)

fun Int.json(): JsonInt = JsonInt(this.toLong())

fun Boolean.json(): JsonBool = JsonBool(this)

fun List<JsonValue>.json(): JsonArray = JsonArray(this)

/**
 * Serializzazione canonica (unica per ogni valore) e lettura difensiva.
 *
 * Forma canonica: UTF-8 senza BOM; nessuno spazio fuori dalle stringhe; chiavi in ordine crescente;
 * stringhe con i soli escape `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t` e `\u00xx` (minuscolo) per gli
 * altri caratteri di controllo, tutto il resto letterale; interi senza zeri iniziali e senza `-0`.
 *
 * Chi verifica controlla prima la firma sui byte, poi legge con [parse] e infine pretende che la
 * ri-serializzazione coincida byte per byte ([parseCanonical]): due programmi non possono mai leggere
 * lo stesso file firmato in due modi diversi.
 */
object CanonicalJson {

  data class Limits(
    val maxDepth: Int = TrustLimits.MAX_JSON_DEPTH,
    val maxStringChars: Int = TrustLimits.MAX_JSON_STRING_CHARS,
    val maxKeyChars: Int = TrustLimits.MAX_JSON_KEY_CHARS,
    val maxObjectMembers: Int = TrustLimits.MAX_JSON_OBJECT_MEMBERS,
    val maxArrayItems: Int = TrustLimits.MAX_JSON_ARRAY_ITEMS,
  )

  private val DEFAULT_LIMITS = Limits()

  /** Chiavi: lettera ASCII seguita da lettere o cifre ASCII. Niente Unicode, niente ordinamenti ambigui. */
  fun isValidKey(key: String, maxChars: Int = TrustLimits.MAX_JSON_KEY_CHARS): Boolean =
    key.length in 1..maxChars &&
      key[0].let { it in 'a'..'z' || it in 'A'..'Z' } &&
      key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

  fun encode(value: JsonValue): ByteArray {
    val sb = StringBuilder()
    write(sb, value)
    return utf8Strict(sb)
  }

  /** Legge e pretende la forma canonica. */
  fun parseCanonical(bytes: ByteArray, limits: Limits = DEFAULT_LIMITS): JsonValue {
    val value = parse(bytes, limits)
    if (!encode(value).contentEquals(bytes)) violation(RejectReason.NOT_CANONICAL, "json: non canonico")
    return value
  }

  fun parse(bytes: ByteArray, limits: Limits = DEFAULT_LIMITS): JsonValue {
    val text = decodeUtf8Strict(bytes)
    val parser = Parser(text, limits)
    val value = parser.readValue(depth = 1)
    if (parser.pos != text.length) violation(RejectReason.MALFORMED, "json: dati dopo la fine")
    return value
  }

  // --- Scrittura ------------------------------------------------------------------------------------

  private fun write(sb: StringBuilder, value: JsonValue) {
    when (value) {
      is JsonString -> writeString(sb, value.value)
      is JsonInt -> sb.append(value.value)
      is JsonBool -> sb.append(if (value.value) "true" else "false")
      is JsonArray -> {
        sb.append('[')
        value.items.forEachIndexed { i, item ->
          if (i > 0) sb.append(',')
          write(sb, item)
        }
        sb.append(']')
      }
      is JsonObject -> {
        sb.append('{')
        var first = true
        for ((key, member) in value.members) {
          require(isValidKey(key)) { "Chiave JSON non ammessa nel formato." }
          if (!first) sb.append(',')
          first = false
          writeString(sb, key)
          sb.append(':')
          write(sb, member)
        }
        sb.append('}')
      }
    }
  }

  private fun writeString(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
      when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\b' -> sb.append("\\b")
        c == '\u000C' -> sb.append("\\f")
        c == '\n' -> sb.append("\\n")
        c == '\r' -> sb.append("\\r")
        c == '\t' -> sb.append("\\t")
        c.code < 0x20 -> sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xf])
        else -> sb.append(c)
      }
    }
    sb.append('"')
  }

  private const val HEX = "0123456789abcdef"

  private fun utf8Strict(text: CharSequence): ByteArray {
    val encoder = StandardCharsets.UTF_8.newEncoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val buffer = try {
      encoder.encode(java.nio.CharBuffer.wrap(text))
    } catch (e: CharacterCodingException) {
      // Un surrogato spaiato non ha una codifica UTF-8: non si scrive.
      throw IllegalArgumentException("Testo non rappresentabile in UTF-8.", e)
    }
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
  }

  private fun decodeUtf8Strict(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
      decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
      violation(RejectReason.MALFORMED, "json: UTF-8 non valido")
    }
  }

  // --- Lettura ------------------------------------------------------------------------------------------

  /**
   * Discesa ricorsiva con profondita' limitata ([Limits.maxDepth], pochi livelli: lo stack non e' mai un
   * problema). Nessuno spazio ammesso fuori dalle stringhe: la forma canonica non ne ha.
   */
  private class Parser(private val s: String, private val limits: Limits) {
    var pos = 0

    fun readValue(depth: Int): JsonValue {
      if (depth > limits.maxDepth) violation(RejectReason.TOO_LARGE, "json: troppo profondo")
      if (pos >= s.length) violation(RejectReason.MALFORMED, "json: fine inattesa")
      return when (val c = s[pos]) {
        '{' -> readObject(depth)
        '[' -> readArray(depth)
        '"' -> JsonString(readString(limits.maxStringChars))
        't' -> literal("true", JsonBool(true))
        'f' -> literal("false", JsonBool(false))
        else -> if (c == '-' || c in '0'..'9') readInt() else violation(RejectReason.MALFORMED, "json: valore non ammesso")
      }
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
      if (!s.startsWith(word, pos)) violation(RejectReason.MALFORMED, "json: letterale non valido")
      pos += word.length
      return value
    }

    private fun readObject(depth: Int): JsonObject {
      pos++ // {
      val members = LinkedHashMap<String, JsonValue>()
      if (peek() == '}') {
        pos++
        return JsonObject(members)
      }
      while (true) {
        if (peek() != '"') violation(RejectReason.MALFORMED, "json: chiave attesa")
        val key = readString(limits.maxKeyChars)
        if (!isValidKey(key, limits.maxKeyChars)) violation(RejectReason.MALFORMED, "json: chiave non ammessa")
        if (members.containsKey(key)) violation(RejectReason.MALFORMED, "json: chiave ripetuta")
        expect(':')
        members[key] = readValue(depth + 1)
        if (members.size > limits.maxObjectMembers) violation(RejectReason.TOO_LARGE, "json: troppi campi")
        when (next()) {
          ',' -> continue
          '}' -> return JsonObject(members)
          else -> violation(RejectReason.MALFORMED, "json: separatore atteso")
        }
      }
    }

    private fun readArray(depth: Int): JsonArray {
      pos++ // [
      val items = ArrayList<JsonValue>()
      if (peek() == ']') {
        pos++
        return JsonArray(items)
      }
      while (true) {
        items.add(readValue(depth + 1))
        if (items.size > limits.maxArrayItems) violation(RejectReason.TOO_LARGE, "json: array troppo lungo")
        when (next()) {
          ',' -> continue
          ']' -> return JsonArray(items)
          else -> violation(RejectReason.MALFORMED, "json: separatore atteso")
        }
      }
    }

    private fun readString(maxChars: Int): String {
      pos++ // "
      val sb = StringBuilder()
      while (true) {
        if (pos >= s.length) violation(RejectReason.MALFORMED, "json: stringa non chiusa")
        val c = s[pos++]
        when {
          c == '"' -> break
          c == '\\' -> sb.append(readEscape())
          c.code < 0x20 -> violation(RejectReason.MALFORMED, "json: controllo non escapato")
          else -> sb.append(c)
        }
        if (sb.length > maxChars) violation(RejectReason.TOO_LARGE, "json: stringa troppo lunga")
      }
      // Surrogati spaiati (anche da \ud800): testo non valido, mai accettato.
      for (i in sb.indices) {
        val ch = sb[i]
        if (Character.isHighSurrogate(ch)) {
          if (i + 1 >= sb.length || !Character.isLowSurrogate(sb[i + 1])) violation(RejectReason.MALFORMED, "json: surrogato spaiato")
        } else if (Character.isLowSurrogate(ch)) {
          if (i == 0 || !Character.isHighSurrogate(sb[i - 1])) violation(RejectReason.MALFORMED, "json: surrogato spaiato")
        }
      }
      return sb.toString()
    }

    private fun readEscape(): Char {
      if (pos >= s.length) violation(RejectReason.MALFORMED, "json: escape troncato")
      return when (s[pos++]) {
        '"' -> '"'
        '\\' -> '\\'
        '/' -> '/'
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
          if (pos + 4 > s.length) violation(RejectReason.MALFORMED, "json: escape troncato")
          var v = 0
          repeat(4) {
            val d = Character.digit(s[pos++], 16)
            if (d < 0) violation(RejectReason.MALFORMED, "json: escape non valido")
            v = (v shl 4) or d
          }
          v.toChar()
        }
        else -> violation(RejectReason.MALFORMED, "json: escape non valido")
      }
    }

    private fun readInt(): JsonInt {
      val start = pos
      if (s[pos] == '-') pos++
      val digitsStart = pos
      while (pos < s.length && s[pos] in '0'..'9') pos++
      val digits = pos - digitsStart
      if (digits == 0) violation(RejectReason.MALFORMED, "json: numero non valido")
      if (digits > 1 && s[digitsStart] == '0') violation(RejectReason.MALFORMED, "json: zeri iniziali")
      if (pos < s.length && (s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) {
        violation(RejectReason.MALFORMED, "json: solo numeri interi")
      }
      val text = s.substring(start, pos)
      if (text == "-0") violation(RejectReason.MALFORMED, "json: -0 non ammesso")
      val value = text.toLongOrNull() ?: violation(RejectReason.MALFORMED, "json: intero fuori intervallo")
      return JsonInt(value)
    }

    private fun peek(): Char = if (pos < s.length) s[pos] else violation(RejectReason.MALFORMED, "json: fine inattesa")

    private fun next(): Char = peek().also { pos++ }

    private fun expect(c: Char) {
      if (next() != c) violation(RejectReason.MALFORMED, "json: carattere inatteso")
    }
  }
}
