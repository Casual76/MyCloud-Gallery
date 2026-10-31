package dev.pampa.mycloudgallery.bench

import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTitle
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.shelf.FileShelfStore
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.runBlocking

/**
 * I giorni "di un anno fa" del dataset (Fase 9, P12; `docs/fase9-progetto.md` §5.4: "scaffale seminato"). I ricordi "N anni fa oggi" nascono dai media dello
 * **stesso giorno** di un anno passato: un dataset con date fisse non avrebbe mai un anniversario "oggi". Qui un gruppo di righe (scelte da un passo fisso, mai
 * dalla sequenza casuale del 13/10) prende le date dei giorni attorno a "oggi" di 1 e di 3 anni fa, `[-SPAN_DAYS, +SPAN_DAYS]`: il dataset vale per [VALID_DAYS]
 * giorni dalla creazione (poi si rifa': `BenchDataset.isReusable`), e in quei giorni la generazione trova anniversari veri.
 *
 * Le date vecchie di 1 e 3 anni sono dentro i 15 anni della Libreria: la forma del resto (date, luoghi, flag) non cambia.
 */
internal object BenchAnniversary {

  /** Quanti anni indietro: "Un anno fa" e "3 anni fa". */
  val YEARS: IntArray = intArrayOf(1, 3)

  /** I giorni attorno a "oggi", da `-SPAN_DAYS` a `+SPAN_DAYS`: il dataset regge qualche giorno senza rifarsi. */
  const val SPAN_DAYS: Int = 3

  /** Dopo quanti giorni dalla creazione il dataset si rifa' (un anniversario di oggi ha ancora le sue righe). */
  const val VALID_DAYS: Int = 2

  private const val DAYS_PER_YEAR_SLOT: Int = 2 * SPAN_DAYS + 1

  /** Quante righe per giorno: abbastanza per almeno 6 media buoni (`MemoryRules.OTD_MIN_GOOD`) anche dopo le esclusioni, e per riempire un ricordo da 40. */
  fun perDay(rows: Int): Int = (rows / 250).coerceIn(24, 80)

  /** Quante righe in tutto prendono una data di anniversario. */
  fun total(rows: Int): Int = YEARS.size * DAYS_PER_YEAR_SLOT * perDay(rows)

  /** Ogni quante righe se ne prende una (la riga 0 e' il soggetto dello zoom: non si tocca). */
  fun stride(rows: Int): Int = ((rows - 2) / total(rows)).coerceAtLeast(1)

  /** La riga della [j]-esima data di anniversario. */
  fun row(rows: Int, j: Int): Int = 1 + j * stride(rows)

  /** Il giorno della [j]-esima data. */
  fun day(anchor: LocalDate, rows: Int, j: Int): LocalDate {
    val slot = j / perDay(rows)
    val year = YEARS[slot / DAYS_PER_YEAR_SLOT]
    val offset = slot % DAYS_PER_YEAR_SLOT - SPAN_DAYS
    return anchor.minusYears(year.toLong()).plusDays(offset.toLong())
  }

  /** L'istante (ms) della [j]-esima data: fra le 9 e le 19, ora locale. */
  fun timeMs(anchor: LocalDate, rows: Int, j: Int, zone: ZoneId): Long {
    val within = j % perDay(rows)
    val hour = 9 + within % 10
    val minute = (within * 7) % 60
    return day(anchor, rows, j).atTime(hour, minute, (within * 13) % 60).atZone(zone).toInstant().toEpochMilli()
  }
}

/**
 * Lo scaffale dei ricordi seminato (Fase 9, P12): cinque ricordi veri nel file che l'app legge al montaggio (`noBackupFilesDir/memories/shelf.bin`, lo stesso di
 * `FileShelfStore`), cosi' Raccolte ha **ricordi da aprire** anche prima di qualunque giro di generazione: "Un anno fa", "3 anni fa", un viaggio, un evento
 * e "Il meglio di" del mese scorso. Gli elementi sono righe del dataset (quelle che stanno sul telefono, perche' le loro miniature si disegnano davvero);
 * i titoli sono inventati (`Viaggio di prova`, `Evento di prova`), nessuna persona e nessun luogo dell'utente.
 *
 * Il giorno dell'ultimo giro e' oggi: aprire Raccolte (`refresh(OPEN)`) non rigenera niente finche' non cambia il giorno, e lo scaffale resta quello seminato.
 */
internal object BenchShelf {

  /** La cartella dei ricordi in `noBackupFilesDir` (`MemoriesWiring.DIR`). */
  const val DIR: String = "memories"

  /** Roma (id di GeoNames del dataset), per il luogo del viaggio di prova. */
  private const val TRIP_PLACE: Int = 3169070

  /**
   * Scrive lo scaffale e dice quanti ricordi ha. [membersOf] da' le righe del dataset (id dei media, solo quelle sul telefono) di un giorno di anniversario;
   * un ricordo con meno di 3 elementi non si scrive.
   */
  fun write(directory: File, today: LocalDate, membersOf: (LocalDate) -> List<String>): Int {
    val todayDay = MemoryDays.dayOf(today)
    val oneYear = today.minusYears(1)
    val memories = ArrayList<Memory>()

    fun add(key: MemoryKey, title: MemoryTitle, members: List<String>) {
      val ids = members.distinct().take(Memory.MAX_MEMBERS).map { MediaId(it) }
      if (ids.size < Memory.MIN_MEMBERS) return
      memories += Memory(id = MemoryIds.of(key), key = key, title = title, members = ids, cover = ids.first(), publishedDay = todayDay)
    }

    add(MemoryKey.OnThisDay(oneYear), MemoryTitle(1, DayRange.of(oneYear), emptyList(), null, null, null), membersOf(oneYear))
    val threeYears = today.minusYears(3)
    add(MemoryKey.OnThisDay(threeYears), MemoryTitle(3, DayRange.of(threeYears), emptyList(), null, null, null), membersOf(threeYears))

    val tripDays = DayRange(oneYear.minusDays(1), oneYear.plusDays(1))
    val tripMembers = (-1L..1L).flatMap { membersOf(oneYear.plusDays(it)) }
    add(MemoryKey.Trip(tripDays), MemoryTitle(null, tripDays, listOf(TRIP_PLACE), "Viaggio di prova", null, null), tripMembers)

    val eventDay = oneYear.plusDays(2)
    add(MemoryKey.Event(eventDay, 44), MemoryTitle(null, DayRange.of(eventDay), emptyList(), "Evento di prova", null, null), membersOf(eventDay))

    val lastMonth = YearMonth.from(today.minusMonths(1))
    val monthMembers = (-3L..3L).flatMap { membersOf(oneYear.plusDays(it)) }.reversed()
    add(MemoryKey.Month(lastMonth), MemoryTitle(null, DayRange(lastMonth.atDay(1), lastMonth.atEndOfMonth()), emptyList(), null, null, lastMonth), monthMembers)

    val ordered = memories.sortedWith(compareByDescending<Memory> { it.publishedDay }.thenBy { it.id.value })
    directory.mkdirs()
    runBlocking { FileShelfStore(directory).write(ShelfFile(ordered, emptyMap(), null, todayDay)) }
    return ordered.size
  }
}
