package dev.pampa.mycloudgallery.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.HolderState
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Fase 8, P13 (§2.10, D37): i codici della Diagnostica delle persone. Ogni codice di `FacesDiag.ALL` e' nel catalogo (sezione `PEOPLE`) con il suo titolo e
 * il suo "cosa fare"; la sezione dice **solo numeri, flag e costanti di enum** con chiavi dell'elenco chiuso dell'esportazione; mai nomi, id di persone o di
 * volti, chiavi di contenuto, riquadri, vettori, conteggi per persona o la generazione. `feature-diagnostics` non vede `core-faces` (regola della Fase 8):
 * il controllo sta qui, in `:app`, che li vede tutti e due.
 */
class Phase8DiagCatalogTest {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @After
  fun tearDown() = scope.cancel()

  private val diagnostics = PeopleDiagnostics(FacesGraph(scope, { null }, MutableStateFlow(false)))

  @Test
  fun ogniCodiceDelParagrafo210HaLaSuaVoceNelCatalogoNellaSezioneDellePersone() {
    for (id in FacesDiag.ALL) {
      val entry = DiagCatalog.entry(DiagCode(id))
      assertWithMessage("$id non e' nel catalogo della Diagnostica").that(entry).isNotNull()
      assertThat(entry!!.section).isEqualTo(DiagSection.PEOPLE)
      assertWithMessage("$id senza titolo").that(entry.titleRes).isNotEqualTo(0)
      if (entry.severity == DiagSeverity.WARNING || entry.severity == DiagSeverity.ERROR) {
        assertWithMessage("$id e' un avviso senza «cosa fare»").that(entry.guideRes).isNotEqualTo(0)
      }
    }
    assertThat(FacesDiag.ALL).containsNoDuplicates()
  }

  @Test
  fun iCodiciNuoviDiP13StannoNelCatalogoENelContratto() {
    for (code in listOf(DiagCodes.FACES_ERASE_PENDING, DiagCodes.FACES_KILLED_MEMORY)) {
      assertThat(code.id).isIn(FacesDiag.ALL)
      assertThat(code in DiagCatalog).isTrue()
    }
    // Il segnale del gancio dei dati personali fermato per un errore non e' un codice dei volti (e' di P8, nel catalogo da P13).
    assertThat(DiagCodes.PRE_PUBLISH_FAILED in DiagCatalog).isTrue()
    // E-A16: anche i volti dicono "Android ha chiuso l'app per memoria".
    assertThat(DiagCatalog.codesCovering("E-A16")).contains(DiagCodes.FACES_KILLED_MEMORY)
  }

  @Test
  fun primaDelMontaggioLaSezioneDiceSoloLoStato() = runBlocking<Unit> {
    val items = diagnostics.collect()
    assertThat(items.map { it.code }).containsExactly(DiagCodes.PEOPLE_STATE)
    assertThat(items.single().export).containsExactly("mounted", ExportValue.Flag(false))
  }

  /** Una fotografia in cui ogni cosa che puo' dire qualcosa la dice: ogni codice che la sezione sa emettere. */
  private fun everything() = PeopleFacts(
    mounted = true,
    state = FacesState.On(FaceGeneration.random(), HereState.ACTIVE),
    status = FacesStatus.Running(10, 100, dev.pampa.mycloudgallery.core.faces.contract.FacesPhase.DETECT),
    holder = HolderState.Holder(7),
    rebased = 2,
    fresh = true,
    syncPending = true,
    roundErrors = 3,
    facesSaved = 120,
    waitingPreviews = 4,
    vectorsInvalid = 1,
    engineFailures = 1,
    engineStops = 1,
    modelStops = 1,
    keyLost = 1,
    segmentsRejected = 2,
    headsRejected = 1,
    foreignGenerations = 1,
    devicesRead = 2,
    headsAccepted = 3,
    removedFiles = 9,
    indexers = 1,
    hold = FacesDiag.FACES_ERASE_PENDING,
    eraseStep = EraseStep.ANNOUNCED,
    eraseLocal = StepState.DONE,
    eraseNas = StepState.RUNNING,
    eraseWaiting = EraseWait.DEVICES,
    devicesWaiting = 1,
    devicesConfirmed = 1,
    recycleBin = true,
  )

  @Test
  fun leVociUsanoSoloCodiciDelCatalogoEChiaviDellElencoChiuso() {
    for (facts in listOf(everything(), PeopleFacts(mounted = true, state = FacesState.Off), PeopleFacts(mounted = true, state = FacesState.NeedsUpdate), PeopleFacts())) {
      val items = diagnostics.render(facts)
      assertThat(items).isNotEmpty()
      for (item in items) {
        assertWithMessage("codice ${item.code.id} fuori dal catalogo").that(item.code in DiagCatalog).isTrue()
        assertThat(item.section).isEqualTo(DiagSection.PEOPLE)
        for ((key, value) in item.export) {
          assertWithMessage("chiave $key fuori da ExportKeys").that(ExportKeys.isAllowed(key)).isTrue()
          // Solo numeri, flag e costanti di enum: mai un testo libero.
          assertThat(value is ExportValue.Num || value is ExportValue.Flag || value is ExportValue.Enum).isTrue()
        }
      }
    }
  }

  @Test
  fun conTuttoAccesoOgniCodiceChePuoEssereEmessoLoE() {
    val codes = diagnostics.render(everything()).map { it.code.id }.toSet()
    val expected = setOf(
      FacesDiag.PEOPLE_STATE, FacesDiag.FACES_WAITING_PREVIEWS, FacesDiag.FACES_VECTOR_INVALID, FacesDiag.FACES_ENGINE_FAILED, FacesDiag.FACES_MODEL_MISMATCH,
      FacesDiag.FACES_KEY_LOST, FacesDiag.FACES_SEGMENT_REJECTED, FacesDiag.FACES_HEAD_REJECTED, FacesDiag.FACES_FOREIGN_GENERATION, FacesDiag.FACES_HOLDER_REBASED,
      FacesDiag.FACES_ERASE_PENDING, FacesDiag.FACES_ERASE_LOCAL_DONE, FacesDiag.FACES_ERASE_NAS_PENDING, FacesDiag.FACES_ERASE_WAITING_DEVICES, FacesDiag.FACES_RECYCLE_BIN,
    )
    assertThat(codes).containsAtLeastElementsIn(expected)
  }

  @Test
  fun nelloStatoNessunNomeNessunIdENessunaGenerazione() {
    val generation = FaceGeneration.random()
    val facts = everything().copy(state = FacesState.On(generation, HereState.ACTIVE))
    val dump = diagnostics.render(facts).joinToString("\n") { "${it.code.id} ${it.export}" }
    assertThat(dump).doesNotContain(generation.base32)
    // La generazione compare solo come costante `ACTIVE`/`EXCLUDED`/`LOCAL_ONLY` dello stato di qui, mai il suo valore.
    assertThat(dump).contains("hereState")
    assertThat(dump).doesNotContainMatch("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
  }

  @Test
  fun lAvanzamentoEIlCustodeSonoNumeriNonTesti() {
    assertThat(PeopleDiagnostics.statusCode(FacesStatus.Off)).isEqualTo(0)
    assertThat(PeopleDiagnostics.holderCode(HolderState.Holder(99))).isEqualTo(2)
    assertThat(PeopleDiagnostics.holderCode(HolderState.LocalOnly)).isEqualTo(4)
  }
}
