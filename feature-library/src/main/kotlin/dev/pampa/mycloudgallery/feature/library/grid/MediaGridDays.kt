package dev.pampa.mycloudgallery.feature.library.grid

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fase H (R2, scelta dell'utente dell'11/11: «il calendario con i giorni»): i giorni che hanno foto nella vista [view], per il calendario del
 * salto a una data. Vengono dai dati che la griglia ha gia' (il giorno di ogni elemento, `TimelineSnapshot.localDayAt`, lo stesso delle
 * intestazioni): nessuna query. Si calcolano **solo quando il calendario si apre**, fuori dal thread principale ([of]): una passata sulla vista
 * (100 mila elementi: pochi millisecondi), e poi ogni domanda e' una ricerca binaria.
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
