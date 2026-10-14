package dev.pampa.mycloudgallery.core.prepare

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.prepare.codec.DecoderFamily
import dev.pampa.mycloudgallery.core.prepare.codec.VideoCodecs
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.photo.OutputPlan
import dev.pampa.mycloudgallery.core.prepare.spill.TempSpill
import dev.pampa.mycloudgallery.core.prepare.testing.FakeCodecs
import dev.pampa.mycloudgallery.core.prepare.video.MediaMetadataFrameGrabbers
import dev.pampa.mycloudgallery.core.prepare.video.VideoInput
import dev.pampa.mycloudgallery.core.trust.data.PrepFailure
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/** I pezzi piccoli della preparazione: tetti di memoria, taglie delle uscite, famiglia del decoder, temporanei, adattatore dei fotogrammi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmallPiecesTest {

  @get:Rule
  val tmp = TemporaryFolder()

  // --- PrepBudget ----------------------------------------------------------------------------------------------------------

  @Test
  fun iTettiDiMemoriaSeguonoLaMemoriaDellApp() {
    val s25 = PrepBudget(memoryClassMb = 256, cores = 8)
    assertThat(s25.parallelMedia).isEqualTo(2)
    assertThat(s25.heavyDecodes).isEqualTo(1)
    // Un quarto della memoria in ARGB_8888: 16,7 MP, 64 MiB.
    assertThat(s25.maxDecodedPixels).isEqualTo(16_777_216L)
    assertThat(s25.maxDecodedBytes).isEqualTo(64L * 1024 * 1024)
    // L'HQ (3,1 MP) e la copertina di un 8K (33 MP, ridotta a 2048) ci stanno con ampio margine.
    assertThat(2048L * 1536).isLessThan(s25.maxDecodedPixels / 4)
    assertThat(s25.inMemoryMaxBytes).isEqualTo(64L * 1024 * 1024)
    assertThat(s25.maxTempBytes).isEqualTo(512L * 1024 * 1024)
    assertThat(s25.largeFileBytes).isEqualTo(16L * 1024 * 1024)
    assertThat(s25.videoCacheBytes).isEqualTo(4 * 1024 * 1024)
    // Con due processori un media solo; con 128 MB la bitmap massima scende a 8 MP.
    assertThat(PrepBudget(256, 2).parallelMedia).isEqualTo(1)
    assertThat(PrepBudget(128, 4).maxDecodedPixels).isEqualTo(8_388_608L)
    assertThrows(IllegalArgumentException::class.java) { PrepBudget(0, 4) }
    assertThrows(IllegalArgumentException::class.java) { PrepBudget(256, 4, inMemoryMaxBytes = 100, maxTempBytes = 50) }
  }

  // --- OutputPlan -------------------------------------------------------------------------------------------------------------

  @Test
  fun leTaglieDelleUsciteSeguonoD12() {
    val spec = PrepSpec()
    fun size(cls: SegmentClass, w: Int, h: Int) = OutputPlan.sizeFor(cls, w, h, spec)
    // 4000x3000: S 267x200, M 533x400, analisi 1024x768, HQ 2048x1536.
    assertThat(size(SegmentClass.THUMB_S, 4000, 3000)).isEqualTo(267 to 200)
    assertThat(size(SegmentClass.THUMB_M, 4000, 3000)).isEqualTo(533 to 400)
    assertThat(size(SegmentClass.ANALYSIS, 4000, 3000)).isEqualTo(1024 to 768)
    assertThat(size(SegmentClass.HQ, 4000, 3000)).isEqualTo(2048 to 1536)
    // In piedi, lo stesso con i lati scambiati: il corto e' sempre la larghezza.
    assertThat(size(SegmentClass.THUMB_S, 3000, 4000)).isEqualTo(200 to 267)
    assertThat(size(SegmentClass.HQ, 3000, 4000)).isEqualTo(1536 to 2048)
    // 200 MP.
    assertThat(size(SegmentClass.HQ, 16320, 12240)).isEqualTo(2048 to 1536)
    // Mai ingrandire.
    for (cls in OutputPlan.IMAGE_ORDER) assertThat(size(cls, 100, 80)).isEqualTo(100 to 80)
    assertThat(size(SegmentClass.THUMB_M, 300, 900)).isEqualTo(300 to 900)
    // La proporzione dell'originale si conserva al pixel.
    val (w, h) = size(SegmentClass.ANALYSIS, 4032, 3024)
    assertThat(w.toDouble() / h).isWithin(0.005).of(4032.0 / 3024)
  }

  @Test
  fun iPrimiDueTetti_NonSiSuperanoMai() {
    val spec = PrepSpec()
    for ((w, h) in listOf(16320 to 12240, 100_000 to 300, 300 to 100_000, 4000 to 4000, 1 to 1, 7 to 90_000)) {
      for (cls in OutputPlan.IMAGE_ORDER) {
        val (ow, oh) = OutputPlan.sizeFor(cls, w, h, spec)
        assertWithMessage("$cls $w x $h").that(maxOf(ow, oh)).isAtMost(maxOf(w, h))
        assertWithMessage("$cls $w x $h").that(maxOf(ow, oh)).isAtMost(spec.hq)
        assertThat(ow).isAtLeast(1)
        assertThat(oh).isAtLeast(1)
      }
    }
  }

  // --- Famiglia del decoder e codec -----------------------------------------------------------------------------------------

  @Test
  fun laFamigliaDelDecoderEUnNomeValidoPerIlRecord() {
    assertThat(DecoderFamily.of("Qualcomm", "qcom")).isEqualTo("c2_qualcomm")
    assertThat(DecoderFamily.of("Samsung", "exynos2400")).isEqualTo("c2_samsung")
    assertThat(DecoderFamily.of("MediaTek Inc.", null)).isEqualTo("c2_mediatek_inc")
    assertThat(DecoderFamily.of(null, "mt6893")).isEqualTo("c2_mt6893")
    assertThat(DecoderFamily.of("unknown", "")).isEqualTo("c2_generic")
    assertThat(DecoderFamily.of("   ", null)).isEqualTo("c2_generic")
    assertThat(DecoderFamily.of("A".repeat(80), null).length).isAtMost(27)
    // E' accettato da PrepFailure (campo `fail.dec`).
    for (input in listOf("Qualcomm", "MediaTek Inc.", "a/b\\c", "Ünïcode", "A".repeat(80))) {
      PrepFailure("DECODER_MISSING", DecoderFamily.of(input, null))
    }
    // Quella di questo "dispositivo" (Robolectric) e' valida.
    PrepFailure("DECODER_MISSING", DecoderFamily.ofDevice())
  }

  @Test
  fun ilMarchioDelCodecDiceQualeDecoderServe() {
    assertThat(VideoCodecs.mimesFor("avc1")).containsExactly("video/avc")
    assertThat(VideoCodecs.mimesFor("hvc1")).containsExactly("video/hevc")
    assertThat(VideoCodecs.mimesFor("apv1")).containsExactly("video/apv")
    assertThat(VideoCodecs.mimesFor("sconosciuto")).isEmpty()
    // Un marchio che non conosciamo non e' mai "decoder mancante": si lascia provare al sistema.
    assertThat(VideoCodecs.isMissing("sconosciuto", FakeCodecs.none())).isFalse()
    assertThat(VideoCodecs.isMissing("apv1", FakeCodecs.none())).isTrue()
    assertThat(VideoCodecs.isMissing("apv1", FakeCodecs("VIDEO/APV"))).isFalse()
    // Dolby Vision decodificato come HEVC.
    assertThat(VideoCodecs.isMissing("dvh1", FakeCodecs("video/hevc"))).isFalse()
    assertThat(VideoCodecs.isMissing("dvh1", FakeCodecs.none())).isTrue()
  }

  // --- TempSpill -----------------------------------------------------------------------------------------------------------------

  @Test
  fun iTemporaneiHannoNomiDaUnHashEGliOrfaniSiTolgono() {
    val dir = File(tmp.root, "prep")
    val spill = TempSpill(dir)
    val a = spill.create()
    val b = spill.create()
    // Nomi derivati da un hash (LocalNames): 53 caratteri, mai testo del NAS, tutti diversi.
    assertThat(a.name).matches("m[0-9a-z]{52}")
    assertThat(a.name).isNotEqualTo(b.name)
    assertThat(a.parentFile).isEqualTo(dir)
    // Un orfano di un processo morto.
    File(dir, "orfano").writeText("x")
    assertThat(spill.liveCount()).isEqualTo(2)
    assertThat(spill.sweepStale()).isEqualTo(1)
    assertThat(a.exists() && b.exists()).isTrue()
    spill.release(a)
    spill.release(b)
    assertThat(dir.listFiles().orEmpty().toList()).isEmpty()
    assertThat(spill.usableBytes()).isGreaterThan(0)
  }

  // --- L'adattatore di MediaMetadataRetriever (sul "sistema" di Robolectric) -----------------------------------------------------------

  @Test
  fun ilLettoreDiFotogrammiLeggeDatiEFotogrammiDaUnFileLocale() {
    val file = tmp.newFile("v.mp4")
    ShadowMediaMetadataRetriever.addMetadata(file.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "90000")
    ShadowMediaMetadataRetriever.addMetadata(file.path, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1920")
    ShadowMediaMetadataRetriever.addMetadata(file.path, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "1080")
    ShadowMediaMetadataRetriever.addMetadata(file.path, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "90")
    val frame = TestMedia.quadrants(640, 360)
    ShadowMediaMetadataRetriever.addFrame(file.path, 1_000_000L, frame)

    MediaMetadataFrameGrabbers.open(VideoInput.LocalFile(file)).use { grabber ->
      assertThat(grabber.info.durationMs).isEqualTo(90_000)
      assertThat(grabber.info.width).isEqualTo(1920)
      assertThat(grabber.info.height).isEqualTo(1080)
      assertThat(grabber.info.rotation).isEqualTo(90)
      // L'istante c'e' (anche senza la versione scalata: il ripiego chiede il fotogramma intero), l'altro no.
      assertThat(grabber.frameAt(1_000, 1024)).isNotNull()
      assertThat(grabber.frameAt(5_000, 1024)).isNull()
    }
  }

  @Test
  fun ilLettoreDiFotogrammiLeggeDaUnaMediaDataSource() {
    val source = object : MediaDataSource() {
      override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = -1

      override fun getSize(): Long = 1000

      override fun close() = Unit
    }
    val ds = DataSource.toDataSource(source)
    ShadowMediaMetadataRetriever.addMetadata(ds, MediaMetadataRetriever.METADATA_KEY_DURATION, "")
    ShadowMediaMetadataRetriever.addMetadata(ds, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1280")
    ShadowMediaMetadataRetriever.addMetadata(ds, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "720")
    ShadowMediaMetadataRetriever.addScaledFrame(ds, 2_000_000L, 1024, 1024, Bitmap.createBitmap(1024, 576, Bitmap.Config.ARGB_8888))
    MediaMetadataFrameGrabbers.open(VideoInput.Source(source)).use { grabber ->
      // Durata vuota o illeggibile = ignota (null), non zero; nessuna rotazione dichiarata = 0.
      assertThat(grabber.info.durationMs).isNull()
      assertThat(grabber.info.width).isEqualTo(1280)
      assertThat(grabber.info.rotation).isEqualTo(0)
      // La versione scalata entra in un quadrato di 1024 (non oltre il video, 1280 di lato lungo).
      assertThat(grabber.frameAt(2_000, 1024)).isNotNull()
    }
  }

  @Test
  fun unContenitoreCheNonSiApreLanciaUnaRuntimeException_ECheLoCatturaIlPreparatore() {
    val source = object : MediaDataSource() {
      override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = -1

      override fun getSize(): Long = 1000

      override fun close() = Unit
    }
    ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(source), IllegalArgumentException("no"))
    assertThrows(RuntimeException::class.java) { MediaMetadataFrameGrabbers.open(VideoInput.Source(source)) }
  }
}
