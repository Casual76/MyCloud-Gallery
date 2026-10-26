package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.ai.testing.FakeDeviceProbe
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextReadingSwitch
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.testing.FakeModelManager
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.feature.search.contract.AreaKind
import dev.pampa.mycloudgallery.feature.search.contract.IndexerBacklog
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingAccepted
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** La pagina Ricerca e modelli (Fase 6, P8, §2.6; D15, D20, D41; Q3, Q13): lo stato composto dalle porte e le richieste dell'utente. */
class SearchSettingsControllerTest {

  private val engine = ScriptedEngine { library().results() }
  private val history = InMemorySearchHistory()
  private val models = FakeModelManager()
  private val indexer = FakeIndexerControl(backlog = IndexerBacklog(13_760, listOf(AreaKind.COMMON_AREA)))
  private val reading = FakeTextReadingSwitch()
  private val accepted = object : TextReadingAccepted {
    override val acceptedVersion = MutableStateFlow(0)
  }
  private val probe = FakeDeviceProbe(ProbeResult(imagesPerSecond = 2.8, backend = AiBackend.GPU, coldStartMs = 3_600))

  private fun TestScope.controller(withIndexer: Boolean = true, withReading: Boolean = true) = SearchSettingsController(
    indexer = if (withIndexer) indexer else null,
    models = models,
    engine = engine,
    history = history,
    textReading = if (withReading) reading else null,
    accepted = if (withReading) accepted else null,
    probe = probe,
    scope = backgroundScope,
  ).also { runCurrent() }

  @Test
  fun accendereIlRuoloProponeIl440MELanciaLaMisuraConLaStima_D15() = runTest {
    val c = controller()
    assertThat(c.state.value.indexer.indexer).isFalse()
    c.setIndexer(true)
    runCurrent()
    assertThat(indexer.changes).containsExactly(true)
    // MO-1: la pagina non scarica nel proprio scope (uscire dalla pagina fermerebbe lo scaricamento): il 440M lo chiede `IndexerControl.setIndexer`
    // dell'app, come lavoro accelerato (AiWorkSchedulingTest). Qui la pagina non chiama il gestore dei modelli.
    assertThat(models.ensured).isEmpty()
    assertThat(probe.measures).isEqualTo(1)
    val state = c.state.value.indexer
    assertThat(state.indexer).isTrue()
    assertThat(state.probe?.imagesPerSecond).isEqualTo(2.8)
    assertThat(state.toAnalyze).isEqualTo(13_760)
    assertThat(state.nights).isEqualTo(1)
    assertThat(state.areasWithoutIndexer).containsExactly(AreaKind.COMMON_AREA)
  }

  @Test
  fun spegnereIlRuoloNonScaricaNeMisuraNiente() = runTest {
    indexer.indexer.value = true
    val c = controller()
    c.setIndexer(false)
    runCurrent()
    assertThat(indexer.changes).containsExactly(false)
    assertThat(models.ensured).isEmpty()
    assertThat(probe.measures).isEqualTo(0)
    assertThat(c.state.value.indexer.indexer).isFalse()
  }

  @Test
  fun unaMisuraAllaVoltaEUnGuastoNonFermaLaPagina() = runTest {
    val c = controller()
    indexer.failure = IllegalStateException("NAS non risponde")
    c.setIndexer(true)
    runCurrent()
    assertThat(c.state.value.indexer.indexer).isFalse()
    c.measure()
    c.measure()
    runCurrent()
    assertThat(probe.measures).isAtLeast(1)
    assertThat(c.measuring.value).isFalse()
  }

  @Test
  fun lIndicizzaOraEUnaRichiesta_D22() = runTest {
    val c = controller()
    c.indexNow()
    assertThat(indexer.indexNowCalls).isEqualTo(1)
  }

