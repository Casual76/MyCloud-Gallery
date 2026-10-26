package dev.pampa.mycloudgallery.feature.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.albums.contract.TripRules
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumScreensClock
import dev.pampa.mycloudgallery.feature.albums.contract.TripsState
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// LE PROPOSTE DI VIAGGIO (Fase 7, P10; docs/fase7-progetto.md §1.5 `TripsState`, §2.3, D18-D20, E-AL16, E-H7). Raccolte -> Viaggi: viaggi ed eventi
// dal piu' recente; con un tocco "Crea album" (uno smart con periodo e luogo del viaggio, regole gia' compilate) o "Non mi interessa" (la
// proposta non torna su nessun dispositivo, voce sincronizzata). La prima schermata mostra gli ultimi 2 anni, "Mostra piu' vecchi" il resto.
// Senza una "casa" stimabile (nessuna foto con GPS, E-AL16) non ci sono viaggi: la schermata lo spiega una volta e propone gli eventi, che
// non vogliono il GPS. Mai coordinate, mai la casa: il contratto non le porta. Mai un nascosto (E-H7): conteggi e copertine vengono da P7.

/** Quello che una proposta ha appena fatto: la schermata porta l'utente all'album nato, o dice che e' gia' un album. */
sealed interface TripAccepted {
  data class Created(val album: AlbumId) : TripAccepted

  data class AlreadyAlbum(val album: AlbumId) : TripAccepted

  data object NotFound : TripAccepted
}

class TripsController(
  private val trips: Trips,
  private val clock: AlbumScreensClock,
  private val scope: CoroutineScope,
) {
  private val showOlder = MutableStateFlow(false)

  val state: StateFlow<TripsState> = combine(trips.proposals, showOlder) { proposals, older -> stateOf(proposals, older, clock.today()) }
    .stateIn(scope, SharingStarted.Eagerly, TripsState.Empty)

  /** "Mostra piu' vecchi": oltre i 2 anni della prima schermata. */
  fun showOlder() = showOlder.update { true }

  /** "Crea album" (D20): un tocco. [onDone] riceve l'album nato (o quello che era gia'). */
  fun accept(id: TripId, onDone: (TripAccepted) -> Unit = {}) {
    scope.launch {
      onDone(
        when (val out = trips.accept(id)) {
          is TripAcceptOutcome.Created -> TripAccepted.Created(out.album)
          is TripAcceptOutcome.AlreadyAlbum -> TripAccepted.AlreadyAlbum(out.album)
          TripAcceptOutcome.NotFound -> TripAccepted.NotFound
        },
      )
    }
  }

  /** "Non mi interessa" (D20): la proposta sparisce per sempre su ogni dispositivo dell'account. */
  fun dismiss(id: TripId) {
    scope.launch { trips.dismiss(id) }
  }

  companion object {
    /**
     * Lo stato dalle proposte. Senza casa (E-AL16) solo gli eventi; con la casa, gli ultimi 2 anni dal piu' recente e, se ce ne sono di piu'
     * vecchi, [TripsState.Proposals.hasOlder]. Funzione pura: provata senza Compose.
     */
    fun stateOf(proposals: TripProposals, showOlder: Boolean, today: LocalDate): TripsState {
      if (!proposals.homeKnown) return TripsState.NoGps(proposals.proposals.filter { it.kind == TripKind.EVENT }.recentFirst())
      if (proposals.proposals.isEmpty()) return TripsState.Empty
      val since = TripRules.firstScreenSince(today)
      val all = proposals.proposals.recentFirst()
      val recent = all.filter { !it.days.to.isBefore(since) }
      val older = all.size - recent.size
      // La prima schermata puo' essere vuota con proposte piu' vecchie: "Mostra piu' vecchi" le porta.
      return TripsState.Proposals(if (showOlder) all else recent, hasOlder = !showOlder && older > 0)
    }

    private fun List<TripProposal>.recentFirst(): List<TripProposal> = sortedWith(compareByDescending<TripProposal> { it.days.to }.thenByDescending { it.days.from })
  }
}
