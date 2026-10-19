package dev.pampa.mycloudgallery.feature.integrations.receive

import dev.pampa.mycloudgallery.core.mediastore.sync.MediaSniffer
import java.util.Locale

// Fase 5B (P11, docs/fase5-progetto.md D24, C18, E-X2): il TIPO di un file in arrivo, dai suoi primi byte e mai dal nome o dal tipo dichiarato
// dall'altra app. Un `.jpg` che dentro e' testo, HTML, un PDF o uno ZIP non e' un media (`MediaSniffer`, lo stesso controllo della scansione del
// telefono); un file che ha la firma di un media ha il tipo della sua firma, non quello che dice l'altro. Il tipo dichiarato serve solo per i
// formati senza una firma propria (un RAW basato su TIFF), e solo se e' un tipo di immagine o video.

object IncomingMime {

  /** Quanti byte iniziali servono: `MediaSniffer` guarda al piu' 512 byte per riconoscere un testo; `ftyp` ne chiede meno di 200. */
  const val HEAD_BYTES: Int = 1024

  private val DECLARED = Regex("^(image|video)/[a-z0-9][a-z0-9.+-]{0,60}$")
  private val RAW = Regex("^image/(dng|x-[a-z0-9-]{1,40})$")

  /** I marchi `ftyp` di HEIF/HEIC e di AVIF. */
  private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs")
  private val HEIF_BRANDS = setOf("mif1", "msf1", "heif")
  private val AVIF_BRANDS = setOf("avif", "avis")

  /** I marchi dei contenitori che portano solo audio: hanno `ftyp` come un video, ma non lo sono. */
  private val AUDIO_BRANDS = setOf("m4a ", "m4b ", "m4p ", "f4a ", "f4b ")

  /**
   * Il tipo di un file dalla sua testa [head], o null se non e' un'immagine o un video che l'app tratta. [declared] e' il tipo dichiarato
   * dall'altra app: non si crede, serve solo a rifiutare un tipo che ha una firma precisa e non la rispetta (via `MediaSniffer`).
   */
  fun of(head: ByteArray, declared: String?): String? {
    if (!MediaSniffer.looksLikeMedia(head, declared)) return null
    val exact = exact(head)
    if (exact == AUDIO) return null
    // Un RAW (DNG, CR2, NEF, ARW...) ha le intestazioni di un TIFF: il suo tipo e' quello dichiarato, ma solo se e' proprio un tipo RAW.
    if (exact == "image/tiff") return declared?.lowercase(Locale.ROOT)?.takeIf { RAW.matches(it) } ?: exact
    if (exact != null) return exact
    return declared?.lowercase(Locale.ROOT)?.takeIf { DECLARED.matches(it) }
  }

  private const val AUDIO = "audio"

  private fun exact(h: ByteArray): String? = when {
    h.starts(0xFF, 0xD8, 0xFF) -> "image/jpeg"
    h.starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
    h.ascii(0, "GIF87a") || h.ascii(0, "GIF89a") -> "image/gif"
    h.ascii(0, "RIFF") && h.ascii(8, "WEBP") -> "image/webp"
    h.ascii(0, "RIFF") && h.ascii(8, "AVI ") -> "video/x-msvideo"
    h.ascii(0, "BM") && h.size >= 26 -> "image/bmp"
    h.starts(0x1A, 0x45, 0xDF, 0xA3) -> "video/x-matroska"
    h.ascii(4, "ftyp") -> isoType(h)
    h.starts(0x49, 0x49, 0x2A, 0x00) || h.starts(0x4D, 0x4D, 0x00, 0x2A) -> "image/tiff"
    // Un QuickTime vecchio senza `ftyp` comincia con un altro riquadro.
    listOf("moov", "mdat", "wide", "free", "skip", "pnot").any { h.ascii(4, it) } -> "video/quicktime"
    else -> null
  }

  private fun isoType(h: ByteArray): String? {
    val brands = brandsOf(h)
    return when {
      // Un file audio in un contenitore MP4 dichiara l'audio come marca principale e `isom`/`mp42` fra le compatibili: conta la principale.
      brands.firstOrNull() in AUDIO_BRANDS -> AUDIO
      brands.any { it in AVIF_BRANDS } -> "image/avif"
      brands.any { it in HEIC_BRANDS } -> "image/heic"
      brands.any { it in HEIF_BRANDS } -> "image/heif"
      brands.firstOrNull() == "qt  " -> "video/quicktime"
      brands.firstOrNull()?.startsWith("3gp") == true -> "video/3gpp"
      brands.firstOrNull()?.startsWith("3g2") == true -> "video/3gpp2"
      else -> "video/mp4"
    }
  }

  /** Il marchio principale e i compatibili dichiarati da `ftyp` (al piu' 32). */
  private fun brandsOf(h: ByteArray): List<String> {
    if (h.size < 12) return emptyList()
    val size = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
    val end = if (size in 16..h.size.toLong()) size.toInt() else h.size
    val out = ArrayList<String>()
    out += String(h, 8, 4, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
    var i = 16
    while (i + 4 <= end && out.size <= 32) {
      out += String(h, i, 4, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
      i += 4
    }
    return out
  }

  private fun ByteArray.starts(vararg bytes: Int): Boolean {
    if (size < bytes.size) return false
    for (i in bytes.indices) if ((this[i].toInt() and 0xFF) != bytes[i]) return false
    return true
  }

  private fun ByteArray.ascii(at: Int, text: String): Boolean {
    if (size < at + text.length) return false
    for (i in text.indices) if ((this[at + i].toInt() and 0xFF) != text[i].code) return false
    return true
  }
}
