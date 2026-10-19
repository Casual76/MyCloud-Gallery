package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.folders.NasFolderService
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * La verifica funzionale delle fasi 1-5A (docs/review/funzionale-fasi-1-5a.md), il NAS nella Libreria e l'organizzazione: le voci NA
 * che si provano sull'esecutore delle operazioni e su Cartelle NAS. Le prove di NA-1 girano su **entrambi** gli esecutori del kit
 * (quello di riferimento e quello vero): il finto non deve nascondere il difetto.
 */
class VerificaFunzionaleNasOpsTest {

  private val a = byteArrayOf(1, 2, 3)
  private val b = byteArrayOf(4, 5, 6)

  private suspend fun planOf(kit: NasOpsTestKit, request: OpRequest): OpPlan =
    (kit.operations.plan(request) as? PlanOutcome.Ready)?.plan ?: error("piano rifiutato")

  private fun states(kit: NasOpsTestKit): List<NasOperationState> = kit.operations.operations.value

  /** Una prova su thread veri: l'operazione che gira resta ferma dentro il NAS finto finche' il test non la rilascia. */
  private fun na1(engine: KitEngine, body: suspend CoroutineScope.(NasOpsTestKit, CountDownLatch, CountDownLatch) -> Unit) = runBlocking(Dispatchers.Default) {
    val kit = NasOpsTestKit(engine = engine)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val firstOnly = AtomicInteger()
    kit.nas.fault = FakeNasFault { op, _, _ ->
      if (op == FakeNasOp.TRASH_MOVE && firstOnly.getAndIncrement() == 0) {
        entered.countDown()
        check(release.await(30, TimeUnit.SECONDS)) { "la prova non ha rilasciato l'operazione" }
      }
      null
    }
    try {
      body(this, kit, entered, release)
    } finally {
      release.countDown()
    }
  }

  private fun na1Test(engine: KitEngine) = na1(engine) { kit, entered, release ->
    kit.putFile("test", "Lunga/a.jpg", a)
    kit.putFile("test", "Breve/b.jpg", b)
    val planA = planOf(kit, OpRequest.Trash(listOf(kit.itemOf("test", "Lunga/a.jpg"))))
    val planB = planOf(kit, OpRequest.Trash(listOf(kit.itemOf("test", "Breve/b.jpg"))))

    val first = async { kit.operations.start(planA, kit.confirm(planA)) }
    assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue()
    // Un'operazione nuova mentre ne gira una: prima aspettava, invisibile, la fine dell'altra.
    val second = async { kit.operations.start(planB, kit.confirm(planB)) }
    withTimeout(15_000) { while (states(kit).none { it is NasOperationState.Planned }) delay(10) }

    val shown = states(kit)
    assertWithMessage("la nuova e' nel diario e si vede in coda").that(shown.filterIsInstance<NasOperationState.Planned>()).hasSize(1)
    assertThat(shown.filterIsInstance<NasOperationState.Running>()).hasSize(1)
    assertWithMessage("in coda non ha toccato niente").that(kit.exists("test", "Breve/b.jpg")).isTrue()
    // E una richiesta identica, toccata di nuovo, non ne fa partire una terza.
    val again = kit.operations.start(planB, kit.confirm(planB))
    assertThat(again).isInstanceOf(StartOutcome.AlreadyStarted::class.java)

    release.countDown()
    assertThat(first.await()).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(second.await()).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(states(kit).filterIsInstance<NasOperationState.Done>()).hasSize(2)
    assertThat(kit.exists("test", "Lunga/a.jpg")).isFalse()
    assertThat(kit.exists("test", "Breve/b.jpg")).isFalse()
  }

  /**
   * NA-1: `start` teneva il lucchetto dell'esecuzione anche per creare la voce del diario. Un'operazione avviata mentre ne girava
   * una lunga (2.000 file fra le aree) non esisteva per nessuno: "Operazioni in corso" non la mostrava e partiva ore dopo.
   */
  @Test
  fun na1_unaOperazioneNuovaMentreNeGiraUnaEInCodaEVisibile_esecutoreVero() = na1Test(KitEngine.PRODUCTION)

  @Test
  fun na1_unaOperazioneNuovaMentreNeGiraUnaEInCodaEVisibile_esecutoreDiRiferimento() = na1Test(KitEngine.REFERENCE)

  /** "Ferma" su un'operazione in coda: non parte mai, e non tocca niente. */
  @Test
  fun na1_fermareUnaOperazioneInCodaLaAnnullaSenzaEseguirla() = na1(KitEngine.PRODUCTION) { kit, entered, release ->
    kit.putFile("test", "Lunga/a.jpg", a)
    kit.putFile("test", "Breve/b.jpg", b)
    val planA = planOf(kit, OpRequest.Trash(listOf(kit.itemOf("test", "Lunga/a.jpg"))))
    val planB = planOf(kit, OpRequest.Trash(listOf(kit.itemOf("test", "Breve/b.jpg"))))
    val first = async { kit.operations.start(planA, kit.confirm(planA)) }
    assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue()
    val second = async { kit.operations.start(planB, kit.confirm(planB)) }
    withTimeout(15_000) { while (states(kit).none { it is NasOperationState.Planned }) delay(10) }
    val queued = states(kit).filterIsInstance<NasOperationState.Planned>().single()

    val cancelling = launch { kit.operations.cancel(queued.id) }
    delay(100)
    release.countDown()
    first.await()
    second.await()
    cancelling.join()

    assertWithMessage("l'operazione ferma in coda non e' stata eseguita").that(kit.exists("test", "Breve/b.jpg")).isTrue()
    val finalState = states(kit).single { it.id == queued.id }
    assertThat(finalState).isInstanceOf(NasOperationState.Cancelled::class.java)
    assertThat(states(kit).filterIsInstance<NasOperationState.Done>()).hasSize(1)
  }

