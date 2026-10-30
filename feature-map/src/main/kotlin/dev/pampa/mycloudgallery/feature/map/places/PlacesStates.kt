package dev.pampa.mycloudgallery.feature.map.places

import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.contract.PlaceRow
import dev.pampa.mycloudgallery.feature.map.contract.PlacesSectionState
import dev.pampa.mycloudgallery.feature.map.map.MapLabels
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// GLI STATI DELLA SEZIONE LUOGHI (Fase 9, P4; docs/fase9-progetto.md §1.5 `PlacesSectionState`, §2.5, §2.11 `places-section`, D30, E-MAP5, E-MAP18, E-H9). Funzioni pure sull'indice dei punti: da li' vengono
// i conti ("9.000 foto con la posizione", "1.240 senza posizione") e i **luoghi piu' fotografati** (fino a 8: nome e numero), con i nomi di `PlaceNames` e **mai la parola "casa"** (un luogo
// il cui nome fosse "Casa" non c'e'). L'indice contiene **solo celle visibili** (la query esclude nascosti e gruppi nascosti): un luogo con soli nascosti non c'e', e un conto non include mai un nascosto.

object PlacesStates {

  /** Lo stato di adesso per [index], o null se l'indice si sta ancora costruendo (la sezione ha la riga "Mappa" subito, i numeri arrivano dopo). */
  fun of(index: GeoIndexState, names: MapPlaceNames): PlacesSectionState? = when (index) {
    GeoIndexState.Building -> null
    is GeoIndexState.Empty -> PlacesSectionState.NoGps(index.withoutPosition)
    is GeoIndexState.Ready -> PlacesSectionState.Ready(index.index.size, topPlaces(index, names), index.withoutPosition)
  }

  /**
   * I luoghi piu' fotografati: le celle all'ultimo livello della griglia (ogni cella e' un punto, o piu' foto nello stesso punto: stesso luogo) sommate per `place_id`, in ordine di
   * numero e poi di id, **solo quelli con un nome** (senza nome o "Casa" non si mostrano). Al piu' [PlacesSectionState.Ready.MAX_PLACES].
   */
  internal fun topPlaces(ready: GeoIndexState.Ready, names: MapPlaceNames): List<PlaceRow> {
    val tally = HashMap<Int, Int>()
    for (cluster in ready.index.clusters(WORLD, FINEST_ZOOM, 1)) {
      val place = cluster.placeId ?: continue
      if (place != 0) tally.merge(place, cluster.count, Int::plus)
    }
    val out = ArrayList<PlaceRow>(PlacesSectionState.Ready.MAX_PLACES)
    for ((place, count) in tally.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })) {
      val label = MapLabels.clean(names.label(place)) ?: continue
      out += PlaceRow(place, label, count)
      if (out.size == PlacesSectionState.Ready.MAX_PLACES) break
    }
    return out
  }

  /** Tutto il mondo: la vista da cui si contano i luoghi (l'indice ha solo punti validi, entro +-85 gradi). */
  private val WORLD = GeoBox(-GeoGrid.LON_E6, -GeoGrid.LAT_E6, GeoGrid.LON_E6, GeoGrid.LAT_E6)

  /** Uno zoom oltre cui ogni cella e' l'ultimo livello (il piu' fine). */
  private const val FINEST_ZOOM = 23.0
}

/**
 * La sezione Luoghi viva: lo stato dall'indice dei punti, calcolato **fuori dal thread principale** (centomila punti sommati per luogo costano poche decine di millisecondi) e solo quando qualcuno
 * lo guarda (`Lazily`); l'indice si costruisce al primo sguardo (`MapPoints.rebuild(OPEN)`), mai all'avvio dell'app. [state] null = non ancora pronto (primo fotogramma: la riga "Mappa" c'e' gia').
 */
class PlacesSectionModel(
  private val points: MapPoints,
  private val names: MapPlaceNames,
  scope: CoroutineScope,
  compute: CoroutineDispatcher = Dispatchers.Default,
) {
  val state: StateFlow<PlacesSectionState?> = flow {
    coroutineScope {
      launch { points.rebuild(RebuildReason.OPEN) }
      points.index.map { PlacesStates.of(it, names) }.collect { emit(it) }
    }
  }.flowOn(compute).stateIn(scope, SharingStarted.Lazily, null)
}

/**
 * Cosa apre una voce della sezione Luoghi delle Raccolte: `:app` la riconosce dal codice e porta alla pagina giusta. I codici sono stabili: la riga "Mappa" e i luoghi (`place_<id GeoNames>`, un
 * id pubblico di un posto, mai una coordinata). Le righe che dicono come stanno le cose non portano da nessuna parte.
 */
sealed interface PlacesEntryTarget {
  /** La mappa a schermo pieno. */
  data object OpenMap : PlacesEntryTarget

  /** La griglia del luogo. */
  data class Place(val placeId: Int) : PlacesEntryTarget {
    override fun toString(): String = "PlacesEntryTarget.Place"
  }

  /** "N foto senza posizione": niente da aprire. */
  data object Inert : PlacesEntryTarget

  companion object {
    const val MAP: String = "map"
    const val NO_POSITION: String = "places_nogps"
    const val PLACE_PREFIX: String = "place_"

    fun codeOf(placeId: Int): String = PLACE_PREFIX + placeId

    /** Il bersaglio di una voce, o null se il codice non e' di questa sezione (o e' di una versione piu' nuova). */
    fun parse(code: String): PlacesEntryTarget? = when {
      code == MAP -> OpenMap
      code == NO_POSITION -> Inert
      code.startsWith(PLACE_PREFIX) -> code.removePrefix(PLACE_PREFIX).takeIf { it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it > 0 }?.let(::Place)
      else -> null
    }
  }
}
