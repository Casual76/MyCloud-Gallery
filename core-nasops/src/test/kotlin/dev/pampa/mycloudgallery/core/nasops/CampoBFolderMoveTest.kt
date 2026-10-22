package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFolderBackoff
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.p12.ProcessDeath
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * O-B18 delle prove sul campo (gruppo B): dopo lo spostamento di una cartella intera fra aree la cartella restava, vuota, nell'area di partenza.
 * Il progetto (D24, Q3): la sorgente di ogni file va nel cestino della sua share e la cartella d'origine, svuotata, non resta. La toglie
 * `rmdirIfEmpty` (una richiesta, mai ricorsiva: se e' arrivato un file nuovo il NAS rifiuta e la cartella resta con lui).
 *
 * Qui, sul NAS finto con l'esecutore vero: la cartella se ne va; un blocco passeggero non la lascia li' per sempre; un blocco che non passa o
 * un file arrivato dal PC non fanno fallire lo spostamento e non tolgono niente; la morte del processo prima di quel passo non cambia l'esito.
 */
class CampoBFolderMoveTest {

  private val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
  private val bytes = byteArrayOf(1, 2, 3, 4)

  private fun populate() {
    kit.putFile("test", "V/c.jpg", bytes)
    kit.putFile("test", "V/Gita/a.jpg", byteArrayOf(5, 6))
    kit.putFile("test", "V/Gita/Mare/b.jpg", byteArrayOf(7, 8, 9))
    kit.putDir("test", "V/Vuota")
  }

  /** Un esecutore vero con le primitive del NAS che il test sceglie (stesso NAS finto, stesso diario) e le attese brevi sull'orologio finto. */
  private fun engineOver(ops: NasFileOps) = NasOpsEngine(
    deviceId = kit.deviceId,
    accountId = kit.accountId,
    fileOps = ops,
    reader = kit.nas.reader,
    store = kit.store,
    clocks = kit.clocks,
    gate = kit.gate,
    waitShortly = { kit.advance(it) },
  )

  /** Le primitive vere, tranne `rmdirIfEmpty` della cartella [target] (percorso nella share di partenza), che passa da [onTarget]. */
  private class RmdirHook(
    private val inner: NasFileOps,
    private val target: String,
    private val onTarget: suspend (NasFileOps, String, SharePath, Purpose) -> NasResult<RmdirOutcome>,
  ) : NasFileOps by inner {
    var targetCalls = 0
      private set

    override suspend fun rmdirIfEmpty(share: String, dir: SharePath, purpose: Purpose): NasResult<RmdirOutcome> {
      if (dir.value != target) return inner.rmdirIfEmpty(share, dir, purpose)
      targetCalls++
      return onTarget(inner, share, dir, purpose)
    }
  }

  private suspend fun moveV(engine: NasOpsEngine): StartOutcome {
    val request = OpRequest.Move(listOf(kit.itemOf("test", "V")), "test-comune", null)
    val plan = (engine.plan(request) as PlanOutcome.Ready).plan
    return engine.start(plan, kit.confirm(plan))
  }

  private fun assertAllArrived() {
    assertThat(kit.bytes("test-comune", "V/c.jpg")!!.toList()).isEqualTo(bytes.toList())
    assertThat(kit.exists("test-comune", "V/Gita/a.jpg")).isTrue()
    assertThat(kit.exists("test-comune", "V/Gita/Mare/b.jpg")).isTrue()
    assertThat(kit.exists("test-comune", "V/Vuota")).isTrue()
  }

  @Test
  fun o_b18_laCartellaSegueISuoiFileEDallAreaDiPartenzaNonRestaNiente() = runTest {
    populate()

    assertThat(kit.run(OpRequest.Move(listOf(kit.itemOf("test", "V")), "test-comune", null))).isInstanceOf(StartOutcome.Started::class.java)

    assertAllArrived()
    for (dir in listOf("V", "V/Gita", "V/Gita/Mare", "V/Vuota")) assertThat(kit.exists("test", dir)).isFalse()
    // I file sono nel cestino della loro share per 30 giorni (D24): nient'altro, la cartella non e' una voce a se'.
    assertThat(kit.store.allTrash()).hasSize(3)
  }

  @Test
  fun o_b18_unBloccoPasseggeroNonLasciaLaCartellaLiPerSempre() = runTest {
    populate()
    // Un handle aperto sotto o sopra la cartella (della Libreria che legge, di un altro client): due risposte "in uso", poi libera.
    var locked = 2
    val hook = RmdirHook(kit.fileOps, "V") { inner, share, dir, purpose ->
      if (locked > 0) {
        locked--
        NasResult.Ok(RmdirOutcome.Locked)
      } else {
        inner.rmdirIfEmpty(share, dir, purpose)
      }
    }

    val engine = engineOver(hook)

    assertThat(moveV(engine)).isInstanceOf(StartOutcome.Started::class.java)

    assertAllArrived()
    assertThat(hook.targetCalls).isEqualTo(3)
    assertThat(kit.exists("test", "V")).isFalse()
    assertThat(engine.operations.value.single()).isInstanceOf(NasOperationState.Done::class.java)
  }

  @Test
  fun o_b18_unBloccoCheNonPassaNonFaFallireLoSpostamentoELaCartellaRestaVuota() = runTest {
    populate()
    val hook = RmdirHook(kit.fileOps, "V") { _, _, _, _ -> NasResult.Ok(RmdirOutcome.Locked) }

    val engine = engineOver(hook)

    assertThat(moveV(engine)).isInstanceOf(StartOutcome.Started::class.java)

    // Tutto e' arrivato, l'operazione e' chiusa bene; la cartella resta, vuota, e non e' un errore.
    assertAllArrived()
    assertThat(kit.exists("test", "V")).isTrue()
    assertThat(kit.filesIn("test").filter { it.startsWith("V/") }).isEmpty()
    assertThat(engine.operations.value.single()).isInstanceOf(NasOperationState.Done::class.java)
    // Le attese sono brevi e hanno un tetto: non si insiste per sempre.
    assertThat(hook.targetCalls).isAtMost(1 + OwnFolderBackoff.DELAYS_MS.size)
  }

  @Test
  fun o_b18_unFileArrivatoDalPcNelFrattempoImpedisceDiTogliereLaCartellaENonSiToccaNiente() = runTest {
    populate()
    val arrived = byteArrayOf(42, 43)
    val hook = RmdirHook(kit.fileOps, "V") { inner, share, dir, purpose ->
      // Dopo che i file sono usciti e prima della richiesta: qualcuno copia un file nuovo nella cartella.
      kit.putFile("test", "V/nuovo.jpg", arrived)
      inner.rmdirIfEmpty(share, dir, purpose)
    }

    val engine = engineOver(hook)

    assertThat(moveV(engine)).isInstanceOf(StartOutcome.Started::class.java)

    // Mai una cancellazione ricorsiva: la cartella resta con il file nuovo, intatto, e lo spostamento e' chiuso.
    assertThat(kit.bytes("test", "V/nuovo.jpg")!!.toList()).isEqualTo(arrived.toList())
    assertThat(kit.exists("test", "V")).isTrue()
    assertAllArrived()
    assertThat(kit.exists("test-comune", "V/nuovo.jpg")).isFalse()
    assertThat(engine.operations.value.single()).isInstanceOf(NasOperationState.Done::class.java)
  }

  @Test
  fun o_b18_laMorteDelProcessoPrimaDiTogliereLaCartellaNonCambiaLEsito() = runTest {
    populate()
    // Il processo muore alla prima richiesta di togliere V: i file sono gia' tutti spostati e nel cestino.
    val dying = RmdirHook(kit.fileOps, "V") { _, _, _, _ -> throw ProcessDeath() }
    val thrown = runCatching { moveV(engineOver(dying)) }.exceptionOrNull()
    assertThat(thrown).isInstanceOf(ProcessDeath::class.java)
    assertThat(kit.exists("test", "V")).isTrue()
    assertThat(kit.store.ops(OpState.entries.toSet()).single().state.terminal).isFalse()

    // Un processo nuovo, stesso diario e stesso NAS: la ripresa porta a termine il passo.
    val resumed = engineOver(kit.fileOps).resumeAll(5.minutes)

    assertThat(resumed.itemsFailed).isEqualTo(0)
    assertAllArrived()
    assertThat(kit.exists("test", "V")).isFalse()
    assertThat(kit.store.ops(OpState.entries.toSet()).single().state).isEqualTo(OpState.DONE)
    assertThat(kit.store.allTrash()).hasSize(3)
  }
}
