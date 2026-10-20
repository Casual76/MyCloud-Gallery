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
 * Correzioni 5B (R1): la ricostruzione di un video per elenco di cio' che si tiene ([IsoClean]), sui casi limite e ostili: `moov` prima del `mdat`
 * (gli indirizzi non devono cambiare), piu' campioni per chunk e `stz2`, intestazioni a 64 bit, code che non sono riquadri, file frammentati,
 * tabelle che puntano fuori da `mdat`, troppi riquadri, una regione incorporata (la motion photo). Piu' il lettore di sistema
 * (`MediaMetadataRetriever`).
 */
@RunWith(RobolectricTestRunner::class)
class IsoCleanTest {

  @get:Rule val folder = TemporaryFolder()

  private fun clean(bytes: ByteArray, from: Long = 0): Pair<IsoStop?, ByteArray> {
    val file = folder.newFile().apply { writeBytes(bytes) }
    val stop = RandomAccessFile(file, "rw").use { raf -> RawFile(raf).let { IsoClean.clean(it, from, it.length) } }
    return stop to file.readBytes()
  }

  private fun box(type: String, vararg children: ByteArray): ByteArray = IsoFixtures.box(type, *children)

  private val djmd = LocationFixtures.Track("meta", listOf("djmd"), (0 until 3).map { LocationFixtures.BINARY_POSITION + byteArrayOf(it.toByte()) })

  @Test
  fun conIlMoovPrimaDelMdatNienteSiSpostaEIMetadatiSparisconoComunque() {
    val built = LocationFixtures.mp4(listOf(LocationFixtures.video(), LocationFixtures.audio(), djmd), moovFirst = true)

    val (stop, out) = clean(built.bytes)

    assertThat(stop).isNull()
    assertThat(out.size).isEqualTo(built.bytes.size)
    assertThat(LocationFixtures.handlers(out)).containsExactly("vide", "soun").inOrder()
    for (k in built.samples[0].indices) assertThat(out.copyOfRange(built.samples[0][k].first, built.samples[0][k].last + 1)).isEqualTo(built.sample(0, k))
    assertThat(StripFixtures.indexOf(out, LocationFixtures.BINARY_POSITION)).isEqualTo(-1)
    assertThat(LocationTraces.scan(folder.newFile().apply { writeBytes(out) }).clean).isTrue()
  }

  @Test
  fun piuCampioniPerChunkEDimensioniInStz2() {
    val video = LocationFixtures.Track("vide", listOf("avc1"), LocationFixtures.videoSamples(5), ByteArray(70), perChunk = 2, compactSizes = true)
    val audio = LocationFixtures.Track("soun", listOf("mp4a"), LocationFixtures.audioSamples(4), ByteArray(20), perChunk = 3)
    val built = LocationFixtures.mp4(listOf(video, audio, djmd))

    val (stop, out) = clean(built.bytes)

    assertThat(stop).isNull()
    for (t in 0..1) {
      for (k in built.samples[t].indices) assertThat(out.copyOfRange(built.samples[t][k].first, built.samples[t][k].last + 1)).isEqualTo(built.sample(t, k))
    }
    assertThat(StripFixtures.indexOf(out, LocationFixtures.BINARY_POSITION)).isEqualTo(-1)
  }

  @Test
  fun unVideoPulitoNonCambiaUnByte() {
    val built = StripFixtures.mp4Clean()

    val (stop, out) = clean(built.bytes)

    assertThat(stop).isNull()
    assertThat(out).isEqualTo(built.bytes)
  }

  @Test
  fun intestazioneA64BitRiempimentoECodaSiAzzerano() {
    // Un `free` con l'intestazione a 64 bit e dentro una posizione (come le dashcam Novatek), poi in coda un trailer che non e' un riquadro.
    val novatek = Bytes().u32be(1).ascii("free").u64be(16L + 8 + StripFixtures.ISO6709.length).ascii("freeGPS ").raw(StripFixtures.ISO6709.toByteArray()).toBytes()
    val built = LocationFixtures.mp4(listOf(LocationFixtures.video()), topExtra = listOf(novatek, "SEFHxxxx+45.4642+009.1900/SEFT".toByteArray()))

    val (stop, out) = clean(built.bytes)

    assertThat(stop).isNull()
    assertThat(out.size).isEqualTo(built.bytes.size)
    assertThat(StripFixtures.contains(out, "freeGPS")).isFalse()
    assertThat(StripFixtures.contains(out, "+45.4642")).isFalse()
    assertThat(StripFixtures.contains(out, "SEFH")).isFalse()
  }