  @Test
  fun lo_StatoDelCalcoloArrivaDallaPorta() = runTest {
    val c = controller()
    indexer.status.value = AiStatus.Running(done = 1_240, total = 15_000, etaMinutes = 95, backend = AiBackend.GPU)
    runCurrent()
    assertThat((c.state.value.indexer.status as AiStatus.Running).done).isEqualTo(1_240)
  }

  @Test
  fun iModelliSonoTreEIlRitaglioNonSiToglie_D44() = runTest {
    models.set(ModelCatalog.QUERY_270M.id, ModelState.Ready(ModelCatalog.QUERY_270M.sizeBytes, ModelSourceKind.NAS, 0))
    val c = controller()
    val rows = c.state.value.models.models
    assertThat(rows.map { it.id }).containsExactly(ModelCatalog.QUERY_270M.id, ModelCatalog.INDEX_440M.id, ModelCatalog.CUTOUT_MAGICTOUCH.id).inOrder()
    assertThat(rows.first().source).isEqualTo(ModelSourceKind.NAS)
    assertThat(rows[1].state).isEqualTo(ModelState.Absent)
    val cutout = rows.last()
    assertThat(cutout.removable).isFalse()
    assertThat(cutout.state).isInstanceOf(ModelState.Ready::class.java)
    c.removeModel(ModelCatalog.CUTOUT_MAGICTOUCH.id)
    runCurrent()
    assertThat(models.removed).isEmpty()
    c.removeModel(ModelCatalog.QUERY_270M.id)
    runCurrent()
    assertThat(models.removed).containsExactly(ModelCatalog.QUERY_270M.id)
  }

  @Test
  fun scaricareUnModelloEUnaRichiestaDellUtente() = runTest {
    val c = controller()
    c.downloadModel(ModelCatalog.INDEX_440M.id)
    runCurrent()
    assertThat(models.ensured).containsExactly(ModelCatalog.INDEX_440M.id to ModelTrigger.USER_NOW)
  }

  @Test
  fun laLetturaDelTestoPartSpentaESiAccendeSoloConIlConsenso_D41() = runTest {
    val c = controller()
    assertThat(c.state.value.textReading.state).isEqualTo(TextReadingState.Off)
    assertThat(c.state.value.textReading.needsConsent).isTrue()
    c.enableTextReading(TextReadingConsent(TextReadingSwitch.CURRENT_TEXT_VERSION, shownAtMonotonicNanos = 5))
    runCurrent()
    assertThat(reading.consents.single().textVersion).isEqualTo(TextReadingSwitch.CURRENT_TEXT_VERSION)
    accepted.acceptedVersion.value = TextReadingSwitch.CURRENT_TEXT_VERSION
    runCurrent()
    val page = c.state.value.textReading
    assertThat(page.state).isEqualTo(TextReadingState.OnForAccount(thisDevice = true))
    assertThat(page.needsConsent).isFalse()
    assertThat(page.textVersion).isEqualTo(TextReadingSwitch.CURRENT_TEXT_VERSION)
  }

  @Test
  fun seIlTestoECambiatoLaFunzioneSiRichiede() = runTest {
    reading.state.value = TextReadingState.OnForAccount(thisDevice = true)
    accepted.acceptedVersion.value = TextReadingSwitch.CURRENT_TEXT_VERSION - 1
    val c = controller()
    assertThat(c.state.value.textReading.needsConsent).isTrue()
  }

  @Test
  fun unDispositivoSiEscludeESiRiprendeESiSpegnePerTutti_Q13() = runTest {
    reading.state.value = TextReadingState.OnForAccount(thisDevice = true)
    accepted.acceptedVersion.value = TextReadingSwitch.CURRENT_TEXT_VERSION
    val c = controller()
    c.excludeThisDevice()
    runCurrent()
    assertThat(c.state.value.textReading.state).isEqualTo(TextReadingState.OnForAccount(thisDevice = false))
    assertThat(c.state.value.textReading.state.activeHere).isFalse()
    c.includeThisDevice()
    runCurrent()
    assertThat(c.state.value.textReading.state.activeHere).isTrue()
    c.disableTextReading()
    runCurrent()
    assertThat(c.state.value.textReading.state).isEqualTo(TextReadingState.Off)
  }

