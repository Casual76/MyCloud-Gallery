package dev.pampa.mycloudgallery.core.map.points

/**
 * Il conteggio dei luoghi di una cella (D28: il luogo piu' frequente non nullo, a parita' l'id minore) senza allocare per cella: una tabella a
 * indirizzamento aperto che si riusa a ogni cella di una stessa chiamata ([reset] azzera solo gli slot toccati). Un luogo e' un `geonameId` > 0;
 * lo 0 e' "nessun luogo" e non si conta. Non e' thread-safe: ogni chiamata di `clusters` ne ha una sua.
 */
internal class PlaceTally {
  private var keys = IntArray(INITIAL)
  private var counts = IntArray(INITIAL)
  private var touched = IntArray(INITIAL / 2)
  private var used = 0

  fun add(place: Int) {
    if ((used + 1) * 2 > keys.size) grow()
    var slot = mix(place) and (keys.size - 1)
    while (true) {
      val k = keys[slot]
      if (k == place) {
        counts[slot]++
        return
      }
      if (k == 0) {
        keys[slot] = place
        counts[slot] = 1
        touched[used++] = slot
        return
      }
      slot = (slot + 1) and (keys.size - 1)
    }
  }

  /** Il piu' frequente, a parita' l'id minore; 0 se non c'e' nessun luogo. */
  fun best(): Int {
    var bestKey = 0
    var bestCount = 0
    for (i in 0 until used) {
      val slot = touched[i]
      val k = keys[slot]
      val c = counts[slot]
      if (c > bestCount || (c == bestCount && k < bestKey)) {
        bestKey = k
        bestCount = c
      }
    }
    return bestKey
  }

  fun reset() {
    for (i in 0 until used) {
      val slot = touched[i]
      keys[slot] = 0
      counts[slot] = 0
    }
    used = 0
  }

  private fun grow() {
    val oldKeys = keys
    val oldCounts = counts
    val oldTouched = touched
    val oldUsed = used
    keys = IntArray(oldKeys.size * 2)
    counts = IntArray(oldKeys.size * 2)
    touched = IntArray(oldKeys.size)
    used = 0
    for (i in 0 until oldUsed) {
      val slot = oldTouched[i]
      val k = oldKeys[slot]
      var s = mix(k) and (keys.size - 1)
      while (keys[s] != 0) s = (s + 1) and (keys.size - 1)
      keys[s] = k
      counts[s] = oldCounts[slot]
      touched[used++] = s
    }
  }

  private fun mix(v: Int): Int {
    var h = v * -0x61c88647
    h = h xor (h ushr 15)
    return h
  }

  private companion object {
    const val INITIAL = 64
  }
}
