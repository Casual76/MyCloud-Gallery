package dev.pampa.mycloudgallery.core.map.contract

import dev.pampa.mycloudgallery.core.model.StableCode

// IL CANCELLO DI RETE DELLA MAPPA (docs/fase9-progetto.md §1.2 "Politica", §2.8, D22, D23, D24, D26, E-MAP1-E-MAP4, E-MAP13, E-R3, E-R13;
// risposta Q1 (c) del 27/10). Contratto di P0: i fatti di una richiesta, lo stato della rete e della mappa, il verdetto, e la porta della
// decisione **pura** (`MapRequestGate`). La decisione vera e' `TilePolicy` di P1 (policy/), chiamata dall'intercettore di `MapTileHttp`
// (feature-map/.../net/, l'UNICO codice di rete della fase) per **ogni** richiesta e per ogni redirect; il riferimento e' `ReferenceTilePolicy`
// (testing/) e le regole sono la suite `TilePolicyContract` (proprieta' su richieste casuali). Il null-object rifiuta tutto.
//
// Con Q1 (c) la mappa online e' **accesa di default** su ogni dispositivo; il cancello resta per intero: un solo host, solo `GET` HTTPS sulla 443,
// solo il TileJSON e le tessere dell'URL stabile fino a z14, niente con la mappa spenta (qui, o per l'interruttore di build), niente in roaming o
// sui dati mobili se "Solo sul Wi-Fi", mai una tessera di una zona scaricata (`COVERED`), niente cookie, `Referer` o identificativi (li toglie
// l'intercettore). La mini-mappa delle info non passa mai di qui: il suo stile non ha la sorgente online (D29).

/** La rete in uso (porta `MapNetworkState`, legata a core-network): Wi-Fi a consumo o con pagina d'accesso = [MOBILE] o [NONE] (E-R3, E-R13). */
enum class MapNetwork(override val code: String) : StableCode {
  WIFI("wifi"),
  MOBILE("mobile"),
  ROAMING("roaming"),

  /** Nessuna rete, o rete non validata. */
  NONE("none"),
}

/** I fatti di una richiesta come li vede l'intercettore (`HttpUrl` gia' normalizzato: host minuscolo, porta effettiva). Mai nei log. */
data class MapRequestFacts(
  val method: String,
  val scheme: String,
  val host: String,
  val port: Int,
  val path: String,
  val query: String?,
  val userInfo: String? = null,
  val fragment: String? = null,
) {
  override fun toString(): String = "MapRequestFacts($method)"
}

/** Le tessere ammesse durante lo scaricamento **confermato** di una regione (D26): anche a mappa online spenta, solo quelle, solo per quell'operazione. */
class RegionDownloadScope(val includes: (TileKey) -> Boolean, val mobileConfirmed: Boolean) {
  override fun toString(): String = "RegionDownloadScope(mobile=$mobileConfirmed)"
}

/**
 * Lo stato che decide (§2.8): [online] = `FeatureFlags.mapOnline` **e** `MapOnline.On` su questo dispositivo; [network] la rete in uso;
 * [mobileAllowed] = "Solo sul Wi-Fi" spento; [covered] la tessera sta in una regione offline **completa** (`MapRegions.covers`); [currentVersion]
 * la versione corrente del server dal TileJSON in cache (null = non nota: prima il TileJSON); [download] lo scaricamento confermato in corso.
 */
class MapNetState(
  val online: Boolean,
  val network: MapNetwork,
  val mobileAllowed: Boolean,
  val covered: (TileKey) -> Boolean,
  val currentVersion: String?,
  val download: RegionDownloadScope? = null,
) {
  override fun toString(): String = "MapNetState(online=$online, ${network.code}, mobile=$mobileAllowed)"
}

/** Perche' una richiesta non esce (Diagnostica `MAP_REFUSED`, per motivo): un `HOST` o un `PATH` diverso da zero e' un difetto da segnalare. */
enum class RefuseReason(override val code: String) : StableCode {
  /** Mappa online spenta (qui o per l'interruttore di build). */
  OFF("off"),

