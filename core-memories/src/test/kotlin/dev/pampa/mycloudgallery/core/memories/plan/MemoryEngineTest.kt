package dev.pampa.mycloudgallery.core.memories.plan

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfState
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.TripKind
import java.time.LocalDate
import java.time.LocalTime
import java.util.Random
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Le regole del motore sulle Librerie sintetiche (docs/fase9-progetto.md D3, D9, D10, §2.1, E-MEM1-E-MEM4, E-MEM9, E-MEM18): i quattro tipi con le
 * soglie di D3, il 29 febbraio, i fusi, gli anni senza foto, i giorni di soli screenshot, "al piu' due nuovi al giorno", il generatore
 * **deterministico**, gli id **uguali** su due dispositivi con Librerie diverse, "visto" per 12 mesi e "scartato" per sempre.
 */
class MemoryEngineTest {

  private val rigs = ArrayList<EngineRig>()

  @After
  fun chiudeIDispositivi() {
    rigs.forEach { it.close() }
  }

  private fun rig(shots: List<TestShot>, today: LocalDate = LocalDate.of(2026, 11, 3), trips: dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips = dev.pampa.mycloudgallery.core.memories.noop.NoMemoryTrips): EngineRig =
    EngineRig(visibility = visibilityOver(shots), today = today, trips = trips).also { rigs += it }

  private fun d(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

  /** E-MEM1: una Libreria giovane, con poche foto, non ha ricordi: la sezione dice "I ricordi arrivano quando ci sono abbastanza foto". */
  @Test
  fun unaLibreriaGiovaneNonHaRicordi() = runTest {
    val rig = rig(dayShots(d(2026, 11, 1), 30, series = 1))
    val report = rig.refresh()
    assertThat(report.published).isEqualTo(0)
    assertThat(rig.engine.shelf.value.state).isEqualTo(ShelfState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS))
    assertThat(rig.engine.status.value).isEqualTo(dev.pampa.mycloudgallery.core.memories.contract.MemoriesStatus.Idle(20261103, 0, 0))
  }

  /** E-MEM2, E-MEM18: un giorno di soli screenshot (o di date stimate) non e' un "anni fa"; l'anno con foto vere si'. */
  @Test
  fun ungiornoDiSoliScreenshotNonFaUnAnniFa() = runTest {
    val shots = dayShots(d(2025, 11, 3), 12, series = 1, source = MediaSource.SCREENSHOT) + dayShots(d(2025, 11, 3), 3, series = 2) +
      dayShots(d(2024, 11, 3), 8, series = 3)
    val rig = rig(shots)
    assertThat(rig.refresh().published).isEqualTo(1)
    val memory = rig.engine.current()!!.memories.single()
    assertThat(memory.key).isEqualTo(MemoryKey.OnThisDay(d(2024, 11, 3)))
    assertThat(memory.title.yearsAgo).isEqualTo(2)
    // Nessun elemento e' uno screenshot.
    val screenshots = shots.filter { it.source == MediaSource.SCREENSHOT }.map { it.id }.toSet()
    assertThat(memory.members.toSet().intersect(screenshots)).isEmpty()
  }

  /** E-MEM3: un 29 febbraio esce il 28 negli anni non bisestili e il 29 in quelli bisestili; il 1 marzo niente. */
  @Test
  fun ilVentinoveFebbraio() = runTest {
    val shots = dayShots(d(2024, 2, 29), 8, series = 1)
    for ((today, expectedYears) in listOf(d(2025, 2, 28) to 1, d(2026, 2, 28) to 2, d(2028, 2, 29) to 4)) {
      val rig = rig(shots, today)
      assertThat(rig.refresh().published).isEqualTo(1)
      val memory = rig.engine.current()!!.memories.single()
      assertThat(memory.key).isEqualTo(MemoryKey.OnThisDay(d(2024, 2, 29)))
      assertThat(memory.title.yearsAgo).isEqualTo(expectedYears)
    }
    assertThat(rig(shots, d(2025, 3, 1)).refresh().published).isEqualTo(0)
    assertThat(rig(shots, d(2028, 2, 28)).refresh().published).isEqualTo(0)
  }

