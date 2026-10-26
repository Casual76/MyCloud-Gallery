package dev.pampa.mycloudgallery.core.index.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.p3.Images
import dev.pampa.mycloudgallery.core.index.p3.TestFile
import dev.pampa.mycloudgallery.core.index.testing.FakeAiDoneClasses
import dev.pampa.mycloudgallery.core.index.testing.FakeAiIndexPublisher
import dev.pampa.mycloudgallery.core.index.testing.FakeAnalysisFetcher
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadSeries
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// "Fake fedeli con test di parita'" (§5.2, §4.3): i fake di P0 (testing/AiIndexFakes.kt) e le implementazioni vere di P2 passano la
// STESSA suite di contratto. Cosi' P5, P10 e P11, che provano il calcolo con i fake, provano il comportamento vero.

// --- Pubblicatore ------------------------------------------------------------------------------------------------------------

/** Il contratto di [AiIndexPublisher]: outbox, cancello, blocco permanente con le voci in attesa e ripubblicazione senza ricalcolo. */
abstract class AiIndexPublisherContract {

  @get:Rule val tmp = TemporaryFolder()

  interface Env {
    val share: String
    val publisher: AiIndexPublisher
    val gate: MapWriteGate

    /** La chiave di un file della share. */
    fun key(n: Int): ContentKey

    /** Cio' che chi legge vede pubblicato in [share]: per (chiave, classe) le impronte dei corpi. */
    suspend fun published(): Map<AiKey, Set<String>>
  }

  abstract fun env(dir: File): Env

  private fun entries(e: Env) = (1..3).map { AiEntry.Vector(e.key(it), AiBodies.vec(it)) } + AiEntry.Text(e.key(1), AiBodies.ocr("Via Roma"))

