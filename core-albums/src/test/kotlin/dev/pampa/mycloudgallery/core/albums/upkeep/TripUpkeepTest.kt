package dev.pampa.mycloudgallery.core.albums.upkeep

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * VG-1: i viaggi si cercano subito dopo l'installazione e dopo le scansioni, non solo di notte. Mai cercati: si cerca appena il catalogo e' fermo (non sul
 * primo gruppo di foto di una scansione che dura minuti); cercati senza una casa: di nuovo, ma solo se il catalogo e' cambiato e sono passati dieci minuti
 * (le posizioni delle foto arrivano da META a poco a poco); con una casa basta la notte.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripUpkeepTest {

  private class FakeTrips(searched: Boolean, homeKnown: Boolean) : Trips {
    override val proposals: MutableStateFlow<TripProposals> = MutableStateFlow(TripProposals(emptyList(), homeKnown, searched))
    var detections = 0
    var nextHomeKnown = false
    var incomplete = false

    override suspend fun detect(budget: Duration): TripReport {
      detections++
      if (!incomplete) proposals.value = TripProposals(emptyList(), nextHomeKnown, searched = true)
      return TripReport(0, 0, nextHomeKnown, incomplete)
    }

    override suspend fun accept(id: TripId): TripAcceptOutcome = TripAcceptOutcome.NotFound

    override suspend fun dismiss(id: TripId) = Unit
  }

  @Test
  fun maiCercatiSiCercaAppenaIlCatalogoEFermoNonSulPrimoGruppo() = runTest {
    val trips = FakeTrips(searched = false, homeKnown = false)
    val reports = ArrayList<TripReport>()
    val upkeep = TripUpkeep(backgroundScope, trips, FakeAlbumClocks(), onReport = { reports += it }, quiet = 60.seconds)

    // Una scansione lunga: un gruppo di foto ogni 20 secondi.
    repeat(5) {
      upkeep.poke()
      advanceTimeBy(20.seconds)
    }
    runCurrent()
    assertThat(trips.detections).isEqualTo(0)

    advanceTimeBy(41.seconds)
    runCurrent()
    assertThat(trips.detections).isEqualTo(1)
    assertThat(reports).hasSize(1)
    assertThat(trips.proposals.value.searched).isTrue()
  }

  @Test
  fun conUnaCasaNotaNonCercaFuoriDallaNotte() = runTest {
    val trips = FakeTrips(searched = true, homeKnown = true)
    val upkeep = TripUpkeep(backgroundScope, trips, FakeAlbumClocks(), quiet = 60.seconds)

    upkeep.poke()
    advanceTimeBy(61.seconds)
    runCurrent()

    assertThat(trips.detections).isEqualTo(0)
  }

  @Test
  fun cercatiSenzaCasaSiRicercaSoloSeIlCatalogoECambiatoEPassanoDieciMinuti() = runTest {
    val clocks = FakeAlbumClocks()
    val trips = FakeTrips(searched = false, homeKnown = false)
    val upkeep = TripUpkeep(backgroundScope, trips, clocks, quiet = 60.seconds, retry = 10.minutes)

    upkeep.poke()
    advanceTimeBy(61.seconds)
    runCurrent()
    assertThat(trips.detections).isEqualTo(1)

    // Subito dopo, un'altra novita': troppo presto per riprovare (dieci minuti non sono passati).
    upkeep.poke()
    advanceTimeBy(61.seconds)
    runCurrent()
    assertThat(trips.detections).isEqualTo(1)

    // Passati i dieci minuti (l'orologio monotono) e con una novita' nuova: la ricerca si rifa', e stavolta trova la casa.
    clocks.monotonic += 11.minutes.inWholeMilliseconds
    trips.nextHomeKnown = true
    upkeep.poke()
    advanceTimeBy(61.seconds)
    runCurrent()
    assertThat(trips.detections).isEqualTo(2)
    assertThat(trips.proposals.value.homeKnown).isTrue()

    // Con la casa nota non si cerca piu' fuori dalla notte.
    clocks.monotonic += 11.minutes.inWholeMilliseconds
    upkeep.poke()
    advanceTimeBy(61.seconds)
    runCurrent()
    assertThat(trips.detections).isEqualTo(2)
  }

  @Test
  fun unaRicercaInterrottaDalBudgetNonValeComeFatta() = runTest {
    val trips = FakeTrips(searched = false, homeKnown = false).also { it.incomplete = true }
    val upkeep = TripUpkeep(backgroundScope, trips, FakeAlbumClocks(), quiet = 60.seconds)

    upkeep.poke()
    advanceTimeBy(61.seconds)
    runCurrent()
    upkeep.poke()
    advanceTimeBy(61.seconds)
    runCurrent()

    // Non cercati: la ricerca riprova a ogni catalogo fermo, e la sezione non dice «servono foto con la posizione».
    assertThat(trips.detections).isEqualTo(2)
    assertThat(trips.proposals.value.searched).isFalse()
  }

  @Test
  fun unErroreDellaRicercaSiContaENonFermaIlResto() = runTest {
    val trips = object : Trips by FakeTrips(searched = false, homeKnown = false) {
      override suspend fun detect(budget: Duration): TripReport = error("guasto")
    }
    val errors = ArrayList<Exception>()
    val upkeep = TripUpkeep(backgroundScope, trips, FakeAlbumClocks(), onError = { errors += it }, quiet = 60.seconds)

    upkeep.poke()
    advanceTimeBy(61.seconds)
    runCurrent()

    assertThat(errors).hasSize(1)
    assertThat(upkeep.checkNow()).isTrue()
  }
}
