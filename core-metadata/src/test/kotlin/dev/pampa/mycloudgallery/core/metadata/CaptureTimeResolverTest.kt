package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.CaptureTimeInputs
import dev.pampa.mycloudgallery.core.metadata.contract.CaptureTimeResolver
import dev.pampa.mycloudgallery.core.metadata.contract.FileNameDate
import dev.pampa.mycloudgallery.core.metadata.contract.FileNamePattern
import dev.pampa.mycloudgallery.core.metadata.contract.NeighborOffsets
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence.ESTIMATED
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence.EXACT
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence.OFFSET_ASSUMED
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.domain.TimelineRules
import dev.pampa.mycloudgallery.core.places.noop.NoTimeZoneLookup
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test

/** La tabella delle date del §2.2 (E-M8, E-M9, E-M14, E-P5), una riga per prova, senza file: solo il resolver. */
class CaptureTimeResolverTest {

  private val now = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()
  private val tokyo = GeoPoint(35.6895, 139.6917)
  private val newYork = GeoPoint(40.7143, -74.0060)
  private val oslo = GeoPoint(59.9127, 10.7461)

  private fun local(text: String): LocalDateTime = LocalDateTime.parse(text)

  private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

  private fun resolve(
    exifLocal: LocalDateTime? = null,
    exifOffset: Int? = null,
    subSecond: Int? = null,
    gpsUtc: Instant? = null,
    gps: GeoPoint? = null,
    videoUtcMs: Long? = null,
    videoGps: GeoPoint? = null,
    fileName: FileNameDate? = null,
    taken: Long? = null,
    modified: Long? = null,
    zone: ZoneId = ROME,
    neighbors: NeighborOffsets? = null,
  ): CaptureTime = CaptureTimeResolver.resolve(
    CaptureTimeInputs(exifLocal, exifOffset, subSecond, gpsUtc, gps, videoUtcMs, videoGps, fileName, taken, modified, zone, now),
    PLACES,
    neighbors,
  )

  private fun CaptureTime.assertIs(utc: String, offset: Int, confidence: Any, source: CaptureTimeSource) {
    assertThat(utcMillis).isEqualTo(ms(utc))
    assertThat(offsetMinutes).isEqualTo(offset)
    assertThat(this.confidence).isEqualTo(confidence)
    assertThat(this.source).isEqualTo(source)
  }

  // ---- 1. EXIF con fuso ---------------------------------------------------------------------------------------