  @Test
  fun leRicercheRecentiSiCancellanoDallaPagina_Q5() = runTest {
    history.record("mare", emptyList())
    history.record("monti", emptyList())
    val c = controller()
    assertThat(c.state.value.historyCount).isEqualTo(2)
    c.clearHistory()
    runCurrent()
    assertThat(c.state.value.historyCount).isEqualTo(0)
  }

  @Test
  fun laCoperturaVieneDalMotore() = runTest {
    val c = controller()
    engine.coverage.value = SearchCoverage.Partial(12_300, 15_000, emptyMap())
    runCurrent()
    assertThat(c.state.value.coverage).isEqualTo(SearchCoverage.Partial(12_300, 15_000, emptyMap()))
  }

  @Test
  fun senzaLePorteLeSezioniNonCiSonoELaPaginaResta_UnRiposoSicuro() = runTest {
    val c = controller(withIndexer = false, withReading = false)
    assertThat(c.hasIndexer).isFalse()
    assertThat(c.hasTextReading).isFalse()
    assertThat(c.state.value.indexer.indexer).isFalse()
    assertThat(c.state.value.textReading.state).isEqualTo(TextReadingState.Off)
    c.setIndexer(true)
    c.enableTextReading(TextReadingConsent(TextReadingSwitch.CURRENT_TEXT_VERSION, 1))
    runCurrent()
    assertThat(indexer.changes).isEmpty()
    assertThat(reading.consents).isEmpty()
  }
  // --- Revisione B: i difetti della pagina Ricerca e modelli (MO-2, MO-6, IX-3, IX-8, LT-2) ------------------------------------------------

  /** MO-2: il "Si'" alla conferma sui dati mobili porta con se' la conferma (un trigger), non una bandiera condivisa. */
  @Test
  fun mo2_ilSiAllaConfermaChiedeIlModelloConLaConfermaNelTrigger() = runTest {
    val c = controller()
    c.confirmMobileDownload(ModelCatalog.INDEX_440M.id)
    runCurrent()
    assertThat(models.ensured).containsExactly(ModelCatalog.INDEX_440M.id to ModelTrigger.USER_CONFIRMED)
  }

  /** MO-6: il ritaglio sta nell'APK; il gestore lo dice `Absent` fino alla prima apertura e la pagina non deve mostrare "Da scaricare". */
  @Test
  fun mo6_ilRitaglioEProntoFinoAQuandoNonEInErrore() = runTest {
    val id = ModelCatalog.CUTOUT_MAGICTOUCH.id
    val c = controller()
    for (reported in listOf(ModelState.Absent, ModelState.Queued(ModelTrigger.RULES), ModelState.Verifying)) {
      models.set(id, reported)
      runCurrent()
      assertThat(c.state.value.models.models.last().state).isInstanceOf(ModelState.Ready::class.java)
    }
    models.set(id, ModelState.Failed(dev.pampa.mycloudgallery.core.models.contract.ModelFailCode.VERIFY_FAILED))
    runCurrent()
    assertThat(c.state.value.models.models.last().state).isInstanceOf(ModelState.Failed::class.java)
  }

