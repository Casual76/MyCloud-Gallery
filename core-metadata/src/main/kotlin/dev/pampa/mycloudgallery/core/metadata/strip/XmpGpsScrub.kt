package dev.pampa.mycloudgallery.core.metadata.strip

// Fase 5B, P11 (docs/fase5-progetto.md D26, E-X5): le coordinate possono stare anche nell'XMP, non solo nell'EXIF (`exif:GPSLatitude` e
// sorelle). `ExifInterface` non le tocca e l'estrattore dell'app non le legge: se non le togliessimo noi, un file "senza posizione" ne
// porterebbe ancora una, che `exiftool` mostra. Qui si cancellano **sul posto**: ogni proprieta' GPS diventa spazi della stessa lunghezza
// (XML valido, nessun offset che cambia, nessun riquadro da riscrivere).

/** L'esito della pulizia di un pacchetto XMP. */
internal class XmpScrub(val bytes: ByteArray, val removed: Int, val unsupported: Boolean)

internal object XmpGpsScrub {

  /** `exif:GPSLatitude="45,27.8520N"` come attributo: sempre preceduto da uno spazio. */
  private val ATTRIBUTE = Regex("""(?<=\s)(?:[A-Za-z_][\w.\-]*:)?GPS\w*\s*=\s*(?:"[^"]*"|'[^']*')""")

  /** `<exif:GPSLatitude>45,27.8520N</exif:GPSLatitude>` come elemento (anche vuoto o autochiuso). */
  private val ELEMENT = Regex("""<(?:([A-Za-z_][\w.\-]*):)?(GPS\w*)\b[^>]*?(?:/>|>[\s\S]*?</(?:\1:)?\2\s*>)""")

  /** Una proprieta' GPS ancora presente (nome che comincia per GPS e una maiuscola, seguito da `=` o `>`), per il ricontrollo. */
  private val PROPERTY = Regex("""[:<\s]GPS[A-Z]\w*\s*(?:=|>|/>)""")

  /**
   * Toglie dal pacchetto [packet] ogni proprieta' GPS, sul posto (stessa lunghezza). Un pacchetto in UTF-16 non si sa riscrivere in questo
   * modo: se ha proprieta' GPS risponde `unsupported` e il chiamante ferma la condivisione.
   */
  fun scrub(packet: ByteArray): XmpScrub {
    if (isUtf16(packet)) return XmpScrub(packet, 0, unsupported = hasGps(packet))
    val text = String(packet, Charsets.ISO_8859_1)
    val out = packet.copyOf()
    var removed = 0
    for (regex in listOf(ELEMENT, ATTRIBUTE)) {
      for (m in regex.findAll(text)) {
        for (i in m.range) out[i] = SPACE
        removed++
      }
    }
    return XmpScrub(out, removed, unsupported = false)
  }

  /** Il pacchetto ha ancora una proprieta' GPS? (Anche in UTF-16.) */
  fun hasGps(packet: ByteArray): Boolean {
    if (PROPERTY.containsMatchIn(String(packet, Charsets.ISO_8859_1))) return true
    if (isUtf16(packet)) {
      val charset = if (packet.size >= 2 && packet[0] == 0xFF.toByte() && packet[1] == 0xFE.toByte()) Charsets.UTF_16LE else Charsets.UTF_16BE
      return PROPERTY.containsMatchIn(String(packet, charset))
    }
    return false
  }

  /** Un BOM UTF-16, o un testo con un byte nullo ogni due. */
  private fun isUtf16(packet: ByteArray): Boolean {
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

  private const val SPACE: Byte = 0x20
}
