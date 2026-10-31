package dev.pampa.mycloudgallery.core.trust.security

import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiFx
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.AlbumDocs
import dev.pampa.mycloudgallery.core.trust.data.AlbumFx
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesFx
import dev.pampa.mycloudgallery.core.trust.data.FramesBody
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.data.MetaRecords
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.Segments
import dev.pampa.mycloudgallery.core.trust.data.TestImages
import dev.pampa.mycloudgallery.core.trust.data.TestRecords
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Riscrive il corpus di S3 (a) in `tools/security/corpus/` con i codec dell'app (solo con `MCG_SECURITY_WRITE_CORPUS=1`: di norma il
 * corpus e' quello del repository e questo test si salta). Ogni voce e' un file **valido**: le mutazioni partono da li'.
 */
class S3CorpusGenerator {

  @Test
  fun scriveIlCorpus() {
    assumeTrue("corpus riscritto solo con MCG_SECURITY_WRITE_CORPUS=1", System.getenv("MCG_SECURITY_WRITE_CORPUS") == "1")
    for ((codec, entries) in corpora()) SecurityCorpus.write(codec.first, entries, codec.second)
  }

  internal companion object {
    /** Per ogni codec: (nome, contesto) e le voci. */
    fun corpora(): List<Pair<Pair<String, Map<String, String>>, List<ByteArray>>> {
      val out = ArrayList<Pair<Pair<String, Map<String, String>>, List<ByteArray>>>()
      fun add(codec: String, entries: List<ByteArray>, context: Map<String, String> = emptyMap()) {
        out += (codec to context) to entries
      }

      add(
        "meta",
        listOf(
          IndexSegments.meta(listOf(TestRecords.present("Foto/a.jpg", 1))).bytes,
          IndexSegments.meta(listOf(TestRecords.present("Foto/b.jpg", 2, full = true), TestRecords.gone("Foto/c.jpg", 3), TestRecords.present("Viaggi/2024/città.jpg", 4))).bytes,
          IndexSegments.meta((1..12).map { TestRecords.present("Cartella/$it/f$it.jpg", it.toLong(), full = it % 3 == 0) }).bytes,
        ),
      )
      add(
        "thumbS",
        listOf(
          IndexSegments.blobs(SegmentClass.THUMB_S, listOf(TestRecords.key(1) to TestImages.jpeg(64, 48))).bytes,
          IndexSegments.blobs(SegmentClass.THUMB_S, listOf(TestRecords.key(2) to TestImages.jpeg(32, 32), TestRecords.key(3) to TestImages.jpeg(48, 64, filler = 300))).bytes,
        ),
      )
      add(
        "frames",
        listOf(
          IndexSegments.blobs(SegmentClass.FRAMES, listOf(TestRecords.key(4) to FramesBody.encode(listOf(0 to TestImages.jpeg(16, 16), 500 to TestImages.jpeg(16, 16))))).bytes,
        ),
      )
      add(
        "vec",
        listOf(
          AiSegments.encode(AiClass.VEC, listOf(AiFx.key(1) to AiFx.photo(1))).bytes,
          AiSegments.encode(AiClass.VEC, listOf(AiFx.key(2) to AiFx.video(2, frames = 3))).bytes,
        ),
      )
      add(
        "ocr",
        listOf(
          AiSegments.encode(AiClass.OCR, listOf(AiFx.key(3) to AiFx.text("Via Roma 12", "Scontrino"))).bytes,
          AiSegments.encode(AiClass.OCR, listOf(AiFx.key(4) to AiFx.text("Biglietto del treno", "Firenze Santa Maria Novella", "Posto 42"))).bytes,
        ),
      )
      val album = AlbumFx()
      add(
        "album",
        listOf(
          AlbumDocs.encodeSegment(album.album, album.everyKind(album.phone)).bytes,
          AlbumDocs.encodeSegment(album.album, album.everyKind(album.phone).take(2)).bytes,
        ),
        mapOf("album" to album.album.value, "device" to album.phone.deviceId, "account" to album.account(album.phone).value),
      )
      val faces = FacesFx()
      add("faces", listOf(FacesDocs.bodiesSegment(listOf(faces.key(1) to faces.body(), faces.key(2) to faces.videoBody())).bytes))
      add("cluster", listOf(FacesDocs.clusterSegment(faces.snapshotEntries()).bytes))
      add("people", listOf(FacesDocs.peopleSegment(faces.everyPeople()).bytes))
      add(
        "json",
        listOf(
          MetaRecords.encode(TestRecords.present("Foto/a.jpg", 1)),
          MetaRecords.encode(TestRecords.present("Foto/b.jpg", 7, full = true)),
          MetaRecords.encode(TestRecords.gone("Foto/c.jpg", 9)),
        ),
      )
      // La testa: il contenuto firmato (JSON canonico) di una testa vera; il banco lo rifirma con un dispositivo approvato.
      val family = TestFamily()
      val phone = family.approveNewDevice(name = "Telefono", nasUser = "test")
      val segments = listOf(Segments.wrap("a".toByteArray()).second, Segments.wrap("b".toByteArray()).second).sortedBy { it.hash }
      val head = DeviceHead(family.familyId, phone.deviceId, "Public", HeadKind.INDEX, 1, family.state.registry.version, family.clock.nowMillis(), segments.map { ClassedSegment(SegmentClass.META, it) }).sign(phone)
      add("testa", listOf(Envelope.open(head, DocType.DEVICE_HEAD).payload), mapOf("family" to family.familyId, "device" to phone.deviceId))
      return out
    }
  }
}