  // --- NA-3 -------------------------------------------------------------------------------------------------------------------

  private suspend fun NasFolderService.listing(share: String = "test", dir: SharePath? = null): NasFolderState.Listing =
    withTimeout(15_000) { observe(share, dir).first { it is NasFolderState.Listing } as NasFolderState.Listing }

  /**
   * NA-3: `observe` leggeva dal NAS solo la prima volta e poi mostrava per sempre quell'elenco: i file copiati dal PC o portati dal
   * backup non comparivano, e dopo uno spostamento l'origine li mostrava ancora (toccarli dava "non c'e' piu'"). Ora ogni volta che
   * si guarda la cartella si rilegge; nel frattempo si vede l'ultimo elenco, senza un attimo di vuoto.
   */
  @Test
  fun na3_riaprireUnaCartellaLaRilegge() = runBlocking<Unit> {
    val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
    kit.putFile("test", "Foto/a.jpg", a)
    val service = NasFolderService(kit.nas.reader, kit.clocks)
    assertThat(service.listing(dir = SharePath.parse("Foto")).entries.map { it.name }).containsExactly("a.jpg")

    // Dal PC arriva un file e se ne va un altro.
    kit.putFile("test", "Foto/b.jpg", b)
    kit.deleteFromPc("test", "Foto/a.jpg")

    val later = withTimeout(15_000) {
      var seen: NasFolderState.Listing
      do {
        seen = service.listing(dir = SharePath.parse("Foto"))
        if (seen.entries.map { it.name } == listOf("b.jpg")) break
        delay(20)
      } while (true)
      seen
    }
    assertThat(later.entries.map { it.name }).containsExactly("b.jpg")
  }

  /** Un NAS lento dalla seconda lettura in poi: la rilettura resta in volo finche' il test non la lascia andare. */
  private class SlowAfterFirst(private val inner: NasMediaReader, private val gate: CompletableDeferred<Unit>) : NasMediaReader by inner {
    private val calls = AtomicInteger()

    override suspend fun list(share: String, dir: SharePath?, purpose: Purpose, pageSize: Int, onPage: suspend (List<NasEntry>) -> Unit): NasResult<ListOutcome> {
      if (calls.incrementAndGet() > 1) gate.await()
      return inner.list(share, dir, purpose, pageSize, onPage)
    }
  }

  /** Chi riapre la cartella vede subito l'ultimo elenco (niente schermo vuoto) e poi, quando il NAS risponde, quello nuovo. */
  @Test
  fun na3_chiRiapreVedeSubitoL_ultimoElencoEPoiIlNuovo() = runBlocking<Unit> {
    val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
    kit.putFile("test", "Foto/a.jpg", a)
    val gate = CompletableDeferred<Unit>()
    val service = NasFolderService(SlowAfterFirst(kit.nas.reader, gate), kit.clocks)
    service.listing(dir = SharePath.parse("Foto"))
    kit.putFile("test", "Foto/b.jpg", b)

    val emitted = ArrayList<List<String>>()
    val watching = launch(Dispatchers.Default) {
      service.observe("test", SharePath.parse("Foto")).collect { state -> if (state is NasFolderState.Listing) synchronized(emitted) { emitted += state.entries.map { it.name } } }
    }
    withTimeout(15_000) { while (synchronized(emitted) { emitted.isEmpty() }) delay(10) }
    // Il NAS non ha ancora risposto: si vede l'elenco di prima.
    assertThat(synchronized(emitted) { emitted.toList() }).containsExactly(listOf("a.jpg"))
    gate.complete(Unit)
    withTimeout(15_000) { while (synchronized(emitted) { emitted.none { it.size == 2 } }) delay(10) }
    watching.cancel()
    assertThat(emitted.last()).containsExactly("a.jpg", "b.jpg")
  }

  /** Un lettore che legge subito ma risponde tardi la prima volta: il suo elenco, vecchio, non deve sovrascrivere uno piu' recente. */
  private class SlowFirstList(private val inner: NasMediaReader, private val gate: CompletableDeferred<Unit>) : NasMediaReader by inner {
    private val calls = AtomicInteger()

    override suspend fun list(share: String, dir: SharePath?, purpose: Purpose, pageSize: Int, onPage: suspend (List<NasEntry>) -> Unit): NasResult<ListOutcome> {
      val mine = calls.incrementAndGet()
      val result = inner.list(share, dir, purpose, pageSize, onPage)
      if (mine == 1) gate.await()
      return result
    }
  }

  /**
   * NA-3 (la gara): due riletture in volo, la prima ha letto prima e risponde dopo. Vince quella partita per ultima, non quella
   * arrivata per ultima.
   */
  @Test
  fun na3_unaLetturaVecchiaCheArrivaTardiNonSovrascriveQuellaNuova() = runBlocking<Unit> {
    val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
    kit.putFile("test", "Foto/a.jpg", a)
    val gate = CompletableDeferred<Unit>()
    val service = NasFolderService(SlowFirstList(kit.nas.reader, gate), kit.clocks)
    val stale = launch(Dispatchers.Default) { service.refresh("test", SharePath.parse("Foto")) }
    delay(200)

    // Cambia il NAS, e una seconda lettura (partita dopo) finisce per prima.
    kit.putFile("test", "Foto/b.jpg", b)
    service.refresh("test", SharePath.parse("Foto"))
    gate.complete(Unit)
    stale.join()

    val seen = withTimeout(15_000) { service.observe("test", SharePath.parse("Foto")).first { it is NasFolderState.Listing } as NasFolderState.Listing }
    assertThat(seen.entries.map { it.name }).containsExactly("a.jpg", "b.jpg")
  }
}
