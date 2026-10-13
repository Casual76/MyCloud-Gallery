package dev.pampa.mycloudgallery.core.metadata.format

import dev.pampa.mycloudgallery.core.model.ExifOrientation

// ISO BMFF: HEIC/HEIF/AVIF (immagini) e MP4/MOV (video). Per le immagini si legge solo il riquadro `meta` (voci, posizioni,
// proprieta') e poi i pochi byte di Exif e XMP; per i video basta la sequenza dei riquadri di primo livello, che dice
// se il file e' intero (manca `moov` o un riquadro esce dal file = registrazione interrotta).

internal class Box(val type: String, val start: Long, val headerSize: Int, val end: Long) {
  val contentStart: Long get() = start + headerSize
}

/** I riquadri di primo livello di un file e se la sequenza finisce dentro il file. */
internal class TopLevel(val boxes: List<Box>, val truncated: Boolean, val malformed: Boolean, val complete: Boolean) {
  fun first(type: String): Box? = boxes.firstOrNull { it.type == type }
}

internal object IsoBmff {

  private const val MAX_TOP_BOXES = 256
  private const val MAX_META_BYTES = 8 * 1024 * 1024
  private const val MAX_ITEM_BYTES = 2 * 1024 * 1024

  // Tetti sui conteggi dichiarati dentro `meta` (R1 della revisione di sicurezza della Fase 3). Un HEIC vero ha decine di
  // voci (un iPhone circa 50-60: tessere della griglia, anteprima, Exif, XMP, mappe ausiliarie) e un extent a testa; anche
  // una griglia di 256 tessere sta ampiamente sotto. I tetti sono larghi di un ordine di grandezza sui casi reali e stretti
  // abbastanza da far costare a un file ostile poche decine di KB di memoria, qualunque cosa dichiari. Oltre il tetto il
  // file e' trattato come "non leggibile" (`ImageScan.malformed`), mai con un'eccezione che esca da [scanHeif].
  /** Voci (`iloc` item_count, `infe` di `iinf`, voci di `ipma`): una griglia da 256x256 sarebbe il caso limite, nessuno la produce. */
  internal const val MAX_ITEMS = 4096

  /** Extent per voce dell'`iloc`: un'immagine o un blocco Exif/XMP ne ha uno, un dato frammentato pochi. */
  internal const val MAX_EXTENTS_PER_ITEM = 32

  /** Extent in tutto l'`iloc`, a prescindere da quante voci li dichiarano. */
  internal const val MAX_EXTENTS_TOTAL = 8192

  /** Proprieta' (figli di `ipco`): un iPhone ne ha una trentina. */
  internal const val MAX_PROPERTIES = 1024

  /** Riquadri figli di un contenitore (`meta`, `iinf`, `iprp`, `ipco`): sono al piu' le voci o le proprieta' piu' qualche altro. */
  internal const val MAX_CHILD_BOXES = MAX_ITEMS + 64

