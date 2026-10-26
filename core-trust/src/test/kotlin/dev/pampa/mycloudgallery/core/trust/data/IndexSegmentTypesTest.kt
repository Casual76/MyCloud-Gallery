package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

/** I tipi del contenuto dei segmenti (Fase 4, P0) e la loro coerenza con docs/formato-nas.md (§5.5-bis, §7). */
class IndexSegmentTypesTest {

  @Test
  fun leClassiHannoCodiciENomiDelFormato() {
    assertThat(SegmentClass.entries.associate { it.wire to it.code }).containsExactly(
      "meta", 1, "thumbS", 2, "thumbM", 3, "analysis", 4, "hq", 5, "frames", 6, "vec", 7, "ocr", 8, "personal", 16, "album", 17,
      "faces", 18, "cluster", 19, "people", 20,
    )
    // Fase 7 (D6): `album` solo nelle teste d'album, mai nell'indice, nei dati personali o nei dati AI; non e' un'immagine.
    assertThat(SegmentClass.INDEX_CLASSES).doesNotContain(SegmentClass.ALBUM)
    assertThat(HeadKind.entries.flatMap { it.classes }).doesNotContain(SegmentClass.ALBUM)
    assertThat(SegmentClass.ALBUM.isContentBlob).isFalse()
    assertThat(MetaRecords.PREP_MASK_ALLOWED and SegmentClass.ALBUM.mask).isEqualTo(0)
    (21..31).forEach { assertThat(SegmentClass.fromCode(it)).isNull() }
    // Fase 8 (A3): `faces` (18), `cluster` (19) e `people` (20) li definisce la versione 4, solo per le teste dei volti; per ogni altra
    // testa il nome `faces` resta riservato (E-I4) e nessun tipo di testa elenca una classe dei volti. Non sono immagini ne' `prep`.
    assertThat(SegmentClass.RESERVED_WIRE).containsExactly("faces")
    assertThat(SegmentClass.FACES_CLASSES.map { it.wire }).containsAtLeastElementsIn(SegmentClass.RESERVED_WIRE)
    assertThat(SegmentClass.FACES_CLASSES).containsExactly(SegmentClass.FACES, SegmentClass.CLUSTER, SegmentClass.PEOPLE)
    assertThat(HeadKind.entries.flatMap { it.classes }).containsNoneIn(SegmentClass.FACES_CLASSES)
    assertThat(SegmentClass.INDEX_CLASSES).containsNoneIn(SegmentClass.FACES_CLASSES)
    assertThat(SegmentClass.FACES_CLASSES.none { it.isContentBlob }).isTrue()
    assertThat(MetaRecords.PREP_MASK_ALLOWED and SegmentClass.maskOf(SegmentClass.FACES_CLASSES)).isEqualTo(0)
    (9..15).forEach { assertThat(SegmentClass.fromCode(it)).isNull() }
    assertThat(SegmentClass.INDEX_CLASSES).doesNotContain(SegmentClass.PERSONAL)
    // Fase 6 (D6): `vec` e `ocr` solo nelle teste `ai`, mai nell'indice; non sono immagini e non stanno nella maschera `prep`.
    assertThat(SegmentClass.INDEX_CLASSES).containsNoneOf(SegmentClass.VEC, SegmentClass.OCR)
    assertThat(SegmentClass.AI_CLASSES).containsExactly(SegmentClass.VEC, SegmentClass.OCR)
    assertThat(SegmentClass.VEC.isContentBlob || SegmentClass.OCR.isContentBlob).isFalse()
    assertThat(MetaRecords.PREP_MASK_ALLOWED and (SegmentClass.VEC.mask or SegmentClass.OCR.mask)).isEqualTo(0)
    assertThat(HeadKind.AI.classes).isEqualTo(SegmentClass.AI_CLASSES)
    assertThat(AiClass.entries.map { it.segmentClass }).containsExactly(SegmentClass.VEC, SegmentClass.OCR).inOrder()
    val mask = SegmentClass.maskOf(listOf(SegmentClass.THUMB_S, SegmentClass.HQ))
    assertThat(SegmentClass.fromMask(mask)).containsExactly(SegmentClass.THUMB_S, SegmentClass.HQ)
  }

  @Test
  fun unRecordNonAccettaMaschereOValoriImpossibili() {
    val qh = QuickHashAlgorithm.ofContent(ByteArray(10))
    val file = MetaFile(
      stamp = NasStamp(10, 1_800_000_000_000, fileId = 3),
      quickHash = qh,
      mediaId = MediaId.random(),
      versionId = VersionId.random(),
      kind = MediaKind.PHOTO,
      source = MediaSource.CAMERA,
      mime = "image/jpeg",
      prepared = SegmentClass.maskOf(listOf(SegmentClass.THUMB_S)),
    )
    assertThat(file.contentKey).isEqualTo(dev.pampa.mycloudgallery.core.model.ContentKey.of(qh, 10))
    assertThat(file.preparedClasses).containsExactly(SegmentClass.THUMB_S)
    assertThrows(IllegalArgumentException::class.java) { file.copy(prepared = SegmentClass.META.mask) }
    assertThrows(IllegalArgumentException::class.java) { file.copy(mime = "Image/JPEG; x") }
    assertThrows(IllegalArgumentException::class.java) { file.copy(width = 0) }
    val path = SharePath.parseMediaPath("Foto/a.jpg")
    assertThat(MetaRecord.Present(path, 1, file).toString()).doesNotContain("Foto")
    assertThrows(IllegalArgumentException::class.java) { MetaRecord.Gone(path, 0) }
    assertThrows(IllegalArgumentException::class.java) { PrepFailure("decoder missing", "sw") }
    assertThat(GeoE7(436_000_000, 112_000_000).toString()).doesNotContain("436")
  }

  /**
   * "formato-nas.md aggiornato e coerente con TrustLimits" (riga P0): ogni riga del §7 che nomina una costante tra
   * apici inversi deve riportarne il valore. Cosi' una costante cambiata senza cambiare il documento (o il contrario)
   * rompe il test.
   */
  @Test
  fun iTettiNominatiNelFormatoCoincidonoConLeCostanti() {
    val doc = generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "docs/formato-nas.md") }.first { it.isFile }.readText()
    val section = doc.substringAfter("## 7. Tetti della lettura difensiva").substringBefore("\n## ")
    val rows = Regex("""(?m)^\|[^|\n]*`([A-Z_]+)`[^|\n]*\|\s*([^|\n]+?)\s*\|\s*$""").findAll(section).toList()
    assertWithMessage("righe del §7 con il nome della costante").that(rows.size).isAtLeast(15)
    for (row in rows) {
      val name = row.groupValues[1]
      val field = TrustLimits::class.java.getDeclaredField(name)
      field.isAccessible = true
      val value = (field.get(null) as Number).toLong()
      val written = row.groupValues[2].substringBefore(" ").replace(".", "").replace("'", "")
      assertWithMessage("§7, $name").that(written.toLongOrNull()).isEqualTo(value)
    }
  }
}
