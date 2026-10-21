package dev.pampa.mycloudgallery.feature.viewer.video

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.MotionPhotoLocator
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.MotionTestFiles
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerBytes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L'istante della foto per formato (D50, §2.11, E-V16, E-M16): sulle foto in movimento sintetiche il localizzatore del viewer da' lo stesso
 * intervallo del localizzatore vero di core-metadata (`MotionPhotoLocator`) e un istante che segue la regola: il campo dichiarato se e'
 * valido, altrimenti meta' del video.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MotionInstantTest {

  private val ref = MediaRef.Phone("external_primary", 1_001L, "image/jpeg")
  private val video = MotionTestFiles.mp4(durationMs = 4_000L)

  private fun locate(file: ByteArray): MotionVideo = runBlocking {
    BytesMotionVideoLocator(ViewerBytes { ByteArrayMediaBytes(file) }).locate(ref)
  }

  /** Cio' che dice il localizzatore vero sugli stessi byte (testa per l'XMP, coda per il trailer): la verita' di riferimento. */
  private fun reference(file: ByteArray) = MotionPhotoLocator.locate(
    XmpPacket.find(file.copyOfRange(0, minOf(file.size, 256 * 1024))),
    file.copyOfRange(maxOf(0, file.size - 128 * 1024), file.size),
    file.size.toLong(),
  )

  private fun assertSameAsReal(file: ByteArray, found: MotionVideo.Found) {
    val real = reference(file)!!
    assertThat(found.range).isEqualTo(real.videoRange)
    assertThat(file.copyOfRange(found.range.start.toInt(), found.range.endExclusive.toInt())).isEqualTo(video)
  }

  private fun found(file: ByteArray): MotionVideo.Found = locate(file) as MotionVideo.Found

  @Test
  fun googleConIlCampo_ValeIlCampo() {
    val file = MotionTestFiles.google(video, declaredUs = 1_500_000L)
    val result = found(file)
    assertSameAsReal(file, result)
    assertThat(result.stillUs).isEqualTo(1_500_000L)
    assertThat(result.durationUs).isEqualTo(4_000_000L)
    assertThat(reference(file)!!.stillPresentationUs).isEqualTo(1_500_000L)
  }

  @Test
  fun e_V16_googleSenzaIlCampo_MetaDelVideo() {
    val file = MotionTestFiles.google(video, declaredUs = null)
    val result = found(file)
    assertSameAsReal(file, result)
    assertThat(reference(file)!!.stillPresentationUs).isNull()
    assertThat(result.stillUs).isEqualTo(2_000_000L)
  }

  @Test
  fun e_V16_googleConMenoUno_MetaDelVideo() {
    val file = MotionTestFiles.google(video, declaredUs = -1L)
    val result = found(file)
    assertSameAsReal(file, result)
    assertThat(result.stillUs).isEqualTo(2_000_000L)
  }

  @Test
  fun e_V16_googleOltreLaDurata_MetaDelVideo() {
    val file = MotionTestFiles.google(video, declaredUs = 9_000_000L)
    val result = found(file)
    assertSameAsReal(file, result)
    // Il localizzatore vero porta il campo cosi' com'e': e' il viewer, che conosce la durata, a scartarlo.
    assertThat(reference(file)!!.stillPresentationUs).isEqualTo(9_000_000L)
    assertThat(result.stillUs).isEqualTo(2_000_000L)
  }

  @Test
  fun googleConIstanteEsattamenteAllaFineDelVideo_VaBene() {
    val result = found(MotionTestFiles.google(video, declaredUs = 4_000_000L))
    assertThat(result.stillUs).isEqualTo(4_000_000L)
  }

  @Test
  fun microVideoConIlCampo_ValeIlCampo() {
    val file = MotionTestFiles.microVideo(video, declaredUs = 1_000_000L)
    val result = found(file)
    assertSameAsReal(file, result)
    assertThat(result.stillUs).isEqualTo(1_000_000L)
  }

  @Test
  fun microVideoSenzaIlCampo_MetaDelVideo() {
    val file = MotionTestFiles.microVideo(video, declaredUs = null)
    val result = found(file)
    assertSameAsReal(file, result)
    assertThat(result.stillUs).isEqualTo(2_000_000L)
  }

  @Test
  fun samsungSefSenzaXmp_MetaDelVideo() {
    val file = MotionTestFiles.samsungSef(video, declaredUs = null)
    val result = found(file)
    assertSameAsReal(file, result)
    assertThat(reference(file)!!.stillPresentationUs).isNull()
    assertThat(result.stillUs).isEqualTo(2_000_000L)
  }

  @Test
  fun samsungSefConXmp_VaLIstanteDellXmp() {
    val file = MotionTestFiles.samsungSef(video, declaredUs = 3_100_000L)
    val result = found(file)
    assertSameAsReal(file, result)
    assertThat(result.stillUs).isEqualTo(3_100_000L)
  }

  @Test
  fun e_M16_unHeicDiSamsungNonERiconosciuto_NienteAnimazioneNeBadge() {
    val file = MotionTestFiles.heicSamsung()
    assertThat(reference(file)).isNull()
    assertThat(locate(file)).isEqualTo(MotionVideo.Unrecognized)
  }

  @Test
  fun e_M16_dichiarataSenzaVideoEUnFileVuoto_NonSonoRiconosciute() {
    val flagOnly = MotionTestFiles.jpeg(
      "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
        "<rdf:Description xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" GCamera:MotionPhoto=\"1\"/></rdf:RDF></x:xmpmeta>",
    )
    assertThat(locate(flagOnly)).isEqualTo(MotionVideo.Unrecognized)
    assertThat(locate(ByteArray(0))).isEqualTo(MotionVideo.Unrecognized)
  }

  @Test
  fun senzaLaDurataLIstanteDichiaratoVale_senzaCampoNonSiSa() {
    val noDuration = MotionTestFiles.mp4WithoutDuration()
    val declared = found(MotionTestFiles.google(noDuration, declaredUs = 1_200_000L))
    assertThat(declared.stillUs).isEqualTo(1_200_000L)
    assertThat(declared.durationUs).isNull()
    val unknown = found(MotionTestFiles.google(noDuration, declaredUs = null))
    assertThat(unknown.stillUs).isNull()
    // Senza istante non c'e' animazione; il badge funziona lo stesso (D51).
    assertThat(unknown.openingClip).isNull()
  }

  @Test
  fun laDurataSiLeggeAncheConIlMoovInFondoAlVideo() {
    val withMoovLast = MotionTestFiles.mp4(durationMs = 6_000L, moovLast = true)
    val result = found(MotionTestFiles.google(withMoovLast, declaredUs = null))
    assertThat(result.durationUs).isEqualTo(6_000_000L)
    assertThat(result.stillUs).isEqualTo(3_000_000L)
  }

  @Test
  fun ilTrattoDApertura_UnVirgolaDueSecondiPrimaDellIstante() {
    assertThat(MotionInstant.openingClip(1_500_000L)).isEqualTo(PlaybackClip(startMs = 300L, endMs = 1_500L))
    assertThat(MotionInstant.openingClip(5_000_000L)).isEqualTo(PlaybackClip(startMs = 3_800L, endMs = 5_000L))
    // Vicino all'inizio il tratto parte da zero; vicinissimo, non c'e'.
    assertThat(MotionInstant.openingClip(800_000L)).isEqualTo(PlaybackClip(startMs = 0L, endMs = 800L))
    assertThat(MotionInstant.openingClip(50_000L)).isNull()
    assertThat(MotionInstant.openingClip(null)).isNull()
  }

  @Test
  fun lIstanteSiRisolveCosi() {
    assertThat(MotionInstant.resolve(1_000_000L, 4_000_000L)).isEqualTo(1_000_000L)
    assertThat(MotionInstant.resolve(0L, 4_000_000L)).isEqualTo(0L)
    assertThat(MotionInstant.resolve(null, 4_000_000L)).isEqualTo(2_000_000L)
    assertThat(MotionInstant.resolve(-1L, 4_000_000L)).isEqualTo(2_000_000L)
    assertThat(MotionInstant.resolve(4_000_001L, 4_000_000L)).isEqualTo(2_000_000L)
    assertThat(MotionInstant.resolve(2_500_000L, null)).isEqualTo(2_500_000L)
    assertThat(MotionInstant.resolve(null, null)).isNull()
    assertThat(MotionInstant.resolve(null, 0L)).isNull()
  }

  @Test
  fun unMp4RovinatoNonDaEccezioniNeDurata() = runBlocking<Unit> {
    val garbage = ByteArray(500) { (it * 7).toByte() }
    val read: suspend (Long, Int) -> ByteArray = { offset, length ->
      garbage.copyOfRange(offset.toInt().coerceAtMost(garbage.size), (offset + length).toInt().coerceAtMost(garbage.size))
    }
    assertThat(Mp4Duration.read(garbage.size.toLong(), read)).isNull()
    assertThat(Mp4Duration.read(0L, read)).isNull()
  }
}
