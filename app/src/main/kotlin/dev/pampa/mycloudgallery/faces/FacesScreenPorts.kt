package dev.pampa.mycloudgallery.faces

import android.content.res.Resources
import dev.pampa.mycloudgallery.core.ai.compute.IndexerAreas
import dev.pampa.mycloudgallery.core.faces.compute.FacesRates
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseStatus
import dev.pampa.mycloudgallery.core.faces.contract.ErasePreview
import dev.pampa.mycloudgallery.core.faces.contract.EraseReport
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraser
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesEraser
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.estimate.DeviceMeasures
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionsSectionProvider
import dev.pampa.mycloudgallery.feature.people.activation.ActivationIndexerRole
import dev.pampa.mycloudgallery.feature.people.activation.ActivationPorts
import dev.pampa.mycloudgallery.feature.people.activation.FacesAnalyzeNow
import dev.pampa.mycloudgallery.feature.people.activation.FacesActivatedBy
import dev.pampa.mycloudgallery.feature.people.activation.PeopleSettingsFacts
import dev.pampa.mycloudgallery.feature.people.contract.ComputingDevice
import dev.pampa.mycloudgallery.feature.people.contract.FaceModelRow
import dev.pampa.mycloudgallery.feature.people.erase.EraseSheetStates
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionInputs
import dev.pampa.mycloudgallery.feature.people.section.PeopleSectionSources
import dev.pampa.mycloudgallery.feature.people.section.SectionData
import dev.pampa.mycloudgallery.feature.search.contract.IndexerChange
import dev.pampa.mycloudgallery.feature.search.contract.IndexerControl
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntryState
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntryStatus
import dev.pampa.mycloudgallery.feature.settings.people.PeopleEntrySummary
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.navigation.FlowSection
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// LE PORTE DELLE SCHERMATE DELLE PERSONE (Fase 8, P13; docs/fase8-progetto.md §1.4, §1.5): cio' che la pagina di attivazione, l'avviso, Impostazioni -> Persone,
// il foglio "Disattiva e cancella", la voce delle Impostazioni e la sezione delle Raccolte chiedono a `:app`. Solo contratti e dati: mai un nome, un id di
// persona o di volto, o un conteggio per persona nei log (D37); gli stessi dati che mostrano le schermate non escono dal telefono.

/** "Disattiva e cancella" di adesso: quello vero dalle persone montate; prima, niente da cancellare. */
@OptIn(ExperimentalCoroutinesApi::class)
class FacesEraserFacade(private val graph: FacesGraph, scope: CoroutineScope) : FacesEraser {
  override val status: StateFlow<EraseStatus?> = graph.live.flatMapLatest { it?.eraser?.status ?: flowOf(null) }.stateIn(scope, SharingStarted.Eagerly, null)

  override suspend fun preview(): ErasePreview = graph.live.value?.eraser?.preview() ?: NoFacesEraser.preview()

  override suspend fun erase(confirmation: FacesEraseConfirmation, albums: PeopleAlbumsChoice): EraseStart =
    graph.live.value?.eraser?.erase(confirmation, albums) ?: EraseStart.NothingToErase

  override suspend fun resume(budget: Duration): EraseReport = graph.live.value?.eraser?.resume(budget) ?: NoFacesEraser.resume(budget)

  override fun toString(): String = "FacesEraserFacade"
}

/**
 * La voce "Persone" delle Impostazioni (D3, D4): lo stato di QUESTO dispositivo in una frase. Mai un nome, un conteggio o una parola sulla famiglia (Q3,
 * D37).
 */
class AppPeopleEntryStatus(switch: FacesSwitch, scope: CoroutineScope) : PeopleEntryStatus {
  // L350: prima del montaggio l'interruttore risponde `Off` come un null-object: non e' lo stato vero, e la voce non dice «spenta» (`NOT_YET`).
  override val summary: StateFlow<PeopleEntrySummary> = combine(switch.state, switch.known) { state, known -> PeopleEntrySummary(entryOf(state, known)) }
    .stateIn(scope, SharingStarted.Eagerly, PeopleEntrySummary(entryOf(switch.state.value, switch.known.value)))

  private fun entryOf(state: FacesState, known: Boolean): PeopleEntryState = if (known) entryOf(state) else PeopleEntryState.NOT_YET