  /** Roaming, nessuna rete, rete non validata, dati mobili con "Solo sul Wi-Fi". */
  NETWORK("network"),

  /** Metodo, schema, host, porta, utente o frammento fuori dalle regole. */
  HOST("host"),

  /** Percorso o query fuori dalle regole (livello oltre 14, x e y fuori dai limiti, altro). */
  PATH("path"),

  /** Tessera coperta da una regione offline completa: MapLibre usa quella del suo database. */
  COVERED("covered"),

  /** Redirect verso un altro host o fuori dalle regole (E-MAP4): lo decide l'intercettore richiamando la decisione. */
  REDIRECT("redirect"),

  /** Tipo di contenuto inatteso o risposta oltre [MapResponseRules.MAX_BYTES] (E-MAP4): lo decide l'intercettore. */
  CONTENT("content"),
}

/** Il verdetto su una richiesta. */
sealed interface MapVerdict {
  /** Esce, con il percorso [path] (riscritto sulla versione corrente per le tessere, D22). */
  data class Allow(val path: String) : MapVerdict {
    override fun toString(): String = "MapVerdict.Allow"
  }

  /** Una tessera ammessa ma la versione corrente non e' nota: prima il TileJSON, poi si richiede (non e' un rifiuto). */
  data object VersionFirst : MapVerdict

  data class Refuse(val reason: RefuseReason) : MapVerdict
}

/** La decisione su ogni richiesta della mappa (D23): **pura**, la stessa per l'intercettore, i test di proprieta' e il banco. */
fun interface MapRequestGate {
  fun decide(req: MapRequestFacts, state: MapNetState): MapVerdict
}

/** L'unico host, i percorsi e la versione ammessi (D22). */
object MapHost {
  const val TILES: String = "tiles.openfreemap.org"
  const val SCHEME: String = "https"
  const val PORT: Int = 443
  const val METHOD: String = "GET"

  /** Il TileJSON (al piu' una volta al giorno, poi in cache). */
  const val TILEJSON_PATH: String = "/planet"

  /** L'URL **stabile** delle tessere nello stile: l'intercettore lo riscrive sulla versione corrente. */
  const val STABLE_TILE_PREFIX: String = "/planet/v/"

  const val MAX_ZOOM: Int = 14

  /** Una versione del server come entra in un percorso: lettere, cifre, `_`, `.`, `-`, al piu' 64 (niente `/`, niente `..`). */
  val VERSION: Regex = Regex("^(?!\\.\\.?$)[A-Za-z0-9_.-]{1,64}$")

  /** Una tessera dell'URL stabile, forma canonica (niente zeri davanti). */
  val STABLE_TILE: Regex = Regex("^/planet/v/(0|[1-9][0-9]?)/(0|[1-9][0-9]{0,4})/(0|[1-9][0-9]{0,4})\\.pbf$")

  /** Il percorso di una tessera sulla versione [version]. */
  fun versionedPath(version: String, tile: TileKey): String = "/planet/$version/${tile.z}/${tile.x}/${tile.y}.pbf"
}

/** Le risposte ammesse (D23, E-MAP4): tipi di contenuto e dimensione. Le applica l'intercettore. */
object MapResponseRules {
  const val MAX_BYTES: Long = 2L * 1024 * 1024
  val TILE_TYPES: Set<String> = setOf("application/x-protobuf", "application/vnd.mapbox-vector-tile")
  val TILEJSON_TYPES: Set<String> = setOf("application/json")

  /** Se una risposta di tipo [contentType] (senza parametri, minuscolo) e lunga [bytes] (null = non detto) e' accettabile per [tile] o per il TileJSON. */
  fun accepts(tile: Boolean, contentType: String?, bytes: Long?): Boolean {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    val allowed = if (tile) TILE_TYPES else TILEJSON_TYPES
    return type in allowed && (bytes == null || bytes in 0..MAX_BYTES)
  }
}
