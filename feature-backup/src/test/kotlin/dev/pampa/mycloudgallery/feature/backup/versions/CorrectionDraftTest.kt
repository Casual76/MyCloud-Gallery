package dev.pampa.mycloudgallery.feature.backup.versions

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.GeoPoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Test

/**
 * La bozza di una correzione (D19, E-L8): cio' che l'utente scrive diventa una richiesta del contratto, o errori. Prova la lettura di date, ore
 * e coordinate come le scrive una persona, la rotazione (anche di una foto specchiata) e che un campo non toccato non entra nella correzione.
 */
class CorrectionDraftTest {

  private val subject = CorrectionSubject(
    mediaId = VersionSamples.media,
    takenAt = LocalDateTime.of(2026, 10, 12, 14, 32),
    offsetMinutes = 120,
    placeLabel = "Oslo",
    hasLocation = true,
    orientation = ExifOrientation.NORMAL,
  )

  private fun draft() = CorrectionDraft.startingFrom(subject)

  // --- Date e ore ------------------------------------------------------------------------------------------------------------

  @Test
  fun laBozzaDiPartenzaHaIValoriDiAdessoENessunCampoAcceso() {
    val start = draft()
    assertThat(start.dateText).isEqualTo("12/10/2026")
    assertThat(start.timeText).isEqualTo("14:32")
    assertThat(start.fields).isEmpty()
    assertThat(start.build(subject).errors).containsExactly(DraftError.NOTHING_TO_DO)
  }

  @Test
  fun unMediaSenzaDataParteConICampiVuoti() {
    val start = CorrectionDraft.startingFrom(CorrectionSubject(VersionSamples.media))
    assertThat(start.dateText).isEmpty()
    assertThat(start.timeText).isEmpty()
  }

  @Test
  fun leDateSiLeggonoComeLeScriveUnaPersona() {
    assertThat(CorrectionParsing.parseDate("03/10/2026")).isEqualTo(LocalDate.of(2026, 10, 3))
    assertThat(CorrectionParsing.parseDate(" 3.10.2026 ")).isEqualTo(LocalDate.of(2026, 10, 3))
    assertThat(CorrectionParsing.parseDate("3-1-1999")).isEqualTo(LocalDate.of(1999, 1, 3))
    assertThat(CorrectionParsing.parseDate("29/02/2024")).isEqualTo(LocalDate.of(2024, 2, 29))
  }

  @Test
  fun leDateFalseSiRifiutano() {
    for (text in listOf("", "31/02/2026", "29/02/2025", "00/01/2026", "12/13/2026", "12/10", "12/10/26", "ieri", "12/10/2026/1", "12/10/1800", "12/10/2200", "a/b/c", "-1/10/2026")) {
      assertThat(CorrectionParsing.parseDate(text)).isNull()
    }
  }

  @Test
  fun leOreSiLeggonoEQuelleFalseSiRifiutano() {
    assertThat(CorrectionParsing.parseTime("14:30")).isEqualTo(LocalTime.of(14, 30))
    assertThat(CorrectionParsing.parseTime("9.05")).isEqualTo(LocalTime.of(9, 5))
    assertThat(CorrectionParsing.parseTime("00:00")).isEqualTo(LocalTime.MIDNIGHT)
    for (text in listOf("", "24:00", "12:60", "12", "12:5x", "dodici", "12:30:10", ":30")) {
      assertThat(CorrectionParsing.parseTime(text)).isNull()
    }
  }