  /** IX-8: spegnere il ruolo con il modello di analisi sul telefono (370 MiB) chiede se toglierlo. */
  @Test
  fun ix8_spegnereIlRuoloConIl440MProntoProponeDiTogliereIlModello() = runTest {
    indexer.indexer.value = true
    models.set(ModelCatalog.INDEX_440M.id, ModelState.Ready(ModelCatalog.INDEX_440M.sizeBytes, ModelSourceKind.HTTPS, 0))
    val c = controller()
    assertThat(c.state.value.offerRemoveIndexModel).isFalse()

    c.setIndexer(false)
    runCurrent()
    assertThat(c.state.value.offerRemoveIndexModel).isTrue()

    // "Tienilo": il modello resta e la domanda sparisce.
    c.dismissRemoveOffer()
    runCurrent()
    assertThat(c.state.value.offerRemoveIndexModel).isFalse()
    assertThat(models.removed).isEmpty()

    // "Togli": si toglie il modello e la domanda sparisce.
    indexer.indexer.value = true
    c.setIndexer(false)
    runCurrent()
    assertThat(c.state.value.offerRemoveIndexModel).isTrue()
    c.removeModel(ModelCatalog.INDEX_440M.id)
    runCurrent()
    assertThat(models.removed).containsExactly(ModelCatalog.INDEX_440M.id)
    assertThat(c.state.value.offerRemoveIndexModel).isFalse()
  }

  @Test
  fun ix8_senzaIlModelloProntoSpegnereIlRuoloNonChiedeNiente() = runTest {
    indexer.indexer.value = true
    val c = controller()
    c.setIndexer(false)
    runCurrent()
    assertThat(c.state.value.offerRemoveIndexModel).isFalse()
  }

  /** IX-3: la misura si ricorda fra una visita e l'altra (prima la stima delle notti restava "in misura" per sempre). */
  @Test
  fun ix3_laMisuraSiSalvaESiRitrovaAllaVisitaDopoSenzaRifarla() = runTest {
    val first = controller()
    first.setIndexer(true)
    runCurrent()
    assertThat(probe.measures).isEqualTo(1)
    assertThat(indexer.saved).isEqualTo(probe.result)

    val again = controller()
    assertThat(again.state.value.indexer.probe).isEqualTo(probe.result)
    assertThat(probe.measures).isEqualTo(1)
  }

  /** IX-3: se il modello del calcolo non c'era alla prima misura, la misura si rifa' quando diventa pronto. */
  @Test
  fun ix3_laMisuraSiRifaQuandoIlModelloDelCalcoloDiventaPronto() = runTest {
    indexer.indexer.value = true
    val c = controller()
    assertThat(probe.measures).isEqualTo(0)
    models.set(ModelCatalog.INDEX_440M.id, ModelState.Ready(ModelCatalog.INDEX_440M.sizeBytes, ModelSourceKind.HTTPS, 0))
    runCurrent()
    assertThat(probe.measures).isEqualTo(1)
    assertThat(c.state.value.indexer.probe).isEqualTo(probe.result)
  }

  @Test
  fun ix3_ognAperturaDellaPaginaRileggeLoStato() = runTest {
    val c = controller()
    c.refresh()
    c.refresh()
    assertThat(indexer.refreshCalls).isEqualTo(2)
  }

  /** LT-2: con la lettura accesa altrove la pagina non la da' per attiva qui: serve l'avviso (e ci si puo' escludere). */
  @Test
  fun lt2_conLAccountAccesoAltroveServeLAvvisoAQuestoDispositivo() = runTest {
    reading.state.value = TextReadingState.AwaitingNotice(TextReadingSwitch.CURRENT_TEXT_VERSION)
    accepted.acceptedVersion.value = TextReadingSwitch.CURRENT_TEXT_VERSION
    val c = controller()
    val page = c.state.value.textReading
    assertThat(page.state).isInstanceOf(TextReadingState.AwaitingNotice::class.java)
    assertThat(page.state.activeHere).isFalse()
    assertThat(page.needsConsent).isTrue()
  }

  /** LT-1 / I8: l'esclusione e lo spegnimento ancora da consegnare agli altri si vedono nella pagina. */
  @Test
  fun lt1_lEsclusioneELoSpegnimentoDaConsegnareArrivanoAllaPagina() = runTest {
    val c = controller()
    reading.excludedHere.value = true
    reading.syncPending.value = true
    runCurrent()
    assertThat(c.state.value.textReading.excludedHere).isTrue()
    assertThat(c.state.value.textReading.syncPending).isTrue()
  }
}
