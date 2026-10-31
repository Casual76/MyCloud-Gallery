package dev.pampa.mycloudgallery.core.map.testing

import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapRequestFacts
import dev.pampa.mycloudgallery.core.map.contract.MapRequestGate
import dev.pampa.mycloudgallery.core.map.contract.MapVerdict
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.TileKey

/**
 * Il cancello di rete di riferimento (Fase 9, P0, D22-D24, D26, §2.8): le regole scritte una per una, nell'ordine dei motivi. `TilePolicy` di
 * P1 passa la stessa suite (`TilePolicyContract`, proprieta' su 100.000 richieste casuali). In `testing/`: mai nella release; il null-object
 * della release (`RefuseAllRequests`) rifiuta tutto finche' P1 non scrive quello vero.
 *
 * L'ordine (il primo che non va decide il motivo):
 *  1. `HOST`: metodo `GET`, schema `https`, host esattamente `tiles.openfreemap.org`, porta 443, nessun utente, nessun frammento;
 *  2. `PATH`: nessuna query; il TileJSON `/planet` o una tessera dell'URL stabile `/planet/v/{z}/{x}/{y}.pbf` in forma canonica, z <= 14, x e y
 *     dentro i limiti di z;
 *  3. durante uno scaricamento confermato ([MapNetState.download]): il TileJSON e le tessere della regione passano anche a mappa spenta e anche se
 *     coperte (aggiornare una regione), sul Wi-Fi o sui dati mobili con la seconda conferma; mai in roaming o senza rete (`NETWORK`);
 *  3b. (P5) con il conto dello scaricamento esaurito (`DownloadBudget`): `LIMIT` per le tessere della regione;
 *  4. altrimenti `OFF` se la mappa online e' spenta; `NETWORK` in roaming, senza rete, o sui dati mobili con "Solo sul Wi-Fi"; `COVERED` se la
 *     tessera sta in una regione completa;
 *  5. il TileJSON esce cosi'; una tessera esce sul percorso della versione corrente, o `VersionFirst` se la versione non e' nota (o non e' una
 *     versione valida: un TileJSON ostile non entra in un percorso).
 */
class ReferenceTilePolicy : MapRequestGate {

  override fun decide(req: MapRequestFacts, state: MapNetState): MapVerdict {
    if (req.method != MapHost.METHOD || req.scheme != MapHost.SCHEME || req.host != MapHost.TILES || req.port != MapHost.PORT ||
      !req.userInfo.isNullOrEmpty() || req.fragment != null
    ) {
      return MapVerdict.Refuse(RefuseReason.HOST)
    }
    if (!req.query.isNullOrEmpty()) return MapVerdict.Refuse(RefuseReason.PATH)
    val tile: TileKey?
    if (req.path == MapHost.TILEJSON_PATH) {
      tile = null
    } else {
      tile = parseTile(req.path) ?: return MapVerdict.Refuse(RefuseReason.PATH)
    }

    val download = state.download
    val inScope = download != null && (tile == null || download.includes(tile))
    if (inScope) {
      when (state.network) {
        MapNetwork.WIFI -> Unit
        MapNetwork.MOBILE -> if (!download!!.mobileConfirmed) return MapVerdict.Refuse(RefuseReason.NETWORK)
        MapNetwork.ROAMING, MapNetwork.NONE -> return MapVerdict.Refuse(RefuseReason.NETWORK)
      }
      // P5: il conto dello scaricamento (3.000 tessere o 50 MB) e' esaurito: le tessere della regione si rifiutano.
      if (tile != null && download!!.budget?.exhausted == true) return MapVerdict.Refuse(RefuseReason.LIMIT)
    } else {
      if (!state.online) return MapVerdict.Refuse(RefuseReason.OFF)
      when (state.network) {
        MapNetwork.WIFI -> Unit
        MapNetwork.MOBILE -> if (!state.mobileAllowed) return MapVerdict.Refuse(RefuseReason.NETWORK)
        MapNetwork.ROAMING, MapNetwork.NONE -> return MapVerdict.Refuse(RefuseReason.NETWORK)
      }
      if (tile != null && state.covered(tile)) return MapVerdict.Refuse(RefuseReason.COVERED)
    }

    if (tile == null) return MapVerdict.Allow(MapHost.TILEJSON_PATH)
    val version = state.currentVersion?.takeIf { MapHost.VERSION.matches(it) } ?: return MapVerdict.VersionFirst
    return MapVerdict.Allow(MapHost.versionedPath(version, tile))
  }

  companion object {
    /** La tessera di un percorso dell'URL stabile, o null se il percorso non lo e' (forma canonica, z <= 14, x e y nei limiti). */
    fun parseTile(path: String): TileKey? {
      val match = MapHost.STABLE_TILE.matchEntire(path) ?: return null
      val z = match.groupValues[1].toInt()
      val x = match.groupValues[2].toLong()
      val y = match.groupValues[3].toLong()
      if (z > MapHost.MAX_ZOOM) return null
      val side = 1L shl z
      if (x !in 0 until side || y !in 0 until side) return null
      return TileKey(z, x.toInt(), y.toInt())
    }
  }
}
