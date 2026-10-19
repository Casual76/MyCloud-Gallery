package dev.pampa.mycloudgallery.core.metadata.strip

import android.media.MediaMetadataRetriever
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.fixtures.Bytes
import dev.pampa.mycloudgallery.fixtures.IsoFixtures
import java.io.File
import java.io.RandomAccessFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaMetadataRetriever

/**
 * Fase 5B, P11 (D26, E-X5): i riquadri della posizione, camminati sui soli header. Qui i casi limite e ostili (file che non tornano, riquadri
 * annidati o in numero smisurato, intestazioni a 64 bit, code che non sono riquadri), piu' il lettore di sistema (`MediaMetadataRetriever`).
 */
@RunWith(RobolectricTestRunner::class)
class IsoLocationTest {

  @get:Rule val folder = TemporaryFolder()

  private fun plan(bytes: ByteArray): IsoLocation.Plan = RandomAccessFile(folder.newFile().apply { writeBytes(bytes) }, "r").use {
    val file = RawFile(it)
    IsoLocation.plan(file, 0, file.length)
  }

  private fun box(type: String, vararg children: ByteArray): ByteArray = IsoFixtures.box(type, *children)

  private val ftyp = box("ftyp", Bytes.ascii4("isom"), Bytes().u32be(0x200).toBytes(), Bytes.ascii4("isom"))

  private fun xyz(text: String = StripFixtures.ISO6709): ByteArray {
    val body = text.toByteArray(Charsets.ISO_8859_1)
    return Bytes().u32be(8L + 4 + body.size).u8(0xA9).ascii("xyz").u16be(body.size).u16be(0x15C7).raw(body).toBytes()
  }

  @Test
  fun unVideoCon_xyz_haUnPianoELoStessoVideoPulitoNonHaPiuNiente() {
    val dirty = Bytes.concat(ftyp, box("moov", box("udta", xyz())), box("mdat", ByteArray(64)))

    val plan = plan(dirty)

    assertThat(plan.ops).hasSize(1)
    assertThat(plan.blockers).isEmpty()
    assertThat(plan.isClean).isFalse()
    assertThat(plan(Bytes.concat(ftyp, box("moov", box("udta", box("free"))), box("mdat", ByteArray(64)))).isClean).isTrue()
  }

  @Test
  fun leScatoleDiPosizioneInOgniPostoDoveStannoVengonoTrovate() {
    // ©xyz anche fuori da udta (in cima, o dentro la traccia), loci, gps e la voce di un meta in stile ISO con versione.
    val isoMeta = IsoFixtures.fullBox("meta", 0, 0, box("loci", ByteArray(20)))
    val trak = box("trak", box("udta", xyz()))
    val dirty = Bytes.concat(ftyp, xyz(), box("moov", trak, box("udta", box("loci", ByteArray(8)), box("gps ", ByteArray(8)), isoMeta)), box("mdat", ByteArray(16)))

    val plan = plan(dirty)

    assertThat(plan.ops.count { it is IsoLocation.Op.Neutralize }).isEqualTo(5)
  }

  @Test
  fun intestazioneA64BitEBoxCheUsciDalFileNonFannoDanniEUnaCodaNonEUnErrore() {
    val big = Bytes().u32be(1).ascii("free").u64be(24).zeros(8).toBytes()
    val dirty = Bytes.concat(ftyp, big, box("moov", box("udta", xyz())))
    assertThat(plan(dirty).ops).hasSize(1)

    // Un riquadro che dichiara piu' byte di quanti ne restano: la camminata si ferma, niente eccezioni e niente patch fuori dal file.
    val truncated = Bytes.concat(ftyp, Bytes().u32be(10_000).ascii("moov").zeros(20).toBytes())
    assertThat(plan(truncated).blockers).isEmpty()

    // Una coda che non e' un riquadro (il trailer di Samsung, riempimento) non e' un errore...
    val withTrailer = Bytes.concat(ftyp, box("moov", box("udta", box("free"))), "SEFHxxxxxxxxxxxxxxxxxSEFT".toByteArray())
    assertThat(plan(withTrailer).isClean).isTrue()
    // ...ma non nasconde un XMP con le coordinate.
    val hiding = Bytes.concat(ftyp, box("moov"), "<x exif:GPSLatitude=\"45,27N\"/>".padEnd(64).toByteArray())
    assertThat(plan(hiding).blockers).contains(IsoLocation.Blocker.XMP_UNSUPPORTED)
  }

  @Test
  fun annidamentoSmisuratoOTroppiRiquadriFermanoLaCondivisioneInveceDiSbagliare() {
    var nested = box("udta", xyz())
    repeat(12) { nested = box("udta", nested) }
    assertThat(plan(Bytes.concat(ftyp, box("moov", nested))).blockers).contains(IsoLocation.Blocker.TOO_COMPLEX)

    val many = Bytes()
    repeat(5_000) { many.raw(box("free")) }
    assertThat(plan(Bytes.concat(ftyp, box("moov", many.toBytes()))).blockers).contains(IsoLocation.Blocker.TOO_COMPLEX)
  }

  @Test
  fun lePatchNonCambianoLaLunghezzaEFunzionanoAncheSuUnaRegioneIncorporata() {
    val video = Bytes.concat(ftyp, box("moov", box("udta", xyz())), box("mdat", ByteArray(32)))
    val prefix = ByteArray(100) { 7 }
    val file = folder.newFile().apply { writeBytes(prefix + video) }
    RandomAccessFile(file, "rw").use {
      val raw = RawFile(it)
      val plan = IsoLocation.plan(raw, 100, raw.length)
      assertThat(plan.ops).hasSize(1)
      IsoLocation.apply(raw, plan)
      assertThat(raw.length).isEqualTo(100L + video.size)
      assertThat(IsoLocation.plan(raw, 100, raw.length).isClean).isTrue()
    }
    assertThat(file.readBytes().copyOf(100)).isEqualTo(prefix) // fuori dalla regione non si tocca niente
  }

  // --- Il lettore di sistema --------------------------------------------------------------------------------------------------------

  private fun retriever(file: File, duration: String?, location: String?): LocationVerdict {
    if (duration != null) ShadowMediaMetadataRetriever.addMetadata(file.path, MediaMetadataRetriever.METADATA_KEY_DURATION, duration)
    if (location != null) ShadowMediaMetadataRetriever.addMetadata(file.path, MediaMetadataRetriever.METADATA_KEY_LOCATION, location)
    return MediaMetadataLocationRecheck().check(file, "video/mp4")
  }

  @Test
  fun mediaMetadataRetrieverUnaPosizioneDichiarataEUnResto_UnVideoCheNonSiApreNonAssolve() {
    assertThat(retriever(folder.newFile(), "3000", "+45.4642+009.1900/")).isEqualTo(LocationVerdict.HAS_LOCATION)
    // Anche una stringa che non si interpreta e' un resto: non si legge, si rifiuta.
    assertThat(retriever(folder.newFile(), "3000", "???")).isEqualTo(LocationVerdict.HAS_LOCATION)
    assertThat(retriever(folder.newFile(), "3000", null)).isEqualTo(LocationVerdict.CLEAR)
    assertThat(retriever(folder.newFile(), null, null)).isEqualTo(LocationVerdict.UNREADABLE)
    assertThat(MediaMetadataLocationRecheck().appliesTo("video/mp4")).isTrue()
    assertThat(MediaMetadataLocationRecheck().appliesTo("image/jpeg")).isFalse()
    assertThat(ExifInterfaceLocationRecheck().appliesTo("video/mp4")).isFalse()
  }
}
