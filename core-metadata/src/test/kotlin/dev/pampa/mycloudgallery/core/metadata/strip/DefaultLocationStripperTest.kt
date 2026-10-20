package dev.pampa.mycloudgallery.core.metadata.strip

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.StripUnsupported
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.strip.StripFixtures.ISO6709
import dev.pampa.mycloudgallery.core.metadata.strip.StripFixtures.ROME
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever

/**
 * Fase 5B, P11 (D26, E-X5, L41, §6.1), con le correzioni 5B (R1, R2): la posizione tolta da una copia e **ricontrollata**. Per ogni formato (JPEG,
 * PNG, WebP, MP4, MOV, HEIC delle fixture, piu' la motion photo): la copia non ha piu' nessuna coordinata (EXIF, XMP, scatole del video), il
 * rilevatore indipendente non ci trova niente, l'estrattore dell'app la rilegge senza luogo, `ExifInterface` non trova etichette GPS, la copia si
 * decodifica (foto) o ha ancora il suo `moov` con video e audio (video); restano **solo** orientamento e data (fotocamera, XMP e il resto no: si
 * tiene per elenco), e il sorgente e' intatto byte per byte. I posti nascosti (DJI, `mebx`, XMP esteso, APP13...) sono in [PosizioneNascostaTest].
 *
 * Il lato duro e' **fallire chiusi**: se togliere la posizione non riesce, la destinazione non esiste e il risultato non e' [StripOutcome.Stripped].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DefaultLocationStripperTest {

  @get:Rule val folder = TemporaryFolder()

  private val stripper = DefaultLocationStripper(StripFixtures.rechecks(), heic = FakeHeicToJpeg())

  private fun src(name: String, bytes: ByteArray): File = StripFixtures.write(folder.root, name, bytes)

  private fun dst(name: String): File = File(folder.root, name)

  /**
   * Il video si apre e dura 3 secondi, per `MediaMetadataRetriever` (su JVM c'e' il suo shadow, che non legge il file: gli si dice cosa
   * risponderebbe il sistema). Niente `METADATA_KEY_LOCATION`: la prova che la posizione non c'e' piu' la danno gli altri due lettori, che
   * leggono i byte davvero; qui si prova che il lettore del sistema, interrogato, non ne trova.
   */
  private fun playable(file: File): File {
    ShadowMediaMetadataRetriever.addMetadata(file.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "3000")
    return file
  }

  private fun sha(file: File): List<Byte> = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

  private fun read(file: File, mime: String): Extraction =
    runBlocking { StripFixtures.extractor().extract(ByteArrayMediaBytes(file.readBytes()), ExtractHints(mime, "x", "", ROME)) }

  /** Il quadro completo di una foto ripulita. */
  private fun assertCleanPhoto(source: File, copy: File, mime: String) {
    val read = read(copy, mime) as Extraction.Read
    assertThat(read.metadata.location).isNull()
    val exif = ExifInterface(copy)
    assertThat(exif.latLong).isNull()
    for (tag in listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP)) {
      assertWithMessage(tag).that(exif.getAttribute(tag)).isNull()
    }
    // Nessun byte di XMP con GPS: ne' "GPSLatitude" ne' le coordinate scritte come testo.
    val bytes = copy.readBytes()
    assertThat(StripFixtures.contains(bytes, "GPSLatitude")).isFalse()
    assertThat(StripFixtures.contains(bytes, "45,27.852N")).isFalse()
    assertThat(StripFixtures.contains(bytes, "GPSProcessingMethod")).isFalse()
    // Ancora una foto: si decodifica.
    assertThat(AndroidPreviewDecoder().decode(bytes, 64)).isNotNull()
    // Restano orientamento e data (con fuso e millisecondi); la fotocamera no: si tiene solo cio' che e' in elenco.
    assertThat(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)).isEqualTo(6)
    assertThat(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).isEqualTo("2024:03:12 10:15:30")
    assertThat(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)).isEqualTo("+01:00")
    assertThat(exif.getAttribute(ExifInterface.TAG_MODEL)).isNull()
    assertThat(exif.getAttribute(ExifInterface.TAG_MAKE)).isNull()
    assertThat(LocationTraces.scan(copy).clean).isTrue()
    assertThat(source.length()).isGreaterThan(0)
  }

  // --- JPEG, PNG, WebP -----------------------------------------------------------------------------------------------------------

  @Test
  fun jpegSenzaPosizioneNeEXifNeXmp() {
    val source = src("a.jpg", StripFixtures.jpeg())
    val before = sha(source)
    val out = dst("out.jpg")
    // Prima: il file ha davvero una posizione, in tutti e due i posti (altrimenti il test non proverebbe niente).
    assertThat(ExifInterface(source).latLong).isNotNull()
    assertThat(StripFixtures.contains(source.readBytes(), "GPSLatitude")).isTrue()
    assertThat((read(source, "image/jpeg") as Extraction.Read).metadata.location).isNotNull()

    val result = stripper.strip(source, out, "image/jpeg")

    assertThat(result).isEqualTo(StripOutcome.Stripped("image/jpeg"))
    assertCleanPhoto(source, out, "image/jpeg")
    assertThat(sha(source)).isEqualTo(before) // il sorgente non si tocca mai
    // L'XMP non si pulisce: non c'e' piu', intero (anche le proprieta' innocue: si tiene per elenco, e l'XMP non c'e').
    assertThat(StripFixtures.contains(out.readBytes(), "dc:creator")).isFalse()
    assertThat(StripFixtures.contains(out.readBytes(), "xmpmeta")).isFalse()
  }

  @Test
  fun pngSenzaPosizioneNeEXifNeXmpEIlCrcDelChunkTorna() {
    val source = src("a.png", StripFixtures.png())
    val out = dst("out.png")
    assertThat(ExifInterface(source).latLong).isNotNull()

    assertThat(stripper.strip(source, out, "image/png")).isEqualTo(StripOutcome.Stripped("image/png"))

    assertCleanPhoto(source, out, "image/png")
    // Dopo la patch sul posto, ogni chunk ha il CRC giusto: un lettore severo non rifiuta il file.
    assertThat(pngCrcErrors(out.readBytes())).isEmpty()
  }

  @Test
  fun webpSenzaPosizioneNeEXifNeXmp() {
    val source = src("a.webp", StripFixtures.webp())
    val out = dst("out.webp")
    assertThat(ExifInterface(source).latLong).isNotNull()

    assertThat(stripper.strip(source, out, "image/webp")).isEqualTo(StripOutcome.Stripped("image/webp"))

    assertCleanPhoto(source, out, "image/webp")
  }

  @Test
  fun ilTipoLoDicononoIByteNonIlNomeNeIlMimeDichiarato() {
    // Un JPEG dichiarato come PNG con il nome .png: conta il contenuto.
    val source = src("finto.png", StripFixtures.jpeg())
    val out = dst("out.bin")
    assertThat(stripper.strip(source, out, "image/png")).isEqualTo(StripOutcome.Stripped("image/jpeg"))
    assertCleanPhoto(source, out, "image/jpeg")
  }

  @Test
  fun unaFotoSenzaMetadatiNonVieneRiscrittaEResta_Identica() {
    val plain = ImageFixtures.jpeg(64, 48, 0x336699)
    val source = src("plain.jpg", plain)
    val out = dst("out.jpg")

    assertThat(stripper.strip(source, out, "image/jpeg")).isEqualTo(StripOutcome.Stripped("image/jpeg"))

    assertThat(out.readBytes()).isEqualTo(plain)
  }

  @Test
  fun unaFotoConSoloXmpGpsELExifPulitoVieneComunqueRipulita() {
    // Il caso che ExifInterface da solo lascerebbe passare: l'EXIF e' pulito, le coordinate stanno solo nell'XMP.
    val source = src("xmp-only.jpg", StripFixtures.jpeg(exif = StripFixtures.exifWithoutGps()))
    val out = dst("out.jpg")
    assertThat(ExifInterface(source).latLong).isNull()
    assertThat(LocationTraceRecheck().check(source, "image/jpeg")).isEqualTo(LocationVerdict.HAS_LOCATION)

    assertThat(stripper.strip(source, out, "image/jpeg")).isEqualTo(StripOutcome.Stripped("image/jpeg"))

    assertThat(LocationTraceRecheck().check(out, "image/jpeg")).isEqualTo(LocationVerdict.CLEAR)
    assertThat(StripFixtures.contains(out.readBytes(), "GPSLatitude")).isFalse()
  }

  // --- MP4, MOV ------------------------------------------------------------------------------------------------------------------

  @Test
  fun mp4SenzaPosizioneStessaDimensioneEScatolaRinominataInFree() {
    val original = StripFixtures.mp4WithLocation()
    val source = src("a.mp4", original)
    val out = playable(dst("out.mp4"))
    assertThat(StripFixtures.contains(original, ISO6709)).isTrue()
    assertThat((read(source, "video/mp4") as Extraction.Read).metadata.location).isNotNull()

    val result = stripper.strip(source, out, "video/mp4")

    assertThat(result).isEqualTo(StripOutcome.Stripped("video/mp4"))
    val stripped = out.readBytes()
    assertThat(stripped.size).isEqualTo(original.size) // stessa dimensione: nessuna ricodifica, nessun offset che cambia
    assertThat(StripFixtures.contains(stripped, ISO6709)).isFalse()
    assertThat(StripFixtures.contains(stripped, "+45.4642")).isFalse()
    assertThat(StripFixtures.indexOf(stripped, byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()))).isEqualTo(-1)
    assertThat(StripFixtures.contains(stripped, "free")).isTrue()
    // Ancora un video che si riproduce: ftyp, mdat e moov sono dove erano (stessi offset), con la traccia video e quella audio.
    assertThat(StripFixtures.contains(stripped, "moov")).isTrue()
    assertThat(LocationFixtures.handlers(stripped)).containsExactly("vide", "soun").inOrder()
    assertThat(stripped.copyOfRange(0, original.indexOfFirst4("udta"))).isEqualTo(original.copyOfRange(0, original.indexOfFirst4("udta")))
    assertThat((read(out, "video/mp4") as Extraction.Read).metadata.location).isNull()
    assertThat(sha(source)).isEqualTo(MessageDigest.getInstance("SHA-256").digest(original).toList())
  }

  @Test
  fun movDiAppleIlMetaDiQuickTimeSparisceIntero() {
    val original = StripFixtures.movWithApplePosition()
    val source = src("a.mov", original)
    val out = playable(dst("out.mov"))
    assertThat(StripFixtures.contains(original, ISO6709)).isTrue()
    assertThat(StripFixtures.contains(original, "com.apple.quicktime.location.ISO6709")).isTrue()

    val result = stripper.strip(source, out, "video/quicktime")

    assertThat(result).isEqualTo(StripOutcome.Stripped("video/quicktime"))
    val stripped = out.readBytes()
    assertThat(stripped.size).isEqualTo(original.size)
    assertThat(StripFixtures.contains(stripped, ISO6709)).isFalse()
    assertThat(StripFixtures.contains(stripped, "location")).isFalse()
    // Il `meta` di QuickTime non si pulisce chiave per chiave: sparisce intero (anche marca e modello), le tracce restano.
    assertThat(StripFixtures.contains(stripped, "com.apple.quicktime.make")).isFalse()
    assertThat(StripFixtures.contains(stripped, "iPhone 15")).isFalse()
    assertThat(LocationFixtures.handlers(stripped)).containsExactly("vide", "soun").inOrder()
  }

  @Test
  fun mp4ConXmpInUnRiquadroUuidIlRiquadroSparisce() {
    val original = StripFixtures.mp4WithXmpUuid()
    val source = src("a.mp4", original)
    val out = playable(dst("out.mp4"))
    assertThat(StripFixtures.contains(original, "GPSLatitude")).isTrue()

    assertThat(stripper.strip(source, out, "video/mp4")).isEqualTo(StripOutcome.Stripped("video/mp4"))

    val stripped = out.readBytes()
    assertThat(stripped.size).isEqualTo(original.size)
    assertThat(StripFixtures.contains(stripped, "GPSLatitude")).isFalse()
    assertThat(StripFixtures.contains(stripped, "dc:creator")).isFalse() // il riquadro sconosciuto diventa `free`, vuoto
  }

  @Test
  fun unVideoConLaSolaTracciaDiMetadatiNonSiCondivide() {
    // Tolta la traccia dei punti GPS non resterebbe un video: la condivisione si ferma (le tracce accanto a video e audio, invece, si tolgono:
    // `PosizioneNascostaTest`).
    for (entry in listOf("camm", "gpmd")) {
      val source = src("t-$entry.mp4", StripFixtures.mp4WithTimedTrack(entry))
      val out = playable(dst("out-$entry.mp4"))

      assertThat(stripper.strip(source, out, "video/mp4")).isEqualTo(StripOutcome.Unsupported(StripUnsupported.FORMAT))

      assertWithMessage(entry).that(out.exists()).isFalse()
    }
  }

  @Test
  fun unVideoSenzaIndiceNonSiRiproduceENonSiConsegna() {
    // Registrazione interrotta: `ftyp` e `mdat`, niente `moov`. Nessuna posizione da togliere, ma nemmeno un video da condividere.
    val truncated = dev.pampa.mycloudgallery.fixtures.IsoFixtures.mp4(dev.pampa.mycloudgallery.fixtures.IsoFixtures.Mp4Spec().apply { includeMoov = false })
    val source = src("t.mp4", truncated)
    val out = playable(dst("out.mp4"))

    val result = stripper.strip(source, out, "video/mp4")

    assertThat(result).isNotInstanceOf(StripOutcome.Stripped::class.java)
    assertThat(out.exists()).isFalse()
  }

  @Test
  fun unVideoSenzaPosizioneNonCambiaUnByte() {
    val original = StripFixtures.mp4Clean().bytes
    val source = src("a.mp4", original)
    val out = playable(dst("out.mp4"))

    assertThat(stripper.strip(source, out, "video/mp4")).isEqualTo(StripOutcome.Stripped("video/mp4"))

    assertThat(out.readBytes()).isEqualTo(original)
  }

  // --- Motion photo --------------------------------------------------------------------------------------------------------------

  @Test
  fun inUnaMotionPhotoAnchePartVideoPerdeLaSuaPosizione() {
    val original = StripFixtures.motionPhoto()
    val source = src("motion.jpg", original)
    val out = dst("out.jpg")
    assertThat(StripFixtures.contains(original, ISO6709)).isTrue()

    assertThat(stripper.strip(source, out, "image/jpeg")).isEqualTo(StripOutcome.Stripped("image/jpeg"))

    val stripped = out.readBytes()
    assertThat(StripFixtures.contains(stripped, ISO6709)).isFalse()
    assertThat(StripFixtures.contains(stripped, "GPSLatitude")).isFalse()
    assertThat(ExifInterface(out).latLong).isNull()
    // Il video incorporato c'e' ancora (ftyp e moov): e' una motion photo, non solo una foto.
    assertThat(StripFixtures.contains(stripped, "ftyp")).isTrue()
    assertThat(StripFixtures.contains(stripped, "moov")).isTrue()
    assertThat(LocationTraceRecheck().check(out, "image/jpeg")).isEqualTo(LocationVerdict.CLEAR)
    assertThat(LocationTraceRecheck().check(source, "image/jpeg")).isEqualTo(LocationVerdict.HAS_LOCATION)
    // Il video riattaccato ha ancora video e audio, ed e' dichiarato da un XMP nuovo (solo la sua lunghezza).
    val start = StripFixtures.indexOf(stripped, "ftyp".toByteArray()) - 4
    assertThat(LocationFixtures.handlers(stripped, start)).containsExactly("vide", "soun").inOrder()
    assertThat(StripFixtures.contains(stripped, "MotionPhoto")).isTrue()
  }

  // --- HEIC → JPEG ---------------------------------------------------------------------------------------------------------------

  @Test
  fun heicDiventaJpegSenzaPosizione() {
    val source = src("a.heic", StripFixtures.heic())
    val out = dst("out.jpg")
    // Il sorgente HEIC ha una posizione che l'estrattore dell'app legge.
    assertThat((read(source, "image/heic") as Extraction.Read).metadata.location).isNotNull()

    val result = stripper.strip(source, out, "image/heic")

    assertThat(result).isEqualTo(StripOutcome.Stripped("image/jpeg"))
    val read = read(out, "image/jpeg") as Extraction.Read
    assertThat(read.metadata.location).isNull()
    assertThat(AndroidPreviewDecoder().decode(out.readBytes(), 64)).isNotNull()
    assertThat(ExifInterface(out).latLong).isNull()
  }

  @Test
  fun unConvertitoreChePortaUnaPosizioneNonLaFaPassare() {
    // Un convertitore sbagliato (restituisce un JPEG con l'EXIF GPS): il JPEG si ricostruisce come ogni altro, e la posizione non passa.
    val leaky = DefaultLocationStripper(StripFixtures.rechecks(), heic = FakeHeicToJpeg(leak = true))
    val source = src("a.heic", StripFixtures.heic())
    val out = dst("out.jpg")

    assertThat(leaky.strip(source, out, "image/heic")).isEqualTo(StripOutcome.Stripped("image/jpeg"))

    assertThat(ExifInterface(out).latLong).isNull()
    assertThat(LocationTraces.scan(out).clean).isTrue()
    // Il file di lavoro della conversione non resta accanto alla copia.
    assertThat(folder.root.listFiles()!!.map { it.name }.filter { it.endsWith(".heic.jpg") }).isEmpty()
  }

  @Test
  fun senzaConvertitoreOSenzaDecoderHevcUnHeicNonSiCondivideSenzaPosizione() {
    val none = DefaultLocationStripper(StripFixtures.rechecks())
    val source = src("a.heic", StripFixtures.heic())
    val out = dst("out.jpg")

    assertThat(none.strip(source, out, "image/heic")).isEqualTo(StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE))
    assertThat(out.exists()).isFalse()

    val failing = DefaultLocationStripper(StripFixtures.rechecks(), heic = FakeHeicToJpeg(fail = WriteFailure.SOURCE_UNREADABLE))
    assertThat(failing.strip(source, out, "image/heic")).isEqualTo(StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE))
    assertThat(out.exists()).isFalse()
  }

  // --- Si fallisce chiusi --------------------------------------------------------------------------------------------------------

  @Test
  fun unLettoreCheTrovaUnaPosizioneFermaTuttoEToglieLaCopia() {
    val readers = StripFixtures.rechecks() + FixedRecheck(LocationVerdict.HAS_LOCATION)
    val strict = DefaultLocationStripper(readers)
    val source = src("a.jpg", StripFixtures.jpeg())
    val out = dst("out.jpg")

    assertThat(strict.strip(source, out, "image/jpeg")).isEqualTo(StripOutcome.StillHasLocation)

    assertThat(out.exists()).isFalse()
  }

  @Test
  fun unLettoreCheNonRiesceALeggereNonAssolveEToglieLaCopia() {
    val readers = StripFixtures.rechecks() + FixedRecheck(LocationVerdict.UNREADABLE)
    val strict = DefaultLocationStripper(readers)
    val source = src("a.jpg", StripFixtures.jpeg())
    val out = dst("out.jpg")

    assertThat(strict.strip(source, out, "image/jpeg")).isEqualTo(StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE))

    assertThat(out.exists()).isFalse()
  }

  @Test
  fun conMenoDiDueLettoriCheSiApplicanoNonSiCondivideNiente() {
    val source = src("a.jpg", StripFixtures.jpeg())
    val video = src("a.mp4", StripFixtures.mp4WithLocation())
    val out = dst("out.bin")

    // Nessun lettore, o uno solo: non e' un "ricontrollo con due lettori".
    for (readers in listOf(emptyList(), listOf(ExifInterfaceLocationRecheck()))) {
      val weak = DefaultLocationStripper(readers)
      assertThat(weak.strip(source, out, "image/jpeg")).isEqualTo(StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE))
      assertThat(out.exists()).isFalse()
    }
    // Per un video i due lettori delle foto non contano: l'estrattore da solo non basta.
    val photoReaders = DefaultLocationStripper(listOf(ExtractorLocationRecheck(StripFixtures.extractor(), null, ROME), ExifInterfaceLocationRecheck()))
    assertThat(photoReaders.strip(video, out, "video/mp4")).isEqualTo(StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE))
    assertThat(out.exists()).isFalse()
  }

  @Test
  fun unaCopiaCheNonSiLeggeNonEUnSuccesso() {
    // Un "JPEG" troncato dopo l'intestazione: nessun lettore puo' assolverlo.
    val broken = StripFixtures.jpeg().copyOf(120)
    val source = src("broken.jpg", broken)
    val out = dst("out.jpg")

    val result = stripper.strip(source, out, "image/jpeg")

    assertThat(result).isNotInstanceOf(StripOutcome.Stripped::class.java)
    assertThat(out.exists()).isFalse()
  }

  @Test
  fun iFormatiCheNonSiSannoTrattareSiFermanoConIlMotivo() {
    val formats = mapOf(
      "gif" to ImageFixtures.gif(16, 16, 0x336699),
      "bmp" to ImageFixtures.bmp(16, 16, 0x336699),
      "testo" to "non sono un media, solo testo".toByteArray(),
    )
    for ((name, bytes) in formats) {
      val source = src("a.$name", bytes)
      val out = dst("out-$name")

      assertWithMessage(name).that(stripper.strip(source, out, "image/jpeg")).isEqualTo(StripOutcome.Unsupported(StripUnsupported.FORMAT))

      assertThat(out.exists()).isFalse()
    }
  }

  @Test
  fun unaDestinazioneCheEsisteGiaNonSiSostituisceEUnSorgenteAssenteNonSiLegge() {
    val source = src("a.jpg", StripFixtures.jpeg())
    val taken = src("taken.jpg", "gia' mio".toByteArray())

    assertThat(stripper.strip(source, taken, "image/jpeg")).isEqualTo(StripOutcome.Failed(WriteFailure.DESTINATION_EXISTS))
    assertThat(taken.readText()).isEqualTo("gia' mio")

    assertThat(stripper.strip(dst("manca.jpg"), dst("out.jpg"), "image/jpeg")).isEqualTo(StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE))
    assertThat(dst("out.jpg").exists()).isFalse()
  }

  @Test
  fun unaFotoOltreIlTettoNonSiCopiaNeppure() {
    val small = DefaultLocationStripper(StripFixtures.rechecks(), maxImageBytes = 1_000)
    val source = src("a.jpg", StripFixtures.jpeg())
    val out = dst("out.jpg")

    assertThat(small.strip(source, out, "image/jpeg")).isEqualTo(StripOutcome.Failed(WriteFailure.SOURCE_UNREADABLE))

    assertThat(out.exists()).isFalse()
  }

  // --- Ogni lettore, da solo, riconosce ogni tipo di posizione ---------------------------------------------------------------------

  @Test
  fun ognunoDeiLettoriVedeUnaPosizioneNelSuoPosto() {
    val withExif = src("exif.jpg", StripFixtures.jpeg(xmp = null))
    val withXmp = src("xmp.jpg", StripFixtures.jpeg(exif = StripFixtures.exifWithoutGps()))
    val video = src("v.mp4", StripFixtures.mp4WithLocation())
    val extractor = ExtractorLocationRecheck(StripFixtures.extractor(), AndroidPreviewDecoder(), ROME)

    assertThat(extractor.check(withExif, "image/jpeg")).isEqualTo(LocationVerdict.HAS_LOCATION)
    assertThat(ExifInterfaceLocationRecheck().check(withExif, "image/jpeg")).isEqualTo(LocationVerdict.HAS_LOCATION)
    assertThat(LocationTraceRecheck().check(withExif, "image/jpeg")).isEqualTo(LocationVerdict.HAS_LOCATION)
    assertThat(LocationTraceRecheck().check(withXmp, "image/jpeg")).isEqualTo(LocationVerdict.HAS_LOCATION)
    assertThat(extractor.check(video, "video/mp4")).isEqualTo(LocationVerdict.HAS_LOCATION)
    assertThat(LocationTraceRecheck().check(video, "video/mp4")).isEqualTo(LocationVerdict.HAS_LOCATION)
    // E su file puliti dicono tutti "nessuna posizione".
    val clean = src("c.jpg", StripFixtures.jpeg(exif = StripFixtures.exifWithoutGps(), xmp = null))
    assertThat(extractor.check(clean, "image/jpeg")).isEqualTo(LocationVerdict.CLEAR)
    assertThat(ExifInterfaceLocationRecheck().check(clean, "image/jpeg")).isEqualTo(LocationVerdict.CLEAR)
    assertThat(LocationTraceRecheck().check(clean, "image/jpeg")).isEqualTo(LocationVerdict.CLEAR)
  }

  // --- Aiuti ---------------------------------------------------------------------------------------------------------------------

  /** Un lettore con un parere deciso dal test. */
  private class FixedRecheck(private val verdict: LocationVerdict) : LocationRecheck {
    override fun appliesTo(mime: String): Boolean = true

    override fun check(file: File, mime: String): LocationVerdict = verdict
  }

  /**
   * Un convertitore HEIC → JPEG per la JVM (qui non c'e' un decodificatore HEVC): scrive un JPEG vero, senza metadati; con [leak] un JPEG che
   * ha ancora le coordinate (un convertitore sbagliato); con [fail] un guasto.
   */
  private class FakeHeicToJpeg(private val leak: Boolean = false, private val fail: WriteFailure? = null) : dev.pampa.mycloudgallery.core.metadata.strip.HeicToJpeg {
    override fun convert(src: File, dst: File): HeicConversion {
      if (fail != null) return HeicConversion.Failed(fail)
      val bytes = if (leak) StripFixtures.jpeg(xmp = null) else ImageFixtures.jpeg(64, 48, 0x336699)
      try {
        dst.writeBytes(bytes)
      } catch (_: IOException) {
        return HeicConversion.Failed(WriteFailure.IO)
      }
      return HeicConversion.Done
    }
  }

  private fun ByteArray.indexOfFirst4(text: String): Int = StripFixtures.indexOf(this, text.toByteArray(Charsets.ISO_8859_1))

  /** I chunk di un PNG che non hanno il CRC giusto. */
  private fun pngCrcErrors(png: ByteArray): List<String> {
    val errors = ArrayList<String>()
    var pos = 8
    while (pos + 12 <= png.size) {
      val length = ((png[pos].toInt() and 0xFF) shl 24) or ((png[pos + 1].toInt() and 0xFF) shl 16) or ((png[pos + 2].toInt() and 0xFF) shl 8) or (png[pos + 3].toInt() and 0xFF)
      val crc = java.util.zip.CRC32().apply { update(png, pos + 4, 4 + length) }.value
      val stored = ((png[pos + 8 + length].toLong() and 0xFF) shl 24) or ((png[pos + 9 + length].toLong() and 0xFF) shl 16) or
        ((png[pos + 10 + length].toLong() and 0xFF) shl 8) or (png[pos + 11 + length].toLong() and 0xFF)
      if (crc != stored) errors += String(png, pos + 4, 4, Charsets.ISO_8859_1)
      pos += 12 + length
    }
    return errors
  }
}