  @Test
  fun iRiquadriSconosciutiDiventanoFreeVuoti() {
    val udta = box("udta", StripFixtures.xyz(), box("smta", "samsung".toByteArray()))
    val uuid = box("uuid", ByteArray(16) { 7 }, "qualcosa di Sony".toByteArray())
    val built = LocationFixtures.mp4(listOf(LocationFixtures.video(), LocationFixtures.audio()), moovExtra = listOf(udta), topExtra = listOf(uuid, box("meta", ByteArray(12))))

    val (stop, out) = clean(built.bytes)

    assertThat(stop).isNull()
    for (gone in listOf("udta", "smta", "samsung", "uuid", "Sony", "meta")) assertThat(StripFixtures.contains(out, gone)).isFalse()
    assertThat(LocationFixtures.handlers(out)).containsExactly("vide", "soun").inOrder()
  }

  @Test
  fun senzaVideoNeAudioSenzaMoovOConDueMoovNonSiRicostruisce() {
    assertThat(clean(StripFixtures.mp4WithTimedTrack("camm")).first).isEqualTo(IsoStop.NO_AV_TRACK)
    assertThat(clean(IsoFixtures.mp4(IsoFixtures.Mp4Spec().apply { includeMoov = false })).first).isEqualTo(IsoStop.NO_MOVIE)
    val one = LocationFixtures.mp4(listOf(LocationFixtures.video())).bytes
    val moov = LocationFixtures.childBox(one, 0, one.size, "moov")!!
    val twice = one + one.copyOfRange(moov.first - 8, moov.last + 1)
    assertThat(clean(twice).first).isEqualTo(IsoStop.NO_MOVIE)
  }

  @Test
  fun unFileFrammentatoNonSiRicostruisce() {
    val withMoof = LocationFixtures.mp4(listOf(LocationFixtures.video()), topExtra = listOf(box("moof", box("mfhd", ByteArray(8))))).bytes
    assertThat(clean(withMoof).first).isEqualTo(IsoStop.FRAGMENTED)
    val withMvex = LocationFixtures.mp4(listOf(LocationFixtures.video()), moovExtra = listOf(box("mvex", box("trex", ByteArray(24))))).bytes
    assertThat(clean(withMvex).first).isEqualTo(IsoStop.FRAGMENTED)
  }

  @Test
  fun campioniFuoriDalMdatOTabelleRotteFermanoSenzaToccareIlFile() {
    val built = LocationFixtures.mp4(listOf(LocationFixtures.video()))
    // L'indirizzo del primo chunk punta dentro `ftyp`: un file costruito apposta per far tenere dei byte che non sono campioni.
    val bytes = built.bytes.copyOf()
    val stco = StripFixtures.indexOf(bytes, "stco".toByteArray())
    bytes[stco + 4 + 4 + 4 + 3] = 4
    bytes[stco + 4 + 4 + 4 + 2] = 0

    val (stop, out) = clean(bytes)

    assertThat(stop).isEqualTo(IsoStop.BROKEN)
    assertThat(out).isEqualTo(bytes) // si decide tutto prima di scrivere
    // Un figlio che esce dal suo contenitore.
    val broken = built.bytes.copyOf()
    val stsz = StripFixtures.indexOf(broken, "stsz".toByteArray())
    broken[stsz - 2] = 0x7F
    assertThat(clean(broken).first).isEqualTo(IsoStop.BROKEN)
  }

  @Test
  fun troppiRiquadriFermano() {
    val many = Bytes()
    repeat(100_001) { many.raw(box("free")) }
    val built = LocationFixtures.mp4(listOf(LocationFixtures.video()), moovExtra = listOf(box("udta", many.toBytes())))
    // `udta` non si apre: i suoi figli non contano. Messi in cima al file invece si.
    assertThat(clean(built.bytes).first).isNull()
    val top = LocationFixtures.mp4(listOf(LocationFixtures.video()), topExtra = listOf(many.toBytes())).bytes
    assertThat(clean(top).first).isEqualTo(IsoStop.TOO_COMPLEX)
  }

  @Test
  fun unaRegioneIncorporataNonToccaNienteFuori() {
    val built = LocationFixtures.mp4(listOf(LocationFixtures.video(), djmd))
    val prefix = ByteArray(100) { 7 }

    val (stop, out) = clean(prefix + built.bytes, from = 100)

    assertThat(stop).isNull()
    assertThat(out.copyOf(100)).isEqualTo(prefix)
    assertThat(LocationFixtures.handlers(out, 100)).containsExactly("vide")
    for (k in built.samples[0].indices) assertThat(out.copyOfRange(100 + built.samples[0][k].first, 100 + built.samples[0][k].last + 1)).isEqualTo(built.sample(0, k))
    assertThat(StripFixtures.indexOf(out, LocationFixtures.BINARY_POSITION)).isEqualTo(-1)
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
