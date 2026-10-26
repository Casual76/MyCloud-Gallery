package dev.pampa.mycloudgallery.debugtools.eval.albums

import java.time.LocalDate

// IL METODO "A POZZO" PER GLI ALBUM (docs/fase7-progetto.md §5.3): per non premiare il motore che si sta misurando, la verita' di un viaggio non si
// ricava dall'album. Tre fasi, nell'ordine:
//  1. **alla cieca**: l'utente segna le foto del viaggio scorrendo **i giorni del viaggio nella Libreria**, senza guardare l'album;
//  2. **la corsa**: lo strumento crea l'album smart dal titolo (in memoria) e mostra dove il motore e l'utente non sono d'accordo: i sicuri e i
//     suggeriti che l'utente non ha segnato. Per ognuno l'utente dice "del viaggio" o "non del viaggio";
//  3. **i mancanti**: le foto dei giorni attorno al viaggio (non nell'album) fra cui l'utente segna quelle che sono del viaggio.
// Cio' che l'utente non segna resta "non del viaggio". Codice puro, LO STESSO FILE in `core-albums/src/test/.../eval/` e in
// `app/src/debug/.../debugtools/eval/albums/`.

object AlbumsEvalReview {
  /** I giorni attorno al viaggio (prima e dopo) in cui si cercano i mancanti: la partenza e il ritorno spesso hanno la data accanto. */
  const val NEIGHBOUR_DAYS: Long = 3

  /** Da giudicare dopo la corsa: i sicuri e i suggeriti dell'album che l'utente non ha ancora detto ne' del viaggio ne' no. */
  class Pending(val sure: List<String>, val suggested: List<String>) {
    val isEmpty: Boolean get() = sure.isEmpty() && suggested.isEmpty()

    override fun toString(): String = "Pending(${sure.size} sicuri, ${suggested.size} suggeriti)"
  }

  fun pending(run: TripRun, inTrip: Set<String>, notInTrip: Set<String>): Pending = Pending(
    run.sure.filter { it !in inTrip && it !in notInTrip },
    run.suggested.filter { it !in inTrip && it !in notInTrip },
  )

  /** I giorni delle foto da guardare alla cieca: quelli del viaggio. */
  fun blindDays(trip: EvalTrip): Pair<LocalDate, LocalDate> = trip.from to trip.to

  /** I giorni attorno: [NEIGHBOUR_DAYS] prima e [NEIGHBOUR_DAYS] dopo il viaggio (due intervalli, estremi compresi). */
  fun neighbourDays(trip: EvalTrip): List<Pair<LocalDate, LocalDate>> = listOf(
    trip.from.minusDays(NEIGHBOUR_DAYS) to trip.from.minusDays(1),
    trip.to.plusDays(1) to trip.to.plusDays(NEIGHBOUR_DAYS),
  )

  /**
   * Le foto dei giorni attorno fra cui cercare i mancanti: tutte quelle che l'album non ha ne' inserito ne' suggerito (se l'album ne ha
   * preso una, e' fra i sicuri o i suggeriti da giudicare). Nell'ordine in cui arrivano.
   */
  fun missingPool(neighbours: List<String>, run: TripRun): List<String> {
    val taken = run.sure.toHashSet().also { it.addAll(run.suggested) }
    return neighbours.filter { it !in taken }.distinct()
  }

  /** Quante foto di un elenco sono segnate come del viaggio: per le intestazioni di una pagina. */
  fun marked(ids: Collection<String>, inTrip: Set<String>): Int = ids.count { it in inTrip }
}
