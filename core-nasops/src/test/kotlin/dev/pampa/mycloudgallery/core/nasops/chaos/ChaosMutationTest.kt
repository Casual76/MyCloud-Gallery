package dev.pampa.mycloudgallery.core.nasops.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.RunResult
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il banco ha i denti (docs/fase4-progetto.md §5.5): se si rompe di proposito il codice sotto prova, in poche centinaia di giri il banco
 * deve diventare rosso, e con l'invariante giusto. Senza questa prova un banco verde non vale niente.
 *
 * Ogni "difetto" avvolge un pezzo dello stack fake (le primitive, il diario, le operazioni) e rompe una promessa:
 *  - il cestino che perde il file (perdita);
 *  - il cestino che dice di aver fatto e non fa (il file resta, doppione o catalogo sbagliato);
 *  - l'eliminazione del temporaneo che mente (temporaneo orfano);
 *  - la voce del diario locale che non si scrive (file nel cestino senza diario);
 *  - la ripresa che non riprende (operazioni che non arrivano in fondo).
 */
class ChaosMutationTest {

  /** Uno stack fake con un pezzo rotto. */
  private class Mutant(
    private val base: ChaosStackFactory,
    private val label: String,
    private val fileOps: ((NasFileOps) -> NasFileOps)? = null,
    private val store: ((OpsStore) -> OpsStore)? = null,
    private val operations: ((NasOperations) -> NasOperations)? = null,
  ) : ChaosStackFactory by base {
    override val name: String = "${base.name} + difetto iniettato: $label"

    override fun create(env: StackEnv): ChaosStack {
      val broken = StackEnv(
        world = env.world,
        fileOps = fileOps?.invoke(env.fileOps) ?: env.fileOps,
        reader = env.reader,
        store = store?.invoke(env.store) ?: env.store,
        clocks = env.clocks,
        gate = env.gate,
        newId = env.newId,
        readOnly = env.readOnly,
      )
      val stack = base.create(broken)
      return ChaosStack(operations?.invoke(stack.operations) ?: stack.operations, stack.trash)
    }
  }

  private fun run(factory: ChaosStackFactory, rounds: Int): BenchResult = runBlocking {
    ChaosBench.run(rounds, baseSeed = 4242L, target = InMemoryTarget(), factory = factory)
  }

  private fun assertDetects(result: BenchResult, expected: Set<Invariant>) {
    assertWithMessage("il banco non ha visto il difetto in ${result.rounds} giri\n${result.summary()}").that(result.failures).isNotEmpty()
    val seen = result.failures.flatMap { it.violations }.map { it.invariant }.toSet()
    assertWithMessage("invarianti violati: $seen, attesi almeno uno fra $expected").that(seen.intersect(expected)).isNotEmpty()
  }

  @Test
  fun ilCestinoChePerdeIlFileEUnaPerdita() {
    val broken = Mutant(
      FakeChaosStackFactory,
      "il file va nel cestino e subito dopo sparisce",
      fileOps = { inner ->
        object : NasFileOps by inner {
          override suspend fun trashMove(share: String, path: SharePath, trashId: String, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> {
            val result = inner.trashMove(share, path, trashId, expect, purpose)
            if (result is NasResult.Ok && result.value is RenameOutcome.Renamed && expect != null) {
              inner.deleteIfUnchanged(share, NasLayout.trashItemFile(inner.ownerDeviceId, trashId), expect.size, purpose)
            }
            return result
          }
        }
      },
    )
    assertDetects(run(broken, 400), setOf(Invariant.LOSS))
  }

  @Test
  fun ilCestinoCheDiceDiAverFattoENonFaLasciaIlFileOveNonDeve() {
    val broken = Mutant(
      FakeChaosStackFactory,
      "trashMove risponde 'fatto' senza fare niente",
      fileOps = { inner ->
        object : NasFileOps by inner {
          override suspend fun trashMove(share: String, path: SharePath, trashId: String, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> =
            NasResult.Ok(RenameOutcome.Renamed(null))
        }
      },
    )
    assertDetects(run(broken, 400), setOf(Invariant.CATALOG, Invariant.DUPLICATE, Invariant.LOSS))
  }

  @Test
  fun lEliminazioneDelTemporaneoCheMenteLasciaTemporaneiOrfani() {
    val broken = Mutant(
      FakeChaosStackFactory,
      "deleteOwnTemp risponde 'eliminato' senza eliminare",
      fileOps = { inner ->
        object : NasFileOps by inner {
          override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> =
            NasResult.Ok(DeleteOutcome.Deleted)
        }
      },
    )
    assertDetects(run(broken, 1_500), setOf(Invariant.TEMP, Invariant.CONVERGENCE))
  }

  @Test
  fun laVoceDelDiarioLocaleCheNonSiScriveLasciaFileNelCestinoSenzaDiario() {
    val broken = Mutant(
      FakeChaosStackFactory,
      "recordTrash non scrive niente",
      store = { inner ->
        object : OpsStore by inner {
          override suspend fun recordTrash(entry: LocalTrashRecord) = Unit
        }
      },
    )
    assertDetects(run(broken, 300), setOf(Invariant.TRASH_RECORD, Invariant.DIARY))
  }

  @Test
  fun laRipresaCheNonRiprendeLasciaOperazioniAperte() {
    val broken = Mutant(
      FakeChaosStackFactory,
      "resumeAll non fa niente",
      operations = { inner ->
        object : NasOperations by inner {
          override suspend fun resumeAll(budget: Duration): RunResult = RunResult(0, 0, 0, 0, RunStop.COMPLETE)
        }
      },
    )
    val result = run(broken, 300)
    assertDetects(result, setOf(Invariant.CONVERGENCE))
  }
}
