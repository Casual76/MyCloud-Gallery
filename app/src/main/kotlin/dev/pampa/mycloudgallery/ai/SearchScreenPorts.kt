package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import dev.pampa.mycloudgallery.core.common.files.StateFiles
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
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Le PORTE di Cerca e della pagina Ricerca e modelli (feature-search, P8) sopra le impostazioni personali, i ruoli e `AiGraph` (P11).

/**
 * L'ordine dei risultati (D48) sopra `search_order`, per codice: la scelta vale subito (CE-3), anche fuori casa senza l'ora del NAS e anche
 * prima che il registro dei dati personali ci sia ([PersonalSettingsHub.searchOrder]); la sincronizzazione con gli altri dispositivi viene dopo.
 */
class PersonalSearchOrderStore(private val personal: PersonalSettingsHub, scope: CoroutineScope) : SearchOrderStore {
  override val order: StateFlow<ResultOrder> = personal.searchOrder
    .map { ResultOrder.fromCode(it.code) }
    .stateIn(scope, SharingStarted.Eagerly, ResultOrder.fromCode(personal.searchOrder.value.code))

  override suspend fun setOrder(order: ResultOrder) {
    personal.setSearchOrder(SearchOrderSetting.fromCode(order.code))
  }
}

/** La versione del testo accettata per l'account (0 se la lettura e' spenta, D41). */
class PersonalTextReadingAccepted(personal: PersonalSettingsHub, scope: CoroutineScope) : TextReadingAccepted {
  override val acceptedVersion: StateFlow<Int> = personal.textReading
    .map { (it as? TextReadingChoice.OnForAccount)?.acceptedTextVersion ?: 0 }
    .stateIn(scope, SharingStarted.Eagerly, 0)
}

/**
 * "Animazione delle foto in movimento" (D50, Q16 (b)) sopra `motion_animation`: acceso finche' non si sceglie. L'interruttore non si ignora
 * piu' quando il registro dei dati personali non c'e' (VM-2): la scelta vale subito e arriva al registro appena c'e'
 * ([PersonalSettingsHub.motionAnimation]).
 */
class PersonalMotionAnimationSettings(
  private val personal: PersonalSettingsHub,
  scope: CoroutineScope,
) : dev.pampa.mycloudgallery.feature.settings.library.MotionAnimationSettings {
  override val enabled: StateFlow<Boolean> = personal.motionAnimation

  override suspend fun setEnabled(on: Boolean) {
    personal.setMotionAnimation(on)
  }
}