  @Test
  fun stageEFlushPubblicanoLeVoci_unaFlushVuotaNonFaNiente() = runTest {
    val e = env(tmp.newFolder())
    assertThat(e.publisher.flush(e.share, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.NothingPending)
    val entries = entries(e)
    e.publisher.stage(e.share, entries)
    assertThat(e.publisher.pending.value).isEqualTo(AiPendingCounts(4, 0))
    assertThat(e.publisher.flush(e.share, FlushReason.UNIT_FINISHED)).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(e.publisher.pending.value).isEqualTo(AiPendingCounts.EMPTY)
    assertThat(e.published()).isEqualTo(entries.associate { it.key to setOf(it.digest()) })
  }

  @Test
  fun unBloccoTemporaneoLasciaTuttoNellOutbox() = runTest {
    val e = env(tmp.newFolder())
    e.publisher.stage(e.share, entries(e))
    e.gate.block(e.share, WriteBlock.FROZEN)
    assertThat(e.publisher.flush(e.share, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Blocked(WriteBlock.FROZEN))
    assertThat(e.publisher.pending.value).isEqualTo(AiPendingCounts(4, 0))
    assertThat(e.publisher.releasePending(e.share)).isEqualTo(0)
    e.gate.block(e.share, null)
    assertThat(e.publisher.flush(e.share, FlushReason.UNIT_FINISHED)).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(e.published()).hasSize(4)
  }

  @Test
  fun unBloccoPermanenteMetteLeVociInAttesa_eSiPubblicanoDopoGliStessiCorpi() = runTest {
    val e = env(tmp.newFolder())
    val entries = entries(e)
    e.gate.block(e.share, WriteBlock.READ_ONLY_AREA)
    e.publisher.stage(e.share, entries)
    assertThat(e.publisher.flush(e.share, FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Blocked(WriteBlock.READ_ONLY_AREA))
    assertThat(e.publisher.pending.value).isEqualTo(AiPendingCounts(staged = 0, waitingForShare = 4))
    assertThat(e.publisher.releasePending(e.share)).isEqualTo(0)
    assertThat(e.published()).isEmpty()
    e.gate.block(e.share, null)
    assertThat(e.publisher.releasePending(e.share)).isEqualTo(4)
    assertThat(e.publisher.flush(e.share, FlushReason.UNIT_FINISHED)).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(e.published()).isEqualTo(entries.associate { it.key to setOf(it.digest()) })
  }
}

/** Il fake di P0. */
class FakeAiIndexPublisherContractTest : AiIndexPublisherContract() {
  override fun env(dir: File): Env = object : Env {
    override val share = "test-comune"
    override val gate = MapWriteGate()
    val fake = FakeAiIndexPublisher(gate)
    override val publisher: AiIndexPublisher = fake

    override fun key(n: Int): ContentKey = TestFile.random(share, "Foto/IMG_$n.jpg", kotlin.random.Random(n)).contentKey

    override suspend fun published(): Map<AiKey, Set<String>> {
      val out = HashMap<AiKey, MutableSet<String>>()
      fake.published.flatMap { it.entries }.forEach { out.getOrPut(it.key) { HashSet() } += it.digest() }
      return out
    }
  }
}

/** Il pubblicatore vero sul NAS finto, letto da un lettore qualunque. */
class OutboxAiIndexPublisherContractTest : AiIndexPublisherContract() {
  override fun env(dir: File): Env {
    val ai = AiWorld(dir)
    val device = ai.device()
    val files = (1..3).map { ai.file("test-comune", "Foto/IMG_$it.jpg") }
    return object : Env {
      override val share = "test-comune"
      override val gate = device.gate
      override val publisher: AiIndexPublisher = device.publisher()

      override fun key(n: Int): ContentKey = files[n - 1].contentKey

      override suspend fun published(): Map<AiKey, Set<String>> = aiReaderState(ai, share, device.id)
    }
  }
}

// --- Scaricamento delle anteprime ----------------------------------------------------------------------------------------------

/** Il contratto di [AnalysisFetcher]: solo blob attestati, solo le classi ammesse, fermo quando il buffer rifiuta. */
abstract class AnalysisFetcherContract {

  @get:Rule val tmp = TemporaryFolder()

  interface Env {
    val share: String
    val fetcher: AnalysisFetcher

    fun key(n: Int): ContentKey

    /** Un'anteprima di analisi della chiave [n] nella share, attestata o no; [ready] la rende leggibile. */
    fun given(n: Int, attested: Boolean)

    suspend fun ready()
  }

  abstract fun env(dir: File): Env

  @Test
  fun soloBlobAttestati_eLeChiaviSenzaBlobSonoMancanti() = runTest {
    val e = env(tmp.newFolder())
    e.given(1, attested = true)
    e.given(2, attested = true)
    e.given(3, attested = false)
    e.ready()
    val buffer = MemoryBuffer()
    val report = e.fetcher.fetch(e.share, (1..4).map { e.key(it) }, setOf(SegmentClass.ANALYSIS), buffer, Duration.INFINITE)
    assertThat(report.fetched).isEqualTo(2)
    assertThat(report.missing).isEqualTo(2)
    assertThat(report.stoppedEarly).isFalse()
    assertThat(buffer.blobs.keys.map { it.first }).containsExactly(e.key(1), e.key(2))
  }

  @Test
  fun unBufferPienoFermaLoScaricamento() = runTest {
    val e = env(tmp.newFolder())
    (1..3).forEach { e.given(it, attested = true) }
    e.ready()
    val report = e.fetcher.fetch(e.share, (1..3).map { e.key(it) }, setOf(SegmentClass.ANALYSIS), MemoryBuffer(capacity = 1), Duration.INFINITE)
    assertThat(report.fetched).isEqualTo(1)
    assertThat(report.stoppedEarly).isTrue()
  }

  @Test
  fun soloLeClassiAmmesse() {
    val e = env(tmp.newFolder())
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { e.fetcher.fetch(e.share, listOf(e.key(1)), setOf(SegmentClass.THUMB_M), MemoryBuffer(), Duration.INFINITE) }
    }
  }
}

class FakeAnalysisFetcherContractTest : AnalysisFetcherContract() {
  override fun env(dir: File): Env = object : Env {
    override val share = "test-comune"
    val fake = FakeAnalysisFetcher()
    override val fetcher: AnalysisFetcher = fake

    override fun key(n: Int): ContentKey = TestFile.random(share, "Foto/IMG_$n.jpg", kotlin.random.Random(n)).contentKey

    override fun given(n: Int, attested: Boolean) = fake.put(share, key(n), SegmentClass.ANALYSIS, Images.jpeg(marker = ByteArray(8) { n.toByte() }), attested)

    override suspend fun ready() = Unit
  }
}

class IndexAnalysisFetcherContractTest : AnalysisFetcherContract() {
  override fun env(dir: File): Env {
    val ai = AiWorld(dir)
    val preparer = ai.device("Tablet")
    val rogue = ai.device("Senza record")
    val reader = ai.device("Telefono")
    val files = (1..4).map { ai.file("test-comune", "Foto/IMG_$it.jpg") }
    val withRecord = ArrayList<TestFile>()
    val stray = ArrayList<TestFile>()
    return object : Env {
      override val share = "test-comune"
      override val fetcher: AnalysisFetcher = IndexAnalysisFetcher(reader.remote(), reader.replicaStore, {})

      override fun key(n: Int): ContentKey = files[n - 1].contentKey

      override fun given(n: Int, attested: Boolean) {
        (if (attested) withRecord else stray) += files[n - 1]
      }

      override suspend fun ready() {
        // Le anteprime attestate con il record del preparatore; quelle senza record le pubblica un altro dispositivo.
        val publisher = preparer.indexPublisher()
        publisher.stage(share, withRecord.map { MetaChange.Put(it.path, it.meta(setOf(SegmentClass.ANALYSIS))) }, withRecord.map { it.thumb(SegmentClass.ANALYSIS) })
        publisher.flush(share, FlushReason.UNIT_FINISHED)
        if (stray.isNotEmpty()) {
          val segment = IndexSegments.blobs(SegmentClass.ANALYSIS, stray.map { it.contentKey to Images.jpeg(marker = it.marker()) })
          val fs = ai.world.nas.share(share)
          fs.publishExclusive(NasLayout.segmentFile(rogue.id, "index", segment.ref.hash), segment.bytes)
          val state = ai.world.trust.state
          HeadSeries.publish(fs, state, rogue.base.signer, HeadKind.INDEX, 0, rogue.base.account) { v ->
            DeviceHead(state.registry.familyId, rogue.id, share, HeadKind.INDEX, v, state.registry.version, ai.clock.nowMillis(), listOf(ClassedSegment(SegmentClass.ANALYSIS, segment.ref)))
              .sign(rogue.base.signer)
          }
        }
        reader.sync()
      }
    }
  }
}

// --- Lavoro gia' fatto -----------------------------------------------------------------------------------------------------------

/** Il contratto di [AiDoneClasses]: per classe, con la ricetta chiesta o una piu' nuova. */
abstract class AiDoneClassesContract {

  @get:Rule val tmp = TemporaryFolder()

  interface Env {
    val share: String
    val done: AiDoneClasses

    fun key(n: Int): ContentKey

    /** Un altro dispositivo ha pubblicato (attestate) le classi [classes] della chiave [n] con la ricetta [recipe]. */
    suspend fun given(n: Int, recipe: Int, vararg classes: AiClass)
  }

  abstract fun env(dir: File): Env

  /**
   * Fatto per classe solo con la ricetta chiesta (revisione A, R16): una ricetta piu' nuova di quella corrente (anche la 255 di un
   * dispositivo compromesso) non conta come fatta; prima contava, e il contenuto restava "fatto" anche dopo un cambio di ricetta.
   */
  @Test
  fun fattoPerClasse_soloConLaRicettaChiesta() = runTest {
    val e = env(tmp.newFolder())
    e.given(1, 1, AiClass.VEC, AiClass.OCR)
    e.given(2, 2, AiClass.VEC)
    val keys = (1..3).map { e.key(it) }
    val atOne = e.done.done(e.share, keys, recipe = 1)
    assertThat(atOne[e.key(1)]).containsExactly(AiClass.VEC, AiClass.OCR)
    assertThat(atOne).doesNotContainKey(e.key(2))
    assertThat(atOne).doesNotContainKey(e.key(3))
    assertThat(e.done.done(e.share, keys, recipe = 2).keys).containsExactly(e.key(2))
    assertThat(e.done.done("test", keys, recipe = 1)).isEmpty()
  }
}

class FakeAiDoneClassesContractTest : AiDoneClassesContract() {
  override fun env(dir: File): Env = object : Env {
    override val share = "test-comune"
    val fake = FakeAiDoneClasses()
    override val done: AiDoneClasses = fake

    override fun key(n: Int): ContentKey = TestFile.random(share, "Foto/IMG_$n.jpg", kotlin.random.Random(n)).contentKey

    override suspend fun given(n: Int, recipe: Int, vararg classes: AiClass) = fake.mark(share, key(n), recipe, *classes)
  }
}

class AiPublishedClassesContractTest : AiDoneClassesContract() {
  override fun env(dir: File): Env {
    val ai = AiWorld(dir)
    val preparer = ai.device("Tablet")
    val other = ai.device("Altro indicizzatore")
    val reader = ai.device("Telefono")
    val files = (1..3).map { ai.file("test-comune", "Foto/IMG_$it.jpg") }
    runBlocking { preparer.prepare(files) }
    val published = AiPublishedClasses(reader.shareSync(), reader.aiStore) { 1 }
    return object : Env {
      override val share = "test-comune"
      override val done: AiDoneClasses = published

      override fun key(n: Int): ContentKey = files[n - 1].contentKey

      override suspend fun given(n: Int, recipe: Int, vararg classes: AiClass) {
        val file = files[n - 1]
        val entries = classes.map { if (it == AiClass.VEC) other.vector(file, recipe) else other.text(file, recipe = recipe) }
        val publisher = other.publisher()
        publisher.stage(share, entries)
        publisher.flush(share, FlushReason.UNIT_FINISHED)
        reader.sync()
      }
    }
  }
}
