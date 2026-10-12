package dev.pampa.mycloudgallery.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsLog
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Cio' che la Diagnostica mostra: le voci di ogni sezione (dai contributori dei moduli core) e quanti eventi
 * ricorda l'anello.
 */
data class DiagnosticsUiState(
  val loading: Boolean,
  val sections: Map<DiagSection, List<DiagItem>>,
  val recentEvents: Int,
)

/**
 * Raccoglie la Diagnostica: interroga ogni [DiagnosticsContributor] (multibinding di :app, vuoto finche' P9
 * non collega i moduli) e conta gli eventi del [DiagnosticsLog]. Scheletro P0: P8 aggiunge il dettaglio,
 * le azioni, il test di connessione e l'esportazione.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
  private val contributors: Set<@JvmSuppressWildcards DiagnosticsContributor>,
  private val log: DiagnosticsLog,
) : ViewModel() {

  private val mutableState = MutableStateFlow(DiagnosticsUiState(loading = true, sections = emptyMap(), recentEvents = 0))
  val state: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch {
      mutableState.value = collect(contributors, log)
    }
  }

  internal companion object {
    /** Una passata su tutti i contributori; uno che fallisce non toglie gli altri. */
    suspend fun collect(contributors: Set<DiagnosticsContributor>, log: DiagnosticsLog): DiagnosticsUiState {
      val bySection = sortedMapOf<DiagSection, MutableList<DiagItem>>()
      for (contributor in contributors) {
        val items = try {
          contributor.collect()
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (_: Exception) {
          emptyList()
        }
        for (item in items) bySection.getOrPut(item.section) { mutableListOf() } += item
      }
      return DiagnosticsUiState(loading = false, sections = bySection, recentEvents = log.snapshot().size)
    }
  }
}
