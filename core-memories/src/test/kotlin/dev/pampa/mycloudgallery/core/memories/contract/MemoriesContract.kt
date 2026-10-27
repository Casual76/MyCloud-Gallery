package dev.pampa.mycloudgallery.core.memories.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClock
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPeople
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryTrips
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMemories
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryMemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.MemoriesRegister
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto del servizio dei ricordi (Fase 9, P0; `docs/fase9-progetto.md` D3, D4, D9-D11, D31, E-MEM1, E-MEM5-E-MEM10, E-MEM12,
 * E-H8, R17, §5.2). Gira sul riferimento (`InMemoryMemories`) e, da P6, sul servizio vero: [memories] lo costruisce dalle stesse parti
 * (Libreria sintetica e visibilita', scelte, scaffale, orologio, viaggi, persone).
 */
abstract class MemoriesContract {

  /** Le parti di un dispositivo finto. */
  class Parts(
    val visible: VisibleMedia,
    val mask: HiddenMask,
    val personal: MemoriesPersonalSettings,
    val store: InMemoryShelfStore,
    val clock: FakeMemoryClock,
    val trips: FakeMemoryTrips,
    val people: FakeMemoryPeople,
  )

  protected abstract fun memories(parts: Parts): Memories

  private inner class Device(val lib: SyntheticMemoryLibrary = SyntheticMemoryLibrary(), register: MemoriesRegister = MemoriesRegister()) {
    val clock = FakeMemoryClock(LocalDate.of(2026, 11, 3))
    val personal = InMemoryMemoriesPersonalSettings(register)
    val store = InMemoryShelfStore()
    val trips = FakeMemoryTrips()
    val people = FakeMemoryPeople(available = true) { ids -> lib.visibility.visible(ids) }
    val service = memories(Parts(lib.visibility, lib.visibility, personal, store, clock, trips, people))

    suspend fun refresh() = service.refresh(RefreshReason.NIGHTLY, 60.seconds)
  }

  /** Quattro "anni fa" del 3 novembre e un ottobre pieno: due ricordi nuovi al massimo, piu' il mese. */
  private fun richLibrary(): SyntheticMemoryLibrary = SyntheticMemoryLibrary().apply {
    batch {
      for (year in listOf(2025, 2024, 2023, 2022)) photos(LocalDate.of(year, 11, 3), 9, place = 3_000_000 + year)
      for (d in 1..15) photos(LocalDate.of(2026, 10, d), 3)
    }
  }

  @Test
  fun unaLibreriaVuotaHaLoScaffaleVuoto() = runTest {
    val device = Device()
    device.refresh()
    assertThat(device.service.shelf.value.items).isEmpty()
    assertThat(device.service.shelf.value.state).isEqualTo(ShelfState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS))
  }

  /** D3: al piu' due ricordi nuovi al giorno piu' il mese; un secondo giro lo stesso giorno non aggiunge niente. */
  @Test
  fun alPiuDueNuoviAlGiornoPiuIlMese() = runTest {
    val device = Device(richLibrary())
    val report = device.refresh()
    val kinds = device.service.shelf.value.items.map { it.kind }
    assertThat(kinds.count { it != MemoryKind.MONTH }).isEqualTo(2)
    assertThat(kinds.count { it == MemoryKind.MONTH }).isEqualTo(1)
    assertThat(report.published).isEqualTo(3)
    assertThat(device.refresh().published).isEqualTo(0)
    // Il piu' recente e il piu' vecchio (D3), con il luogo nel titolo.
    val otd = device.service.shelf.value.items.filter { it.kind == MemoryKind.ON_THIS_DAY }
    assertThat(otd.map { it.title.yearsAgo }).containsExactly(1, 4)
    assertThat(otd.flatMap { it.title.placeIds }).containsExactly(3_002_025, 3_002_022)
    assertThat(device.service.shelf.value.unseen).isEqualTo(3)
  }

  /** D9, E-MEM5, E-MEM6, E-MEM7: dopo la pubblicazione solo togliere; la copertina passa al primo rimasto; sotto 3 il ricordo esce. */
  @Test
  fun unRicordoPubblicatoSiPuoSoloTogliere() = runTest {
    val lib = richLibrary()
    val device = Device(lib)
    device.refresh()
    val card = device.service.shelf.value.items.first { it.title.yearsAgo == 1 }
    val before = device.service.memory(card.id).first()!!
    // Una foto nuova dello stesso giorno, il giorno dopo: il ricordo non la prende (E-MEM7).
    lib.photos(LocalDate.of(2025, 11, 3), 3, start = java.time.LocalTime.of(20, 0))
    device.clock.today = device.clock.today.plusDays(1)
    device.refresh()
    val after = device.store.peek()!!.memories.first { it.id == card.id }
    assertThat(after.members).isEqualTo(before.memory.members)
    // La copertina diventa nascosta (E-MEM5): esce dagli elementi e dalla vista; la copertina passa al primo rimasto.
    lib.hide(before.memory.cover)
    device.service.prune()
    val trimmed = device.store.peek()!!.memories.first { it.id == card.id }
    assertThat(trimmed.members).doesNotContain(before.memory.cover)
    assertThat(trimmed.cover).isEqualTo(trimmed.members.first())
    assertThat(trimmed.title).isEqualTo(before.memory.title)
    // Eliminati finche' ne restano meno di 3 (E-MEM6): il ricordo esce.
    trimmed.members.drop(2).forEach { lib.delete(it) }
    device.service.prune()
    assertThat(device.store.peek()!!.memories.map { it.id }).doesNotContain(card.id)
    assertThat(device.service.memory(card.id).first()).isNull()
  }

  /** D31, E-H8: un media nascosto non compare in nessuna voce, copertina o vista, nemmeno appena nascosto (vista viva). */
  @Test
  fun unNascostoNonCompareMai() = runTest {
    val lib = richLibrary()
    val device = Device(lib)
    device.refresh()
    val card = device.service.shelf.value.items.first()
    val memory = device.service.memory(card.id).first()!!
    val victim = memory.memory.members[1]
    lib.hide(victim)
    // La vista e' viva: niente prune, il nascosto e' gia' fuori.
    val live = device.service.memory(card.id).first()!!
    assertThat(live.view.viewIndexOf(victim)).isEqualTo(-1)
    device.refresh()
    for (item in device.service.shelf.value.items) {
      val view = device.service.memory(item.id).first() ?: continue
      for (id in view.memory.members) assertWithMessage("nascosto in ${item.kind}").that(id).isNotEqualTo(victim)
      assertThat(item.cover).isNotEqualTo(victim)
    }
  }

  /** D10, E-MEM8, E-MEM9: "visto" e "Non mostrare" valgono su tutti i dispositivi; "Annulla" lo riporta. */
  @Test
  fun vistoEScartatoValgonoOvunque() = runTest {
    val register = MemoriesRegister()
    val phone = Device(richLibrary(), register)
    val tablet = Device(richLibrary(), register)
    phone.refresh()
    val card = phone.service.shelf.value.items.first { it.kind == MemoryKind.ON_THIS_DAY }
    phone.service.markSeen(card.id)
    assertThat(phone.service.shelf.value.items.first { it.id == card.id }.isNew).isFalse()
    assertThat(phone.personal.current().seen[card.id.value]).isEqualTo(20261103)
    phone.service.dismiss(card.id)
    assertThat(phone.service.shelf.value.items.map { it.id }).doesNotContain(card.id)
    assertThat(phone.service.memory(card.id).first()).isNull()
    // L'altro dispositivo (stessi id perche' le chiavi sono le stesse) non lo pubblica mai.
    tablet.personal.pull()
    tablet.refresh()
    assertThat(tablet.service.shelf.value.items.map { it.id }).doesNotContain(card.id)
    // "Annulla" lo riporta sul telefono.
    phone.service.dismiss(card.id, dismissed = false)
    assertThat(phone.service.shelf.value.items.map { it.id }).contains(card.id)
  }

  /** D11, E-MEM12: "Mai" per un periodo toglie subito il ricordo di quei giorni. */
  @Test
  fun maiPerUnPeriodoValeSubito() = runTest {
    val device = Device(richLibrary())
    device.refresh()
    val card = device.service.shelf.value.items.first { it.title.yearsAgo == 1 }
    val day = LocalDate.of(2025, 11, 3)
    device.service.setLess(LessTarget.Days(DayRange.of(day)), LessLevel.NEVER)
    assertThat(device.service.shelf.value.items.map { it.id }).doesNotContain(card.id)
    assertThat(device.service.lessChoices.value.map { it.target }).contains(LessTarget.Days(DayRange.of(day)))
  }

  /** D11, E-MEM10, E-MEM11: "Mai" per una persona toglie le sue foto; "Mostra meno" la toglie dalla copertina e toglie i suoi ricordi. */
  @Test
  fun maiEMostraMenoPerUnaPersona() = runTest {
    val lib = richLibrary()
    val device = Device(lib)
    device.refresh()
    val card = device.service.shelf.value.items.first { it.title.yearsAgo == 1 }
    val memory = device.store.peek()!!.memories.first { it.id == card.id }
    val anna = PersonId.fromBytes(ByteArray(16) { 6 })
    device.people.person(anna, "Anna", listOf(memory.cover, memory.members.last()))
    device.service.setLess(LessTarget.Person(anna), LessLevel.LESS)
    val less = device.store.peek()!!.memories.first { it.id == card.id }
    assertThat(less.cover).isNotEqualTo(memory.cover)
    assertThat(less.members).contains(memory.cover)
    device.service.setLess(LessTarget.Person(anna), LessLevel.NEVER)
    val never = device.store.peek()!!.memories.first { it.id == card.id }
    assertThat(never.members).containsNoneOf(memory.cover, memory.members.last())
    assertThat(device.service.lessChoices.value.single().personName).isEqualTo("Anna")
  }

  /** D10: oltre 30 giorni un ricordo esce dallo scaffale. */
  @Test
  fun dopoTrentaGiorniEsce() = runTest {
    val device = Device(richLibrary())
    device.refresh()
    assertThat(device.service.shelf.value.items).isNotEmpty()
    device.clock.today = device.clock.today.plusDays(30)
    device.refresh()
    assertThat(device.store.peek()!!.memories.filter { it.publishedDay == 20261103 }).isEmpty()
  }

  /** D10: uno scaffale rovinato si rigenera (e lo dice); una morte a meta' scrittura lascia lo scaffale di prima. */
  @Test
  fun scaffaleRovinatoEMorteAMetaScrittura() = runTest {
    val device = Device(richLibrary())
    device.store.corrupt()
    assertThat(device.refresh().shelfReset).isTrue()
    assertThat(device.store.peek()!!.memories).isNotEmpty()
    val before = device.store.peek()
    device.store.dieDuringNextWrite()
    device.clock.today = device.clock.today.plusDays(1)
    assertThat(runCatching { device.refresh() }.isFailure).isTrue()
    assertThat(device.store.peek()).isEqualTo(before)
  }

  /** R17: un guasto della lettura dei visibili non svuota lo scaffale. */
  @Test
  fun unGuastoDeiVisibiliNonSvuotaLoScaffale() = runTest {
    val lib = richLibrary()
    val failing = object : VisibleMedia {
      var broken = false
      override val snapshot: StateFlow<TimelineSnapshot?> = lib.visibility.snapshot
      override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = if (broken) emptySet() else lib.visibility.visible(ids)
      override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> {
        check(!broken) { "database guasto" }
        return lib.visibility.visible(ids)
      }
    }
    val parts = Device(lib)
    val service = memories(Parts(failing, lib.visibility, parts.personal, parts.store, parts.clock, parts.trips, parts.people))
    service.refresh(RefreshReason.NIGHTLY, 60.seconds)
    val before = parts.store.peek()
    failing.broken = true
    assertThat(runCatching { service.prune() }.isFailure).isTrue()
    assertThat(parts.store.peek()).isEqualTo(before)
  }

  /** D3, D12, E-MEM13: l'anniversario di un viaggio ha la precedenza, il titolo del viaggio e "Apri l'album". */
  @Test
  fun anniversarioDiUnViaggioConLAlbum() = runTest {
    val lib = richLibrary()
    val device = Device(lib)
    val album = AlbumId.random()
    val from = LocalDate.of(2025, 11, 1)
    val members = (0..3).flatMap { lib.photos(from.plusDays(it.toLong()), 4, start = java.time.LocalTime.of(14, 0)) }
    device.trips.trips = listOf(TripDigestView(TripKind.TRIP, DayRange(from, from.plusDays(3)), "Viaggio sintetico", listOf(3_200_000), members, album, null))
    device.refresh()
    val trip = device.store.peek()!!.memories.single { it.kind == MemoryKind.TRIP }
    assertThat(trip.origin).isEqualTo(MemoryOrigin.TripAlbum(album))
    assertThat(trip.title.tripLabel).isEqualTo("Viaggio sintetico")
    assertThat(trip.title.yearsAgo).isEqualTo(1)
    assertThat(device.service.status.value).isEqualTo(MemoriesStatus.Idle(20261103, device.store.peek()!!.memories.size, device.service.shelf.value.unseen))
  }
}

/** La suite sul riferimento (§5.2). */
class InMemoryMemoriesContractTest : MemoriesContract() {
  override fun memories(parts: Parts): Memories = InMemoryMemories(
    visible = parts.visible,
    mask = parts.mask,
    personal = parts.personal,
    store = parts.store,
    clock = parts.clock,
    trips = parts.trips,
    people = parts.people,
  )
}
