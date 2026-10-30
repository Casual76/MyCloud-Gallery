package dev.pampa.mycloudgallery.core.memories.plan.people

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.memories.contract.CellTable
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCandidate
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPriority
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.memories.contract.PersonWindow
import dev.pampa.mycloudgallery.core.memories.contract.PlanHistory
import dev.pampa.mycloudgallery.core.memories.contract.PlanInput
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticMemoryLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import java.time.LocalDate
import java.util.Random
import org.junit.Test

/**
 * Le regole del generatore delle persone una per una (docs/fase9-progetto.md §2.1 "Persona", D3, D5, D9, D11, D12, D31, E-MEM10, E-MEM11, Q9 (a), P8).
 * Librerie sintetiche piccole, date relative a "oggi"; i nomi non sono mai un ingresso del generatore (la porta passa solo gli id).
 */
class PeoplePlannerTest {

  private val planner = PeoplePlanner()
  private val today = LocalDate.of(2026, 11, 3)
  private val anna = PersonId.fromBytes(ByteArray(16) { 1 })
  private val marco = PersonId.fromBytes(ByteArray(16) { 2 })

  private fun input(
    lib: SyntheticMemoryLibrary,
    people: List<PersonDigest>,
    available: Boolean = true,
    choices: MemoriesChoices = MemoriesChoices.NONE,
    history: PlanHistory = PlanHistory(),
  ) = PlanInput(CellTable.of(lib.snapshot()), emptyList(), people, available, choices, history)

  private fun plan(input: PlanInput, day: LocalDate = today): List<MemoryCandidate> = planner.plan(input, day)

  /** [days] giorni di 2025 (uno ogni quattro giorni di marzo), con [perDay] foto l'uno. */
  private fun photosIn2025(lib: SyntheticMemoryLibrary, days: Int, perDay: Int): List<MediaId> =
    (1..days).flatMap { lib.photos(LocalDate.of(2025, 3, it * 4), perDay) }

  private fun yearKey(person: PersonId, year: Int) = MemoryKey.Person(person, PersonWindow.Year(year))

  /** D3: almeno 20 foto in almeno 3 giorni; un anno passato con almeno 15 foto. */
  @Test
  fun leSoglieDiD3() {
    val lib = SyntheticMemoryLibrary()
    val nineteen = PersonDigest(anna, photosIn2025(lib, 3, 7).take(19))
    assertThat(plan(input(lib, listOf(nineteen)))).isEmpty()
    val twoDays = PersonDigest(anna, photosIn2025(lib, 2, 12))
    assertThat(plan(input(lib, listOf(twoDays)))).isEmpty()
    val enough = PersonDigest(anna, photosIn2025(lib, 3, 7))
    val c = plan(input(lib, listOf(enough))).single()
    assertThat(c.key).isEqualTo(yearKey(anna, 2025))
    assertThat(c.priority).isEqualTo(MemoryPriority.PERSON)
    assertThat(c.person).isEqualTo(anna)
    assertThat(c.cells).hasLength(21)
    assertThat(c.yearsAgo).isNull()
    // Venti foto in tre giorni ma nessun anno con quindici (e niente di recente): niente.
    val scattered = SyntheticMemoryLibrary()
    val spread = PersonDigest(
      anna,
      scattered.photos(LocalDate.of(2024, 5, 1), 7) + scattered.photos(LocalDate.of(2025, 5, 1), 7) + scattered.photos(LocalDate.of(2023, 5, 1), 7),
    )
    assertThat(plan(input(scattered, listOf(spread)))).isEmpty()
  }

  /** Una persona alla volta: la piu' fotografata; a parita' l'id. Non dipende dall'ordine in cui arrivano. */
  @Test
  fun unaSolaPersonaPerGiroLaPiuFotografata() {
    val lib = SyntheticMemoryLibrary()
    val small = PersonDigest(anna, photosIn2025(lib, 3, 7))
    val big = PersonDigest(marco, (1..6).flatMap { lib.photos(LocalDate.of(2024, 6, it * 3), 8) })
    for (order in listOf(listOf(small, big), listOf(big, small))) {
      val c = plan(input(lib, order)).single()
      assertThat(c.person).isEqualTo(marco)
      assertThat(c.key).isEqualTo(yearKey(marco, 2024))
    }
    // Se la piu' fotografata non ha una finestra, passa alla successiva.
    val none = PersonDigest(marco, (1..6).flatMap { lib.photos(LocalDate.of(2020 + it, 2, 3), 5) })
    assertThat(plan(input(lib, listOf(none, small))).single().person).isEqualTo(anna)
  }

