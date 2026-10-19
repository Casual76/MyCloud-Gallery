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
    assertThat(model.ui.value.nasReachable).isFalse() // "lo salvo appena risponde": le copie locali restano in coda
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
