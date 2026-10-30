package dev.pampa.mycloudgallery.feature.map.assets

import java.io.File
import java.security.MessageDigest
import kotlin.math.pow

/**
 * Gli aiuti delle prove sui file della mappa (Fase 9, P2): dove stanno, come si leggono (JSON piccolo scritto qui, senza librerie) e il calcolo
 * del contrasto fra due colori (WCAG 2.x), fatto sui colori dello stile e non su numeri scritti a mano.
 */
internal object MapAssets {
  /** La cartella degli asset della mappa, relativa alla cartella del modulo (la cartella di lavoro dei test). */
  val dir: File = File("src/main/assets/map")

  /** Le impronte scritte dagli strumenti (le impronte.json di tools/maps/base e di tools/maps/style), nella cartella accanto ai moduli. */
  val baseRegistry: File = File("../tools/maps/base/impronte.json")
  val styleRegistry: File = File("../tools/maps/style/impronte.json")

  val styles: List<String> = listOf("style-chiaro.json", "style-scuro.json", "style-chiaro-offline.json", "style-scuro-offline.json")
  val baseFiles: List<String> = listOf(
    "land.geojson", "coast.geojson", "lakes.geojson", "borders.geojson", "regions.geojson",
    "country-labels.geojson", "region-labels.geojson", "cities.geojson",
  )

  fun file(name: String): File = File(dir, name).also { require(it.isFile) { "Manca il file dell'app: ${it.path}" } }

  fun json(name: String): Any? = MiniJson.parse(file(name).readText(Charsets.UTF_8))

  fun jsonOf(file: File): Any? {
    require(file.isFile) { "Manca il file: ${file.path}" }
    return MiniJson.parse(file.readText(Charsets.UTF_8))
  }

  @Suppress("UNCHECKED_CAST")
  fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

  @Suppress("UNCHECKED_CAST")
  fun list(value: Any?): List<Any?> = value as List<Any?>

  fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  /** Tutte le stringhe dentro un valore JSON, chiavi comprese. */
  fun strings(value: Any?): Sequence<String> = sequence {
    when (value) {
      is String -> yield(value)
      is List<*> -> value.forEach { yieldAll(strings(it)) }
      is Map<*, *> -> value.forEach { (k, v) -> yield(k as String); yieldAll(strings(v)) }
    }
  }

  /** I colori "#rrggbb" dentro un valore di proprieta' (anche dentro un'espressione). */
  fun hexColors(value: Any?): List<String> = strings(value).filter { it.length == 7 && it.startsWith("#") }.toList()

  private fun channel(c: Int): Double {
    val v = c / 255.0
    return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
  }

  private fun luminance(hex: String): Double {
    val v = hex.substring(1).toInt(16)
    return 0.2126 * channel(v shr 16 and 255) + 0.7152 * channel(v shr 8 and 255) + 0.0722 * channel(v and 255)
  }

  /** Il rapporto di contrasto WCAG fra due colori "#rrggbb". */
  fun contrast(a: String, b: String): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
  }

  /** La tonalita' (0..360) di un colore "#rrggbb". */
  fun hue(hex: String): Double {
    val v = hex.substring(1).toInt(16)
    val r = (v shr 16 and 255) / 255.0
    val g = (v shr 8 and 255) / 255.0
    val b = (v and 255) / 255.0
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    if (delta == 0.0) return 0.0
    val h = when (max) {
      r -> ((g - b) / delta) % 6
      g -> (b - r) / delta + 2
      else -> (r - g) / delta + 4
    }
    return (h * 60 + 360) % 360
  }
}

/** Un lettore JSON minimo: oggetti (nell'ordine di scrittura), liste, stringhe, numeri (Double), booleani e null. Fallisce su ogni errore. */
internal object MiniJson {
  fun parse(text: String): Any? {
    val p = Parser(text)
    val v = p.value()
    p.skip()
    require(p.i == text.length) { "Testo dopo il JSON" }
    return v
  }

  private class Parser(val s: String) {
    var i = 0

    fun skip() {
      while (i < s.length && s[i].isWhitespace()) i++
    }

    fun value(): Any? {
      skip()
      return when (val c = s[i]) {
        '{' -> obj()
        '[' -> arr()
        '"' -> str()
        't' -> lit("true", true)
        'f' -> lit("false", false)
        'n' -> lit("null", null)
        else -> if (c == '-' || c.isDigit()) num() else error("Carattere inatteso $c a $i")
      }
    }

    private fun lit(word: String, v: Any?): Any? {
      require(s.startsWith(word, i)) { "Valore inatteso a $i" }
      i += word.length
      return v
    }

    private fun num(): Double {
      val start = i
      while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
      return s.substring(start, i).toDouble()
    }

    private fun str(): String {
      val sb = StringBuilder()
      i++
      while (s[i] != '"') {
        if (s[i] == '\\') {
          i++
          when (val e = s[i]) {
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
            else -> sb.append(e)
          }
        } else {
          sb.append(s[i])
        }
        i++
      }
      i++
      return sb.toString()
    }

    private fun arr(): List<Any?> {
      val out = ArrayList<Any?>()
      i++
      skip()
      if (s[i] == ']') { i++; return out }
      while (true) {
        out += value()
        skip()
        if (s[i] == ',') i++ else { require(s[i] == ']') { "Atteso ] a $i" }; i++; return out }
      }
    }

    private fun obj(): Map<String, Any?> {
      val out = LinkedHashMap<String, Any?>()
      i++
      skip()
      if (s[i] == '}') { i++; return out }
      while (true) {
        skip()
        val key = str()
        skip()
        require(s[i] == ':') { "Atteso : a $i" }
        i++
        out[key] = value()
        skip()
        if (s[i] == ',') i++ else { require(s[i] == '}') { "Atteso } a $i" }; i++; return out }
      }
    }
  }
}