  @Test
  fun laDataCorrettaSiScriveNelFusoDelMedia() {
    val result = draft().copy(changeDate = true, dateText = "03/10/2026", timeText = "10:00").build(subject)
    val change = result.request!!.change
    // 10:00 a UTC+2 = 08:00 UTC.
    assertThat(change.takenAtUtcMillis).isEqualTo(LocalDateTime.of(2026, 10, 3, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
    assertThat(change.offsetMinutes).isEqualTo(120)
    assertThat(result.request!!.fields).containsExactly(CorrectionField.DATE)
  }

  @Test
  fun unaDataSbagliataDaTuttiGliErroriGiusti() {
    val result = draft().copy(changeDate = true, dateText = "31/02/2026", timeText = "25:00").build(subject)
    assertThat(result.request).isNull()
    assertThat(result.errors).containsExactly(DraftError.DATE_INVALID, DraftError.TIME_INVALID)
  }

  @Test
  fun uncampoSpentoNonEntraNellaCorrezioneNemmenoSeSbagliato() {
    val result = draft().copy(changeDate = false, dateText = "xx", timeText = "yy", quarterTurns = 1).build(subject)
    assertThat(result.errors).isEmpty()
    assertThat(result.request!!.fields).containsExactly(CorrectionField.ROTATION)
  }

  // --- Luogo -----------------------------------------------------------------------------------------------------------------

  @Test
  fun togliereIlLuogo() {
    val request = draft().copy(place = PlaceEdit.CLEAR).build(subject).request!!
    assertThat(request.change.geo).isEqualTo(GeoChange.Clear)
    assertThat(request.fields).containsExactly(CorrectionField.LOCATION)
  }

  @Test
  fun cambiareIlLuogoSenzaSceglierneUnoNonSiPuo() {
    val result = draft().copy(place = PlaceEdit.CHANGE).build(subject)
    assertThat(result.errors).containsExactly(DraftError.PLACE_MISSING)
  }

  @Test
  fun unLuogoSceltoFraIRisultatiVinceSulleCoordinate() {
    val choice = PlaceChoice("Bergen", GeoPoint(60.39, 5.32))
    val result = draft().copy(place = PlaceEdit.CHANGE, chosenPlace = choice, coordinatesText = "1 2").build(subject)
    val geo = result.request!!.change.geo as GeoChange.Set
    assertThat(geo.point.latitude).isEqualTo(60.39)
    assertThat(geo.point.longitude).isEqualTo(5.32)
  }

  @Test
  fun leCoordinateSiLeggonoInTuttiIModiNormali() {
    for (text in listOf("45,4642 9,1900", "45.4642, 9.1900", "45,4642; 9,1900", "45.4642 9.1900", "45.4642,9.1900", "  45,4642   9,1900  ")) {
      val point = CorrectionParsing.parseCoordinates(text)
      assertThat(point).isNotNull()
      assertThat(point!!.latitude).isWithin(1e-9).of(45.4642)
      assertThat(point.longitude).isWithin(1e-9).of(9.19)
    }
    val south = CorrectionParsing.parseCoordinates("-33,8688 151,2093")!!
    assertThat(south.latitude).isWithin(1e-9).of(-33.8688)
  }

  @Test
  fun leCoordinateSbagliateSiRifiutano() {
    for (text in listOf("", "45,4642", "abc def", "91 10", "10 181", "45,4642,9,1900", "nan 3", "1;2;3")) {
      assertThat(CorrectionParsing.parseCoordinates(text)).isNull()
    }
    val result = draft().copy(place = PlaceEdit.CHANGE, coordinatesText = "91 10").build(subject)
    assertThat(result.errors).containsExactly(DraftError.COORDINATES_INVALID)
  }

  // --- Rotazione -------------------------------------------------------------------------------------------------------------

  @Test
  fun laRotazioneSiSommaAiGradiDiAdesso() {
    assertThat(CorrectionParsing.rotated(ExifOrientation.NORMAL, 1)).isEqualTo(ExifOrientation.ROTATE_90)
    assertThat(CorrectionParsing.rotated(ExifOrientation.NORMAL, 2)).isEqualTo(ExifOrientation.ROTATE_180)
    assertThat(CorrectionParsing.rotated(ExifOrientation.NORMAL, 3)).isEqualTo(ExifOrientation.ROTATE_270)
    assertThat(CorrectionParsing.rotated(ExifOrientation.ROTATE_270, 1)).isEqualTo(ExifOrientation.NORMAL)
    assertThat(CorrectionParsing.rotated(ExifOrientation.ROTATE_90, 3)).isEqualTo(ExifOrientation.NORMAL)
    assertThat(CorrectionParsing.rotated(ExifOrientation.ROTATE_90, 0)).isEqualTo(ExifOrientation.ROTATE_90)
  }

  @Test
  fun unaFotoSpecchiataRestaSpecchiataRuotando() {
    // "Prima lo specchio, poi la rotazione": i gradi si sommano e lo specchio non cambia.
    assertThat(CorrectionParsing.rotated(ExifOrientation.FLIP_HORIZONTAL, 1)).isEqualTo(ExifOrientation.TRANSVERSE)
    assertThat(CorrectionParsing.rotated(ExifOrientation.TRANSPOSE, 1)).isEqualTo(ExifOrientation.FLIP_HORIZONTAL)
    for (start in ExifOrientation.entries) {
      for (turns in 0..3) assertThat(CorrectionParsing.rotated(start, turns).mirrored).isEqualTo(start.mirrored)
    }
  }

  @Test
  fun quattroQuartiDiGiroTornanoAlPuntoDiPartenzaEdunqueNonCambiaNiente() {
    // La bozza non permette quarti oltre 3: quattro giri sono zero giri.
    assertThat(CorrectionParsing.rotated(ExifOrientation.ROTATE_90, 4)).isEqualTo(ExifOrientation.ROTATE_90)
    assertThat(draft().copy(quarterTurns = 0).build(subject).errors).containsExactly(DraftError.NOTHING_TO_DO)
  }

  @Test
  fun inModoRuotaContaSoloLaRotazione() {
    val noisy = draft().copy(changeDate = true, dateText = "xx", place = PlaceEdit.CLEAR, quarterTurns = 3)
    val result = noisy.build(subject, CorrectionMode.ROTATE)
    assertThat(result.errors).isEmpty()
    val request = result.request!!
    assertThat(request.fields).containsExactly(CorrectionField.ROTATION)
    assertThat(request.change.orientation).isEqualTo(ExifOrientation.ROTATE_270)
    assertThat(request.change.geo).isEqualTo(GeoChange.Keep)
    assertThat(request.change.takenAtUtcMillis).isNull()
  }

  @Test
  fun fuoriDaiTreQuartiNonSiPuoDichiarare() {
    val failure = runCatching { CorrectionDraft(quarterTurns = 4) }
    assertThat(failure.isFailure).isTrue()
  }

  @Test
  fun ilTestoDiUnaBozzaNonPortaDatiPersonali() {
    val text = draft().copy(changeDate = true, dateText = "03/10/2026", place = PlaceEdit.CLEAR).toString()
    assertThat(text).doesNotContain("2026")
    assertThat(subject.toString()).doesNotContain("Oslo")
    assertThat(PlaceChoice("Bergen", GeoPoint(60.39, 5.32)).toString()).doesNotContain("Bergen")
  }
}