  /** Un conteggio fuori scala o un riquadro incoerente: il file e' "non leggibile". Resta dentro [scanHeif]. */
  private class BoxLimitExceeded : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
  }

  suspend fun readBox(ra: RandomAccess, pos: Long, parentEnd: Long): Box? {
    val h = ra.read(pos, 16)
    if (h.size < 8) return null
    var size = h.u32be(0)
    val type = h.fourcc(4)
    var header = 8
    if (size == 1L) {
      if (h.size < 16) return null
      size = h.u64be(8)
      header = 16
    } else if (size == 0L) {
      size = parentEnd - pos
    }
    if (size < header) return null
    return Box(type, pos, header, pos + size)
  }

  /** Percorre i riquadri di primo livello. */
  suspend fun walkTopLevel(ra: RandomAccess): TopLevel {
    val boxes = ArrayList<Box>()
    var pos = 0L
    var truncated = false
    var malformed = false
    var complete = false
    while (boxes.size < MAX_TOP_BOXES) {
      if (pos == ra.size) { complete = true; break }
      if (pos > ra.size) { truncated = true; break }
      val box = readBox(ra, pos, ra.size)
      if (box == null) {
        // Meno di 8 byte dalla fine: il file e' stato tagliato dentro un'intestazione.
        if (ra.size - pos < 8) truncated = true else malformed = true
        break
      }
      boxes += box
      if (box.end > ra.size) { truncated = true; break }
      pos = box.end
    }
    return TopLevel(boxes, truncated, malformed, complete)
  }

  /** Il video e' intero? Vero se la sequenza arriva in fondo, c'e' `moov` (o `moof` in un file frammentato) e nessun riquadro esce. */
  fun videoLooksComplete(top: TopLevel): Boolean {
    if (top.truncated || top.malformed) return false
    val hasMovie = top.boxes.any { it.type == "moov" || it.type == "moof" }
    // Oltre il tetto di riquadri non si puo' dire: nel dubbio e' intero.
    return hasMovie || !top.complete
  }

  // ---- HEIF ----------------------------------------------------------------------------------------------------

  private class Item(val id: Long, val type: String, val contentType: String?)

  private class Extent(val offset: Long, val length: Long)

  private class Prop(val type: String, val content: ByteArray)

  suspend fun scanHeif(ra: RandomAccess): ImageScan {
    val top = walkTopLevel(ra)
    if (top.truncated) return ImageScan(truncated = true)
    val meta = top.first("meta") ?: return ImageScan(malformed = true)
    val bodyStart = meta.contentStart + 4 // versione e flag del FullBox
    val bodyLength = meta.end - bodyStart
    if (bodyLength <= 0 || bodyLength > MAX_META_BYTES) return ImageScan(malformed = true)
    val body = ra.read(bodyStart, bodyLength.toInt())
    if (body.size < bodyLength) return ImageScan(truncated = true)
    return try {
      parseMeta(ra, body)
    } catch (_: IndexOutOfBoundsException) {
      ImageScan(malformed = true)
    } catch (_: BoxLimitExceeded) {
      ImageScan(malformed = true)
    }
  }

  private suspend fun parseMeta(ra: RandomAccess, body: ByteArray): ImageScan {
    var primaryId: Long? = null
    val items = LinkedHashMap<Long, Item>()
    val locations = HashMap<Long, List<Extent>>()
    var props: List<Prop> = emptyList()
    val associations = HashMap<Long, List<Int>>()

    forEachChild(body, 0, body.size) { type, start, end ->
      when (type) {
        "pitm" -> primaryId = if (body.u8(start) == 0) body.u16be(start + 4).toLong() else body.u32be(start + 4)
        "iinf" -> parseIinf(body, start, end, items)
        "iloc" -> parseIloc(body, start, end, locations)
        "iprp" -> forEachChild(body, start, end) { t2, s2, e2 ->
          if (t2 == "ipco") props = parseIpco(body, s2, e2)
          if (t2 == "ipma") parseIpma(body, s2, e2, associations)
        }
      }
    }

    val primary = primaryId ?: items.values.firstOrNull { it.type in IMAGE_ITEM_TYPES }?.id
    var width: Int? = null
    var height: Int? = null
    var orientation: ExifOrientation? = null
    if (primary != null) {
      var rotation = 0
      var mirrored = false
      var transformed = false
      for (index in associations[primary].orEmpty()) {
        val prop = props.getOrNull(index - 1) ?: continue
        when (prop.type) {
          "ispe" -> if (prop.content.size >= 12) {
            val w = prop.content.u32be(4)
            val h = prop.content.u32be(8)
            if (w in 1..TiffFile.MAX_SIDE && h in 1..TiffFile.MAX_SIDE) { width = w.toInt(); height = h.toInt() }
          }
          "irot" -> if (prop.content.isNotEmpty()) {
            val counterClockwise = (prop.content.u8(0) and 3) * 90
            rotation = (rotation + (360 - counterClockwise) % 360) % 360
            transformed = true
          }
          "imir" -> if (prop.content.isNotEmpty()) {
            val horizontalAxis = prop.content.u8(0) and 1 == 1
            rotation = if (horizontalAxis) ((180 - rotation) % 360 + 360) % 360 else (360 - rotation) % 360
            mirrored = !mirrored
            transformed = true
          }
        }
      }
      if (transformed) orientation = ExifOrientation.of(rotation, mirrored)
    }

    var exifTiff: ByteArray? = null
    var xmp: String? = null
    var truncated = false
    for (item in items.values) {
      val isExif = item.type == "Exif"
      val isXmp = item.type == "mime" && item.contentType?.startsWith("application/rdf+xml") == true
      if (!isExif && !isXmp) continue
      if (isExif && exifTiff != null || isXmp && xmp != null) continue
      val extents = locations[item.id] ?: continue
      // Ogni extent dentro i limiti prima di sommare: niente lunghezze negative che si compensano o somme che traboccano.
      if (extents.any { it.offset < 0 || it.length < 0 || it.length > MAX_ITEM_BYTES }) continue
      val total = extents.sumOf { it.length }
      if (total <= 0 || total > MAX_ITEM_BYTES) continue
      if (extents.any { it.offset > ra.size || it.length > ra.size - it.offset }) { truncated = true; continue }
      val data = if (extents.size == 1) ra.read(extents[0].offset, extents[0].length.toInt()) else {
        val out = java.io.ByteArrayOutputStream(total.toInt())
        for (e in extents) out.write(ra.read(e.offset, e.length.toInt()))
        out.toByteArray()
      }
      if (isExif && data.size > 8) {
        // 4 byte: distanza dall'intestazione TIFF (di solito 6, dopo "Exif\0\0").
        val skip = data.u32be(0)
        val tiffStart = (4 + skip).toInt()
        exifTiff = if (skip in 0..1024 && tiffStart + 8 <= data.size) stripExifHeader(data.copyOfRange(tiffStart, data.size)) else null
        if (exifTiff == null) exifTiff = stripExifHeader(data.copyOfRange(4, data.size))
      } else if (isXmp) {
        xmp = String(data, Charsets.UTF_8)
      }
    }
    return ImageScan(width = width, height = height, exifTiff = exifTiff, xmp = xmp, heifOrientation = orientation, truncated = truncated, malformed = width == null && !truncated)
  }

  private val IMAGE_ITEM_TYPES = setOf("hvc1", "hev1", "av01", "avc1", "grid", "jpeg", "iovl", "vvc1")

  /**
   * Chiama [block] per ogni riquadro figlio di `[from, to)`: tipo, inizio del contenuto e fine. I figli sono al piu'
   * [MAX_CHILD_BOXES]: ognuno pesa almeno 8 byte, quindi un `meta` da 8 MiB ne potrebbe dichiarare un milione.
   */
  private inline fun forEachChild(data: ByteArray, from: Int, to: Int, block: (type: String, start: Int, end: Int) -> Unit) {
    var pos = from
    val limit = minOf(to, data.size)
    var children = 0
    while (pos + 8 <= limit) {
      val size = data.u32be(pos)
      val type = data.fourcc(pos + 4)
      if (size < 8 || pos + size > limit) break // 64 bit e 0 non servono dentro `meta`
      if (++children > MAX_CHILD_BOXES) throw BoxLimitExceeded()
      block(type, pos + 8, (pos + size).toInt())
      pos = (pos + size).toInt()
    }
  }

  private fun parseIinf(body: ByteArray, start: Int, end: Int, out: MutableMap<Long, Item>) {
    val version = body.u8(start)
    val first = start + 4 + if (version == 0) 2 else 4
    forEachChild(body, first, end) { type, s, e ->
      if (type != "infe") return@forEachChild
      // `entry_count` non si legge (si cammina sui figli), quindi il tetto va sulle voci davvero presenti.
      if (out.size >= MAX_ITEMS) throw BoxLimitExceeded()
      val v = body.u8(s)
      if (v < 2) return@forEachChild
      var p = s + 4
      val id: Long
      if (v == 2) { id = body.u16be(p).toLong(); p += 2 } else { id = body.u32be(p); p += 4 }
      p += 2 // item_protection_index
      val itemType = body.fourcc(p)
      p += 4
      val nameEnd = indexOfZero(body, p, e)
      p = minOf(nameEnd + 1, e)
      var contentType: String? = null
      if (itemType == "mime" && p < e) {
        val ctEnd = indexOfZero(body, p, e)
        contentType = String(body, p, ctEnd - p, Charsets.ISO_8859_1)
      }
      out[id] = Item(id, itemType, contentType)
    }
  }

  private fun indexOfZero(data: ByteArray, from: Int, to: Int): Int {
    var i = from
    while (i < to && data[i] != 0.toByte()) i++
    return i
  }

  private fun parseIloc(body: ByteArray, start: Int, end: Int, out: MutableMap<Long, List<Extent>>) {
    val version = body.u8(start)
    var p = start + 4
    val sizes1 = body.u8(p++)
    val sizes2 = body.u8(p++)
    val offsetSize = sizes1 shr 4
    val lengthSize = sizes1 and 15
    val baseOffsetSize = sizes2 shr 4
    val indexSize = if (version == 1 || version == 2) sizes2 and 15 else 0
    val count: Long
    if (version < 2) { count = body.u16be(p).toLong(); p += 2 } else { count = body.u32be(p); p += 4 }
    // Tetti (R1): nessun conteggio dichiarato decide da solo quanto lavoro o memoria costa il riquadro.
    if (count > MAX_ITEMS) throw BoxLimitExceeded()
    fun number(size: Int): Long {
      var value = 0L
      for (k in 0 until size) value = (value shl 8) or body.u8(p + k).toLong()
      p += size
      return value
    }
    // Byte fissi di una voce prima degli extent e byte di ogni extent (con taglie a 0 un extent non consuma nulla: per
    // questo il numero di extent ha un tetto proprio, e non basta "i byte restanti").
    val itemFixedBytes = (if (version < 2) 2 else 4) + (if (version == 1 || version == 2) 2 else 0) + 2 + baseOffsetSize + 2
    val extentBytes = indexSize + offsetSize + lengthSize
    var totalExtents = 0
    var item = 0L
    while (item++ < count && p < end) {
      if (end - p < itemFixedBytes) throw BoxLimitExceeded() // voce tagliata dal fondo del riquadro
      val id: Long
      if (version < 2) { id = body.u16be(p).toLong(); p += 2 } else { id = body.u32be(p); p += 4 }
      var method = 0
      if (version == 1 || version == 2) { method = body.u16be(p) and 15; p += 2 }
      p += 2 // data_reference_index
      val baseOffset = number(baseOffsetSize)
      val extentCount = body.u16be(p)
      p += 2
      if (extentCount > MAX_EXTENTS_PER_ITEM) throw BoxLimitExceeded()
      totalExtents += extentCount
      if (totalExtents > MAX_EXTENTS_TOTAL) throw BoxLimitExceeded()
      // Con taglie > 0 gli extent dichiarati devono stare davvero nei byte che restano al riquadro.
      if (extentBytes > 0 && extentCount.toLong() * extentBytes > end - p) throw BoxLimitExceeded()
      val extents = ArrayList<Extent>(extentCount)
      for (k in 0 until extentCount) {
        if (indexSize > 0) number(indexSize)
        val offset = number(offsetSize)
        val length = number(lengthSize)
        // Valori a 64 bit con il bit alto acceso o somme che traboccano non sono posizioni vere.
        if (baseOffset < 0 || offset < 0 || length < 0 || baseOffset + offset < 0) throw BoxLimitExceeded()
        extents += Extent(baseOffset + offset, length)
      }
      // Solo i dati nel file (metodo 0): quelli in `idat` o in altre voci non servono a Exif e XMP.
      if (method == 0 && extents.isNotEmpty()) out[id] = extents
    }
  }

  private fun parseIpco(body: ByteArray, start: Int, end: Int): List<Prop> {
    val out = ArrayList<Prop>()
    forEachChild(body, start, end) { type, s, e ->
      if (out.size >= MAX_PROPERTIES) throw BoxLimitExceeded()
      out += Prop(type, body.copyOfRange(s, e))
    }
    return out
  }

  private fun parseIpma(body: ByteArray, start: Int, end: Int, out: MutableMap<Long, List<Int>>) {
    val version = body.u8(start)
    val wideIndex = body.u8(start + 3) and 1 == 1
    var p = start + 4
    val entries = body.u32be(p)
    p += 4
    if (entries > MAX_ITEMS) throw BoxLimitExceeded()
    var k = 0L
    while (k++ < entries && p < end) {
      val id: Long
      if (version < 1) { id = body.u16be(p).toLong(); p += 2 } else { id = body.u32be(p); p += 4 }
      val n = body.u8(p++)
      val list = ArrayList<Int>(n)
      for (a in 0 until n) {
        if (wideIndex) { list += body.u16be(p) and 0x7FFF; p += 2 } else { list += body.u8(p) and 0x7F; p += 1 }
      }
      out[id] = list
    }
  }
}
