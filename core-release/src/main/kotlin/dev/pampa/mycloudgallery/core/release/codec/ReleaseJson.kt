package dev.pampa.mycloudgallery.core.release.codec

import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.TreeMap

// IL JSON CANONICO DEL MANIFEST (docs/fase10-progetto.md D29, §1.2 "Busta"; docs/formato-nas.md §3.2 e §15). Lo stesso sottoinsieme del formato
// del NAS, scritto qui perche' core-release vede solo le fondamenta (non core-trust): oggetti, array, stringhe UTF-8 in NFC, interi a 64 bit,
// booleani; niente `null`, niente virgola ne' esponente, niente `-0` ne' zeri iniziali; chiavi ASCII in ordine crescente e mai ripetute;
// nessuno spazio fuori dalle stringhe; escape solo per `"`, la barra rovesciata e i caratteri di controllo. Chi legge pretende che la
// riscrittura dia **gli stessi byte**: due versioni dell'app non possono leggere lo stesso manifest firmato in due modi.
// La lettura "leggibile" ([ReleaseJson.parseLenient]) ammette gli spazi e serve solo a `ManifestTool` per `release/manifest.json`.

internal sealed interface JValue

internal data class JStr(val value: String) : JValue

internal data class JInt(val value: Long) : JValue

internal data class JBool(val value: Boolean) : JValue

internal data class JArr(val items: List<JValue>) : JValue

internal class JObj(members: Map<String, JValue>) : JValue {
  val members: Map<String, JValue> = TreeMap(members)

  override fun equals(other: Any?): Boolean = other is JObj && other.members == members

  override fun hashCode(): Int = members.hashCode()

  override fun toString(): String = "JObj(${members.keys})"
}

/** Un rifiuto del codec con il suo motivo (mai un testo del manifest nel messaggio). */
internal class CodecException(val reason: RejectReason, message: String) : Exception(message)

internal fun reject(reason: RejectReason, why: String): Nothing = throw CodecException(reason, why)

internal object ReleaseJson {
  /** Oggetto del manifest → `models` → un modello → `urls` → un indirizzo (o un `critical` e i suoi nomi): cinque livelli bastano. */
  const val MAX_DEPTH: Int = 5
  const val MAX_STRING_CHARS: Int = 2 * 1024
  const val MAX_KEY_CHARS: Int = 32
  const val MAX_MEMBERS: Int = 32
  const val MAX_ITEMS: Int = 32

  fun isValidKey(key: String): Boolean =
    key.length in 1..MAX_KEY_CHARS && key[0].let { it in 'a'..'z' || it in 'A'..'Z' } &&
      key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

  fun encode(value: JValue): ByteArray {
    val sb = StringBuilder()
    write(sb, value)
    val encoder = StandardCharsets.UTF_8.newEncoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val buffer = try {
      encoder.encode(CharBuffer.wrap(sb))
    } catch (e: CharacterCodingException) {
      throw IllegalArgumentException("Testo non rappresentabile in UTF-8.", e)
    }
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
  }

  /** Legge in modo stretto e pretende la forma canonica (stessi byte alla riscrittura). */
  fun parseCanonical(bytes: ByteArray): JValue {
    val value = Parser(decode(bytes), lenient = false).readDocument()
    if (!encode(value).contentEquals(bytes)) reject(RejectReason.MALFORMED, "json non canonico")
    return value
  }

  /** Il JSON scritto a mano (`release/manifest.json`): spazi e a capo ammessi fra i simboli, il resto come la forma stretta. */
  fun parseLenient(text: String): JValue = Parser(text.removePrefix("\uFEFF"), lenient = true).readDocument()

