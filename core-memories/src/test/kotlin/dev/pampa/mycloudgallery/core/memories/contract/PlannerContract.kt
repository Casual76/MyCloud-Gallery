package dev.pampa.mycloudgallery.core.memories.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePlanner
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.TripKind
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Test

/**
 * La suite di contratto del generatore (Fase 9, P0; `docs/fase9-progetto.md` D3, D5, D9, D11, E-MEM1-E-MEM3, E-MEM12, E-MEM13, E-MEM18, §5.2).
 * Gira sul riferimento a forza bruta (`ReferencePlanner`) e, da P6, sul generatore vero di plan/. Le Librerie sono sintetiche e piccole: ogni
 * prova mette a mano i giorni che le servono.
 */
abstract class PlannerContract {

  protected abstract fun planner(): MemoryPlanner

  private val today = LocalDate.of(2026, 10, 27)

  private fun input(
    lib: SyntheticMemoryLibrary,
    trips: List<TripDigestView> = emptyList(),
    people: List<PersonDigest> = emptyList(),
    peopleAvailable: Boolean = false,
    choices: MemoriesChoices = MemoriesChoices.NONE,
    history: PlanHistory = PlanHistory(),
  ) = PlanInput(CellTable.of(lib.snapshot()), trips, people, peopleAvailable, choices, history)

  private fun plan(input: PlanInput, day: LocalDate = today) = planner().plan(input, day)

  private fun otdKeys(candidates: List<MemoryCandidate>) =
    candidates.filter { it.key is MemoryKey.OnThisDay }.map { (it.key as MemoryKey.OnThisDay).day }

  private fun trip(lib: SyntheticMemoryLibrary, from: LocalDate, days: Int, perDay: Int, kind: TripKind = TripKind.TRIP, album: AlbumId? = null): TripDigestView {
    val members = (0 until days).flatMap { lib.photos(from.plusDays(it.toLong()), perDay, place = 3_100_000) }
    val to = from.plusDays(days - 1L)
    return TripDigestView(kind, DayRange(from, to), "Viaggio sintetico", listOf(3_100_000), members, album, if (kind == TripKind.EVENT) 40 else null)
  }

  /** E-MEM1: una Libreria vuota o giovane non ha ricordi. */
  @Test
  fun unaLibreriaVuotaNonHaRicordi() {
    assertThat(plan(input(SyntheticMemoryLibrary()))).isEmpty()
    val young = SyntheticMemoryLibrary().apply { photos(today.minusDays(3), 30) }
    assertThat(plan(input(young))).isEmpty()
  }

  /** D3: "anni fa" per ogni anno con almeno 6 candidati buoni; al piu' due, il piu' recente e il piu' vecchio. */
  @Test
  fun anniFaIlPiuRecenteEIlPiuVecchioCheSiQualificano() {
    val lib = SyntheticMemoryLibrary()
    lib.photos(LocalDate.of(2025, 10, 27), 8)
    lib.photos(LocalDate.of(2023, 10, 27), 7)
    lib.photos(LocalDate.of(2021, 10, 27), 6)
    lib.photos(LocalDate.of(2024, 10, 27), 5)
    val candidates = plan(input(lib))
    assertThat(otdKeys(candidates)).containsExactly(LocalDate.of(2025, 10, 27), LocalDate.of(2021, 10, 27))
    val recent = candidates.first { it.key == MemoryKey.OnThisDay(LocalDate.of(2025, 10, 27)) }
    assertThat(recent.priority).isEqualTo(MemoryPriority.OTD_RECENT)
    assertThat(recent.yearsAgo).isEqualTo(1)
    assertThat(recent.cells).hasLength(8)
    assertThat(candidates.first { it.key == MemoryKey.OnThisDay(LocalDate.of(2021, 10, 27)) }.priority).isEqualTo(MemoryPriority.OTD_OLDEST)
  }

