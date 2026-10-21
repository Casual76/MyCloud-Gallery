package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.testing.FakeAiBuffer
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.estimate.AiComputeCosts
import dev.pampa.mycloudgallery.core.rules.estimate.BacklogFacts
import dev.pampa.mycloudgallery.core.rules.estimate.DeviceMeasures
import dev.pampa.mycloudgallery.core.rules.estimate.ReplicaDepth
import dev.pampa.mycloudgallery.core.rules.run.BatchResult
import dev.pampa.mycloudgallery.core.rules.run.StageHalt
import dev.pampa.mycloudgallery.core.rules.run.StageScope
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** I pezzi del calcolo: fotogrammi nel buffer, la porta della preparazione, le regole di `StagedRun`, le stime dal backlog vero. */
class ComputePiecesTest {
  private fun key(n: Int) = ContentKey(n.toString(16).padStart(64, '0'))

  @Test
  fun fotogrammi_andataERitornoETettiDelFormato() {
    val frames = (0 until 9).map { VideoFrame(it * 30_000, "jpeg $it".toByteArray()) }
    val bytes = FramesBlob.encode(frames)
    val back = FramesBlob.split(bytes)!!
    assertThat(back.map { it.atMs }).isEqualTo(frames.map { it.atMs })
    assertThat(back.map { String(it.jpeg) }).isEqualTo(frames.map { String(it.jpeg) })
    assertThat(FramesBlob.split(bytes.copyOf(bytes.size - 1))).isNull() // troncato
    assertThat(FramesBlob.split(bytes + 0)).isNull() // byte in piu'
    assertThat(FramesBlob.split(byteArrayOf(10))).isNull() // troppi fotogrammi dichiarati
    val huge = bytes.copyOf().also { it[5] = 0x7f } // lunghezza enorme del primo fotogramma
    assertThat(FramesBlob.split(huge)).isNull()
    assertThat(FramesBlob.forOcr(back, 2).map { it.atMs }).containsExactly(0, 4 * 30_000).inOrder()
    assertThat(FramesBlob.forOcr(back.take(1), 2)).hasSize(1)
  }

