package dev.pampa.mycloudgallery.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
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
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import dev.pampa.mycloudgallery.navigation.DIAGNOSTICS_APP_ACTIONS
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

  /** I7 (correzioni della Fase 8): un montaggio fallito si dice con il suo codice, anche con le persone non montate. */
  @Test
  fun i7_conIlMontaggioFallitoLaSezioneLoDiceConIlSuoCodice() {
    val items = diagnostics.render(PeopleFacts(mountFailed = true))
    assertThat(items.map { it.code }).containsExactly(DiagCodes.PEOPLE_STATE, DiagCodes.FACES_MOUNT_FAILED)
    assertThat(DiagCodes.FACES_MOUNT_FAILED.id).isIn(FacesDiag.ALL)
    assertThat(DiagCodes.FACES_MOUNT_FAILED in DiagCatalog).isTrue()
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
    segmentsWaiting = 3,
    archiveFull = 2,
    groupingSkipped = 1,
    indexers = 1,
    hold = FacesDiag.FACES_ERASE_PENDING,
    eraseStep = EraseStep.ANNOUNCED,
    eraseLocal = StepState.DONE,
    eraseNas = StepState.RUNNING,
    eraseWaiting = EraseWait.DEVICES,
    devicesWaiting = 1,
    devicesConfirmed = 1,
    recycleBin = true,
    eraseFileHeld = true,
    eraseHeldFiles = 4,
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
      // Correzioni E (L320, L343, C232, C234): i conteggi con il loro codice.
      FacesDiag.FACES_ERASE_FILES_HELD, FacesDiag.FACES_GROUPING_SKIPPED, FacesDiag.FACES_SEGMENT_WAITING, FacesDiag.FACES_ARCHIVE_FULL,
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

  // --- Correzioni E (DG-1, DG-2, L320, L343, C232, C234) ---------------------------------------------------------------------------------

  private fun codes(facts: PeopleFacts) = diagnostics.render(facts).map { it.code.id }

  private fun mountedOn(): PeopleFacts = PeopleFacts(mounted = true, state = FacesState.On(FaceGeneration.random(), HereState.ACTIVE), indexers = 1)

  /** DG-1: questo dispositivo, se e' l'indicizzatore, conta anche prima che lo stato firmato col suo ruolo sia stato letto. */
  @Test
  fun dg1_unIndicizzatoreChePrimaNonSiVedeNonDiceNessunIndicizzatore() {
    // `AppFacesRequirements.indexers`: nessuno stato firmato letto, quindi accountIndexers = 0, ma qui il ruolo c'e'.
    assertThat(PeopleDiagnostics.indexersOf(IndexerState(accountIndexers = 0, thisDevice = true, canBecomeIndexer = true))).isEqualTo(1)
    // Con gli stati letti il conto e' quello dell'account (gia' include questo dispositivo): non si somma due volte.
    assertThat(PeopleDiagnostics.indexersOf(IndexerState(accountIndexers = 2, thisDevice = true, canBecomeIndexer = true))).isEqualTo(2)
    assertThat(PeopleDiagnostics.indexersOf(IndexerState(accountIndexers = 1, thisDevice = false, canBecomeIndexer = true))).isEqualTo(1)
    // Davvero nessuno: ne' l'account ne' questo dispositivo, o non si sa.
    assertThat(PeopleDiagnostics.indexersOf(IndexerState(accountIndexers = 0, thisDevice = false, canBecomeIndexer = true))).isEqualTo(0)
    assertThat(PeopleDiagnostics.indexersOf(null)).isEqualTo(0)

    val here = mountedOn().copy(indexers = PeopleDiagnostics.indexersOf(IndexerState(0, thisDevice = true, canBecomeIndexer = true)))
    assertThat(codes(here)).doesNotContain(FacesDiag.FACES_NO_INDEXER)
    val nobody = mountedOn().copy(indexers = PeopleDiagnostics.indexersOf(IndexerState(0, thisDevice = false, canBecomeIndexer = true)))
    assertThat(codes(nobody)).contains(FacesDiag.FACES_NO_INDEXER)
  }

  /** DG-2: "Fallo su questo dispositivo" e "Configurazione" sono legati; ogni azione del catalogo delle persone ha il suo tasto. */
  @Test
  fun dg2_leAzioniDelParagrafo210SonoLegate() {
    assertThat(DiagCatalog.entry(DiagCodes.FACES_NO_INDEXER)!!.action).isEqualTo(DiagnosticsActions.OPEN_INDEXER_ROLE)
    assertThat(DiagCatalog.entry(DiagCodes.FACES_NO_PRIVATE_AREA)!!.action).isEqualTo(DiagAction.RESUME_ONBOARDING)
    val withAction = DiagCatalog.entries.filter { it.section == DiagSection.PEOPLE && it.action != null }
    assertThat(withAction.map { it.code.id }).containsAtLeast(FacesDiag.FACES_NO_INDEXER, FacesDiag.FACES_NO_PRIVATE_AREA)
    for (entry in withAction) {
      // Un tasto senza etichetta o che `:app` non sa eseguire e' peggio di nessun tasto.
      assertWithMessage(entry.code.id).that(DiagnosticsActions.labelRes(entry.action!!)).isNotEqualTo(0)
      assertWithMessage(entry.code.id).that(DiagnosticsActions.isAvailable(entry.action!!, DIAGNOSTICS_APP_ACTIONS)).isTrue()
    }
  }

  /** C232, C234: i segmenti in attesa e l'archivio pieno non stanno piu' nel conto dei rifiutati. */
  @Test
  fun c232C234_iSegmentiInAttesaELArchivioPienoHannoIlLoroCodice() {
    val waiting = diagnostics.render(mountedOn().copy(segmentsWaiting = 3))
    assertThat(waiting.map { it.code.id }).contains(FacesDiag.FACES_SEGMENT_WAITING)
    assertThat(waiting.map { it.code.id }).doesNotContain(FacesDiag.FACES_SEGMENT_REJECTED)
    assertThat(waiting.first { it.code.id == FacesDiag.FACES_SEGMENT_WAITING }.export).containsExactly("count", ExportValue.Num(3))

    val full = diagnostics.render(mountedOn().copy(archiveFull = 2))
    assertThat(full.map { it.code.id }).contains(FacesDiag.FACES_ARCHIVE_FULL)
    assertThat(full.map { it.code.id }).doesNotContain(FacesDiag.FACES_SEGMENT_REJECTED)
    assertThat(full.first { it.code.id == FacesDiag.FACES_ARCHIVE_FULL }.export).containsExactly("count", ExportValue.Num(2))

    // I veri rifiuti hanno ancora il loro: e il numero esportato dello stato e' quello dei soli rifiutati.
    val refused = diagnostics.render(mountedOn().copy(segmentsRejected = 5, segmentsWaiting = 3))
    assertThat(refused.map { it.code.id }).containsAtLeast(FacesDiag.FACES_SEGMENT_REJECTED, FacesDiag.FACES_SEGMENT_WAITING)
    assertThat(refused.first { it.code.id == FacesDiag.FACES_SEGMENT_REJECTED }.export).containsExactly("count", ExportValue.Num(5))
    val state = refused.first { it.code.id == FacesDiag.PEOPLE_STATE }.export
    assertThat(state["rejectedSegments"]).isEqualTo(ExportValue.Num(5))
    assertThat(state["waitingSegments"]).isEqualTo(ExportValue.Num(3))
    // Niente da dire: niente codici.
    assertThat(codes(mountedOn())).containsExactly(FacesDiag.PEOPLE_STATE)
  }

  /** L343: un codice proprio quando il raggruppamento salta il passo oltre il tetto dei volti. */
  @Test
  fun l343_ilPassoSaltatoOltreIlTettoHaIlSuoCodice() {
    val items = diagnostics.render(mountedOn().copy(groupingSkipped = 2))
    val item = items.first { it.code.id == FacesDiag.FACES_GROUPING_SKIPPED }
    assertThat(item.export).containsExactly("count", ExportValue.Num(2))
    assertThat(DiagCatalog.entry(DiagCodes.FACES_GROUPING_SKIPPED)!!.severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(codes(mountedOn())).doesNotContain(FacesDiag.FACES_GROUPING_SKIPPED)
  }

  /** L320: i file dei volti trattenuti sul NAS durante "Disattiva e cancella" si contano; senza il numero la voce c'e' lo stesso. */
  @Test
  fun l320_iFileTrattenutiSulNasSiContano() {
    val erasing = mountedOn().copy(eraseStep = EraseStep.ANNOUNCED, eraseLocal = StepState.DONE, eraseNas = StepState.WAITING, eraseWaiting = EraseWait.FILE_OPEN)
    val counted = diagnostics.render(erasing.copy(eraseFileHeld = true, eraseHeldFiles = 7))
    assertThat(counted.first { it.code.id == FacesDiag.FACES_ERASE_FILES_HELD }.export).containsExactly("count", ExportValue.Num(7))
    val unknown = diagnostics.render(erasing.copy(eraseFileHeld = true, eraseHeldFiles = 0))
    assertThat(unknown.first { it.code.id == FacesDiag.FACES_ERASE_FILES_HELD }.export).isEmpty()
    // Un'altra attesa, o nessuna cancellazione: niente.
    assertThat(codes(erasing.copy(eraseFileHeld = false))).doesNotContain(FacesDiag.FACES_ERASE_FILES_HELD)
    assertThat(codes(mountedOn().copy(eraseFileHeld = true, eraseHeldFiles = 3))).doesNotContain(FacesDiag.FACES_ERASE_FILES_HELD)
  }

  /** I codici nuovi stanno nel catalogo e nel contratto, nella sezione delle persone, con titolo e «cosa fare». */
  @Test
  fun iCodiciDelPacchettoEStannoNelCatalogoENelContratto() {
    val added = listOf(DiagCodes.FACES_ERASE_FILES_HELD, DiagCodes.FACES_GROUPING_SKIPPED, DiagCodes.FACES_SEGMENT_WAITING, DiagCodes.FACES_ARCHIVE_FULL)
    for (code in added) {
      assertThat(code.id).isIn(FacesDiag.ALL)
      val entry = DiagCatalog.entry(code)!!
      assertThat(entry.section).isEqualTo(DiagSection.PEOPLE)
      assertThat(entry.titleRes).isNotEqualTo(0)
      assertThat(entry.guideRes).isNotEqualTo(0)
    }
  }
}
