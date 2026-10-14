package dev.pampa.mycloudgallery.core.prepare

import android.graphics.Bitmap
import android.media.MediaDataSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.prepare.codec.CodecAvailability
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.engine.DefaultPreparer
import dev.pampa.mycloudgallery.core.prepare.testing.FakeCodecs
import dev.pampa.mycloudgallery.core.prepare.testing.FakeVideoFrameGrabbers
import dev.pampa.mycloudgallery.core.prepare.video.VideoFrameGrabber
import dev.pampa.mycloudgallery.core.prepare.video.VideoFrameGrabberFactory
import dev.pampa.mycloudgallery.core.prepare.video.VideoInput
import dev.pampa.mycloudgallery.core.prepare.video.VideoStreamInfo
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.fixtures.PrepareFixtures
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * La preparazione dei VIDEO (§2.4, E-M7, E-M13): mai scaricati interi, l'APV senza decoder passa di mano, i fotogrammi
 * escono dritti. Il lettore di fotogrammi e' [SimulatedRetriever], un modello di `MediaMetadataRetriever` che legge dalla
 * stessa `MediaDataSource` come farebbe il sistema (testa, riquadri di primo livello fino al moov, moov intero, e per ogni
 * istante il fotogramma chiave piu' vicino piu' due campioni): conta i byte che il **nostro** codice chiede all'origine.
 * Quanto legge il sistema vero lo dice la prova sul telefono (F6, `RunResult.bytesRead`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoPreparerTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val block = 256 * 1024

  /** Cio' che fa il sistema leggendo un MP4 da una `MediaDataSource` (modello, non misura). */
  private class SimulatedRetriever(
    private val mp4: PrepareFixtures.VirtualMp4,
    private val durationMs: Long,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val rotation: Int = 0,
    private val frameSize: (Int) -> Pair<Int, Int> = { 1024 to 576 },
  ) : VideoFrameGrabberFactory {
    val opened = java.util.concurrent.atomic.AtomicInteger()
    val frameIndexes = java.util.concurrent.CopyOnWriteArrayList<Int>()

    override fun open(input: VideoInput): VideoFrameGrabber {
      opened.incrementAndGet()
      val source = (input as VideoInput.Source).dataSource
      // 1. L'inizio (il tipo del file).
      readFully(source, 0, 4096)
      // 2. I riquadri di primo livello, a salti, fino al moov.
      var position = 0L
      while (position < source.size) {
        val header = ByteArray(16)
        if (source.readAt(position, header, 0, 16) < 8) break
        var size = u32(header, 0)
        val type = String(header, 4, 4, Charsets.ISO_8859_1)
        if (size == 1L) size = (u32(header, 8) shl 32) or u32(header, 12)
        if (type == "moov") {
          // 3. L'indice intero.
          readFully(source, position, size)
          break
        }
        position += size
      }
      return object : VideoFrameGrabber {
        override val info = VideoStreamInfo(durationMs, width, height, rotation)

        override fun frameAt(timeMs: Long, maxSide: Int): Bitmap? {
          // 4. Il fotogramma chiave piu' vicino e due campioni dopo (il decoder ne vuole alcuni per produrre l'immagine).
          val index = mp4.closestSync(timeMs)
          frameIndexes += index
          for (i in index..minOf(index + 2, mp4.sampleCount() - 1)) readFully(source, mp4.sampleOffset(i), mp4.sampleSize(i).toLong())
          val (w, h) = frameSize(index)
          return TestMedia.marked(TestMedia.quadrants(w, h), index.toLong())
        }

        override fun close() = Unit
      }
    }

    private fun readFully(source: MediaDataSource, position: Long, length: Long) {
      val buffer = ByteArray(64 * 1024)
      var done = 0L
      while (done < length) {
        val n = source.readAt(position + done, buffer, 0, minOf(buffer.size.toLong(), length - done).toInt())
        if (n <= 0) break
        done += n
      }
    }

    private fun u32(b: ByteArray, i: Int): Long =
      ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
  }

  private fun preparer(
    frames: VideoFrameGrabberFactory,
    codecs: CodecAvailability = FakeCodecs.everything(),
    budget: PrepBudget = PrepBudget(256, 8),
  ) = testPreparer(budget, File(tmp.root, "prep"), codecs, frames = frames)

  private fun spec(configure: PrepareFixtures.VideoSpec.() -> Unit = {}) = PrepareFixtures.VideoSpec().apply(configure)

  private fun PrepResult.done(): PrepResult.Done {
    assertThat(this).isInstanceOf(PrepResult.Done::class.java)
    return this as PrepResult.Done
  }

  private fun PrepResult.Done.frames(): PrepOutput.Frames = outputs.filterIsInstance<PrepOutput.Frames>().single()

  private fun PrepResult.Done.image(cls: SegmentClass): PrepOutput.Image = outputs.filterIsInstance<PrepOutput.Image>().first { it.cls == cls }

  private suspend fun prepare(bytes: MediaBytes, frames: VideoFrameGrabberFactory, codecs: CodecAvailability = FakeCodecs.everything()) =
    preparer(frames, codecs).prepare(PrepareSource.Bytes(bytes, MediaKind.VIDEO, "video/mp4"), PrepSpec())

  // --- Mai scaricati ---------------------------------------------------------------------------------------------------

  @Test
  fun unVideoDa90SecondiConLIndiceInCodaSiPreparaLeggendoIndiceEFotogrammi() = runTest(timeout = 120.seconds) {
    val mp4 = PrepareFixtures.video(spec())
    val bytes = VirtualBytes(mp4)
    val retriever = SimulatedRetriever(mp4, 90_000)
    val done = prepare(bytes, retriever).done()

    // Copertina piu' un fotogramma chiave ogni 30 s: 3 fette da 30 s, quindi 4 fotogrammi.
    val frames = done.frames().frames
    assertThat(frames).hasSize(4)
    assertThat(frames.map { it.atMs }).isInOrder()
    assertThat(frames.first().atMs).isEqualTo(1_000)

    // Il budget del file: indice + un fotogramma chiave (e due campioni) per istante, ognuno arrotondato ai blocchi, piu'
    // il blocco iniziale. Mai il file.
    val sample = retriever.frameIndexes.distinct().sumOf { i -> (i..minOf(i + 2, mp4.sampleCount() - 1)).sumOf { mp4.sampleSize(it).toLong() } }
    val budget = mp4.moovSize + sample + (2 + 2L * retriever.frameIndexes.distinct().size) * block + 2 * block
    assertWithMessage("byte letti ${bytes.bytesRead} su un file da ${mp4.size()}").that(bytes.bytesRead).isAtMost(budget)
    assertThat(bytes.bytesRead).isLessThan(mp4.size() / 2)
    // Nessuna lettura oltre il necessario: lo stesso blocco non e' stato chiesto due volte.
    assertThat(bytes.reads.map { it.first }.toSet().size).isEqualTo(bytes.reads.size)
  }

  @Test
  fun unVideoDa9GigabyteCostaQuantoUnoDa20Megabyte() = runTest(timeout = 300.seconds) {
    val mp4 = PrepareFixtures.video(spec {
      durationMs = 7_200_000
      keyframeBytes = 1_000_000
      deltaFrameBytes = 27_000
    })
    assertThat(mp4.size()).isGreaterThan(9_000_000_000L)
    val bytes = VirtualBytes(mp4)
    val retriever = SimulatedRetriever(mp4, 7_200_000)
    val done = prepare(bytes, retriever).done()

    // Due ore: otto fotogrammi chiave piu' la copertina (E-A9: il massimo).
    assertThat(done.frames().frames).hasSize(9)
    val distinct = retriever.frameIndexes.distinct().size
    // Indice (2,6 MB) + nove fotogrammi chiave da 1 MB: una dozzina di megabyte su 9,3 GB.
    assertWithMessage("byte letti ${bytes.bytesRead}").that(bytes.bytesRead).isAtMost(mp4.moovSize + distinct * (1_000_000L + 2 * 27_000 + 2L * block) + 4L * block)
    assertThat(bytes.bytesRead).isLessThan(20L * 1024 * 1024)
    assertThat(bytes.bytesRead.toDouble() / mp4.size()).isLessThan(0.002)
    println("video da ${mp4.size() / 1_000_000} MB: letti ${bytes.bytesRead / 1000} KB in ${bytes.reads.size} letture (${"%.3f".format(bytes.bytesRead * 100.0 / mp4.size())}%)")
  }

  @Test
  fun conLIndiceInTestaLoStessoVideoCostaLoStesso() = runTest(timeout = 120.seconds) {
    val mp4 = PrepareFixtures.video(spec { moovLast = false })
    val bytes = VirtualBytes(mp4)
    val done = prepare(bytes, SimulatedRetriever(mp4, 90_000)).done()
    assertThat(done.frames().frames).hasSize(4)
    assertThat(bytes.bytesRead).isLessThan(mp4.size() / 2)
  }

  // --- Uscite -------------------------------------------------------------------------------------------------------------

  @Test
  fun leUsciteDiUnVideoSonoCopertinaMiniatureAnalisiEdHqPiuIFotogrammi() = runTest(timeout = 120.seconds) {
    val mp4 = PrepareFixtures.video(spec())
    val retriever = SimulatedRetriever(mp4, 90_000, frameSize = { 1024 to 576 })
    val done = prepare(VirtualBytes(mp4), retriever).done()

    // Dalla copertina di 1024x576 (il sistema non ingrandisce): S e M non possono superare la copertina.
    assertThat(done.image(SegmentClass.THUMB_S).let { it.width to it.height }).isEqualTo(356 to 200)
    assertThat(done.image(SegmentClass.THUMB_M).let { it.width to it.height }).isEqualTo(711 to 400)
    assertThat(done.image(SegmentClass.ANALYSIS).let { it.width to it.height }).isEqualTo(1024 to 576)
    assertThat(done.image(SegmentClass.HQ).let { it.width to it.height }).isEqualTo(1024 to 576)
    // Il video: 1920x1080 per 90 s, dai dati del contenitore.
    assertThat(done.facts!!.width to done.facts!!.height).isEqualTo(1920 to 1080)
    assertThat(done.facts!!.durationMs).isEqualTo(90_000)
    assertThat(done.fullHash).isNull()
    assertThat(done.thumbHash).isNotNull()

    // L'impronta rapida e' quella dei byte del file (testa e coda da 64 KiB).
    val whole = mp4.toBytes()
    assertThat(done.quickHash).isEqualTo(QuickHashAlgorithm.ofContent(whole))

    // I fotogrammi: JPEG con il lato lungo a 1024, dentro il tetto del corpo `frames`.
    val frames = done.frames().frames
    var total = 1
    for (frame in frames) {
      assertThat(frame.jpeg[0]).isEqualTo(0xFF.toByte())
      assertThat(frame.jpeg[1]).isEqualTo(0xD8.toByte())
      assertThat(TestMedia.decode(frame.jpeg).let { maxOf(it.width, it.height) }).isEqualTo(1024)
      total += 8 + frame.jpeg.size
    }
    assertThat(total).isAtMost(TrustLimits.MAX_FRAMES_BODY_BYTES)
    assertThat(frames.size).isAtMost(TrustLimits.MAX_FRAMES_PER_MEDIA)
  }

  @Test
  fun seMancanoSoloLeMiniatureNonSiPrendonoIFotogrammiChiave() = runTest {
    val mp4 = PrepareFixtures.video(spec())
    val retriever = SimulatedRetriever(mp4, 90_000)
    val result = preparer(retriever).prepare(
      PrepareSource.Bytes(VirtualBytes(mp4), MediaKind.VIDEO, null),
      PrepSpec(classes = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M)),
    ).done()
    assertThat(result.outputs.map { it.cls }).containsExactly(SegmentClass.THUMB_S, SegmentClass.THUMB_M).inOrder()
    assertThat(retriever.frameIndexes).hasSize(1)
  }

  @Test
  fun seManca_SoloFrames_SiLeggonoICopertinaEFotogrammiSenzaMiniature() = runTest {
    val mp4 = PrepareFixtures.video(spec())
    val result = preparer(SimulatedRetriever(mp4, 90_000)).prepare(
      PrepareSource.Bytes(VirtualBytes(mp4), MediaKind.VIDEO, null),
      PrepSpec(classes = setOf(SegmentClass.FRAMES)),
    ).done()
    assertThat(result.outputs.map { it.cls }).containsExactly(SegmentClass.FRAMES)
    assertThat(result.thumbHash).isNotNull()
  }

  // --- Rotazione ---------------------------------------------------------------------------------------------------------------

  @Test
  fun unVideoVerticaleEsceVerticaleSiaSeIlSistemaLoRuotaSiaSeNo() = runTest(timeout = 120.seconds) {
    val mp4 = PrepareFixtures.video(spec { rotation = 90; durationMs = 20_000 })
    for ((label, frameSize) in listOf("fotogramma non ruotato" to { _: Int -> 1024 to 576 }, "fotogramma gia' ruotato" to { _: Int -> 576 to 1024 })) {
      val retriever = SimulatedRetriever(mp4, 20_000, width = 1920, height = 1080, rotation = 90, frameSize = frameSize)
      val done = preparer(retriever).prepare(PrepareSource.Bytes(VirtualBytes(mp4), MediaKind.VIDEO, null), PrepSpec()).done()
      for (cls in listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)) {
        val out = done.image(cls)
        assertWithMessage("$label $cls").that(out.height).isGreaterThan(out.width)
      }
      for (frame in done.frames().frames) {
        val bitmap = TestMedia.decode(frame.jpeg)
        assertWithMessage(label).that(bitmap.height).isGreaterThan(bitmap.width)
        // I quadranti del fotogramma di prova (rosso in alto a sinistra) ruotati di 90 gradi orari: il rosso e' in alto a destra.
        if (label == "fotogramma non ruotato") {
          assertThat(TestMedia.checkOrientation(bitmap, 6)).isNull()
        }
      }
      assertThat(done.facts!!.width to done.facts!!.height).isEqualTo(1080 to 1920)
    }
  }

  // --- DECODER_MISSING e passaggio di mano (D14, E-M13) -------------------------------------------------------------

  @Test
  fun unApvSenzaDecoderApvPassaDiManoSenzaLeggereAltroCheLIndice() = runTest {
    val mp4 = PrepareFixtures.video(spec { sampleEntry = "apv1"; durationMs = 6_000; width = 1280; height = 720 })
    val bytes = VirtualBytes(mp4)
    val retriever = SimulatedRetriever(mp4, 6_000)
    val result = prepare(bytes, retriever, FakeCodecs.everything())
    assertThat(result).isEqualTo(PrepResult.Failed(PrepFailCode.DECODER_MISSING, retryable = false, decoderFamily = "c2_test"))
    assertThat((result as PrepResult.Failed).decoderMissing).isTrue()
    // Non si e' nemmeno chiesto un fotogramma al sistema, e dell'APV si e' letto solo l'indice.
    assertThat(retriever.opened.get()).isEqualTo(0)
    assertThat(bytes.bytesRead).isAtMost(mp4.size())
    assertThat(bytes.bytesRead).isAtMost(mp4.moovSize + 3L * block)
  }

  @Test
  fun lStessoApvSiPreparaOveIlDecoderApvCe() = runTest {
    val mp4 = PrepareFixtures.video(spec { sampleEntry = "apv1"; durationMs = 6_000 })
    val retriever = SimulatedRetriever(mp4, 6_000)
    val done = prepare(VirtualBytes(mp4), retriever, FakeCodecs("video/apv")).done()
    assertThat(done.frames().frames).isNotEmpty()
    assertThat(retriever.opened.get()).isEqualTo(1)
  }

  @Test
  fun ilCodecSiLeggeDallIndiceOgniFormatoHaIlSuoDecoder() = runTest {
    for ((entry, mime) in listOf("hvc1" to "video/hevc", "av01" to "video/av01", "vp09" to "video/x-vnd.on2.vp9", "avc1" to "video/avc")) {
      val mp4 = PrepareFixtures.video(spec { sampleEntry = entry; durationMs = 6_000 })
      val without = prepare(VirtualBytes(mp4), SimulatedRetriever(mp4, 6_000), FakeCodecs.none())
      assertWithMessage(entry).that(without).isEqualTo(PrepResult.Failed(PrepFailCode.DECODER_MISSING, retryable = false, decoderFamily = "c2_test"))
      val with = prepare(VirtualBytes(mp4), SimulatedRetriever(mp4, 6_000), FakeCodecs(mime))
      assertWithMessage(entry).that(with).isInstanceOf(PrepResult.Done::class.java)
    }
  }

  // --- Rovinati ----------------------------------------------------------------------------------------------------------------

  @Test
  fun unaRegistrazioneInterrottaOUnIndiceTroncatoNonSiAprono() = runTest {
    val mp4 = PrepareFixtures.video(spec { durationMs = 6_000 })
    val whole = mp4.toBytes()
    val retriever = SimulatedRetriever(mp4, 6_000)
    val noMoov = prepare(CountingBytes(whole.copyOf(mp4.moovOffset.toInt())), retriever)
    assertThat(noMoov).isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
    val truncated = prepare(CountingBytes(whole.copyOf((mp4.moovOffset + mp4.moovSize - 40).toInt())), retriever)
    assertThat(truncated).isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
    assertThat(retriever.opened.get()).isEqualTo(0)
  }

  @Test
  fun unVideoFalsoEZeroByteSonoFileRovinati() = runTest {
    val grabbers = FakeVideoFrameGrabbers(VideoStreamInfo(null, null, null, 0)) { _, _ -> null }
    assertThat(prepare(CountingBytes("non sono un video".toByteArray()), grabbers))
      .isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
    assertThat(prepare(CountingBytes(ByteArray(0)), grabbers))
      .isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
  }

  @Test
  fun seIlSistemaNonDaNessunFotogrammaIlVideoENonLeggibile() = runTest {
    val mp4 = PrepareFixtures.video(spec { durationMs = 6_000 })
    val nothing = FakeVideoFrameGrabbers(VideoStreamInfo(6_000, 1920, 1080, 0)) { _, _ -> null }
    assertThat(prepare(VirtualBytes(mp4), nothing)).isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
    val cannotOpen = FakeVideoFrameGrabbers(VideoStreamInfo(null, null, null, 0)) { _, _ -> null }.also { it.openFailure = IllegalArgumentException("x") }
    assertThat(prepare(VirtualBytes(mp4), cannotOpen)).isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
  }

  @Test
  fun seIlPrimoFotogrammaNonSiDecodificaSiUsaIlSuccessivo() = runTest {
    val mp4 = PrepareFixtures.video(spec { durationMs = 75_000 })
    val grabbers = FakeVideoFrameGrabbers(VideoStreamInfo(75_000, 1920, 1080, 0)) { t, _ -> if (t < 5_000) null else TestMedia.marked(TestMedia.quadrants(640, 360), t) }
    val done = prepare(VirtualBytes(mp4), grabbers).done()
    assertThat(done.frames().frames.map { it.atMs }).containsExactly(12_500, 37_500, 62_500).inOrder()
  }

  @Test
  fun IlNasCheSiFermaMentreSiLeggeNonEUnVideoRovinato() = runTest {
    val mp4 = PrepareFixtures.video(spec())
    val real = VirtualBytes(mp4)
    // Dopo l'indice e il primo fotogramma l'origine non risponde piu': il sistema vede solo errori di lettura.
    var reads = 0
    val flaky = object : MediaBytes {
      override val size: Long get() = real.size

      override suspend fun read(range: LongRange): ByteArray {
        if (++reads > 6) throw IOException("persa")
        return real.read(range)
      }

      override fun dataSource(): MediaDataSource = throw UnsupportedOperationException()
    }
    val result = prepare(flaky, SimulatedRetriever(mp4, 90_000))
    // La lettura che fallisce dentro il "sistema" lo fa lanciare: da NAS irraggiungibile, senza contare il tentativo.
    assertThat(result).isInstanceOf(PrepResult.Failed::class.java)
    assertThat((result as PrepResult.Failed).code).isAnyOf(PrepFailCode.NAS_UNREACHABLE, PrepFailCode.DECODE_FAILED)
  }

  @Test
  fun annullareIlLavoroFermaLeLettureDelSistema() = runTest(timeout = 60.seconds) {
    val mp4 = PrepareFixtures.video(spec())
    val real = VirtualBytes(mp4)
    val hanging = object : MediaBytes {
      override val size: Long get() = real.size

      override suspend fun read(range: LongRange): ByteArray = if (range.first < mp4.moovOffset) real.read(range) else awaitCancellation()

      override fun dataSource(): MediaDataSource = throw UnsupportedOperationException()
    }
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      prepare(hanging, SimulatedRetriever(mp4, 90_000))
    }
    job.cancel()
    job.join()
    assertThat(job.isCancelled).isTrue()
  }

  // --- Sorgente locale ---------------------------------------------------------------------------------------------------------

  @Test
  fun unVideoLocaleSiApreDalPercorsoSenzaCopiarlo() = runTest(timeout = 120.seconds) {
    val mp4 = PrepareFixtures.video(spec { durationMs = 40_000; keyframeBytes = 20_000; deltaFrameBytes = 2_000 })
    val file = File(tmp.root, "locale.mp4").also { it.writeBytes(mp4.toBytes()) }
    val seen = mutableListOf<VideoInput>()
    val grabbers = FakeVideoFrameGrabbers(VideoStreamInfo(40_000, 1920, 1080, 0)) { t, _ -> TestMedia.marked(TestMedia.quadrants(640, 360), t) }
    val wrapper = VideoFrameGrabberFactory { input ->
      seen += input
      grabbers.open(input)
    }
    val done = preparer(wrapper).prepare(PrepareSource.Local(file, MediaKind.VIDEO, null), PrepSpec()).done()
    assertThat(seen.single()).isInstanceOf(VideoInput.LocalFile::class.java)
    assertThat((seen.single() as VideoInput.LocalFile).file).isEqualTo(file)
    assertThat(done.frames().frames).isNotEmpty()
    assertThat(done.quickHash).isEqualTo(QuickHashAlgorithm.ofContent(file.readBytes()))
    assertThat(File(tmp.root, "prep").listFiles().orEmpty().toList()).isEmpty()
  }
}
