package dev.pampa.mycloudgallery.core.library.index

import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID

// Le chiavi dell'indice: un'identita' sono due `long` (UUID), l'ordine e' quello della query del catalogo
// (`sort_time DESC, media_id DESC`). Per un UUID canonico (minuscolo, con trattini) il confronto fra stringhe di SQLite
// (BINARY) e il confronto senza segno dei due long danno lo stesso ordine: le cifre esadecimali hanno lo stesso ordine
// dei loro valori ('0'..'9' < 'a'..'f') e i trattini stanno in posizioni fisse.

/** Lettura veloce di un UUID canonico in due long, senza oggetti. */
internal object Uuids {

  /** Scrive in [out] (0 = parte alta, 1 = parte bassa) e restituisce vero se [s] e' un UUID di 36 caratteri. */
  fun parse(s: String, out: LongArray): Boolean {
    if (s.length != 36 || s[8] != '-' || s[13] != '-' || s[18] != '-' || s[23] != '-') return false
    var msb = 0L
    var lsb = 0L
    for (k in 0 until 36) {
      if (k == 8 || k == 13 || k == 18 || k == 23) continue
      val d = hex(s[k])
      if (d < 0) return false
      if (k < 18) msb = (msb shl 4) or d.toLong() else lsb = (lsb shl 4) or d.toLong()
    }
    out[0] = msb
    out[1] = lsb
    return true
  }

  fun toMediaId(msb: Long, lsb: Long): MediaId = MediaId(UUID(msb, lsb).toString())

  fun toText(msb: Long, lsb: Long): String = UUID(msb, lsb).toString()

  private fun hex(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
  }
}

/** L'ordine dell'indice: negativo se (t1, m1, l1) viene **prima** di (t2, m2, l2), cioe' e' piu' recente. */
internal fun compareIndexOrder(t1: Long, m1: Long, l1: Long, t2: Long, m2: Long, l2: Long): Int {
  if (t1 != t2) return if (t1 > t2) -1 else 1
  val m = java.lang.Long.compareUnsigned(m2, m1)
  if (m != 0) return m
  return java.lang.Long.compareUnsigned(l2, l1)
}

/**
 * La tabella delle identita' a indirizzamento aperto (sondaggio lineare): `indexOf` in O(1) senza un oggetto per
 * elemento. Riempita al piu' all'80%: circa 5 byte per elemento.
 */
internal class IdTable(private val msb: LongArray, private val lsb: LongArray, size: Int) {

  private val slots: IntArray
  private val mask: Int

  init {
    var capacity = 8
    while (capacity < size + size / 4 + 1) capacity = capacity shl 1
    slots = IntArray(capacity)
    mask = capacity - 1
    for (i in 0 until size) {
      var slot = hash(msb[i], lsb[i]) and mask
      while (slots[slot] != 0) slot = (slot + 1) and mask
      slots[slot] = i + 1
    }
  }

  val bytes: Long get() = 16L + slots.size * 4L

  fun find(m: Long, l: Long): Int {
    var slot = hash(m, l) and mask
    while (true) {
      val v = slots[slot]
      if (v == 0) return -1
      val i = v - 1
      if (msb[i] == m && lsb[i] == l) return i
      slot = (slot + 1) and mask
    }
  }

  fun find(id: MediaId): Int {
    val out = LongArray(2)
    return if (Uuids.parse(id.value, out)) find(out[0], out[1]) else -1
  }

  private companion object {
    fun hash(m: Long, l: Long): Int {
      var z = m * -0x61c8864680b583ebL xor l
      z = (z xor (z ushr 31)) * -0x40a7b892e31b1a47L
      return (z xor (z ushr 29)).toInt()
    }
  }
}
