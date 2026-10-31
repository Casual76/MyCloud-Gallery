package dev.pampa.mycloudgallery.feature.map.net

// LO STILE DELLO SCARICAMENTO DI UNA REGIONE (Fase 9, P5; docs/fase9-progetto.md §2.9, D26). `OfflineManager` di MapLibre scarica una regione partendo dallo **stile**: lo chiede alla sua rete (`http` o `https`, non `asset://`:
// il motore degli scaricamenti usa il client di rete, non quello degli asset) e poi chiede le tessere che lo stile nomina. Lo stile dello scaricamento e' **solo la sorgente delle tessere** (lo stesso URL stabile degli stili veri, cosi' le
// tessere nel database sono quelle che la mappa cerca; niente glifi, sprite o carta di base: stanno gia' nell'APK). Per non far uscire nemmeno questa richiesta, lo stile sta in questo file e **il cancello lo serve da se'**: un
// indirizzo che non esiste (`.invalid`, riservato: nessun DNS lo risolve) risposto dal primo intercettore del client, mai dalla rete. Se per un errore l'intercettore non lo servisse, la richiesta morirebbe al DNS
// (`OnlyMapHostDns` risolve solo l'host delle tessere): non esce niente in nessun caso.

/** Lo stile con cui `MapLibreRegionEngine` crea le regioni, e l'indirizzo (finto) con cui lo si chiede. */
internal object RegionStyleSource {

  /** Un nome riservato (`.invalid`, RFC 2606): mai risolto da nessun DNS. */
  const val HOST: String = "regione.mcg.invalid"
  const val PATH: String = "/stile.json"
  const val URL: String = "https://$HOST$PATH"

  /**
   * Una sola sorgente vettoriale (`openmaptiles`, `minzoom` 0 e `maxzoom` 14 come negli stili veri) con l'URL stabile delle tessere, e un livello di sfondo (uno stile ne vuole almeno uno). Il modello d'URL
   * **deve** restare uguale a quello degli stili veri (`MapStyleAssetsTest`, `RegionStyleTest`): e' la chiave con cui MapLibre cerca le tessere nel suo database.
   */
  const val JSON: String =
    """{"version":8,"name":"MyCloud Gallery regione (solo tessere)","sources":{"openmaptiles":{"type":"vector",""" +
      """"tiles":["https://tiles.openfreemap.org/planet/v/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14}},""" +
      """"layers":[{"id":"sfondo","type":"background","paint":{"background-color":"#000000"}}]}"""
}
