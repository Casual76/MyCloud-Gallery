package dev.pampa.mycloudgallery.core.metadata.strip

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.TreeMap
import java.util.zip.DataFormatException
import java.util.zip.Inflater

// Correzioni 5B (docs/review/5b-fase5b.md R1, R2, R12): il CONTROLLO FINALE di un file da cui si e' tolta la posizione, **scritto da zero in questo
// file e senza riusare niente dello stripper**: ne' i suoi camminatori di segmenti, chunk e riquadri, ne' i suoi elenchi di cio' che si tiene, ne'
// i suoi aiuti sui byte (`RawFile`, `u32be`...). Se lo stripper ha un punto cieco, qui non si eredita. Non toglie niente: guarda i byte del file
// finale e dice se c'e' ancora una traccia di posizione, in qualunque forma la conosciamo:
//  - un blocco GPS dell'EXIF con almeno una voce (il puntatore 0x8825 di IFD0), ovunque ci sia un EXIF (JPEG, PNG, WebP, HEIF, in coda al file, in
//    un'immagine secondaria, dentro `mdat`);
//  - un nome di proprieta' XMP con `gps`, `latitude`, `longitude` (o `longtitude`, come lo scrivono i droni DJI) in qualunque spazio dei nomi, anche
//    nell'XMP esteso spezzato in piu' segmenti (si riassembla per GUID prima di cercare) e in UTF-16;
//  - le parole `latitude`, `longitude`, `iso6709` in un testo, le coordinate ISO 6709 (`+45.4642+009.1900/`), le frasi NMEA (`$GPRMC,`), i blocchi
//    GPS di GoPro (`GPS5`) e delle dashcam Novatek (`freeGPS`);
//  - i riquadri `©xyz`, `loci`, `gps ` di MP4 e MOV, e ogni traccia che non sia audio o video (metadati a tempo, sottotitoli, `mebx`, `djmd`...).
// Un file che non si sa leggere fino in fondo (contenitore sconosciuto, struttura che esce dal file, troppo complesso o troppo lungo da guardare)
// non e' "senza posizione": e' illeggibile, e chi chiama si ferma (si fallisce chiusi).
//
// Tetti: ogni pacchetto XMP al massimo 16 MiB, i testi compressi dei PNG al massimo 16 MiB, al massimo 64 livelli di annidamento e 200.000
// riquadri, e un tempo massimo per tutto il file. Le ricerche sono lineari (nessuna espressione regolare): un file costruito apposta non blocca un
// thread (R13).

/** Una traccia di posizione trovata da [LocationTraces]. Solo codici: mai il valore trovato. */
enum class LocationTrace(val code: String) {
  /** Un blocco GPS dell'EXIF con almeno una voce (o un puntatore che non si legge). */
  EXIF_GPS("exif_gps"),

  /** Un nome di proprieta' XMP (o di un testo `nome=valore`) con `gps`, `latitude`, `longitude`. */
  XMP_NAME("xmp_name"),

  /** Le parole `latitude`, `longitude`, `iso6709` in un testo. */
  LOCATION_WORD("location_word"),

  /** Coordinate ISO 6709 come le scrivono i telefoni (`+45.4642+009.1900/`). */
  ISO6709("iso6709"),

  /** Frasi NMEA dei ricevitori GPS (`$GPRMC,`, `$GPGGA,`...). */
  NMEA("nmea"),

  /** I blocchi GPS di GoPro (`GPS5`) e delle dashcam Novatek (`freeGPS`). */
  GPS_BLOCK("gps_block"),

  /** Un riquadro di posizione di MP4 o MOV (`©xyz`, `loci`, `gps `...). */
  LOCATION_BOX("location_box"),

  /** Una traccia che non e' audio ne' video (metadati a tempo, sottotitoli, `mebx`, `djmd`, `camm`, `gpmd`...). */
  NON_AV_TRACK("non_av_track"),
}

/** L'esito di [LocationTraces.scan]: le tracce trovate e se il file si e' letto fino in fondo. */
class TraceScan(val traces: Set<LocationTrace>, val readable: Boolean) {
  /** Si e' letto tutto e non c'e' nessuna traccia. */
  val clean: Boolean get() = readable && traces.isEmpty()

  override fun toString(): String = "TraceScan(${traces.joinToString { it.code }}, leggibile=$readable)"
}

/** Il rilevatore grezzo. Sincrono e bloccante: chi chiama lo gira su un dispatcher di I/O. */
object LocationTraces {

  /** Il tempo massimo per guardare un file (anche un video da 2 GiB si legge in pochi secondi). */
  const val MAX_SCAN_MILLIS: Long = 5L * 60 * 1000

  fun scan(file: File, maxMillis: Long = MAX_SCAN_MILLIS): TraceScan = try {
    RandomAccessFile(file, "r").use { TraceWalk(it, System.nanoTime() + maxMillis * 1_000_000L).run() }
  } catch (_: IOException) {
    TraceScan(emptySet(), readable = false)
  } catch (_: SecurityException) {
    TraceScan(emptySet(), readable = false)
  }

  /** Solo i byte di un pacchetto XMP (per i test e per chi ha gia' il pacchetto in mano): nomi e testo. */
  fun scanXmp(packet: ByteArray): Set<LocationTrace> {
    val out = LinkedHashSet<LocationTrace>()
    XmlNames.check(packet, out)
    Patterns.scan(packet, 0, packet.size, out, null)
    return out
  }
}

/**
 * Il controllo finale come lettore del ricontrollo: lo stripper lo mette sempre in testa ai lettori iniettati, che non lo possono togliere. Una
 * traccia trovata e' una posizione; un file che non si legge fino in fondo non assolve.
 */