  private fun decode(bytes: ByteArray): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
      decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
      reject(RejectReason.MALFORMED, "UTF-8 non valido")
    }
  }

  private fun write(sb: StringBuilder, value: JValue) {
    when (value) {
      is JStr -> writeString(sb, value.value)
      is JInt -> sb.append(value.value)
      is JBool -> sb.append(if (value.value) "true" else "false")
      is JArr -> {
        sb.append('[')
        value.items.forEachIndexed { i, item ->
          if (i > 0) sb.append(',')
          write(sb, item)
        }
        sb.append(']')
      }
      is JObj -> {
        sb.append('{')
        var first = true
        for ((key, member) in value.members) {
          require(isValidKey(key)) { "Chiave JSON non ammessa." }
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

  /** Discesa ricorsiva con profondita' e tetti: lo stack non e' mai un problema, la memoria nemmeno (64 KiB in tutto). */
  private class Parser(private val s: String, private val lenient: Boolean) {
    private var pos = 0

    fun readDocument(): JValue {
      skip()
      val value = readValue(depth = 1)
      skip()
      if (pos != s.length) reject(RejectReason.MALFORMED, "dati dopo la fine")
      return value
    }

    private fun skip() {
      if (!lenient) return
      while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
    }

    private fun readValue(depth: Int): JValue {
      if (depth > MAX_DEPTH) reject(RejectReason.TOO_LARGE, "troppo profondo")
      if (pos >= s.length) reject(RejectReason.MALFORMED, "fine inattesa")
      return when (val c = s[pos]) {
        '{' -> readObject(depth)
        '[' -> readArray(depth)
        '"' -> JStr(readString(MAX_STRING_CHARS))
        't' -> literal("true", JBool(true))
        'f' -> literal("false", JBool(false))
        else -> if (c == '-' || c in '0'..'9') readInt() else reject(RejectReason.MALFORMED, "valore non ammesso")
      }
    }

    private fun literal(word: String, value: JValue): JValue {
      if (!s.startsWith(word, pos)) reject(RejectReason.MALFORMED, "letterale non valido")
      pos += word.length
      return value
    }

    private fun readObject(depth: Int): JObj {
      pos++
      val members = LinkedHashMap<String, JValue>()
      skip()
      if (peek() == '}') {
        pos++
        return JObj(members)
      }
      while (true) {
        skip()
        if (peek() != '"') reject(RejectReason.MALFORMED, "chiave attesa")
        val key = readString(MAX_KEY_CHARS)
        if (!isValidKey(key)) reject(RejectReason.MALFORMED, "chiave non ammessa")
        if (members.containsKey(key)) reject(RejectReason.MALFORMED, "chiave ripetuta")
        skip()
        if (next() != ':') reject(RejectReason.MALFORMED, "due punti attesi")
        skip()
        members[key] = readValue(depth + 1)
        if (members.size > MAX_MEMBERS) reject(RejectReason.TOO_LARGE, "troppi campi")
        skip()
        when (next()) {
          ',' -> continue
          '}' -> return JObj(members)
          else -> reject(RejectReason.MALFORMED, "separatore atteso")
        }
      }
    }

    private fun readArray(depth: Int): JArr {
      pos++
      val items = ArrayList<JValue>()
      skip()
      if (peek() == ']') {
        pos++
        return JArr(items)
      }
      while (true) {
        skip()
        items.add(readValue(depth + 1))
        if (items.size > MAX_ITEMS) reject(RejectReason.TOO_LARGE, "array troppo lungo")
        skip()
        when (next()) {
          ',' -> continue
          ']' -> return JArr(items)
          else -> reject(RejectReason.MALFORMED, "separatore atteso")
        }
      }
    }

    private fun readString(maxChars: Int): String {
      pos++
      val sb = StringBuilder()
      while (true) {
        if (pos >= s.length) reject(RejectReason.MALFORMED, "stringa non chiusa")
        val c = s[pos++]
        when {
          c == '"' -> break
          c == '\\' -> sb.append(readEscape())
          c.code < 0x20 -> reject(RejectReason.MALFORMED, "controllo non escapato")
          else -> sb.append(c)
        }
        if (sb.length > maxChars) reject(RejectReason.TOO_LARGE, "stringa troppo lunga")
      }
      for (i in sb.indices) {
        val ch = sb[i]
        val paired = when {
          Character.isHighSurrogate(ch) -> i + 1 < sb.length && Character.isLowSurrogate(sb[i + 1])
          Character.isLowSurrogate(ch) -> i > 0 && Character.isHighSurrogate(sb[i - 1])
          else -> true
        }
        if (!paired) reject(RejectReason.MALFORMED, "surrogato spaiato")
      }
      val text = sb.toString()
      if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) reject(RejectReason.MALFORMED, "testo non in NFC")
      return text
    }

    private fun readEscape(): Char {
      if (pos >= s.length) reject(RejectReason.MALFORMED, "escape troncato")
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
          if (pos + 4 > s.length) reject(RejectReason.MALFORMED, "escape troncato")
          var v = 0
          repeat(4) {
            val d = Character.digit(s[pos++], 16)
            if (d < 0) reject(RejectReason.MALFORMED, "escape non valido")
            v = (v shl 4) or d
          }
          v.toChar()
        }
        else -> reject(RejectReason.MALFORMED, "escape non valido")
      }
    }

    private fun readInt(): JInt {
      val start = pos
      if (s[pos] == '-') pos++
      val digitsStart = pos
      while (pos < s.length && s[pos] in '0'..'9') pos++
      val digits = pos - digitsStart
      if (digits == 0) reject(RejectReason.MALFORMED, "numero non valido")
      if (digits > 1 && s[digitsStart] == '0') reject(RejectReason.MALFORMED, "zeri iniziali")
      if (pos < s.length && (s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) reject(RejectReason.MALFORMED, "solo interi")
      val text = s.substring(start, pos)
      if (text == "-0") reject(RejectReason.MALFORMED, "-0 non ammesso")
      return JInt(text.toLongOrNull() ?: reject(RejectReason.MALFORMED, "intero fuori intervallo"))
    }

    private fun peek(): Char = if (pos < s.length) s[pos] else reject(RejectReason.MALFORMED, "fine inattesa")

    private fun next(): Char = peek().also { pos++ }
  }
}
