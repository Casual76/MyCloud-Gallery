package dev.pampa.mycloudgallery.feature.map.ui

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * **Il filtro finale dei Nascosti** delle superfici della mappa (docs/fase9-progetto.md D31, E-H9, E-MAP15, §2.11): subito prima di mostrare, ogni superficie
 * chiede ancora ai nascosti ([HiddenMask]) quali dei suoi media non si possono vedere, anche se l'indice dei punti e l'istantanea li hanno gia' tolti: un media
 * nascosto un attimo fa non deve comparire come copertina di una bolla, nella griglia di una zona o come mini-mappa. **Fallisce chiuso**: un guasto della lettura
 * vale "tutti nascosti" (`HiddenMask.hiddenAmong`). Senza maschera ([mask] nulla: `:app` non l'ha ancora legata) vale il solo filtro di chi costruisce i dati, che i
 * nascosti non li ha mai (la query dei punti e l'istantanea).
 *
 * E' **l'unico file** di questo modulo che nomina il contratto dei nascosti: le superfici passano tutte da qui (`HiddenSurfaces`, riga `MAP`).
 */
class FinalFilter(private val mask: HiddenMask?) {

  /** Quali di [ids] sono nascosti adesso. */
  suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
    if (mask == null || ids.isEmpty()) return emptySet()
    return mask.hiddenAmong(ids)
  }

  /** Gli id di [ids] che si possono mostrare adesso, nell'ordine dato. */
  suspend fun shown(ids: List<MediaId>): List<MediaId> {
    val hidden = hiddenAmong(ids)
    return if (hidden.isEmpty()) ids else ids.filter { it !in hidden }
  }
}
