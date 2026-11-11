package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le frasi del viewer, con dati veri: la data e l'ora **con il fuso dello scatto** (E-M14), "stimata" quando serve,
 * i megapixel, il peso, l'esposizione e le coordinate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "it")
class ViewerFormatTest {

  private val format = ViewerFormat(ApplicationProvider.getApplicationContext(), Locale.ITALIAN)

  private fun capture(offsetMinutes: Int, confidence: CaptureTimeConfidence = CaptureTimeConfidence.EXACT, source: CaptureTimeSource = CaptureTimeSource.EXIF_WITH_OFFSET) =
    CaptureTime(utcMillis = 1_710_243_660_000L, offsetMinutes = offsetMinutes, confidence = confidence, source = source)

  private fun details(width: Int?, height: Int?, orientation: Int = 0) = MediaDetails(
    id = MediaId.random(),
    kind = MediaKind.PHOTO,
    width = width,
    height = height,
    orientationDegrees = orientation,
  )

  @Test
  fun laDataEdOraSonoQuelleDelPostoDelloScatto() {
    // 11:41 UTC: a Oslo (UTC+1) sono le 12:41, a Tokyo (UTC+9) le 20:41.
    assertThat(format.time(capture(60))).isEqualTo("12:41")
    assertThat(format.time(capture(540))).isEqualTo("20:41")
    assertThat(format.dateTitle(capture(60))).isEqualTo("12 marzo 2024")
    assertThat(format.panelDate(capture(60))).isEqualTo("Martedì 12 marzo 2024")
  }

  @Test
  fun loScattoDopoMezzanotteDelPostoCambiaIlGiorno() {
    // 11:41 UTC e UTC+13 (Auckland): il giorno dopo.
    assertThat(format.dateTitle(capture(13 * 60))).isEqualTo("13 marzo 2024")
  }

  @Test
  fun lOffsetFattoDiMinutiSiLeggeComeSiScrive() {
    assertThat(format.offsetLabel(0)).isEqualTo("UTC")
    assertThat(format.offsetLabel(60)).isEqualTo("UTC+1")
    assertThat(format.offsetLabel(330)).isEqualTo("UTC+5:30")
    assertThat(format.offsetLabel(-300)).isEqualTo("UTC-5")
    assertThat(format.offsetLabel(-570)).isEqualTo("UTC-9:30")
    assertThat(format.offsetLabel(765)).isEqualTo("UTC+12:45")
  }

  @Test
  fun unaDataStimataDiceDaDoveVieneLaStima() {
    val byName = capture(60, CaptureTimeConfidence.ESTIMATED, CaptureTimeSource.FILE_NAME)
    val bySystem = capture(60, CaptureTimeConfidence.ESTIMATED, CaptureTimeSource.MEDIASTORE)
    val byModified = capture(0, CaptureTimeConfidence.ESTIMATED, CaptureTimeSource.FILE_MODIFIED)
    assertThat(format.panelTimeLine(byName)).endsWith(" · Data ricavata dal nome del file")
    assertThat(format.panelTimeLine(bySystem)).endsWith(" · Data ricavata dal sistema")
    assertThat(format.panelTimeLine(byModified)).endsWith(" · Data di modifica del file, non dello scatto")
  }

  @Test
  fun ilSottotitoloHaOraELuogoSoloQuandoCiSono() {
    assertThat(format.subtitle(capture(60), "Oslo")).isEqualTo("12:41 · Oslo")
    assertThat(format.subtitle(capture(60), null)).isEqualTo("12:41")
    assertThat(format.subtitle(null, "Oslo")).isEqualTo("Oslo")
    assertThat(format.subtitle(null, null)).isNull()
  }

