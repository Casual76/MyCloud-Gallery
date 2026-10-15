package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOpsFactory
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFolderBackoff
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.testing.FakeOpsGate
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryOpsStore
import dev.pampa.mycloudgallery.core.nasops.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/**
 * "Cartella in uso dall'app": decisione dell'utente del 15/10. Sul WD una cartella con un file aperto sotto dallo stesso client non si rinomina
 * (`ACCESS_DENIED`, Samba `strict rename = no`); se il file lo tiene aperto la Libreria (una miniatura, un'anteprima) lo spostamento o
 * l'eliminazione della cartella si fermavano con "permesso negato". Qui, con l'esecutore vero sulla sessione SMB di produzione e il
 * simulatore che riproduce quel comportamento, l'attesa e' **breve** e non e' un errore:
 *  - un handle di lettura nostro sotto la cartella si rilascia e l'operazione riesce subito (nessuna attesa);
 *  - un handle che non si rilascia (un elenco a meta') fa riprovare dopo 1, 2, 4, 8, 16, 32 secondi ([OwnFolderBackoff]);
 *  - se dopo l'ultima prova la cartella e' ancora bloccata diventa un'attesa normale (file aperto), non un errore definitivo;
 *  - **un vero permesso negato resta un errore** (nessun handle nostro sotto, share in sola lettura, rifiuto che persiste);
 *  - un guasto della rete o la morte del processo durante l'attesa si riprendono dal diario.
 *
 * Le attese del test non dormono: avanzano l'orologio finto (monotono e ora del NAS insieme).
 */
class OwnFolderWaitTest {

  private val device = Base32.encode(ByteArray(16) { 0x5a })
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val monotonic = ManualMonotonicClock(10_000_000L)
  private val backend = InMemorySmbBackend().apply {
    addUser(USER, PASSWORD)
    for (share in listOf("test", "test-comune")) addShare(share, mapOf(USER to SimAccess.READ_WRITE))
    addShare("sola", mapOf(USER to SimAccess.READ))
  }
  private val store = InMemoryOpsStore()
  private val gate = FakeOpsGate()

  /** Le attese brevi chieste dall'esecutore, in ms; [onWait] e' il gancio del test (cosa succede "mentre si aspetta"). */
  private val waits = CopyOnWriteArrayList<Long>()
  private var onWait: suspend (Long) -> Unit = {}

