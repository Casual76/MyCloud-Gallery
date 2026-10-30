package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.TripDigest
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripRules
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import kotlin.time.Duration

/**
 * I viaggi e gli eventi passati per i ricordi (docs/fase9-progetto.md D12, E-MEM13, E-H7, E-H8, §2.1 passo 3; P8): la porta `TripMemories`, scritta
 * sopra [TripReader] e sulle proposte che la rilevazione ha gia' conservato ([TripStore]), **senza rifare la rilevazione** (leggerla costa una tabella,
 * non la Libreria intera).
 *
 *  - **Accettati compresi, scartati mai.** Una proposta diventata album c'e' (con l'album: "Apri l'album", E-MEM13); una scartata ("Non mi
 *    interessa") non c'e' mai, ne' per impronta ne' per giorno d'inizio ([TripId.startKey]: un viaggio scartato e poi allungato torna con
 *    un'impronta nuova, ma lo stesso inizio). Gli stessi due controlli di `CatalogTrips.isDismissed`.
 *  - **Solo media propri e visibili** ([TripReader.mine] con il filtro finale dei nascosti: un media nascosto un attimo fa non c'e'; non
 *    screenshot ne' registrazioni dello schermo). Una proposta senza piu' un media visibile non c'e' (E-H7). Un guasto della lettura si **propaga**
 *    (R17: un database guasto non e' "nessun viaggio").
 *  - **Titolo e luoghi come li mostra la sezione Viaggi** ([ShownTrips], R16): un luogo di soli media nascosti dopo la rilevazione non resta nel
 *    titolo di un ricordo.
 *  - **Nessuna coordinata, nessuna casa**: il `TripDigest` non ha un tipo che le porti e questa classe non le legge (la rilevazione e'
 *    di `CatalogTrips`). I media sono id, in ordine di scatto.
 *  - **Finiti prima di [until]** (un viaggio in corso non e' un ricordo), dal piu' recente (inizio decrescente, poi impronta). Entro il
 *    budget (orologio monotono): finito il tempo risponde con quelli fatti, senza errore.
 *  - **Un evento** porta il quarto d'ora locale del suo primo scatto (la chiave `event:<giorno>:<quarto d'ora>` del ricordo, D9, [startQuarterOf]).
 */
internal class PastTrips(
  private val reader: TripReader,
  private val store: TripStore,
  private val dismissals: DismissedTrips,
  private val titles: TripTitles,
  private val clocks: AlbumClocks,
) {

  suspend fun past(until: LocalDate, budget: Duration): List<TripDigest> {
    val deadline = clocks.monotonicMs() + budget.inWholeMilliseconds
    val dismissed = dismissals.dismissed.value
    val rows = store.proposals()
      .filter { it.days.to.isBefore(until) && !isDismissed(it, dismissed) }
      .sortedWith(compareByDescending<StoredProposal> { it.days.from }.thenBy { it.id.value })
    val out = ArrayList<TripDigest>()
    for (row in rows) {
      if (clocks.monotonicMs() >= deadline) break
      // Fra una pagina e l'altra si ricontrolla il tempo: a meta' di una proposta non si da' niente (un conteggio a meta' sarebbe sbagliato).
      val pool = reader.mine(days = row.days, finalMask = true, expired = { clocks.monotonicMs() > deadline }) ?: break
      digestOf(row, pool)?.let { out += it }
    }
    return out
  }

  private fun isDismissed(row: StoredProposal, dismissed: Set<TripId>): Boolean =
    row.id in dismissed || (row.kind == TripKind.TRIP && TripId.startKey(row.kind, row.days.from) in dismissed)

  private fun digestOf(row: StoredProposal, pool: List<AlbumCandidate>): TripDigest? {
    if (pool.isEmpty()) return null
    val ordered = pool.sortedWith(compareBy<AlbumCandidate>({ it.takenAtUtcMs }, { it.media.value }))
    val members = ordered.map { it.media }.distinct()
    val shown = ShownTrips.of(titles, row, pool)
    val quarter = if (row.kind == TripKind.EVENT) startQuarterOf(row.days, ordered) else null
    return TripDigest(row.id, row.kind, row.days, shown.label, shown.placeIds, members, row.album, quarter)
  }

  /**
   * Il quarto d'ora locale (0..95) in cui comincia l'evento di [days]: il primo scatto del **primo giorno** da cui parte una finestra di
   * [TripRules.EVENT_MAX_HOURS] ore con almeno [TripRules.EVENT_MIN_MEDIA] scatti (lo stesso criterio con cui la rilevazione trova l'evento, nel suo
   * minimo: un solo scatto sperduto al mattino, o uno arrivato dopo da un altro dispositivo, non sposta l'inizio e quindi non cambia l'id del
   * ricordo, D9). Se nessuna finestra arriva al minimo (i nascosti hanno tolto troppo) vale il primo scatto del giorno con un'ora; null se non ce n'e'.
   */
  private fun startQuarterOf(days: LocalDayRange, ordered: List<AlbumCandidate>): Int? {
    val first = ordered.filter { it.day == days.from }
    val window = TripRules.EVENT_MAX_HOURS * MILLIS_PER_HOUR
    var j = 0
    for (i in first.indices) {
      if (j < i) j = i
      while (j + 1 < first.size && first[j + 1].takenAtUtcMs - first[i].takenAtUtcMs <= window) j++
      if (j - i + 1 >= TripRules.EVENT_MIN_MEDIA) return quarterOf(first[i])
    }
    return first.firstOrNull { it.localTime != null }?.let { quarterOf(it) }
  }

  private fun quarterOf(c: AlbumCandidate): Int? = c.localTime?.let { it.hour * QUARTERS_PER_HOUR + it.minute / MINUTES_PER_QUARTER }

  private companion object {
    const val MILLIS_PER_HOUR = 3_600_000L
    const val QUARTERS_PER_HOUR = 4
    const val MINUTES_PER_QUARTER = 15
  }
}
