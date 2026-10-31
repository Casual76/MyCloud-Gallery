package dev.pampa.mycloudgallery.feature.map.ui

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.model.MediaId

/**
 * **Il filtro finale dei Nascosti** delle superfici della mappa (docs/fase9-progetto.md D31, E-H9, E-MAP15, §2.11): subito prima di mostrare, ogni superficie
 * chiede ancora ai nascosti ([HiddenMask]) quali dei suoi media non si possono vedere, anche se l'indice dei punti e l'istantanea li hanno gia' tolti: un media
 * nascosto un attimo fa non deve comparire come copertina di una bolla, nella griglia di una zona o come mini-mappa. **Fallisce chiuso**: un guasto della lettura
 * vale "tutti nascosti" (`HiddenMask.hiddenAmong`).
 *
 * **La maschera c'e' sempre** (R3, 31/10): [mask] non e' opzionale. Prima, senza il legame di `:app`, il filtro non faceva niente e nessuna prova lo vedeva; ora
 * `MapDependencies` la chiede a Hilt senza `Optional` e la mancanza e' un errore di compilazione di `:app`. Chi prova una superficie senza nascosti passa una
 * maschera che non nasconde niente, in modo che si veda.
 *
 * E' **l'unico file** di questo modulo che nomina il contratto dei nascosti: le superfici passano tutte da qui (`HiddenSurfaces`, riga `MAP`).
 */
class FinalFilter(private val mask: HiddenMask) {

  /** Quali di [ids] sono nascosti adesso. */
  suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId> {
    if (ids.isEmpty()) return emptySet()
    return mask.hiddenAmong(ids)
  }

  /** Gli id di [ids] che si possono mostrare adesso, nell'ordine dato. */
  suspend fun shown(ids: List<MediaId>): List<MediaId> {
    val hidden = hiddenAmong(ids)
    return if (hidden.isEmpty()) ids else ids.filter { it !in hidden }
  }
}
