package dev.pampa.mycloudgallery.core.metadata.strip

import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

// Correzioni 5B (R2, docs/review/5b-fase5b.md): una foto senza posizione **si ricostruisce per elenco di cio' che si tiene**. Prima si toglievano le
// etichette GPS dell'EXIF e le proprieta' `GPS…` dell'XMP: un XMP con altri nomi (`drone-dji:GpsLatitude`), l'XMP esteso spezzato, i testi dei
// PNG, l'APP13 di Photoshop e i segmenti dopo la prima scansione restavano. Adesso dal file sorgente si copiano solo i pezzi che servono a vedere
// l'immagine, piu' un EXIF nuovo con l'orientamento e la data (`MinimalExif`):
//  - **JPEG**: SOI, JFIF, l'EXIF nuovo, il profilo colore (APP2 `ICC_PROFILE`), APP14 `Adobe` (serve a decodificare i CMYK), poi tabelle,
//    fotogrammi e scansioni (DQT, DHT, DAC, SOFn, DRI, SOS con i dati compressi, DNL) nell'ordine del sorgente, EOI. Fuori: ogni altro APPn
//    (EXIF di prima, XMP intero ed esteso, APP13, MPF con le immagini secondarie), i commenti, i segmenti sconosciuti, cio' che segue EOI;
//  - **PNG**: IHDR, PLTE, tRNS, IDAT, IEND, i chunk del colore (gAMA, cHRM, sRGB, iCCP con il nome del profilo neutro, sBIT, cICP, mDCv, cLLi),
//    pHYs e i chunk dell'animazione (acTL, fcTL, fdAT), piu' l'EXIF nuovo in `eXIf`. Fuori: `tEXt`, `zTXt`, `iTXt`, `eXIf` di prima, `tIME` e ogni
//    chunk sconosciuto;
//  - **WebP**: i dati dell'immagine (VP8, VP8L, ALPH; per un'animazione ANIM e i fotogrammi ANMF con i soli dati dell'immagine) e il profilo
//    colore (ICCP), con un VP8X nuovo e l'EXIF nuovo. Fuori: `EXIF` e `XMP ` di prima e ogni chunk sconosciuto.
// **Motion photo**: la parte video in coda a un JPEG si riattacca solo dopo averla ricostruita con le regole dei video (`IsoClean`), e il JPEG porta
// un XMP nuovo, scritto qui, che la dichiara (Google "MotionPhoto" e "MicroVideo": solo la lunghezza, nessun altro dato).
// Un file che non si ricostruisce cosi' (troncato, struttura che non torna, un video in coda che non si pulisce) non si condivide: si fallisce chiusi.

internal object ImageRebuild {

  /** Esito: fatto, il file non si ricostruisce con queste regole, o un guasto di lettura e scrittura. */
  enum class Result { DONE, UNSUPPORTED, IO }

  private class Unsupported : Exception(null, null, false, false)

  private const val COPY = 1 shl 20
  private const val MAX_SEGMENTS = 65_536
  private const val ENTROPY = -1

  /**
   * Scrive in [dst] (che c'e' gia', vuoto: lo crea in esclusiva chi chiama) la foto [src] di tipo [type] ricostruita, con l'EXIF [exif] (o
   * nessuno). Il sorgente non si tocca.
   */
  fun run(src: File, dst: File, type: ContainerType, exif: MinimalExif?): Result = try {
    RandomAccessFile(src, "r").use { raf ->
      val file = RawFile(raf)
      when (type) {
        ContainerType.JPEG -> jpeg(file, dst, exif)
        ContainerType.PNG -> dst.outputStream().buffered(COPY).use { png(file, it, exif) }
        ContainerType.WEBP -> dst.outputStream().buffered(COPY).use { webp(file, it, exif) }
        else -> throw Unsupported()
      }
    }
    Result.DONE
  } catch (_: Unsupported) {
    Result.UNSUPPORTED
  } catch (_: IOException) {
    Result.IO
  } catch (_: SecurityException) {
    Result.IO
  }