class LocationTraceRecheck : LocationRecheck {
  override fun appliesTo(mime: String): Boolean = true

  override fun check(file: File, mime: String): LocationVerdict {
    val scan = LocationTraces.scan(file)
    return when {
      scan.traces.isNotEmpty() -> LocationVerdict.HAS_LOCATION
      !scan.readable -> LocationVerdict.UNREADABLE
      else -> LocationVerdict.CLEAR
    }
  }
}

// --- La camminata ------------------------------------------------------------------------------------------------------------------

private class TraceWalk(private val raf: RandomAccessFile, private val deadline: Long) {

  private val size: Long = raf.length()
  private val found = LinkedHashSet<LocationTrace>()
  private var broken = false
  private var boxes = 0

  /** I pezzi dell'XMP esteso dei JPEG, per GUID e per posizione: si cercano riassemblati. */
  private val extended = HashMap<String, TreeMap<Long, ByteArray>>()
  private var extendedBytes = 0L

  fun run(): TraceScan {
    val head = bytes(0, 64)
    when {
      head.size >= 3 && b(head, 0) == 0xFF && b(head, 1) == 0xD8 && b(head, 2) == 0xFF -> jpeg(0, size, 0)
      head.size >= 8 && head.copyOf(8).contentEquals(PNG_SIGNATURE) -> png()
      head.size >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP") -> webp()
      isIso(head) -> iso(0, size, 0, topLevel = true)
      else -> broken = true
    }
    if (System.nanoTime() > deadline) broken = true
    return TraceScan(found, readable = !broken)
  }

  private fun late(): Boolean {
    if (System.nanoTime() > deadline) broken = true
    return broken
  }

  // --- JPEG ----------------------------------------------------------------------------------------------------------------------

  private fun jpeg(start: Long, end: Long, depth: Int) {
    if (depth > MAX_DEPTH) {
      broken = true
      return
    }
    var p = start + 2
    var segments = 0
    while (p + 2 <= end && !late()) {
      if (++segments > MAX_SEGMENTS) break
      if (byteAt(p) != 0xFF) {
        // Non e' un marcatore: da qui in poi non e' piu' struttura del JPEG. Si guarda come un pezzo sconosciuto, e il file non si dice letto.
        unknown(p, end, depth)
        break
      }
      var marker = byteAt(p + 1)
      var fill = 0
      while (marker == 0xFF && fill++ < 64) {
        p++
        marker = byteAt(p + 1)
      }
      when {
        marker < 0 -> break
        marker == 0xD9 -> {
          unknown(p + 2, end, depth)
          finishExtended()
          return
        }
        marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> {
          p += 2
          continue
        }
      }
      val length = u16(p + 2)
      if (length < 2 || p + 2 + length > end) break
      // APPn, commenti e marcatori sconosciuti sono metadati; tabelle, fotogrammi e scansioni no.
      if (marker !in KNOWN_FRAME_MARKERS) segment(marker, p + 4, length - 2)
      p += 2 + length
      if (marker == 0xDA) p = afterEntropy(p, end)
    }
    finishExtended()
    // Finito senza EOI (troncato, struttura che non torna, troppi segmenti): non e' un file di cui si possa dire "senza posizione".
    broken = true
  }

