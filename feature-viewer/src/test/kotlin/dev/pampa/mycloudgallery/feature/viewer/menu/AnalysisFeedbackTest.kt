package dev.pampa.mycloudgallery.feature.viewer.menu

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * XS-6 (Pacchetto XF): lo stato dell'analisi del visore e' **legato al media** per cui e' nato e il riscontro di fine analisi vale **solo per la
 * richiesta fatta in questa visita**. Le prove usano un servizio con **barriere** (un canale per media: l'emissione arriva quando decide il
 * test), cioe' emissioni ritardate vere, e la stessa logica che usa il visore (`observeBound`, `observedFor`, `AnalysisFeedback`).
 *
 * Mutazioni eseguite: `observedFor` che ignora l'id (cade `ilValoreDiUnAltroMediaNonValePerQuelloAperto` e `ilRitornoPrimaDellaNuovaEmissione...`);
 * `AnalysisFeedback.moved` che non azzera (cade `unaRichiestaNonConclusaNonRestaRicordataFraLeFoto` e il riscontro tardivo).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AnalysisFeedbackTest {

  private fun id(n: Long) = MediaId(UUID(0L, n).toString())

  private val ready = MediaAnalysis(MediaAnalysisKind.READY)
  private val queued = MediaAnalysis(MediaAnalysisKind.QUEUED)

  /** Il servizio con una barriera per media: `observe(id)` emette solo cio' che il test manda a quel media. */
  private class Gated : ViewerAnalysis {
    private val gates = HashMap<MediaId, Channel<MediaAnalysis>>()

    private fun gate(id: MediaId) = gates.getOrPut(id) { Channel(Channel.UNLIMITED) }

    fun emit(id: MediaId, value: MediaAnalysis) {
      gate(id).trySend(value)
    }

    override fun observe(id: MediaId): Flow<MediaAnalysis> = flow { for (value in gate(id)) emit(value) }

    override fun analyzeNow(id: MediaId) = Unit
  }

  @Test
  fun ilValoreDiUnAltroMediaNonValePerQuelloAperto() {
    val a = id(1)
    val b = id(2)
    val stale = AnalysisObserved(a, ready)
    // Aperto B con ancora in mano il valore di A: "non si sa ancora", mai il READY di A.
    assertThat(observedFor(b, stale)).isEqualTo(MediaAnalysis.UNKNOWN)
    assertThat(observedFor(a, stale)).isEqualTo(ready)
    assertThat(observedFor(b, null)).isEqualTo(MediaAnalysis.UNKNOWN)
  }

  /** Il flusso legato parte da "non si sa" (mai dal valore di una foto di prima) e ogni emissione porta il suo media. */
  @Test
  fun ilFlussoLegatoPorteIlMediaPerCuiEnato() = runTest {
    val service = Gated()
    val a = id(1)
    val seen = ArrayList<AnalysisObserved>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) { service.observeBound(a).collect { seen += it } }
    service.emit(a, queued)
    service.emit(a, ready)

    assertThat(seen.map { it.id }.toSet()).containsExactly(a)
    assertThat(seen.map { it.state }).containsExactly(MediaAnalysis.UNKNOWN, queued, ready).inOrder()
    job.cancel()
  }

  /**
   * Lo scenario del critico: richiesta per B, navigazione ad A (gia' pronto), ritorno a B **prima** della nuova emissione. Fra il cambio di foto e
   * la partenza del producer di B in mano c'e' ancora il valore di A: non puo' diventare un tocco su B. La richiesta di prima non vale piu'
   * (l'utente e' uscito da B), quindi nemmeno la fine vera, arrivata in ritardo, da' un riscontro.
   */
  @Test
  fun ilRitornoPrimaDellaNuovaEmissioneNonProduceUnTocco() = runTest {
    val service = Gated()
    val feedback = AnalysisFeedback()
    val a = id(1)
    val b = id(2)
    var observed: AnalysisObserved? = null
    var job: Job? = null
    var taps = 0

    fun show(current: MediaId) {
      // Quello che fa `produceState` al cambio di foto: il producer si ferma e **ne parte uno nuovo, ma non subito** (finestra del difetto):
      // fino ad allora `observed` e' ancora quello della foto di prima.
      job?.cancel()
      job = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.LAZY) {
        observed = AnalysisObserved(current, MediaAnalysis.UNKNOWN)
        service.observeBound(current).collect { observed = it }
      }
      feedback.moved(current)
    }

    fun settle() {
      job?.start()
    }

    fun refresh(current: MediaId) {
      if (feedback.completed(current, observedFor(current, observed).kind)) taps++
    }

    show(b)
    settle()
    service.emit(b, queued)
    feedback.asked(b) // "Analizza adesso" su B
    show(a) // naviga ad A, gia' pronto
    settle()
    service.emit(a, ready)
    refresh(a)
    assertThat(taps).isEqualTo(0)

    // Ritorno a B prima che B emetta di nuovo: in mano c'e' ancora il READY di A, che non puo' diventare un tocco su B.
    show(b)
    assertThat(observed?.id).isEqualTo(a)
    assertThat(observedFor(b, observed).kind).isEqualTo(MediaAnalysisKind.UNKNOWN)
    refresh(b)
    assertThat(taps).isEqualTo(0)

    // E la fine vera, arrivata in ritardo, non da' un riscontro per una richiesta di un'altra visita.
    settle()
    service.emit(b, ready)
    refresh(b)
    assertThat(taps).isEqualTo(0)
    job?.cancel()
  }

  /** Una richiesta ancora valida per B non nasce dallo stato di un altro media: con in mano il READY di A, B non da' riscontro. */
  @Test
  fun unRiscontroNonNasceDalloStatoDiUnAltroMedia() {
    val feedback = AnalysisFeedback()
    val a = id(1)
    val b = id(2)
    feedback.moved(b)
    feedback.asked(b)
    val stale = AnalysisObserved(a, ready)
    assertThat(feedback.completed(b, observedFor(b, stale).kind)).isFalse()
    // Quando B dice davvero "pronto" il riscontro c'e'.
    assertThat(feedback.completed(b, observedFor(b, AnalysisObserved(b, ready)).kind)).isTrue()
  }

  @Test
  fun unaRichiestaNonConclusaNonRestaRicordataFraLeFoto() {
    val feedback = AnalysisFeedback()
    val a = id(1)
    val b = id(2)
    feedback.asked(b)
    feedback.moved(a)
    feedback.moved(b)
    assertThat(feedback.completed(b, MediaAnalysisKind.READY)).isFalse()
  }

  @Test
  fun unaRichiestaFattaInQuestaVisitaDaIlRiscontoUnaVoltaSola() {
    val feedback = AnalysisFeedback()
    val b = id(2)
    feedback.moved(b)
    feedback.asked(b)
    assertThat(feedback.completed(b, MediaAnalysisKind.QUEUED)).isFalse()
    assertThat(feedback.completed(b, MediaAnalysisKind.FIRST)).isFalse()
    assertThat(feedback.completed(b, MediaAnalysisKind.READY)).isTrue()
    assertThat(feedback.completed(b, MediaAnalysisKind.READY)).isFalse()
  }

  @Test
  fun unoStatoProntoDiUnaFotoNonChiestaDaQuiNonDaRiscontro() {
    val feedback = AnalysisFeedback()
    val a = id(1)
    val b = id(2)
    feedback.moved(a)
    // Pronta da sempre, nessuno l'ha chiesta: nessun tocco.
    assertThat(feedback.completed(a, MediaAnalysisKind.READY)).isFalse()
    // E una richiesta per B non da' un riscontro per A.
    feedback.asked(b)
    assertThat(feedback.completed(a, MediaAnalysisKind.READY)).isFalse()
  }

  // ---- XS2-5: la versione del media -------------------------------------------------------------------------------------------------------

  /**
   * Una richiesta fatta per una versione non da' riscontro per un'altra: la READY di un'altra versione (la vecchia, arrivata in ritardo, o la nuova dopo una
   * modifica) non diventa un tocco, e la richiesta finisce. Mutazione: ignorare la versione in `completed`.
   */
  @Test
  fun unaRichiestaPerUnaVersioneNonDaRiscontroPerUnAltra() {
    val feedback = AnalysisFeedback()
    val b = id(2)
    feedback.moved(b)
    feedback.asked(b, version = 2L) // il visore vedeva la versione 2 al momento del tocco
    assertThat(feedback.completed(b, MediaAnalysisKind.READY, version = 1L)).isFalse()
    // La richiesta e' finita senza riscontro: nemmeno la READY della versione giusta, dopo, da' un tocco.
    assertThat(feedback.completed(b, MediaAnalysisKind.READY, version = 2L)).isFalse()
  }

  @Test
  fun lAnalisiPerLaStessaVersioneDaRiscontro() {
    val feedback = AnalysisFeedback()
    val b = id(2)
    feedback.asked(b, version = 7L)
    assertThat(feedback.completed(b, MediaAnalysisKind.QUEUED, version = 7L)).isFalse()
    assertThat(feedback.completed(b, MediaAnalysisKind.READY, version = 7L)).isTrue()
  }

  /** Al cambio di versione dello stesso media il binding si azzera ("non si sa") prima del nuovo stato: niente stato della versione di prima. */
  @Test
  fun alCambioDiVersioneIlBindingSiAzzera() = runTest {
    val service = Gated()
    val b = id(2)
    val seen = ArrayList<AnalysisObserved>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) { service.observeBound(b).collect { seen += it } }
    service.emit(b, MediaAnalysis(MediaAnalysisKind.READY, version = 1L))
    service.emit(b, MediaAnalysis(MediaAnalysisKind.QUEUED, version = 2L))

    assertThat(seen.map { it.state.kind to it.state.version }).containsExactly(
      MediaAnalysisKind.UNKNOWN to null, // l'inizio
      MediaAnalysisKind.READY to 1L,
      MediaAnalysisKind.UNKNOWN to null, // la versione e' cambiata: azzerato
      MediaAnalysisKind.QUEUED to 2L,
    ).inOrder()
    job.cancel()
  }
}
