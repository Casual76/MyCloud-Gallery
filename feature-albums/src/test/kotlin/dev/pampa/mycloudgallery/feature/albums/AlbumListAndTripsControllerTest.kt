package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.albums.contract.TripAcceptOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.albums.contract.TripProposal
import dev.pampa.mycloudgallery.core.albums.contract.TripProposals
import dev.pampa.mycloudgallery.core.albums.contract.Trips
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.testing.closeAlbum
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumListState
import dev.pampa.mycloudgallery.feature.albums.contract.TripsState
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListController
import dev.pampa.mycloudgallery.feature.albums.list.CreateAlbumResult
import dev.pampa.mycloudgallery.feature.albums.list.ListMove
import dev.pampa.mycloudgallery.feature.albums.trips.TripAccepted
import dev.pampa.mycloudgallery.feature.albums.trips.TripsController
import java.time.LocalDate
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** L'elenco degli album (D21, D33, E-SH21, Q5) e le proposte di viaggio (D19, D20, E-AL16). */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumListAndTripsControllerTest {

  private val other = AccountId("test2")

  private fun ready(c: AlbumListController) = c.ui.value.state as AlbumListState.Ready

  @Test
  fun unElencoSenzaAlbumEVuotoEdopoLaCreazioneHaIlSuoAlbum() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val list = AlbumListController(world.albums, world.sharedAlbums, backgroundScope)
    assertThat(list.ui.value.state).isEqualTo(AlbumListState.Empty)
    val created = list.createManual("  Compleanno di Anna ")
    assertThat(created).isInstanceOf(CreateAlbumResult.Created::class.java)
    assertThat(ready(list).mineAndShared.map { it.title }).containsExactly("Compleanno di Anna")
  }

  @Test
  fun unNomeNonValidoNonCreaNienteELoDice() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val list = AlbumListController(world.albums, world.sharedAlbums, backgroundScope)
    assertThat(list.createManual("   ")).isEqualTo(CreateAlbumResult.InvalidTitle(TitleProblem.EMPTY))
    assertThat(list.createManual("x".repeat(65))).isEqualTo(CreateAlbumResult.InvalidTitle(TitleProblem.TOO_LONG))
    assertThat(list.ui.value.state).isEqualTo(AlbumListState.Empty)
  }

  @Test
  fun imieIDondivisiStannoInsiemeEGliSmartAParte() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val list = AlbumListController(world.albums, world.sharedAlbums, backgroundScope)
    list.createManual("Mio")
    world.albums.create("Oslo 2026", AlbumKind.SMART, dev.pampa.mycloudgallery.core.albums.contract.SmartRules(null, null, "neve"))
    world.albumBy(other, "Di famiglia")
    val state = ready(list)
    assertThat(state.mineAndShared.map { it::class.simpleName }).containsExactly("Manual", "Shared")
    assertThat(state.smart.map { it.title }).containsExactly("Oslo 2026")
  }

  @Test
  fun lOrdineSiCambiaEConOrdinePersonalizzatoSiPuoSpostare() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val list = AlbumListController(world.albums, world.sharedAlbums, backgroundScope)
    val a = (list.createManual("Alfa") as CreateAlbumResult.Created).id
    val b = (list.createManual("Beta") as CreateAlbumResult.Created).id
    val c = (list.createManual("Gamma") as CreateAlbumResult.Created).id
    list.setSort(AlbumListSort.NAME)
    assertThat(ready(list).mineAndShared.map { it.title }).containsExactly("Alfa", "Beta", "Gamma").inOrder()
    // Senza l'ordine personalizzato non si sposta (le azioni "Sposta su" e "Sposta giu'" non ci sono).
    assertThat(list.ui.value.reorderable).isFalse()
    assertThat(list.canMove(b, ListMove.UP)).isFalse()
    list.setSort(AlbumListSort.CUSTOM)
    assertThat(list.ui.value.reorderable).isTrue()
    val before = ready(list).mineAndShared.map { it.id }
    val middle = before[1]
    assertThat(list.canMove(before.first(), ListMove.UP)).isFalse()
    assertThat(list.canMove(before.last(), ListMove.DOWN)).isFalse()
    assertThat(list.canMove(middle, ListMove.UP)).isTrue()
    list.move(middle, ListMove.UP)
    assertThat(ready(list).mineAndShared.map { it.id }).containsExactly(before[1], before[0], before[2]).inOrder()
    list.move(middle, ListMove.DOWN)
    assertThat(ready(list).mineAndShared.map { it.id }).containsExactly(before[0], before[1], before[2]).inOrder()
    assertThat(setOf(a, b, c)).containsExactlyElementsIn(before)
  }

  @Test
  fun unAlbumLasciatoSiRitrovaEUnAvvisoLetto_SparisceUnaVoltaSola() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val list = AlbumListController(world.albums, world.sharedAlbums, backgroundScope)
    val shared = world.albumBy(other, "Di famiglia")
    world.sharedAlbums.leave(shared)
    assertThat(list.ui.value.left.map { it.id }).containsExactly(shared)
    // Un album lasciato non e' nell'elenco, ma l'elenco non e' vuoto: si ritrova da "Album che non mostri piu'".
    assertThat(list.ui.value.state).isNotEqualTo(AlbumListState.Empty)
    list.rejoin(shared)
    assertThat(list.ui.value.left).isEmpty()
    assertThat(ready(list).mineAndShared.map { it.id }).containsExactly(shared)
  }

  @Test
  fun unAlbumChiusoConLaCopiaDaUnAvvisoEUnaCopiaPersonale() = runTest(UnconfinedTestDispatcher()) {
    // Quello che chi chiude ha scelto (Q5 b) arriva a chi legge: avviso con la copia, e la copia e' un album personale.
    val world = AlbumsTestWorld()
    val list = AlbumListController(world.albums, world.sharedAlbums, backgroundScope)
    val id = world.albumBy(other, "Gita al lago")
    world.addToShared(id, other, world.commonPhoto(1))
    world.common.closeAlbum(id, other, SharedState.CLOSED, OthersAfterClose.KEEP_COPY)
    val notice = ready(list).notices.single() as SharedAlbumNotice.Closed
    assertThat(notice.mode).isEqualTo(CloseMode.STOP)
    assertThat(notice.keptCopy).isNotNull()
    assertThat(ready(list).mineAndShared.map { it.id }).contains(notice.keptCopy)
    list.dismissNotice(notice)
    assertThat(ready(list).notices).isEmpty()
  }

  // --- Viaggi -----------------------------------------------------------------------------------------------------------------

  private val today = LocalDate.of(2026, 10, 21)

  private fun trip(kind: TripKind, from: LocalDate, days: Long = 3, count: Int = 100): TripProposal {
    val range = LocalDayRange(from, from.plusDays(days))
    return TripProposal(TripId.of(kind, range, if (kind == TripKind.TRIP) listOf(1) else emptyList()), kind, range, "Proposta $from", count, ShareFixtures.media(7), if (kind == TripKind.TRIP) listOf(1) else emptyList())
  }

  private val recent = trip(TripKind.TRIP, LocalDate.of(2026, 3, 10))
  private val recentEvent = trip(TripKind.EVENT, LocalDate.of(2026, 8, 1), days = 0, count = 40)
  private val old = trip(TripKind.TRIP, LocalDate.of(2023, 5, 1))
  private val older = trip(TripKind.TRIP, LocalDate.of(2022, 5, 1))

  @Test
  fun leProposteSonoDalPiuRecenteConGliUltimiDueAnniEMostraPiuVecchiPortaIlResto() {
    val proposals = TripProposals(listOf(older, recent, old, recentEvent), homeKnown = true)
    val first = TripsController.stateOf(proposals, showOlder = false, today = today) as TripsState.Proposals
    assertThat(first.proposals).containsExactly(recentEvent, recent).inOrder()
    assertThat(first.hasOlder).isTrue()
    val all = TripsController.stateOf(proposals, showOlder = true, today = today) as TripsState.Proposals
    assertThat(all.proposals).containsExactly(recentEvent, recent, old, older).inOrder()
    assertThat(all.hasOlder).isFalse()
  }

  @Test
  fun senzaGpsNonCiSonoViaggiMaSoloEventi_E_AL16() {
    val state = TripsController.stateOf(TripProposals(listOf(recent, recentEvent), homeKnown = false), false, today)
    assertThat(state).isEqualTo(TripsState.NoGps(listOf(recentEvent)))
    assertThat(TripsController.stateOf(TripProposals(emptyList(), homeKnown = false), false, today)).isEqualTo(TripsState.NoGps(emptyList()))
    assertThat(TripsController.stateOf(TripProposals(emptyList(), homeKnown = true), false, today)).isEqualTo(TripsState.Empty)
  }

  @Test
  fun primaDellaPrimaRicercaNonSiDiceCheServonoFotoConLaPosizione_VG1() {
    // VG-1: «nessuna casa» prima della prima ricerca non vuol dire niente; con TripProposals.EMPTY lo stato e' «sta cercando», non NoGps.
    assertThat(TripsController.stateOf(TripProposals.EMPTY, false, today)).isEqualTo(TripsState.Searching)
    assertThat(TripsController.stateOf(TripProposals(emptyList(), homeKnown = false, searched = false), true, today)).isEqualTo(TripsState.Searching)
    // Dopo la ricerca, senza casa, si': solo allora.
    assertThat(TripsController.stateOf(TripProposals(emptyList(), homeKnown = false, searched = true), false, today)).isEqualTo(TripsState.NoGps(emptyList()))
  }

  @Test
  fun unElencoVuotoNelPrimoSecondoNonDiceNessunAlbum_AP1() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    world.albums.status.value = dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus.Loading
    val list = AlbumListController(world.albums, world.sharedAlbums, backgroundScope)
    assertThat(list.ui.value.state).isEqualTo(AlbumListState.Loading)
    // Gli album sono stati letti e davvero non ce ne sono: allora si'.
    world.albums.status.value = dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus.Ready
    assertThat(list.ui.value.state).isEqualTo(AlbumListState.Empty)
  }

  @Test
  fun conGliAlbumGiaLettiNonSiTornaInCaricamentoSeLoStatoOscilla_AP1() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val list = AlbumListController(world.albums, world.sharedAlbums, backgroundScope)
    list.createManual("Mio")
    world.albums.status.value = dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus.Loading
    // Con un album in elenco il caricamento non cancella la schermata.
    assertThat(list.ui.value.state).isInstanceOf(AlbumListState.Ready::class.java)
  }

  @Test
  fun soloProposteVecchieDannoUnaPrimaSchermataVuotaEPiuVecchi() {
    val state = TripsController.stateOf(TripProposals(listOf(old, older), homeKnown = true), false, today) as TripsState.Proposals
    assertThat(state.proposals).isEmpty()
    assertThat(state.hasOlder).isTrue()
  }

  private class FakeTrips(initial: TripProposals) : Trips {
    override val proposals = MutableStateFlow(initial)
    val accepted = mutableListOf<TripId>()
    val dismissed = mutableListOf<TripId>()
    val album = AlbumId("00000000-0000-4000-8000-0000000000b1")

    override suspend fun detect(budget: Duration) = dev.pampa.mycloudgallery.core.albums.contract.TripReport(0, 0, true, false)

    override suspend fun accept(id: TripId): TripAcceptOutcome {
      accepted += id
      return if (proposals.value.proposals.any { it.id == id }) TripAcceptOutcome.Created(album) else TripAcceptOutcome.NotFound
    }

    override suspend fun dismiss(id: TripId) {
      dismissed += id
      proposals.value = TripProposals(proposals.value.proposals.filterNot { it.id == id }, proposals.value.homeKnown)
    }
  }

  @Test
  fun creareUnAlbumDaUnaPropostaPortaAllAlbumENonScrartaAltro() = runTest(UnconfinedTestDispatcher()) {
    val trips = FakeTrips(TripProposals(listOf(recent, recentEvent), homeKnown = true))
    val controller = TripsController(trips, FixedAlbumScreensClock(), backgroundScope)
    var result: TripAccepted? = null
    controller.accept(recent.id) { result = it }
    assertThat(result).isEqualTo(TripAccepted.Created(trips.album))
    assertThat(trips.dismissed).isEmpty()
    controller.dismiss(recentEvent.id)
    assertThat((controller.state.value as TripsState.Proposals).proposals).containsExactly(recent)
    assertThat(trips.dismissed).containsExactly(recentEvent.id)
    controller.accept(TripId.of(TripKind.TRIP, LocalDayRange(today, today), listOf(9))) { result = it }
    assertThat(result).isEqualTo(TripAccepted.NotFound)
  }
}

private fun FixedAlbumScreensClock() = dev.pampa.mycloudgallery.feature.albums.contract.FixedAlbumScreensClock(LocalDate.of(2026, 10, 21))