  /** Dopo l'intestazione di una scansione: i dati compressi fino al prossimo marcatore vero (non `FF00`, non `RSTn`). */
  private fun afterEntropy(from: Long, end: Long): Long {
    var block = from
    while (block < end && !late()) {
      val data = bytes(block, BLOCK)
      if (data.isEmpty()) return end
      var i = 0
      while (i < data.size - 1) {
        if (data[i] == 0xFF.toByte()) {
          val next = b(data, i + 1)
          if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) return block + i
        }
        i++
      }
      block += (data.size - 1).coerceAtLeast(1)
    }
    return end
  }

  private fun segment(marker: Int, at: Long, length: Int) {
    if (length <= 0) return
    val data = bytes(at, length)
    when {
      marker == 0xE1 && startsWith(data, EXIF_HEADER) -> {
        tiff(data, EXIF_HEADER.size)
        text(data)
      }
      marker == 0xE1 && startsWith(data, XMP_MAIN) -> xmp(data.copyOfRange(XMP_MAIN.size, data.size))
      marker == 0xE1 && startsWith(data, XMP_EXTENSION) -> extendedPart(data)
      else -> {
        // APP13 (Photoshop: EXIF e XMP dentro), APP2 (ICC, MPF), commenti, segmenti sconosciuti: testo e marcatori incorporati.
        XmlNames.check(data, found)
        text(data)
        tiffHeaders(data)
      }
    }
  }

  /** Parole, coordinate e marcatori incorporati in un pezzo gia' in memoria. */
  private fun text(data: ByteArray) = Patterns.scan(data, 0, data.size, found, { pos, kind -> embedded(data, pos, kind) })

  /** Un pezzo di XMP esteso: GUID (32), lunghezza totale (4), posizione (4), dati. Si guarda da solo e poi riassemblato. */
  private fun extendedPart(data: ByteArray) {
    val head = XMP_EXTENSION.size
    if (data.size < head + 40) return
    val guid = String(data, head, 32, Charsets.ISO_8859_1)
    val offset = u32(data, head + 36)
    val part = data.copyOfRange(head + 40, data.size)
    xmp(part)
    extendedBytes += part.size
    if (extendedBytes > MAX_XMP) {
      broken = true
      return
    }
    extended.getOrPut(guid) { TreeMap() }[offset] = part
  }

  private fun finishExtended() {
    for (parts in extended.values) {
      val total = parts.values.sumOf { it.size }
      val whole = ByteArray(total)
      var at = 0
      for (part in parts.values) {
        part.copyInto(whole, at)
        at += part.size
      }
      xmp(whole)
    }
    extended.clear()
  }

  // --- PNG -----------------------------------------------------------------------------------------------------------------------

  private fun png() {
    var p = 8L
    var chunks = 0
    while (p + 12 <= size && !late()) {
      if (++chunks > MAX_SEGMENTS) {
        broken = true
        return
      }
      val length = u32(p)
      val type = String(bytes(p + 4, 4), Charsets.ISO_8859_1)
      if (length > Int.MAX_VALUE || p + 12 + length > size) {
        broken = true
        return
      }
      val dataAt = p + 8
      when (type) {
        "IDAT", "fdAT" -> Unit
        "IEND" -> {
          unknown(p + 12 + length, size, 0)
          return
        }
        "tEXt", "zTXt", "iTXt" -> pngText(type, bytes(dataAt, length.toInt()))
        "eXIf" -> bytes(dataAt, length.toInt()).let {
          tiff(it, 0)
          text(it)
        }
        else -> if (length > 0) bigRegion(dataAt, dataAt + length, deep = true)
      }
      p += 12 + length
    }
    // Senza IEND il PNG e' troncato.
    broken = true
  }

  private fun pngText(type: String, data: ByteArray) {
    val keywordEnd = zeroFrom(data, 0)
    if (keywordEnd < 0) {
      XmlNames.check(data, found)
      text(data)
      return
    }
    val keyword = String(data, 0, keywordEnd, Charsets.ISO_8859_1)
    // La parola chiave e' un nome: ImageMagick scrive l'EXIF come testo, `exif:GPSLatitude` = "45/1, 27/1, ...".
    if (XmlNames.suspiciousName(keyword)) found += LocationTrace.XMP_NAME
    val text: ByteArray? = when (type) {
      "tEXt" -> data.copyOfRange(keywordEnd + 1, data.size)
      "zTXt" -> inflate(data, keywordEnd + 2, data.size - keywordEnd - 2)
      else -> {
        val compressed = data.getOrElse(keywordEnd + 1) { 0 }.toInt() != 0
        val languageEnd = zeroFrom(data, keywordEnd + 3)
        val translatedEnd = if (languageEnd < 0) -1 else zeroFrom(data, languageEnd + 1)
        if (translatedEnd < 0) {
          null
        } else if (compressed) {
          inflate(data, translatedEnd + 1, data.size - translatedEnd - 1)
        } else {
          data.copyOfRange(translatedEnd + 1, data.size)
        }
      }
    }
    if (text == null) {
      // Un testo che non si apre (compressione rovinata, oltre il tetto): non si puo' dire che sia senza posizione.
      broken = true
      return
    }
    if (keyword.startsWith(RAW_PROFILE, ignoreCase = true)) rawProfile(text)
    xmp(text)
  }

  /** "Raw profile type exif/xmp/iptc" di ImageMagick: i byte in esadecimale. */
  private fun rawProfile(text: ByteArray) {
    val out = java.io.ByteArrayOutputStream()
    var high = -1
    for (c in text) {
      val v = hexValue(c.toInt() and 0xFF)
      if (v < 0) continue
      if (high < 0) {
        high = v
      } else {
        out.write((high shl 4) or v)
        high = -1
      }
      if (out.size() > MAX_XMP) {
        broken = true
        return
      }
    }
    val decoded = out.toByteArray()
    XmlNames.check(decoded, found)
    text(decoded)
    tiffHeaders(decoded)
  }

  // --- WebP ----------------------------------------------------------------------------------------------------------------------

  private fun webp() {
    val riffEnd = minOf(size, 8 + u32le(4))
    webpChunks(12, riffEnd, inFrame = false)
    if (riffEnd < size) unknown(riffEnd, size, 0)
  }

  private fun webpChunks(from: Long, to: Long, inFrame: Boolean) {
    var p = from
    var chunks = 0
    while (p + 8 <= to && !late()) {
      if (++chunks > MAX_SEGMENTS) {
        broken = true
        return
      }
      val type = String(bytes(p, 4), Charsets.ISO_8859_1)
      val length = u32le(p + 4)
      if (p + 8 + length > to) {
        broken = true
        return
      }
      val dataAt = p + 8
      when (type) {
        "VP8 ", "VP8L", "ALPH" -> Unit
        "ANMF" -> if (inFrame) broken = true else webpChunks(dataAt + 16, dataAt + length, inFrame = true)
        "EXIF" -> bytes(dataAt, length.toInt()).let { data ->
          tiff(data, if (startsWith(data, EXIF_HEADER)) EXIF_HEADER.size else 0)
          text(data)
        }
        "XMP " -> xmp(bytes(dataAt, length.toInt()))
        else -> if (length > 0) bigRegion(dataAt, dataAt + length, deep = true)
      }
      p += 8 + length + (length and 1)
    }
  }

  // --- MP4, MOV, HEIF ------------------------------------------------------------------------------------------------------------

  private fun iso(from: Long, to: Long, depth: Int, topLevel: Boolean) {
    if (depth > MAX_DEPTH) {
      broken = true
      return
    }
    var p = from
    while (p + 8 <= to && !late()) {
      if (++boxes > MAX_BOXES) {
        broken = true
        return
      }
      val head = bytes(p, 16)
      var boxSize = u32(head, 0)
      val type = String(head, 4, 4, Charsets.ISO_8859_1)
      var header = 8
      if (boxSize == 1L) {
        if (head.size < 16) {
          broken = true
          return
        }
        boxSize = u64(head, 8)
        header = 16
      } else if (boxSize == 0L) {
        boxSize = to - p
      }
      if (boxSize < header || boxSize > to - p) {
        // In cima al file una coda che non e' un riquadro (il trailer di Samsung, riempimento) si guarda come un pezzo sconosciuto; dentro un
        // riquadro e' una struttura che non torna.
        if (topLevel) unknown(p, to, depth) else broken = true
        return
      }
      box(type, p + header, p + boxSize, depth)
      p += boxSize
    }
    if (p < to && topLevel) unknown(p, to, depth)
  }

  private fun box(type: String, start: Long, end: Long, depth: Int) {
    val length = end - start
    if (type in LOCATION_BOXES) found += LocationTrace.LOCATION_BOX
    when (type) {
      // I dati compressi (anche le immagini di un HEIF): solo cio' che non si trova per caso, e i marcatori `Exif\0\0` e `<x:xmpmeta` degli elementi.
      "mdat", "idat" -> bigRegion(start, end, deep = false)
      "trak" -> {
        if (handlerOf(start, end, depth) !in AV_HANDLERS) found += LocationTrace.NON_AV_TRACK
        iso(start, end, depth + 1, topLevel = false)
      }
      "meta" -> {
        // In QuickTime `meta` e' un contenitore semplice; in ISO ha 4 byte di versione e flag prima dei figli.
        val head = bytes(start, 8)
        val quickTime = head.size >= 8 && u32(head, 0) >= 8 && fourcc(head, 4)
        iso(start + if (quickTime) 0 else 4, end, depth + 1, topLevel = false)
      }
      "stsd" -> sampleEntries(start, end)
      "uuid", "XMP_" -> bigRegion(start, end, deep = true).also { if (length in 1..MAX_XMP) xmp(bytes(start, length.toInt())) }
      in CONTAINERS -> iso(start, end, depth + 1, topLevel = false)
      "ilst" -> iso(start, end, depth + 1, topLevel = false)
      // FullBox (4) e numero di voci: 2 byte nella versione 0, 4 nelle altre.
      "iinf" -> iso(start + if (byteAt(start) == 0) 6 else 8, end, depth + 1, topLevel = false)
      else -> {
        // Le voci di `ilst` hanno per tipo un indice (0x00000001...): si aprono per guardare il loro `data`.
        if (type.any { it.code < 0x20 } && length >= 8) iso(start, end, depth + 1, topLevel = false)
        // Le tabelle binarie (campioni, indirizzi, intestazioni) non sono testo: niente nomi XML ne' intestazioni TIFF cercati per caso.
        if (length > 0) bigRegion(start, end, deep = type !in BINARY_LEAVES)
      }
    }
  }

  /** Il tipo della traccia (`hdlr` dentro `mdia`): `vide`, `soun`, `meta`, `text`... Null se non c'e' (una traccia cosi' non e' audio ne' video). */
  private fun handlerOf(start: Long, end: Long, depth: Int): String? {
    val mdia = child(start, end, "mdia") ?: return null
    val hdlr = child(mdia.first, mdia.second, "hdlr") ?: return null
    if (hdlr.second - hdlr.first < 12 || depth > MAX_DEPTH) return null
    return String(bytes(hdlr.first + 8, 4), Charsets.ISO_8859_1)
  }

  /** Il primo figlio di tipo [type] in `[start, end)`: inizio e fine del contenuto. */
  private fun child(start: Long, end: Long, type: String): Pair<Long, Long>? {
    var p = start
    var count = 0
    while (p + 8 <= end && count++ < MAX_SEGMENTS) {
      val head = bytes(p, 16)
      var boxSize = u32(head, 0)
      var header = 8
      if (boxSize == 1L && head.size >= 16) {
        boxSize = u64(head, 8)
        header = 16
      } else if (boxSize == 0L) {
        boxSize = end - p
      }
      if (boxSize < header || boxSize > end - p) return null
      if (String(head, 4, 4, Charsets.ISO_8859_1) == type) return (p + header) to (p + boxSize)
      p += boxSize
    }
    return null
  }

  /** Le voci di `stsd`: un formato di metadati a tempo e' una traccia che non e' audio ne' video, qualunque sia il suo `hdlr`. */
  private fun sampleEntries(start: Long, end: Long) {
    val body = bytes(start, minOf(end - start, MAX_TABLE.toLong()).toInt())
    if (body.size < 8) return
    val count = u32(body, 4)
    var p = 8
    var k = 0L
    while (k < count && p + 8 <= body.size) {
      val entrySize = u32(body, p)
      val format = String(body, p + 4, 4, Charsets.ISO_8859_1)
      if (format in METADATA_FORMATS) found += LocationTrace.NON_AV_TRACK
      if (entrySize < 8) break
      Patterns.scan(body, p, minOf(body.size.toLong(), p + entrySize).toInt(), found, null)
      p += entrySize.toInt()
      k++
    }
  }

  // --- Pezzi sconosciuti e regioni grandi --------------------------------------------------------------------------------------------

  /**
   * Un pezzo che non e' struttura (la coda dopo un JPEG o un PNG, i riquadri che non tornano): testo, marcatori incorporati e un eventuale MP4
   * incorporato (la parte video di una motion photo), che si cammina come un video.
   */
  private fun unknown(from: Long, to: Long, depth: Int) {
    if (from >= to) return
    bigRegion(from, to, deep = false)
    // Un MP4 incorporato comincia con `ftyp`: le sue tracce e i suoi riquadri si guardano come quelli di un video.
    var block = from
    while (block < to && !late()) {
      val data = bytes(block, minOf((to - block), (BLOCK + 8).toLong()).toInt())
      if (data.size < 8) break
      var i = 4
      while (i + 4 <= data.size) {
        if (ascii(data, i, "ftyp")) {
          val start = block + i - 4
          val boxSize = u32(data, i - 4)
          if (boxSize in 12..1024 && start >= from) iso(start, to, depth + 1, topLevel = true)
        }
        i++
      }
      block += BLOCK
    }
  }

  /** Una regione grande (anche GB): a blocchi, con un po' di sovrapposizione perche' una parola non cada a cavallo di due blocchi. */
  private fun bigRegion(from: Long, to: Long, deep: Boolean) {
    var block = from
    while (block < to && !late()) {
      val want = minOf(to - block, (BLOCK + OVERLAP).toLong()).toInt()
      val data = bytes(block, want)
      if (data.isEmpty()) break
      val scanEnd = if (block + data.size >= to) data.size else minOf(data.size, BLOCK)
      Patterns.scan(data, 0, scanEnd, found, { pos, kind -> embeddedAt(block + pos, kind) }, data.size)
      if (deep) {
        XmlNames.check(data, found)
        tiffHeaders(data)
      }
      block += BLOCK
    }
  }

  /** Un marcatore incorporato in un pezzo gia' letto in memoria ([data]): un EXIF o un pacchetto XMP. */
  private fun embedded(data: ByteArray, pos: Int, kind: Embedded) {
    when (kind) {
      Embedded.EXIF -> tiff(data, pos + EXIF_HEADER.size)
      Embedded.XMP -> xmp(xmpFrom(data, pos))
    }
  }

  /** Un marcatore incorporato in una regione del file: si rilegge da li'. */
  private fun embeddedAt(abs: Long, kind: Embedded) {
    when (kind) {
      Embedded.EXIF -> tiff(bytes(abs, MAX_TIFF), EXIF_HEADER.size)
      Embedded.XMP -> xmp(xmpFrom(bytes(abs, MAX_XMP_READ), 0))
    }
  }

  /** Da `<x:xmpmeta` o `<?xpacket` fino alla chiusura del pacchetto (o alla fine di cio' che si e' letto). */
  private fun xmpFrom(data: ByteArray, pos: Int): ByteArray {
    val end = indexOf(data, XMP_CLOSE, pos).let { if (it < 0) data.size else minOf(data.size, it + XMP_CLOSE.size) }
    return data.copyOfRange(pos, end)
  }

  private fun xmp(packet: ByteArray) {
    if (packet.size > MAX_XMP) {
      broken = true
      return
    }
    XmlNames.check(packet, found)
    Patterns.scan(packet, 0, packet.size, found, null)
  }

  // --- EXIF ----------------------------------------------------------------------------------------------------------------------

  /** Le intestazioni TIFF (`II*\0`, `MM\0*`) dentro un pezzo di metadati: un EXIF senza il prefisso `Exif\0\0` (Photoshop, PNG, HEIF). */
  private fun tiffHeaders(data: ByteArray) {
    var i = 0
    while (i + 8 <= data.size) {
      val a = b(data, i)
      if ((a == 'I'.code && b(data, i + 1) == 'I'.code && b(data, i + 2) == 0x2A && b(data, i + 3) == 0) ||
        (a == 'M'.code && b(data, i + 1) == 'M'.code && b(data, i + 2) == 0 && b(data, i + 3) == 0x2A)
      ) {
        tiff(data, i)
      }
      i++
    }
  }

  /**
   * Un EXIF (TIFF) a partire da [at] in [data]: un puntatore al blocco GPS (0x8825) in IFD0 con almeno una voce, o che non si legge, e' una
   * traccia. L'XMP dentro l'EXIF (0x02BC) si guarda come ogni XMP.
   */
  private fun tiff(data: ByteArray, at: Int) {
    if (at < 0 || at + 8 > data.size) return
    val little = when {
      b(data, at) == 'I'.code && b(data, at + 1) == 'I'.code -> true
      b(data, at) == 'M'.code && b(data, at + 1) == 'M'.code -> false
      else -> return
    }
    fun u16t(p: Int): Int = if (p < 0 || p + 2 > data.size) -1 else if (little) b(data, p) or (b(data, p + 1) shl 8) else (b(data, p) shl 8) or b(data, p + 1)
    fun u32t(p: Int): Long =
      if (p < 0 || p + 4 > data.size) {
        -1L
      } else if (little) {
        (b(data, p).toLong()) or (b(data, p + 1).toLong() shl 8) or (b(data, p + 2).toLong() shl 16) or (b(data, p + 3).toLong() shl 24)
      } else {
        (b(data, p).toLong() shl 24) or (b(data, p + 1).toLong() shl 16) or (b(data, p + 2).toLong() shl 8) or b(data, p + 3).toLong()
      }
    if (u16t(at + 2) != 42) return
    val ifd0 = u32t(at + 4)
    if (ifd0 < 8 || at + ifd0 + 2 > data.size) return
    val ifdAt = (at + ifd0).toInt()
    val count = u16t(ifdAt)
    if (count !in 0..MAX_IFD_ENTRIES) return
    for (k in 0 until count) {
      val e = ifdAt + 2 + 12 * k
      if (e + 12 > data.size) break
      val tag = u16t(e)
      when (tag) {
        0x8825 -> {
          val gps = u32t(e + 8)
          val gpsAt = at + gps
          // Un puntatore che esce dal blocco non si puo' leggere: nel dubbio, c'e'.
          if (gps <= 0 || gpsAt + 2 > data.size) {
            found += LocationTrace.EXIF_GPS
          } else if (u16t(gpsAt.toInt()) != 0) {
            found += LocationTrace.EXIF_GPS
          }
        }
        0x02BC -> {
          val n = u32t(e + 4)
          val off = u32t(e + 8)
          if (n > 4 && off > 0 && at + off + n <= data.size) xmp(data.copyOfRange((at + off).toInt(), (at + off + n).toInt()))
        }
      }
    }
  }

  // --- Byte del file -------------------------------------------------------------------------------------------------------------

  private fun bytes(pos: Long, len: Int): ByteArray {
    if (len <= 0 || pos < 0 || pos >= size) return ByteArray(0)
    val n = minOf(len.toLong(), size - pos).toInt()
    val out = ByteArray(n)
    raf.seek(pos)
    raf.readFully(out)
    return out
  }

  private fun bytes(pos: Long, len: Long): ByteArray = bytes(pos, minOf(len, Int.MAX_VALUE.toLong()).toInt())

  private fun byteAt(pos: Long): Int = bytes(pos, 1).let { if (it.isEmpty()) -1 else b(it, 0) }

  private fun u16(pos: Long): Int = bytes(pos, 2).let { if (it.size < 2) -1 else (b(it, 0) shl 8) or b(it, 1) }

  private fun u32(pos: Long): Long = bytes(pos, 4).let { if (it.size < 4) -1L else u32(it, 0) }

  private fun u32le(pos: Long): Long = bytes(pos, 4).let {
    if (it.size < 4) -1L else b(it, 0).toLong() or (b(it, 1).toLong() shl 8) or (b(it, 2).toLong() shl 16) or (b(it, 3).toLong() shl 24)
  }

  private fun inflate(data: ByteArray, from: Int, length: Int): ByteArray? {
    if (from < 0 || length < 0 || from + length > data.size) return null
    val inflater = Inflater()
    return try {
      inflater.setInput(data, from, length)
      val out = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(16 * 1024)
      while (!inflater.finished()) {
        val n = inflater.inflate(buffer)
        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
        out.write(buffer, 0, n)
        if (out.size() > MAX_XMP) return null
      }
      out.toByteArray()
    } catch (_: DataFormatException) {
      null
    } finally {
      inflater.end()
    }
  }

  private companion object {
    const val MAX_DEPTH = 64
    const val MAX_SEGMENTS = 100_000
    const val MAX_BOXES = 200_000
    const val MAX_IFD_ENTRIES = 1_000
    const val MAX_XMP = 16 * 1024 * 1024
    const val MAX_XMP_READ = 4 * 1024 * 1024
    const val MAX_TIFF = 1024 * 1024
    const val MAX_TABLE = 1024 * 1024
    const val BLOCK = 1 shl 20
    const val OVERLAP = 64
    const val RAW_PROFILE = "Raw profile type"

    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
    val XMP_MAIN = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    val XMP_EXTENSION = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.ISO_8859_1)
    val XMP_CLOSE = "</x:xmpmeta>".toByteArray(Charsets.ISO_8859_1)

    /** I marcatori della struttura di un JPEG (tabelle, fotogrammi, scansioni): non sono metadati e non si guardano come testo. */
    val KNOWN_FRAME_MARKERS: Set<Int> = (0xC0..0xCF).toSet() + setOf(0xDA, 0xDB, 0xDC, 0xDD, 0xDE, 0xDF)

    /** I riquadri che per definizione portano una posizione (Android, 3GPP, QuickTime, videocamere). */
    val LOCATION_BOXES: Set<String> = setOf("©xyz", "loci", "gps ", "GPS ", "©gps", "©GPS", "gpsa", "©loc")

    /** Le sole tracce che possono restare in una copia senza posizione. */
    val AV_HANDLERS: Set<String?> = setOf("vide", "soun")

    /** I formati di campione dei metadati a tempo conosciuti (GPS, sensori, sottotitoli, chiavi di Apple, droni, Sony). */
    val METADATA_FORMATS: Set<String> = setOf("camm", "gpmd", "djmd", "dbgi", "mebx", "rtmd", "nmea", "gps ", "GPS ", "tx3g", "text", "wvtt", "stpp", "mett", "metx", "urim")

    /** Le tabelle binarie di MP4, MOV e HEIF: dentro non c'e' testo, e una ricerca di nomi XML ci troverebbe solo il caso. */
    val BINARY_LEAVES: Set<String> = setOf(
      "mvhd", "tkhd", "mdhd", "elst", "vmhd", "smhd", "nmhd", "hmhd", "stts", "ctts", "cslg", "stss", "stps", "sdtp", "stsc", "stsz", "stz2", "stco",
      "co64", "sgpd", "sbgp", "subs", "saiz", "saio", "stsh", "padb", "stdp", "iloc", "ipma", "pitm", "ispe", "pixi", "irot", "imir", "iref", "hvcC",
      "avcC", "av1C", "vpcC", "colr", "pasp", "btrt", "esds", "dOps", "dac3", "dec3", "mfhd", "tfhd", "tfdt", "trun", "trex", "mehd",
    )

    /** I riquadri da aprire per guardare i figli. */
    val CONTAINERS: Set<String> = setOf(
      "moov", "mdia", "minf", "stbl", "udta", "edts", "dinf", "mvex", "moof", "traf", "mfra", "sinf", "schi", "tref", "iprp", "ipco", "wave",
      "trgr", "grpl", "strk", "strd", "meco", "mere",
    )

    fun isIso(head: ByteArray): Boolean {
      if (head.size < 8) return false
      val type = String(head, 4, 4, Charsets.ISO_8859_1)
      return type == "ftyp" || (type in setOf("moov", "mdat", "wide", "free", "skip", "pnot") && u32(head, 0) >= 8)
    }

    fun b(data: ByteArray, i: Int): Int = data[i].toInt() and 0xFF

    fun u32(data: ByteArray, i: Int): Long =
      if (i < 0 || i + 4 > data.size) -1L else (b(data, i).toLong() shl 24) or (b(data, i + 1).toLong() shl 16) or (b(data, i + 2).toLong() shl 8) or b(data, i + 3).toLong()

    fun u64(data: ByteArray, i: Int): Long = (u32(data, i) shl 32) or u32(data, i + 4)

    fun ascii(data: ByteArray, at: Int, text: String): Boolean {
      if (at < 0 || at + text.length > data.size) return false
      for (k in text.indices) if (b(data, at + k) != text[k].code) return false
      return true
    }

    fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
      if (data.size < prefix.size) return false
      for (k in prefix.indices) if (data[k] != prefix[k]) return false
      return true
    }

    fun fourcc(data: ByteArray, at: Int): Boolean {
      if (at + 4 > data.size) return false
      for (k in 0 until 4) {
        val c = b(data, at + k)
        if (!(c in 0x20..0x7E || c == 0xA9)) return false
      }
      return true
    }

    fun zeroFrom(data: ByteArray, from: Int): Int {
      var i = maxOf(from, 0)
      while (i < data.size) {
        if (data[i] == 0.toByte()) return i
        i++
      }
      return -1
    }

    fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
      var i = maxOf(from, 0)
      while (i + needle.size <= data.size) {
        var k = 0
        while (k < needle.size && data[i + k] == needle[k]) k++
        if (k == needle.size) return i
        i++
      }
      return -1
    }

    fun hexValue(c: Int): Int = when (c) {
      in '0'.code..'9'.code -> c - '0'.code
      in 'a'.code..'f'.code -> c - 'a'.code + 10
      in 'A'.code..'F'.code -> c - 'A'.code + 10
      else -> -1
    }
  }
}