  @Test
  fun leDimensioniSonoQuelleCheSiVedonoConIMegapixel() {
    assertThat(format.dimensionPills(details(4000, 3000))).containsExactly("4000 × 3000", "12 MP").inOrder()
    // Una foto in verticale (rotazione di 90 gradi) si legge dritta.
    assertThat(format.dimensionPills(details(4000, 3000, orientation = 90))).containsExactly("3000 × 4000", "12 MP").inOrder()
    assertThat(format.dimensionPills(details(1600, 1200))).containsExactly("1600 × 1200", "1,9 MP").inOrder()
    assertThat(format.dimensionPills(details(16000, 12500))).containsExactly("16000 × 12500", "200 MP").inOrder()
    assertThat(format.dimensionPills(details(null, null))).isEmpty()
  }

  @Test
  fun ilFormatoSiScriveComeLoLeggeUnUmano() {
    assertThat(format.formatName("image/jpeg")).isEqualTo("JPEG")
    assertThat(format.formatName("image/heic")).isEqualTo("HEIC")
    assertThat(format.formatName("image/webp")).isEqualTo("WebP")
    assertThat(format.formatName("image/x-adobe-dng")).isEqualTo("DNG")
    assertThat(format.formatName("video/quicktime")).isEqualTo("MOV")
    assertThat(format.formatName("image/x-strano")).isEqualTo("X-STRANO")
    assertThat(format.formatName(null)).isNull()
  }

  @Test
  fun laFotocameraNonRipeteLaMarca() {
    assertThat(format.camera(ExifDetails(make = "SAMSUNG", model = "Samsung Galaxy S25"))).isEqualTo("Samsung Galaxy S25")
    assertThat(format.camera(ExifDetails(make = "Canon", model = "EOS R5"))).isEqualTo("Canon EOS R5")
    assertThat(format.camera(ExifDetails(make = "SONY", model = "ILCE-7M4"))).isEqualTo("Sony ILCE-7M4")
    assertThat(format.camera(ExifDetails(model = "Pixel 9"))).isEqualTo("Pixel 9")
    assertThat(format.camera(ExifDetails())).isNull()
  }

  @Test
  fun l_EsposizioneHaSoloIPezziChePortaLaFotocamera() {
    val full = ExifDetails(fNumber = 1.8, exposureTime = "1/250", iso = 50, focalLengthMm = 6.3, flashFired = true, lens = "Principale")
    assertThat(format.shotStrip(full)).containsExactly("ISO 50", "6,3 mm", "f/1,8", "1/250 s").inOrder()
    assertThat(format.shotStrip(ExifDetails(iso = 200))).containsExactly("ISO 200")
    assertThat(format.shotStrip(ExifDetails())).isEmpty()
    // Un diaframma intero non si scrive "2,0".
    assertThat(format.shotStrip(ExifDetails(fNumber = 2.0))).containsExactly("f/2")
  }

  @Test
  fun leCoordinateHannoIlVersoGiusto() {
    assertThat(format.coordinates(GeoPoint(59.9127, 10.7461))).isEqualTo("59,9127° N, 10,7461° E")
    assertThat(format.coordinates(GeoPoint(-33.8688, -151.2093))).isEqualTo("33,8688° S, 151,2093° O")
  }

  @Test
  fun laDescrizioneDiUnaPaginaPerIlLettoreDiSchermo() {
    assertThat(format.pageDescription(MediaKind.PHOTO, 20240312, "Oslo")).isEqualTo("Foto, 12 marzo 2024, Oslo")
    assertThat(format.pageDescription(MediaKind.VIDEO, 20240312, null)).isEqualTo("Video, 12 marzo 2024")
    assertThat(format.pageDescription(MediaKind.MOTION_PHOTO, 20240312, null)).isEqualTo("Foto in movimento, 12 marzo 2024")
    // Un giorno impossibile (indice rovinato) non fa cadere il viewer: resta il tipo.
    assertThat(format.pageDescription(MediaKind.PHOTO, 20241399, null)).isEqualTo("Foto")
  }

  @Test
  fun ilPesoSiScriveConLeUnitaDelSistema() {
    assertThat(format.fileSize(3_600_000L)).contains("MB")
  }
}
