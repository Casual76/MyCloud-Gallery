package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Immagini minime ma con intestazioni vere: il codec legge solo le dimensioni, mai i pixel. */
object TestImages {
  /** JPEG: SOI, un APP0 da saltare, SOF0 con le dimensioni, un po' di "dati", EOI. */
  fun jpeg(width: Int, height: Int, filler: Int = 16, sof: Int = 0xC0): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16) + "JFIF".toByteArray() + ByteArray(10))
    out.write(byteArrayOf(0xFF.toByte(), sof.toByte(), 0, 11, 8))
    out.write(byteArrayOf((height ushr 8).toByte(), height.toByte(), (width ushr 8).toByte(), width.toByte(), 1, 1, 0x11, 0))
    out.write(ByteArray(filler) { 0x55 })
    out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    return out.toByteArray()
  }

  /** WebP con perdita (`VP8 `). */
  fun webp(width: Int, height: Int): ByteArray = riff(chunk("VP8 ", vp8Payload(width, height)))

  /** WebP senza perdita (`VP8L`). */
  fun webpLossless(width: Int, height: Int): ByteArray {
    val bits = ((width - 1) and 0x3FFF) or (((height - 1) and 0x3FFF) shl 14)
    val payload = byteArrayOf(0x2F) + le32(bits)
    return riff(chunk("VP8L", payload))
  }

  /** WebP esteso (`VP8X`): tela, eventuale animazione, immagine interna. */
  fun webpExtended(canvasW: Int, canvasH: Int, innerW: Int = canvasW, innerH: Int = canvasH, animated: Boolean = false): ByteArray {
    val header = ByteArray(10)
    header[0] = if (animated) 0x02 else 0x10
    le24(canvasW - 1).copyInto(header, 4)
    le24(canvasH - 1).copyInto(header, 7)
    return riff(chunk("VP8X", header) + chunk("ALPH", ByteArray(3)) + chunk("VP8 ", vp8Payload(innerW, innerH)))
  }

  private fun vp8Payload(width: Int, height: Int): ByteArray =
    byteArrayOf(0x10, 0x02, 0x00, 0x9D.toByte(), 0x01, 0x2A) + le16(width) + le16(height) + ByteArray(6)

  fun chunk(fourcc: String, payload: ByteArray): ByteArray {
    val pad = if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
    return fourcc.toByteArray(Charsets.US_ASCII) + le32(payload.size) + payload + pad
  }

  fun riff(body: ByteArray): ByteArray = "RIFF".toByteArray() + le32(body.size + 4) + "WEBP".toByteArray() + body

  fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

  fun le24(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte())

  fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}

/** Record e segmenti di prova. */
object TestRecords {
  const val NOW: Long = 1_791_806_400_000L

  fun key(i: Int): ContentKey = ContentKey.of(QuickHashAlgorithm.ofContent(byteArrayOf(i.toByte(), (i ushr 8).toByte(), 7)), 1000L + i)

  fun file(i: Int, full: Boolean = false, prep: Set<SegmentClass> = emptySet()): MetaFile {
    val content = ByteArray(64) { (it * 31 + i).toByte() }
    return MetaFile(
      stamp = NasStamp(content.size.toLong() + i, NOW - i * 1000L, fileId = if (full) 1000L + i else null),
      quickHash = QuickHashAlgorithm.ofContent(content),
      fullHash = if (full) Sha256.of(content) else null,
      mediaId = MediaId.random(),
      versionId = VersionId.random(),
      parentVersionId = if (full) VersionId.random() else null,
      versionKind = if (full) VersionKind.EDITED else VersionKind.ORIGINAL,
      kind = if (i % 3 == 0) MediaKind.VIDEO else MediaKind.PHOTO,
      source = MediaSource.CAMERA,
      mime = if (full) "image/jpeg" else null,
      capture = if (full) CaptureTime(NOW - 86_400_000L, 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET) else null,
      width = if (full) 4000 else null,
      height = if (full) 3000 else null,
      durationMs = if (full && i % 3 == 0) 12_345L else null,
      traits = if (full) 5 else 0,
      geo = if (full) GeoE7(436_000_000, 112_000_000) else null,
      thumbHash = if (full) ThumbHash.of(ByteArray(25) { it.toByte() }) else null,
      prepared = SegmentClass.maskOf(prep),
      failure = if (full) PrepFailure("DECODER_MISSING", "c2_qti") else null,
    )
  }

  fun present(path: String, seq: Long, i: Int = seq.toInt(), full: Boolean = false): MetaRecord.Present =
    MetaRecord.Present(SharePath.parseMediaPath(path), seq, file(i, full))

  fun gone(path: String, seq: Long): MetaRecord.Gone = MetaRecord.Gone(SharePath.parseMediaPath(path), seq)

  /** Un JSON qualunque come corpo di una voce `meta` (per i casi ostili: niente controlli di chi scrive). */
  fun json(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

  /** Un record valido in JSON canonico, con [edit] che lo rovina sul testo. */
  fun recordJson(path: String = "Foto/a.jpg", seq: Long = 1, edit: (String) -> String = { it }): ByteArray =
    edit(String(MetaRecords.encode(present(path, seq)), Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
}

/**
 * Un segmento scritto "a mano", come lo scriverebbe un attaccante: si sceglie ogni byte, e il riferimento si calcola
 * sui byte finali (un dispositivo compromesso lo firmerebbe nella sua testa), cosi' il controllo d'impronta passa e si
 * arriva davvero al contenuto.
 */
class RawSegment(
  var magic: ByteArray = "MCGB".toByteArray(),
  var version: Int = 1,
  var cls: Int = SegmentClass.META.code,
  var reserved: ByteArray = byteArrayOf(0, 0, 0),
  var declaredCount: Int? = null,
  val rows: MutableList<Pair<ByteArray, Long>> = mutableListOf(),
  var bodies: ByteArray = ByteArray(0),
  var trailing: ByteArray = ByteArray(0),
) {
  fun add(key: ByteArray, body: ByteArray, declaredLength: Long = body.size.toLong()): RawSegment {
    rows += key to declaredLength
    bodies += body
    return this
  }

  fun bytes(): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(magic)
    out.write(version)
    out.write(cls)
    out.write(reserved)
    out.write(ByteBuffer.allocate(4).putInt(declaredCount ?: rows.size).array())
    for ((key, length) in rows) {
      out.write(key)
      out.write(ByteBuffer.allocate(4).putInt(length.toInt()).array())
    }
    out.write(bodies)
    out.write(trailing)
    return out.toByteArray()
  }

  fun ref(bytes: ByteArray = bytes()): SegmentRef = SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong())

  fun decode(expected: SegmentClass = SegmentClass.fromCode(cls) ?: SegmentClass.META) = bytes().let { IndexSegments.decode(it, ref(it), expected) }

  companion object {
    fun metaOf(vararg records: MetaRecord): RawSegment {
      val raw = RawSegment(cls = SegmentClass.META.code)
      records.sortedWith { a, b -> IndexKeys.compare(IndexKeys.meta(a.path), IndexKeys.meta(b.path)) }
        .forEach { raw.add(IndexKeys.meta(it.path), MetaRecords.encode(it)) }
      return raw
    }
  }
}

fun randomBytes(random: Random, size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