  /** E-MEM2, E-MEM18, D5: screenshot, documenti, WhatsApp-no (penalizzato, non escluso), date stimate, ritagli e animate non contano. */
  @Test
  fun leEsclusioniDiD5NonContanoPerLaSoglia() {
    val lib = SyntheticMemoryLibrary()
    val day = LocalDate.of(2025, 10, 27)
    lib.photos(day, 10, source = MediaSource.SCREENSHOT)
    lib.photos(day, 3, flags = CellFlags.DOCUMENT)
    lib.photos(day, 4, flags = CellFlags.DATE_ESTIMATED)
    lib.photos(day, 5)
    assertThat(otdKeys(plan(input(lib)))).isEmpty()
    lib.photos(day, 1, source = MediaSource.WHATSAPP)
    assertThat(otdKeys(plan(input(lib)))).containsExactly(day)
    assertThat(plan(input(lib)).first().cells).hasLength(6)
  }

  /** E-MEM3: il 29 febbraio esce il 28 negli anni non bisestili, il 29 in quelli bisestili. */
  @Test
  fun il29FebbraioEsceIl28NegliAnniNonBisestili() {
    val lib = SyntheticMemoryLibrary()
    val leapDay = LocalDate.of(2024, 2, 29)
    lib.photos(leapDay, 8)
    lib.photos(LocalDate.of(2024, 2, 28), 8)
    assertThat(otdKeys(plan(input(lib), LocalDate.of(2025, 2, 28)))).containsExactly(LocalDate.of(2024, 2, 28), leapDay)
    assertThat(otdKeys(plan(input(lib), LocalDate.of(2026, 2, 28)))).contains(leapDay)
    assertThat(otdKeys(plan(input(lib), LocalDate.of(2028, 2, 28)))).containsExactly(LocalDate.of(2024, 2, 28))
    assertThat(otdKeys(plan(input(lib), LocalDate.of(2028, 2, 29)))).containsExactly(leapDay)
    assertThat(plan(input(lib), LocalDate.of(2026, 3, 1)).filter { it.key is MemoryKey.OnThisDay }).isEmpty()
  }

  /** D9: gia' sullo scaffale, scartato, visto negli ultimi 12 mesi: non si propone; visto da piu' di un anno si'. */
  @Test
  fun visteScartateEGiaPubblicateNonTornano() {
    val lib = SyntheticMemoryLibrary()
    val day = LocalDate.of(2025, 10, 27)
    lib.photos(day, 8)
    val id = MemoryIds.of(MemoryKey.OnThisDay(day)).value
    assertThat(otdKeys(plan(input(lib, choices = MemoriesChoices(seen = mapOf(id to 20260301)))))).isEmpty()
    assertThat(otdKeys(plan(input(lib, choices = MemoriesChoices(seen = mapOf(id to 20250901)))))).containsExactly(day)
    assertThat(otdKeys(plan(input(lib, choices = MemoriesChoices(dismissed = setOf(id)))))).isEmpty()
    assertThat(otdKeys(plan(input(lib, history = PlanHistory(onShelf = setOf(MemoryId(id))))))).isEmpty()
  }

  /** D11, E-MEM12: un periodo "Mai" toglie le foto; un viaggio che lo tocca anche per un giorno non e' un ricordo. */
  @Test
  fun unPeriodoMaiToglieIGiorniEIViaggiCheLoToccano() {
    val lib = SyntheticMemoryLibrary()
    val day = LocalDate.of(2025, 10, 27)
    lib.photos(day, 8)
    val t = trip(lib, LocalDate.of(2025, 10, 25), 4, 4)
    val never = MemoriesChoices(neverDays = setOf(MemoryDays.of(LocalDate.of(2025, 10, 28), LocalDate.of(2025, 11, 2))))
    val withNever = plan(input(lib, trips = listOf(t), choices = never))
    assertThat(withNever.map { it.key }).doesNotContain(t.key())
    assertThat(otdKeys(withNever)).containsExactly(day)
    val dayNever = MemoriesChoices(neverDays = setOf(MemoryDays.of(day, day)))
    assertThat(otdKeys(plan(input(lib, choices = dayNever)))).isEmpty()
  }