  /** Un processo dell'app: sessione SMB di produzione, lettura, esecutore vero. Dopo una morte se ne fa un altro sullo stesso diario. */
  private inner class AppProcess {
    val manager = SmbNasSessionManager(
      account = { NasAccount(NasAddress("192.168.178.58"), USER, "slot", backend.serverGuid) },
      routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding("pc")) },
      credentials = { CredentialLoad.Found(NasCredentials(USER, PASSWORD.toCharArray())) },
      breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope),
      scope = scope,
      transport = backend,
      clock = monotonic,
      io = Dispatchers.IO,
      backoff = { },
    )
    val reader = manager.mediaReader()
    val clocks = object : OpClocks {
      override fun monotonicMs(): Long = monotonic.nowMillis()

      override fun nasNowMs(): Long? = manager.nasClock.nowMillisOrNull()
    }
    val engine = NasOpsEngine(
      deviceId = device,
      accountId = USER,
      fileOps = NasFileOpsFactory.create(manager, device),
      reader = reader,
      store = store,
      clocks = clocks,
      gate = gate,
      waitShortly = { ms ->
        waits += ms
        monotonic.advance(ms)
        onWait(ms)
      },
    )

    init {
      // La prima ancora dell'ora del NAS, come all'avvio dell'app.
      check(runBlocking { manager.withShare("test", Purpose.INTERACTIVE) { } } is NasResult.Ok)
    }
  }

  private val app = AppProcess()

  @After
  fun tearDown() {
    backend.beforeOp = null
    backend.faults = null
    runBlocking { app.manager.invalidate(InvalidateReason.USER_REQUEST) }
    scope.cancel()
  }

  private fun bounded(block: suspend () -> Unit) = runBlocking { withTimeout(60_000) { block() } }

  private fun p(path: String) = SharePath.parse(path)

  private fun file(path: String, size: Int = 2_000, share: String = "test") {
    backend.put(share, path, ByteArray(size) { (it * 7).toByte() })
  }

  private suspend fun AppProcess.folder(path: String, share: String = "test", bytes: Long = 0): NasItem {
    val entry = (reader.stat(share, p(path), Purpose.INTERACTIVE) as NasResult.Ok).value!!
    check(entry.isDirectory)
    return NasItem(share, p(path), true, bytes, null)
  }

  private suspend fun AppProcess.start(request: OpRequest) {
    val plan = (engine.plan(request) as PlanOutcome.Ready).plan
    engine.start(plan, TestConfirmations.forPlan(plan, clocks.monotonicMs()))
  }

  private suspend fun only() = store.ops(OpState.entries.toSet()).single()

  private suspend fun items(): List<OpItemRecord> = store.items(only().id)

  private suspend fun describe(): String = items().joinToString { "${it.state.code}/${it.errorCode?.code}/x${it.attempts}" } + " op=${only().state.code}/${only().errorCode?.code}"

  /** Un elenco di [path] a pagine di una voce, fermo dopo la prima: la cartella resta aperta sul NAS finche' [gate] non si apre. */
  private fun holdListing(process: AppProcess, path: String, hold: CompletableDeferred<Unit>) {
    scope.launch(Dispatchers.IO) { process.reader.list("test", p(path), Purpose.BACKGROUND, pageSize = 1) { hold.await() } }
    waitFor("elenco a meta'") { backend.openHandlesOn("test", path) == 1 }
  }

  private fun waitFor(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (!condition()) {
      check(System.nanoTime() < deadline) { "$what: ancora non vero dopo 30 s" }
      Thread.sleep(1)
    }
  }

  // --- Si rilascia e si passa ------------------------------------------------------------------------------------------------

  @Test
  fun unaMiniaturaApertaNonFermaLaRinominaDellaCartella() = bounded {
    file("Foto/Mare/a.jpg")
    file("Foto/b.jpg")
    val thumbnail = (app.reader.open("test", p("Foto/Mare/a.jpg"), Purpose.BACKGROUND) as NasResult.Ok).value
    try {
      app.start(OpRequest.Rename(app.folder("Foto"), "Gita"))

      assertWithMessage(describe()).that(only().state).isEqualTo(OpState.DONE)
      assertThat(waits).isEmpty()
      assertThat(backend.exists("test", "Gita/Mare/a.jpg")).isTrue()
      assertThat(backend.exists("test", "Foto")).isFalse()
    } finally {
      thumbnail.close()
    }
  }

  @Test
  fun unaMiniaturaApertaNonFermaLaRinominaSoloDiMaiuscole() = bounded {
    file("Foto/Mare/a.jpg")
    val thumbnail = (app.reader.open("test", p("Foto/Mare/a.jpg"), Purpose.BACKGROUND) as NasResult.Ok).value
    try {
      app.start(OpRequest.Rename(app.folder("Foto"), "FOTO"))

      assertWithMessage(describe()).that(only().state).isEqualTo(OpState.DONE)
      assertThat(waits).isEmpty()
      assertThat(backend.children("test", "")).containsExactly("FOTO")
    } finally {
      thumbnail.close()
    }
  }

  @Test
  fun unaMiniaturaApertaNonFermaLEliminazioneDellaCartella() = bounded {
    file("Gita/Mare/a.jpg", size = 1_000)
    file("Gita/b.jpg", size = 1_000)
    val thumbnail = (app.reader.open("test", p("Gita/Mare/a.jpg"), Purpose.BACKGROUND) as NasResult.Ok).value
    try {
      app.start(OpRequest.Trash(listOf(app.folder("Gita", bytes = 2_000))))

      assertWithMessage(describe()).that(only().state).isEqualTo(OpState.DONE)
      assertThat(waits).isEmpty()
      assertThat(backend.exists("test", "Gita")).isFalse()
      val trashed = NasLayout.trashItemFile(device, items().single().trashId!!).value
      assertThat(backend.exists("test", "$trashed/Mare/a.jpg")).isTrue()
    } finally {
      thumbnail.close()
    }
  }

  // --- Si aspetta poco, poi e' un'attesa normale ---------------------------------------------------------------------------------

  @Test
  fun unElencoAMetaFaRiprovareDopoUnSecondoEPassa() = bounded {
    file("Foto/Mare/a.jpg")
    file("Foto/Mare/b.jpg")
    val hold = CompletableDeferred<Unit>()
    holdListing(app, "Foto/Mare", hold)
    // Al primo giro di attesa l'elenco finisce e i suoi handle si chiudono: la seconda prova riesce.
    onWait = {
      hold.complete(Unit)
      waitFor("handle chiusi") { backend.openHandles == 0 }
    }

    app.start(OpRequest.Rename(app.folder("Foto"), "Gita"))

    assertWithMessage(describe()).that(only().state).isEqualTo(OpState.DONE)
    assertThat(waits).containsExactly(1_000L)
    assertThat(backend.exists("test", "Gita/Mare/a.jpg")).isTrue()
  }

  @Test
  fun ilCestinoDiUnaCartellaConUnElencoAMetaAspettaEPassa() = bounded {
    file("Gita/Mare/a.jpg", size = 1_000)
    file("Gita/Mare/b.jpg", size = 1_000)
    val hold = CompletableDeferred<Unit>()
    holdListing(app, "Gita/Mare", hold)
    onWait = { ms ->
      // Dopo 1 e 2 secondi e' ancora aperto, poi finisce.
      if (ms >= 2_000) {
        hold.complete(Unit)
        waitFor("handle chiusi") { backend.openHandles == 0 }
      }
    }

    app.start(OpRequest.Trash(listOf(app.folder("Gita", bytes = 2_000))))

    assertWithMessage(describe()).that(only().state).isEqualTo(OpState.DONE)
    assertThat(waits).containsExactly(1_000L, 2_000L).inOrder()
    assertThat(backend.exists("test", "Gita")).isFalse()
  }

  @Test
  fun senzaFineLeAtteseBreviDiventanoUnaAttesaNormaleENonUnErrore() = bounded {
    file("Foto/Mare/a.jpg")
    file("Foto/Mare/b.jpg")
    val hold = CompletableDeferred<Unit>()
    holdListing(app, "Foto/Mare", hold)

    app.start(OpRequest.Rename(app.folder("Foto"), "Gita"))

    // 1, 2, 4, 8, 16 e 32 secondi: circa un minuto, poi basta.
    assertThat(waits).containsExactlyElementsIn(OwnFolderBackoff.DELAYS_MS).inOrder()
    assertThat(OwnFolderBackoff.MAX_TOTAL_WAIT_MS).isAtMost(65_000L)
    // Non e' un errore: l'elemento aspetta come per un file aperto (E-N13), con la prossima prova fra un minuto, e niente e' cambiato.
    val item = items().single()
    assertWithMessage(describe()).that(item.state).isEqualTo(OpItemState.RENAMING)
    assertThat(item.errorCode).isEqualTo(OpErrorCode.LOCKED)
    assertThat(item.waitingLocked).isTrue()
    assertThat(item.attempts).isEqualTo(1)
    assertThat(only().state).isEqualTo(OpState.PAUSED)
    assertThat(only().errorCode).isEqualTo(OpErrorCode.LOCKED)
    assertThat(app.engine.operations.value.single()).isInstanceOf(NasOperationState.WaitingLocked::class.java)
    assertThat(backend.exists("test", "Foto/Mare/a.jpg")).isTrue()
    waits.clear()

    // Subito dopo non si riprova (l'attesa normale non e' scaduta): nessun'altra attesa breve, nessun tentativo sul NAS.
    assertThat(app.engine.resumeAll(1.minutes).stoppedBy).isEqualTo(RunStop.WAITING_LOCKED)
    assertThat(waits).isEmpty()

    // L'elenco finisce, passa il minuto: la ripresa porta a termine la rinomina.
    hold.complete(Unit)
    waitFor("handle chiusi") { backend.openHandles == 0 }
    monotonic.advance(61_000)
    app.engine.resumeAll(1.minutes)
    assertWithMessage(describe()).that(only().state).isEqualTo(OpState.DONE)
    assertThat(backend.exists("test", "Gita/Mare/a.jpg")).isTrue()
    assertThat(waits).isEmpty()
  }

  @Test
  fun piuCartelleBloccateNonRaddoppianoLAttesaBreve() = bounded {
    file("A/Mare/a.jpg", size = 1_000)
    file("A/Mare/b.jpg", size = 1_000)
    file("B/Sera/c.jpg", size = 1_000)
    file("B/Sera/d.jpg", size = 1_000)
    val holdA = CompletableDeferred<Unit>()
    val holdB = CompletableDeferred<Unit>()
    holdListing(app, "A/Mare", holdA)
    holdListing(app, "B/Sera", holdB)

    app.start(OpRequest.Trash(listOf(app.folder("A", bytes = 2_000), app.folder("B", bytes = 2_000))))

    // Il minuto di attesa breve e' uno per giro, non uno per cartella: la seconda cartella bloccata va subito in attesa normale.
    assertThat(waits.sum()).isAtMost(OwnFolderBackoff.MAX_TOTAL_WAIT_MS)
    assertThat(waits).containsExactlyElementsIn(OwnFolderBackoff.DELAYS_MS).inOrder()
    assertWithMessage(describe()).that(items().map { it.waitingLocked }).containsExactly(true, true)
    assertThat(only().state).isEqualTo(OpState.PAUSED)
    assertThat(only().errorCode).isEqualTo(OpErrorCode.LOCKED)
    assertThat(backend.exists("test", "A/Mare/a.jpg")).isTrue()
    assertThat(backend.exists("test", "B/Sera/c.jpg")).isTrue()

    // Finiti gli elenchi e passato il minuto, la ripresa porta a termine tutte e due.
    holdA.complete(Unit)
    holdB.complete(Unit)
    waitFor("handle chiusi") { backend.openHandles == 0 }
    monotonic.advance(61_000)
    app.engine.resumeAll(1.minutes)
    assertWithMessage(describe()).that(only().state).isEqualTo(OpState.DONE)
    assertThat(backend.exists("test", "A")).isFalse()
    assertThat(backend.exists("test", "B")).isFalse()
  }

  @Test
  fun leScrittureFermateInterrompononLeAtteseBrevi() = bounded {
    file("Foto/Mare/a.jpg")
    file("Foto/Mare/b.jpg")
    val hold = CompletableDeferred<Unit>()
    holdListing(app, "Foto/Mare", hold)
    onWait = { gate.byShare["test"] = GateStatus.WRITES_STOPPED }

    app.start(OpRequest.Rename(app.folder("Foto"), "Gita"))

    // Una sola attesa: poi le scritture sono ferme e l'elemento aspetta; niente e' cambiato.
    assertThat(waits).containsExactly(1_000L)
    assertThat(items().single().waitingLocked).isTrue()
    assertThat(backend.exists("test", "Foto/Mare/a.jpg")).isTrue()
    hold.complete(Unit)
  }

  // --- Un vero permesso negato resta un errore -----------------------------------------------------------------------------------

  @Test
  fun unAccessDeniedSenzaHandleNostriSottoRestaUnErroreSenzaAttese() = bounded {
    file("Foto/a.jpg")
    backend.faults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.Status(NtStatus.ACCESS_DENIED), op = SimOp.RENAME)

    app.start(OpRequest.Rename(app.folder("Foto"), "Gita"))

    assertThat(waits).isEmpty()
    assertThat(items().single().state).isEqualTo(OpItemState.FAILED)
    assertThat(items().single().errorCode).isEqualTo(OpErrorCode.PERMISSION_DENIED)
    assertThat(only().state).isEqualTo(OpState.FAILED)
    assertThat(backend.exists("test", "Foto/a.jpg")).isTrue()
  }

  @Test
  fun unRifiutoChePersisteConUnaMiniaturaApertaResteraUnErrore() = bounded {
    file("Foto/Mare/a.jpg")
    val thumbnail = (app.reader.open("test", p("Foto/Mare/a.jpg"), Purpose.BACKGROUND) as NasResult.Ok).value
    try {
      // Il rifiuto non dipende dalla miniatura: rilasciata quella, la cartella e' ancora negata. E' un permesso negato vero.
      backend.faults = FaultPlan().random(1L, 1.0, FaultPhase.BEFORE, SimFault.Status(NtStatus.ACCESS_DENIED), ops = setOf(SimOp.RENAME))

      app.start(OpRequest.Trash(listOf(app.folder("Foto", bytes = 2_000))))

      assertThat(waits).isEmpty()
      assertWithMessage(describe()).that(items().single().errorCode).isEqualTo(OpErrorCode.PERMISSION_DENIED)
      assertThat(only().state).isEqualTo(OpState.FAILED)
      assertThat(backend.exists("test", "Foto/Mare/a.jpg")).isTrue()
    } finally {
      thumbnail.close()
    }
  }

  @Test
  fun unaShareInSolaLetturaResteUnErroreAnchePerChiLeggeDentro() = bounded {
    file("Foto/Mare/a.jpg", share = "sola")
    val thumbnail = (app.reader.open("sola", p("Foto/Mare/a.jpg"), Purpose.BACKGROUND) as NasResult.Ok).value
    try {
      app.start(OpRequest.Rename(app.folder("Foto", share = "sola"), "Gita"))

      assertThat(waits).isEmpty()
      assertWithMessage(describe()).that(items().single().errorCode).isEqualTo(OpErrorCode.PERMISSION_DENIED)
      assertThat(only().state).isEqualTo(OpState.FAILED)
      assertThat(backend.exists("sola", "Foto/Mare/a.jpg")).isTrue()
    } finally {
      thumbnail.close()
    }
  }

  // --- Guasti e morte del processo durante l'attesa --------------------------------------------------------------------------------

  /** L'elenco finisce durante la prima attesa e, proprio sulla rinomina che segue, scatta [fault] in [phase]; poi si riprende dal diario. */
  private suspend fun faultOnTheRetry(phase: FaultPhase, fault: SimFault) {
    file("Foto/Mare/a.jpg")
    file("Foto/Mare/b.jpg")
    val hold = CompletableDeferred<Unit>()
    holdListing(app, "Foto/Mare", hold)
    onWait = {
      hold.complete(Unit)
      waitFor("handle chiusi") { backend.openHandles == 0 }
      // Un piano nuovo conta dalla prima: la rinomina che segue l'attesa e' la prima.
      backend.faults = FaultPlan().at(1, phase, fault, op = SimOp.RENAME)
    }

    app.start(OpRequest.Rename(app.folder("Foto"), "Gita"))

    // La rete e' caduta (o la sessione e' scaduta) a meta' passo: l'operazione e' in pausa, non fallita, e il passo e' quello scritto.
    assertWithMessage(describe()).that(only().state).isEqualTo(OpState.PAUSED)
    assertThat(only().pauseReason).isEqualTo(PauseReason.NAS_UNREACHABLE)
    assertThat(items().single().state).isEqualTo(OpItemState.RENAMING)
    assertThat(waits).containsExactly(1_000L)

    backend.faults = null
    app.engine.resumeAll(1.minutes)
    assertWithMessage(describe()).that(only().state).isEqualTo(OpState.DONE)
    // La rinomina e' avvenuta una volta sola, comunque sia andata la risposta.
    assertThat(backend.exists("test", "Gita/Mare/a.jpg")).isTrue()
    assertThat(backend.children("test", "")).containsExactly("Gita")
    assertThat(backend.openHandles).isEqualTo(0)
  }

  @Test
  fun laConnessioneCadeProprioSullaProvaDopoLAttesa() = bounded { faultOnTheRetry(FaultPhase.BEFORE, SimFault.DropConnection) }

  @Test
  fun laRispostaDellaProvaDopoLAttesaSiPerde() = bounded { faultOnTheRetry(FaultPhase.AFTER, SimFault.DropConnection) }

  @Test
  fun laSessioneScadeProprioSullaProvaDopoLAttesa() = bounded { faultOnTheRetry(FaultPhase.AFTER, SimFault.ExpireSession) }

  @Test
  fun ilProcessoMuoreDuranteLAttesaELaRipresaPortaATermine() = bounded {
    file("Foto/Mare/a.jpg")
    file("Foto/Mare/b.jpg")
    val hold = CompletableDeferred<Unit>()
    holdListing(app, "Foto/Mare", hold)
    onWait = { throw ProcessDeath() }

    var died = false
    try {
      app.start(OpRequest.Rename(app.folder("Foto"), "Gita"))
    } catch (_: ProcessDeath) {
      died = true
    }
    assertThat(died).isTrue()
    // Il diario dice il passo: la rinomina era in corso (scritta prima di farla), non finita.
    assertThat(only().state).isEqualTo(OpState.RUNNING)
    assertThat(items().single().state).isEqualTo(OpItemState.RENAMING)
    assertThat(backend.exists("test", "Foto/Mare/a.jpg")).isTrue()

    // Il sistema chiude i socket del processo morto: il NAS chiude i suoi handle. Ne parte uno nuovo, che riprende dal diario.
    backend.dropAllConnections()
    hold.complete(Unit)
    onWait = {}
    val second = AppProcess()
    second.engine.resumeAll(1.minutes)

    assertWithMessage(describe()).that(only().state).isEqualTo(OpState.DONE)
    assertThat(items().single().state).isEqualTo(OpItemState.DONE)
    assertThat(backend.exists("test", "Gita/Mare/a.jpg")).isTrue()
    assertThat(backend.children("test", "")).containsExactly("Gita")
    assertThat(waits).containsExactly(1_000L)
    runBlocking { second.manager.invalidate(InvalidateReason.USER_REQUEST) }
  }

  private companion object {
    const val USER = "test"
    const val PASSWORD = "pw-di-prova"
  }
}