/** Un marcatore di metadati incorporati trovato nel testo: un EXIF (`Exif\0\0`) o un pacchetto XMP. */
private enum class Embedded { EXIF, XMP }

// --- I nomi dell'XMP -----------------------------------------------------------------------------------------------------------------

/**
 * I nomi di elementi e attributi di un testo XML (o `nome=valore`): un nome che contiene `gps`, `latitude`, `longitude` o `longtitude`, in
 * qualunque spazio dei nomi e con qualunque prefisso, e' una traccia. Lineare: per ogni `<` si legge il nome in avanti, per ogni `=` all'indietro,
 * entrambi entro 256 caratteri. Un pacchetto UTF-16 si legge in UTF-16.
 */
private object XmlNames {

  private const val MAX_NAME = 256

  fun check(data: ByteArray, found: MutableSet<LocationTrace>) {
    val text: CharSequence = if (utf16(data)) decode16(data) else Latin1(data)
    val n = text.length
    var i = 0
    while (i < n) {
      val c = text[i]
      if (c == '<') {
        var s = i + 1
        if (s < n && (text[s] == '/' || text[s] == '?' || text[s] == '!')) s++
        var e = s
        while (e < n && e - s < MAX_NAME && nameChar(text[e])) e++
        if (e > s && suspicious(text, s, e)) {
          found += LocationTrace.XMP_NAME
          return
        }
      } else if (c == '=') {
        var e = i
        var spaces = 0
        while (e > 0 && text[e - 1].isWhitespace() && spaces++ < 64) e--
        var s = e
        while (s > 0 && e - s < MAX_NAME && nameChar(text[s - 1])) s--
        if (e > s && suspicious(text, s, e)) {
          found += LocationTrace.XMP_NAME
          return
        }
      }
      i++
    }
  }

