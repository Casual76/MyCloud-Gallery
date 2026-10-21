package dev.pampa.mycloudgallery.core.models

import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import dev.pampa.mycloudgallery.core.models.download.https.HttpsModelFetcher
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
   */
  fun create(nas: NasMediaReader, assets: BundledAssets, traffic: ModelTraffic): ModelFetcher =
    RoutingModelFetcher(
      nas = NasModelFetcher(nas, traffic),
      https = HttpsModelFetcher(traffic),
      bundled = BundledModelFetcher(assets),
    )
}
