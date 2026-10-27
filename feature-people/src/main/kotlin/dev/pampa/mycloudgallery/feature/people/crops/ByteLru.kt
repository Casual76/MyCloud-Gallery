package dev.pampa.mycloudgallery.feature.people.crops

/**
 * Una cache LRU **solo in memoria**, limitata in byte (docs/fase8-progetto.md D25: "cache solo in memoria (LRU da 8 MB), mai su disco"):
 * le copertine e i ritagli dei volti si rifanno al momento e non si salvano da nessuna parte. [weigh] da' il peso di un valore; un valore
 * piu' pesante dell'intero tetto non entra. Sicura fra thread (un lucchetto: e' una mappa piccola, toccata a ogni avatar).
 *
 * Nessun file, nessun database, nessun `cacheDir`: lo verifica `NoDiskSourcesTest`.
 */
class ByteLru<K : Any, V : Any>(private val maxBytes: Long, private val weigh: (V) -> Int) {
  init {
    require(maxBytes > 0) { "Il tetto di una cache e' positivo." }
  }

  private val entries = LinkedHashMap<K, V>(INITIAL, LOAD, true)
  private var bytes = 0L

  @Synchronized
  fun get(key: K): V? = entries[key]

  /** Mette [value] sotto [key] (sostituisce); toglie i meno usati finche' si sta nel tetto. Un valore piu' grosso del tetto non entra. */
  @Synchronized
  fun put(key: K, value: V) {
    val weight = weigh(value).toLong()
    entries.remove(key)?.let { bytes -= weigh(it) }
    if (weight > maxBytes) return
    entries[key] = value
    bytes += weight
    val iterator = entries.entries.iterator()
    while (bytes > maxBytes && iterator.hasNext()) {
      val eldest = iterator.next()
      if (eldest.key == key) continue
      bytes -= weigh(eldest.value)
      iterator.remove()
    }
  }

  @Synchronized
  fun remove(key: K): V? = entries.remove(key)?.also { bytes -= weigh(it) }

  @Synchronized
  fun clear() {
    entries.clear()
    bytes = 0
  }

  @get:Synchronized
  val size: Int get() = entries.size

  /** Quanti byte tiene adesso. */
  @get:Synchronized
  val weight: Long get() = bytes

  override fun toString(): String = "ByteLru($size voci, $bytes/$maxBytes byte)"

  private companion object {
    const val INITIAL = 16
    const val LOAD = 0.75f
  }
}
