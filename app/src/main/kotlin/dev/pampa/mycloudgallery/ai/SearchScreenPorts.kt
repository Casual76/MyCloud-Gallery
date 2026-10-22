package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.family.contract.RolesChange
import dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.feature.search.contract.AreaKind
import dev.pampa.mycloudgallery.feature.search.contract.IndexSpace
import dev.pampa.mycloudgallery.feature.search.contract.IndexerBacklog
import dev.pampa.mycloudgallery.feature.search.contract.IndexerChange
import dev.pampa.mycloudgallery.feature.search.contract.IndexerControl
import dev.pampa.mycloudgallery.feature.search.contract.SearchOrderStore
import dev.pampa.mycloudgallery.feature.search.contract.TextReadingAccepted
import dev.pampa.mycloudgallery.search.SearchGraph
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Le PORTE di Cerca e della pagina Ricerca e modelli (feature-search, P8) sopra le impostazioni personali, i ruoli e `AiGraph` (P11).

/** L'ordine dei risultati (D48) sopra `search_order`, per codice; senza impostazioni personali (prima del NAS) resta in memoria. */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalSearchOrderStore(private val personal: PersonalSettingsHub, scope: CoroutineScope) : SearchOrderStore {
  private val local = MutableStateFlow(ResultOrder.RECENT)

  override val order: StateFlow<ResultOrder> = personal.search
    .flatMapLatest { s -> s?.searchOrder?.map { ResultOrder.fromCode(it.code) } ?: local }
    .stateIn(scope, SharingStarted.Eagerly, ResultOrder.RECENT)

  override suspend fun setOrder(order: ResultOrder) {
    local.value = order
    personal.search.value?.setSearchOrder(SearchOrderSetting.fromCode(order.code))
  }
}

/** La versione del testo accettata per l'account (0 se la lettura e' spenta, D41). */
class PersonalTextReadingAccepted(personal: PersonalSettingsHub, scope: CoroutineScope) : TextReadingAccepted {
  override val acceptedVersion: StateFlow<Int> = personal.textReading
    .map { (it as? TextReadingChoice.OnForAccount)?.acceptedTextVersion ?: 0 }
    .stateIn(scope, SharingStarted.Eagerly, 0)
}

/** "Animazione delle foto in movimento" (D50, Q16 (b)) sopra `motion_animation`: acceso finche' le impostazioni personali non ci sono. */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalMotionAnimationSettings(
  private val personal: PersonalSettingsHub,
  scope: CoroutineScope,
) : dev.pampa.mycloudgallery.feature.settings.library.MotionAnimationSettings {
  override val enabled: StateFlow<Boolean> = personal.motion
    .flatMapLatest { m -> m?.motionAnimation ?: flowOf(true) }
    .stateIn(scope, SharingStarted.Eagerly, true)

  override suspend fun setEnabled(on: Boolean) {
    personal.motion.value?.setMotionAnimation(on)
  }
}

/** Lo stato della voce "Ricerca e modelli" delle Impostazioni: ruolo, lettura del testo qui, un modello che manca. */
class AppSearchEntryStatus(stack: AiStack, scope: CoroutineScope) : dev.pampa.mycloudgallery.feature.settings.search.SearchEntryStatus {
  override val summary: StateFlow<dev.pampa.mycloudgallery.feature.settings.search.SearchEntrySummary> =
    combine(stack.ai.indexer, stack.ai.textReadingHere, stack.localModels.manager.states) { indexer, reading, states ->
      val wanted = listOfNotNull(
        dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.QUERY_270M.id,
        dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.INDEX_440M.id.takeIf { indexer },
      )
      dev.pampa.mycloudgallery.feature.settings.search.SearchEntrySummary(
        indexer = indexer,
        textReadingHere = reading,
        modelMissing = wanted.any { states[it] !is dev.pampa.mycloudgallery.core.models.contract.ModelState.Ready },
      )
    }.stateIn(scope, SharingStarted.Eagerly, dev.pampa.mycloudgallery.feature.settings.search.SearchEntrySummary(false, false, false))
}

/**
 * Il ruolo Indicizzatore di QUESTO dispositivo e lo stato del calcolo (D15, D22): ruoli dall'istantanea dell'onboarding (ripubblicati nello
 * stato firmato), stato dal calcolo, lavoro che resta da `AiBacklog`, spazio dell'indice dalla cartella della ricerca. "Indicizza ora"
 * chiede un lavoro accelerato all'app ([indexNowRequest]).
 */
class AppIndexerControl(
  private val stack: AiStack,
  private val scope: CoroutineScope,
  private val indexNowRequest: () -> Unit,
) : IndexerControl {
  override val indexer: StateFlow<Boolean> get() = stack.ai.indexer

  override val status: StateFlow<AiStatus> = combine(stack.ai.indexer, stack.ai.status) { on, s -> if (!on) AiStatus.Off else s }
    .stateIn(scope, SharingStarted.Eagerly, AiStatus.Off)

  private val backlogFlow = MutableStateFlow(IndexerBacklog.NONE)
  override val backlog: StateFlow<IndexerBacklog> get() = backlogFlow

  private val spaceFlow = MutableStateFlow(IndexSpace.NONE)
  override val space: StateFlow<IndexSpace> get() = spaceFlow

  init {
    refresh()
  }

  /** Rilegge il lavoro che resta e lo spazio (all'apertura della pagina e dopo un cambio di ruolo). */
  fun refresh() {
    scope.launch {
      val work = runCatching { stack.ai.backlog() }.getOrNull()
      // PR-1 (E-I12): le aree senza indicizzatori, prima l'area comune.
      val areas = runCatching { stack.search.coverageNotices.areasWithoutIndexer() }.getOrDefault(emptyList())
        .distinct().sortedDescending().map { common -> if (common) AreaKind.COMMON_AREA else AreaKind.MY_PRIVATE_AREA }
      backlogFlow.value = IndexerBacklog(((work?.photos ?: 0L) + (work?.videos ?: 0L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), areas)
      // Revisione A: il testo letto sta nel registro dell'indice (le righe per chiave di prima non ci sono piu').
      val files = File(stack.search.dir, SearchGraph.INDEX_DIR).walkTopDown().filter { it.isFile }.toList()
      val vectors = files.filter { it.name.startsWith("vectors-") || it.name.startsWith("coarse-") }.sumOf { it.length() }
      spaceFlow.value = IndexSpace(vectorBytes = vectors, textBytes = files.sumOf { it.length() } - vectors)
    }
  }

  override suspend fun setIndexer(on: Boolean): IndexerChange {
    val preparer = DeviceRole.PREPARER in stack.roles.roles.value
    return when (stack.roles.setRoles(indexer = on, preparer = preparer)) {
      is RolesChange.Saved -> IndexerChange.SAVED.also { refresh() }
      RolesChange.Unchanged -> IndexerChange.UNCHANGED
      RolesChange.NotConfigured -> IndexerChange.NOT_CONFIGURED
    }
  }

  override fun indexNow() = indexNowRequest()

}
