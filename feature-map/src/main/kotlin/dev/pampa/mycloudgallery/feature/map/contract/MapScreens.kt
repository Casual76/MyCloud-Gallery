package dev.pampa.mycloudgallery.feature.map.contract

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.map.contract.CellKey
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.RegionEstimate
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode

// Gli STATI delle schermate della mappa (docs/fase9-progetto.md §1.5, D24, D28-D30, D34; risposta Q1 (c) del 27/10). Contratto di P0: la Fase 9
// decide COSA si mostra e QUANDO, il filone grafico decide COME (le bolle sono G3). P4 (mappa, bolle, griglia, Luoghi, pagina della mappa) e
// P5 (regioni) scrivono ViewModel e composable con i soli componenti G2. **Mai un nascosto** (punti e copertine dalle sole celle visibili e dal
// filtro finale, D31), **mai la "casa"** (nessun segno, nessuna etichetta "Casa", D29), **mai coordinate o tessere nei log** (D33).
//
// Q1 (c): la mappa online e' accesa di default; la vecchia "pagina sulla privacy prima di accendere" diventa la pagina Impostazioni -> Mappa con
// l'interruttore e una nota breve su cosa esce ([MapPrivacyState]); riaccendere dopo averla spenta passa da li' (`MapOnlineChoice`).

/** Le righe discrete sopra la mappa (§2.10 "Avvisi"): mai un dialogo all'apertura. */
enum class MapNotice(override val code: String) : StableCode {
  /** Mappa online spenta qui: "Carta semplice · Mappa dettagliata" (apre Impostazioni -> Mappa). */
  BASE_ONLY("base_only"),

  /** Dati mobili con "Solo sul Wi-Fi": "Mappa dettagliata solo sul Wi-Fi" (E-MAP2). */
  WIFI_ONLY("wifi_only"),

  /** Roaming o nessuna rete: la carta di base e le zone scaricate (E-MAP1-E-MAP3). */
  OFFLINE("offline"),
}

/** La mappa (§1.5 `MapScreenState`). */
sealed interface MapScreenState {
  data object Loading : MapScreenState

  /** [online] lo stato della mappa online qui; [notice] la riga da mostrare (null = nessuna); [withoutPosition] i media senza posizione (E-MAP5). */
  data class Ready(val online: MapOnline, val notice: MapNotice?, val withoutPosition: Int) : MapScreenState

  /** "Nessuna foto con la posizione" e quante sono senza. */
  data class Empty(val withoutPosition: Int) : MapScreenState
}

/** Una bolla sopra la mappa (§1.5 `MapBubble`, D28, G3): al piu' 60, con la miniatura locale della copertina. Etichetta da `PlaceNames`, mai "Casa". */
sealed interface MapBubble {
  val key: CellKey
  val cover: MediaId

  data class Cluster(override val key: CellKey, val count: Int, override val cover: MediaId, val label: String?) : MapBubble {
    override fun toString(): String = "MapBubble.Cluster($count, etichetta=${label != null})"
  }

  data class Single(override val key: CellKey, override val cover: MediaId) : MapBubble {
    override fun toString(): String = "MapBubble.Single"
  }
}

/** La griglia di una zona o di un luogo (§1.5 `ZoneGridState`): viva sull'istantanea visibile, dal piu' recente; un tocco apre il viewer. */
sealed interface ZoneGridState {
  /** [label] il nome del luogo (null = "Questa zona"). */
  class Ready(val label: String?, val view: OrderedTimelineView) : ZoneGridState {
    override fun toString(): String = "ZoneGridState.Ready(${view.size})"
  }

  data object Empty : ZoneGridState
}

/** Un luogo della sezione Luoghi: id GeoNames, nome gia' risolto (mai "casa"), quante foto. */
data class PlaceRow(val placeId: Int, val label: String, val count: Int) {
  override fun toString(): String = "PlaceRow($count)"
}

/** Raccolte -> Luoghi (§1.5 `PlacesSectionState`, D30): la riga "Mappa" con quante foto hanno una posizione, fino a 8 luoghi, i senza posizione. */
sealed interface PlacesSectionState {
  data class Ready(val withPosition: Int, val places: List<PlaceRow>, val withoutPosition: Int) : PlacesSectionState {
    init {
      require(places.size <= MAX_PLACES) { "Al piu' $MAX_PLACES luoghi nella sezione." }
    }

    override fun toString(): String = "PlacesSectionState.Ready($withPosition, ${places.size}, senza=$withoutPosition)"

    companion object {
      const val MAX_PLACES: Int = 8
    }
  }

  /** Nessuna foto con la posizione: la riga dice quante sono senza. */
  data class NoGps(val withoutPosition: Int) : PlacesSectionState
}

/**
 * Impostazioni -> Mappa (§1.5 `MapPrivacyState`, adeguato a Q1 (c)): l'interruttore della mappa dettagliata con la nota breve (versione
 * [noteVersion]: "OpenFreeMap riceve la zona che guardi e l'indirizzo della connessione, mai le tue foto ne' la loro posizione"), "Solo sul
 * Wi-Fi", la voce "Zone senza Internet".
 */
sealed interface MapPrivacyState {
  data class Shown(val online: MapOnline, val wifiOnly: Boolean, val noteVersion: Int, val regions: Int) : MapPrivacyState

  /** L'interruttore di build e' spento: la pagina dice che la mappa dettagliata non c'e' in questa versione. */
  data object Unavailable : MapPrivacyState
}

/** Le regioni offline (§1.5 `RegionsState`, D26). */
sealed interface RegionsState {
  data class Catalog(val catalog: List<RegionSpec>, val mine: List<RegionState>) : RegionsState {
    override fun toString(): String = "RegionsState.Catalog(${catalog.size}, ${mine.size})"
  }

  /** Il foglio "Scarica «Toscana»? Circa 250 MB, sul Wi-Fi. OpenFreeMap vedra' quale zona scarichi, una volta." */
  data class Estimate(val spec: RegionSpec, val estimate: RegionEstimate, val freeBytes: Long) : RegionsState {
    override fun toString(): String = "RegionsState.Estimate(${estimate.bytes})"
  }

  data class Downloading(val region: RegionId, val percent: Int) : RegionsState

  data class Partial(val region: RegionId, val percent: Int) : RegionsState

  data class Ready(val region: RegionId, val bytes: Long, val ageDays: Long) : RegionsState

  data class NeedsUpdate(val region: RegionId) : RegionsState
}
