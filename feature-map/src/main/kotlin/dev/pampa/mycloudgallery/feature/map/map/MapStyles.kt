package dev.pampa.mycloudgallery.feature.map.map

import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import dev.pampa.mycloudgallery.feature.map.contract.MapNotice

// LO STILE E GLI AVVISI DELLA MAPPA (Fase 9, P4; docs/fase9-progetto.md §2.7, §2.8, §2.10 "Avvisi", D24, D29, E-MAP1, E-MAP2, E-MAP16; decisioni.md "Fase 9 P2").
// P2 ha consegnato quattro stili in `assets/map/`: chiaro e scuro, ognuno **online** (sorgente `openmaptiles` con l'URL stabile) e **offline** (la sola carta di
// base, nessuna sorgente vettoriale, zoom fino a 9). Qui si sceglie quale mettere, e cosa dire a chi guarda. Funzioni pure.

/** Lo stile di una mappa: il file in `assets/map/`, se e' offline (nessuna sorgente online), lo zoom massimo e il credito da mostrare sulla mappa (L430). */
data class MapStyleChoice(val asset: String, val offline: Boolean, val maxZoom: Double, val attribution: String) {
  /** L'indirizzo per MapLibre (`asset://`: nessuna richiesta). */
  val uri: String get() = "asset://map/$asset"
}

object MapStyles {
  const val ONLINE_MAX_ZOOM: Double = 17.0

  /** Lo zoom oltre cui lo stile offline non ha piu' dati (carta di base a z0-z9, P2): la mappa si ferma li'. */
  const val OFFLINE_MAX_ZOOM: Double = 9.0

  private fun file(dark: Boolean, offline: Boolean): String = (if (dark) "style-scuro" else "style-chiaro") + (if (offline) "-offline" else "") + ".json"

  /** I quattro file, nell'ordine (per i test: ognuno esiste negli asset). */
  val ALL_FILES: List<String> = listOf(false, true).flatMap { dark -> listOf(false, true).map { offline -> file(dark, offline) } }

  /**
   * Quale stile per la mappa grande. **Online** se la mappa dettagliata e' accesa (anche senza rete: la carta di base sta sotto e la cache resta) o se ci
   * sono regioni scaricate (le loro tessere sono nel database di MapLibre e il cancello non lascia uscire niente a mappa spenta); **offline** con la mappa
   * spenta (o non disponibile) e nessuna regione: cosi' non c'e' mai la terra senza mare sopra lo zoom 8.
   */
  fun choose(online: MapOnline, hasRegions: Boolean, dark: Boolean): MapStyleChoice {
    val detailed = online is MapOnline.On || hasRegions
    return if (detailed) {
      MapStyleChoice(file(dark, offline = false), offline = false, maxZoom = ONLINE_MAX_ZOOM, attribution = PlacesAttribution.MAP_ONLINE_SHORT)
    } else {
      MapStyleChoice(file(dark, offline = true), offline = true, maxZoom = OFFLINE_MAX_ZOOM, attribution = PlacesAttribution.MAP_BASE_SHORT)
    }
  }

  /**
   * Lo stile della **mini-mappa** delle info (D29, E-MAP16): **sempre** offline, qualunque cosa dica la mappa online (aprire le info di una foto non deve
   * dire a un terzo dove e' stata scattata). Non esiste una variante con la sorgente.
   */
  fun miniMap(dark: Boolean): MapStyleChoice =
    MapStyleChoice(file(dark, offline = true), offline = true, maxZoom = OFFLINE_MAX_ZOOM, attribution = PlacesAttribution.MAP_BASE_SHORT)
}

/** La riga discreta sopra la mappa (§2.10 "Avvisi"): mai un dialogo all'apertura. */
object MapNotices {

  /**
   * - **mappa dettagliata spenta** qui: "Carta semplice · Mappa dettagliata" (apre Impostazioni -> Mappa);
   * - **roaming o nessuna rete**: la carta di base e le zone scaricate (E-MAP1-E-MAP3);
   * - **dati mobili con "Solo sul Wi-Fi"**: "Mappa dettagliata solo sul Wi-Fi" (E-MAP2);
   * - altrimenti niente. Con l'interruttore di build spento la mappa dettagliata non esiste: nessuna riga che la offra.
   */
  fun of(online: MapOnline, wifiOnly: Boolean, network: MapNetwork): MapNotice? = when (online) {
    MapOnline.Unavailable -> null
    MapOnline.Off -> MapNotice.BASE_ONLY
    is MapOnline.On -> when (network) {
      MapNetwork.ROAMING, MapNetwork.NONE -> MapNotice.OFFLINE
      MapNetwork.MOBILE -> if (wifiOnly) MapNotice.WIFI_ONLY else null
      MapNetwork.WIFI -> null
    }
  }
}
