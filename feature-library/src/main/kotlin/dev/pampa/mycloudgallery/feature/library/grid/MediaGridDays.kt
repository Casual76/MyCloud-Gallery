package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.pampa.mycloudgallery.core.common.perf.AfterFirstDraw
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.min
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fase H (R2, scelta dell'utente dell'11/11: «il calendario con i giorni»): i giorni che hanno foto nella vista [view], per il calendario del
 * salto a una data. Vengono dai dati che la griglia ha gia' (il giorno di ogni elemento, `TimelineSnapshot.localDayAt`, lo stesso delle
 * intestazioni): nessuna query. Una passata sulla vista fuori dal thread principale ([of]: 100 mila elementi, pochi millisecondi), poi ogni
 * domanda e' una ricerca binaria. Si tengono **per vista del catalogo** in [GridDaysCache]: si rifanno solo quando il catalogo cambia.
 *
 * Per ogni giorno si tiene la prima e l'ultima posizione nella vista: e' cio' che serve al salto ([jumpToDay]).
 */
@Immutable
class GridDays private constructor(
  internal val view: TimelineView,
  /** I giorni con foto, `aaaammgg`, in ordine crescente. */
  private val days: IntArray,
  private val firstViewIndex: IntArray,
  private val lastViewIndex: IntArray,
) {
  /** I mesi con almeno una foto, dal piu' vecchio al piu' recente. */
  val months: List<YearMonth> = buildList {
    var previous = -1
    for (day in days) {
      val month = day / 100
      if (month != previous) add(YearMonth.of(month / 100, month % 100))
      previous = month
    }
  }

  /** Gli anni con almeno una foto, dal piu' recente al piu' vecchio (l'ordine della scelta rapida). */
  val yearsNewestFirst: List<Int> = months.map { it.year }.distinct().sortedDescending()

  val isEmpty: Boolean get() = days.isEmpty()

  /** Il giorno [date] ha almeno una foto. */
  fun has(date: LocalDate): Boolean = days.binarySearch(encode(date)) >= 0

  /** Il mese [month] ha almeno una foto. */
  fun has(month: YearMonth): Boolean {
    val start = encode(month.atDay(1))
    val i = days.binarySearch(start).let { if (it >= 0) it else -it - 1 }
    return i < days.size && days[i] / 100 == start / 100
  }

  /** Le posizioni nella vista (prima e ultima) del giorno [date], o null se quel giorno non ha foto. */
  internal fun viewRangeOf(date: LocalDate): IntRange? {
    val i = days.binarySearch(encode(date))
    if (i < 0) return null
    return firstViewIndex[i]..lastViewIndex[i]
  }

  /**
   * Il mese da cui il calendario parte, per un giorno [shown] (quello che l'etichetta in alto dice): il suo, se ha foto; se no il mese con foto
   * piu' vicino che non lo supera; senza [shown] il piu' recente.
   */
  fun startMonth(shown: LocalDate?): YearMonth? {
    if (months.isEmpty()) return null
    if (shown == null) return months.last()
    val target = YearMonth.from(shown)
    return months.lastOrNull { it <= target } ?: months.first()
  }

  companion object {
    /** Una passata sulla vista: da chiamare fuori dal thread principale (il calendario lo fa con `Dispatchers.Default`). */
    fun of(view: TimelineView): GridDays {
      val snapshot = view.snapshot
      val map = HashMap<Int, IntArray>()
      var lastDay = Int.MIN_VALUE
      var current: IntArray? = null
      for (i in 0 until view.size) {
        val day = snapshot.localDayAt(view.snapshotIndex(i))
        // La vista e' in ordine di tempo: i giorni uguali sono quasi sempre vicini, e la mappa si tocca solo quando il giorno cambia.
        if (day != lastDay || current == null) {
          current = map.getOrPut(day) { intArrayOf(i, i) }
          lastDay = day
        }
        val range = current
        if (i < range[0]) range[0] = i
        if (i > range[1]) range[1] = i
      }
      val sorted = map.keys.toIntArray().apply { sort() }
      return GridDays(
        view = view,
        days = sorted,
        firstViewIndex = IntArray(sorted.size) { map.getValue(sorted[it])[0] },
        lastViewIndex = IntArray(sorted.size) { map.getValue(sorted[it])[1] },
      )
    }

    /** Un giorno come `aaaammgg`, la forma di `TimelineSnapshot.localDayAt` e delle sezioni. */
    fun encode(date: LocalDate): Int = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
  }
}