  /** D3: anniversario di un viaggio a +-3 giorni (priorita' piu' alta, con l'album se c'e'); a 7 giorni no. */
  @Test
  fun anniversarioDiUnViaggio() {
    val lib = SyntheticMemoryLibrary()
    val album = AlbumId.random()
    val near = trip(lib, LocalDate.of(2025, 10, 25), 4, 4, album = album)
    val far = trip(lib, LocalDate.of(2026, 1, 10), 3, 5)
    val candidates = plan(input(lib, trips = listOf(near, far)))
    val anniversary = candidates.single { it.key == near.key() }
    assertThat(anniversary.priority).isEqualTo(MemoryPriority.ANNIVERSARY)
    assertThat(anniversary.yearsAgo).isEqualTo(1)
    assertThat(anniversary.origin).isEqualTo(MemoryOrigin.TripAlbum(album))
    assertThat(anniversary.tripLabel).isEqualTo("Viaggio sintetico")
    assertThat(candidates.map { it.key }).doesNotContain(far.key())
    assertThat(candidates.first().key).isEqualTo(near.key())
  }

  /** Un evento: anniversario con la chiave del giorno e del quarto d'ora d'inizio (D9). */
  @Test
  fun anniversarioDiUnEvento() {
    val lib = SyntheticMemoryLibrary()
    val event = trip(lib, LocalDate.of(2024, 10, 28), 1, 12, kind = TripKind.EVENT)
    val candidates = plan(input(lib, trips = listOf(event)))
    val c = candidates.single { it.key.kind == MemoryKind.EVENT }
    assertThat(c.key).isEqualTo(MemoryKey.Event(LocalDate.of(2024, 10, 28), 40))
    assertThat(c.yearsAgo).isEqualTo(2)
  }

  /** D3: riscoperta di un viaggio di piu' di un anno fa, il piu' lungo, al piu' una a settimana. */
  @Test
  fun riscopertaDelViaggioPiuLungoUnaASettimana() {
    val lib = SyntheticMemoryLibrary()
    val short = trip(lib, LocalDate.of(2023, 5, 1), 3, 5)
    val long = trip(lib, LocalDate.of(2022, 7, 10), 6, 3)
    val recent = trip(lib, LocalDate.of(2026, 5, 1), 5, 4)
    val candidates = plan(input(lib, trips = listOf(short, long, recent)))
    val found = candidates.filter { it.rediscovery }
    assertThat(found.map { it.key }).containsExactly(long.key())
    assertThat(found.single().priority).isEqualTo(MemoryPriority.REDISCOVERY)
    assertThat(found.single().yearsAgo).isNull()
    val lastWeek = PlanHistory(lastRediscovery = MemoryDays.dayOf(today.minusDays(3)))
    assertThat(plan(input(lib, trips = listOf(short, long), history = lastWeek)).filter { it.rediscovery }).isEmpty()
  }

  /** D3, D11, Q9: una persona con nome, 20 foto in 3 giorni, un anno con 15; mai con "Mostra meno", Q9 spento o Persone non disponibile. */
  @Test
  fun unaPersonaConNomeEAbbastanzaFoto() {
    val lib = SyntheticMemoryLibrary()
    val anna = PersonId.fromBytes(ByteArray(16) { (it + 1).toByte() })
    val media = (1..4).flatMap { lib.photos(LocalDate.of(2025, 3, it * 5), 6) }
    val digest = PersonDigest(anna, media)
    val candidates = plan(input(lib, people = listOf(digest), peopleAvailable = true))
    val c = candidates.single { it.key.kind == MemoryKind.PERSON }
    assertThat(c.key).isEqualTo(MemoryKey.Person(anna, PersonWindow.Year(2025)))
    assertThat(c.person).isEqualTo(anna)
    assertThat(c.cells).hasLength(24)
    val less = MemoriesChoices(people = mapOf(anna to MemoryLessLevel.LESS))
    assertThat(plan(input(lib, people = listOf(digest), peopleAvailable = true, choices = less)).filter { it.key.kind == MemoryKind.PERSON }).isEmpty()
    val off = MemoriesChoices(peopleMemories = false)
    assertThat(plan(input(lib, people = listOf(digest), peopleAvailable = true, choices = off)).filter { it.key.kind == MemoryKind.PERSON }).isEmpty()
    assertThat(plan(input(lib, people = listOf(digest), peopleAvailable = false)).filter { it.key.kind == MemoryKind.PERSON }).isEmpty()
    val lastWeek = PlanHistory(lastPublished = mapOf(MemoryKind.PERSON to MemoryDays.dayOf(today.minusDays(2))))
    assertThat(plan(input(lib, people = listOf(digest), peopleAvailable = true, history = lastWeek)).filter { it.key.kind == MemoryKind.PERSON }).isEmpty()
    val twoDays = PersonDigest(anna, media.take(12) + lib.photos(LocalDate.of(2025, 3, 5), 10))
    assertThat(plan(input(lib, people = listOf(twoDays), peopleAvailable = true)).filter { it.key.kind == MemoryKind.PERSON }).isEmpty()
  }

