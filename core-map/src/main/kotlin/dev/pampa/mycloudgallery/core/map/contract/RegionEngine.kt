package dev.pampa.mycloudgallery.core.map.contract

// IL MOTORE DEGLI SCARICAMENTI DELLE REGIONI (Fase 9, P5; docs/fase9-progetto.md §2.9, D26). La porta fra il gestore delle regioni (`core.map.regions.OfflineMapRegions`, JVM puro: stati, limiti,
// rete, ripresa) e `OfflineManager` di MapLibre (`feature-map/.../regions/MapLibreRegionEngine`, l'unico codice che lo nomina). Cosi' i limiti di OpenFreeMap, la ripresa e gli stati si provano
// sulla JVM con un motore finto (`FakeRegionEngine`, testing/), e il motore vero si prova sull'emulatore con un server finto. Le richieste di rete le fa sempre il client delle tessere
// dietro il cancello (`feature-map/.../net/`): il motore decide **cosa** scaricare, mai **come** uscire. Una regione si riconosce dalla sua chiave (l'id): niente nei log.

/** Perche' il motore non ha potuto cominciare o ha rinunciato (il gestore lo traduce in un [RegionStop]). */
enum class RegionEngineFailure {
  /** La libreria o il suo database non sono pronti (la cartella `map/` non si e' creata, il database non si e' spostato): si riprova all'apertura. */
  NOT_READY,

  /** Il server ha risposto male a lungo. */
  SERVER,

  /** Qualunque altro guasto. */
  OTHER,
}

/** Gli eventi di uno scaricamento, dal motore al gestore (da un thread qualsiasi). Nessuna tessera, nessuna zona: solo numeri. */
interface RegionEngineListener {
  /** [completedTiles] tessere gia' nel database (anche quelle di una ripresa) su [requiredTiles] richieste (0 = non ancora noto). */
  fun onProgress(completedTiles: Long, requiredTiles: Long)

  /** Tutte le tessere della regione sono nel database. */
  fun onComplete()

  fun onFailure(failure: RegionEngineFailure)
}

interface RegionEngine {

  /**
   * Comincia (o riprende) lo scaricamento del riquadro [box] da z[minZoom] a z[maxZoom] per la regione [key]: se la regione esiste gia' nel database la riprende (le tessere
   * arrivate valgono, E-MAP11), altrimenti la crea. Le richieste passano dal cancello, con lo scaricamento confermato in corso (`MapNetWiring.downloadScope`).
   */
  suspend fun start(key: String, box: GeoBox, minZoom: Int, maxZoom: Int, listener: RegionEngineListener)

  /** Ferma lo scaricamento di [key]; cio' che e' arrivato resta nel database. */
  suspend fun stop(key: String)

  /** Toglie la regione [key] dal database (e le sue tessere, se nessun'altra le richiede). */
  suspend fun delete(key: String)

  /** Toglie tutte le regioni dal database (l'azzeramento, D37). */
  suspend fun deleteAll()
}
