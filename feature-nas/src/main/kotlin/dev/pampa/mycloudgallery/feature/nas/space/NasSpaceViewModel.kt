package dev.pampa.mycloudgallery.feature.nas.space

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.feature.nas.contract.NasArea
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreas
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Spazio NAS (docs/fase4-progetto.md §1.5 "NasSpaceState", §2.7): si calcola **solo quando l'utente apre la pagina** (e quando
// cambia area o tocca "Ricalcola"), mai in background. Sola lettura: non c'e' niente da confermare.

/** Lo stato della pagina: le aree fra cui scegliere, quella scelta e il suo spazio. */
@Immutable
internal data class SpaceUi(
  val areas: List<NasArea> = emptyList(),
  val share: String? = null,
  val space: NasSpaceState = NasSpaceState.Loading,
)

@HiltViewModel
class NasSpaceViewModel @Inject constructor(
  private val space: NasSpace,
  areas: NasAreas,
) : ViewModel() {

  private val mutable = MutableStateFlow(SpaceUi())
  internal val state: StateFlow<SpaceUi> = mutable

  private var job: Job? = null

  init {
    viewModelScope.launch {
      areas.areas.collect { list ->
        val keep = mutable.value.share?.takeIf { share -> list.any { it.share.equals(share, ignoreCase = true) } }
        val next = keep ?: list.firstOrNull()?.share
        mutable.update { it.copy(areas = list, share = next) }
        if (next != null && next != keep) load(next)
      }
    }
  }

  /** L'area scelta cambia: si ricalcola per lei. */
  internal fun select(share: String) {
    if (mutable.value.share == share) return
    mutable.update { it.copy(share = share) }
    load(share)
  }

  /** "Ricalcola": una lettura nuova dello spazio dell'area di adesso. */
  internal fun reload() {
    mutable.value.share?.let(::load)
  }

  private fun load(share: String) {
    job?.cancel()
    mutable.update { it.copy(space = NasSpaceState.Loading) }
    job = viewModelScope.launch {
      val result = space.load(share)
      mutable.update { if (it.share == share) it.copy(space = result) else it }
    }
  }
}
