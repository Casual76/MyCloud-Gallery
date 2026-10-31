package dev.pampa.mycloudgallery.map

import android.content.Context
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.dao.MapPointsQueries
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapNetworkState
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import dev.pampa.mycloudgallery.core.map.contract.MapTraffic
import dev.pampa.mycloudgallery.core.map.points.MapPointPager
import dev.pampa.mycloudgallery.core.map.points.MapPointRaw
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import dev.pampa.mycloudgallery.ai.trafficNetworkOf
import dev.pampa.mycloudgallery.feature.map.contract.MapEngineStarter
import dev.pampa.mycloudgallery.feature.map.map.MapLibreInit
import dev.pampa.mycloudgallery.feature.map.map.MapNetWiring
import dev.pampa.mycloudgallery.feature.settings.map.MapEntryState
import dev.pampa.mycloudgallery.feature.settings.map.MapEntryStatus
import dev.pampa.mycloudgallery.feature.settings.map.MapEntrySummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// LE PORTE DELLA MAPPA LEGATE A CIO' CHE L'APP HA GIA' (Fase 9, P13; docs/fase9-progetto.md §1.4 `MapGraph`, §2.8, D23, D27; "per P13" di P1, P3 e P4 in
// docs/decisioni.md). Nessuna fa rete: la rete della mappa sta tutta in `feature-map/.../net/` (Phase9ArchitectureTest), e qui si decide solo **quando**
// MapLibre parte (mai all'avvio) e che cosa il cancello sa della rete. Nessuna porta porta coordinate fuori dal telefono; nei log solo nomi di classi.

/**
 * La query dei punti di core-database come pagine di core-map (P3: "una riga per metodo"): le colonne dette per codice tornano enum con lo stesso
 * `StableCode` del catalogo. Le sole celle visibili le sceglie la query (`MapPointsQueries`, P3-2).
 */
class CatalogMapPointPager(database: GalleryDatabase) : MapPointPager {
  private val queries = MapPointsQueries(database)

  override suspend fun page(after: MediaId?, limit: Int): List<MapPointRaw> = queries.page(after, limit).map {
    MapPointRaw(MediaId(it.mediaId), it.latitude, it.longitude, it.localDay, it.cellFlags, byCode<MediaSource>(it.source), byCode<MediaKind>(it.kind), it.durationMs, it.placeId)
  }

  override suspend fun countWithoutPosition(): Int = queries.countWithoutPosition()

  private inline fun <reified E> byCode(code: String): E where E : Enum<E>, E : StableCode = enumValues<E>().first { it.code == code }

  override fun toString(): String = "CatalogMapPointPager"
}

/**
 * La rete in uso per la mappa, da core-network (E-MAP2, E-R3, E-R13): nessuna rete o una rete **non validata** e' [MapNetwork.NONE]; il roaming
 * prima di tutto; una rete cellulare **o a consumo** (anche un Wi-Fi a consumo, E-R13) e' [MapNetwork.MOBILE]; il resto [MapNetwork.WIFI].
 */
class AppMapNetworkState(snapshot: StateFlow<NetworkSnapshot>, scope: CoroutineScope) : MapNetworkState {
  override val current: StateFlow<MapNetwork> = snapshot.map(::networkOf).stateIn(scope, SharingStarted.Eagerly, networkOf(snapshot.value))

  companion object {
    fun networkOf(snapshot: NetworkSnapshot): MapNetwork {
      val net = snapshot.underlying ?: return MapNetwork.NONE
      return when {
        !net.validated -> MapNetwork.NONE
        net.roaming -> MapNetwork.ROAMING
        net.transport == NetTransport.CELLULAR || net.metered -> MapNetwork.MOBILE
        else -> MapNetwork.WIFI
      }
    }
  }
}

