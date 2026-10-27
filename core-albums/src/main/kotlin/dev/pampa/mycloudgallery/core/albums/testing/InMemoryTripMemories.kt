package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.TripDigest
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripMemories
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlin.time.Duration

/**
 * Il riferimento della porta dei viaggi per i ricordi (Fase 9, P0, D12, §5.2): i viaggi e gli eventi del mondo finto ([trips], gia' rilevati,
 * con tutti i loro media), le proposte scartate ([dismissed]: impronte e, per i viaggi, le impronte del giorno d'inizio, come
 * `trip_dismissed`) e la visibilita' ([visibility], `visibleOrThrow`). Passa `TripMemoriesContract`; la porta vera sopra `TripReader` (P8)
 * passa la stessa suite e, sulle storie sintetiche della Fase 7, da' le stesse `TripDigest`.
 */
class InMemoryTripMemories(
  trips: List<TripDigest>,
  private val visibility: VisibleMedia,
  dismissed: Set<TripId> = emptySet(),
) : TripMemories {

  val trips: MutableList<TripDigest> = trips.toMutableList()
  val dismissed: MutableSet<TripId> = dismissed.toMutableSet()

  /** Quante volte e' stata chiesta (un test controlla che il passo leggero non la chiami a vuoto). */
  var calls: Int = 0
    private set

  /** "Non mi interessa" come lo scrive la Fase 7: l'impronta e, per un viaggio, quella del giorno d'inizio (VG-2). */
  fun dismiss(trip: TripDigest) {
    dismissed += trip.id
    if (trip.kind == TripKind.TRIP) dismissed += TripId.startKey(trip.kind, trip.days.from)
  }

  override suspend fun past(until: LocalDate, budget: Duration): List<TripDigest> {
    calls++
    val out = ArrayList<TripDigest>()
    for (trip in trips) {
      if (!trip.days.to.isBefore(until)) continue
      if (trip.id in dismissed) continue
      if (trip.kind == TripKind.TRIP && TripId.startKey(trip.kind, trip.days.from) in dismissed) continue
      val visible = visibility.visibleOrThrow(trip.members)
      val members = trip.members.filter { it in visible }
      if (members.isEmpty()) continue
      out += trip.copy(members = members)
    }
    return out.sortedWith(compareByDescending<TripDigest> { it.days.from }.thenBy { it.id.value })
  }
}
