package dev.pampa.mycloudgallery.faces

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.HolderState
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import kotlinx.coroutines.CancellationException

/**
 * La sezione "Persone" della Diagnostica (Fase 8, §2.10, D37, P13): lo stato (se Persone e' attiva qui, il calcolo, il custode, la replica, la
 * cancellazione) e un elemento per ogni codice del §2.10 che ha qualcosa da dire. **Solo numeri, flag e costanti di enum**: mai nomi, id di persone o di
 * volti, chiavi di contenuto, riquadri, vettori, conteggi per persona o la generazione. Prima che le persone siano montate (famiglia non pronta) dice solo
 * lo stato, con `mounted` spento.
 */
class PeopleDiagnostics(private val graph: FacesGraph) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.PEOPLE

  override suspend fun collect(): List<DiagItem> = render(facts())

  /** Quello che il grafo sa adesso, in soli numeri e segni: la sezione si compone da qui (e si prova senza montare le persone). */
  internal suspend fun facts(): PeopleFacts {
    val live = graph.live.value ?: return PeopleFacts(roundErrors = graph.roundErrors)
    val state = live.switch.currentState()
    val c = live.counters
    val sync = graph.lastSync.value
    val janitor = graph.lastJanitor.value
    val erase = live.eraser.status.value
    val indexers = safely { live.requirements.indexers() }
    val area = safely { live.requirements.privateArea() }
    return PeopleFacts(
      mounted = true,
      state = state,
      status = live.status.value,
      holder = live.grouping.holder.value,
      rebased = live.grouping.rebased.value,
      fresh = live.switch.fresh.value,
      syncPending = live.switch.syncPending.value,
      roundErrors = graph.roundErrors,
      facesSaved = c.facesSaved.get(),
      waitingPreviews = c.waitingPreviews.get(),
      vectorsInvalid = c.vectorsInvalid.get(),
      engineFailures = c.engineFailures.get(),
      engineStops = c.engineStops.get(),
      modelStops = c.modelStops.get(),
      keyLost = c.keyLost.get(),
      databaseRebuilt = c.databaseRebuilt.get(),
      storeFailures = c.storeFailures.get(),
      unitsLost = c.unitsLost.get(),
      leaseTroubles = c.leaseTroubles.get(),
      publishFailures = c.publishFailures.get(),
      groupingFailures = c.groupingFailures.get(),
      foreignScopes = c.foreignScopes.get(),
      devicesRead = sync?.devicesRead ?: 0,
      headsAccepted = sync?.headsAccepted ?: 0,
      headsRejected = sync?.headsRejected?.values?.sum() ?: 0,
      segmentsRejected = sync?.segmentsRejected?.values?.sum() ?: 0,
      foreignGenerations = maxOf(sync?.foreignGenerations ?: 0, janitor?.foreignGenerations ?: 0),
      removedFiles = janitor?.removedFiles ?: 0,
      indexers = indexers?.accountIndexers ?: 0,
      noPrivateArea = area != null && area !is dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState.Declared,
      hold = graph.holdCode(),
      eraseStep = erase?.step,
      eraseLocal = erase?.local,
      eraseNas = erase?.nas,
      eraseWaiting = erase?.waiting,
      devicesWaiting = erase?.devices?.count { it.state == AckState.WAITING } ?: 0,
      devicesConfirmed = erase?.devices?.count { it.state == AckState.CONFIRMED } ?: 0,
      recycleBin = erase?.waiting == EraseWait.RECYCLE_BIN,
    )
  }

  internal fun render(f: PeopleFacts): List<DiagItem> {
    val items = ArrayList<DiagItem>()
    val state = f.state
    val export = LinkedHashMap<String, ExportValue>()
    export["mounted"] = ExportValue.Flag(f.mounted)
    if (f.mounted) {
      export["enabled"] = ExportValue.Flag(state?.let { it is FacesState.On } == true)
      (state as? FacesState.On)?.let { export["hereState"] = ExportValue.Enum.of(it.here) }
      export["awaitingNotice"] = ExportValue.Flag(state is FacesState.AwaitingNotice)
      export["erasing"] = ExportValue.Flag(state is FacesState.Erasing)
      export["fresh"] = ExportValue.Flag(f.fresh)
      export["syncPending"] = ExportValue.Flag(f.syncPending)
      export["runStatus"] = ExportValue.Num(statusCode(f.status))
      export["holder"] = ExportValue.Num(holderCode(f.holder))
      export["facesSaved"] = ExportValue.Num(f.facesSaved.toLong())
      export["indexers"] = ExportValue.Num(f.indexers.toLong())
      export["devicesRead"] = ExportValue.Num(f.devicesRead.toLong())
      export["headsAccepted"] = ExportValue.Num(f.headsAccepted.toLong())
      export["rejectedHeads"] = ExportValue.Num(f.headsRejected.toLong())
      export["rejectedSegments"] = ExportValue.Num(f.segmentsRejected.toLong())
      export["roundErrors"] = ExportValue.Num(f.roundErrors.toLong())
      export["holdActive"] = ExportValue.Flag(f.hold != null)
      f.eraseStep?.let { export["eraseStep"] = ExportValue.Enum.of(it) }
      f.eraseLocal?.let { export["eraseLocal"] = ExportValue.Enum.of(it) }
      f.eraseNas?.let { export["eraseNas"] = ExportValue.Enum.of(it) }
      f.eraseWaiting?.let { export["eraseWaiting"] = ExportValue.Enum.of(it) }
    }
    items += item(DiagCodes.PEOPLE_STATE, DiagSeverity.INFO, export)
    if (!f.mounted) return items
    if (state == FacesState.NeedsUpdate) items += item(DiagCodes.FACES_NEEDS_UPDATE, DiagSeverity.WARNING, emptyMap())
    val active = state is FacesState.On && state.here != HereState.EXCLUDED
    if (active && f.noPrivateArea) items += item(DiagCodes.FACES_NO_PRIVATE_AREA, DiagSeverity.WARNING, emptyMap())
    if (active && f.indexers == 0 && !f.noPrivateArea) items += item(DiagCodes.FACES_NO_INDEXER, DiagSeverity.INFO, emptyMap())
    if (f.waitingPreviews > 0) items += count(DiagCodes.FACES_WAITING_PREVIEWS, f.waitingPreviews)
    if (f.vectorsInvalid > 0) items += count(DiagCodes.FACES_VECTOR_INVALID, f.vectorsInvalid)
    if (f.engineStops > 0 || f.engineFailures > 0) items += count(DiagCodes.FACES_ENGINE_FAILED, f.engineStops + f.engineFailures)
    if (f.modelStops > 0) items += count(DiagCodes.FACES_MODEL_MISMATCH, f.modelStops)
    if (f.keyLost > 0) items += count(DiagCodes.FACES_KEY_LOST, f.keyLost)
    if (f.segmentsRejected > 0) items += count(DiagCodes.FACES_SEGMENT_REJECTED, f.segmentsRejected)
    if (f.headsRejected > 0) items += count(DiagCodes.FACES_HEAD_REJECTED, f.headsRejected)
    if (f.foreignGenerations > 0) items += count(DiagCodes.FACES_FOREIGN_GENERATION, f.foreignGenerations)
    if (f.rebased > 0) items += count(DiagCodes.FACES_HOLDER_REBASED, f.rebased)
    if (f.hold == FacesDiag.FACES_ERASE_PENDING) items += item(DiagCodes.FACES_ERASE_PENDING, DiagSeverity.INFO, emptyMap())
    if (f.hold == PRE_PUBLISH_FAILED) items += item(DiagCodes.PRE_PUBLISH_FAILED, DiagSeverity.WARNING, emptyMap())
    // La cancellazione: dove e' arrivata, in codici (la pagina di Persone dice il resto).
    if (f.eraseStep != null) {
      if (f.eraseLocal == StepState.DONE) items += item(DiagCodes.FACES_ERASE_LOCAL_DONE, DiagSeverity.INFO, emptyMap())
      when (f.eraseNas) {
        StepState.DONE -> items += item(DiagCodes.FACES_ERASE_NAS_DONE, DiagSeverity.INFO, emptyMap())
        StepState.RUNNING, StepState.WAITING, StepState.NOT_STARTED -> items += item(DiagCodes.FACES_ERASE_NAS_PENDING, DiagSeverity.INFO, emptyMap())
        else -> Unit
      }
      if (f.devicesWaiting > 0 && f.eraseWaiting == EraseWait.DEVICES) items += count(DiagCodes.FACES_ERASE_WAITING_DEVICES, f.devicesWaiting)
      if (f.recycleBin) items += item(DiagCodes.FACES_RECYCLE_BIN, DiagSeverity.WARNING, emptyMap())
    }
    return items
  }

  private suspend fun <T> safely(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  private fun count(code: DiagCode, n: Int) = item(code, DiagCatalog.entry(code)?.severity ?: DiagSeverity.INFO, mapOf("count" to ExportValue.Num(n.toLong())))

  private fun item(code: DiagCode, severity: DiagSeverity, export: Map<String, ExportValue>) =
    DiagItem(code = code, section = section, severity = severity, titleRes = DiagCatalog.titleOf(code), export = export)

  companion object {
    /** I codici del contratto (`FacesDiag`, §2.10): `Phase8DiagCatalogTest` li cerca tutti nel catalogo. */
    val CONTRACT_CODES: List<String> = FacesDiag.ALL

    const val PRE_PUBLISH_FAILED: String = "PRE_PUBLISH_FAILED"

    /** Lo stato del calcolo in un numero: 0 spento, 1 attesa, 2 in corso, 3 fermo, 4 problema. */
    fun statusCode(status: FacesStatus): Long = when (status) {
      FacesStatus.Off -> 0
      is FacesStatus.Waiting -> 1
      is FacesStatus.Running -> 2
      is FacesStatus.Idle -> 3
      is FacesStatus.Problem -> 4
    }

    /** Il custode in un numero: 0 non si sa, 1 non e' lui, 2 e' lui, 3 contesa, 4 recinzione. */
    fun holderCode(holder: HolderState): Long = when (holder) {
      HolderState.Unknown -> 0
      HolderState.NotHolder -> 1
      is HolderState.Holder -> 2
      HolderState.Contended -> 3
      HolderState.LocalOnly -> 4
    }
  }
}

