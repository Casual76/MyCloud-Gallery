package dev.pampa.mycloudgallery.feature.library.utility

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.attempt
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.library.collections.GridEnvironment
import dev.pampa.mycloudgallery.feature.library.familyNoticeAfterHide
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Fase 7, P8 (D24): il ViewModel delle pagine-filtro dell'Utility (oggi Modificate). Come `CollectionsViewModel`: nessuno stato
// proprio, la vista sta nell'indice (che non contiene mai i nascosti, E-H1) e il filtro EDITED e' lo stesso `LibraryFilter` delle altre.

/** I conteggi vivi delle pagine-filtro dell'Utility, dalla stessa fonte delle Raccolte. */
internal fun CollectionItemsSource.utilityCounts(): Flow<Map<UtilityPage, Int>> {
  val flows = UtilityPage.filters.map { page -> items(page.filter!!).view.map { view -> page to view.size } }
  return combine(flows) { pairs -> pairs.toMap() }
}

/** I conteggi di adesso, senza aspettare il flusso: lo stato iniziale delle voci. */
internal fun CollectionItemsSource.utilityCountsNow(): Map<UtilityPage, Int> =
  UtilityPage.filters.associateWith { page -> items(page.filter!!).view.value.size }

@HiltViewModel
class UtilityViewModel @Inject constructor(
  private val source: CollectionItemsSource,
  intents: PhoneMediaIntents,
  private val actions: LocalMediaActions,
  val environment: GridEnvironment,
) : ViewModel() {

  /** Dove c'e' un cestino (quello di sistema, o quello del NAS): senza, la voce "Eliminati di recente" non c'e'. */
  val canTrash: Boolean = intents.canTrash

  /** I conteggi vivi delle pagine-filtro. */
  val counts: StateFlow<Map<UtilityPage, Int>> = source.utilityCounts()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), source.utilityCountsNow())

  /** Gli elementi di una pagina-filtro, o null per Doppioni ed Eliminati di recente (non sono filtri). */
  fun items(page: UtilityPage): ViewerItems? = page.filter?.let(source::items)

  private val noticeFlow = MutableStateFlow<ActionNotice?>(null)

  /** L'azione che non e' riuscita (preferito, nascondi), finche' la pagina non la chiude (R8). */
  internal val notice: StateFlow<ActionNotice?> = noticeFlow.asStateFlow()

  internal fun dismissNotice() {
    noticeFlow.value = null
  }

  fun setFavorite(ids: Set<MediaId>, on: Boolean) {
    if (ids.isEmpty()) return
    viewModelScope.launch { if (!attempt { actions.setFavorite(ids, on) }) noticeFlow.value = ActionNotice.FavoriteFailed }
  }

  fun hide(ids: Set<MediaId>) {
    if (ids.isEmpty()) return
    viewModelScope.launch {
      if (attempt { actions.setHidden(ids, true) }) familyNoticeAfterHide(actions, ids)?.let { noticeFlow.value = it } else noticeFlow.value = ActionNotice.HideFailed
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