  private fun nameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == ':' || c == '.' || c == '-'

  /** Il nome locale (dopo l'ultimo `:`) contiene una delle parole della posizione. */
  private fun suspicious(text: CharSequence, start: Int, end: Int): Boolean {
    var local = start
    for (k in start until end) if (text[k] == ':') local = k + 1
    return localSuspicious(text.subSequence(local, end).toString())
  }

  /** Un nome intero (con o senza prefisso) che porta una posizione: per le parole chiave dei testi PNG. */
  fun suspiciousName(name: String): Boolean = localSuspicious(name.substringAfterLast(':'))

  private fun localSuspicious(local: String): Boolean {
    val name = local.lowercase()
    return "gps" in name || "latitude" in name || "longitude" in name || "longtitude" in name
  }

  private fun utf16(data: ByteArray): Boolean {
    if (data.size >= 2) {
      val a = data[0].toInt() and 0xFF
      val b = data[1].toInt() and 0xFF
      if ((a == 0xFE && b == 0xFF) || (a == 0xFF && b == 0xFE)) return true
    }
    val probe = minOf(data.size, 64)
    if (probe < 8) return false
    var zeros = 0
    for (i in 0 until probe) if (data[i] == 0.toByte()) zeros++
    return zeros * 3 >= probe
  }

