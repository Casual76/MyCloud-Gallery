package dev.pampa.mycloudgallery.core.memories.contract

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.time.LocalDate
import java.time.YearMonth

// LA PIANIFICAZIONE DEI RICORDI (docs/fase9-progetto.md §1.2 "Generatori", §2.1, D3, D5, D7, D8). Contratto di P0: la tabella a colonne, le
// esclusioni di D5 e le soglie di D3/D7 in un posto solo (le usano il riferimento, P6 e P7: un'esclusione non puo' valere in un posto e non
// in un altro), il generatore come funzione **pura e deterministica**. Il generatore vero e' di P6 (plan/), il riferimento a forza bruta e'
// `ReferencePlanner` (testing/) con la suite `PlannerContract`. Soglie provvisorie: le calibra P11 (§5.3).

/**
 * Le celle **visibili** della Libreria a colonne (§2.1, passo 1): un'estrazione dell'istantanea (che i nascosti non li ha e ha una cella per
 * gruppo, D5) piu' i ritagli ([MemoryCutouts]). Le posizioni sono quelle dell'istantanea (0 = il piu' recente). Si rifa' solo se cambia [seq].
 */
class CellTable private constructor(
  private val snapshot: TimelineSnapshot,
  private val days: IntArray,
  private val times: LongArray,
  private val cutout: BooleanArray,
) {
  val size: Int get() = days.size
  val seq: Long get() = snapshot.seq

  /** Le posizioni di ogni giorno locale (`aaaammgg`), in ordine di posizione: l'"indice per giorno". */
  private val byDay: Map<Int, IntArray> by lazy {
    val lists = HashMap<Int, MutableList<Int>>()
    for (i in 0 until size) lists.getOrPut(days[i]) { ArrayList() }.add(i)
    lists.mapValues { it.value.toIntArray() }
  }

  fun idAt(i: Int): MediaId = snapshot.idAt(i)

  fun indexOf(id: MediaId): Int = snapshot.indexOf(id)

  fun dayAt(i: Int): Int = days[i]

  /** La data del giorno locale della cella, o null se il giorno non e' una data (dato rovinato: la cella non entra nei ricordi). */
  fun dateAt(i: Int): LocalDate? = MemoryDays.dateOf(days[i])

  fun timeAt(i: Int): Long = times[i]

  fun sourceAt(i: Int): MediaSource = snapshot.sourceAt(i)

  fun flagsAt(i: Int): Int = snapshot.flagsAt(i)

  fun kindAt(i: Int): MediaKind = snapshot.kindAt(i)

  fun durationMsAt(i: Int): Long = snapshot.durationMsAt(i)

  fun placeAt(i: Int): Int = snapshot.placeAt(i)

  fun aspectMilliAt(i: Int): Int = snapshot.aspectMilliAt(i)

  fun thumbHashAt(i: Int): ThumbHash? = snapshot.thumbHashAt(i)

  fun isCutout(i: Int): Boolean = cutout[i]

  /** Le posizioni del giorno [day] (`aaaammgg`); vuoto se nessuna. */
  fun onDay(day: Int): IntArray = byDay[day] ?: EMPTY

  /** I giorni che hanno almeno una cella. */
  fun daysWithCells(): Set<Int> = byDay.keys

  /** La cella [i] come candidata alla scelta delle foto. */
  fun candidate(i: Int): CandidateCell = CandidateCell(
    idAt(i), timeAt(i), dayAt(i), kindAt(i), sourceAt(i), flagsAt(i), durationMsAt(i), aspectMilliAt(i), thumbHashAt(i), isCutout(i),
  )

  override fun toString(): String = "CellTable($size, seq=$seq)"

  companion object {
    private val EMPTY = IntArray(0)

    /** La tabella dell'istantanea [snapshot] (gia' senza nascosti), con i ritagli [cutouts]. */
    fun of(snapshot: TimelineSnapshot, cutouts: Set<MediaId> = emptySet()): CellTable {
      val n = snapshot.size
      val days = IntArray(n) { snapshot.localDayAt(it) }
      val times = LongArray(n) { snapshot.sortTimeAt(it) }
      val cut = BooleanArray(n)
      for (id in cutouts) {
        val i = snapshot.indexOf(id)
        if (i >= 0) cut[i] = true
      }
      return CellTable(snapshot, days, times, cut)
    }
  }
}

