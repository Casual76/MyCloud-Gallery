package dev.pampa.mycloudgallery.core.models.source

import dev.pampa.mycloudgallery.core.models.contract.ExtraModelSources
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.noop.NoExtraModelSources
import java.io.File
import kotlin.time.Duration

/**
 * Il fetcher unico che il gestore chiama: smista per tipo di sorgente (NAS e cartella dal PC, HTTPS, asset dell'APK).
 *
 * Fase 10 (P1, D29, E-REL7): l'ordine delle sorgenti lo da' il gestore (copie sul NAS, indirizzi fissati del catalogo, poi gli indirizzi
 * aggiuntivi di [extra], poi la cartella dal PC); qui, prima di aprire una connessione, un indirizzo HTTPS deve essere **fissato** per questo
 * modello (`spec.https`) o **aggiuntivo adesso** (`extra.urls(spec.id, spec.sha256)`, che decade con il manifest scaduto): qualunque altro
 * indirizzo e' `Refused` senza rete. L'impronta del catalogo decide comunque dopo lo scaricamento.
 */
class RoutingModelFetcher(
  private val nas: ModelFetcher,
  private val https: ModelFetcher,
  private val bundled: ModelFetcher,
  private val extra: ExtraModelSources = NoExtraModelSources,
) : ModelFetcher {
  override suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult =
    when (source) {
      is ModelSource.NasCopy, is ModelSource.PcFolder -> nas.fetch(spec, source, partial, budget)
      is ModelSource.Https ->
        if (admitted(spec, source)) https.fetch(spec, source, partial, budget) else FetchResult.Refused("indirizzo non fissato per il modello")
      is ModelSource.Bundled -> bundled.fetch(spec, source, partial, budget)
    }

  private fun admitted(spec: ModelSpec, source: ModelSource.Https): Boolean {
    if (source.url in spec.https) return true
    return try {
      source.url in extra.urls(spec.id, spec.sha256)
    } catch (e: RuntimeException) {
      false
    }
  }
}
