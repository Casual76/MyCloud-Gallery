package dev.pampa.mycloudgallery.feature.integrations.receive

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingReject
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.FakeIncomingDestinations
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.FakeIncomingQueue
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.OpenCounter
import dev.pampa.mycloudgallery.feature.integrations.receive.testing.incomingStream
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B, P11 (D24, C17, E-X2, E-X7, E-X8, E-S11): lo stato di "Salva sul NAS" con la copia vera, il diario finto e il cancello scelto dal test.
 * Con l'app bloccata non si apre nessun flusso; la copia parte da sola, il caricamento **mai**; "Salva sul NAS" e' un tocco e la conferma nasce da li';
 * annullare e chiudere senza scegliere tolgono le copie.
 */
class ReceiveViewModelTest {

  @get:Rule val folder = TemporaryFolder()

  private val gate = FakeEntryGate(EntryDecision.ALLOWED)
  private val queue = FakeIncomingQueue()
  private val destinations = FakeIncomingDestinations()
  private val clock = FakeBackupClock()
  private val scope = CoroutineScope(Job() + Dispatchers.Default)
  private lateinit var uploads: DefaultIncomingUploads

  private val incoming: File get() = File(folder.root, "incoming")

  @Before
  fun setUp() {
    FlowCounters.reset()
    uploads = DefaultIncomingUploads(IncomingStager(incoming, freeBytes = { 100L shl 30 }, bounds = ImageBoundsReader { null }), queue, clock)
  }

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun model(streams: List<IncomingStream> = defaultStreams(), overflow: Int = 0, dest: FakeIncomingDestinations = destinations) =
    ReceiveViewModel(
      streams = { asked++; streams },
      overflow = overflow,
      gate = gate,
      uploads = uploads,
      destinations = dest,
      monotonicMs = { clock.monotonicMs() },
      scope = scope,
    )

  /** Quante volte la schermata ha chiesto i flussi dell'altra app: con l'app bloccata, mai. */
  private var asked = 0

  private fun defaultStreams(): List<IncomingStream> = listOf(
    incomingStream(ReceiveSamples.jpeg(3_000, 1)),
    incomingStream(ReceiveSamples.png(3_500, 2)),
    incomingStream(ReceiveSamples.mp4(5_000)),
  )

