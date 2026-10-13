package dev.pampa.mycloudgallery.core.imaging.scheduler

/** In che verso scorre la griglia, per l'anticipo delle miniature (D5: "anticipo nella direzione dello scorrimento"). */
enum class ScrollDirection {
  /** Verso gli indici crescenti. */
  FORWARD,

  /** Verso gli indici decrescenti. */
  BACKWARD,

  /** Fermo (o verso incerto): si anticipa da entrambe le parti, alternando. */
  NONE,
}

/**
 * Quali elementi chiedere in anticipo. La griglia (P7) conosce gli indici visibili e il verso; questa funzione pura
 * dice quali indici stanno per entrare, **dal piu' vicino al piu' lontano**: e' l'ordine in cui il pianificatore li
 * fa partire, quindi conta quanto la scelta. La griglia costruisce poi le `ThumbRequest` di quegli indici e le passa
 * come `ahead` a `ThumbnailLoader.window`.
 */
object LookAhead {

  /**
   * L'ordine delle celle **visibili**, dalla piu' urgente. Durante un fling quelle che contano sono sul bordo
   * d'ingresso (le altre escono tra pochi fotogrammi): con [ScrollDirection.FORWARD] si parte dall'ultima visibile e
   * si torna indietro, con [ScrollDirection.BACKWARD] dalla prima. Da fermi ([ScrollDirection.NONE]) l'ordine di
   * lettura, dall'alto. Sceglie la griglia: in uno scorrimento lento basta `NONE`.
   */
  fun visibleIndices(firstVisible: Int, lastVisible: Int, direction: ScrollDirection): IntArray {
    if (lastVisible < firstVisible) return IntArray(0)
    val ascending = IntArray(lastVisible - firstVisible + 1) { firstVisible + it }
    return if (direction == ScrollDirection.FORWARD) ascending.reversedArray() else ascending
  }

  /**
   * Gli indici dei prossimi [count] elementi fuori dall'intervallo visibile `[firstVisible, lastVisible]`, in
   * [direction]. Con [ScrollDirection.NONE] alterna avanti e indietro a partire dal piu' vicino. Non esce mai da
   * `0 until itemCount`.
   */
  fun indices(
    firstVisible: Int,
    lastVisible: Int,
    itemCount: Int,
    direction: ScrollDirection,
    count: Int,
  ): IntArray {
    if (count <= 0 || itemCount <= 0 || lastVisible < firstVisible) return IntArray(0)
    val ahead = lastVisible + 1
    val behind = firstVisible - 1
    val forward = (ahead until itemCount).take(count)
    val backward = generateSequence(behind) { it - 1 }.takeWhile { it >= 0 }.take(count).toList()
    return when (direction) {
      ScrollDirection.FORWARD -> forward.toIntArray()
      ScrollDirection.BACKWARD -> backward.toIntArray()
      ScrollDirection.NONE -> {
        val out = ArrayList<Int>(count)
        var f = 0
        var b = 0
        while (out.size < count && (f < forward.size || b < backward.size)) {
          if (f < forward.size) out += forward[f++]
          if (out.size < count && b < backward.size) out += backward[b++]
        }
        out.toIntArray()
      }
    }
  }
}
