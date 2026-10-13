package dev.pampa.mycloudgallery.feature.diagnostics

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsLog
import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsAggregator
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestInputSource
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestOutcome
import dev.pampa.mycloudgallery.feature.diagnostics.connection.ConnectionTestRunner
import dev.pampa.mycloudgallery.feature.diagnostics.connection.toItems
import dev.pampa.mycloudgallery.feature.diagnostics.export.DiagnosticsExporter
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportDocument
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportEnvironment
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Il test di connessione, visto dalla schermata. */
sealed interface ConnectionTestUiState {
  data object Idle : ConnectionTestUiState

  data object Running : ConnectionTestUiState

  class Finished(val outcome: ConnectionTestOutcome) : ConnectionTestUiState
}

/** Come e' andato il salvataggio del file dell'esportazione. */
enum class SaveResult { SAVED, FAILED }

/**
 * Cio' che la Diagnostica mostra: le voci di ogni sezione (dai contributori dei moduli), quanti eventi ricorda
 * l'anello, il test di connessione e l'esportazione pronta per l'anteprima.
 *
 * I primi tre campi sono quelli dello scheletro P0 (lo usano anche gli screenshot di `:app`); gli altri hanno un
 * valore di partenza, cosi' chi costruisce lo stato a mano non li deve nominare.
 *
 * @property export il file che verrebbe salvato, gia' pronto; null finche' non c'e' una lettura.
 * @property exportRefused l'esportazione e' stata bloccata dal controllo di grammatica (non dovrebbe mai succedere).
 * @property wireGuardAvailable WireGuard e' installato: solo allora «Apri WireGuard» e' un tasto vero.
 */
data class DiagnosticsUiState(
  val loading: Boolean,
  val sections: Map<DiagSection, List<DiagItem>>,
  val recentEvents: Int,
  val connectionTest: ConnectionTestUiState = ConnectionTestUiState.Idle,
  val export: ExportDocument? = null,
  val exportRefused: Boolean = false,
  val saveResult: SaveResult? = null,
  val wireGuardAvailable: Boolean = false,
) {
  /** Le voci almeno [DiagSeverity.WARNING]: quelle che l'utente deve guardare. */
  val attentionCount: Int get() = sections.values.sumOf { items -> items.count { it.severity >= DiagSeverity.WARNING } }

  fun attentionIn(section: DiagSection): Int = sections[section]?.count { it.severity >= DiagSeverity.WARNING } ?: 0

  fun worst(section: DiagSection): DiagSeverity? = sections[section]?.maxOfOrNull { it.severity }
}

/** Dove si scrive il file dell'esportazione scelto dall'utente (`ACTION_CREATE_DOCUMENT`). */
fun interface ExportSink {
  /** Scrive [text] in [uri]; falso se non e' riuscito. */
  suspend fun write(uri: Uri, text: String): Boolean
}

