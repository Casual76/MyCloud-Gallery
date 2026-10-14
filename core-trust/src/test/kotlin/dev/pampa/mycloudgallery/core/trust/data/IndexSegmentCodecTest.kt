package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Il codec dei segmenti `MCGB` v1 (§5.5-bis): andata e ritorno, chiavi, forma unica dei record. */
class IndexSegmentCodecTest {

  @Test
  fun segmentoMeta_andataERitorno_conTuttiICampiEConLeLapidi() {
    val records = listOf(
      TestRecords.present("Foto/2024/IMG_0001.jpg", 1, full = true),
      TestRecords.present("Foto/2024/IMG_0002.jpg", 2),
      TestRecords.present("Video/città/è un video.mp4", 3, i = 3, full = true),
      TestRecords.gone("Foto/vecchia.jpg", 4),
      TestRecords.present("Emoji/👨‍👩‍👧.heic", 5),
    )

    val encoded = IndexSegments.meta(records)
    val content = IndexSegments.decode(encoded.bytes, encoded.ref, SegmentClass.META).acceptedOrNull() as SegmentContent.Meta

    assertThat(content.records).containsExactlyElementsIn(records)
    // Le voci escono nell'ordine delle chiavi, non in quello di chi scrive.
    val keys = content.records.map { IndexKeys.meta(it.path) }
    assertThat(keys.zipWithNext().all { (a, b) -> IndexKeys.compare(a, b) < 0 }).isTrue()
    // Stessi byte di Segments.wrap: il contenitore della Fase 2 lo accetta.
    assertThat(Segments.unwrap(encoded.bytes, encoded.ref).acceptedOrNull()).isNotNull()
    assertThat(Segments.wrap(encoded.bytes.copyOfRange(5, encoded.bytes.size)).first).isEqualTo(encoded.bytes)
  }

  @Test
  fun segmentiDImmaginiEFotogrammi_andataERitorno_conLeVociDentroIByte() {
    val images = listOf(
      TestRecords.key(1) to TestImages.jpeg(200, 150),
      TestRecords.key(2) to TestImages.webp(400, 300),
      TestRecords.key(3) to TestImages.webpLossless(256, 256),
      TestRecords.key(4) to TestImages.webpExtended(512, 384),
    )
    val encoded = IndexSegments.blobs(SegmentClass.THUMB_M, images)
    val blobs = IndexSegments.decode(encoded.bytes, encoded.ref, SegmentClass.THUMB_M).acceptedOrNull() as SegmentContent.Blobs

    assertThat(blobs.entries.map { it.contentKey }).containsExactlyElementsIn(images.map { it.first })
    for ((key, bytes) in images) {
      val entry = blobs.entries.single { it.contentKey == key }
      assertThat(blobs.bytes.copyOfRange(entry.offset, entry.offset + entry.length)).isEqualTo(bytes)
    }

    val frames = FramesBody.encode(listOf(0 to TestImages.jpeg(1024, 576), 30_000 to TestImages.jpeg(1024, 576, filler = 40)))
    val framesSegment = IndexSegments.blobs(SegmentClass.FRAMES, listOf(TestRecords.key(9) to frames))
    val decoded = IndexSegments.decode(framesSegment.bytes, framesSegment.ref, SegmentClass.FRAMES).acceptedOrNull() as SegmentContent.Blobs
    val parsed = FramesBody.read(decoded.bytes, decoded.entries.single()).acceptedOrNull()!!
    assertThat(parsed.map { it.atMs }).containsExactly(0, 30_000).inOrder()
    assertThat(decoded.bytes.copyOfRange(parsed[1].offset, parsed[1].offset + parsed[1].length)).isEqualTo(TestImages.jpeg(1024, 576, filler = 40))
  }

  @Test
  fun laChiaveMetaNonDistingueMaiuscoleNeFormeUnicode() {
    val nfc = SharePath.parseMediaPath("Foto/Perù.JPG")
    val nfd = SharePath.parseMediaPath("foto/Perù.jpg")

    assertThat(IndexKeys.meta(nfc)).isEqualTo(IndexKeys.meta(nfd))
    // Due record per lo stesso posto in un segmento: errore di chi scrive (la tabella vuole chiavi strettamente crescenti).
    assertThrows(IllegalArgumentException::class.java) {
      IndexSegments.meta(listOf(MetaRecord.Present(nfc, 1, TestRecords.file(1)), MetaRecord.Gone(nfd, 2)))
    }
    assertThat(IndexKeys.meta(SharePath.parseMediaPath("Foto/a.jpg"))).isNotEqualTo(IndexKeys.meta(SharePath.parseMediaPath("Foto/b.jpg")))
  }

