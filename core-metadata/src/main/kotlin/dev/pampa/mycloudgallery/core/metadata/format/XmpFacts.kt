package dev.pampa.mycloudgallery.core.metadata.format

/** Una voce di `Container:Directory` (motion photo e Ultra HDR, formato di Google). */
internal class XmpItem(val mime: String?, val semantic: String?, val length: Long?, val padding: Long?)

/**
 * Cio' che serve di un pacchetto XMP: motion photo (Google v1 e MicroVideo), panorama e sfera (GPano), Ultra HDR
 * (`hdrgm`). Non e' un parser XML: l'XMP e' un testo con una sintassi molto regolare e i prefissi degli spazi dei nomi
 * cambiano da fotocamera a fotocamera (`Camera:`, `GCamera:`, `GContainer:`), quindi si cercano i **nomi locali**, sia
 * come attributo (`Camera:MotionPhoto="1"`) sia come elemento (`<Camera:MotionPhoto>1</...>`). Un testo ostile non
 * puo' far altro che produrre valori sbagliati: ogni numero e' limitato e le espressioni non fanno backtracking.
 */
internal class XmpFacts(
  /** `MotionPhoto` = 1: la foto dichiara di avere un video (non e' definitivo: va trovato e verificato). */
  val motionPhotoFlag: Boolean,
  /** `MicroVideo` = 1 (formato precedente di Google). */
  val microVideoFlag: Boolean,
  /** `MicroVideoOffset`: i byte dalla **fine** del file all'inizio del video. */
  val microVideoOffset: Long?,
  /** Il fotogramma della foto ferma dentro il video, in microsecondi (`...PresentationTimestampUs`), se dichiarato e valido. */
  val presentationTimestampUs: Long?,
  val items: List<XmpItem>,
  /** C'e' una voce `GainMap` nella directory, oppure `hdrgm:Version`. */
  val gainMap: Boolean,
  val hasGPano: Boolean,
  val projectionType: String?,
  val fullPano: Pair<Long, Long>?,
  val croppedArea: Pair<Long, Long>?,
) {

  /** La voce della directory che porta il video di una motion photo, se c'e'. */
  val motionItem: XmpItem? get() = items.firstOrNull { it.semantic.equals("MotionPhoto", ignoreCase = true) }

  /** Una qualunque dichiarazione di motion photo. */
  val declaresMotion: Boolean get() = motionPhotoFlag || microVideoFlag || motionItem != null

  /** Una sfera completa: proiezione equirettangolare che copre tutto, oppure, senza i dati di ritaglio, un rapporto di 2:1. */
  fun isFullSphere(width: Int?, height: Int?): Boolean {
    if (!projectionType.equals("equirectangular", ignoreCase = true)) return false
    val full = fullPano
    val crop = croppedArea
    if (full != null && crop != null) return crop.first >= full.first && crop.second >= full.second
    if (width == null || height == null || width <= 0 || height <= 0) return false
    val ratio = maxOf(width, height).toDouble() / minOf(width, height)
    return ratio in 1.9..2.1
  }

  companion object {

    val EMPTY = XmpFacts(false, false, null, null, emptyList(), false, false, null, null, null)

    fun parse(xmp: String?): XmpFacts {
      if (xmp.isNullOrBlank()) return EMPTY
      val text = if (xmp.length > MAX_CHARS) xmp.substring(0, MAX_CHARS) else xmp
      val items = parseItems(text)
      val hdrgm = Regex("""hdrgm:Version\b""").containsMatchIn(text)
      val timestamp = (longValue(text, "MotionPhotoPresentationTimestampUs") ?: longValue(text, "MicroVideoPresentationTimestampUs"))
        ?.takeIf { it >= 0 }
      val hasGPano = text.contains("GPano:") || text.contains("ns.google.com/photos/1.0/panorama/")
      return XmpFacts(
        motionPhotoFlag = longValue(text, "MotionPhoto") == 1L,
        microVideoFlag = longValue(text, "MicroVideo") == 1L,
        microVideoOffset = longValue(text, "MicroVideoOffset")?.takeIf { it > 0 },
        presentationTimestampUs = timestamp,
        items = items,
        gainMap = hdrgm || items.any { it.semantic.equals("GainMap", ignoreCase = true) },
        hasGPano = hasGPano,
        projectionType = stringValue(text, "ProjectionType"),
        fullPano = pair(text, "FullPanoWidthPixels", "FullPanoHeightPixels"),
        croppedArea = pair(text, "CroppedAreaImageWidthPixels", "CroppedAreaImageHeightPixels"),
      )
    }

    private const val MAX_CHARS = 1_000_000
    private const val MAX_ITEMS = 16

    private fun pair(text: String, a: String, b: String): Pair<Long, Long>? {
      val x = longValue(text, a) ?: return null
      val y = longValue(text, b) ?: return null
      return if (x > 0 && y > 0) x to y else null
    }

    /** Il valore di una proprieta' semplice, come attributo (`ns:Nome="v"`) o come elemento (`<ns:Nome>v</ns:Nome>`). */
    private fun stringValue(text: String, local: String): String? {
      val attr = Regex("""(?<![\w.-])(?:[\w.-]+:)?$local\s*=\s*["']([^"']{0,200})["']""").find(text)
      if (attr != null) return attr.groupValues[1].trim()
      val element = Regex("""<(?:[\w.-]+:)?$local(?:\s[^>]*)?>([^<]{0,200})</""").find(text)
      return element?.groupValues?.get(1)?.trim()
    }

    private fun longValue(text: String, local: String): Long? {
      val s = stringValue(text, local) ?: return null
      return s.toLongOrNull() ?: when (s.lowercase()) {
        "true" -> 1L
        "false" -> 0L
        else -> null
      }
    }

    private fun parseItems(text: String): List<XmpItem> {
      val items = ArrayList<XmpItem>()
      // Forma ad attributi: <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="123"/>
      for (m in Regex("""<(?:[\w.-]+:)?Item\b([^>]{0,2000})>""").findAll(text)) {
        val attrs = m.groupValues[1]
        val semantic = stringValue(attrs, "Semantic")
        if (semantic == null && stringValue(attrs, "Mime") == null) continue
        items += XmpItem(stringValue(attrs, "Mime"), semantic, longValue(attrs, "Length"), longValue(attrs, "Padding"))
        if (items.size >= MAX_ITEMS) return items
      }
      if (items.isNotEmpty()) return items
      // Forma a elementi: <rdf:li><Item:Mime>..</Item:Mime><Item:Semantic>..</Item:Semantic>...</rdf:li>
      for (m in Regex("""<rdf:li\b[^>]{0,500}>(.{0,4000}?)</rdf:li>""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
        val inner = m.groupValues[1]
        val semantic = stringValue(inner, "Semantic")
        val mime = stringValue(inner, "Mime")
        if (semantic == null && mime == null) continue
        items += XmpItem(mime, semantic, longValue(inner, "Length"), longValue(inner, "Padding"))
        if (items.size >= MAX_ITEMS) return items
      }
      return items
    }
  }
}