  private fun decode16(data: ByteArray): String {
    val little = (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) || (data.size >= 2 && data[1] == 0.toByte() && data[0] != 0.toByte())
    return String(data, if (little) Charsets.UTF_16LE else Charsets.UTF_16BE)
  }

  /** I byte come caratteri ISO-8859-1, senza copiarli. */
  private class Latin1(private val data: ByteArray) : CharSequence {
    override val length: Int get() = data.size

    override fun get(index: Int): Char = (data[index].toInt() and 0xFF).toChar()

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = String(data, startIndex, endIndex - startIndex, Charsets.ISO_8859_1)

    override fun toString(): String = String(data, Charsets.ISO_8859_1)
  }
}

// --- Le parole e le coordinate -------------------------------------------------------------------------------------------------------

/**
 * La ricerca nei byte, lineare: le coordinate ISO 6709 (con i decimali: `+45.4642+009.1900`), le parole della posizione (senza maiuscole), le
 * frasi NMEA, i blocchi GPS di GoPro e Novatek; e i marcatori di metadati incorporati (`Exif\0\0`, `<x:xmpmeta`, `<?xpacket`), che chi chiama
 * apre con [onEmbedded].
 */
private object Patterns {

  private val WORDS: List<ByteArray> = listOf("latitude", "longitude", "longtitude", "iso6709").map { it.toByteArray(Charsets.ISO_8859_1) }
  private val NMEA: List<ByteArray> = listOf("\$GPRMC,", "\$GPGGA,", "\$GPGLL,", "\$GNRMC,", "\$GNGGA,", "\$GNGLL,").map { it.toByteArray(Charsets.ISO_8859_1) }
  /** `freeGPS` delle dashcam Novatek; `GPS5` di GoPro con il suo tipo (`l`) e la sua dimensione (20 byte): cinque byte da soli si troverebbero per caso. */
  private val BLOCKS: List<ByteArray> = listOf("freeGPS".toByteArray(Charsets.ISO_8859_1), byteArrayOf('G'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte(), '5'.code.toByte(), 'l'.code.toByte(), 20))
  private val EXIF = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
  private val XMP_OPEN: List<ByteArray> = listOf("<x:xmpmeta", "<?xpacket").map { it.toByteArray(Charsets.ISO_8859_1) }