/**
 * H3, giro 1 (CU-10, CG-4: «il calendario si apre passando da un indicatore di caricamento e ricalcola i giorni a ogni apertura»): i giorni con
 * foto **tenuti in memoria per vista del catalogo**. Il calendario si apre gia' disegnato con cio' che c'e' qui ([latest], anche di una vista
 * di poco prima) e si completa appena la vista di adesso e' pronta; una seconda apertura sulla stessa vista non rifa' niente.
 *
 * Quando si preparano:
 *  - **al primo tocco sulla data** ([prepare] con [openDispatcher], il `Default`: l'utente sta guardando);
 *  - **a griglia ferma da un po'** ([keepWarm]): mai all'avvio (aspetta il primo disegno utile, `AfterFirstDraw`), mai mentre la griglia scorre,
 *    una volta per vista nuova, su un thread suo a **priorita' minima** creato al primo uso.
 * A calendario chiuso e con il catalogo fermo non costa niente: una coroutine che aspetta.
 */
@Stable
class GridDaysCache {

  /** I giorni dell'ultima vista preparata (puo' essere di una vista di poco prima: si mostra quella finche' la nuova non e' pronta). */
  var latest: GridDays? by mutableStateOf(null)
    private set

  /** Dove si contano i giorni quando il calendario si apre e non li ha: il `Default` (i test lo cambiano). */
  internal var openDispatcher: CoroutineDispatcher = Dispatchers.Default

  /** Solo per i test: quante passate sulla vista sono state fatte. */
  internal var computations: Int = 0
    private set

  private val mutex = Mutex()

  /** I giorni di [view], se sono gia' pronti. */
  fun current(view: TimelineView): GridDays? = latest?.takeIf { it.view === view }

  /** I giorni di [view]: quelli in memoria se sono di questa vista, se no una passata su [dispatcher]. Due richieste insieme ne fanno una. */
  suspend fun prepare(view: TimelineView, dispatcher: CoroutineDispatcher = openDispatcher): GridDays = mutex.withLock {
    current(view)?.let { return it }
    val days = withContext(dispatcher) { GridDays.of(view) }
    computations++
    latest = days
    days
  }

  /**
   * Tiene i giorni pronti per la vista della griglia [grid], senza costi visibili: dopo il primo disegno utile, a ogni vista nuova aspetta che la
   * griglia sia ferma da [idleMillis] e prepara i giorni sul thread a priorita' minima. Uno scorrimento che riparte annulla l'attesa.
   */
  suspend fun keepWarm(grid: MediaGridState, idleMillis: Long = WarmIdleMillis) {
    AfterFirstDraw.await()
    snapshotFlow { grid.view to grid.listState.isScrollInProgress }.collectLatest { (view, scrolling) ->
      if (scrolling || current(view) != null || view.size == 0) return@collectLatest
      delay(idleMillis)
      prepare(view, LowPriority.dispatcher)
    }
  }

  private object LowPriority {
    /** Un thread solo, a priorita' minima, creato al primo uso (mai all'avvio). */
    val dispatcher: CoroutineDispatcher by lazy {
      Executors.newSingleThreadExecutor { r -> Thread(r, "MCG:giorni").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher()
    }
  }

  internal companion object {
    /** Quanto la griglia deve restare ferma prima che i giorni si preparino da soli. */
    const val WarmIdleMillis: Long = 3_000L
  }
}

/**
 * Porta la griglia al giorno [date] (R2): la prima riga di celle di quel giorno in cima all'area dei contenuti, cioe' subito sotto la chrome;
 * la sua intestazione resta appena sopra, sotto l'etichetta di vetro, che quindi dice proprio quel giorno.
 *
 * **Il livello:** da Anni o da Mesi si scende a Giorni (scelta di H3): il calendario sceglie un giorno, e a quei livelli un giorno non ha una
 * riga sua. A Giorni e a Tutte si resta al livello. [onLevelChanged] riceve il livello nuovo, se cambia (la Libreria lo ricorda).
 * Senza layout, durante un pinch, o se il giorno non c'e' piu' nella vista, non fa niente.
 */
suspend fun MediaGridState.jumpToDay(days: GridDays, date: LocalDate, onLevelChanged: (GridLevel) -> Unit = {}) {
  if (pinching) return
  if (level == GridLevel.YEARS || level == GridLevel.MONTHS) {
    level = GridLevel.DAYS
    onLevelChanged(GridLevel.DAYS)
    withTimeoutOrNull(LevelWaitMillis) { snapshotFlow { layout?.spec?.level }.first { it == GridLevel.DAYS } }
  }
  val l = layout ?: return
  if (l.itemCount == 0) return
  val shown = shownView
  // La vista mostrata puo' essere piu' nuova di quella da cui sono stati contati i giorni (la lettura del telefono continua): si riconta quel giorno.
  val range = if (shown === days.view) days.viewRangeOf(date) else withContext(Dispatchers.Default) { GridDays.of(shown).viewRangeOf(date) }
  if (range == null || layout !== l) return
  val first = l.rowOfItem(l.positionOfViewIndex(range.first))
  val last = l.rowOfItem(l.positionOfViewIndex(range.last))
  userScrolled = true
  listState.requestScrollToItem(min(first, last), 0)
}

/** Quanto si aspetta il layout di Giorni dopo un cambio di livello dal calendario (di solito e' gia' pronto: i quattro si precalcolano). */
private const val LevelWaitMillis = 2_000L
