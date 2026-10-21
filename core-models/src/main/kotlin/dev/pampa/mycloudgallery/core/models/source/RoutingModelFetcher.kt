package dev.pampa.mycloudgallery.core.models.source

import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import java.io.File
import kotlin.time.Duration

/** Il fetcher unico che il gestore chiama: smista per tipo di sorgente (NAS e cartella dal PC, HTTPS, asset dell'APK). */
class RoutingModelFetcher(
  private val nas: ModelFetcher,
  private val https: ModelFetcher,
  private val bundled: ModelFetcher,
) : ModelFetcher {
  override suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult =
    when (source) {
      is ModelSource.NasCopy, is ModelSource.PcFolder -> nas.fetch(spec, source, partial, budget)
      is ModelSource.Https -> https.fetch(spec, source, partial, budget)
      is ModelSource.Bundled -> bundled.fetch(spec, source, partial, budget)
    }
}
