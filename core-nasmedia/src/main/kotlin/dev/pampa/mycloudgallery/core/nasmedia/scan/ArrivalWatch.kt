package dev.pampa.mycloudgallery.core.nasmedia.scan

import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nasmedia.exclude.TemporaryNames

/**
 * La stabilita' dei file (E-N4, D9): un file nuovo o cambiato non si rileva finche' qualcuno lo sta ancora scrivendo. Si
 * decide **solo con l'ora del NAS** (`NasClock`): quella del telefono e' ~55 s indietro e l'utente la puo' spostare
 * (§4.3, D32), e una data di modifica scritta dal PC e' nell'ora del NAS.
 *
 * Un file e' **in arrivo** se:
 * - ha un nome temporaneo (`*.part`, `*.tmp`, `*.crdownload`, `~$*`): il file vero comparira' con il nome finale;
 * - e' stato modificato da meno di [STABLE_AFTER_MS] (data di modifica vicina all'ora del NAS), o ha una data nel futuro
 *   del NAS (orologio del PC sbagliato): non si puo' dire che sia finito;
 * - e' stato visto con un timbro diverso dall'elenco precedente (cresce).
 *
 * Un file che e' stato in arrivo torna stabile solo quando e' **visto due volte uguale a distanza di almeno due minuti**.
 * La memoria dei file in arrivo e' solo del processo: dopo un riavvio vale la sola data di modifica (un file ancora in
 * scrittura ha comunque una data recente). Limitata a [MAX_WATCHED] voci: oltre, i piu' vecchi si dimenticano.
 */
class ArrivalWatch(private val stableAfterMs: Long = STABLE_AFTER_MS, private val maxWatched: Int = MAX_WATCHED) {

  private class Sighting(val stamp: NasStamp, val firstSeenNas: Long)

  private val watched = object : LinkedHashMap<LocationKey, Sighting>(64, 0.75f, false) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocationKey, Sighting>?): Boolean = size > maxWatched
  }

  /** Quanti file sono tenuti d'occhio. */
  val size: Int get() = synchronized(watched) { watched.size }

  /**
   * Vero se il file [name] di posto [key], visto adesso con [stamp] a [nasNow] (ora del NAS), e' stabile e si puo' rilevare.
   */
  fun isStable(key: LocationKey, name: String, stamp: NasStamp, nasNow: Long): Boolean {
    if (TemporaryNames.isTemporary(name)) return false
    val age = nasNow - stamp.modifiedAtMillis
    synchronized(watched) {
      val before = watched[key]
      if (before != null && !before.stamp.sameAs(stamp)) {
        // Cresce, o l'hanno riscritto: si ricomincia a contare da adesso.
        watched[key] = Sighting(stamp, nasNow)
        return false
      }
      if (age in 0 until stableAfterMs) {
        if (before == null) watched[key] = Sighting(stamp, nasNow)
        return false
      }
      if (before != null) {
        if (nasNow - before.firstSeenNas < stableAfterMs) return false
        watched.remove(key)
        return true
      }
      if (age < 0) {
        // Data di modifica nel futuro del NAS: senza una seconda occhiata non si sa se e' finito.
        watched[key] = Sighting(stamp, nasNow)
        return false
      }
      return true
    }
  }

  /** Il file non interessa piu' (rilevato, sparito): si dimentica. */
  fun forget(key: LocationKey) {
    synchronized(watched) { watched.remove(key) }
  }

  companion object {
    /** Due minuti di ora del NAS (E-N4). */
    const val STABLE_AFTER_MS: Long = 2L * 60 * 1000

    const val MAX_WATCHED: Int = 10_000
  }
}
