package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.format.ArrayAccess
import dev.pampa.mycloudgallery.core.metadata.format.ContainerType
import dev.pampa.mycloudgallery.core.metadata.format.ExifFacts
import dev.pampa.mycloudgallery.core.metadata.format.IsoBmff
import dev.pampa.mycloudgallery.core.metadata.format.JpegScanner
import dev.pampa.mycloudgallery.core.metadata.format.PagedAccess
import dev.pampa.mycloudgallery.core.metadata.format.PngScanner
import dev.pampa.mycloudgallery.core.metadata.format.Sniffer
import dev.pampa.mycloudgallery.core.metadata.format.TiffParser
import dev.pampa.mycloudgallery.core.metadata.format.WebpScanner
import dev.pampa.mycloudgallery.core.metadata.format.XmpFacts
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.fixtures.Bytes
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import dev.pampa.mycloudgallery.fixtures.Img
import dev.pampa.mycloudgallery.fixtures.IsoFixtures
import dev.pampa.mycloudgallery.fixtures.TiffFixtures
import dev.pampa.mycloudgallery.fixtures.XmpPackets
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** I lettori dei contenitori, uno per uno, su file costruiti dai test. */
class FormatReadersTest {

  private fun spec(block: ExifWriter.Spec.() -> Unit = {}): ExifWriter.Spec = ExifWriter.Spec().apply(block)

  // ---- TIFF / EXIF ------------------------------------------------------------------------------------------------

