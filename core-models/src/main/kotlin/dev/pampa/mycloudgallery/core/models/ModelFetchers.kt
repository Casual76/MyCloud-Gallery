package dev.pampa.mycloudgallery.core.models

import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.ExtraModelSources
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelHttpsRoute
import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import dev.pampa.mycloudgallery.core.models.download.https.HttpsModelFetcher
import dev.pampa.mycloudgallery.core.models.noop.NoExtraModelSources
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
   * @param extra Fase 10 (P1, D29): gli indirizzi aggiuntivi del manifest firmato (`CatalogExtraModelSources`, lo stesso oggetto passato a
   *   `LocalModels`); un indirizzo HTTPS ne' fissato ne' aggiuntivo e' rifiutato prima di aprire la connessione. Di base nessuno.
   */
  fun create(
    nas: NasMediaReader,
    assets: BundledAssets,
    traffic: ModelTraffic,
    httpsRoute: ModelHttpsRoute? = null,
    extra: ExtraModelSources = NoExtraModelSources,
  ): ModelFetcher =
    RoutingModelFetcher(
      nas = NasModelFetcher(nas, traffic),
      https = HttpsModelFetcher(traffic, route = httpsRoute),
      bundled = BundledModelFetcher(assets),
      extra = extra,
    )
}
