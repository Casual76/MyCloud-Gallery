package dev.pampa.mycloudgallery.feature.albums.page

import java.util.concurrent.ConcurrentHashMap

/**
 * La pagina di un album al primo ingresso (Avvio istantaneo, 28/10): finche' l'album non e' letto la pagina disegna lo scheletro della
 * griglia con il titolo che gia' si conosce, mai "Caricamento" o una rotella. Le celle dello scheletro sono quante l'album ne aveva l'ultima
 * volta in questo processo (al piu' [MAX_CELLS]: una schermata), altrimenti [DEFAULT_CELLS]. Solo un numero per album, in memoria: niente su
 * disco, nessun media, nessun titolo.
 */
internal object AlbumInstant {

  /** Una schermata di celle: oltre non si vede niente di piu'. */
  const val MAX_CELLS: Int = 24

  /** Le celle di un album mai aperto in questo processo. */
  const val DEFAULT_CELLS: Int = 12

  private val counts = ConcurrentHashMap<String, Int>()

  /** Ricorda quante celle ha l'album [id] (alla pagina pronta). */
  fun remember(id: String, cells: Int) {
    if (counts.size > MAX_ALBUMS && !counts.containsKey(id)) counts.clear()
    counts[id] = cells.coerceIn(0, MAX_CELLS)
  }

  /** Le celle dello scheletro dell'album [id]. Un album vuoto non ha scheletro (si dice subito che e' vuoto). */
  fun cellsFor(id: String): Int = counts[id] ?: DEFAULT_CELLS

  private const val MAX_ALBUMS = 256
}
