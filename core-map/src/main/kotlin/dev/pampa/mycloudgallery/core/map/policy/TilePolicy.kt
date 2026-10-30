package dev.pampa.mycloudgallery.core.map.policy

import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapRequestFacts
import dev.pampa.mycloudgallery.core.map.contract.MapRequestGate
import dev.pampa.mycloudgallery.core.map.contract.MapVerdict
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.TileKey

/**
 * IL CANCELLO DI RETE DELLA MAPPA (Fase 9, P1; `docs/fase9-progetto.md` §2.8, D22, D23, D24, D26; E-MAP1, E-MAP2, E-MAP4, E-MAP13, E-R3, E-R13;
 * risposta Q1 (c) del 27/10). La funzione **pura** che decide ogni richiesta che MapLibre (o il TileJSON, o un redirect) vorrebbe fare: la
 * chiama l'intercettore di `MapTileHttp` (feature-map/.../net/, l'unico codice di rete della fase) per **ogni** richiesta e per **ogni**
 * redirect, prima di aprire qualunque socket; la stessa funzione la usano i test di proprieta' e il banco. Nessuno stato, nessun orologio, nessuna
 * rete: i fatti della richiesta e lo stato della mappa entrano, il verdetto esce.
 *
 * Le regole, nell'ordine (il primo controllo che non passa decide il motivo, come la suite `TilePolicyContract`):
 *  1. **Chi** ([RefuseReason.HOST]): metodo esattamente `GET`, schema `https`, host esattamente `tiles.openfreemap.org` (minuscolo, senza
 *     punto finale: l'intercettore passa l'host gia' normalizzato e un host "simile" non e' quello), porta 443, nessun utente, nessun frammento;
 *  2. **Cosa** ([RefuseReason.PATH]): nessuna query; il TileJSON `/planet` oppure una tessera dell'URL **stabile** `/planet/v/{z}/{x}/{y}.pbf` in
 *     forma canonica (cifre ASCII, niente zeri davanti, niente segni, niente barre o punti in piu'), `z` fino a 14, `x` e `y` dentro `0..2^z-1`;
 *  3. **Durante uno scaricamento confermato** (D26, [MapNetState.download]): il TileJSON e le tessere della regione passano anche a mappa online
 *     spenta e anche se coperte (aggiornare una regione), sul Wi-Fi o sui dati mobili con la seconda conferma; mai in roaming o senza rete;
 *  4. **Altrimenti**: [RefuseReason.OFF] a mappa online spenta; [RefuseReason.NETWORK] in roaming, senza rete o rete non validata, o sui dati
 *     mobili con "Solo sul Wi-Fi"; [RefuseReason.COVERED] per una tessera di una regione offline completa (MapLibre usa quella del database);
 *  5. il TileJSON esce cosi'; una tessera esce sul percorso della **versione corrente** del server (D22, E-MAP13: la cache e le regioni restano
 *     legate all'URL stabile), o [MapVerdict.VersionFirst] se la versione non e' nota o non e' una versione valida (un TileJSON ostile non entra in
 *     un percorso).
 *
 * Cio' che resta all'intercettore (perche' non e' una domanda sulla richiesta ma sulla risposta): tipi e dimensioni ([RefuseReason.CONTENT]),
 * i redirect (richiama questa funzione sull'indirizzo nuovo e rifiuta con [RefuseReason.REDIRECT] se non passa), gli header ripuliti.
 *
 * `TilePolicy` passa la suite di contratto con 100.000 richieste casuali e coincide con il riferimento (`ReferenceTilePolicy`) su ogni richiesta
 * (`TilePolicyTest`). Mai nei log: i fatti e la tessera dicono la zona guardata (D33).
 */
object TilePolicy : MapRequestGate {

  override fun decide(req: MapRequestFacts, state: MapNetState): MapVerdict {
    if (!fromTheOnlyHost(req)) return MapVerdict.Refuse(RefuseReason.HOST)
    if (!req.query.isNullOrEmpty()) return MapVerdict.Refuse(RefuseReason.PATH)
    val tileJson = req.path == MapHost.TILEJSON_PATH
    val tile = if (tileJson) null else stableTile(req.path) ?: return MapVerdict.Refuse(RefuseReason.PATH)

    val download = state.download
    val inDownload = download != null && (tile == null || download.includes(tile))
    val network = when {
      inDownload -> networkFor(state.network, mobileAllowed = download!!.mobileConfirmed)
      !state.online -> return MapVerdict.Refuse(RefuseReason.OFF)
      else -> networkFor(state.network, mobileAllowed = state.mobileAllowed)
    }
    if (!network) return MapVerdict.Refuse(RefuseReason.NETWORK)
    if (!inDownload && tile != null && state.covered(tile)) return MapVerdict.Refuse(RefuseReason.COVERED)

    if (tile == null) return MapVerdict.Allow(MapHost.TILEJSON_PATH)
    val version = state.currentVersion
    if (version == null || !MapHost.VERSION.matches(version)) return MapVerdict.VersionFirst
    return MapVerdict.Allow(MapHost.versionedPath(version, tile))
  }

  /** La tessera di un percorso dell'URL stabile in forma canonica, o null (z oltre 14, x o y fuori dai limiti, qualunque altra forma). */
  fun stableTile(path: String): TileKey? {
    if (!path.startsWith(MapHost.STABLE_TILE_PREFIX) || !path.endsWith(TILE_SUFFIX)) return null
    val parts = path.substring(MapHost.STABLE_TILE_PREFIX.length, path.length - TILE_SUFFIX.length).split('/')
    if (parts.size != 3) return null
    val z = canonical(parts[0], maxDigits = 2) ?: return null
    if (z > MapHost.MAX_ZOOM) return null
    val side = 1L shl z.toInt()
    val x = canonical(parts[1], maxDigits = 5)?.takeIf { it < side } ?: return null
    val y = canonical(parts[2], maxDigits = 5)?.takeIf { it < side } ?: return null
    return TileKey(z.toInt(), x.toInt(), y.toInt())
  }

  private const val TILE_SUFFIX = ".pbf"

  private fun fromTheOnlyHost(req: MapRequestFacts): Boolean =
    req.method == MapHost.METHOD && req.scheme == MapHost.SCHEME && req.host == MapHost.TILES && req.port == MapHost.PORT &&
      req.userInfo.isNullOrEmpty() && req.fragment == null

  /** Wi-Fi si'; dati mobili solo se permessi; roaming e nessuna rete (o rete non validata) mai. */
  private fun networkFor(network: MapNetwork, mobileAllowed: Boolean): Boolean = when (network) {
    MapNetwork.WIFI -> true
    MapNetwork.MOBILE -> mobileAllowed
    MapNetwork.ROAMING, MapNetwork.NONE -> false
  }

  /** Un numero in forma canonica: solo cifre ASCII, al piu' [maxDigits], niente zeri davanti (tranne "0"). Altrimenti null. */
  private fun canonical(text: String, maxDigits: Int): Long? {
    if (text.isEmpty() || text.length > maxDigits) return null
    if (text.length > 1 && text[0] == '0') return null
    var value = 0L
    for (c in text) {
      if (c !in '0'..'9') return null
      value = value * 10 + (c - '0')
    }
    return value
  }
}
