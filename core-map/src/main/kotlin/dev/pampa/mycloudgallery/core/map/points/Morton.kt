package dev.pampa.mycloudgallery.core.map.points

import dev.pampa.mycloudgallery.core.map.contract.GeoGrid

// L'ARITMETICA DELLE CHIAVI DI MORTON (docs/fase9-progetto.md §2.10, D28). La definizione resta quella di `GeoGrid` (contratto di P0: x sui bit
// dispari, quindi il bit piu' alto della chiave a 48 bit e' di x); qui solo le versioni **veloci** (senza cicli sui 24 bit) che servono all'indice
// per costruire le chiavi di 100 mila punti, per scomporre una cella nelle sue colonne e righe e per elencare le celle di una vista. Il test
// `MortonTest` le confronta con `GeoGrid` su migliaia di valori casuali: una definizione sola, due implementazioni che non possono divergere.

internal object Morton {

  private const val EVEN_BITS: Long = 0x5555_5555_5555_5555L

  /** I 24 bit di [v] messi sui bit pari (il bit i va in 2i), gli altri a zero. */
  fun spread(v: Int): Long {
    var x = v.toLong() and 0xFFFF_FFFFL
    x = (x or (x shl 16)) and 0x0000_FFFF_0000_FFFFL
    x = (x or (x shl 8)) and 0x00FF_00FF_00FF_00FFL
    x = (x or (x shl 4)) and 0x0F0F_0F0F_0F0F_0F0FL
    x = (x or (x shl 2)) and 0x3333_3333_3333_3333L
    x = (x or (x shl 1)) and EVEN_BITS
    return x
  }

  /** L'inverso di [spread]: i bit pari di [v] compattati nei bit bassi. */
  fun compact(v: Long): Int {
    var x = v and EVEN_BITS
    x = (x or (x ushr 1)) and 0x3333_3333_3333_3333L
    x = (x or (x ushr 2)) and 0x0F0F_0F0F_0F0F_0F0FL
    x = (x or (x ushr 4)) and 0x00FF_00FF_00FF_00FFL
    x = (x or (x ushr 8)) and 0x0000_FFFF_0000_FFFFL
    x = (x or (x ushr 16)) and 0x0000_0000_FFFF_FFFFL
    return x.toInt()
  }

  /** La chiave a 48 bit di (x, y) a 24 bit: come `GeoGrid.morton`. */
  fun key(x: Int, y: Int): Long = (spread(x) shl 1) or spread(y)

  /** I bit bassi della chiave che un prefisso di [level] bit lascia liberi. */
  fun freeBits(level: Int): Int = GeoGrid.MAX_LEVEL - level

  /** La prima chiave (a 48 bit) della cella [prefix] di [level] bit. */
  fun firstKey(level: Int, prefix: Long): Long = prefix shl freeBits(level)

  /** La prima chiave **oltre** la cella: per `prefix` massimo e' 2^48, che sta in un `Long`. */
  fun endKey(level: Int, prefix: Long): Long = (prefix + 1) shl freeBits(level)

  /** La colonna piu' a ovest della cella (a 24 bit). */
  fun xMin(firstKey: Long): Int = compact(firstKey ushr 1)

  /** La riga piu' a nord della cella (a 24 bit). */
  fun yMin(firstKey: Long): Int = compact(firstKey)

  /** La colonna piu' a est della cella [level], data la sua [firstKey]. */
  fun xMax(level: Int, firstKey: Long): Int = compact((firstKey or lowMask(level)) ushr 1)

  /** La riga piu' a sud della cella [level], data la sua [firstKey]. */
  fun yMax(level: Int, firstKey: Long): Int = compact(firstKey or lowMask(level))

  private fun lowMask(level: Int): Long = (1L shl freeBits(level)) - 1L

  /**
   * Il prefisso di [level] bit della cella di colonna [cx] e riga [cy] **del livello** (cx ha `GeoGrid.xBits(level)` bit, cy
   * `GeoGrid.yBits(level)`): con livello pari x sta sui bit dispari, con livello dispari sui pari (il bit piu' alto del prefisso e' di x).
   */
  fun prefix(level: Int, cx: Int, cy: Int): Long =
    if (level % 2 == 0) (spread(cx) shl 1) or spread(cy) else spread(cx) or (spread(cy) shl 1)
}