  private fun entryOf(state: FacesState): PeopleEntryState = when (state) {
    FacesState.Off -> PeopleEntryState.OFF
    is FacesState.On -> when (state.here) {
      HereState.ACTIVE -> PeopleEntryState.ON
      HereState.LOCAL_ONLY -> PeopleEntryState.LOCAL_ONLY
      HereState.EXCLUDED -> PeopleEntryState.EXCLUDED_HERE
    }
    is FacesState.AwaitingNotice -> PeopleEntryState.AWAITING_NOTICE
    is FacesState.Erasing -> PeopleEntryState.ERASING
    FacesState.NeedsUpdate -> PeopleEntryState.NEEDS_UPDATE
  }
}

/**
 * Le porte della pagina di attivazione, dell'avviso e di Impostazioni -> Persone (P7): l'interruttore, i requisiti, il ruolo di indicizzatore ("Fallo su
 * questo dispositivo"), il nome del dispositivo **dell'account** che ha acceso Persone (mai un familiare: Q3) e i fatti di Impostazioni (chi calcola, a che
 * punto e', le persone nascoste, i modelli, la cancellazione).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppActivationPorts(
  override val switch: FacesSwitch,
  override val requirements: FacesRequirements,
  private val faces: FacesGraph,
  private val graph: AppGraph,
  private val gallery: GalleryGraph,
  private val indexerHere: () -> Boolean,
  indexer: IndexerControl,
  people: FacesPeopleFacade,
  eraser: FacesEraserFacade,
) : ActivationPorts {

  override val indexerRole: ActivationIndexerRole = ActivationIndexerRole { indexer.setIndexer(true).let { it == IndexerChange.SAVED || it == IndexerChange.UNCHANGED } }

  override val activatedBy: FacesActivatedBy = FacesActivatedBy { activatedByLabel() }

  private val status: StateFlow<FacesStatus> =
    faces.live.flatMapLatest { it?.status ?: flowOf(FacesStatus.Off) }.stateIn(graph.scope, SharingStarted.Eagerly, FacesStatus.Off)

  /**
   * CA-2: «Analizza ora» con la stima. Il conto legge il catalogo dal grafo montato (`FacesLive.backlog`); i ritmi sono quelli misurati dal calcolo di questo processo; il
   * verdetto dei dati mobili e' delle regole per un lavoro dell'utente su `AI_COMPUTE` (la stessa strada degli altri «Fai ora»).
   */
  override val analyze: FacesAnalyzeNow = AppFacesAnalyzeNow(
    tally = { faces.live.value?.backlog?.tally() },
    rates = { faces.live.value?.compute?.rates?.rates() ?: FacesRates.forPhotosPerSecond(DeviceMeasures.PHASE_0.facesPerSecond) },
    decide = { bytes ->
      graph.nasInputs.rules.evaluate(WorkRequest.UserInitiated(BackgroundActivity.AI_COMPUTE, bytes, confirmed = false, writesToNas = true))
    },
    request = { faces.analyzeNow() },
  )

  override val facts: StateFlow<PeopleSettingsFacts> =
    combine(combine(status, people.list, eraser.status, graph.statuses.latest, switch.state) { progress, list, erase, _, _ ->
      PeopleSettingsFacts(
        devices = computingDevices(),
        progress = progress,
        hiddenPeople = list.hiddenCount,
        models = MODELS,
        erase = erase?.takeIf { it.step != EraseStep.DONE }?.let(EraseSheetStates::progress),
      )
    }, indexer.indexer) { base, computes -> base.copy(computesHere = computes) }
      .stateIn(graph.scope, SharingStarted.Eagerly, PeopleSettingsFacts(models = MODELS, computesHere = indexer.indexer.value))

  /** Il nome (del registro) del dispositivo dell'account che ha scritto la voce `faces`; null se non si sa o se non e' dell'account. */
  private fun activatedByLabel(): String? {
    val author = gallery.nas?.organizer?.parts?.personalStore?.effectiveEntries?.value?.get(FacesSettingCodec.KEY)?.author ?: return null
    val registry = graph.nasInputs.registry()?.registry ?: return null
    val me = graph.nasInputs.deviceId()?.let { registry.device(it) } ?: return null
    val device = registry.device(author) ?: return null
    return device.name.takeIf { dev.pampa.mycloudgallery.core.trust.codec.TextRules.sameAccount(device.nasUser, me.nasUser) }
  }

  /** I dispositivi dell'account che analizzano le foto (ruolo Indicizzatore negli stati firmati degli ultimi 7 giorni, piu' questo col ruolo di adesso). */
  private fun computingDevices(): List<ComputingDevice> {
    val registry = graph.nasInputs.registry()?.registry ?: return emptyList()
    val me = graph.nasInputs.deviceId()
    val mine = me?.let { registry.device(it) }?.nasUser ?: return emptyList()
    val statuses = graph.statuses.latest.value
    val now = graph.nasInputs.nasClock.nowMillisOrNull()
    return registry.devices.filter {
      it.status == DeviceStatus.APPROVED && dev.pampa.mycloudgallery.core.trust.codec.TextRules.sameAccount(it.nasUser, mine)
    }.mapNotNull { device ->
      val isMe = device.id == me
      val indexer = if (isMe) {
        indexerHere()
      } else {
        statuses[device.id]?.let { report -> DeviceRole.INDEXER in report.roles && now != null && now - report.lastSeenAt in 0..IndexerAreas.FRESH_MILLIS } == true
      }
      if (indexer) ComputingDevice(device.name, thisDevice = isMe, excluded = false) else null
    }
  }

  companion object {
    /** I due modelli dei volti, dentro l'app (Q1 (a)): nome, versione, licenza. */
    val MODELS: List<FaceModelRow> = listOf(
      FaceModelRow("YuNet", "2023mar", "MIT"),
      FaceModelRow("SFace", "2021dec", "Apache 2.0"),
    )
  }
}

