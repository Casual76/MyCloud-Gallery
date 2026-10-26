package dev.pampa.mycloudgallery.core.models

import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelHttpsRoute
import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import dev.pampa.mycloudgallery.core.models.download.https.HttpsModelFetcher
import dev.pampa.mycloudgallery.core.models.download.https.NetworkBoundConnections
import dev.pampa.mycloudgallery.core.models.download.https.PlatformConnections
import dev.pampa.mycloudgallery.core.models.source.BundledModelFetcher
import dev.pampa.mycloudgallery.core.models.source.NasModelFetcher
import dev.pampa.mycloudgallery.core.models.source.RoutingModelFetcher
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader

/**
 * L'unico modo in cui il resto dell'app ottiene il fetcher dei modelli (D33): le classi di `download/https/` sono `internal`, qui si
 * montano insieme a quelle del NAS e degli asset. Sta nel pacchetto radice del modulo perche' nessun altro modulo puo' nominare il
 * pacchetto `download` (Phase6ArchitectureTest).
 */
object ModelFetchers {
  /**
   * @param nas la lettura del NAS (`NasSessionManager.mediaReader()`): copie dei dispositivi e cartella dal PC.
   * @param assets gli asset dell'APK (il ritaglio).
   * @param traffic dove si registrano i byte scaricati (`LedgerModelTraffic`).
   * @param httpsRoute la rete su cui escono le connessioni HTTPS (R12): quella approvata dal cancello. Senza, la rete predefinita del sistema.
   */
  fun create(nas: NasMediaReader, assets: BundledAssets, traffic: ModelTraffic, httpsRoute: ModelHttpsRoute? = null): ModelFetcher =
    RoutingModelFetcher(
      nas = NasModelFetcher(nas, traffic),
      https = HttpsModelFetcher(traffic, httpsRoute?.let { NetworkBoundConnections(it) } ?: PlatformConnections),
      bundled = BundledModelFetcher(assets),
    )
}