  @Test
  fun unRecordHaUnaSolaScrittura() {
    val record = TestRecords.present("Foto/a.jpg", 7)
    val canonical = String(MetaRecords.encode(record), Charsets.UTF_8)
    assertThat(canonical).doesNotContain("vkind")
    assertThat(canonical).doesNotContain("prep")
    assertThat(canonical).doesNotContain("traits")

    assertThat(MetaRecords.verify(canonical.toByteArray()).acceptedOrNull()).isEqualTo(record)

    // Un campo sconosciuto (in ordine canonico) e' un errore, non si ignora.
    assertThat(MetaRecords.verify(canonical.replaceFirst("{", "{\"aaa\":1,").toByteArray()).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    // I default scritti esplicitamente sono un'altra scrittura dello stesso valore ("vkind" viene dopo "version").
    val explicitOriginal = canonical.replace("}", ",\"vkind\":\"original\"}")
    assertThat(MetaRecords.verify(explicitOriginal.toByteArray()).rejectionOrNull()).isEqualTo(RejectReason.NOT_CANONICAL)
    val explicitZeroPrep = canonical.replace("\"qh\":", "\"prep\":0,\"qh\":")
    assertThat(MetaRecords.verify(explicitZeroPrep.toByteArray()).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
  }

  @Test
  fun chiScriveScartaCioCheChiLeggeRifiuterebbe() {
    val longPath = (1..17).joinToString("/") { "c".repeat(230) } + "/x.jpg"
    val record = MetaRecord.Present(SharePath.parseMediaPath(longPath), 1, TestRecords.file(1, full = true))

    assertThat(MetaRecords.encodeOrNull(record)).isNull()
    assertThat(MetaRecords.encodeOrNull(TestRecords.present("Foto/a.jpg", 1, full = true))).isNotNull()
    assertThat(IndexSegments.admits(SegmentClass.THUMB_S, TestImages.jpeg(200, 200))).isTrue()
    assertThat(IndexSegments.admits(SegmentClass.THUMB_S, TestImages.jpeg(20_000, 20_000))).isFalse()
    assertThat(IndexSegments.admits(SegmentClass.FRAMES, TestImages.jpeg(200, 200))).isFalse()
    assertThat(IndexSegments.admits(SegmentClass.THUMB_S, "PNG".toByteArray())).isFalse()
    assertThrows(IllegalArgumentException::class.java) { IndexSegments.blobs(SegmentClass.HQ, listOf(TestRecords.key(1) to ByteArray(10))) }
    assertThrows(IllegalArgumentException::class.java) { IndexSegments.encode(SegmentClass.PERSONAL, listOf(SegmentEntry(ByteArray(32), ByteArray(1)))) }
  }

  @Test
  fun unSegmentoPieno_restaSottoIlTetto() {
    val records = (1..TrustLimits.MAX_SEGMENT_ENTRIES).map { TestRecords.present("Foto/f$it.jpg", it.toLong()) }
    val encoded = IndexSegments.meta(records)

    assertThat(encoded.entries).isEqualTo(TrustLimits.MAX_SEGMENT_ENTRIES)
    assertThat(encoded.bytes.size).isLessThan(TrustLimits.MAX_SEGMENT_BYTES)
    assertThat((IndexSegments.decode(encoded.bytes, encoded.ref, SegmentClass.META).acceptedOrNull() as SegmentContent.Meta).records).hasSize(records.size)
    assertThrows(IllegalArgumentException::class.java) { IndexSegments.meta(records + TestRecords.present("Foto/oltre.jpg", 99_999)) }
  }

  @Test
  fun leDimensioniDelleImmaginiSiLeggonoDallIntestazione() {
    assertThat(IndexImages.inspect(TestImages.jpeg(4000, 3000)).acceptedOrNull()!!.width).isEqualTo(4000)
    assertThat(IndexImages.inspect(TestImages.jpeg(320, 240, sof = 0xC2)).acceptedOrNull()!!.height).isEqualTo(240)
    assertThat(IndexImages.inspect(TestImages.webp(400, 300)).acceptedOrNull()!!.format).isEqualTo(IndexImages.Format.WEBP)
    assertThat(IndexImages.inspect(TestImages.webpLossless(16384, 1)).acceptedOrNull()!!.width).isEqualTo(16384)
    assertThat(IndexImages.inspect(TestImages.webpExtended(100, 80)).acceptedOrNull()!!.height).isEqualTo(80)
  }
}
