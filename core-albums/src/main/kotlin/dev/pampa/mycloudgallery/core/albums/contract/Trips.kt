package dev.pampa.mycloudgallery.core.albums.contract

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.TripKind
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// VIAGGI ED EVENTI (docs/fase7-progetto.md §1.2 "Viaggi", §2.3, D18-D20, E-AL10, E-AL15, E-AL16, E-H7). Contratto di P0; la "casa" solo
// locale, i viaggi, gli eventi e le proposte sono di P7 (trips/), l'esecutore di riferimento e' `InMemoryTrips`. **Nessuna coordinata
// della casa fuori da trips/**: nessun tipo pubblico qui la porta (solo "lontano da casa si'/no" e la distanza arrotondata a 10 km), e
// `home_estimate` lo nomina solo trips/ (Phase7ArchitectureTest).

/**
 * L'impronta di una proposta (D20): SHA-256 di tipo, inizio, fine e luoghi (in ordine). La voce `trip_dismissed:<impronta>` la scarta su
 * ogni dispositivo dell'account; la stessa proposta rilevata da due dispositivi ha la stessa impronta.
 */
@JvmInline
value class TripId(val value: String) {
  init {
    require(HEX_64.matches(value)) { "L'impronta di una proposta sono 64 cifre esadecimali." }
  }

  override fun toString(): String = "TripId(${value.take(8)}…)"

  companion object {
    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    fun of(kind: TripKind, days: LocalDayRange, placeIds: Collection<Int>): TripId {
      val md = MessageDigest.getInstance("SHA-256")
      md.update("mcg-trip\u0000${kind.code}\u0000${days.from}\u0000${days.to}\u0000".toByteArray(Charsets.UTF_8))
      md.update(placeIds.toSortedSet().joinToString(",").toByteArray(Charsets.UTF_8))
      return TripId(md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
  }
}

/**
 * Una proposta della sezione Viaggi (D20): [label] gia' in italiano ("Oslo, marzo 2026", "Sabato 12 aprile 2026"); [count] e [cover]
 * **senza nascosti** (E-H7: una proposta di soli nascosti non esiste). [placeIds] i luoghi `CITY`/`NEAR` delle foto (vuoto per un evento
 * senza GPS). Mai coordinate.
 */
data class TripProposal(
  val id: TripId,
  val kind: TripKind,
  val days: LocalDayRange,
  val label: String,
  val count: Int,
  val cover: MediaId,
  val placeIds: List<Int>,
) {
  override fun toString(): String = "TripProposal(${kind.code}, $count)"
}

/** Le proposte (§1.5 `TripsState`): dal piu' recente; [homeKnown] falso = nessuna casa stimabile (E-AL16: solo eventi, `TRIP_NO_GPS`). */
data class TripProposals(val proposals: List<TripProposal>, val homeKnown: Boolean) {
  companion object {
    val EMPTY: TripProposals = TripProposals(emptyList(), homeKnown = false)
  }
}

/** Il resoconto di una rilevazione: solo numeri. */
data class TripReport(val trips: Int, val events: Int, val homeKnown: Boolean, val incomplete: Boolean)

sealed interface TripAcceptOutcome {
  /** L'album smart nato dalla proposta (periodo e luoghi del viaggio, sorgenti tutte, D20). */
  data class Created(val album: AlbumId) : TripAcceptOutcome

  /** La proposta era gia' diventata un album: lo stesso, nessun doppione. */
  data class AlreadyAlbum(val album: AlbumId) : TripAcceptOutcome

  data object NotFound : TripAcceptOutcome
}

/** Viaggi ed eventi (D18-D20). Nessuna notifica nella Fase 7. */
interface Trips {
  val proposals: StateFlow<TripProposals>

  /** Rileva casa, viaggi ed eventi (una volta per notte e dopo una prima scansione, §2.3). */
  suspend fun detect(budget: Duration): TripReport

  /** "Crea album": un tocco (D20). */
  suspend fun accept(id: TripId): TripAcceptOutcome

  /** "Non mi interessa": voce sincronizzata `trip_dismissed:<impronta>`; non torna su nessun dispositivo. */
  suspend fun dismiss(id: TripId)
}

/**
 * I numeri di D18 e D19, per P7 e P12. **Interpretazione di P0** (D19 non la fissava): il "ritmo almeno 5 volte quello medio dei giorni
 * con foto" di un evento e' il numero di media nella finestra di [EVENT_MAX_HOURS] ore confrontato con la media dei media per giorno con
 * foto: un evento ha almeno max([EVENT_MIN_MEDIA], [EVENT_RATE_FACTOR] x media giornaliera) media. P7 la conferma sui dati sintetici di P12.
 */
object TripRules {
  /** Lato della cella della casa: 0,01° (~1 km). La casa = la cella piu' pesante con le sue 8 vicine. */
  const val HOME_CELL_DEGREES: Double = 0.01
  const val HOME_WINDOW_MONTHS: Long = 12
  const val HOME_MIN_SHARE: Double = 0.15
  const val HABITUAL_MIN_SHARE: Double = 0.10

  /** Le foto "serali o notturne" (ora locale dello scatto): dalle 19 alle 8. */
  const val EVENING_FROM_HOUR: Int = 19
  const val MORNING_UNTIL_HOUR: Int = 8

  /** Raggio di casa: il 90° percentile della distanza delle foto serali dal centro, almeno 2 km. */
  const val HOME_RADIUS_PERCENTILE: Int = 90
  const val HOME_RADIUS_MIN_KM: Double = 2.0

  /** Un giorno e' lontano se la mediana delle distanze supera max(50 km, 3 x raggio di casa). */
  const val FAR_MIN_KM: Double = 50.0
  const val FAR_HOME_RADIUS_FACTOR: Double = 3.0

  /** Al piu' 2 giorni senza foto in mezzo a un viaggio. */
  const val MAX_GAP_DAYS: Long = 2

  /** Un viaggio di un giorno solo: almeno 20 foto oltre 100 km. */
  const val SINGLE_DAY_MIN_MEDIA: Int = 20
  const val SINGLE_DAY_MIN_KM: Double = 100.0

  const val EVENT_MIN_MEDIA: Int = 30
  const val EVENT_MAX_HOURS: Long = 4
  const val EVENT_RATE_FACTOR: Int = 5

  /** L'unica distanza da casa che esce da trips/: arrotondata a 10 km. */
  const val DISTANCE_ROUNDING_KM: Int = 10

  /** La prima schermata mostra gli ultimi 2 anni ("Mostra piu' vecchi"). */
  const val FIRST_SCREEN_YEARS: Long = 2

  /** I giorni della prima schermata, visti [today]. */
  fun firstScreenSince(today: LocalDate): LocalDate = today.minusYears(FIRST_SCREEN_YEARS)
}
