package dev.pampa.mycloudgallery.feature.map.net

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings
import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapRequestGate
import dev.pampa.mycloudgallery.core.map.contract.MapTraffic
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * IL CLIENT DELLE TESSERE (Fase 9, P1; docs/fase9-progetto.md D21, D23, §2.8, §5.5; C155). L'**unico** codice di rete della fase (la seconda
 * eccezione dichiarata dopo i modelli, Phase6ArchitectureTest regola 5, Phase9ArchitectureTest regola 4, `tools/check.ps1` sul dex): il
 * client OkHttp che MapLibre usa per ogni richiesta (`HttpRequestUtil.setOkHttpClient`, chiamato da `MapLibreInit` **prima** di
 * `MapLibre.getInstance` e di qualunque mappa).
 *
 * Come e' fatto, e perche':
 *  - **primo intercettore: il cancello** ([MapGateInterceptor]): ogni richiesta, prima del DNS e di qualunque socket, passa dalla decisione
 *    pura; la richiesta che esce e' ricostruita sull'unico host con i soli header ammessi;
 *  - **intercettore di rete** ([MapWireGuard]): l'ultimo controllo su host, porta e header prima che i byte partano; il tetto sui byte che
 *    arrivano; il registro del traffico;
 *  - **DNS** che risolve solo `tiles.openfreemap.org` ([OnlyMapHostDns]); **nessun proxy** (la sola destinazione e' l'host della mappa);
 *    **solo TLS moderno** (nessun testo in chiaro); **nessun redirect automatico** (li segue il cancello, a mano); **nessun cookie**; **nessuna
 *    cache di OkHttp** (la cache e' quella di MapLibre, nel suo database); nessun nuovo tentativo automatico;
 *  - **al piu' 4 richieste insieme** verso l'host (condizioni d'uso di OpenFreeMap per lo scaricamento offline; vale per tutto: niente
 *    raffiche, E-MAP3).
 *
 * Il client nasce pigro (alla prima mappa), mai all'avvio dell'app. Nessun log con indirizzi: quelli di MapLibre sono spenti qui.
 */
internal class MapTileHttp(
  gate: MapRequestGate,
  conditions: (currentVersion: String?) -> MapNetState,
  traffic: MapTraffic,
  network: () -> MapNetwork,
  stateFile: File?,
  clock: MapNetClock,
) {
  val counters = MapNetCounters()
  private val tileJson = TileJsonCache(stateFile, clock)
  private val gateInterceptor = MapGateInterceptor(gate, conditions, tileJson, ServerBackoff(clock), counters)
  private val wireGuard = MapWireGuard(traffic, network, counters)

  /**
   * Il client con il cancello (nasce con questo oggetto, cioe' alla prima mappa). I test strumentati ne fanno uno derivato (`newBuilder`) con DNS
   * e socket che contano. Niente `lazy`: R8 sposta le classi delle lambda dove vuole, e una lambda che restituisce un client di OkHttp in un'altra
   * classe dell'app farebbe fallire il controllo del bytecode (tools/check.ps1, passo 7h) per un motivo che non e' vero.
   */
  val client: OkHttpClient = build()

  fun snapshot(): MapNetSnapshot = counters.snapshot()

  /** Mette questo client in MapLibre (vedi [installInMapLibre]): chi chiama non vede i tipi di OkHttp (il passo 7h li cerca anche nelle firme). */
  fun install() = installInMapLibre(client)

  private fun build(): OkHttpClient =
    OkHttpClient.Builder()
      .dispatcher(Dispatcher().apply {
        maxRequests = MAX_REQUESTS
        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
      })
      .addInterceptor(gateInterceptor)
      .addNetworkInterceptor(wireGuard)
      .dns(OnlyMapHostDns())
      .proxy(Proxy.NO_PROXY)
      .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .cookieJar(CookieJar.NO_COOKIES)
      .cache(null)
      .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
      .writeTimeout(READ_SECONDS, TimeUnit.SECONDS)
      .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
      .build()

  companion object {
    const val MAX_REQUESTS = 8
    const val MAX_REQUESTS_PER_HOST = 4
    const val CONNECT_SECONDS = 10L
    const val READ_SECONDS = 15L
    const val CALL_SECONDS = 30L

    /** Il nome del file della versione del server, in `noBackupFilesDir/map/`. */
    const val STATE_FILE = "tilejson-version"

    /**
     * Mette [client] in MapLibre e spegne i log di rete di MapLibre (che stamperebbero l'indirizzo della tessera fallita). Da chiamare
     * **prima** di `MapLibre.getInstance`: da qui in poi ogni richiesta di MapLibre passa dal cancello.
     */
    fun installInMapLibre(client: OkHttpClient) {
      HttpRequestUtil.setLogEnabled(false)
      HttpRequestUtil.setPrintRequestUrlOnFailure(false)
      HttpRequestUtil.setOkHttpClient(client)
    }

    /** L'orologio monotono del telefono: il numero dell'avvio (letto una volta: in un processo non cambia) e il tempo dall'accensione. */
    fun deviceClock(resolver: ContentResolver): MapNetClock {
      val boot = try {
        Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1).toLong()
      } catch (_: SecurityException) {
        -1L
      }
      return MapNetClock { MonoStamp(boot, SystemClock.elapsedRealtime()) }
    }
  }
}