  /**
   * E-MEM4: conta il giorno **locale** dello scatto, non il giorno di UTC. Otto scatti del 3 novembre 2025 a UTC+14 cadono, in UTC, il 2 novembre;
   * cinque del 2 novembre a UTC-10 cadono, in UTC, il 3: con i giorni di UTC il 3 novembre non avrebbe abbastanza foto.
   */
  @Test
  fun iFusiOrari() = runTest {
    val east = dayShots(d(2025, 11, 3), 8, series = 1, offsetHours = 14)
    val west = List(5) { k -> TestShot(testId(2, k.toLong()), dayInt(d(2025, 11, 2)), instantOf(d(2025, 11, 2), LocalTime.of(20, 0).plusMinutes(10L * k), -10)) }
    val rig = rig(east + west)
    assertThat(rig.refresh().published).isEqualTo(1)
    assertThat(rig.engine.current()!!.memories.single().members.toSet()).isEqualTo(east.map { it.id }.toSet())
    assertThat(rig(east + west, d(2026, 11, 2)).refresh().published).isEqualTo(0)
  }

  /** Anni senza foto: si propongono il piu' recente e il piu' vecchio fra quelli che si qualificano; un anno solo, un ricordo solo. */
  @Test
  fun anniSenzaFoto() = runTest {
    val shots = dayShots(d(2025, 11, 3), 7, series = 1) + dayShots(d(2022, 11, 3), 7, series = 2) + dayShots(d(2023, 11, 3), 3, series = 3)
    val rig = rig(shots)
    assertThat(rig.refresh().published).isEqualTo(2)
    assertThat(rig.engine.current()!!.memories.mapNotNull { it.title.yearsAgo }).containsExactly(1, 4)
    assertThat(rig(dayShots(d(2022, 11, 3), 7, series = 2)).also { it.refresh() }.engine.current()!!.memories).hasSize(1)
  }

  /** D3: al piu' due ricordi nuovi al giorno; foto che arrivano dopo non ne fanno uscire altri nello stesso giorno, ne' entrano in quelli pubblicati. */
  @Test
  fun alPiuDueNuoviAlGiorno() = runTest {
    val years = listOf(2025, 2024, 2023, 2022)
    val shots = years.flatMapIndexed { n, year -> dayShots(d(year, 11, 3), 8, series = 10L + n) }
    val rig = rig(shots)
    assertThat(rig.refresh().published).isEqualTo(2)
    val first = rig.engine.current()!!
    val arrived = dayShots(d(2021, 11, 3), 9, series = 99) + dayShots(d(2025, 11, 3), 4, series = 98)
    rig.visibility.present += arrived.map { it.id }
    rig.visibility.snapshot.value = snapshotOf(shots + arrived, seq = 2)
    assertThat(rig.refresh().published).isEqualTo(0)
    assertThat(rig.engine.current()!!.memories).isEqualTo(first.memories)
    // Il giorno dopo non ci sono anniversari: niente di nuovo; il ricordo dello stesso giorno dell'anno dopo li prenderebbe (E-MEM7).
    rig.clock.today = d(2026, 11, 4)
    assertThat(rig.refresh().published).isEqualTo(0)
  }

  /** Il generatore e' deterministico: stessa Libreria e stesso giorno = stessi ricordi, stessi elementi, stesso ordine, anche con gli scatti in un altro ordine. */
  @Test
  fun stessaLibreriaStessoGiornoStessiRicordi() = runTest {
    val years = listOf(2025, 2024, 2023, 2022)
    val shots = years.flatMapIndexed { n, year -> dayShots(d(year, 11, 3), 9, series = 20L + n) } +
      (1..15).flatMap { day -> dayShots(d(2026, 10, day), 3, series = 100L + day) }
    val first = rig(shots).also { it.refresh() }
    val shuffled = rig(shots.shuffled(Random(5))).also { it.refresh() }
    val again = rig(shots).also { it.refresh() }
    assertThat(first.engine.current()!!.memories).isNotEmpty()
    assertThat(shuffled.engine.current()).isEqualTo(first.engine.current())
    assertThat(again.engine.current()).isEqualTo(first.engine.current())
    // E un secondo giro lo stesso giorno non cambia niente.
    val before = first.engine.current()
    first.refresh()
    assertThat(first.engine.current()).isEqualTo(before)
  }

  /** D9, E-MEM8: lo stesso ricordo ha lo stesso id su due dispositivi che hanno Librerie diverse (altri id di media, altri elementi). */
  @Test
  fun idUgualiSuDueDispositiviConLibrerieDiverse() = runTest {
    val phone = rig(dayShots(d(2025, 11, 3), 9, series = 1) + dayShots(d(2024, 11, 3), 9, series = 2))
    val tablet = rig(dayShots(d(2025, 11, 3), 7, series = 3) + dayShots(d(2023, 11, 3), 8, series = 4))
    phone.refresh()
    tablet.refresh()
    val phoneRecent = phone.engine.current()!!.memories.first { it.title.yearsAgo == 1 }
    val tabletRecent = tablet.engine.current()!!.memories.first { it.title.yearsAgo == 1 }
    assertThat(phoneRecent.id).isEqualTo(tabletRecent.id)
    assertThat(phoneRecent.members).isNotEqualTo(tabletRecent.members)
    assertThat(phoneRecent.members.toSet().intersect(tabletRecent.members.toSet())).isEmpty()
    // Gli altri due ricordi sono di anni diversi: id diversi.
    val ids = (phone.engine.current()!!.memories + tablet.engine.current()!!.memories).map { it.id }.toSet()
    assertThat(ids).hasSize(3)
  }

