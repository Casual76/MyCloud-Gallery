package dev.pampa.mycloudgallery.core.prepare

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.engine.DefaultPreparer
import dev.pampa.mycloudgallery.core.prepare.engine.PrepProbe
import dev.pampa.mycloudgallery.core.prepare.testing.FakeCodecs
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * La memoria con 200 MP sotto i tetti di `PrepBudget` (§2.4, E-M1, E-M2, E-M4) e i tempi per media su JVM. I numeri dei tempi
 * finiscono in `core-prepare/build/prep-timings.txt` (e nel report del pacchetto): sono della JVM di sviluppo, non dell'S25; la
 * misura sul telefono e' la prova F6.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryAndTimingTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val tempDir: File get() = File(tmp.root, "prep")

  private fun preparer(budget: PrepBudget, probe: PrepProbe = PrepProbe.NONE) =
    testPreparer(budget, tempDir, FakeCodecs.everything(), probe)

  /** Il JPEG da 200 MP delle fixture (circa 70 MB), generato una volta sola nella cartella di cache del modulo. */
  private fun bigJpeg(): File {
    val dir = File(System.getProperty("mcg.fixtures.cache") ?: "build/fixtures-cache")
    val file = File(dir, "grande-200mp.jpg")
    if (!file.isFile || file.length() < 10_000_000) {
      dir.mkdirs()
      val exif = ExifWriter.Spec().apply {
        make = "samsung"
        model = "SM-S931B"
        dateOriginal = "2024:03:12 10:15:30"
        offsetOriginal = "+01:00"
      }
      val partial = File(dir, "grande-200mp.jpg.partial")
      ImageFixtures.writeLargeJpeg(partial, 16320, 12240, exif)
      check(partial.renameTo(file)) { "Non riesco a mettere a posto il JPEG da 200 MP." }
    }
    return file
  }

  private fun sha256Of(file: File): Sha256 {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(1 shl 20)
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
      }
    }
    return Sha256(digest.digest().joinToString("") { "%02x".format(it) })
  }

  private fun PrepResult.done(): PrepResult.Done {
    assertThat(this).isInstanceOf(PrepResult.Done::class.java)
    return this as PrepResult.Done
  }

  private fun PrepResult.Done.image(cls: SegmentClass): PrepOutput.Image = outputs.filterIsInstance<PrepOutput.Image>().first { it.cls == cls }

  private fun log(line: String) {
    println(line)
    File("build").mkdirs()
    File("build/prep-timings.txt").appendText(line + "\n")
  }

  // --- 200 MP ---------------------------------------------------------------------------------------------------------------

  @Test
  fun unJpegDa200MegapixelSiPreparaDentroIlTettoDiMemoria() = runTest(timeout = 600.seconds) {
    val file = bigJpeg()
    val budget = PrepBudget(memoryClassMb = 256, cores = 8)
    val peak = AtomicLong()
    val bytes = FileBytes(file)
    val usedBefore = usedHeap()
    val started = System.nanoTime()
    val done = preparer(budget) { peak.set(it) }.prepare(PrepareSource.Bytes(bytes, MediaKind.PHOTO, "image/jpeg"), PrepSpec()).done()
    val elapsed = (System.nanoTime() - started) / 1_000_000

    // Uscite: 16320x12240 → 2048x1536 (HQ), 1024x768, 533x400, 267x200; i metadati sono quelli veri dell'originale.
    assertThat(done.image(SegmentClass.HQ).let { it.width to it.height }).isEqualTo(2048 to 1536)
    assertThat(done.image(SegmentClass.ANALYSIS).let { it.width to it.height }).isEqualTo(1024 to 768)
    assertThat(done.image(SegmentClass.THUMB_M).let { it.width to it.height }).isEqualTo(533 to 400)
    assertThat(done.image(SegmentClass.THUMB_S).let { it.width to it.height }).isEqualTo(267 to 200)
    assertThat(done.facts!!.width to done.facts!!.height).isEqualTo(16320 to 12240)
    assertThat(done.fullHash).isEqualTo(sha256Of(file))
    // Letto una volta sola: ogni byte del file, nessuno di piu'.
    assertThat(bytes.bytesRead).isEqualTo(file.length())

    // Memoria: la preparazione non alloca mai piu' di tre bitmap da HQ insieme, e sta sotto il tetto di PrepBudget.
    val hqBytes = 2048L * 1536 * 4
    assertWithMessage("picco delle bitmap ${peak.get() / 1024} KiB").that(peak.get()).isAtMost(budget.maxDecodedBytes)
    assertThat(peak.get()).isAtMost(3 * hqBytes)
    // E l'heap Java non e' cresciuto in modo apprezzabile (il file passa a blocchi da 1 MiB, mai intero in un array).
    val grown = usedHeap() - usedBefore
    assertThat(grown).isLessThan(160L * 1024 * 1024)
    // Il temporaneo (il file e' oltre 64 MiB se pesa piu' di 67 MB) non resta.
    assertThat(tempDir.listFiles().orEmpty().toList()).isEmpty()
    log("200 MP (${file.length() / 1_000_000} MB): ${elapsed} ms, picco bitmap ${peak.get() / 1024} KiB (tetto ${budget.maxDecodedBytes / 1024} KiB), heap +${grown / 1024 / 1024} MiB")
  }

  @Test
  fun conPocaMemoriaLHqSiRiduceENonSiSfora() = runTest(timeout = 600.seconds) {
    val file = bigJpeg()
    // 16 MB di memoria dell'app: un MP per bitmap. L'HQ non puo' essere 2048x1536 (3,1 MP): esce piu' piccola, ma esce.
    val budget = PrepBudget(memoryClassMb = 16, cores = 4)
    val peak = AtomicLong()
    val done = preparer(budget) { peak.set(it) }.prepare(PrepareSource.Local(file, MediaKind.PHOTO, null), PrepSpec()).done()
    val hq = done.image(SegmentClass.HQ)
    assertThat(hq.width.toLong() * hq.height).isAtMost(budget.maxDecodedPixels)
    assertThat(hq.width.toDouble() / hq.height).isWithin(0.01).of(16320.0 / 12240)
    assertThat(peak.get()).isAtMost(2 * budget.maxDecodedBytes)
    // Le altre uscite non dipendono dalla memoria: l'analisi resta a 1024 se ci sta, altrimenti a quanto ha l'HQ.
    assertThat(maxOf(done.image(SegmentClass.ANALYSIS).width, done.image(SegmentClass.ANALYSIS).height)).isAtMost(1024)
    assertThat(done.image(SegmentClass.THUMB_S).height).isEqualTo(200)
    assertThat(done.fullHash).isEqualTo(sha256Of(file))
    log("200 MP con 16 MB: HQ ${hq.width}x${hq.height}, picco bitmap ${peak.get() / 1024} KiB (tetto ${budget.maxDecodedBytes / 1024} KiB)")
  }

  @Test
  fun oltreIPixelCheSiAccettanoLaFotoESegnaposto() = runTest(timeout = 600.seconds) {
    val file = bigJpeg()
    val budget = PrepBudget(memoryClassMb = 256, cores = 8, maxSourcePixels = 100_000_000L)
    val result = preparer(budget).prepare(PrepareSource.Bytes(FileBytes(file), MediaKind.PHOTO, null), PrepSpec())
    assertThat(result).isEqualTo(PrepResult.Failed(PrepFailCode.TOO_LARGE, retryable = false))
    assertThat(tempDir.listFiles().orEmpty().toList()).isEmpty()
  }

  @Test
  fun dueFotoDa200MegapixelInsiemeNonSiAccavallanoEFinisconoTutteDue() = runTest(timeout = 900.seconds) {
    val file = bigJpeg()
    val preparer = preparer(PrepBudget(256, 8))
    val results = listOf(
      async { preparer.prepare(PrepareSource.Bytes(FileBytes(file), MediaKind.PHOTO, null), PrepSpec(classes = setOf(SegmentClass.THUMB_S, SegmentClass.HQ))) },
      async { preparer.prepare(PrepareSource.Bytes(FileBytes(file), MediaKind.PHOTO, null), PrepSpec(classes = setOf(SegmentClass.THUMB_S, SegmentClass.HQ))) },
    ).awaitAll()
    for (result in results) assertThat(result.done().image(SegmentClass.HQ).width).isEqualTo(2048)
    assertThat(tempDir.listFiles().orEmpty().toList()).isEmpty()
  }

  private fun usedHeap(): Long {
    System.gc()
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
  }

  // --- Tempi per media ------------------------------------------------------------------------------------------------------

  /** Mediana dei tempi di [runs] preparazioni dopo una di riscaldamento. */
  private suspend fun timed(bytes: ByteArray, kind: MediaKind, runs: Int = 3, spec: PrepSpec = PrepSpec()): Pair<Long, PrepResult.Done> {
    val p = preparer(PrepBudget(256, 8))
    p.prepare(PrepareSource.Bytes(CountingBytes(bytes), kind, null), spec)
    val samples = ArrayList<Long>()
    var last: PrepResult.Done? = null
    repeat(runs) {
      val started = System.nanoTime()
      last = p.prepare(PrepareSource.Bytes(CountingBytes(bytes), kind, null), spec).done()
      samples += (System.nanoTime() - started) / 1_000_000
    }
    return samples.sorted()[samples.size / 2] to last!!
  }

  @Test
  fun iTempiPerMediaDelleFotoTipiche() = runTest(timeout = 600.seconds) {
    val cases = listOf(
      "JPEG 12 MP (4000x3000, 'da telefono')" to TestMedia.jpeg(TestMedia.photoLike(4000, 3000), 90),
      "JPEG 12 MP con grana (caso peggiore per i codificatori)" to TestMedia.jpeg(TestMedia.noisy(4000, 3000), 90),
      "JPEG 50 MP (8160x6120)" to TestMedia.jpeg(TestMedia.photoLike(8160, 6120), 90),
      "PNG screenshot 1080x2400" to TestMedia.png(TestMedia.photoLike(1080, 2400)),
    )
    for ((label, bytes) in cases) {
      val (median, done) = timed(bytes, MediaKind.PHOTO)
      val total = done.outputs.filterIsInstance<PrepOutput.Image>().sumOf { it.bytes.size }
      val sizes = done.outputs.filterIsInstance<PrepOutput.Image>().joinToString(", ") { "${it.cls.wire} ${it.bytes.size / 1000} KB" }
      log("$label: ${bytes.size / 1000} KB in ${median} ms; uscite ${total / 1000} KB ($sizes)")
      assertThat(median).isLessThan(30_000)
    }
  }
}