/**
 * Le esclusioni di D5 (e del "Mai" per i periodi, D11): **una sola definizione** per il generatore e per la scelta delle foto. Null = la cella
 * puo' entrare. Le persone "Mai" si guardano nella scelta delle foto (servono le persone di ogni media, [PickSignals.people]).
 */
object MemoryExclusions {
  fun of(
    source: MediaSource,
    flags: Int,
    kind: MediaKind,
    cutout: Boolean,
    day: Int,
    dateBound: Boolean,
    choices: MemoriesChoices,
  ): DropReason? = when {
    source == MediaSource.SCREENSHOT -> DropReason.SCREENSHOT
    CellFlags.has(flags, CellFlags.DOCUMENT) -> DropReason.DOCUMENT
    kind == MediaKind.ANIMATED_IMAGE -> DropReason.ANIMATED
    cutout -> DropReason.CUTOUT
    CellFlags.has(flags, CellFlags.UNREADABLE) -> DropReason.UNREADABLE
    MemoryDays.dateOf(day) == null -> DropReason.DATE_ESTIMATED
    dateBound && CellFlags.has(flags, CellFlags.DATE_ESTIMATED) -> DropReason.DATE_ESTIMATED
    choices.neverOn(day) -> DropReason.NEVER_DAYS
    else -> null
  }

  fun of(table: CellTable, i: Int, dateBound: Boolean, choices: MemoriesChoices): DropReason? =
    of(table.sourceAt(i), table.flagsAt(i), table.kindAt(i), table.isCutout(i), table.dayAt(i), dateBound, choices)

  fun of(cell: CandidateCell, dateBound: Boolean, choices: MemoriesChoices): DropReason? =
    of(cell.source, cell.flags, cell.kind, cell.cutout, cell.day, dateBound, choices)
}

/** Le dimensioni e i tetti della scelta di un tipo di ricordo (D7). */
data class PickSpec(
  val kind: MemoryKind,
  val minSize: Int,
  val maxSize: Int,
  val perDayMin: Int,
  val perDayMax: Int,
  val perHourMax: Int,
  val dateBound: Boolean,
) {
  init {
    require(minSize in Memory.MIN_MEMBERS..maxSize && maxSize <= Memory.MAX_MEMBERS) { "Dimensioni non valide." }
    require(perDayMin >= 0 && perDayMax >= 1 && perHourMax >= 1) { "Tetti non validi." }
  }
}

/**
 * Le soglie di D3, D7, D9, D10 (provvisorie: le calibra P11 sui dati sintetici e F125 su quelli veri, §5.3). Interpretazioni di P0 scritte in
 * `docs/decisioni.md` ("Fase 9 P0"): un evento ha il tetto per ora a 10 (un evento e' "molte foto in poche ore": con 4 all'ora un evento di
 * due ore non arriverebbe al minimo); "anni fa" e mese sono legati a una data, viaggi ed eventi pure, le persone no.
 */
object MemoryRules {
  const val OTD_MIN_GOOD: Int = 6
  const val OTD_PER_DAY: Int = 2
  const val ANNIVERSARY_WINDOW_DAYS: Int = 3
  const val REDISCOVERY_MIN_AGE_DAYS: Long = 365
  const val REDISCOVERY_EVERY_DAYS: Int = 7
  const val PERSON_MIN_PHOTOS: Int = 20
  const val PERSON_MIN_DAYS: Int = 3
  const val PERSON_YEAR_MIN_PHOTOS: Int = 15
  const val PERSON_RECENT_DAYS: Long = 182
  const val PERSON_EVERY_DAYS: Int = 7
  const val MONTH_LAST_DAY: Int = 7
  const val MONTH_MIN_CANDIDATES: Int = 40

  /** Al piu' due ricordi nuovi al giorno, piu' quello del mese nel suo giorno (D3). */
  const val NEW_PER_DAY: Int = 2

  /** Un ricordo visto non si rigenera per 12 mesi (D9); uno scartato mai. */
  const val SEEN_QUIET_DAYS: Long = 365

  /** Lo scaffale: al piu' 60 ricordi e 30 giorni (D10). */
  const val SHELF_MAX: Int = 60
  const val SHELF_DAYS: Long = 30

