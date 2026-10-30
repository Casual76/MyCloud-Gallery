package dev.pampa.mycloudgallery.faces

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.faces.compute.FacesRates
import dev.pampa.mycloudgallery.core.faces.compute.FacesTally
import dev.pampa.mycloudgallery.core.faces.compute.FacesWork
import dev.pampa.mycloudgallery.core.faces.contract.FaceInput
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.InputWait
import dev.pampa.mycloudgallery.core.index.ai.AiBufferSink
import dev.pampa.mycloudgallery.core.index.ai.AnalysisFetcher
import dev.pampa.mycloudgallery.core.index.ai.FetchReport
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.estimate.DeviceMeasures
import dev.pampa.mycloudgallery.core.rules.run.StageHalt
import dev.pampa.mycloudgallery.core.rules.run.StageScope
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.feature.people.contract.FacesAnalyzeEstimate
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Correzioni dopo le revisioni della Fase 8, pacchetto C, nella parte di `:app`: il calcolo dei volti (CA-1...CA-7), «Analizza ora» con la stima e lo stato «non ancora»
 * prima del montaggio (L350). Il grafo e' quello vero ma **non montato** dove basta; il montaggio vero e' in `FacesIntegrationTest` e nelle prove di integrazione.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FacesCorrectionsCTest {

  // ---- CA-6: `mayFetch` e' legato alle regole della tappa -----------------------------------------------------------------------------

  /** Una tappa di prova: solo l'ambito che le regole hanno detto (il resto non serve a `mayFetch`). */
  private class FakeStage(scope: RunScope) : StageScope<Unit> {
    override val request: WorkRequest = WorkRequest.VisibleContent
    override val run: RuleDecision.Run = RuleDecision.Run(scope = scope, pace = Pace.NORMAL, network = NetworkClass.HOME_WIFI)
    override val byteBudget: Long? = null
    override val stageBytes: Long = 0
    override val roundBytes: Long = 0
    override val halt: StageHalt? = null

    override suspend fun proceed(): Boolean = true

    override suspend fun record(bytes: Long, network: NetworkClass?) = Unit

    override suspend fun commit(checkpoint: Unit) = Unit
  }

  @Test
  fun ca6_conLeRegoleCheDiconoSoloInLocaleNonSiScarica() = runBlocking<Unit> {
    val control = StageFacesControl()
    // Fuori da una tappa niente gira e niente si scarica.
    assertThat(control.mayFetch()).isFalse()
    control.bind(FakeStage(RunScope.FULL)) { assertThat(control.mayFetch()).isTrue() }
    control.bind(FakeStage(RunScope.LOCAL_BUFFER_ONLY)) { assertThat(control.mayFetch()).isFalse() }
    assertThat(control.mayFetch()).isFalse()
  }

  private class RecordingFetcher : AnalysisFetcher {
    var calls = 0

    override suspend fun fetch(share: String, keys: List<ContentKey>, classes: Set<SegmentClass>, into: AiBufferSink, budget: Duration): FetchReport {
      calls++
      return FetchReport(fetched = 0, missing = keys.size, bytes = 0, stoppedEarly = false)
    }
  }

  private class EmptyBuffer : AiBuffer {
    override suspend fun put(key: ContentKey, cls: SegmentClass, bytes: ByteArray): Boolean = false

    override suspend fun get(key: ContentKey, cls: SegmentClass): ByteArray? = null

    override suspend fun evict(done: Collection<ContentKey>) = Unit

    override val usedBytes: StateFlow<Long> = MutableStateFlow(0L)
  }

  private fun item() = FacesItem(
    contentKey = ContentKey("%064x".format(7)),
    media = MediaId(UUID.randomUUID().toString()),
    kind = MediaKind.PHOTO,
    source = MediaSource.CAMERA,
    document = false,
    cutout = false,
    month = "2024-03",
    area = FacesArea.PRIVATE,
  )

  private fun inputs(fetcher: RecordingFetcher, mayFetch: () -> Boolean) = AppFaceInputs(
    buffer = EmptyBuffer(),
    fetcher = { fetcher },
    phone = null,
    preparer = { null },
    shares = FacesShares({ "Privata" }, { "Comune" }),
    mayFetch = mayFetch,
  )

  @Test
  fun ca6_senzaNasUnMediaSenzaAnteprimaAspettaIlNasENonProvaAScaricare() = runBlocking<Unit> {
    val fetcher = RecordingFetcher()
    val offline = inputs(fetcher) { false }

    val input = offline.preview(item())

    // Il motore vede «in attesa del NAS» (la pagina lo dice), non «nessuna anteprima»; nessuno scaricamento e' stato tentato.
    assertThat(input).isEqualTo(FaceInput.Waiting(InputWait.NAS))
    assertThat(fetcher.calls).isEqualTo(0)
    offline.prefetch(listOf(item()))
    assertThat(fetcher.calls).isEqualTo(0)
  }

  @Test
  fun ca6_conIlNasUnMediaSenzaAnteprimaProvaAScaricareEAspettaLeAnteprime() = runBlocking<Unit> {
    val fetcher = RecordingFetcher()
    val online = inputs(fetcher) { true }

    val input = online.preview(item())

    assertThat(input).isEqualTo(FaceInput.Waiting(InputWait.NO_PREVIEW))
    assertThat(fetcher.calls).isEqualTo(1)
  }

  // ---- CA-2: «Analizza ora» ---------------------------------------------------------------------------------------------------------------

  private val s25 = FacesRates.forPhotosPerSecond(DeviceMeasures.PHASE_0.facesPerSecond)

  private fun analyze(
    tally: FacesTally? = FacesTally(done = 0, work = FacesWork(photos = 11_000, videos = 1_000)),
    decision: RuleDecision = RuleDecision.Run(network = NetworkClass.HOME_WIFI),
    requests: MutableList<String> = ArrayList(),
    bytes: MutableList<Long> = ArrayList(),
  ) = AppFacesAnalyzeNow(
    tally = { tally },
    rates = { s25 },
    decide = { b ->
      bytes += b
      decision
    },
    request = { requests += "start" },
    io = UnconfinedTestDispatcher(),
  )

  @Test
  fun ca2_laStimaVieneDalConteggioEDalleOttoVirgolaOttoFotoAlSecondo() = runTest {
    val estimate = analyze().estimate()!!

    assertThat(estimate.remaining).isEqualTo(12_000)
    assertThat(estimate.done).isEqualTo(0)
    assertThat(estimate.isFirstRun).isTrue()
    // 11.000 foto (0,1136 s + il 20% con la seconda passata da 0,344 s) e 1.000 video (quattro foto l'uno): circa 42 minuti (con l'ipotesi dei 25 al secondo sarebbero stati la meta').
    assertThat(estimate.minutes).isEqualTo(42)
    // Al piu' le anteprime da scaricare: 150 KB l'una, quattro per un video.
    assertThat(estimate.bytes).isEqualTo((11_000L + 1_000L * 4) * DeviceMeasures.PHASE_0.analysisPreviewBytes)
  }

  @Test
  fun ca2_senzaPersoneAttiveQuiNonSiSaONonCeNiente() = runTest {
    assertThat(analyze(tally = null).estimate()).isNull()
    assertThat(analyze(tally = FacesTally.NONE).estimate()!!.remaining).isEqualTo(0)
    // Un conto che lancia non e' un crollo: «non si sa».
    val failing = AppFacesAnalyzeNow(tally = { error("catalogo") }, rates = { s25 }, decide = { RuleDecision.Run(network = NetworkClass.HOME_WIFI) }, request = {}, io = UnconfinedTestDispatcher())
    assertThat(failing.estimate()).isNull()
  }

  @Test
  fun ca2_unaLibreriaMezzaFattaDiceQuantoResta() = runTest {
    val estimate = analyze(tally = FacesTally(done = 7_800, work = FacesWork(photos = 4_200, videos = 0))).estimate()!!
    assertThat(estimate.isFirstRun).isFalse()
    assertThat(estimate.remaining).isEqualTo(4_200)
    assertThat(estimate.done).isEqualTo(7_800)
  }

  @Test
  fun ca2_suiDatiMobiliLaConfermaPortaLaStimaDeiDatiDelleRegole() = runTest {
    val asked = ArrayList<Long>()
    val confirmation = RuleDecision.NeedsConfirmation(
      kinds = setOf(dev.pampa.mycloudgallery.core.rules.ConfirmationKind.MOBILE_DATA),
      estimate = dev.pampa.mycloudgallery.core.rules.TrafficEstimate(bytes = 1_800_000_000L, monthlyUsedBytes = 0, monthlyCapBytes = null),
    )
    val mobile = analyze(decision = confirmation, bytes = asked)
    val estimate = mobile.estimate()!!

    val note = mobile.networkNote(estimate)

    assertThat(note).contains("dati mobili")
    assertThat(note).contains("Servono circa")
    // Le regole hanno ricevuto la stima dei dati.
    assertThat(asked).containsExactly(estimate.bytes)
    // Sul Wi-Fi di casa le regole non chiedono niente: nessuna frase.
    assertThat(analyze().networkNote(FacesAnalyzeEstimate(10, 0, 1, 1_000))).isNull()
    // E se le regole dicono «aspetta» (offline, caldo) non e' una conferma.
    assertThat(analyze(decision = RuleDecision.Wait(dev.pampa.mycloudgallery.core.rules.WaitReason.Offline)).networkNote(estimate)).isNull()
  }

  @Test
  fun ca2_ilTocco_chiedeIlCalcoloAdesso() = runTest {
    val requests = ArrayList<String>()
    val port = analyze(requests = requests)
    assertThat(requests).isEmpty()
    port.start()
    assertThat(requests).containsExactly("start")
  }

  @Test
  fun ca2_ilRitmoDiPartenzaDelGrafoELaMisuraDelCampo() {
    assertThat(DeviceMeasures.PHASE_0.facesPerSecond).isEqualTo(8.8)
    // Le due misure non possono scostarsi: `FacesRates.DEFAULT` (core-faces, che non vede le regole) e `DeviceMeasures` (core-rules).
    assertThat(FacesRates.DEFAULT).isEqualTo(FacesRates.forPhotosPerSecond(DeviceMeasures.PHASE_0.facesPerSecond))
  }

  // ---- L350: la facciata dell'interruttore non dice «spenta» prima del montaggio ---------------------------------------------------------

  @Test
  fun l350_primaDelMontaggioLoStatoNonEnoto_DopoIlMontaggioFallitoOImpossibileSi() = runTest(UnconfinedTestDispatcher()) {
    val graph = FacesGraph(backgroundScope, { null }, MutableStateFlow(false))
    val switch = FacesSwitchFacade(graph, backgroundScope)
    // Non ancora: l'interruttore risponde `Off` come un null-object, ma non e' la verita' (la sezione non c'e', la pagina e' uno scheletro).
    assertThat(switch.known.value).isFalse()
    assertThat(AppPeopleEntryStatus(switch, backgroundScope).summary.value.state)
      .isEqualTo(dev.pampa.mycloudgallery.feature.settings.people.PeopleEntryState.NOT_YET)

    // Un montaggio che non puo' esserci adesso ha comunque la sua risposta: «non disponibile» e' vero.
    graph.ensureLive()
    assertThat(switch.known.value).isTrue()
    assertThat(AppPeopleEntryStatus(switch, backgroundScope).summary.value.state)
      .isEqualTo(dev.pampa.mycloudgallery.feature.settings.people.PeopleEntryState.OFF)
  }

  @Test
  fun l350_unMontaggioFallitoAvevaGiaLaSuaRisposta() = runTest(UnconfinedTestDispatcher()) {
    val graph = FacesGraph(backgroundScope, { throw IllegalStateException("guasto del montaggio") }, MutableStateFlow(false))
    val switch = FacesSwitchFacade(graph, backgroundScope)
    assertThat(switch.known.value).isFalse()
    graph.ensureLive()
    assertThat(graph.mountFailed).isTrue()
    assertThat(switch.known.value).isTrue()
  }

  @Test
  fun l350_senzaFamigliaLeCoseNonSiMonteranno_LoStatoENotoSubito() = runTest(UnconfinedTestDispatcher()) {
    val graph = FacesGraph(backgroundScope, { null }, MutableStateFlow(false), canMount = false)
    assertThat(FacesSwitchFacade(graph, backgroundScope).known.value).isTrue()
  }

  @Test
  fun l350_loStatoDiventaNotoConIlMontaggioVero() = runTest(UnconfinedTestDispatcher()) {
    // Un montaggio riuscito: `build` risponde con qualcosa. Qui basta sapere che non si arriva a noto prima: lo prova la prova con `canMount`.
    val graph = FacesGraph(backgroundScope, { null }, MutableStateFlow(false))
    assertThat(graph.settled.value).isFalse()
    graph.stop()
    // Dopo l'azzeramento (stop) non c'e' piu' niente da aspettare.
    assertThat(graph.settled.value).isTrue()
  }

  @Test
  fun l350_inullObjectEIFakeSonoNotiDiSerie() {
    // I null-object e i fake sono sempre «noti»: una porta non legata dice «spenta e non accendibile» ed e' la verita'.
    assertThat(dev.pampa.mycloudgallery.core.faces.noop.NoFacesSwitch.known.value).isTrue()
    assertThat(dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch().known.value).isTrue()
  }
}