/** Cio' che la sezione delle persone dice, in soli numeri e segni (D37): mai un nome, un id, una chiave, un riquadro, un vettore o la generazione. */
internal data class PeopleFacts(
  val mounted: Boolean = false,
  val state: FacesState? = null,
  val status: FacesStatus = FacesStatus.Off,
  val holder: HolderState = HolderState.Unknown,
  val rebased: Int = 0,
  val fresh: Boolean = false,
  val syncPending: Boolean = false,
  val roundErrors: Int = 0,
  val facesSaved: Int = 0,
  val waitingPreviews: Int = 0,
  val vectorsInvalid: Int = 0,
  val engineFailures: Int = 0,
  val engineStops: Int = 0,
  val modelStops: Int = 0,
  val keyLost: Int = 0,
  val databaseRebuilt: Int = 0,
  val storeFailures: Int = 0,
  val unitsLost: Int = 0,
  val leaseTroubles: Int = 0,
  val publishFailures: Int = 0,
  val groupingFailures: Int = 0,
  val foreignScopes: Int = 0,
  val devicesRead: Int = 0,
  val headsAccepted: Int = 0,
  val headsRejected: Int = 0,
  val segmentsRejected: Int = 0,
  val foreignGenerations: Int = 0,
  val removedFiles: Int = 0,
  val indexers: Int = 0,
  val noPrivateArea: Boolean = false,
  /** Il codice dell'ultimo giro dei dati personali fermato dal gancio dei volti (D30), o null. */
  val hold: String? = null,
  val eraseStep: EraseStep? = null,
  val eraseLocal: StepState? = null,
  val eraseNas: StepState? = null,
  val eraseWaiting: EraseWait? = null,
  val devicesWaiting: Int = 0,
  val devicesConfirmed: Int = 0,
  val recycleBin: Boolean = false,
)