  /** D3: il meglio del mese dal 1° al 7, per il mese prima, con almeno 40 candidati. */
  @Test
  fun ilMeglioDelMese() {
    val lib = SyntheticMemoryLibrary()
    lib.batch { (1..15).forEach { lib.photos(LocalDate.of(2026, 10, it), 3) } }
    val november = LocalDate.of(2026, 11, 3)
    val c = plan(input(lib), november).single { it.key.kind == MemoryKind.MONTH }
    assertThat(c.key).isEqualTo(MemoryKey.Month(YearMonth.of(2026, 10)))
    assertThat(c.priority).isEqualTo(MemoryPriority.MONTH)
    assertThat(c.cells).hasLength(45)
    assertThat(plan(input(lib), LocalDate.of(2026, 11, 8)).filter { it.key.kind == MemoryKind.MONTH }).isEmpty()
    val few = SyntheticMemoryLibrary().apply { photos(LocalDate.of(2026, 10, 3), 30) }
    assertThat(plan(input(few), november).filter { it.key.kind == MemoryKind.MONTH }).isEmpty()
  }

  /** Puro e deterministico: stessa Libreria, stesse scelte, stesso giorno = stessi candidati, nello stesso ordine (priorita', chiave). */
  @Test
  fun deterministicoEOrdinato() {
    val lib = SyntheticMemoryLibrary()
    lib.photos(LocalDate.of(2025, 10, 27), 8)
    lib.photos(LocalDate.of(2020, 10, 27), 8)
    val t = trip(lib, LocalDate.of(2024, 10, 26), 3, 5)
    val a = plan(input(lib, trips = listOf(t)))
    val b = plan(input(lib, trips = listOf(t)))
    assertThat(a.map { it.key }).isEqualTo(b.map { it.key })
    assertThat(a.map { it.cells.toList() }).isEqualTo(b.map { it.cells.toList() })
    assertThat(a.map { it.priority }).isInOrder()
    for (c in a) assertWithMessage("$c").that(c.toString()).doesNotContain("2025")
  }

  /** I candidati non portano celle escluse, ne' fuori dalla tabella. */
  @Test
  fun leCelleDeiCandidatiSonoTutteBuone() {
    val lib = SyntheticMemoryLibrary()
    val day = LocalDate.of(2025, 10, 27)
    lib.photos(day, 8)
    lib.photos(day, 4, source = MediaSource.SCREENSHOT, start = LocalTime.of(18, 0))
    val table = CellTable.of(lib.snapshot())
    for (c in plan(input(lib))) for (i in c.cells) {
      assertThat(i).isIn(0 until table.size)
      assertThat(MemoryExclusions.of(table, i, MemoryRules.spec(c.key.kind).dateBound, MemoriesChoices.NONE)).isNull()
    }
  }
}

/** La suite sul riferimento (§5.2). */
class ReferencePlannerContractTest : PlannerContract() {
  override fun planner(): MemoryPlanner = ReferencePlanner()
}