  @Test
  fun exifCompletoInLittleEEBigEndian() = runTest {
    for (big in listOf(false, true)) {
      val s = spec {
        bigEndian = big
        make = "samsung"; model = "SM-S931B"; lens = "Wide 1.8"; orientation = 6
        dateOriginal = "2024:03:12 10:15:30"; offsetOriginal = "+01:00"; subSecOriginal = "07"
        latitude = 43.7696; longitude = 11.2558; altitude = 123.5
        gpsDate = "2024:03:12"; gpsTime = intArrayOf(9, 15, 28)
        fNumber = 1.8; exposure = intArrayOf(1, 250); iso = 125; focalMm = 6.3; flash = 1
        pixelX = 4000; pixelY = 3000
      }
      val tiff = TiffParser.parse(ArrayAccess(ExifWriter.tiff(s)))!!
      assertThat(tiff.bigEndian).isEqualTo(big)
      val f = ExifFacts.read(tiff)
      assertThat(f.make).isEqualTo("samsung")
      assertThat(f.model).isEqualTo("SM-S931B")
      assertThat(f.lens).isEqualTo("Wide 1.8")
      assertThat(f.orientation).isEqualTo(6)
      assertThat(f.local).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 15, 30))
      assertThat(f.offsetMinutes).isEqualTo(60)
      assertThat(f.subSecondMs).isEqualTo(70)
      assertThat(f.gps!!.latitude).isWithin(1e-4).of(43.7696)
      assertThat(f.gps!!.longitude).isWithin(1e-4).of(11.2558)
      assertThat(f.gps!!.altitudeMeters).isWithin(0.01).of(123.5)
      assertThat(f.gpsUtc).isEqualTo(Instant.parse("2024-03-12T09:15:28Z"))
      assertThat(f.fNumber).isEqualTo(1.8)
      assertThat(f.exposureTime).isEqualTo("1/250")
      assertThat(f.iso).isEqualTo(125)
      assertThat(f.focalLengthMm).isEqualTo(6.3)
      assertThat(f.flashFired).isTrue()
      assertThat(f.declaredSize).isEqualTo(4000 to 3000)
      assertThat(f.isDng).isFalse()
    }
  }

  @Test
  fun gliEmisferiSudEOvestHannoIlSegno() = runTest {
    val s = spec { dateOriginal = "2024:03:12 10:15:30"; latitude = -33.8688; longitude = -70.6693; altitude = -12.0 }
    val f = ExifFacts.read(TiffParser.parse(ArrayAccess(ExifWriter.tiff(s)))!!)
    assertThat(f.gps!!.latitude).isWithin(1e-4).of(-33.8688)
    assertThat(f.gps!!.longitude).isWithin(1e-4).of(-70.6693)
    assertThat(f.gps!!.altitudeMeters).isWithin(0.01).of(-12.0)
  }

  @Test
  fun laMiniaturaDellIfd1SiLeggeSoloSeRichiesta() = runTest {
    val thumb = ImageFixtures.exifThumbnail(0x112233)
    val tiff = TiffParser.parse(ArrayAccess(ExifWriter.tiff(spec { dateOriginal = "2024:03:12 10:15:30"; thumbnailJpeg = thumb })))!!
    assertThat(ExifFacts.read(tiff).thumbnail).isNull()
    assertThat(ExifFacts.read(tiff, withThumbnail = true).thumbnail).isEqualTo(thumb)
  }

  @Test
  fun leDateImpossibiliDannoNull() {
    for (text in listOf("0000:00:00 00:00:00", "    :  :     :  :  ", "", "2024:13:40 10:00:00", "2024:02:30 10:00:00", "ciao", "2024:03:12", "2024:03:12 25:00:00", "2024:03:12 10:61:00")) {
      assertThat(ExifFacts.parseDateTime(text)).isNull()
    }
    assertThat(ExifFacts.parseDateTime(null)).isNull()
    assertThat(ExifFacts.parseDateTime("2024:03:12 10:15:30")).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 15, 30))
    // Varianti di separatore e un orario con la fine scritta male.
    assertThat(ExifFacts.parseDateTime("2024-03-12 10:15:30")).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 15, 30))
    assertThat(ExifFacts.parseDateTime(" 2024:03:12 10:15:30 ")).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 15, 30))
    assertThat(ExifFacts.parseDateTime("2024:03:12 10:15:30.123")).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 15, 30))
  }

  @Test
  fun iFusiOrariSiLeggonoEntroQuindiciOre() {
    assertThat(ExifFacts.parseOffset("+01:00")).isEqualTo(60)
    assertThat(ExifFacts.parseOffset("-05:30")).isEqualTo(-330)
    assertThat(ExifFacts.parseOffset("+0545")).isEqualTo(345)
    assertThat(ExifFacts.parseOffset("+14:00")).isEqualTo(840)
    assertThat(ExifFacts.parseOffset("+9")).isEqualTo(540)
    for (bad in listOf("", "Z", "01:00", "+99:00", "+01:75", "+16:00", "ora", "+1:2:3")) assertThat(ExifFacts.parseOffset(bad)).isNull()
    assertThat(ExifFacts.parseOffset(null)).isNull()
  }

  @Test
  fun iSottosecondiSonoLaParteDopoLaVirgola() {
    assertThat(ExifFacts.parseSubSecond("123")).isEqualTo(123)
    assertThat(ExifFacts.parseSubSecond("5")).isEqualTo(500)
    assertThat(ExifFacts.parseSubSecond("07")).isEqualTo(70)
    assertThat(ExifFacts.parseSubSecond("999999")).isEqualTo(999)
    assertThat(ExifFacts.parseSubSecond("abc")).isNull()
    assertThat(ExifFacts.parseSubSecond(null)).isNull()
  }

  @Test
  fun iltempoDiPosaSiScriveCome_1Su250() {
    assertThat(ExifFacts.formatExposure(1.0 / 250)).isEqualTo("1/250")
    assertThat(ExifFacts.formatExposure(1.0 / 3)).isEqualTo("1/3")
    assertThat(ExifFacts.formatExposure(2.0)).isEqualTo("2")
    assertThat(ExifFacts.formatExposure(2.5)).isEqualTo("2.5")
    assertThat(ExifFacts.formatExposure(30.0)).isEqualTo("30")
  }

  @Test
  fun unExifRovinatoNonFermaIlProgramma() = runTest {
    val good = ExifWriter.tiff(spec { dateOriginal = "2024:03:12 10:15:30"; model = "SM"; latitude = 43.0; longitude = 11.0 })
    // Ogni prefisso: non lancia, e restituisce null o un EXIF parziale.
    for (n in 0..good.size) {
      val tiff = TiffParser.parse(ArrayAccess(good.copyOf(n)))
      if (tiff != null) ExifFacts.read(tiff, withThumbnail = true)
    }
    // Ogni byte distrutto, uno alla volta.
    for (i in good.indices) {
      val broken = good.copyOf()
      broken[i] = (broken[i].toInt() xor 0xFF).toByte()
      val tiff = TiffParser.parse(ArrayAccess(broken))
      if (tiff != null) ExifFacts.read(tiff, withThumbnail = true)
    }
  }

  @Test
  fun unIfdCheRimandaASeStessoNonFaGirareAVuoto() = runTest {
    // IFD0 a offset 8 con zero voci valide: il "successivo" punta all'IFD0 stesso.
    val loop = Bytes().ascii("II").u16le(42).u32le(8).u16le(1).u16le(0x0112).u16le(3).u32le(1).u16le(1).u16le(0).u32le(8).toBytes()
    val tiff = TiffParser.parse(ArrayAccess(loop))!!
    assertThat(ExifFacts.read(tiff).orientation).isEqualTo(1)
    assertThat(tiff.ifd1).isNull()
  }

  @Test
  fun unaVoceConUnConteggioEnormeSiIgnora() = runTest {
    // DateTimeOriginal ASCII lungo 4 miliardi di byte, in un file di 100: il valore e' fuori dal file -> assente.
    val exifIfd = Bytes().u16le(1).u16le(0x9003).u16le(2).u32le(0xFFFFFFF0L).u32le(60).u32le(0).toBytes()
    val ifd0 = Bytes().u16le(1).u16le(0x8769).u16le(4).u32le(1).u32le(26).u32le(0).toBytes()
    val file = Bytes().ascii("II").u16le(42).u32le(8).raw(ifd0).raw(exifIfd).toBytes()
    val f = ExifFacts.read(TiffParser.parse(ArrayAccess(file))!!)
    assertThat(f.local).isNull()
  }

  @Test
  fun unDngDichiaraLaDimensioneSviluppataELeAnteprime() = runTest {
    val dng = TiffFixtures.dng(TiffFixtures.DngSpec())
    val tiff = TiffParser.parse(ArrayAccess(dng))!!
    val f = ExifFacts.read(tiff)
    assertThat(f.isDng).isTrue()
    assertThat(f.declaredSize).isEqualTo(3984 to 2988)
    assertThat(tiff.subIfds).hasSize(1)
    assertThat(f.local).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 15, 30))
    assertThat(tiff.declaredDataEnd()).isEqualTo(dng.size.toLong())
  }

  // ---- XMP -----------------------------------------------------------------------------------------------------------

  @Test
  fun xmp_MotionPhotoNelleDueScrittureEConPrefissiDiversi() {
    val attributes = XmpFacts.parse(XmpPackets.googleMotionAttributes(1234, 99))
    assertThat(attributes.motionPhotoFlag).isTrue()
    assertThat(attributes.motionItem?.length).isEqualTo(1234L)
    assertThat(attributes.presentationTimestampUs).isEqualTo(99L)
    assertThat(attributes.items.map { it.semantic }).containsExactly("Primary", "MotionPhoto").inOrder()
    val elements = XmpFacts.parse(XmpPackets.googleMotionElements(777))
    assertThat(elements.motionPhotoFlag).isTrue()
    assertThat(elements.motionItem?.length).isEqualTo(777L)
    assertThat(elements.items.map { it.mime }).containsExactly("image/jpeg", "video/mp4").inOrder()
    val micro = XmpFacts.parse(XmpPackets.microVideo(555, 0))
    assertThat(micro.microVideoFlag).isTrue()
    assertThat(micro.microVideoOffset).isEqualTo(555L)
    // Un prefisso qualunque.
    val odd = XmpFacts.parse("<rdf:Description xmlns:Foo=\"http://ns.google.com/photos/1.0/camera/\" Foo:MotionPhoto=\"1\" Foo:MicroVideoOffset='42'/>")
    assertThat(odd.motionPhotoFlag).isTrue()
    assertThat(odd.microVideoOffset).isEqualTo(42L)
    // MotionPhoto = 0 non e' una motion photo.
    assertThat(XmpFacts.parse("<x Camera:MotionPhoto=\"0\"/>").declaresMotion).isFalse()
    // Nel valore di un altro attributo non si confonde.
    assertThat(XmpFacts.parse("<x Item:Semantic=\"MotionPhoto\"/>").motionPhotoFlag).isFalse()
  }

  @Test
  fun xmp_PanoramaSferaEUltraHdr() {
    val pano = XmpFacts.parse(XmpPackets.panorama())
    assertThat(pano.hasGPano).isTrue()
    assertThat(pano.isFullSphere(300, 100)).isFalse()
    val sphere = XmpFacts.parse(XmpPackets.sphere(4096, 2048))
    assertThat(sphere.isFullSphere(4096, 2048)).isTrue()
    // Senza i dati di ritaglio decide il rapporto: 2:1 e' una sfera, 3:1 no.
    val bare = XmpFacts.parse("<x GPano:ProjectionType=\"equirectangular\"/>")
    assertThat(bare.isFullSphere(4000, 2000)).isTrue()
    assertThat(bare.isFullSphere(2000, 4000)).isTrue()
    assertThat(bare.isFullSphere(6000, 2000)).isFalse()
    assertThat(bare.isFullSphere(null, null)).isFalse()
    val partial = XmpFacts.parse(XmpPackets.partialEquirectangular(4096, 2048, 1024))
    assertThat(partial.isFullSphere(4096, 1024)).isFalse()
    assertThat(partial.hasGPano).isTrue()
    val hdr = XmpFacts.parse(XmpPackets.ultraHdr())
    assertThat(hdr.gainMap).isTrue()
    assertThat(XmpFacts.parse("<x hdrgm:Version=\"1.0\"/>").gainMap).isTrue()
    assertThat(XmpFacts.parse("<Container:Item Item:Semantic=\"GainMap\"/>").gainMap).isTrue()
    assertThat(XmpFacts.parse(XmpPackets.motionFlagOnly()).gainMap).isFalse()
  }

  @Test
  fun xmp_TestoOstileONulloNonLancia() {
    assertThat(XmpFacts.parse(null).declaresMotion).isFalse()
    assertThat(XmpFacts.parse("").declaresMotion).isFalse()
    assertThat(XmpFacts.parse("<<<<>>>>\u0000￿".repeat(1000)).declaresMotion).isFalse()
    val long = "<Container:Item " + "Item:Length=\"1\" ".repeat(50_000)
    XmpFacts.parse(long)
    XmpFacts.parse("<rdf:li " + "a".repeat(100_000))
  }

  // ---- riconoscimento ---------------------------------------------------------------------------------------------------

  @Test
  fun ilRiconoscimentoVieneDaiByte() {
    fun sniff(bytes: ByteArray, mime: String? = null) = Sniffer.sniff(bytes.copyOf(64), mime)
    assertThat(sniff(ImageFixtures.jpeg(8, 8, 0)).type).isEqualTo(ContainerType.JPEG)
    assertThat(sniff(ImageFixtures.pngBytes(ImageFixtures.solid(8, 8, 0))).type).isEqualTo(ContainerType.PNG)
    assertThat(sniff(ImageFixtures.gif(8, 8, 0)).type).isEqualTo(ContainerType.GIF)
    assertThat(sniff(ImageFixtures.bmp(8, 8, 0)).type).isEqualTo(ContainerType.BMP)
    assertThat(sniff(ImageFixtures.webp(8, 8, -1, null, null)).type).isEqualTo(ContainerType.WEBP)
    assertThat(sniff(TiffFixtures.dng(TiffFixtures.DngSpec())).type).isEqualTo(ContainerType.TIFF)
    assertThat(sniff(IsoFixtures.heic(IsoFixtures.HeicSpec())).mime).isEqualTo("image/heic")
    assertThat(sniff(IsoFixtures.heic(IsoFixtures.HeicSpec().apply { majorBrand = "avif" })).mime).isEqualTo("image/avif")
    assertThat(sniff(IsoFixtures.mp4(IsoFixtures.Mp4Spec())).type).isEqualTo(ContainerType.MP4)
    assertThat(sniff(IsoFixtures.mp4(IsoFixtures.Mp4Spec().apply { brand = "qt  " })).mime).isEqualTo("video/quicktime")
    assertThat(sniff(IsoFixtures.mp4(IsoFixtures.Mp4Spec().apply { brand = "3gp4" })).mime).isEqualTo("video/3gpp")
    assertThat(sniff(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0)).type).isEqualTo(ContainerType.MATROSKA)
    assertThat(sniff(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0), "video/webm").mime).isEqualTo("video/webm")
    assertThat(sniff("RIFF\u0000\u0000\u0000\u0000AVI LIST".toByteArray(Charsets.ISO_8859_1)).type).isEqualTo(ContainerType.AVI)
    // Non sono media.
    for (text in listOf("<html>", "BM", "BM is a text file that begins with BM and goes on for a while", "GIF", "", "%PDF-1.4 ...")) {
      assertThat(sniff(text.toByteArray()).type).isEqualTo(ContainerType.UNKNOWN)
    }
  }

  // ---- HEIC: irot e imir (E-M15) ----------------------------------------------------------------------------------------

  /** Applica a una griglia di etichette la sequenza irot (quarti di giro antiorari) e imir, come dice la specifica HEIF. */
  private fun heifTransform(src: Array<IntArray>, irot: Int?, imirAxis: Int?): Array<IntArray> {
    var m = src
    if (irot != null) repeat(irot) { m = rotateCounterClockwise(m) }
    if (imirAxis == 0) m = Array(m.size) { y -> m[y].reversedArray() } // asse verticale: sinistra <-> destra
    if (imirAxis == 1) m = Array(m.size) { y -> m[m.size - 1 - y].copyOf() } // asse orizzontale: alto <-> basso
    return m
  }

  private fun rotateCounterClockwise(m: Array<IntArray>): Array<IntArray> {
    val h = m.size
    val w = m[0].size
    return Array(w) { y -> IntArray(h) { x -> m[x][w - 1 - y] } }
  }

  @Test
  fun heicOrientamentoDaIrotEImirCoincideConGliOttoValoriExif() = runTest {
    val w = 3
    val h = 2
    val labels = Array(h) { y -> IntArray(w) { x -> y * w + x } }
    val upright = Img.create(w, h)
    for (y in 0 until h) for (x in 0 until w) upright.setRgb(x, y, labels[y][x])
    // Per ogni valore EXIF 1..8 i pixel che si vedono (secondo la definizione EXIF).
    val byExif = (1..8).associateWith { o ->
      val shown = ImageFixtures.displayed(upright, o)
      Array(shown.height()) { y -> IntArray(shown.width()) { x -> shown.rgb(x, y) } }
    }
    for (irot in listOf(null, 0, 1, 2, 3)) {
      for (imir in listOf(null, 0, 1)) {
        val expected = heifTransform(labels, irot, imir)
        val orientation = byExif.entries.first { (_, shown) -> shown.contentDeepEquals(expected) }.key
        val heic = IsoFixtures.heic(IsoFixtures.HeicSpec().apply { this.irot = irot; imirAxis = imir; width = w; height = h })
        val scan = IsoBmff.scanHeif(ArrayAccess(heic))
        val actual = scan.heifOrientation ?: ExifOrientation.NORMAL
        assertThat(actual.exif).isEqualTo(orientation)
        assertThat(scan.width).isEqualTo(w)
        assertThat(scan.height).isEqualTo(h)
      }
    }
  }

  @Test
  fun heicLeggeExifEXmpDalleVociDelMeta() = runTest {
    val heic = IsoFixtures.heic(
      IsoFixtures.HeicSpec().apply {
        exif = spec { dateOriginal = "2024:03:12 10:15:30"; model = "SM-S931B" }
        xmp = XmpPackets.panorama()
        width = 4000; height = 3000
      },
    )
    val scan = IsoBmff.scanHeif(ArrayAccess(heic))
    assertThat(scan.truncated).isFalse()
    assertThat(scan.width).isEqualTo(4000)
    assertThat(scan.height).isEqualTo(3000)
    assertThat(XmpFacts.parse(scan.xmp).hasGPano).isTrue()
    val f = ExifFacts.read(TiffParser.parse(ArrayAccess(scan.exifTiff!!))!!)
    assertThat(f.model).isEqualTo("SM-S931B")
    assertThat(f.local).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 15, 30))
  }

  @Test
  fun heicSenzaMetaOTroncatoNonLancia() = runTest {
    val heic = IsoFixtures.heic(IsoFixtures.HeicSpec().apply { exif = spec { dateOriginal = "2024:03:12 10:15:30" } })
    for (n in 0..heic.size) {
      IsoBmff.scanHeif(ArrayAccess(heic.copyOf(n)))
    }
    for (i in heic.indices) {
      val broken = heic.copyOf()
      broken[i] = (broken[i].toInt() xor 0x5A).toByte()
      IsoBmff.scanHeif(ArrayAccess(broken))
    }
  }

  // ---- JPEG, PNG, WebP ---------------------------------------------------------------------------------------------------

  @Test
  fun jpegLeggeExifXmpEDimensioniEIgnoraIlResto() = runTest {
    val file = ImageFixtures.jpeg(80, 60, 0x336699, spec { model = "X"; dateOriginal = "2024:03:12 10:15:30" }, XmpPackets.panorama())
    val scan = JpegScanner.scan(ArrayAccess(file))
    assertThat(scan.width).isEqualTo(80)
    assertThat(scan.height).isEqualTo(60)
    assertThat(scan.exifTiff).isNotNull()
    assertThat(XmpFacts.parse(scan.xmp).hasGPano).isTrue()
    assertThat(scan.truncated).isFalse()
    assertThat(scan.jpegEndFound).isTrue()
  }

  @Test
  fun jpegConByteDiRiempimentoESegmentiIntermedi() = runTest {
    val base = ImageFixtures.jpeg(40, 30, 0x336699)
    // SOI, riempimento FF FF, un APP13 inventato, poi il resto.
    val file = Bytes().raw(Bytes.prefix(base, 2)).u8(0xFF).u8(0xFF).u8(0xFF).u8(0xED).u16be(6).zeros(4)
      .raw(base.copyOfRange(2, base.size)).toBytes()
    val scan = JpegScanner.scan(ArrayAccess(file))
    assertThat(scan.width).isEqualTo(40)
    assertThat(scan.truncated).isFalse()
  }

  @Test
  fun jpegTroncatoOSenzaSofELaStessaCosaCheLoDicono_Gli_Scanner() = runTest {
    val file = ImageFixtures.jpeg(ImageFixtures.noise(120, 90, 3), spec { dateOriginal = "2024:03:12 10:15:30" }, null)
    // Tagliato fra i dati dell'immagine: le intestazioni ci sono tutte, manca la fine (EOI).
    val half = JpegScanner.scan(ArrayAccess(file.copyOf(file.size / 2)))
    assertThat(half.truncated).isFalse()
    assertThat(half.jpegEndFound).isFalse()
    assertThat(half.trailerEvidence).isFalse()
    // Tagliato dentro le intestazioni.
    assertThat(JpegScanner.scan(ArrayAccess(file.copyOf(30))).truncated).isTrue()
    val noSof = Bytes().u8(0xFF).u8(0xD8).u8(0xFF).u8(0xDA).u16be(4).zeros(2).zeros(8).toBytes()
    assertThat(JpegScanner.scan(ArrayAccess(noSof)).malformed).isTrue()
  }

  @Test
  fun unJpegConDatiAggiuntiDopoLEoiNonEUnJpegTagliato() = runTest {
    val extractor = testExtractor()
    val jpeg = ImageFixtures.jpeg(ImageFixtures.noise(100, 80, 11), spec { dateOriginal = "2024:03:12 10:15:30" }, null)
    val random = java.util.Random(3)
    val video = ByteArray(200_000).also { random.nextBytes(it) } // un "video" senza ftyp ne' SEFT
    val withVideo = jpeg + video
    val h = ExtractHints("image/jpeg", "a.jpg", "DCIM/Camera/", null, null, null, null, null, ROME)
    assertThat(extractor.extract(ByteArrayMediaBytes(withVideo), h)).isInstanceOf(Extraction.Read::class.java)
    // Riempimento di zeri oltre i 64 KiB dopo l'EOI.
    assertThat(extractor.extract(ByteArrayMediaBytes(jpeg + ByteArray(150_000)), h)).isInstanceOf(Extraction.Read::class.java)
    // Il vero taglio in mezzo ai dati compressi resta un taglio.
    val cut = extractor.extract(ByteArrayMediaBytes(jpeg.copyOf(jpeg.size * 2 / 3)), h)
    assertThat(cut).isEqualTo(Extraction.Corrupt(ExtractCode.TRUNCATED))
  }

  @Test
  fun iDatiCompressiDiUnJpegHannoSoloFfSeguitiDaZero() {
    assertThat(JpegScanner.looksLikeCompressedData(byteArrayOf(1, 2, 0xFF.toByte(), 0, 3, 0xFF.toByte(), 0xD3.toByte(), 9))).isTrue()
    assertThat(JpegScanner.looksLikeCompressedData(byteArrayOf(1, 0xFF.toByte(), 0xC4.toByte(), 3))).isFalse()
    assertThat(JpegScanner.looksLikeCompressedData(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0, 5))).isTrue()
    assertThat(JpegScanner.looksLikeCompressedData(ByteArray(1000))).isFalse() // zeri = riempimento
    assertThat(JpegScanner.looksLikeCompressedData(ByteArray(0))).isTrue()
  }

  @Test
  fun pngEWebpPortanoExifEXmp() = runTest {
    val s = spec { dateOriginal = "2024:03:12 10:15:30"; model = "M" }
    val png = ImageFixtures.png(ImageFixtures.solid(30, 20, 0x112233), s, XmpPackets.panorama())
    val pngScan = PngScanner.scan(ArrayAccess(png))
    assertThat(pngScan.width).isEqualTo(30)
    assertThat(pngScan.height).isEqualTo(20)
    assertThat(pngScan.exifTiff).isNotNull()
    assertThat(XmpFacts.parse(pngScan.xmp).hasGPano).isTrue()
    assertThat(pngScan.truncated).isFalse()

    val webp = ImageFixtures.webp(30, 20, -0xEEDDCD, s, XmpPackets.sphere(60, 30))
    val webpScan = WebpScanner.scan(ArrayAccess(webp))
    assertThat(webpScan.width).isEqualTo(30)
    assertThat(webpScan.height).isEqualTo(20)
    assertThat(webpScan.exifTiff).isNotNull()
    assertThat(XmpFacts.parse(webpScan.xmp).projectionType).isEqualTo("equirectangular")
    assertThat(webpScan.animated).isFalse()
    assertThat(WebpScanner.scan(ArrayAccess(ImageFixtures.webpAnimated(30, 20, intArrayOf(-0x1000000, -1), 100))).animated).isTrue()
    // Un WebP lossy/lossless semplice senza VP8X: le dimensioni vengono dal flusso.
    val plain = WebpScanner.scan(ArrayAccess(ImageFixtures.webp(30, 20, -0x1000000, null, null)))
    assertThat(plain.width).isEqualTo(30)
    assertThat(plain.height).isEqualTo(20)
  }

  @Test
  fun laLetturaAPagineFaPocheLetture() = runTest {
    val data = ByteArray(1_000_000) { (it % 251).toByte() }
    val source = ByteArrayMediaBytes(data)
    val ra = PagedAccess(source)
    // Dieci letture piccole nella stessa pagina: una sola lettura del file.
    repeat(10) { assertThat(ra.read(100L + it * 50, 20)).isEqualTo(data.copyOfRange(100 + it * 50, 120 + it * 50)) }
    assertThat(source.reads).isEqualTo(1)
    assertThat(source.bytesRead).isEqualTo(64L * 1024)
    // A cavallo di due pagine: una lettura per la pagina nuova.
    assertThat(ra.read(65_530, 20)).isEqualTo(data.copyOfRange(65_530, 65_550))
    assertThat(source.reads).isEqualTo(2)
    // Oltre la fine e fuori dal file.
    assertThat(ra.read(999_990, 100)).isEqualTo(data.copyOfRange(999_990, 1_000_000))
    assertThat(ra.read(1_000_000, 10)).isEmpty()
    assertThat(ra.read(-5, 10)).isEmpty()
    assertThat(ra.read(5, 0)).isEmpty()
    // Una lettura grande va diretta.
    val big = ra.read(10, 500_000)
    assertThat(big).isEqualTo(data.copyOfRange(10, 500_010))
    // Un file piu' corto di una pagina.
    val tiny = PagedAccess(ByteArrayMediaBytes(byteArrayOf(1, 2, 3)))
    assertThat(tiny.read(0, 64)).isEqualTo(byteArrayOf(1, 2, 3))
    assertThat(tiny.tail(2)).isEqualTo(byteArrayOf(2, 3))
  }

  private suspend fun PagedAccess.tail(n: Int): ByteArray = read(size - n, n)
}