  @Test
  fun laPreparazioneConsegnaSoloAnalisiEFotogrammi_eSoloSuUnIndicizzatore() = runTest {
    val buffer = FakeAiBuffer()
    var indexer = true
    val sink = BufferAnalysisSink(buffer) { indexer }
    val frames = PrepOutput.Frames(listOf(VideoFrame(0, "a".toByteArray()), VideoFrame(30_000, "b".toByteArray())))
    val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 0x57, 0x45, 0x42, 0x50)
    sink.deliver(key(1), listOf(PrepOutput.Image(SegmentClass.THUMB_S, webp, 10, 10), PrepOutput.Image(SegmentClass.ANALYSIS, "x".toByteArray(), 10, 10)))
    sink.deliver(key(2), listOf(frames))
    assertThat(buffer.contains(key(1), SegmentClass.ANALYSIS)).isTrue()
    assertThat(buffer.contains(key(1), SegmentClass.THUMB_S)).isFalse()
    assertThat(FramesBlob.split(buffer.get(key(2), SegmentClass.FRAMES)!!)!!.map { it.atMs }).containsExactly(0, 30_000)
    indexer = false
    sink.deliver(key(3), listOf(PrepOutput.Image(SegmentClass.ANALYSIS, "y".toByteArray(), 10, 10)))
    assertThat(buffer.contains(key(3), SegmentClass.ANALYSIS)).isFalse()
    // Buffer sotto 1 GiB libero: rifiuta in silenzio, mai un'eccezione verso la preparazione.
    indexer = true
    val low = FakeAiBuffer(freeBytes = { AiBuffer.MIN_FREE_BYTES - 1 })
    val quiet = BufferAnalysisSink(low) { true }
    quiet.deliver(key(4), listOf(PrepOutput.Image(SegmentClass.ANALYSIS, "z".toByteArray(), 10, 10)))
    assertThat(quiet.refused).isEqualTo(1)
  }

  private class Scope(var proceed: Boolean, override val run: RuleDecision.Run, override val halt: StageHalt? = null) : StageScope<AiCheckpoint> {
    override val request: WorkRequest = WorkRequest.Background(BackgroundActivity.AI_COMPUTE)
    override val byteBudget: Long? = null
    override val stageBytes: Long = 0
    override val roundBytes: Long = 0
    val commits = ArrayList<AiCheckpoint>()

    override suspend fun proceed(): Boolean = proceed

    override suspend fun record(bytes: Long, network: NetworkClass?) = Unit

    override suspend fun commit(checkpoint: AiCheckpoint) {
      commits += checkpoint
    }
  }

  @Test
  fun leRegoleDiStagedRunDiventanoRitmoEAmbito() = runTest {
    val hot = Scope(true, RuleDecision.Run(RunScope.LOCAL_BUFFER_ONLY, Pace.REDUCED, NetworkClass.OFFLINE))
    assertThat(StageScopeControl(hot).check()).isEqualTo(AiPace.Go(reduced = true, localOnly = true))
    val stop = Scope(false, RuleDecision.Run(network = NetworkClass.HOME_WIFI), StageHalt.Rules(RuleDecision.Stop(dev.pampa.mycloudgallery.core.rules.WaitReason.PowerSaver)))
    assertThat(StageScopeControl(stop).check()).isEqualTo(AiPace.Halt(AiStop.RULES))
    val time = Scope(false, RuleDecision.Run(network = NetworkClass.HOME_WIFI), StageHalt.TimeUp)
    assertThat(StageScopeControl(time).check()).isEqualTo(AiPace.Halt(AiStop.BUDGET))

    // Il lotto di StagedRun: tutti gli ambiti; c'e' altro da fare se un ambito si e' fermato per tempo o regole.
    val f = TestFamily()
    (1..3).forEach { f.nas.add(TestFamily.COMMON, concepts = "s$it") }
    val phone = f.anna()
    val batch = AiStageBatch(phone.engine)
    val done: BatchResult<AiCheckpoint> = batch.run(Scope(true, RuleDecision.Run(network = NetworkClass.HOME_WIFI)), null)
    assertThat(done.hasMore).isFalse()
    assertThat(batch.last.map { it.first.toString() }).hasSize(3) // privata, comune, telefono
    assertThat(phone.embedded).isEqualTo(3)
    (1..3).forEach { f.nas.add(TestFamily.COMMON, concepts = "t$it") }
    val halted = batch.run(Scope(false, RuleDecision.Run(network = NetworkClass.HOME_WIFI), StageHalt.TimeUp), null)
    assertThat(halted.hasMore).isTrue()
  }

  /** Stime dal backlog vero (§2.8, D15): conteggi con le stesse porte del calcolo, poi notti e lavoro di `AI_COMPUTE`. */
  @Test
  fun stimeDalBacklogVero() = runTest {
    val f = TestFamily()
    repeat(30) { f.nas.add(TestFamily.COMMON, concepts = "p$it") }
    repeat(5) { f.nas.add(TestFamily.ANNA_PRIVATE, kind = MediaKind.VIDEO, concepts = "v$it") }
    repeat(4) { f.nas.add(TestFamily.BRUNO_PRIVATE, concepts = "altro") } // area d'altri: non conta
    val dup = f.nas.add(TestFamily.ANNA_PRIVATE, concepts = "doppio")
    f.nas.add(TestFamily.COMMON, concepts = "doppio", key = dup)
    val phone = f.anna(reading = true)
    phone.phoneMedia[f.nas.newKey()] = MediaKind.PHOTO to "solo telefono"
    BufferAnalysisSink(phone.buffer) { true }.deliver(dup, listOf(PrepOutput.Image(SegmentClass.ANALYSIS, "FAKE:d".toByteArray(), 10, 10)))

    val work = AiBacklog(phone.roles, phone.candidates, phone.buffer).count()
    assertThat(work.photos).isEqualTo(32) // 30 + il doppio una volta + il telefono
    assertThat(work.videos).isEqualTo(5)
    assertThat(work.textReads).isEqualTo(37)
    assertThat(work.buffered).isEqualTo(1)

    phone.roles.textReadingHere.value = false
    val noText = AiBacklog(phone.roles, phone.candidates, phone.buffer).count()
    assertThat(noText.textReads).isEqualTo(0)

    val measures = DeviceMeasures.PHASE_0
    val seconds = AiComputeCosts.seconds(noText, measures)
    assertThat(seconds).isWithin(1e-6).of((32 + 5 * 4.0) / 2.8)
    val backlog = BacklogFacts(analyze = noText).toLibraryBacklog(ReplicaDepth.STANDARD)
    val ai = backlog.pending(BackgroundActivity.AI_COMPUTE)
    assertThat(ai.items / measures.aiItemsPerSecond).isWithin(1.0).of(seconds)
    assertThat(AiComputeCosts.nights(noText, measures)).isEqualTo(1)
    phone.roles.indexer.value = false
    assertThat(AiBacklog(phone.roles, phone.candidates).count().items).isEqualTo(0)
  }
}
