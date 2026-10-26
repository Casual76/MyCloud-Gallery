package dev.pampa.mycloudgallery.feature.library.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.attempt
import dev.pampa.mycloudgallery.feature.library.familyNoticeAfterHide
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Il ViewModel delle Raccolte: i conteggi dell'elenco, gli elementi di ogni raccolta-filtro e le azioni sui dati
 * personali (preferito, nascondi). Lo usano l'elenco, il pannello di dettaglio del tablet e le rotte delle griglie
 * filtrate: non ha stato proprio (la vista sta nell'indice), quindi averne uno per destinazione non costa niente.
 *
 * Gli elementi vengono sempre dall'indice, che non contiene i nascosti (E-H1).
 */
@HiltViewModel
class CollectionsViewModel @Inject constructor(
  private val source: CollectionItemsSource,
  intents: PhoneMediaIntents,
  private val actions: LocalMediaActions,
  val environment: GridEnvironment,
) : ViewModel() {

  private val canTrash: Boolean = intents.canTrash

  /** L'elenco con i conteggi vivi. Gli Eliminati di recente ci sono solo dove il cestino di sistema esiste. */
  val state: StateFlow<CollectionsUiState> = source.counts()
    .map { counts -> collectionsUiState(counts, canTrash) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), collectionsUiState(source.countsNow(), canTrash))

  /** Gli elementi di una raccolta-filtro, o null per i Nascosti e gli Eliminati di recente (che non sono filtri). */
  fun items(kind: CollectionKind): ViewerItems? = kind.filter?.let(source::items)

  private val noticeFlow = MutableStateFlow<ActionNotice?>(null)

  /** L'azione che non e' riuscita (preferito, nascondi), finche' la pagina non la chiude (R8). */
  internal val notice: StateFlow<ActionNotice?> = noticeFlow.asStateFlow()

  internal fun dismissNotice() {
    noticeFlow.value = null
  }

  /** Preferito acceso o spento per [ids]. Se non si salva la pagina lo dice (R8). */
  fun setFavorite(ids: Set<MediaId>, on: Boolean) {
    if (ids.isEmpty()) return
    viewModelScope.launch { if (!attempt { actions.setFavorite(ids, on) }) noticeFlow.value = ActionNotice.FavoriteFailed }
  }

  /**
   * Nasconde [ids]: spariscono da ogni superficie (E-H1) e si ritrovano nei Nascosti. Se non riesce la pagina lo dice (R8). Se riesce ma i
   * familiari vedono ancora qualcuno di loro (area comune, album condivisi) lo dice lo stesso (E-H2).
   */
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