  // --- JPEG ----------------------------------------------------------------------------------------------------------------------

  /** Un pezzo del JPEG sorgente: un segmento con il suo marcatore (`[start, end)` comprende `FF xx` e la lunghezza) o dati compressi. */
  private class Part(val marker: Int, val start: Long, val end: Long)

  private class Layout(val parts: List<Part>, val eoiEnd: Long)

  /** I fotogrammi (SOFn): senza almeno uno non c'e' un'immagine. */
  private val SOF: Set<Int> = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

  /** Tabelle, fotogrammi e scansioni: cio' che serve a decodificare. */
  private val FRAME_KEEP: Set<Int> = setOf(0xDB, 0xC4, 0xCC, 0xDD, 0xDA, 0xDC, 0xDE, 0xDF) + SOF

  private val JFIF = "JFIF\u0000".toByteArray(Charsets.ISO_8859_1)
  private val ICC = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)
  private val ADOBE = "Adobe".toByteArray(Charsets.ISO_8859_1)
  private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
  private val XMP_PREFIX = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)

  private fun jpeg(file: RawFile, dst: File, exif: MinimalExif?) {
    val layout = layout(file)
    // La parte video di una motion photo: il primo MP4 dopo EOI. Tutto il resto della coda (il trailer SEF, immagini secondarie) non si copia.
    val video = EmbeddedIso.first(file, layout.eoiEnd, file.length)
    val jpegEnd = dst.outputStream().buffered(COPY).use { stream ->
      val out = Counting(stream)
      out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
      val header = layout.parts.takeWhile { it.marker != 0xDA }
      header.firstOrNull { it.marker == 0xE0 && payloadStarts(file, it, JFIF) }?.let { copy(file, it.start, it.end, out) }
      exif?.let { segment(0xE1, EXIF_HEADER + it.tiff(), out) }
      video?.let { segment(0xE1, XMP_PREFIX + motionXmp(it.end - it.start).toByteArray(Charsets.UTF_8), out) }
      header.filter { it.marker == 0xE2 && payloadStarts(file, it, ICC) }.forEach { copy(file, it.start, it.end, out) }
      header.firstOrNull { it.marker == 0xEE && payloadStarts(file, it, ADOBE) && it.end - it.start <= 64 }?.let { copy(file, it.start, it.end, out) }
      var frame = false
      var scan = false
      for (part in layout.parts) {
        when {
          part.marker == ENTROPY -> copy(file, part.start, part.end, out)
          part.marker in FRAME_KEEP -> {
            copy(file, part.start, part.end, out)
            if (part.marker in SOF) frame = true
            if (part.marker == 0xDA) scan = true
          }
        }
      }
      out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
      if (!frame || !scan) throw Unsupported()
      val end = out.count
      video?.let { copy(file, it.start, it.end, out) }
      end
    }
    if (video == null) return
    // Il video riattaccato si ricostruisce sul posto, nella copia: i suoi indirizzi contano dal suo inizio.
    RandomAccessFile(dst, "rw").use { raf ->
      val copy = RawFile(raf)
      if (IsoClean.clean(copy, jpegEnd, copy.length) != null) throw Unsupported()
    }
  }

  private fun layout(file: RawFile): Layout {
    if (file.u8(0) != 0xFF || file.u8(1) != 0xD8) throw Unsupported()
    val size = file.length
    val parts = ArrayList<Part>()
    var pos = 2L
    while (true) {
      if (parts.size > MAX_SEGMENTS) throw Unsupported()
      if (file.u8(pos) != 0xFF) throw Unsupported()
      var marker = file.u8(pos + 1)
      var fill = 0
      while (marker == 0xFF && fill++ < 64) {
        pos++
        marker = file.u8(pos + 1)
      }
      when {
        marker < 0 -> throw Unsupported()
        marker == 0xD9 -> return Layout(parts, pos + 2)
        // Marcatori soli fuori da una scansione: non portano dati e non si copiano.
        marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> {
          pos += 2
          continue
        }
      }
      val head = file.read(pos + 2, 2)
      if (head.size < 2) throw Unsupported()
      val length = head.u16be(0)
      if (length < 2 || pos + 2 + length > size) throw Unsupported()
      parts += Part(marker, pos, pos + 2 + length)
      pos += 2 + length
      if (marker == 0xDA) {
        val end = entropyEnd(file, pos)
        parts += Part(ENTROPY, pos, end)
        pos = end
      }
    }
  }

  /** La fine dei dati compressi di una scansione: il primo `FF` seguito da un marcatore vero (non `00`, non `RSTn`, non riempimento). */
  private fun entropyEnd(file: RawFile, from: Long): Long {
    var block = from
    val size = file.length
    while (block < size) {
      val data = file.read(block, COPY)
      if (data.size < 2) break
      for (i in 0 until data.size - 1) {
        if (data[i] == 0xFF.toByte()) {
          val next = data[i + 1].toInt() and 0xFF
          if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) return block + i
        }
      }
      block += data.size - 1
    }
    // Nessun marcatore fino alla fine: un JPEG troncato.
    throw Unsupported()
  }

  private fun payloadStarts(file: RawFile, part: Part, prefix: ByteArray): Boolean {
    val head = file.read(part.start + 4, prefix.size)
    return head.size == prefix.size && head.contentEquals(prefix)
  }

  private fun segment(marker: Int, payload: ByteArray, out: OutputStream) {
    if (payload.size > 65_533) throw Unsupported()
    val length = payload.size + 2
    out.write(byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()))
    out.write(payload)
  }

  /**
   * L'XMP che dichiara la parte video di una motion photo, scritto qui: il formato di Google ("Container" con la lunghezza del video in coda) e
   * quello vecchio ("MicroVideo"), che leggono anche le gallerie meno recenti. Nessun altro dato: non e' l'XMP del sorgente.
   */
  internal fun motionXmp(videoLength: Long): String =
    "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
      "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"" +
      " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"" +
      " GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" GCamera:MotionPhotoPresentationTimestampUs=\"-1\"" +
      " GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"$videoLength\" GCamera:MicroVideoPresentationTimestampUs=\"-1\">" +
      "<Container:Directory><rdf:Seq>" +
      "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>" +
      "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLength\" Item:Padding=\"0\"/></rdf:li>" +
      "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"

  // --- PNG -----------------------------------------------------------------------------------------------------------------------

  private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

  /** I chunk che restano com'erano. `iCCP` resta anche lui, ma con il nome del profilo neutro (il nome e' un testo libero). */
  private val PNG_KEEP = setOf(
    "PLTE", "tRNS", "IDAT", "gAMA", "cHRM", "sRGB", "sBIT", "cICP", "mDCv", "cLLi", "pHYs", "acTL", "fcTL", "fdAT",
  )

  private fun png(file: RawFile, out: OutputStream, exif: MinimalExif?) {
    if (!file.read(0, 8).contentEquals(PNG_SIGNATURE)) throw Unsupported()
    out.write(PNG_SIGNATURE)
    val size = file.length
    var pos = 8L
    var first = true
    var image = false
    var count = 0
    while (true) {
      if (pos + 12 > size || ++count > MAX_SEGMENTS) throw Unsupported()
      val head = file.read(pos, 8)
      val length = head.u32be(0)
      val type = head.fourcc(4)
      if (length > Int.MAX_VALUE || pos + 12 + length > size) throw Unsupported()
      if (first != (type == "IHDR")) throw Unsupported()
      val end = pos + 12 + length
      when (type) {
        "IHDR" -> {
          copy(file, pos, end, out)
          exif?.let { chunk("eXIf", it.tiff(), out) }
        }
        "iCCP" -> neutralProfileName(file.read(pos + 8, length.toInt()))?.let { chunk("iCCP", it, out) }
        "IEND" -> {
          copy(file, pos, end, out)
          if (!image) throw Unsupported()
          return
        }
        in PNG_KEEP -> {
          copy(file, pos, end, out)
          if (type == "IDAT") image = true
        }
      }
      first = false
      pos = end
    }
  }

  /** `iCCP`: nome del profilo (testo libero, fino a 79 caratteri), zero, compressione, profilo. Il nome diventa "ICC". Null se non torna. */
  private fun neutralProfileName(data: ByteArray): ByteArray? {
    var nameEnd = -1
    for (i in 0 until minOf(data.size, 80)) {
      if (data[i] == 0.toByte()) {
        nameEnd = i
        break
      }
    }
    if (nameEnd < 1 || nameEnd + 2 > data.size) return null
    return "ICC".toByteArray(Charsets.ISO_8859_1) + data.copyOfRange(nameEnd, data.size)
  }

  private fun chunk(type: String, data: ByteArray, out: OutputStream) {
    val name = type.toByteArray(Charsets.ISO_8859_1)
    val crc = CRC32().apply {
      update(name)
      update(data)
    }.value
    out.write(be32(data.size.toLong()))
    out.write(name)
    out.write(data)
    out.write(be32(crc))
  }

  // --- WebP ----------------------------------------------------------------------------------------------------------------------

  /** Un pezzo dell'uscita: byte nuovi, o un intervallo da copiare dal sorgente. */
  private sealed interface Piece {
    val size: Long

    class New(val bytes: ByteArray) : Piece {
      override val size: Long get() = bytes.size.toLong()
    }

    class Copy(val start: Long, val end: Long) : Piece {
      override val size: Long get() = end - start
    }
  }

  private class RiffChunk(val type: String, val start: Long, val length: Long) {
    val dataStart: Long get() = start + 8
    val dataEnd: Long get() = start + 8 + length
  }

  private fun webp(file: RawFile, out: OutputStream, exif: MinimalExif?) {
    val head = file.read(0, 12)
    if (head.size < 12 || !head.startsWithAscii(0, "RIFF") || !head.startsWithAscii(8, "WEBP")) throw Unsupported()
    val riffEnd = minOf(file.length, 8 + head.u32le(4))
    val chunks = riffChunks(file, 12, riffEnd)
    val first = chunks.firstOrNull() ?: throw Unsupported()
    val pieces = ArrayList<Piece>()
    when (first.type) {
      // Un WebP semplice: un solo chunk con l'immagine. Non ha dove tenere un EXIF.
      "VP8 ", "VP8L" -> pieces += copyChunk(first)
      "VP8X" -> {
        if (first.length < 10) throw Unsupported()
        val vp8x = file.read(first.dataStart, 10)
        val sourceFlags = vp8x.u8(0)
        val icc = chunks.firstOrNull { it.type == "ICCP" }
        val anim = chunks.firstOrNull { it.type == "ANIM" }
        val frames = chunks.filter { it.type == "ANMF" }
        val alpha = chunks.firstOrNull { it.type == "ALPH" }
        val image = chunks.firstOrNull { it.type == "VP8 " || it.type == "VP8L" }
        if (image == null && frames.isEmpty()) throw Unsupported()
        val tiff = exif?.tiff()
        var flags = sourceFlags and (FLAG_ALPHA or FLAG_ANIMATION)
        if (icc != null) flags = flags or FLAG_ICC
        if (tiff != null) flags = flags or FLAG_EXIF
        pieces += Piece.New(riffChunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0) + vp8x.copyOfRange(4, 10)))
        icc?.let { pieces += copyChunk(it) }
        if (frames.isNotEmpty()) {
          anim?.let { pieces += copyChunk(it) }
          for (frame in frames) pieces += frame(file, frame)
        } else {
          alpha?.let { pieces += copyChunk(it) }
          image?.let { pieces += copyChunk(it) }
        }
        tiff?.let { pieces += Piece.New(riffChunk("EXIF", it)) }
      }
      else -> throw Unsupported()
    }
    val total = 4 + pieces.sumOf { it.size }
    if (total > 0xFFFFFFFFL) throw Unsupported()
    out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
    out.write(le32(total))
    out.write("WEBP".toByteArray(Charsets.ISO_8859_1))
    for (piece in pieces) {
      when (piece) {
        is Piece.New -> out.write(piece.bytes)
        is Piece.Copy -> copy(file, piece.start, piece.end, out)
      }
    }
  }

  /** Un fotogramma ANMF: l'intestazione (16 byte) e i soli dati dell'immagine (ALPH, VP8, VP8L); ogni altro chunk del fotogramma resta fuori. */
  private fun frame(file: RawFile, frame: RiffChunk): List<Piece> {
    if (frame.length < 16) throw Unsupported()
    val inner = riffChunks(file, frame.dataStart + 16, frame.dataEnd).filter { it.type == "ALPH" || it.type == "VP8 " || it.type == "VP8L" }
    if (inner.none { it.type != "ALPH" }) throw Unsupported()
    val body = inner.flatMap { copyChunk(it) }
    val length = 16 + body.sumOf { it.size }
    val header = "ANMF".toByteArray(Charsets.ISO_8859_1) + le32(length) + file.read(frame.dataStart, 16)
    return listOf(Piece.New(header)) + body
  }

  private fun riffChunks(file: RawFile, from: Long, to: Long): List<RiffChunk> {
    val out = ArrayList<RiffChunk>()
    var pos = from
    while (pos + 8 <= to) {
      if (out.size > MAX_SEGMENTS) throw Unsupported()
      val h = file.read(pos, 8)
      val length = h.u32le(4)
      if (pos + 8 + length > to) throw Unsupported()
      out += RiffChunk(h.fourcc(0), pos, length)
      pos += 8 + length + (length and 1)
    }
    return out
  }

  /** Un chunk com'e' (intestazione e dati), con il byte di allineamento a due. */
  private fun copyChunk(chunk: RiffChunk): List<Piece> {
    val pieces = ArrayList<Piece>(2)
    pieces += Piece.Copy(chunk.start, chunk.dataEnd)
    if (chunk.length % 2 == 1L) pieces += Piece.New(byteArrayOf(0))
    return pieces
  }

  private fun riffChunk(type: String, data: ByteArray): ByteArray {
    val pad = if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
    return type.toByteArray(Charsets.ISO_8859_1) + le32(data.size.toLong()) + data + pad
  }

  private const val FLAG_ICC = 0x20
  private const val FLAG_ALPHA = 0x10
  private const val FLAG_EXIF = 0x08
  private const val FLAG_ANIMATION = 0x02

  // --- Attrezzi ------------------------------------------------------------------------------------------------------------------

  /** Copia `[start, end)` del sorgente in [out], a blocchi. */
  private fun copy(file: RawFile, start: Long, end: Long, out: OutputStream) {
    var pos = start
    while (pos < end) {
      val data = file.read(pos, minOf(end - pos, COPY.toLong()).toInt())
      if (data.isEmpty()) throw IOException("Lettura accorciata.")
      out.write(data)
      pos += data.size
    }
  }

  private fun be32(v: Long): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

  private fun le32(v: Long): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

  /** Conta i byte scritti: dove finisce il JPEG, e dove comincia il video che lo segue. */
  private class Counting(out: OutputStream) : FilterOutputStream(out) {
    var count: Long = 0
      private set

    override fun write(b: Int) {
      out.write(b)
      count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      out.write(b, off, len)
      count += len
    }
  }
}