  /** Cerca in `data[from, until)`; un'occorrenza puo' finire oltre [until] fino a [limit] (la sovrapposizione fra i blocchi). */
  fun scan(
    data: ByteArray,
    from: Int,
    until: Int,
    found: MutableSet<LocationTrace>,
    onEmbedded: ((Int, Embedded) -> Unit)?,
    limit: Int = until,
  ) {
    var i = maxOf(from, 0)
    val end = minOf(until, data.size)
    val max = minOf(limit, data.size)
    while (i < end) {
      val c = data[i].toInt() and 0xFF
      when (c) {
        '+'.code, '-'.code -> if (iso6709(data, i, max)) found += LocationTrace.ISO6709
        '$'.code -> if (NMEA.any { at(data, i, max, it, ignoreCase = false) }) found += LocationTrace.NMEA
        'l'.code, 'L'.code, 'i'.code, 'I'.code -> if (WORDS.any { at(data, i, max, it, ignoreCase = true) }) found += LocationTrace.LOCATION_WORD
        'f'.code, 'G'.code -> if (BLOCKS.any { at(data, i, max, it, ignoreCase = false) }) found += LocationTrace.GPS_BLOCK
        'E'.code -> if (onEmbedded != null && at(data, i, max, EXIF, ignoreCase = false)) onEmbedded(i, Embedded.EXIF)
        '<'.code -> if (onEmbedded != null && XMP_OPEN.any { at(data, i, max, it, ignoreCase = false) }) onEmbedded(i, Embedded.XMP)
      }
      i++
    }
  }