  /** D9: un anno gia' sullo scaffale, scartato o visto da meno di 12 mesi non si ripropone: si passa all'anno prima; visto da piu' di un anno si'. */
  @Test
  fun leChiaviQuietePassanoAllAnnoPrima() {
    val lib = SyntheticMemoryLibrary()
    val media = (1..4).flatMap { lib.photos(LocalDate.of(2025, 3, it * 4), 5) } + (1..4).flatMap { lib.photos(LocalDate.of(2024, 3, it * 4), 5) }
    val digest = PersonDigest(anna, media)
    val id2025 = MemoryIds.of(yearKey(anna, 2025))
    fun keyOf(choices: MemoriesChoices = MemoriesChoices.NONE, history: PlanHistory = PlanHistory()) =
      plan(input(lib, listOf(digest), choices = choices, history = history)).singleOrNull()?.key
    assertThat(keyOf()).isEqualTo(yearKey(anna, 2025))
    assertThat(keyOf(history = PlanHistory(onShelf = setOf(id2025)))).isEqualTo(yearKey(anna, 2024))
    assertThat(keyOf(MemoriesChoices(dismissed = setOf(id2025.value)))).isEqualTo(yearKey(anna, 2024))
    assertThat(keyOf(MemoriesChoices(seen = mapOf(id2025.value to MemoryDays.dayOf(today.minusMonths(3)))))).isEqualTo(yearKey(anna, 2024))
    assertThat(keyOf(MemoriesChoices(seen = mapOf(id2025.value to MemoryDays.dayOf(today.minusDays(400)))))).isEqualTo(yearKey(anna, 2025))
    // Tutti e due gli anni quieti, e niente di recente: niente.
    val both = MemoriesChoices(dismissed = setOf(id2025.value, MemoryIds.of(yearKey(anna, 2024)).value))
    assertThat(keyOf(both)).isNull()
  }

  /** "Negli ultimi mesi": gli ultimi 182 giorni fino a ieri, quando nessun anno passato ha abbastanza foto; la chiave `recenti`. */
  @Test
  fun negliUltimiMesi() {
    val lib = SyntheticMemoryLibrary()
    val media = (1..4).flatMap { lib.photos(today.minusDays(it * 20L), 5) }
    val c = plan(input(lib, listOf(PersonDigest(anna, media)))).single()
    assertThat(c.key).isEqualTo(MemoryKey.Person(anna, PersonWindow.Recent))
    assertThat(c.key.text).endsWith(":recenti")
    assertThat(c.days.from).isEqualTo(today.minusDays(182))
    assertThat(c.days.to).isEqualTo(today.minusDays(1))
    assertThat(c.cells).hasLength(20)
    // Le foto di oggi non contano (la finestra arriva a ieri); piu' vecchie di 182 giorni nemmeno.
    val edge = SyntheticMemoryLibrary()
    val old = (1..4).flatMap { edge.photos(today.minusDays(183L + it), 5) } + edge.photos(today, 6)
    assertThat(plan(input(edge, listOf(PersonDigest(anna, old))))).isEmpty()
    val quiet = PlanHistory(onShelf = setOf(MemoryIds.of(MemoryKey.Person(anna, PersonWindow.Recent))))
    assertThat(plan(input(lib, listOf(PersonDigest(anna, media)), history = quiet))).isEmpty()
  }

  /** D11, Q9: "Mostra meno" e "Mai" tolgono i ricordi della persona; "Ricordi con le persone" spento o Persone non disponibile: nessun ricordo. */
  @Test
  fun mostraMenoMaiEQ9() {
    val lib = SyntheticMemoryLibrary()
    val a = PersonDigest(anna, photosIn2025(lib, 3, 8))
    val m = PersonDigest(marco, (1..4).flatMap { lib.photos(LocalDate.of(2023, 7, it * 3), 6) })
    val both = listOf(a, m)
    assertThat(plan(input(lib, both)).single().person).isEqualTo(anna)
    for (level in MemoryLessLevel.entries) {
      val choices = MemoriesChoices(people = mapOf(anna to level))
      assertWithMessage("$level").that(plan(input(lib, both, choices = choices)).single().person).isEqualTo(marco)
      val tooFew = MemoriesChoices(people = mapOf(anna to level, marco to level))
      assertThat(plan(input(lib, both, choices = tooFew))).isEmpty()
    }
    assertThat(plan(input(lib, both, choices = MemoriesChoices(peopleMemories = false)))).isEmpty()
    assertThat(plan(input(lib, both, available = false))).isEmpty()
    assertThat(plan(input(lib, emptyList()))).isEmpty()
  }

