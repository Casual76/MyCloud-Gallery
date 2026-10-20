package dev.pampa.mycloudgallery.core.metadata.write

// Correzioni 5B (R12, R13, docs/review/5b-fase5b.md): togliere il luogo dall'XMP di una foto corretta ("togli il luogo"), **sul posto**: ogni
// proprieta' del luogo diventa spazi della stessa lunghezza (XML valido, nessun indirizzo che cambia, nessun segmento da riscrivere). Prima si
// toglievano solo i nomi `GPS…` con un'espressione regolare che, su un XMP costruito apposta (`<GPSA<GPSA…` senza `>`), lavorava in tempo
// quadratico e teneva un thread per ore (R13). Adesso:
//  - un nome e' del luogo se il suo nome locale (dopo il prefisso, qualunque sia lo spazio dei nomi) contiene `gps`, `latitude`, `longitude`,
//    `longtitude`, `altitude` o `location`, o e' uno dei nomi di luogo testuali di Photoshop e IPTC (`City`, `State`, `Country`, `Sublocation`...):
//    "togli il luogo" toglie anche il nome del posto, non solo le coordinate;
//  - si toglie l'attributo intero (`nome="valore"`) o l'elemento intero, con tutto cio' che contiene (`<Iptc4xmpExt:LocationShown>…</…>`);
//  - la scansione e' **lineare**: per ogni `<` si legge il nome in avanti, per ogni `=` all'indietro (entro 256 caratteri); la fine di un tag, di un
//    valore o di un elemento si cerca una volta sola, e se non c'e' ci si ferma (`unsupported`) invece di riprovare dal byte dopo;
//  - **tetti**: un pacchetto oltre 1 MiB non si esamina, e oltre 2 secondi ci si ferma: in entrambi i casi `unsupported`, e la correzione non si
//    scrive nel file (resta nell'app, come per i formati che non si scrivono).
// Un pacchetto in UTF-16 non si sa riscrivere cosi': se ha nomi del luogo e' `unsupported`.

/** L'esito della pulizia di un pacchetto XMP: i byte (stessa lunghezza), quante proprieta' tolte, e se non si e' potuto. */
internal class XmpScrubbed(val bytes: ByteArray, val removed: Int, val unsupported: Boolean)

internal object XmpLocationScrub {

  /** Oltre questo un pacchetto non si esamina: nel dubbio la correzione non si scrive nel file. */
  const val MAX_PACKET: Int = 1024 * 1024

  /** Il tempo massimo per un pacchetto. */
  const val MAX_MILLIS: Long = 2_000L

  private const val MAX_NAME = 256
  private const val SPACE: Byte = 0x20

  /** I nomi di luogo testuali (Photoshop, IPTC Core ed Extension), senza maiuscole. */
  private val PLACE_NAMES = setOf("city", "state", "country", "countrycode", "countryname", "sublocation", "provincestate", "worldregion")

  /** I pezzi dei nomi del luogo, senza maiuscole. */
  private val PLACE_PARTS = listOf("gps", "latitude", "longitude", "longtitude", "altitude", "location")

  fun scrub(packet: ByteArray, maxMillis: Long = MAX_MILLIS): XmpScrubbed {
    if (packet.size > MAX_PACKET) return XmpScrubbed(packet, 0, unsupported = true)
    if (utf16(packet)) return XmpScrubbed(packet, 0, unsupported = mentionsPlace(String(packet, if (littleEndian(packet)) Charsets.UTF_16LE else Charsets.UTF_16BE)))
    val deadline = System.nanoTime() + maxMillis * 1_000_000L
    val out = packet.copyOf()
    var removed = 0
    var i = 0
    var steps = 0
    while (i < out.size) {
      if (++steps and 0xFFFF == 0 && System.nanoTime() > deadline) return XmpScrubbed(packet, 0, unsupported = true)
      val c = out[i].toInt() and 0xFF
      if (c == '<'.code && i + 1 < out.size && nameStart(out[i + 1])) {
        val nameEnd = nameEnd(out, i + 1)
        if (isPlace(out, i + 1, nameEnd)) {
          val end = elementEnd(out, i, i + 1, nameEnd) ?: return XmpScrubbed(packet, 0, unsupported = true)
          blank(out, i, end)
          removed++
          i = end
          continue
        }
      } else if (c == '='.code) {
        val range = attribute(out, i)
        if (range != null) {
          blank(out, range.first, range.last + 1)
          removed++
          i = range.last + 1
          continue
        }
      }
      i++
    }
    return XmpScrubbed(out, removed, unsupported = false)
  }

  /** Il pacchetto nomina ancora un luogo (per un UTF-16, che non si riscrive): nomi di elementi o di attributi. */
  private fun mentionsPlace(text: String): Boolean {
    val bytes = text.toByteArray(Charsets.ISO_8859_1)
    for (i in bytes.indices) {
      if (bytes[i] == '<'.code.toByte() && i + 1 < bytes.size && nameStart(bytes[i + 1]) && isPlace(bytes, i + 1, nameEnd(bytes, i + 1))) return true
      if (bytes[i] == '='.code.toByte() && attribute(bytes, i) != null) return true
    }
    return false
  }

