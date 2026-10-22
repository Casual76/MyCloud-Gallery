package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.ai.CatalogAiShareContent
import dev.pampa.mycloudgallery.ai.CatalogAiWorkCatalog
import dev.pampa.mycloudgallery.ai.FileAiUnsupported
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.index.ai.AiIndexCounters
import dev.pampa.mycloudgallery.core.index.ai.AiPublishStore
import dev.pampa.mycloudgallery.core.index.ai.AiPublishedClasses
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaSink
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaStore
import dev.pampa.mycloudgallery.core.index.ai.AiShareSync
import dev.pampa.mycloudgallery.core.index.ai.AiTraffic
import dev.pampa.mycloudgallery.core.index.ai.AiWorkSource
import dev.pampa.mycloudgallery.core.index.ai.IndexAnalysisFetcher
import dev.pampa.mycloudgallery.core.index.ai.OutboxAiIndexPublisher
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.NasRemoteIndex
import dev.pampa.mycloudgallery.core.index.publish.ShareLocks
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.work.LeasedWorkUnits
import dev.pampa.mycloudgallery.core.index.work.WorkLeases
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.prepare.contract.AnalysisSink
import dev.pampa.mycloudgallery.core.trust.data.AiClass

/**
 * Cio' che il grafo del NAS riceve per i dati AI dell'indice condiviso (Fase 6, P2 e P11): gli archivi Room (`ai_*`, P1), l'indice locale
 * di ricerca come destinazione della replica ([sink], `SearchGraph`), il traffico delle anteprime, le classi che servono **qui** ([wanted]:
 * `vec` per un indicizzatore, `ocr` solo con la lettura attiva qui, D41) e il buffer dove la preparazione consegna le anteprime degli
 * indicizzatori ([analysisSink], null se questo dispositivo non e' indicizzatore). Lo compone `AiGraph`.
 */
class NasAiHooks(
  val replicaStore: AiReplicaStore,
  val publishStore: AiPublishStore,
  val unsupported: FileAiUnsupported,
  val sink: () -> AiReplicaSink,
  val traffic: AiTraffic,
  val wanted: suspend () -> Set<AiClass>,
  val analysisSink: () -> AnalysisSink?,
  val counters: AiIndexCounters = AiIndexCounters(),
  val recipe: AiRecipe = AiRecipe.CURRENT,
)

/**
 * La parte `ai` del motore del NAS (nasce in `NasGraph.start` con account, chiave del dispositivo e aree): la replica delle teste `ai`
 * ([sync], agganciata a `IndexSyncEngine`), il pubblicatore della testa `ai` propria ([publisher], con gli stessi cancelli dell'indice:
 * F4-D31 sulle share vere = solo locale e in attesa), le unita' del calcolo con prenotazione ([units], `WorkPass.AI`), il lavoro gia' fatto
 * dagli altri ([classes]) e lo scaricamento delle anteprime attestate nel buffer ([fetcher]).
 */
class NasAiRuntime(
  val sync: AiShareSync,
  val publisher: OutboxAiIndexPublisher,
  val classes: AiPublishedClasses,
  val units: LeasedWorkUnits,
  val fetcher: IndexAnalysisFetcher,
  val work: CatalogAiWorkCatalog,
) {
  companion object {
    fun build(
      hooks: NasAiHooks,
      sync: AiShareSync,
      identity: IndexIdentity,
      nasClock: NasClock,
      fileSystem: (String) -> NasFileSystem,
      catalog: suspend () -> MediaCatalog?,
      account: suspend () -> AccountId?,
      rules: IndexWriteRules,
      locks: ShareLocks,
      leases: WorkLeases,
      remote: NasRemoteIndex,
      replicaStore: ReplicaStore,
      blobs: ReplicaBlobs,
    ): NasAiRuntime {
      val publisher = OutboxAiIndexPublisher(
        identity = identity,
        clock = nasClock,
        fileSystem = fileSystem,
        store = hooks.publishStore,
        content = CatalogAiShareContent(catalog, account),
        rules = rules,
        counters = hooks.counters,
        locks = locks,
      )
      val recipe = hooks.recipe
      val classes = AiPublishedClasses(sync, hooks.replicaStore) { cls -> if (cls == AiClass.VEC) recipe.version else recipe.ocrVersion }
      val work = CatalogAiWorkCatalog(catalog, account, hooks.replicaStore, hooks.unsupported, recipe)
      val units = LeasedWorkUnits(
        identity = identity,
        source = AiWorkSource(work, hooks.wanted),
        published = classes,
        leases = leases,
        rules = rules,
      )
      val fetcher = IndexAnalysisFetcher(remote, replicaStore, hooks.traffic, local = blobs)
      return NasAiRuntime(sync, publisher, classes, units, fetcher, work)
    }
  }
}