  /** Al piu' una a settimana: dopo 6 giorni no, dopo 7 si'. */
  @Test
  fun alPiuUnaASettimana() {
    val lib = SyntheticMemoryLibrary()
    val digest = PersonDigest(anna, photosIn2025(lib, 3, 8))
    fun after(days: Long) = plan(input(lib, listOf(digest), history = PlanHistory(lastPublished = mapOf(MemoryKind.PERSON to MemoryDays.dayOf(today.minusDays(days))))))
    assertThat(after(6)).isEmpty()
    assertThat(after(7)).hasSize(1)
    assertThat(after(0)).isEmpty()
  }

  /** D5 e D11 (periodi): screenshot, documenti e giorni "Mai" non contano per le soglie e non sono celle del ricordo. */
  @Test
  fun leEsclusioniNonContano() {
    val lib = SyntheticMemoryLibrary()
    val good = photosIn2025(lib, 3, 5)
    val shots = (1..3).flatMap { lib.photos(LocalDate.of(2025, 3, it * 4), 4, source = MediaSource.SCREENSHOT, start = java.time.LocalTime.of(20, 0)) }
    val docs = (1..3).flatMap { lib.photos(LocalDate.of(2025, 3, it * 4), 3, flags = CellFlags.DOCUMENT, start = java.time.LocalTime.of(21, 0)) }
    val digest = PersonDigest(anna, good + shots + docs)
    assertThat(plan(input(lib, listOf(digest)))).isEmpty()
    val more = lib.photos(LocalDate.of(2025, 3, 20), 6)
    val c = plan(input(lib, listOf(PersonDigest(anna, good + shots + docs + more)))).single()
    assertThat(c.cells).hasLength(21)
    // Un periodo "Mai" su un giorno toglie le sue foto: sotto la soglia di tre giorni o delle venti foto.
    val never = MemoriesChoices(neverDays = setOf(MemoryDays.of(LocalDate.of(2025, 3, 20), LocalDate.of(2025, 3, 20))))
    assertThat(plan(input(lib, listOf(PersonDigest(anna, good + more)), choices = never))).isEmpty()
  }

  /** D31, E-H10: un media nascosto non e' mai una cella di un ricordo di persona, anche se la porta lo elenca ancora. */
  @Test
  fun unNascostoNonEMaiUnaCella() {
    var withCandidate = 0
    for (seed in 1L..60L) {
      val random = Random(seed)
      val lib = SyntheticMemoryLibrary(seed)
      val media = (1..8).flatMap { lib.photos(LocalDate.of(2025, 2 + random.nextInt(8), 1 + it * 3), 4 + random.nextInt(4)) }
      val hidden = media.filter { random.nextInt(100) < 25 }.toSet()
      hidden.forEach { lib.hide(it) }
      val table = CellTable.of(lib.snapshot())
      val candidates = plan(PlanInput(table, emptyList(), listOf(PersonDigest(anna, media)), true, MemoriesChoices.NONE, PlanHistory()))
      for (c in candidates) for (i in c.cells) assertWithMessage("seed $seed").that(table.idAt(i)).isNotIn(hidden)
      if (candidates.isNotEmpty()) withCandidate++
    }
    assertThat(withCandidate).isAtLeast(20)
  }

  /** Mai un nome: il generatore non ne riceve e il candidato porta solo l'id (e nessuno dei suoi `toString()` lo mostra). */
  @Test
  fun nessunNomeInUscita() {
    val lib = SyntheticMemoryLibrary()
    val digest = PersonDigest(anna, photosIn2025(lib, 3, 8))
    val c = plan(input(lib, listOf(digest))).single()
    for (text in listOf(c.toString(), c.key.toString(), c.key.text, digest.toString(), c.id.toString(), c.id.value, planner.toString())) {
      assertThat(text).doesNotContain("Anna")
      assertThat(text).doesNotContain(anna.value)
    }
    // Nel testo della chiave c'e' l'id in base32, non l'uuid ne' un nome.
    assertThat(c.key.text).isEqualTo("person:${anna.base32()}:2025")
  }
}
