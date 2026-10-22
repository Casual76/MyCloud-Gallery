package dev.pampa.mycloudgallery.core.index.ai

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.p3.FaultPlan
import dev.pampa.mycloudgallery.core.index.p3.ProcessDeath
import dev.pampa.mycloudgallery.core.index.store.OwnHead
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Il database del pubblicatore `ai` che muore (o fallisce) al passo del piano: ogni chiamata e' un passo. */
class DyingAiPublishStore(private val delegate: AiPublishStore, private val plan: FaultPlan) : AiPublishStore {
  override suspend fun stage(share: String, state: AiRowState, entries: List<AiStaged>) = plan.aroundSuspend { delegate.stage(share, state, entries) }

  override suspend fun rows(share: String, state: AiRowState, limit: Int) = plan.aroundSuspend { delegate.rows(share, state, limit) }

  override suspend fun refs(share: String, state: AiRowState) = plan.aroundSuspend { delegate.refs(share, state) }

  override suspend fun move(share: String, ids: Set<Long>, to: AiRowState) = plan.aroundSuspend { delegate.move(share, ids, to) }

  override suspend fun discard(share: String, ids: Set<Long>) = plan.aroundSuspend { delegate.discard(share, ids) }

  override suspend fun pending() = plan.aroundSuspend { delegate.pending() }

  override suspend fun ownHead(share: String): OwnHead? = plan.aroundSuspend { delegate.ownHead(share) }

  override suspend fun live(share: String, keys: Collection<AiKey>) = plan.aroundSuspend { delegate.live(share, keys) }

  override suspend fun liveAll(share: String) = plan.aroundSuspend { delegate.liveAll(share) }

  override suspend fun commit(share: String, commit: AiPublishCommit) = plan.aroundSuspend { delegate.commit(share, commit) }

  override suspend fun shares() = plan.aroundSuspend { delegate.shares() }
}

/**
 * "`FaultPlan` a ogni chiamata della pubblicazione" (riga P2 del §4.4): ogni chiamata a NAS e database e' un passo; per ogni passo, e
 * per entrambi i momenti (prima che la chiamata abbia effetto e subito dopo), il processo muore una volta, e in un secondo giro il NAS
 * risponde con un errore. Si riparte con un pubblicatore nuovo sullo stesso database e sullo stesso NAS (`recover`, poi si ripete
 * l'operazione interrotta). Alla fine:
 * - chi legge vede esattamente le voci attese (le superate restano nel segmento di prima, accanto alla nuova);
 * - **due** teste `ai`, una per pubblicazione: una ripetuta dopo una morte adotta la testa gia' scritta;
 * - outbox vuota, nessuna voce in attesa.
 */
class AiPublisherCrashTest {

  @get:Rule val tmp = TemporaryFolder()

  private val share = "test-comune"

  private class Run(val steps: Int, val faulted: Boolean)

  private fun scenario(at: Int?, afterEffect: Boolean, nasError: Boolean): Run {
    var run: Run? = null
    runTest {
      val ai = AiWorld(tmp.newFolder())
      val phone = ai.device()
      val files = (0..4).map { ai.file(share, "Foto/IMG_$it.jpg") }
      val first = files.take(3).map { phone.vector(it) } + files.take(2).map { phone.text(it) }
      // La seconda unita': due file nuovi e una ricetta nuova per il primo (D21).
      val second = listOf(phone.vector(files[3]), phone.text(files[4]), phone.vector(files[0], recipe = 2))
      val plan = FaultPlan()
      if (nasError) {
        plan.errorAt = at
        plan.error = NasError.Timeout
      } else {
        plan.dieAt = at
        plan.afterEffect = afterEffect
      }
      val store = DyingAiPublishStore(phone.publishStore, plan)
      var publisher = phone.publisher(plan, store)

      suspend fun <T> resilient(op: suspend (OutboxAiIndexPublisher) -> T): T {
        while (true) {
          try {
            return op(publisher)
          } catch (death: ProcessDeath) {
            publisher = phone.publisher(plan, store)
            resilient { it.recover() }
          }
        }
      }

      resilient { it.recover() }
      resilient { it.stage(share, first) }
      var outcome = resilient { it.flush(share, FlushReason.UNIT_FINISHED) }
      // Un errore del NAS non lascia niente a meta': si ripete la pubblicazione.
      while (outcome is PublishOutcome.Failed) outcome = resilient { it.flush(share, FlushReason.UNIT_FINISHED) }
      resilient { it.stage(share, second) }
      outcome = resilient { it.flush(share, FlushReason.UNIT_FINISHED) }
      while (outcome is PublishOutcome.Failed) outcome = resilient { it.flush(share, FlushReason.UNIT_FINISHED) }
      var rounds = 0
      while (resilient { it.flush(share, FlushReason.UNIT_FINISHED) } != PublishOutcome.NothingPending) check(++rounds < 5)

      val label = "${if (nasError) "errore del NAS" else "morte"} al passo $at (${if (afterEffect) "dopo" else "prima"})"
      val expected = HashMap<AiKey, MutableSet<String>>()
      (first + second).forEach { expected.getOrPut(it.key) { HashSet() } += it.digest() }
      assertWithMessage(label).that(aiReaderState(ai, share, phone.id)).isEqualTo(expected)
      assertWithMessage("$label: teste").that(phone.aiHeads(share)).containsExactly(1L, 2L).inOrder()
      assertWithMessage("$label: in attesa").that(phone.publishStore.pending()).isEqualTo(AiPendingCounts.EMPTY)
      run = Run(plan.step, plan.died || (nasError && plan.errorAt == null && at != null))
    }
    return run!!
  }

  @Test
  fun morteAOgniPasso_primaEDopoLEffetto_statoFinaleIdenticoEDueTeste() {
    val total = scenario(at = null, afterEffect = false, nasError = false).steps
    assertThat(total).isAtLeast(20)
    var deaths = 0
    for (afterEffect in listOf(false, true)) {
      for (step in 1..total) if (scenario(step, afterEffect, nasError = false).faulted) deaths++
    }
    // Ogni passo dello scenario senza guasti e' stato un punto di morte, in entrambi i momenti.
    assertThat(deaths).isEqualTo(2 * total)
  }

  @Test
  fun erroreDelNasAOgniPasso_statoFinaleIdenticoEDueTeste() {
    val total = scenario(at = null, afterEffect = false, nasError = false).steps
    for (step in 1..total) scenario(step, afterEffect = false, nasError = true)
  }
}