/** La trasformazione campo per campo dei dati di feature-people nel gruppo delle Raccolte (i due moduli non si conoscono). */
internal fun SectionData.toGroup(): CollectionEntryGroup = CollectionEntryGroup(
  title = title,
  entries = entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing) },
  code = code,
  hideable = hideable,
  hint = hint,
)

/**
 * Cio' che la sezione Persone delle Raccolte guarda, **tutto dalle persone montate e preso insieme** (AT-2, correzioni B): finche' [FacesGraph.live] e' null
 * il flusso emette null ("non ancora": la sezione non c'e'), poi un valore solo per volta con lo stato dell'interruttore, le persone, il calcolo, il segno di CE-3
 * e [computesHere]. Non passa dalle facciate, che prima del montaggio rispondono "spenta" e "vuota": un istante in cui la facciata dell'interruttore dice ancora
 * `Off` e il grafo e' gia' montato non puo' arrivare fino alla riga "Attiva riconoscimento dei volti" di chi ha Persone accesa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun peopleSectionInputs(faces: FacesGraph, computesHere: Flow<Boolean>): Flow<PeopleSectionInputs?> = faces.live.flatMapLatest { live ->
  if (live == null) {
    flowOf<PeopleSectionInputs?>(null)
  } else {
    combine(live.switch.state, live.people.list, live.status, live.switch.offElsewhere, computesHere) { state, list, status, off, here ->
      PeopleSectionInputs(state, list, status, off, here)
    }
  }
}

/**
 * La sezione Persone delle Raccolte come fornitore (codice `people`): montata, sempre una riga almeno, anche da spenta con "Attiva riconoscimento dei volti";
 * prima del montaggio assente (AT-2, L297). **Uno solo per processo** (AT-3): lo lega `@Singleton` in `FacesModule`, perche' i suoi collettori vivono quanto [scope].
 * [computesHere]: questo dispositivo analizza le foto (DR-1); senza, la sezione dice che qui le persone non si calcolano.
 */
fun peopleSectionProvider(
  resources: Resources,
  faces: FacesGraph,
  scope: CoroutineScope,
  computesHere: StateFlow<Boolean> = MutableStateFlow(true),
): CollectionsSectionProvider {
  val sources = PeopleSectionSources(resources, peopleSectionInputs(faces, computesHere), scope)
  return FlowSection(
    PeopleSectionSources.CODE,
    sources.section.map { it?.toGroup() }.stateIn(scope, SharingStarted.Eagerly, sources.section.value?.toGroup()),
  )
}
