package dev.pampa.mycloudgallery.core.index.p3

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.index.publish.OutboxIndexPublisher
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Outbox che sopravvive alla morte del processo a ogni passo" e "pubblicazione ripetuta idempotente" (riga P3 del
 * §4.4). Ogni chiamata a NAS, database, pacco dei blob e replica locale e' un passo; per ogni passo e per entrambi i
 * momenti (prima che la chiamata abbia effetto e subito dopo) il processo muore una volta, si riparte con un pubblicatore
 * nuovo sullo stesso database e sullo stesso NAS (`recover`, poi si ripete l'operazione interrotta). Alla fine:
 * - chi legge vede esattamente lo stato atteso (ogni record e ogni blob, a meno del `seq` dei cambiamenti ripetuti);
 * - **due** teste, una per pubblicazione: una ripetuta dopo una morte adotta la testa gia' scritta invece di scriverne
 *   un'altra;
 * - outbox vuota, nessun byte orfano nel pacco, replica locale completa.
 */
open class OutboxCrashTest {

  private val share = "test-comune"

  private class Run(val steps: Int, val died: Boolean)

  private fun scenario(dieAt: Int?, afterEffect: Boolean): Run {
    var run: Run? = null
    runTest {
      val world = TestWorld()
      val phone = world.device()
      val files = (0..4).map { world.file(share, "Foto/IMG_$it.jpg") }
      val frames = BlobPut.Frames(files[2].contentKey, listOf(VideoFrame(0, Images.jpeg(marker = files[2].marker()))))
      val plan = FaultPlan().also {
        it.dieAt = dieAt
        it.afterEffect = afterEffect
      }
      var publisher: OutboxIndexPublisher = phone.publisher(plan)

      suspend fun <T> resilient(op: suspend (OutboxIndexPublisher) -> T): T {
        while (true) {
          try {
            return op(publisher)
          } catch (death: ProcessDeath) {
            publisher = phone.publisher(plan)
            resilient { it.recover() }
          }
        }
      }

      resilient { it.recover() }
      resilient { it.stage(share, files.take(3).map { f -> f.put(setOf(SegmentClass.THUMB_S)) }, files.take(3).map { f -> f.thumb() } + frames) }
      val first = resilient { it.flush(share, FlushReason.UNIT_FINISHED) }
      resilient {
        it.stage(share, listOf(files[3].put(), files[4].put(), MetaChange.Remove(files[0].path)), listOf(files[1].thumb(SegmentClass.THUMB_M)))
      }
      val second = resilient { it.flush(share, FlushReason.UNIT_FINISHED) }
      var rounds = 0
      while (resilient { it.flush(share, FlushReason.UNIT_FINISHED) } != PublishOutcome.NothingPending) check(++rounds < 5)
      resilient { it.recover() }

      val label = "morte al passo $dieAt (${if (afterEffect) "dopo" else "prima"} dell'effetto)"
      // Una morte dopo il commit e prima della pulizia del pacco: la ripetizione trova l'outbox vuota (gia' pubblicata).
      for (outcome in listOf(first, second)) {
        assertWithMessage(label).that(outcome is PublishOutcome.Published || outcome == PublishOutcome.NothingPending).isTrue()
      }

      val state = ReaderState.read(world, share, phone.id)
      assertWithMessage(label).that(state.records.values.map { it.path }.toSet()).isEqualTo(files.drop(1).map { it.path }.toSet())
      for (file in files.drop(1)) {
        val expected = if (file === files[1] || file === files[2]) file.meta(setOf(SegmentClass.THUMB_S)) else file.meta()
        assertWithMessage(label).that(state.records.values.single { it.path == file.path }.file).isEqualTo(expected)
      }
      val expectedBlobs = HashMap<Pair<SegmentClass, ContentKey>, String>()
      files.take(3).forEach { expectedBlobs[SegmentClass.THUMB_S to it.contentKey] = it.thumb().bytes.hex() }
      expectedBlobs[SegmentClass.THUMB_M to files[1].contentKey] = files[1].thumb(SegmentClass.THUMB_M).bytes.hex()
      expectedBlobs[SegmentClass.FRAMES to files[2].contentKey] = OutboxIndexPublisher.bodyOf(frames)!!.hex()
      assertWithMessage(label).that(state.blobs).isEqualTo(expectedBlobs.mapValues { setOf(it.value) })

      assertWithMessage("$label: teste").that(headVersions(world, share, phone.id)).containsExactly(1L, 2L).inOrder()
      assertWithMessage("$label: outbox").that(phone.store.outbox(share).isEmpty).isTrue()
      assertWithMessage("$label: byte orfani").that(phone.blobs.handles()).isEmpty()
      val matching = phone.replica.matching(share, files.associate { it.path to it.stamp })
      assertWithMessage("$label: replica").that(matching.keys).isEqualTo(files.drop(1).map { it.path }.toSet())
      run = Run(plan.step, plan.died)
    }
    return run!!
  }

  @Test
  fun morteAOgniPasso_primaEDopoLEffetto_statoFinaleIdenticoEDueTeste() {
    val total = scenario(dieAt = null, afterEffect = false).steps
    assertThat(total).isAtLeast(30)
    var deaths = 0
    for (afterEffect in listOf(false, true)) {
      for (step in 1..total) {
        if (scenario(step, afterEffect).died) deaths++
      }
    }
    // Ogni passo dello scenario senza guasti e' stato un punto di morte, in entrambi i momenti.
    assertThat(deaths).isEqualTo(2 * total)
  }
}
