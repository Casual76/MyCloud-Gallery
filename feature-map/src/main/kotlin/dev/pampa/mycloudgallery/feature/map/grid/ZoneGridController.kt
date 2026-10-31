package dev.pampa.mycloudgallery.feature.map.grid

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapPlaceNames
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.map.MapLabels
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// LA GRIGLIA DI UNA ZONA O DI UN LUOGO (Fase 9, P4; docs/fase9-progetto.md §1.5 `ZoneGridState`, §2.10 "Griglia della zona", §2.5 Luoghi, D28, D30, D31, E-MAP9, E-MAP15, E-V6, E-H9).
// `IndexOrderedTimelineView` degli elementi **dal piu' recente** (l'ordine dell'istantanea), **viva** sull'istantanea visibile: quando cambia (un nascosto, un'eliminazione, una nuova foto) la griglia
// si rifa da sola, e subito prima di mostrare ogni elemento passa dal **filtro finale dei Nascosti**. Un tocco apre il viewer (anche per i media solo sul NAS, E-MAP6: lo fa `:app`).
//  - **Zona** (una cella della bolla): i media della cella dall'indice dei punti; **luogo**: le celle dell'istantanea con quel `place_id`.
//  - Una cella dell'istantanea e' un **gruppo** (una raffica o le copie simili sono una cella sola, con la copertina): i membri non copertina di un gruppo visibile non hanno una cella e
//    non compaiono qui, come in Libreria (il conto della bolla li comprende).

/** Cosa si sta guardando: una zona (la cella di una bolla) o un luogo (la sezione Luoghi). Nessuna coordinata nel `toString()`. */
sealed interface ZoneTarget {
  data class Cell(val key: CellKey) : ZoneTarget {
    override fun toString(): String = "ZoneTarget.Cell"
  }

  data class Place(val placeId: Int) : ZoneTarget {
    override fun toString(): String = "ZoneTarget.Place"
  }
}

class ZoneGridController(
  private val target: ZoneTarget,
  private val points: MapPoints,
  private val visible: VisibleMedia,
  private val names: MapPlaceNames,
  private val filter: FinalFilter,
  private val scope: CoroutineScope,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) {

  /** La griglia, o null finche' non e' pronta (primo fotogramma: lo scheletro, subito). */
  val state: StateFlow<ZoneGridState?> = combine(visible.snapshot.filterNotNull(), points.index) { snapshot, index -> snapshot to index }
    .map { (snapshot, index) -> build(snapshot, index) }
    .flowOn(compute)
    .stateIn(scope, SharingStarted.Eagerly, null)

  init {
    // Aprendo la griglia senza essere passati dalla mappa (ripresa del processo) l'indice si costruisce qui, fuori dal thread principale.
    if (target is ZoneTarget.Cell) retry()
  }

  /** "Riprova" dopo "Non riesco a leggere le posizioni delle foto" (MA-1): rifa `rebuild(OPEN)` fuori dal thread principale. */
  fun retry() {
    scope.launch(compute) { points.rebuild(RebuildReason.OPEN) }
  }

  private suspend fun build(snapshot: TimelineSnapshot, index: GeoIndexState): ZoneGridState? {
    val candidates: List<MediaId> = when (target) {
      is ZoneTarget.Cell -> {
        val ready = index as? GeoIndexState.Ready ?: return when (index) {
          is GeoIndexState.Empty -> ZoneGridState.Empty
          // MA-1: un errore non e' "nessuna foto qui".
          GeoIndexState.Failed -> ZoneGridState.Failed
          else -> null
        }
        ready.index.members(target.key, MEMBER_LIMIT)
      }
      is ZoneTarget.Place -> idsOfPlace(snapshot, target.placeId)
    }
    // Nell'ordine dell'istantanea (0 e' il piu' recente); i media che l'istantanea non ha (nascosti, eliminati, non copertina di un gruppo) non entrano.
    val inSnapshot = candidates.map { it to snapshot.indexOf(it) }.filter { it.second >= 0 }.sortedBy { it.second }
    val shown = filter.shown(inSnapshot.map { it.first }).toSet()
    val order = inSnapshot.filter { it.first in shown }.map { it.second }.toIntArray()
    if (order.isEmpty()) return ZoneGridState.Empty
    return ZoneGridState.Ready(labelOf(snapshot, order), IndexOrderedTimelineView(snapshot, order))
  }

  private fun idsOfPlace(snapshot: TimelineSnapshot, placeId: Int): List<MediaId> {
    val out = ArrayList<MediaId>()
    for (i in 0 until snapshot.size) if (snapshot.placeAt(i) == placeId) out += snapshot.idAt(i)
    return out
  }

  /** Il nome della zona: il luogo scelto, o il piu' frequente fra gli elementi (mai "Casa"); null = "Questa zona". */
  private fun labelOf(snapshot: TimelineSnapshot, order: IntArray): String? {
    val placeId = when (target) {
      is ZoneTarget.Place -> target.placeId
      is ZoneTarget.Cell -> mostFrequentPlace(snapshot, order)
    }
    return if (placeId == 0) null else MapLabels.clean(names.label(placeId))
  }

  private fun mostFrequentPlace(snapshot: TimelineSnapshot, order: IntArray): Int {
    val tally = HashMap<Int, Int>()
    for (i in order.take(TALLY_LIMIT)) {
      val place = snapshot.placeAt(i)
      if (place != 0) tally.merge(place, 1, Int::plus)
    }
    return tally.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key ?: 0
  }

  companion object {
    /** Quanti media di una cella si portano in griglia al massimo (E-MAP9: migliaia nello stesso punto sono una bolla sola, la griglia scorre come la Libreria). */
    const val MEMBER_LIMIT: Int = 50_000

    /** Quanti elementi si contano per scegliere il nome della zona. */
    const val TALLY_LIMIT: Int = 2_000
  }
}
