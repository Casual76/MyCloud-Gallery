package dev.pampa.mycloudgallery.core.nasops.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.engine.ShareAccessView
import dev.pampa.mycloudgallery.core.nasops.engine.TrashRecordWriter
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryOpsStore
import dev.pampa.mycloudgallery.core.nasops.trash.NasTrashService
import dev.pampa.mycloudgallery.core.nasops.trash.RemoteTrashSource
import java.util.Collections
import java.util.Properties
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 7 P4 (riga P4 del §4.4: "nessuna regressione nella 4B: i 400 giri di F16 sulla JVM restano verdi"). Il runner di F16 (le stesse 400
 * interruzioni del giro sul NAS vero del 15/10, un colpo per passo, dietro la stessa guardia) con lo stack **vero** di P12 e, in piu', la
 * **porta di completamento** legata: un catalogo finto che ricorda ogni elemento collegato e che, per un elemento su tre, la prima volta non
 * risponde (l'elemento deve restare `PUBLISHED`, l'operazione aspettare e la ripresa finirla). Sul NAS finto e sul simulatore SMB con le
 * primitive vere (`SmbNasFileOps`).
 *
 * Oltre alla verifica del runner (nessun file a meta', nessun doppione, nessuna perdita, byte giusti, temporanei solo propri e citati), alla
 * fine di ogni giro: ogni file spostato tra share che ha passato `PUBLISHED` (sorgente registrata, nel cestino, o fatto) e' stato collegato dalla
 * porta, e ogni operazione e' chiusa.
 */
class CompletionChaosAreaTest {

  private val runId = "20261026-101500-1a2b3c4d"

  private fun credentials(password: String): ChaosCredentials = ChaosCredentials.parse(
    Properties().apply {
      listOf("user" to "test", "password" to password, "share" to "test", "share2" to "test-comune", "host" to "192.168.178.58")
        .forEach { (k, v) -> setProperty(k, v) }
    },
  )!!

  /** Lo stack vero di P12 con la porta di completamento (come [ProductionChaosStackFactory], piu' la porta). */
  private class CompletingStack : ChaosStackFactory {
    override val name: String = "operazioni vere di P12 con la porta di completamento (Fase 7 P4)"
    override val removesEmptySourceFolders: Boolean = true

    val stores: MutableList<InMemoryOpsStore> = Collections.synchronizedList(ArrayList())
    val acked: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val refusedOnce: MutableSet<String> = Collections.synchronizedSet(HashSet())
    var calls = 0
      private set
    var refusals = 0
      private set

    override fun newStore(world: ChaosWorld): OpsStore = InMemoryOpsStore().also { stores += it }

    override fun create(env: StackEnv): ChaosStack {
      val lock = NasOpsLock()
      val access = ShareAccessView { share -> env.readOnly(share) }
      val sink = MoveCompletionSink { op, index, _, _, _, _ ->
        val key = "${op.value}#$index"
        synchronized(this) { calls++ }
        // Un elemento su tre: la prima volta il catalogo non risponde (l'elemento resta PUBLISHED e si riprova).
        if (index % 3 == 0 && refusedOnce.add(key)) {
          synchronized(this) { refusals++ }
          error("catalogo occupato")
        }
        acked += key
      }
      val engine = NasOpsEngine(
        deviceId = env.world.deviceId,
        accountId = env.world.accountId,
        fileOps = env.fileOps,
        reader = env.reader,
        store = env.store,
        clocks = env.clocks,
        gate = env.gate,
        access = access,
        records = TrashRecordWriter.NONE,
        lock = lock,
        newId = env.newId,
        waitShortly = { ms -> if (env.world.capabilities.advanceTime) env.world.advanceTime(ms) else delay(ms) },
        completions = sink,
      )
      val trash = NasTrashService(
        deviceId = env.world.deviceId,
        accountId = env.world.accountId,
        shares = { env.world.shares },
        store = env.store,
        fileOps = env.fileOps,
        reader = env.reader,
        clocks = env.clocks,
        gate = env.gate,
        access = access,
        remote = RemoteTrashSource.NONE,
        lock = lock,
        authorName = { null },
      )
      return ChaosStack(engine, trash)
    }

    /** Ogni file spostato tra share oltre `PUBLISHED` e' stato collegato dalla porta; ogni operazione chiusa. */
    suspend fun violations(): List<String> {
      val out = ArrayList<String>()
      for (store in stores.toList()) {
        for (op in store.ops(OpState.entries.toSet())) {
          if (!op.state.terminal) out += "operazione non chiusa (${op.state.code})"
          for (item in store.items(op.id)) {
            if (item.flow != ItemFlow.MOVE_ACROSS_SHARES || item.isFolder) continue
            val past = item.state in setOf(OpItemState.SOURCE_RECORDED, OpItemState.SOURCE_TRASHED, OpItemState.DONE)
            if (past && "${op.id.value}#${item.index}" !in acked) out += "sorgente registrata senza la porta (#${item.index}, ${item.state.code})"
          }
        }
      }
      return out
    }
  }

  @Test
  fun quattrocentoInterruzioniSulNasFintoConLaPortaDiCompletamento(): Unit = runBlocking {
    val guard = ChaosAreaGuard.of(credentials("x"), runId)
    val target = GuardedTarget(InMemoryTarget(), guard)
    val stack = CompletingStack()
    val settings = ChaosAreaRunner.Settings(interruptions = 400, baseSeed = 20261026L, mediumBytes = 200_000, bigBytes = 400_000, bigPercent = 10)
    val result = ChaosAreaRunner.run(target, stack, settings)
    println(result.summary())
    println("porta di completamento: ${stack.calls} chiamate, ${stack.acked.size} elementi collegati, ${stack.refusals} risposte mancate")
    assertWithMessage(result.failureText()).that(result.failures).isEmpty()
    assertThat(result.stats.totalFired).isAtLeast(400)
    assertWithMessage("porta").that(stack.violations()).isEmpty()
    assertThat(stack.acked.size).isAtLeast(20)
    assertThat(stack.refusals).isAtLeast(5)
  }

  @Test
  fun quattrocentoInterruzioniSulSimulatoreSmbConLaPortaDiCompletamento(): Unit = runBlocking {
    val password = "pw-di-prova"
    val backend = InMemorySmbBackend().apply {
      addUser("test", password)
      addShare("test", mapOf("test" to SimAccess.READ_WRITE))
      addShare("test-comune", mapOf("test" to SimAccess.READ_WRITE))
      val start = System.nanoTime()
      val base = nasTime()
      nasTime = { base + (System.nanoTime() - start) / 1_000_000 }
    }
    val area = SmbChaosArea(transport = { backend }, socket = { backend.binding("pc") }, backoff = { })
    val guard = ChaosAreaGuard.of(credentials(password), runId)
    val stack = CompletingStack()
    try {
      val target = GuardedTarget(area.openTarget(credentials(password), guard), guard)
      val settings = ChaosAreaRunner.Settings(interruptions = 400, baseSeed = 20261027L, mediumBytes = 200_000, bigBytes = 400_000, bigPercent = 10)
      val result = ChaosAreaRunner.run(target, stack, settings)
      println(result.summary())
      println("porta di completamento: ${stack.calls} chiamate, ${stack.acked.size} elementi collegati, ${stack.refusals} risposte mancate")
      assertWithMessage(result.failureText()).that(result.failures).isEmpty()
      assertThat(result.stats.totalFired).isAtLeast(400)
      assertWithMessage("porta").that(stack.violations()).isEmpty()
      assertThat(stack.acked.size).isAtLeast(20)
    } finally {
      area.close()
    }
  }
}
