package dev.pampa.mycloudgallery.core.albums.eval

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// IL CONFRONTO FRA L'ELENCO DEI VIAGGI E LE PROPOSTE (docs/fase7-progetto.md §5.3 e §2.3, F89): l'utente scrive i viaggi principali degli ultimi
// 2 anni **prima** di guardare; poi lo strumento li confronta con le proposte di `CatalogTrips`. Codice puro (date e conteggi), LO STESSO FILE in
// `core-albums/src/test/.../eval/` e in `app/src/debug/.../debugtools/eval/albums/` (vedi `AlbumsEvalMetrics`). Il resoconto e' di soli numeri.

/** Un periodo proposto da `CatalogTrips`: i giorni locali e il tipo. Mai i luoghi. */
class ProposedDays(val from: LocalDate, val to: LocalDate, val isTrip: Boolean) {
  init {
    require(!to.isBefore(from)) { "Proposta con i giorni rovesciati." }
  }

  val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

  override fun toString(): String = "ProposedDays(${if (isTrip) "viaggio" else "evento"}, $days giorni)"
}

enum class TripVerdict {
  /** Una proposta di viaggio copre almeno la meta' dei giorni del viaggio e almeno la meta' dei suoi giorni sono del viaggio. */
  FOUND,

  /** Una proposta tocca il viaggio, ma non lo copre bene (troppo corta, troppo lunga, o solo in parte). */
  TOUCHED,

  /** Nessuna proposta di viaggio tocca i suoi giorni. */
  MISSING,

  /** Il viaggio finisce prima degli ultimi 2 anni: non conta per F89. */
  OUTSIDE,
}

/** L'esito del confronto. [verdicts] per id di viaggio; il resto sono conteggi. */
class TripsFound(
  val verdicts: Map<String, TripVerdict>,
  /** Le proposte di viaggio degli ultimi 2 anni. */
  val tripProposals: Int,
  /** Fra queste, quelle che non corrispondono a nessun viaggio dell'elenco (non sono necessariamente sbagliate: l'elenco ha i soli viaggi principali). */
  val unlisted: Int,
  val eventProposals: Int,
  /** Se `CatalogTrips` ha stimato una casa: senza, nessun viaggio e' possibile (E-AL16). */
  val homeKnown: Boolean,
) {
  val listed: Int get() = verdicts.values.count { it != TripVerdict.OUTSIDE }
  val found: Int get() = verdicts.values.count { it == TripVerdict.FOUND }
  val touched: Int get() = verdicts.values.count { it == TripVerdict.TOUCHED }
  val missing: Int get() = verdicts.values.count { it == TripVerdict.MISSING }

  /** F89: tutti i viaggi dell'elenco sono fra le proposte (e l'elenco non e' vuoto). */
  val meets: Boolean get() = listed > 0 && found == listed

  fun toNumbersText(): String {
    val out = StringBuilder()
    out.append("F89, viaggi proposti sui dati veri: $listed viaggi nell'elenco degli ultimi 2 anni\n")
    out.append("trovati: $found (una proposta copre almeno la meta' dei giorni, da entrambe le parti)\n")
    out.append("toccati ma non bene: $touched\n")
    out.append("non trovati: $missing\n")
    val outside = verdicts.values.count { it == TripVerdict.OUTSIDE }
    if (outside > 0) out.append("fuori dagli ultimi 2 anni (non contano): $outside\n")
    out.append("proposte di viaggio negli ultimi 2 anni: $tripProposals, di cui non nell'elenco: $unlisted\n")
    out.append("proposte di evento: $eventProposals\n")
    out.append("casa stimata: ${if (homeKnown) "si" else "no (nessun viaggio e' possibile senza il GPS)"}\n")
    out.append("per viaggio: ").append(verdicts.entries.joinToString(", ") { "${it.key} ${label(it.value)}" }).append('\n')
    out.append("F89: ").append(if (meets) "RAGGIUNTO" else "NON raggiunto").append('\n')
    return out.toString()
  }

  override fun toString(): String = "TripsFound($found/$listed)"

  private fun label(v: TripVerdict): String = when (v) {
    TripVerdict.FOUND -> "trovato"
    TripVerdict.TOUCHED -> "toccato"
    TripVerdict.MISSING -> "non trovato"
    TripVerdict.OUTSIDE -> "fuori"
  }
}

object TripsMatching {
  /** La quota di giorni in comune che serve, da entrambe le parti. */
  const val MIN_SHARE: Double = 0.5

  /** Quanti giorni (estremi compresi) hanno in comune i due periodi. */
  fun overlapDays(aFrom: LocalDate, aTo: LocalDate, bFrom: LocalDate, bTo: LocalDate): Long {
    val from = if (aFrom.isAfter(bFrom)) aFrom else bFrom
    val to = if (aTo.isBefore(bTo)) aTo else bTo
    return if (to.isBefore(from)) 0 else ChronoUnit.DAYS.between(from, to) + 1
  }

  fun covers(trip: EvalTrip, p: ProposedDays): Boolean {
    val overlap = overlapDays(trip.from, trip.to, p.from, p.to).toDouble()
    return overlap / trip.days >= MIN_SHARE && overlap / p.days >= MIN_SHARE
  }

  fun touches(trip: EvalTrip, p: ProposedDays): Boolean = overlapDays(trip.from, trip.to, p.from, p.to) > 0

  /**
   * Confronta l'elenco con le proposte. Contano le proposte che finiscono entro gli ultimi 2 anni ([since] = oggi meno 2 anni, come la prima
   * schermata di `TripRules.firstScreenSince`) e i viaggi dell'elenco che non finiscono prima.
   */
  fun compare(listed: List<EvalTrip>, proposals: List<ProposedDays>, since: LocalDate, homeKnown: Boolean): TripsFound {
    val window = listed.filter { !it.to.isBefore(since) }
    val trips = proposals.filter { it.isTrip && !it.to.isBefore(since) }
    val verdicts = LinkedHashMap<String, TripVerdict>()
    for (trip in listed) {
      verdicts[trip.id] = when {
        trip.to.isBefore(since) -> TripVerdict.OUTSIDE
        trips.any { covers(trip, it) } -> TripVerdict.FOUND
        trips.any { touches(trip, it) } -> TripVerdict.TOUCHED
        else -> TripVerdict.MISSING
      }
    }
    val unlisted = trips.count { p -> window.none { covers(it, p) } }
    return TripsFound(verdicts, trips.size, unlisted, proposals.count { !it.isTrip && !it.to.isBefore(since) }, homeKnown)
  }
}
