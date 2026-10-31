package dev.pampa.mycloudgallery.feature.map.places

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.map.R
import dev.pampa.mycloudgallery.feature.map.contract.PlacesSectionState
import dev.pampa.mycloudgallery.feature.map.ui.MapText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// LA SEZIONE LUOGHI DELLE RACCOLTE (Fase 9, P4; docs/fase9-progetto.md §1.5 `PlacesSectionState`, §2.5, D30, E-MAP5, E-MAP18, E-L14; "Avvio e pagine istantanei" in docs/decisioni.md). Ogni sezione delle Raccolte e' un
// dato che chi la possiede consegna con un `CollectionsSectionProvider` (feature-library); `:app` lo lega con un `@Provides @IntoSet` (P13). Qui nasce il dato, **della stessa forma campo per campo** di
// `CollectionEntryGroup` (come fanno Persone e Ricordi, perche' i feature non si conoscono): il legame in `:app` e' un `map` di poche righe, e `PlacesSectionBindingTest` lo prova su un fornitore vero di
// feature-library. Nascondibile e ordinabile come le altre (P11 della Fase 7); in coda se l'ordine e' gia' salvato (E-L14), nel posto dopo Ricordi altrimenti.
//
// **Istantanea**: la riga "Mappa" c'e' **subito** (con una frase che non aspetta nessun numero); i conti e i luoghi arrivano dall'indice dei punti, calcolati fuori dal thread principale.

/** Una voce di una sezione: la stessa forma di `CollectionEntry` (feature-library). */
@Immutable
data class MapSectionEntry(
  val code: String,
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val tone: FluidTone,
  val badge: Int = 0,
  val trailing: String? = null,
) {
  /** D33: mai il titolo o il sottotitolo (un luogo): solo che tipo di voce e'. Un luogo non e' mai nominato, nemmeno col suo id. */
  override fun toString(): String = "MapSectionEntry(${PlacesEntryTarget.parse(code)})"
}

/** Una sezione: la stessa forma di `CollectionEntryGroup`. I Luoghi si possono nascondere dalle Raccolte (D30). */
@Immutable
data class MapSectionData(
  val code: String,
  val title: String,
  val entries: List<MapSectionEntry>,
  val hideable: Boolean = true,
  val hint: String? = null,
) {
  override fun toString(): String = "MapSectionData($code, ${entries.size} voci)"
}

/**
 * I dati della sezione Luoghi: [section] e' la sezione come dato, **mai vuota** (la riga "Mappa" c'e' sempre, cosi' la sezione non sparisce dal layout e la mappa si apre anche senza foto
 * con la posizione: lo dice il suo stato). [model] si crea solo quando `:app` costruisce la sezione; l'indice si costruisce al primo sguardo.
 */
class PlacesSectionSources(
  private val resources: Resources,
  private val model: PlacesSectionModel,
  scope: CoroutineScope,
) {
  /** Lo stato del §1.5 di adesso, o null finche' l'indice non e' pronto. */
  val sectionState: StateFlow<PlacesSectionState?> = model.state

  /** La riga "Riprova" (MA-1, `PlacesEntryTarget.Retry`): rifa la lettura dei punti. */
  fun retry() = model.retry()

  /** La sezione come dato. */
  val section: StateFlow<MapSectionData> = model.state.map { sectionOf(resources, it) }.stateIn(scope, SharingStarted.Lazily, sectionOf(resources, null))

  companion object {
    /** Il codice stabile della sezione: lo stesso di `CollectionSections.PLACES` (lo prova `PlacesSectionBindingTest`). */
    const val CODE: String = "places"

    /** La sezione per uno stato (funzione pura, provata su ogni caso); [state] null = l'indice si sta costruendo. */
    fun sectionOf(resources: Resources, state: PlacesSectionState?): MapSectionData {
      val entries = ArrayList<MapSectionEntry>()
      entries += mapRow(resources, state)
      if (state is PlacesSectionState.Ready) {
        for (place in state.places) {
          entries += MapSectionEntry(
            code = PlacesEntryTarget.codeOf(place.placeId),
            title = place.label,
            subtitle = MapText.photos(resources, place.count),
            icon = Icons.Rounded.Place,
            tone = FluidTone.Neutral,
          )
        }
      }
      // MA-1: l'indice non si legge: dopo la riga "Mappa" (che lo dice) c'e' "Riprova"; non c'e' nessun conto da dare (non si sa quante sono senza posizione).
      if (state is PlacesSectionState.Failed) {
        entries += MapSectionEntry(
          PlacesEntryTarget.RETRY, resources.getString(R.string.mappa_row_retry), resources.getString(R.string.mappa_row_retry_detail),
          Icons.Rounded.Refresh, FluidTone.Info,
        )
      }
      val without = when (state) {
        is PlacesSectionState.Ready -> state.withoutPosition
        is PlacesSectionState.NoGps -> state.withoutPosition
        PlacesSectionState.Failed, null -> 0
      }
      if (without > 0) {
        entries += MapSectionEntry(
          PlacesEntryTarget.NO_POSITION, MapText.withoutPosition(resources, without), resources.getString(R.string.mappa_row_without_detail),
          Icons.Rounded.LocationOff, FluidTone.Neutral,
        )
      }
      return MapSectionData(code = CODE, title = resources.getString(R.string.mappa_section), entries = entries, hideable = true)
    }

    /** La riga "Mappa": la frase non aspetta nessun numero (primo fotogramma), poi dice quante foto hanno la posizione, o che non ce ne sono. */
    private fun mapRow(resources: Resources, state: PlacesSectionState?): MapSectionEntry {
      val detail = when (state) {
        null -> resources.getString(R.string.mappa_row_map_detail)
        is PlacesSectionState.Ready -> MapText.withPosition(resources, state.withPosition)
        is PlacesSectionState.NoGps -> resources.getString(R.string.mappa_row_map_none)
        PlacesSectionState.Failed -> resources.getString(R.string.mappa_row_map_failed)
      }
      return MapSectionEntry(PlacesEntryTarget.MAP, resources.getString(R.string.mappa_row_map), detail, Icons.Rounded.Map, FluidTone.Info)
    }
  }
}