/** Lo stato della voce "Ricerca e modelli" delle Impostazioni: ruolo, lettura del testo qui, un modello che manca. */
class AppSearchEntryStatus(stack: AiStack, scope: CoroutineScope) : dev.pampa.mycloudgallery.feature.settings.search.SearchEntryStatus {
  override val summary: StateFlow<dev.pampa.mycloudgallery.feature.settings.search.SearchEntrySummary> =
    combine(stack.ai.indexer, stack.ai.textReadingHere, stack.localModels.manager.states, stack.textSwitch.state) { indexer, reading, states, switch ->
      val wanted = listOfNotNull(
        dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.QUERY_270M.id,
        dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.INDEX_440M.id.takeIf { indexer },
      )
      dev.pampa.mycloudgallery.feature.settings.search.SearchEntrySummary(
        indexer = indexer,
        textReadingHere = reading,
        modelMissing = wanted.any { states[it] !is dev.pampa.mycloudgallery.core.models.contract.ModelState.Ready },
        // LT-2: la lettura e' accesa dall'account ma questo dispositivo non ha ancora visto l'avviso: la voce lo segnala.
        readingNeedsNotice = switch is dev.pampa.mycloudgallery.core.ai.contract.TextReadingState.AwaitingNotice,
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
  /** L'ultima misura breve (IX-3): `noBackupFilesDir/ai-probe`; null = solo in memoria. */
  private val probeFile: File? = null,
  private val indexNowRequest: () -> Unit,
) : IndexerControl {
  override val indexer: StateFlow<Boolean> get() = stack.ai.indexer

  override val status: StateFlow<AiStatus> = combine(stack.ai.indexer, stack.ai.status) { on, s -> if (!on) AiStatus.Off else s }
    .stateIn(scope, SharingStarted.Eagerly, if (stack.ai.indexer.value) stack.ai.status.value else AiStatus.Off)

  private val backlogFlow = MutableStateFlow(IndexerBacklog.NONE)
  override val backlog: StateFlow<IndexerBacklog> get() = backlogFlow

  private val spaceFlow = MutableStateFlow(IndexSpace.NONE)
  override val space: StateFlow<IndexSpace> get() = spaceFlow

  private val spaceCache = IndexSpaceCache(read = {
    val files = File(stack.search.dir, SearchGraph.INDEX_DIR).walkTopDown().filter { it.isFile }.toList()
    val vectors = files.filter { it.name.startsWith("vectors-") || it.name.startsWith("coarse-") }.sumOf { it.length() }
    IndexSpace(vectorBytes = vectors, textBytes = files.sumOf { it.length() } - vectors)
  })

  init {
    refresh()
    // IX-3: il lavoro che resta e lo spazio si rileggono a ogni tappa finita (prima, solo alla nascita e dopo un cambio di ruolo).
    scope.launch {
      stack.ai.status.collect { if (it !is AiStatus.Running) refresh() }
    }
  }

  /** Rilegge il lavoro che resta e lo spazio (all'apertura della pagina, dopo ogni tappa e dopo un cambio di ruolo). */
  override fun refresh() {
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
      val work = runCatching { stack.ai.backlog() }.getOrNull()
      // PR-1 (E-I12): le aree senza indicizzatori, prima l'area comune.
      val areas = runCatching { stack.search.coverageNotices.areasWithoutIndexer() }.getOrDefault(emptyList())
        .distinct().sortedDescending().map { common -> if (common) AreaKind.COMMON_AREA else AreaKind.MY_PRIVATE_AREA }
      backlogFlow.value = IndexerBacklog(((work?.photos ?: 0L) + (work?.videos ?: 0L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), areas)
      // Revisione A: il testo letto sta nel registro dell'indice (le righe per chiave di prima non ci sono piu').
      spaceFlow.value = spaceCache.current()
    }
  }

  override suspend fun setIndexer(on: Boolean): IndexerChange {
    val preparer = DeviceRole.PREPARER in stack.roles.roles.value
    return when (stack.roles.setRoles(indexer = on, preparer = preparer)) {
      is RolesChange.Saved -> IndexerChange.SAVED.also {
        refresh()
        // IX-8: spento il ruolo, il buffer di analisi (fino a 500 MB) non serve piu': si svuota quando lo spegnimento e' salvato.
        if (!on) scope.launch { runCatching { stack.ai.buffer.clear() } }
      }
      RolesChange.Unchanged -> IndexerChange.UNCHANGED
      RolesChange.NotConfigured -> IndexerChange.NOT_CONFIGURED
    }
  }

  override fun indexNow() = indexNowRequest()

  override fun savedProbe(): ProbeResult? {
    val file = probeFile ?: return null
    return try {
      val text = if (file.isFile && file.length() < 128) StateFiles.readText(file, Charsets.US_ASCII) else null
      val parts = text?.trim()?.split(' ') ?: return null
      val rate = parts.getOrNull(0)?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() } ?: return null
      val backend = AiBackend.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: return null
      ProbeResult(rate, backend, parts.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0) ?: 0L)
    } catch (e: IOException) {
      null
    }
  }

  override fun saveProbe(result: ProbeResult) {
    val file = probeFile ?: return
    try {
      StateFiles.writeText(file, "${result.imagesPerSecond} ${result.backend.name} ${result.coldStartMs}", Charsets.US_ASCII)
    } catch (e: IOException) {
      // Solo una comodita': la stima si rifa' alla prossima misura (l'errore e' nel log di `StateFiles`).
    }
  }

  companion object {
    const val PROBE_FILE = "ai-probe"
  }
}