  @Test
  fun exifConFusoScrittoEEsatto() {
    resolve(local("2024-03-12T10:15:30"), exifOffset = 60).assertIs("2024-03-12T09:15:30Z", 60, EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
    resolve(local("2024-07-04T22:30:00"), exifOffset = -300).assertIs("2024-07-05T03:30:00Z", -300, EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
    resolve(local("2024-01-15T08:00:00"), exifOffset = 330).assertIs("2024-01-15T02:30:00Z", 330, EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
  }

  @Test
  fun ilFusoScrittoVinceSuTuttoIlResto() {
    val c = resolve(local("2024-03-12T10:15:30"), exifOffset = 60, gps = tokyo, zone = ZoneId.of("Asia/Tokyo"), neighbors = NeighborOffsets(-300))
    c.assertIs("2024-03-12T09:15:30Z", 60, EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
  }

  @Test
  fun isottosecondiOrdinanoLeRaffiche() {
    val a = resolve(local("2024-03-12T10:15:30"), exifOffset = 60, subSecond = 100)
    val b = resolve(local("2024-03-12T10:15:30"), exifOffset = 60, subSecond = 700)
    assertThat(b.utcMillis - a.utcMillis).isEqualTo(600)
    assertThat(a.utcMillis).isEqualTo(ms("2024-03-12T09:15:30.100Z"))
    // Fuori scala si riporta in 0..999.
    assertThat(resolve(local("2024-03-12T10:15:30"), exifOffset = 60, subSecond = 5000).utcMillis).isEqualTo(ms("2024-03-12T09:15:30.999Z"))
  }

  @Test
  fun unFusoImpossibileNonSiUsa() {
    // 30 ore: non e' un fuso. Si ripiega sul telefono.
    val c = resolve(local("2024-03-12T10:15:30"), exifOffset = 30 * 60)
    c.assertIs("2024-03-12T09:15:30Z", 60, OFFSET_ASSUMED, CaptureTimeSource.EXIF_LOCAL)
  }

  // ---- 2. EXIF senza fuso + ora GPS ------------------------------------------------------------------------------

  @Test
  fun ilFusoSiRicavaDallOraGpsArrotondataAQuindiciMinuti() {
    // 10:15:30 locali contro 09:15:28 UTC: 1 h e 2 s -> +01:00.
    resolve(local("2024-03-12T10:15:30"), gpsUtc = Instant.parse("2024-03-12T09:15:28Z"), gps = oslo)
      .assertIs("2024-03-12T09:15:30Z", 60, EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
    // India: +05:30.
    resolve(local("2024-03-12T15:45:00"), gpsUtc = Instant.parse("2024-03-12T10:15:02Z"))
      .assertIs("2024-03-12T10:15:00Z", 330, EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
    // Nepal +05:45 e Terranova -03:30.
    assertThat(resolve(local("2024-03-12T12:00:00"), gpsUtc = Instant.parse("2024-03-12T06:15:00Z")).offsetMinutes).isEqualTo(345)
    assertThat(resolve(local("2024-03-12T12:00:00"), gpsUtc = Instant.parse("2024-03-12T15:30:00Z")).offsetMinutes).isEqualTo(-210)
    // Un fix GPS vecchio di 6 minuti non cambia il quarto d'ora piu' vicino.
    assertThat(resolve(local("2024-03-12T10:15:30"), gpsUtc = Instant.parse("2024-03-12T09:09:30Z")).offsetMinutes).isEqualTo(60)
  }

  @Test
  fun unOraGpsConUnaDifferenzaDiPiuDiQuattordiciOreNonSiUsa() {
    // GPS di un altro giorno: differenza 20 h -> inattendibile -> fuso del luogo (Tokyo), supposto.
    val c = resolve(local("2024-07-04T18:00:00"), gpsUtc = Instant.parse("2024-07-03T22:00:00Z"), gps = tokyo)
    c.assertIs("2024-07-04T09:00:00Z", 540, OFFSET_ASSUMED, CaptureTimeSource.EXIF_LOCAL)
  }

  // ---- 3. EXIF senza fuso, con GPS --------------------------------------------------------------------------------

  @Test
  fun ilFusoDelLuogoVincePerLeFotoConGps() {
    resolve(local("2024-07-04T18:00:00"), gps = tokyo).assertIs("2024-07-04T09:00:00Z", 540, OFFSET_ASSUMED, CaptureTimeSource.EXIF_LOCAL)
    // New York in estate: -04:00, quindi la sera del 4 luglio resta del 4 luglio (E-P5).
    val ny = resolve(local("2024-07-04T23:30:00"), gps = newYork)
    ny.assertIs("2024-07-05T03:30:00Z", -240, OFFSET_ASSUMED, CaptureTimeSource.EXIF_LOCAL)
    assertThat(TimelineRules.localDay(ny)).isEqualTo(20240704)
    // In inverno -05:00.
    assertThat(resolve(local("2024-01-04T23:30:00"), gps = newYork).offsetMinutes).isEqualTo(-300)
  }

  @Test
  fun senzaUnLuogoNoto_siRipiegaSuiViciniEPoiSulTelefono() {
    // NoTimeZoneLookup: nessun fuso dal GPS.
    val inputs = CaptureTimeInputs(exifLocal = local("2024-07-04T18:00:00"), gps = tokyo, scanZone = ROME, nowMs = now)
    val withNeighbors = CaptureTimeResolver.resolve(inputs, NoTimeZoneLookup, NeighborOffsets(-240))
    assertThat(withNeighbors.offsetMinutes).isEqualTo(-240)
    val phone = CaptureTimeResolver.resolve(inputs, NoTimeZoneLookup, null)
    assertThat(phone.offsetMinutes).isEqualTo(120)
    assertThat(phone.confidence).isEqualTo(OFFSET_ASSUMED)
  }

  // ---- 4. EXIF senza fuso e senza GPS (E-P5) ------------------------------------------------------------------------

  @Test
  fun senzaFusoNeGpsVaIlFusoDelTelefonoAllaLettura() {
    resolve(local("2024-01-20T09:00:00")).assertIs("2024-01-20T08:00:00Z", 60, OFFSET_ASSUMED, CaptureTimeSource.EXIF_LOCAL)
    resolve(local("2024-07-20T09:00:00")).assertIs("2024-07-20T07:00:00Z", 120, OFFSET_ASSUMED, CaptureTimeSource.EXIF_LOCAL)
  }

  @Test
  fun cambiareFusoDopoNonSpostaLOraLocale() {
    // Letta a Roma o a Tokyo, l'ora che si vede e' sempre 10:00: cio' che cambia e' l'istante (fuso supposto).
    val rome = resolve(local("2024-03-12T10:00:00"), zone = ROME)
    val tokyoPhone = resolve(local("2024-03-12T10:00:00"), zone = ZoneId.of("Asia/Tokyo"))
    assertThat(rome.localDateTime).isEqualTo(local("2024-03-12T10:00:00"))
    assertThat(tokyoPhone.localDateTime).isEqualTo(local("2024-03-12T10:00:00"))
    assertThat(TimelineRules.localDay(rome)).isEqualTo(TimelineRules.localDay(tokyoPhone))
  }

  @Test
  fun iViciniBattonoIlFusoDelTelefono() {
    val c = resolve(local("2024-07-04T18:00:00"), neighbors = NeighborOffsets(-240))
    c.assertIs("2024-07-04T22:00:00Z", -240, OFFSET_ASSUMED, CaptureTimeSource.EXIF_LOCAL)
  }

  @Test
  fun ilSaltoDellOraLegale() {
    // 02:30 del 31/3/2024 non esiste a Roma: si usa il fuso dopo il salto (+02:00), l'ora locale resta 02:30.
    val gap = resolve(local("2024-03-31T02:30:00"))
    assertThat(gap.offsetMinutes).isEqualTo(120)
    assertThat(gap.localDateTime).isEqualTo(local("2024-03-31T02:30:00"))
    // 02:30 del 27/10/2024 esiste due volte: il primo (+02:00).
    assertThat(resolve(local("2024-10-27T02:30:00")).offsetMinutes).isEqualTo(120)
  }

  // ---- 5. Video (E-M14) ----------------------------------------------------------------------------------------------

  @Test
  fun unVideoSiPiazzaNelFusoDelSuoGps() {
    val utc = ms("2024-07-04T12:00:00Z")
    val c = resolve(videoUtcMs = utc, videoGps = tokyo)
    c.assertIs("2024-07-04T12:00:00Z", 540, OFFSET_ASSUMED, CaptureTimeSource.VIDEO_UTC)
    assertThat(c.localDateTime).isEqualTo(local("2024-07-04T21:00:00"))
  }

  @Test
  fun unVideoSenzaGpsUsaIViciniEPoiIlTelefono() {
    val utc = ms("2024-07-04T12:00:00Z")
    assertThat(resolve(videoUtcMs = utc, neighbors = NeighborOffsets(-240)).localDateTime).isEqualTo(local("2024-07-04T08:00:00"))
    assertThat(resolve(videoUtcMs = utc).localDateTime).isEqualTo(local("2024-07-04T14:00:00"))
  }

  @Test
  fun fotoEVideoDelloStessoViaggioStannoInOrdineDiTempo() {
    // La foto a New York alle 08:00 locali (12:00Z) e il video girato un'ora dopo (13:00Z) sono ordinati per istante.
    val photo = resolve(local("2024-07-04T08:00:00"), exifOffset = -240)
    val video = resolve(videoUtcMs = ms("2024-07-04T13:00:00Z"), videoGps = newYork)
    assertThat(video.utcMillis - photo.utcMillis).isEqualTo(3_600_000)
    assertThat(video.localDateTime).isEqualTo(local("2024-07-04T09:00:00"))
  }

  @Test
  fun unContenitoreVideoAZeroVale1904EsiScarta() {
    val year1904 = ms("1904-01-01T00:00:00Z")
    val c = resolve(videoUtcMs = year1904, taken = ms("2024-03-12T09:15:30Z"))
    assertThat(c.source).isEqualTo(CaptureTimeSource.MEDIASTORE)
    assertThat(resolve(videoUtcMs = 0L, taken = ms("2024-03-12T09:15:30Z")).source).isEqualTo(CaptureTimeSource.MEDIASTORE)
  }

  // ---- 6. Nomi dei file -------------------------------------------------------------------------------------------------

  private fun name(pattern: FileNamePattern, date: String, time: String?, utc: Boolean = false) =
    FileNameDate(LocalDate.parse(date), time?.let { LocalTime.parse(it) }, utc, pattern)

  @Test
  fun unNomeConSoloIlGiornoDaUnaDataStimataAMezzogiorno() {
    val c = resolve(fileName = name(FileNamePattern.WHATSAPP, "2024-03-12", null))
    c.assertIs("2024-03-12T11:00:00Z", 60, ESTIMATED, CaptureTimeSource.FILE_NAME)
    assertThat(c.localDateTime).isEqualTo(local("2024-03-12T12:00:00"))
  }

  @Test
  fun unNomeConLOraDaUnOraCertaEUnFusoSupposto() {
    val c = resolve(fileName = name(FileNamePattern.SCREENSHOT, "2024-03-12", "10:15:30"))
    c.assertIs("2024-03-12T09:15:30Z", 60, OFFSET_ASSUMED, CaptureTimeSource.FILE_NAME)
  }

  @Test
  fun unNomePixelEInUtc() {
    val c = resolve(fileName = name(FileNamePattern.PIXEL, "2024-03-12", "10:15:35.123", utc = true))
    c.assertIs("2024-03-12T10:15:35.123Z", 60, OFFSET_ASSUMED, CaptureTimeSource.FILE_NAME)
    assertThat(c.localDateTime).isEqualTo(LocalDateTime.parse("2024-03-12T11:15:35.123"))
  }

  @Test
  fun unNomeVinceSulDateTakenEUnExifValidoVinceSuiNomi() {
    val named = name(FileNamePattern.AOSP_CAMERA, "2024-03-12", "10:15:30")
    assertThat(resolve(fileName = named, taken = ms("2024-05-05T10:00:00Z")).source).isEqualTo(CaptureTimeSource.FILE_NAME)
    assertThat(resolve(local("2024-01-01T08:00:00"), fileName = named).source).isEqualTo(CaptureTimeSource.EXIF_LOCAL)
  }

  // ---- 7. DATE_TAKEN e data del file -------------------------------------------------------------------------------------

  @Test
  fun ilDateTakenDelMediaStoreHaIlFusoSupposto() {
    val c = resolve(taken = ms("2024-03-12T09:15:30Z"))
    c.assertIs("2024-03-12T09:15:30Z", 60, OFFSET_ASSUMED, CaptureTimeSource.MEDIASTORE)
    assertThat(resolve(taken = ms("2024-03-12T09:15:30Z"), neighbors = NeighborOffsets(-300)).offsetMinutes).isEqualTo(-300)
  }

  @Test
  fun laDataDelFileEUnUltimoRipiegoSempreStimato() {
    val c = resolve(modified = ms("2023-05-05T08:00:00Z"))
    c.assertIs("2023-05-05T08:00:00Z", 120, ESTIMATED, CaptureTimeSource.FILE_MODIFIED)
    assertThat(c.isEstimated).isTrue()
  }

  @Test
  fun laDataDelFilePrimaDelNovantacinqueEAssurda_E_Adesso_E_LUltimoRipiego() {
    // I file dell'inventario hanno date dal 1979 (docs/inventario-nas.md): si scartano.
    val c = resolve(modified = ms("1979-06-01T00:00:00Z"))
    c.assertIs("2026-10-13T12:00:00Z", 120, ESTIMATED, CaptureTimeSource.FILE_MODIFIED)
    // Il primo giorno del 1995 e' ancora buono.
    assertThat(resolve(modified = ms("1995-01-01T00:00:00Z")).utcMillis).isEqualTo(ms("1995-01-01T00:00:00Z"))
    // Per il DATE_TAKEN il limite e' il 1900: un'immagine del 1979 puo' essere vera.
    assertThat(resolve(taken = ms("1979-06-01T00:00:00Z")).source).isEqualTo(CaptureTimeSource.MEDIASTORE)
  }

  @Test
  fun senzaNessunaFonteVieneAdessoEdEStimata() {
    val c = resolve()
    c.assertIs("2026-10-13T12:00:00Z", 120, ESTIMATED, CaptureTimeSource.FILE_MODIFIED)
  }

  // ---- 8. Date assurde (E-M9) -------------------------------------------------------------------------------------------------

  @Test
  fun leDateAssurdeSiScartanoEsiPassaAllaFonteSuccessiva() {
    val fallbackName = name(FileNamePattern.AOSP_CAMERA, "2024-03-12", "10:15:30")
    val absurdExif = listOf(
      "1899-12-31T23:59:59", // prima del 1900
      "1970-01-01T00:00:00", // all'epoca
      "1970-01-01T23:59:59", // a meno di un giorno dall'epoca
      "2026-10-16T00:00:00", // oltre ora + 48 h (13/10 12:00 + 48 h = 15/10 12:00)
      "2099-01-01T12:00:00",
    )
    for (text in absurdExif) {
      val c = resolve(local(text), exifOffset = 60, fileName = fallbackName)
      assertThat(c.source).isEqualTo(CaptureTimeSource.FILE_NAME)
    }
    // Ma entro 48 ore dal futuro e' ancora buona (un orologio avanti di poco).
    assertThat(resolve(local("2026-10-15T11:00:00"), exifOffset = 60, fileName = fallbackName).source).isEqualTo(CaptureTimeSource.EXIF_WITH_OFFSET)
  }

  @Test
  fun unNomeAssurdoSiScartaEIlMediaStoreSubentra() {
    val epoch = name(FileNamePattern.AOSP_CAMERA, "1970-01-01", "00:00:00")
    val c = resolve(fileName = epoch, taken = ms("2024-03-12T09:15:30Z"))
    assertThat(c.source).isEqualTo(CaptureTimeSource.MEDIASTORE)
    val future = name(FileNamePattern.AOSP_CAMERA, "2099-01-01", "00:00:00")
    assertThat(resolve(fileName = future, modified = ms("2023-05-05T08:00:00Z")).source).isEqualTo(CaptureTimeSource.FILE_MODIFIED)
    // Anche un WhatsApp con un giorno del 1970.
    assertThat(resolve(fileName = name(FileNamePattern.WHATSAPP, "1970-01-01", null), taken = ms("2024-03-12T09:15:30Z")).source)
      .isEqualTo(CaptureTimeSource.MEDIASTORE)
  }

  @Test
  fun unDateTakenAZeroONelFuturoSiScarta() {
    for (taken in listOf(0L, -1L, 86_400_000L, ms("2099-01-01T00:00:00Z"), ms("1899-01-01T00:00:00Z"))) {
      val c = resolve(taken = taken, modified = ms("2023-05-05T08:00:00Z"))
      assertThat(c.source).isEqualTo(CaptureTimeSource.FILE_MODIFIED)
    }
  }

  @Test
  fun ilResolverNonLanciaMaCon_Anni_Estremi() {
    val c = resolve(LocalDateTime.of(9999, 12, 31, 23, 59, 59), exifOffset = 60, modified = ms("2023-05-05T08:00:00Z"))
    assertThat(c.source).isEqualTo(CaptureTimeSource.FILE_MODIFIED)
    val zero = resolve(LocalDateTime.of(0, 1, 1, 0, 0, 0), fileName = name(FileNamePattern.SAMSUNG_CAMERA, "2024-03-12", "10:15:30"))
    assertThat(zero.source).isEqualTo(CaptureTimeSource.FILE_NAME)
  }
}