  private fun waitFor(model: ReceiveViewModel, screen: ReceiveScreenState) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (model.ui.value.screen != screen && System.nanoTime() < deadline) Thread.sleep(10)
    assertThat(model.ui.value.screen).isEqualTo(screen)
  }

  private fun stageDirs(): List<String> = incoming.listFiles().orEmpty().map { it.name }

  // --- Il cancello -----------------------------------------------------------------------------------------------------------

  @Test
  fun conLAppBloccataNonSiApreNienteEFinoAllOSbloccoNullaSiCopia_E_X7() {
    gate.decision = EntryDecision.NEEDS_UNLOCK
    val model = model()

    model.refreshGate()
    model.refreshGate()

    assertThat(model.ui.value.screen).isEqualTo(ReceiveScreenState.LOCKED)
    assertThat(asked).isEqualTo(0) // i flussi non sono stati nemmeno richiesti
    assertThat(stageDirs()).isEmpty()
    assertThat(model.ui.value.stage).isNull()

    // Lo sblocco: una volta per volta; se non riesce, lo dice; se riesce, il cancello si apre e solo allora si copia.
    assertThat(model.beginUnlock()).isTrue()
    assertThat(model.beginUnlock()).isFalse()
    model.endUnlock(false)
    assertThat(model.ui.value.unlockFailed).isTrue()
    assertThat(asked).isEqualTo(0)
    assertThat(model.beginUnlock()).isTrue()
    gate.decision = EntryDecision.ALLOWED
    model.endUnlock(true)
    waitFor(model, ReceiveScreenState.READY)
    assertThat(asked).isEqualTo(1)
  }

  @Test
  fun appNonPronta_NienteSiOffreENienteSiCopia() {
    gate.decision = EntryDecision.NOT_READY
    val model = model()

    model.refreshGate()

    assertThat(model.ui.value.screen).isEqualTo(ReceiveScreenState.NOT_READY)
    assertThat(asked).isEqualTo(0)
    assertThat(stageDirs()).isEmpty()
  }

  @Test
  fun seIlBloccoSiRichiudeMentreSiScegliePerSchermaTornaASoloLoSbloccoENonSiSalva() {
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)
    assertThat(model.ui.value.photos).isEqualTo(2)

    gate.decision = EntryDecision.NEEDS_UNLOCK
    model.refreshGate()

    // Il riepilogo e le cartelle non restano in vista; il salvataggio non parte.
    val shown = model.ui.value
    assertThat(shown.screen).isEqualTo(ReceiveScreenState.LOCKED)
    assertThat(shown.stage).isNull()
    assertThat(shown.choices).isEmpty()
    model.save()
    assertThat(queue.enqueued).isEmpty()

    // Allo sblocco lo stato vero riappare, com'era.
    gate.decision = EntryDecision.ALLOWED
    model.refreshGate()
    assertThat(model.ui.value.screen).isEqualTo(ReceiveScreenState.READY)
    assertThat(model.ui.value.photos).isEqualTo(2)
    assertThat(asked).isEqualTo(1) // i file non sono stati ricopiati
  }

  // --- La copia, la scelta, il tocco -------------------------------------------------------------------------------------------

  @Test
  fun lACopiaPartiDaSolaIlCaricamentoMai_E_S11() {
    val model = model()

    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)

    val ui = model.ui.value
    assertThat(ui.photos).isEqualTo(2)
    assertThat(ui.videos).isEqualTo(1)
    assertThat(ui.bytes).isEqualTo(3_000L + 3_500 + 5_000)
    assertThat(ui.selected).isEqualTo(FakeIncomingDestinations.defaultRoots().first())
    assertThat(ui.commonNotice).isFalse()
    assertThat(stageDirs()).hasSize(1) // le copie ci sono, sul telefono
    // Ma niente e' stato messo in coda: serve il tocco.
    assertThat(queue.enqueued).isEmpty()
    assertThat(model.outcome.value).isNull()
  }

  @Test
  fun ilTocco_SalvaInCodaEMostraIlNumeroEIlNasCheNonRisponde_E_X8() {
    destinations.reachable = false
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)

    model.save()
    waitFor(model, ReceiveScreenState.DONE)

    assertThat(model.ui.value.queued).isEqualTo(3)
    // IN-11: "In coda" subito; la prova del NAS corregge la frase dopo, senza far aspettare la schermata.
    waitUntil { model.ui.value.nasWait == NasWait.OFFLINE }
    assertThat(model.ui.value.nasWait).isEqualTo(NasWait.OFFLINE) // "lo salvo appena risponde": le copie locali restano in coda
    assertThat(queue.enqueued.single().second).hasSize(3)
    assertThat(stageDirs()).hasSize(1) // i file restano finche' il diario li cita
    // Chiudere dopo "in coda" non li tocca.
    model.finish()
    assertThat(model.outcome.value).isEqualTo(ReceiveOutcome.FINISHED)
  }

  @Test
  fun scegliereLAreaComuneMostraLAvvisoENavigareFraLeCartelle() {
    val parent = FakeIncomingDestinations.choice("Ricevute")
    val child = FakeIncomingDestinations.choice("Ricevute/Mare", DestinationArea.PRIVATE, "Ricevute/Mare")
    val common = FakeIncomingDestinations.choice("Famiglia", DestinationArea.COMMON, "Famiglia")
    val tree = FakeIncomingDestinations(roots = listOf(parent, common), children = mapOf(parent to listOf(child)))
    val model = model(dest = tree)
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)

    model.select(common)
    assertThat(model.ui.value.commonNotice).isTrue()
    model.select(parent)
    assertThat(model.ui.value.commonNotice).isFalse()

    model.open(parent)
    waitUntil { model.ui.value.trail == listOf(parent) }
    assertThat(model.ui.value.choices).containsExactly(child)
    assertThat(model.ui.value.selected).isEqualTo(parent)

    model.up()
    waitUntil { model.ui.value.trail.isEmpty() }
    assertThat(model.ui.value.choices).containsExactly(parent, common).inOrder()
  }

  private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    assertThat(condition()).isTrue()
  }

  // --- Rifiuti e fallimenti ----------------------------------------------------------------------------------------------------

  @Test
  fun seNienteESalvabileLaSchermataDiceIPerche_ENienteResta() {
    val counter = OpenCounter()
    val streams = listOf(
      counter.stream(IncomingOrigin.FILE_SCHEME),
      counter.stream(IncomingOrigin.OWN_PROVIDER),
      incomingStream(ReceiveSamples.fakeJpegThatIsHtml()),
    )
    val model = model(streams, overflow = 7)

    model.refreshGate()
    waitFor(model, ReceiveScreenState.NOTHING)

    assertThat(counter.opened).isEqualTo(0)
    assertThat(model.ui.value.rejected).containsExactly(
      IncomingReject.FILE_SCHEME, 1,
      IncomingReject.OWN_PROVIDER, 1,
      IncomingReject.NOT_MEDIA, 1,
      IncomingReject.TOO_MANY, 7,
    )
    assertThat(stageDirs()).isEmpty()
  }

  @Test
  fun senzaUnaCartellaDoveSalvareLeCopieSiTolgonoSubito() {
    val model = model(dest = FakeIncomingDestinations(roots = emptyList()))

    model.refreshGate()
    waitFor(model, ReceiveScreenState.NOTHING)

    assertThat(model.ui.value.rejected).isEmpty() // non e' un rifiuto dei file: manca la cartella
    assertThat(model.ui.value.noFolder).isTrue()
    waitUntil { stageDirs().isEmpty() }
  }

  /** IN-11: dei file buoni senza cartella, e altri rifiutati: la schermata dice che manca la cartella (prima: "nessuno dei file si puo' salvare"). */
  @Test
  fun in11_conDeiRifiutiESenzaCartellaLaSchermataDiceCheMancaLaCartella() {
    val streams = listOf(incomingStream(ReceiveSamples.jpeg(3_000, 1)), incomingStream(ReceiveSamples.fakeJpegThatIsHtml()))
    val model = model(streams, dest = FakeIncomingDestinations(roots = emptyList()))

    model.refreshGate()
    waitFor(model, ReceiveScreenState.NOTHING)

    assertThat(model.ui.value.rejected).containsExactly(IncomingReject.NOT_MEDIA, 1)
    assertThat(model.ui.value.noFolder).isTrue() // ci sono dei rifiuti, ma il motivo principale e' la cartella che manca
    waitUntil { stageDirs().isEmpty() }
  }

  @Test
  fun unSalvataggioCheFallisceDiceIlMotivoEIlRiprovaTornaAllaScelta() {
    queue.allowed = false
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)

    model.save()
    waitFor(model, ReceiveScreenState.FAILED)
    assertThat(model.ui.value.failure).isEqualTo(ReceiveFailure.DESTINATION)
    assertThat(queue.enqueued).isEmpty()

    model.retry()
    assertThat(model.ui.value.screen).isEqualTo(ReceiveScreenState.READY)
    queue.allowed = true
    model.save()
    waitFor(model, ReceiveScreenState.DONE)
  }

  /**
   * IN-12 della verifica funzionale 5B: `save` intercettava solo `IOException`; un errore del database (disco pieno: `SQLiteException` di Room) o una
   * qualunque `RuntimeException` chiudeva l'app. Adesso e' "non disponibile" e si puo' riprovare.
   */
  @Test
  fun in12_unErroreCheNonEDiIoNonChiudeLAppEMostraNonDisponibile() {
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)

    queue.failWith = android.database.sqlite.SQLiteFullException("disco pieno")
    model.save()
    waitFor(model, ReceiveScreenState.FAILED)
    assertThat(model.ui.value.failure).isEqualTo(ReceiveFailure.UNAVAILABLE)

    queue.failWith = IllegalStateException("altro")
    model.retry()
    waitFor(model, ReceiveScreenState.READY)
    model.save()
    waitFor(model, ReceiveScreenState.FAILED)
    assertThat(model.ui.value.failure).isEqualTo(ReceiveFailure.UNAVAILABLE)

    queue.failWith = null
    model.retry()
    model.save()
    waitFor(model, ReceiveScreenState.DONE)
  }

  /** IN-12: dei file copiati che al tocco non si leggono piu' non spariscono in silenzio: "In coda" dice quanti non ci sono. */
  @Test
  fun in12_ISalvatiCheNonSiLeggonoPiuSiContano() {
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)
    queue.skipOne = true // il diario vero salta il file di cui non legge piu' l'impronta

    model.save()
    waitFor(model, ReceiveScreenState.DONE)

    assertThat(model.ui.value.queued).isEqualTo(2)
    assertThat(model.ui.value.notQueued).isEqualTo(1)
  }

  // --- Cancello: a processo freddo (IN-3) e backup in prova (IN-6) ----------------------------------------------------------

  /**
   * IN-3 della verifica funzionale 5B: a processo freddo l'app sta ancora leggendo il suo stato. La schermata non decide e non mostra niente (prima:
   * "Prima configura MyCloud Gallery"), aspetta, e quando lo stato c'e' decide da sola, senza un ritorno in primo piano.
   */
  @Test
  fun in3_aFreddoLaSchermataAspettaLoStatoEPoiCopiaDaSola() {
    gate.decision = EntryDecision.LOADING
    val model = model()

    model.refreshGate()
    Thread.sleep(150)
    assertThat(model.ui.value.screen).isEqualTo(ReceiveScreenState.CHECKING) // niente "non pronta", niente sblocco, niente copia
    assertThat(asked).isEqualTo(0)

    gate.decision = EntryDecision.ALLOWED
    waitFor(model, ReceiveScreenState.READY)
    assertThat(asked).isEqualTo(1)
  }

  @Test
  fun in3_aFreddoPoiBloccataMostraLoSbloccoENonNonPronta() {
    gate.decision = EntryDecision.LOADING
    val model = model()
    model.refreshGate()
    gate.decision = EntryDecision.NEEDS_UNLOCK
    waitFor(model, ReceiveScreenState.LOCKED)
    assertThat(asked).isEqualTo(0)
  }

  /** Se lo stato non arriva mai non si offre niente (fallire chiusi) e il prossimo ritorno in primo piano riprova. */
  @Test
  fun in3_seLoStatoNonArrivaNonSiOffreNiente_EIlRitornoRiprova() {
    gate.loadTimeoutMs = 80
    gate.decision = EntryDecision.LOADING
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.NOT_READY)
    assertThat(asked).isEqualTo(0)

    gate.decision = EntryDecision.ALLOWED
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)
  }

  /** IN-6: il backup in prova (D12, R2) non scrive: si dice **prima di copiare**. Prima: fino a 4 GiB copiati e poi "In quella cartella non si puo' scrivere". */
  @Test
  fun in6_ConIlBackupInProvaNonSiCopiaNienteELaSchermataDiceInProva() {
    destinations.trialOnly = true
    val model = model()

    model.refreshGate()
    waitFor(model, ReceiveScreenState.TEST_ONLY)

    assertThat(asked).isEqualTo(0) // i flussi dell'altra app non si sono nemmeno aperti
    assertThat(stageDirs()).isEmpty()
    assertThat(queue.enqueued).isEmpty()
  }

  /** IN-6: se il diario rifiuta perche' e' diventato "in prova" dopo la copia, il motivo e' quello e non "quella cartella". */
  @Test
  fun in6_SeLaProvaArrivaDopoLaCopiaIlMotivoEInProva() {
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)
    queue.allowed = false
    destinations.trialOnly = true

    model.save()
    waitFor(model, ReceiveScreenState.FAILED)

    assertThat(model.ui.value.failure).isEqualTo(ReceiveFailure.TEST_ONLY)
    model.retry() // non c'e' da riprovare: chiude
    assertThat(model.outcome.value).isEqualTo(ReceiveOutcome.FINISHED)
  }

  // --- "In coda": la prova del NAS non fa aspettare (IN-11) -----------------------------------------------------------------------

  @Test
  fun in11_InCodaArrivaSubitoELaProvaDelNasCorreggeLaFraseDopo() {
    destinations.wait = NasWait.METERED
    destinations.nasProbeDelayMs = 400
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)

    model.save()
    waitFor(model, ReceiveScreenState.DONE)
    // La schermata e' gia' "In coda" mentre la prova (che col NAS spento dura fino a 5 s) non e' finita.
    assertThat(model.ui.value.nasWait).isEqualTo(NasWait.NONE)
    assertThat(model.ui.value.queued).isEqualTo(3)

    waitUntil { model.ui.value.nasWait == NasWait.METERED }
  }

  @Test
  fun seICopieSonoSparite_Riprovare_ChiudeTutto() {
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)
    incoming.deleteRecursively()

    model.save()
    waitFor(model, ReceiveScreenState.FAILED)
    assertThat(model.ui.value.failure).isEqualTo(ReceiveFailure.GONE)

    model.retry()
    assertThat(model.outcome.value).isEqualTo(ReceiveOutcome.FINISHED)
  }

  // --- Chiudere senza scegliere ------------------------------------------------------------------------------------------------

  @Test
  fun annullareTogliLeCopieEChiudeSenzaCaricareNiente() {
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)
    assertThat(stageDirs()).hasSize(1)

    model.cancel()

    assertThat(model.outcome.value).isEqualTo(ReceiveOutcome.FINISHED)
    waitUntil { stageDirs().isEmpty() }
    assertThat(queue.enqueued).isEmpty()
  }

  @Test
  fun chiudereLActivityConLIndietroOLaSwipeSenzaScegliereTogliLeCopie() {
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)
    assertThat(stageDirs()).hasSize(1)

    // L'activity finisce: il ViewModel viene ripulito dal suo store.
    val store = ViewModelStore()
    store.put("receive", model)
    store.clear()

    waitUntil { stageDirs().isEmpty() }
    assertThat(queue.enqueued).isEmpty()
  }

  @Test
  fun chiudereDopoIlSalvataggioNonTogliLeCopieDelDiario() {
    val model = model()
    model.refreshGate()
    waitFor(model, ReceiveScreenState.READY)
    model.save()
    waitFor(model, ReceiveScreenState.DONE)

    val store = ViewModelStore()
    store.put("receive", model)
    store.clear()
    Thread.sleep(100)

    assertThat(stageDirs()).hasSize(1)
  }
}