  private fun at(data: ByteArray, i: Int, limit: Int, needle: ByteArray, ignoreCase: Boolean): Boolean {
    if (i + needle.size > limit) return false
    for (k in needle.indices) {
      var a = data[i + k].toInt() and 0xFF
      if (ignoreCase && a in 'A'.code..'Z'.code) a += 0x20
      if (a != (needle[k].toInt() and 0xFF)) return false
    }
    return true
  }

  /**
   * ISO 6709 come lo scrivono telefoni e fotocamere: latitudine con segno e 2, 4 o 6 cifre intere, longitudine con segno e 3, 5 o 7, entrambe
   * con i decimali. Senza i decimali la probabilita' di trovarla per caso nei dati compressi di un video non sarebbe piu' trascurabile.
   */
  private fun iso6709(data: ByteArray, i: Int, limit: Int): Boolean {
    var p = i + 1
    val latDigits = digits(data, p, limit)
    if (latDigits != 2 && latDigits != 4 && latDigits != 6) return false
    p += latDigits
    if (p >= limit || data[p] != '.'.code.toByte()) return false
    p++
    val latFraction = digits(data, p, limit)
    if (latFraction !in 1..12) return false
    p += latFraction
    if (p >= limit || (data[p] != '+'.code.toByte() && data[p] != '-'.code.toByte())) return false
    p++
    val lonDigits = digits(data, p, limit)
    if (lonDigits != 3 && lonDigits != 5 && lonDigits != 7) return false
    p += lonDigits
    if (p >= limit || data[p] != '.'.code.toByte()) return false
    return digits(data, p + 1, limit) in 1..12
  }

  private fun digits(data: ByteArray, from: Int, limit: Int): Int {
    var p = from
    while (p < limit && p - from <= 12 && data[p] >= '0'.code.toByte() && data[p] <= '9'.code.toByte()) p++
    return p - from
  }
}