  /** `nome="valore"` intorno all'`=` in [eq], se il nome e' del luogo e l'attributo sta dentro un tag (spazio prima del nome). */
  private fun attribute(b: ByteArray, eq: Int): IntRange? {
    var e = eq
    var spaces = 0
    while (e > 0 && isSpace(b[e - 1]) && spaces++ < 64) e--
    var s = e
    while (s > 0 && e - s < MAX_NAME && nameChar(b[s - 1])) s--
    if (s == e || s == 0 || !isSpace(b[s - 1]) || !nameStart(b[s])) return null
    if (!isPlace(b, s, e)) return null
    var v = eq + 1
    while (v < b.size && isSpace(b[v])) v++
    if (v >= b.size) return null
    val quote = b[v]
    if (quote != '"'.code.toByte() && quote != '\''.code.toByte()) return null
    var close = v + 1
    while (close < b.size && b[close] != quote) close++
    if (close >= b.size) return null
    return s..close
  }

  /**
   * La fine (esclusa) dell'elemento che comincia a [open] con il nome `[nameStart, nameEnd)`: un tag che si chiude da solo, o il tag di chiusura
   * con lo stesso nome (contando quelli annidati con lo stesso nome). Null se non c'e': il pacchetto non torna.
   */
  private fun elementEnd(b: ByteArray, open: Int, nameStart: Int, nameEnd: Int): Int? {
    val tagEnd = tagEnd(b, nameEnd) ?: return null
    if (b[tagEnd - 2] == '/'.code.toByte()) return tagEnd
    var depth = 1
    var i = tagEnd
    while (i < b.size) {
      if (b[i] == '<'.code.toByte()) {
        val closing = i + 1 < b.size && b[i + 1] == '/'.code.toByte()
        val start = if (closing) i + 2 else i + 1
        if (sameName(b, start, nameStart, nameEnd)) {
          val after = start + (nameEnd - nameStart)
          val end = tagEnd(b, after) ?: return null
          if (closing) {
            depth--
            if (depth == 0) return end
          } else if (b[end - 2] != '/'.code.toByte()) {
            depth++
          }
          i = end
          continue
        }
      }
      i++
    }
    return null
  }

  /** Dopo il nome di un tag: la fine (esclusa) del tag, saltando i valori fra virgolette. */
  private fun tagEnd(b: ByteArray, from: Int): Int? {
    var i = from
    var quote: Byte = 0
    while (i < b.size) {
      val c = b[i]
      if (quote != 0.toByte()) {
        if (c == quote) quote = 0
      } else if (c == '"'.code.toByte() || c == '\''.code.toByte()) {
        quote = c
      } else if (c == '>'.code.toByte()) {
        return i + 1
      } else if (c == '<'.code.toByte()) {
        return null
      }
      i++
    }
    return null
  }

  /** Il nome in [at] e' lo stesso di `[nameStart, nameEnd)` ed e' finito (seguito da spazio, `>` o `/`). */
  private fun sameName(b: ByteArray, at: Int, nameStart: Int, nameEnd: Int): Boolean {
    val length = nameEnd - nameStart
    if (at + length >= b.size) return false
    for (k in 0 until length) if (b[at + k] != b[nameStart + k]) return false
    val next = b[at + length]
    return !nameChar(next)
  }

  private fun isPlace(b: ByteArray, start: Int, end: Int): Boolean {
    var local = start
    for (k in start until end) if (b[k] == ':'.code.toByte()) local = k + 1
    if (local >= end) return false
    val name = String(b, local, end - local, Charsets.ISO_8859_1).lowercase()
    return name in PLACE_NAMES || PLACE_PARTS.any { it in name }
  }

  private fun nameEnd(b: ByteArray, from: Int): Int {
    var e = from
    while (e < b.size && e - from < MAX_NAME && nameChar(b[e])) e++
    return e
  }

  private fun blank(b: ByteArray, from: Int, to: Int) {
    for (k in from until to) if (b[k] != '\n'.code.toByte() && b[k] != '\r'.code.toByte()) b[k] = SPACE
  }

  private fun nameStart(c: Byte): Boolean = c in 'a'.code.toByte()..'z'.code.toByte() || c in 'A'.code.toByte()..'Z'.code.toByte() || c == '_'.code.toByte()

  private fun nameChar(c: Byte): Boolean = nameStart(c) || c in '0'.code.toByte()..'9'.code.toByte() || c == ':'.code.toByte() || c == '.'.code.toByte() || c == '-'.code.toByte()

  private fun isSpace(c: Byte): Boolean = c == SPACE || c == '\t'.code.toByte() || c == '\n'.code.toByte() || c == '\r'.code.toByte()

  private fun utf16(packet: ByteArray): Boolean {
    if (packet.size >= 2) {
      val a = packet[0].toInt() and 0xFF
      val b = packet[1].toInt() and 0xFF
      if ((a == 0xFE && b == 0xFF) || (a == 0xFF && b == 0xFE)) return true
    }
    val probe = minOf(packet.size, 64)
    if (probe < 8) return false
    var zeros = 0
    for (i in 0 until probe) if (packet[i] == 0.toByte()) zeros++
    return zeros * 3 >= probe
  }

  private fun littleEndian(packet: ByteArray): Boolean =
    (packet.size >= 2 && packet[0] == 0xFF.toByte() && packet[1] == 0xFE.toByte()) || (packet.size >= 2 && packet[1] == 0.toByte() && packet[0] != 0.toByte())
}