/**
 * Il traffico della mappa nel registro dell'app (D23, proposta 3 del §2.8): **contenuto visibile** ([TrafficSource.VISIBLE_CONTENT]), sulla rete che il
 * socket ha usato. Il client conta i byte arrivati una volta sola (P1, `MapWireGuard`); qui si scrivono fuori dal thread della rete. Solo byte e rete.
 */
class LedgerMapTraffic(
  /** Il registro, letto alla prima tessera (mai prima). */
  private val ledger: () -> TrafficLedger,
  private val snapshot: () -> NetworkSnapshot,
  private val scope: CoroutineScope,
) : MapTraffic {
  override fun record(bytes: Long, network: MapNetwork) {
    if (bytes <= 0) return
    val on = trafficOf(network, snapshot())
    scope.launch {
      try {
        ledger().record(TrafficSource.VISIBLE_CONTENT, on, bytes)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "traffico della mappa non registrato: ${e.javaClass.simpleName}" }
      }
    }
  }

  companion object {
    private const val TAG = "MapTraffic"

    /** La rete del registro: quella che il client ha visto, e per il Wi-Fi casa o fuori casa da core-network. */
    fun trafficOf(network: MapNetwork, snapshot: NetworkSnapshot): TrafficNetwork = when (network) {
      MapNetwork.ROAMING -> TrafficNetwork.ROAMING
      MapNetwork.MOBILE -> TrafficNetwork.MOBILE
      MapNetwork.WIFI, MapNetwork.NONE -> trafficNetworkOf(snapshot)
    }
  }
}

/**
 * L'avvio **pigro** di MapLibre (P1, P4; §1.4: "test: nessuna `MapView` nasce senza il client"): la prima mappa o mini-mappa chiama [ensure] sul thread
 * principale; qui si chiama `MapLibreInit.ensure`, che installa il nostro client delle tessere (con il cancello) **prima** di dirsi pronta, e si risponde
 * solo con `MapLibreInit.isReady`. Mai all'avvio dell'app. Un guasto (libreria nativa che non si carica, Robolectric) vale "non pronta": la pagina mostra
 * la carta come riquadro vuoto, e nessuna `MapView` nasce. [init] e [ready] sono le due funzioni vere di `MapLibreInit` (i test le sostituiscono).
 */
class AppMapEngineStarter(
  private val wiring: () -> MapNetWiring,
  private val scope: CoroutineScope,
  private val init: (Context, MapNetWiring, CoroutineScope) -> Unit = MapLibreInit::ensure,
  private val ready: () -> Boolean = { MapLibreInit.isReady },
) : MapEngineStarter {

  @Volatile private var failed = false

  override fun ensure(context: Context): Boolean {
    if (ready()) return true
    if (failed) return false
    try {
      init(context, wiring(), scope)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      failed = true
      GalleryLog.w(TAG) { "mappa non avviata: ${e.javaClass.simpleName}" }
    } catch (e: LinkageError) {
      // La libreria nativa non si carica (ABI, Robolectric): nessuna mappa, mai un crollo.
      failed = true
      GalleryLog.w(TAG) { "mappa non avviata: ${e.javaClass.simpleName}" }
    }
    return ready()
  }

  private companion object {
    const val TAG = "MapEngine"
  }
}

/** La voce "Mappa" di Impostazioni (P4, `MapEntryStatus`): la frase dello stato dalla `MapSettings.online`, mai un luogo o un numero. */
class AppMapEntryStatus(settings: MapSettings, scope: CoroutineScope) : MapEntryStatus {
  override val summary: StateFlow<MapEntrySummary> = settings.online.map(::summaryOf).stateIn(scope, SharingStarted.Eagerly, summaryOf(settings.online.value))

  companion object {
    fun summaryOf(online: MapOnline): MapEntrySummary = MapEntrySummary(
      when (online) {
        is MapOnline.On -> MapEntryState.ON
        MapOnline.Off -> MapEntryState.OFF
        MapOnline.Unavailable -> MapEntryState.UNAVAILABLE
      },
    )
  }
}
