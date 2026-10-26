package dev.pampa.mycloudgallery.core.albums.personal

// LE POSIZIONI DEGLI ELEMENTI (docs/fase7-progetto.md D21, §2.1 "Ordine"): ogni elemento ha la sua voce `item:<media>` con una posizione, a
// **intervalli** (1.000, 2.000, ...) per inserire in mezzo senza riscrivere tutto. Una voce per elemento fa convivere le aggiunte e i
// riordini di due dispositivi: ognuno scrive solo le voci che cambia, e l'ordine di un album e' l'ordine per (posizione, id del media).
// Quando fra due elementi non c'e' piu' posto (due posizioni attaccate, o uguali dopo due aggiunte insieme) si **ricompatta**: tutte le
// posizioni tornano a intervalli, in un'operazione rara che chi scrive applica a lotti ([BATCH]).
//
// Pura: niente catalogo, niente store. L'ordine "stabile" vuol dire che un inserimento in mezzo cambia **una** voce (la sua) e lascia
// intatte le posizioni degli altri, quindi nessun altro dispositivo vede un elemento saltare altrove.

/**
 * Un elemento e il suo posto. A parita' di posizione (due dispositivi hanno aggiunto insieme) decide [tie], un dato **dell'elemento**
 * uguale su ogni dispositivo anche se l'id del media e' un altro (l'impronta della voce), poi l'id: lo stesso ordine ovunque.
 */
data class PositionedItem(val media: String, val position: Long, val tie: String = "") {
  override fun toString(): String = "PositionedItem"
}

/** Le voci da scrivere per un riordino o un'aggiunta: una sola, o tutte se si e' dovuto ricompattare ([recompacted]). */
data class PositionPlan(val changes: List<PositionedItem>, val recompacted: Boolean) {
  override fun toString(): String = "PositionPlan(${changes.size}, recompacted=$recompacted)"
}

object AlbumPositions {
  /** L'intervallo fra due posizioni consecutive dopo una ricompattazione e per le aggiunte in coda. */
  const val STEP: Long = 1_000

  /** Quante voci si riscrivono per volta quando si ricompatta (chi scrive cede il passo fra un lotto e l'altro). */
  const val BATCH: Int = 200

  /** Oltre questo valore (la posizione di una voce della Fase 4 senza posto vale `Long.MAX_VALUE`) non si somma: si ricompatta. */
  private const val LIMIT: Long = Long.MAX_VALUE / 4

  /** L'ordine dell'album: per posizione, poi per [PositionedItem.tie], poi per id del media (due posizioni uguali si ordinano uguale ovunque). */
  fun sorted(items: Collection<PositionedItem>): List<PositionedItem> = items.sortedWith(compareBy({ it.position }, { it.tie }, { it.media }))

  /** Tutti gli elementi di [order] a intervalli, nell'ordine dato (la ricompattazione). */
  fun compact(order: List<String>): List<PositionedItem> = order.mapIndexed { i, media -> PositionedItem(media, (i + 1) * STEP) }

  /**
   * Mette [moved] subito dopo [after] (null = in testa) nella sequenza [current] (qualunque ordine: si ordina qui; [moved] puo' esserci
   * gia'). Una voce sola se c'e' posto fra i due vicini; altrimenti tutte, ricompattate.
   */
  fun insertAfter(current: Collection<PositionedItem>, moved: String, after: String?): PositionPlan {
    require(after != moved) { "Un elemento non va dopo se stesso." }
    val rest = sorted(current.filter { it.media != moved })
    val index = if (after == null) -1 else rest.indexOfFirst { it.media == after }
    require(after == null || index >= 0) { "L'elemento dopo cui mettere non c'e'." }
    val previous = rest.getOrNull(index)?.position
    val next = rest.getOrNull(index + 1)?.position
    val position: Long? = if (previous == null) {
      if (next == null) STEP else next.takeIf { it in -LIMIT..LIMIT }?.minus(STEP)
    } else if (next == null) {
      previous.takeIf { it in -LIMIT..LIMIT }?.plus(STEP)
    } else if (gap(previous, next) >= 2) {
      previous + gap(previous, next) / 2
    } else {
      null
    }
    if (position != null) return PositionPlan(listOf(PositionedItem(moved, position)), recompacted = false)
    val order = rest.map { it.media }.toMutableList()
    order.add(index + 1, moved)
    return PositionPlan(compact(order), recompacted = true)
  }

  /** La distanza fra due posizioni, senza sbordare (una posizione della Fase 4 senza posto e' `Long.MAX_VALUE`). */
  private fun gap(previous: Long, next: Long): Long = try {
    Math.subtractExact(next, previous)
  } catch (e: ArithmeticException) {
    Long.MAX_VALUE
  }

  /**
   * Aggiunge [added] in coda, nell'ordine dato, a intervalli dall'ultimo elemento di [current]. Se l'ultimo e' troppo in la' per sommare
   * (una voce senza posto della Fase 4) si ricompatta tutto: prima gli esistenti, poi i nuovi.
   */
  fun append(current: Collection<PositionedItem>, added: List<String>): PositionPlan {
    val rest = sorted(current)
    val last = rest.lastOrNull()?.position ?: 0L
    if (last in -LIMIT..LIMIT) {
      return PositionPlan(added.mapIndexed { i, media -> PositionedItem(media, last + (i + 1) * STEP) }, recompacted = false)
    }
    return PositionPlan(compact(rest.map { it.media } + added), recompacted = true)
  }
}
