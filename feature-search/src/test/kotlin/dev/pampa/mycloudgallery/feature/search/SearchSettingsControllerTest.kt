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
    assertThat(models.ensured).containsExactly(ModelCatalog.INDEX_440M.id to ModelTrigger.ROLE_CHANGED)
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
}