  /** La notifica: solo per un ricordo con almeno 8 elementi (D15). */
  const val NOTIFY_MIN_MEMBERS: Int = 8

  fun spec(kind: MemoryKind): PickSpec = when (kind) {
    MemoryKind.ON_THIS_DAY -> PickSpec(kind, 6, 24, perDayMin = 0, perDayMax = 24, perHourMax = 4, dateBound = true)
    MemoryKind.TRIP -> PickSpec(kind, 10, 40, perDayMin = 1, perDayMax = 8, perHourMax = 4, dateBound = true)
    MemoryKind.EVENT -> PickSpec(kind, 10, 40, perDayMin = 1, perDayMax = 40, perHourMax = 10, dateBound = true)
    MemoryKind.PERSON -> PickSpec(kind, 15, 30, perDayMin = 0, perDayMax = 6, perHourMax = 4, dateBound = false)
    MemoryKind.MONTH -> PickSpec(kind, 20, 30, perDayMin = 0, perDayMax = 4, perHourMax = 4, dateBound = true)
  }
}

/** Le priorita' di pubblicazione (§2.1, passo 6): prima la piu' bassa. Il mese ha la sua quota (D3). */
object MemoryPriority {
  const val ANNIVERSARY: Int = 0
  const val OTD_RECENT: Int = 1
  const val PERSON: Int = 2
  const val REDISCOVERY: Int = 3
  const val OTD_OLDEST: Int = 4
  const val MONTH: Int = 9
}

/** Cio' che serve sapere del passato per pianificare (D3, D9): ricordi gia' sullo scaffale e ultime pubblicazioni per tipo (`aaaammgg`). */
data class PlanHistory(
  val onShelf: Set<MemoryId> = emptySet(),
  val lastPublished: Map<MemoryKind, Int> = emptyMap(),
  val lastRediscovery: Int? = null,
) {
  override fun toString(): String = "PlanHistory(${onShelf.size})"
}

/** L'ingresso del generatore (§1.2): tutto in memoria, niente porte, niente orologio (oggi arriva a parte). */
class PlanInput(
  val cells: CellTable,
  val trips: List<TripDigestView>,
  val people: List<PersonDigest>,
  val peopleAvailable: Boolean,
  val choices: MemoriesChoices,
  val history: PlanHistory,
) {
  override fun toString(): String = "PlanInput(${cells.size}, viaggi=${trips.size}, persone=${people.size})"
}

/**
 * Un ricordo possibile oggi (§2.1, passo 4): la chiave e l'id, la priorita', le posizioni delle celle candidate **gia' senza le esclusioni di
 * D5** ([cells], in ordine di posizione), i dati del titolo. [rediscovery] per un viaggio riproposto senza anniversario.
 */
class MemoryCandidate(
  val key: MemoryKey,
  val priority: Int,
  val cells: IntArray,
  val yearsAgo: Int?,
  val days: DayRange,
  val tripLabel: String? = null,
  val tripPlaces: List<Int> = emptyList(),
  val person: PersonId? = null,
  val month: YearMonth? = null,
  val origin: MemoryOrigin? = null,
  val rediscovery: Boolean = false,
) {
  val id: MemoryId = MemoryIds.of(key)

  override fun toString(): String = "MemoryCandidate(${key.kind.code}, p=$priority, ${cells.size})"
}

/**
 * Il generatore (D3): **puro e deterministico** (stessa Libreria, stesse scelte, stesso giorno = stessi candidati nello stesso ordine:
 * priorita', poi testo della chiave). Scarta le chiavi gia' sullo scaffale, viste negli ultimi 12 mesi, scartate, e quelle toccate da un periodo
 * "Mai" (un viaggio che lo tocca anche per un giorno non diventa un ricordo, E-MEM12).
 */
fun interface MemoryPlanner {
  fun plan(input: PlanInput, today: LocalDate): List<MemoryCandidate>
}

/** La luminanza media dal `ThumbHash` (D6): il `l_dc` dell'intestazione, 6 bit (l'algoritmo pubblico, come `ThumbHashAlgorithm`). */
object ThumbLight {
  fun averageLuma(hash: ThumbHash): Double? {
    val bytes = hash.bytes()
    if (bytes.size < 3) return null
    return (bytes[0].toInt() and 63) / 63.0
  }
}