  /** D4 (c): all'apertura di Raccolte il giro si rifa' solo se il giorno e' cambiato; di notte e al passo leggero si rifa' sempre. */
  @Test
  fun aperturaSoloSeIlGiornoECambiato() = runTest {
    val store = InMemoryShelfStore()
    val shots = dayShots(d(2025, 11, 3), 8, series = 1) + dayShots(d(2024, 11, 3), 8, series = 2)
    val rig = EngineRig(visibility = visibilityOver(shots), store = store).also { rigs += it }
    assertThat(rig.refresh(reason = RefreshReason.OPEN).published).isEqualTo(2)
    val writes = store.writes
    assertThat(rig.refresh(reason = RefreshReason.OPEN)).isEqualTo(dev.pampa.mycloudgallery.core.memories.contract.RefreshReport.NOTHING)
    assertThat(store.writes).isEqualTo(writes)
    rig.refresh(reason = RefreshReason.LIGHT)
    assertThat(store.writes).isEqualTo(writes + 1)
    rig.clock.today = d(2026, 11, 4)
    rig.refresh(reason = RefreshReason.OPEN)
    assertThat(store.writes).isEqualTo(writes + 2)
  }

  private fun trip(series: Long, from: LocalDate, days: Int): Pair<List<TestShot>, TripDigestView> {
    val shots = (0 until days).flatMap { dayShots(from.plusDays(it.toLong()), 4, series = series * 10 + it) }
    val digest = TripDigestView(TripKind.TRIP, DayRange(from, from.plusDays(days - 1L)), "Viaggio sintetico", listOf(3_200_000), shots.map { it.id }, null, null)
    return shots to digest
  }

  /** D9, E-MEM9: un ricordo visto non si rigenera per 12 mesi (l'anniversario dopo 364 giorni no, dopo 365 si); uno scartato non torna mai. */
  @Test
  fun vistoPerDodiciMesiEScartatoPerSempre() = runTest {
    val (shots, digest) = trip(series = 5, from = d(2025, 11, 1), days = 3)
    val trips = fakeTrips(digest)
    val rig = rig(shots, today = d(2026, 11, 3), trips = trips)
    assertThat(rig.refresh().published).isEqualTo(1)
    val memory = rig.engine.current()!!.memories.single()
    assertThat(memory.kind).isEqualTo(MemoryKind.TRIP)
    assertThat(memory.title.yearsAgo).isEqualTo(1)
    rig.engine.markSeen(memory.id)
    rig.clock.today = d(2027, 11, 2)
    assertThat(rig.refresh().published).isEqualTo(0)
    rig.clock.today = d(2027, 11, 3)
    assertThat(rig.refresh().published).isEqualTo(1)
    val again = rig.engine.current()!!.memories.single { it.title.yearsAgo == 2 }
    assertThat(again.id).isEqualTo(memory.id)
    // Scartato: mai piu', neanche fra tre anni.
    rig.engine.dismiss(again.id)
    rig.clock.today = d(2028, 11, 3)
    rig.refresh()
    rig.clock.today = d(2029, 11, 3)
    assertWithMessage("un ricordo scartato non torna").that(rig.refresh().published).isEqualTo(0)
  }

  /** D10: un ricordo esce dallo scaffale dopo 30 giorni ma, se e' stato visto, non si rigenera per 12 mesi anche se il suo giorno ritorna. */
  @Test
  fun unRicordoVistoUscitoDalloScaffaleNonRitorna() = runTest {
    val (shots, digest) = trip(series = 6, from = d(2025, 11, 1), days = 3)
    val rig = rig(shots, trips = fakeTrips(digest))
    rig.refresh()
    val id = rig.engine.current()!!.memories.single().id
    rig.engine.markSeen(id)
    rig.clock.today = d(2026, 12, 10)
    rig.refresh()
    assertThat(rig.engine.current()!!.memories).isEmpty()
    // Il giorno dell'anniversario e' passato: nessun candidato; e se tornasse nella finestra, "visto" lo terrebbe fuori (MemoryQuiet).
    assertThat(rig.engine.shelf.value.state).isEqualTo(ShelfState.Empty(ShelfEmptyReason.NOT_ENOUGH_PHOTOS))
  }
}