/** [ExportSink] su `ContentResolver`: il file lo sceglie l'utente con il selettore di sistema, nessun FileProvider. */
class ContentResolverExportSink(
  private val resolver: ContentResolver,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : ExportSink {

  override suspend fun write(uri: Uri, text: String): Boolean = withContext(dispatchers.io) {
    try {
      // "wt": scrive e tronca, cosi' un file scelto gia' esistente non resta con una coda del contenuto vecchio.
      val stream = resolver.openOutputStream(uri, "wt") ?: return@withContext false
      stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
      true
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      false
    }
  }
}

/**
 * La Diagnostica: raccoglie le voci dei [DiagnosticsContributor] (multibinding di `:app`, piu' quelli di questo
 * modulo: `di/DiagnosticsModule`), lancia il test di connessione a richiesta e prepara l'esportazione.
 *
 * **Quel che si vede nell'anteprima e' quel che si salva**: il file si produce una volta, da `DiagnosticsExporter`,
 * a ogni lettura; il salvataggio riscrive quello stesso testo (dopo aver rifatto il controllo di grammatica).
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
  private val contributors: Set<@JvmSuppressWildcards DiagnosticsContributor>,
  private val log: DiagnosticsLog,
  connectionTester: ConnectionTester,
  breaker: AuthCircuitBreaker,
  credentials: NasCredentialSource,
  inputSource: Optional<@JvmSuppressWildcards ConnectionTestInputSource>,
  private val vpn: VpnController,
) : ViewModel() {

  private val aggregator = DiagnosticsAggregator(contributors, log)
  private val testRunner = ConnectionTestRunner(connectionTester, breaker, credentials, inputSource.orElse(null))

  private var snapshot: DiagnosticsSnapshot = DiagnosticsSnapshot.EMPTY
  private var testState: ConnectionTestUiState = ConnectionTestUiState.Idle
  private var loading = true
  private var saveResult: SaveResult? = null

  /**
   * Revisione 2, F15: il documento dell'anteprima, **fermo** da quando si apre la pagina dell'esportazione a quando si
   * chiude. Un test di connessione che finisce o una rilettura nel frattempo non cambiano ne' l'anteprima ne' il file:
   * si salva esattamente quello che l'utente ha visto.
   */
  private var frozen: ExportDocument? = null
  private var refreshJob: Job? = null
  private var testJob: Job? = null

  private val mutableState = MutableStateFlow(DiagnosticsUiState(loading = true, sections = emptyMap(), recentEvents = 0))
  val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()

  init {
    refresh()
  }

  /** Rilegge tutto. Un'altra richiesta mentre si sta leggendo riparte da capo (vale l'ultima). */
  fun refresh() {
    refreshJob?.cancel()
    loading = true
    publish()
    refreshJob = viewModelScope.launch {
      snapshot = aggregator.collect()
      loading = false
      publish()
    }
  }

  /** Lancia il test di connessione. Un test in corso non si lancia due volte. */
  fun runConnectionTest() {
    if (testState is ConnectionTestUiState.Running) return
    testState = ConnectionTestUiState.Running
    publish()
    testJob = viewModelScope.launch {
      val outcome = try {
        testRunner.run()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        ConnectionTestOutcome.TimedOut
      }
      testState = ConnectionTestUiState.Finished(outcome)
      publish()
    }
  }

  /** L'intent che apre WireGuard, o null se non e' installato. */
  fun wireGuardIntent(): Intent? = vpn.openWireGuardIntent()

  /**
   * Scrive il file dell'esportazione dove ha scelto l'utente. Il testo e' quello dell'anteprima, ricontrollato:
   * se per qualunque motivo non rispettasse la grammatica, non si scrive niente.
   */
  fun saveExport(uri: Uri, sink: ExportSink) {
    val document = frozen ?: mutableState.value.export ?: return
    viewModelScope.launch {
      val ok = DiagnosticsExporter.conforms(document.text) && sink.write(uri, document.text)
      saveResult = if (ok) SaveResult.SAVED else SaveResult.FAILED
      publish()
    }
  }

  /** All'apertura della pagina dell'esportazione: da qui l'anteprima (e il file) non cambiano piu'. */
  fun freezeExport() {
    frozen = mutableState.value.export
    publish()
  }

  /** Dimentica l'esito dell'ultimo salvataggio e sblocca l'anteprima (alla chiusura della pagina dell'esportazione). */
  fun clearSaveResult() {
    saveResult = null
    frozen = null
    publish()
  }

  private fun publish() {
    val merged = mergeTest(snapshot.sections)
    var document: ExportDocument? = null
    var refused = false
    // Prima della prima lettura non c'e' niente da esportare (e un file vuoto in anteprima sarebbe fuorviante).
    if (!(loading && snapshot === DiagnosticsSnapshot.EMPTY)) {
      try {
        document = DiagnosticsExporter.render(
          DiagnosticsSnapshot(merged, snapshot.recentEvents, snapshot.unavailable),
          ExportEnvironment(sdkInt = Build.VERSION.SDK_INT),
        )
      } catch (_: IllegalStateException) {
        refused = true
      }
    }
    mutableState.update {
      DiagnosticsUiState(
        loading = loading,
        sections = merged,
        recentEvents = snapshot.recentEvents,
        connectionTest = testState,
        export = frozen ?: document,
        exportRefused = frozen == null && refused,
        saveResult = saveResult,
        wireGuardAvailable = vpn.openWireGuardIntent() != null,
      )
    }
  }

  /** Le voci del test (se e' girato) vanno nella loro sezione, dopo quelle dei contributori. */
  private fun mergeTest(sections: Map<DiagSection, List<DiagItem>>): Map<DiagSection, List<DiagItem>> {
    val finished = (testState as? ConnectionTestUiState.Finished)?.outcome ?: return sections
    val items = sections[DiagSection.CONNECTION_TEST].orEmpty() + finished.toItems()
    return DiagSection.entries
      .mapNotNull { section ->
        val list = if (section == DiagSection.CONNECTION_TEST) items else sections[section]
        if (list.isNullOrEmpty()) null else section to list
      }
      .toMap()
  }

  internal companion object {
    /** Una passata su tutti i contributori; uno che fallisce non toglie gli altri (resta la sua voce «non ha risposto»). */
    suspend fun collect(contributors: Set<DiagnosticsContributor>, log: DiagnosticsLog): DiagnosticsUiState {
      val snapshot = DiagnosticsAggregator(contributors, log).collect()
      return DiagnosticsUiState(loading = false, sections = snapshot.sections, recentEvents = snapshot.recentEvents)
    }
  }
}
