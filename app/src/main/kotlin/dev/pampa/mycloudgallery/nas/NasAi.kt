package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.ai.CatalogAiShareContent
import dev.pampa.mycloudgallery.ai.CatalogAiWorkCatalog
import dev.pampa.mycloudgallery.ai.ContentFacts
import dev.pampa.mycloudgallery.ai.FileAiUnsupported
import dev.pampa.mycloudgallery.core.ai.compute.AiOriginBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.index.ai.AiAttestation
import dev.pampa.mycloudgallery.core.index.ai.AiCompactionMemory
import dev.pampa.mycloudgallery.core.index.ai.AiIndexCounters
import dev.pampa.mycloudgallery.core.index.ai.AiKey
import dev.pampa.mycloudgallery.core.index.ai.AiOwnBodies
import dev.pampa.mycloudgallery.core.index.ai.AiPublishStore
import dev.pampa.mycloudgallery.core.index.ai.AiPublishedClasses
import dev.pampa.mycloudgallery.core.index.ai.AiRemoteIndex
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaSink
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaStore
import dev.pampa.mycloudgallery.core.index.ai.AiShareContent
import dev.pampa.mycloudgallery.core.index.ai.AiShareSync
import dev.pampa.mycloudgallery.core.index.ai.AiTraffic
import dev.pampa.mycloudgallery.core.index.ai.AiWinner
import dev.pampa.mycloudgallery.core.index.ai.AiWorkSource
import dev.pampa.mycloudgallery.core.index.ai.IndexAnalysisFetcher
import dev.pampa.mycloudgallery.core.index.ai.OutboxAiIndexPublisher
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.NasRemoteIndex
import dev.pampa.mycloudgallery.core.index.publish.ShareLocks
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.sync.DeviceStandings
import dev.pampa.mycloudgallery.core.index.work.LeasedWorkUnits
import dev.pampa.mycloudgallery.core.index.work.WorkLeases
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.prepare.contract.AnalysisSink
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem

/**
 * Cio' che il grafo del NAS riceve per i dati AI dell'indice condiviso (Fase 6, P2 e P11): gli archivi Room (`ai_*`, P1), l'indice locale
 * di ricerca come destinazione della replica ([sink], `SearchGraph`), il traffico delle anteprime, le classi che servono **qui** ([wanted]:
 * `vec` per un indicizzatore, `ocr` solo con la lettura attiva qui, D41) e il buffer dove la preparazione consegna le anteprime degli
 * indicizzatori ([analysisSink], null se questo dispositivo non e' indicizzatore). Lo compone `AiGraph`.
 *
 * Revisione A: [buffer] (le anteprime con la provenienza, R5), [ownBodies] e [compactions] (la compattazione delle teste `ai` proprie,
 * R14), e la ricetta nota per la fusione (R16).
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
  /** Cio' che si elenca per il calcolo (chiave di contenuto -> media, versione, sorgente), per le porte che ricevono solo la chiave (P11b). */
  val facts: ContentFacts? = null,
  /** R5: il buffer degli indicizzatori, che dimentica le anteprime di un dispositivo revocato. */
  val buffer: AiOriginBuffer? = null,
  /** R14: i corpi che questo dispositivo sostiene ancora per le proprie voci (l'indice locale). */
  val ownBodies: AiOwnBodies? = null,
  /** R14: quando si e' compattata ogni share (un file di `noBackupFilesDir`). */
  val compactions: AiCompactionMemory = AiCompactionMemory.inMemory(),
) {
  /** R16: la ricetta piu' alta che quest'app conosce per ogni classe (quella corrente). */
  fun knownRecipe(cls: AiClass): Int = if (cls == AiClass.VEC) recipe.version else recipe.ocrVersion

  private val forgetting = ForgettingSink(sink, this)

  /**
   * La replica delle teste `ai` (P2) con le regole della revisione A: ricette oltre quella nota ridotte (R16) e, quando un dispositivo si
   * dimentica (revocato, perso, sconosciuto), via anche cio' che ne deriva qui: calcoli sulle sue anteprime (indice locale), anteprime nel
   * buffer, segni "non supportato", voci proprie da compattare (R5, R14).
   */
  fun shareSync(
    remote: AiRemoteIndex,
    attestation: AiAttestation,
    standings: DeviceStandings,
    listDevices: (suspend (String) -> RemoteResult<List<String>>)?,
    startRound: () -> Unit,
  ): AiShareSync = AiShareSync(
    remote = remote,
    store = replicaStore,
    attestation = attestation,
    sink = forgetting,
    standings = standings,
    counters = counters,
    listDevices = listDevices,
    startRound = startRound,
    knownRecipe = ::knownRecipe,
  )

  /** Il pubblicatore di adesso (per chiedere una compattazione dopo una revoca): lo lega [NasAiRuntime.build]. */
  @Volatile internal var publisher: OutboxAiIndexPublisher? = null
}

/** L'indice locale come destinazione della replica, con cio' che una revoca deve togliere in piu' (R5, R14). */
private class ForgettingSink(private val target: () -> AiReplicaSink, private val hooks: NasAiHooks) : AiReplicaSink {
  override suspend fun apply(share: String, winners: List<AiWinner>) = target().apply(share, winners)

  override suspend fun withdraw(share: String, keys: List<AiKey>) = target().withdraw(share, keys)

  override suspend fun forgetDevice(device: String) {
    target().forgetDevice(device)
    hooks.unsupported.forgetDevice(device)
    hooks.buffer?.forgetDevice(device)
    hooks.publisher?.requestCompaction()
  }

  override suspend fun forgetShare(share: String) = target().forgetShare(share)
}

/**
 * La parte `ai` del motore del NAS (nasce in `NasGraph.start` con account, chiave del dispositivo e aree): la replica delle teste `ai`
 * ([sync], agganciata a `IndexSyncEngine`), il pubblicatore della testa `ai` propria ([publisher], con gli stessi cancelli dell'indice:
 * F4-D31 sulle share vere = solo locale e in attesa), le unita' del calcolo con prenotazione ([units], `WorkPass.AI`), il lavoro gia' fatto
 * dagli altri ([classes]) e lo scaricamento delle anteprime attestate nel buffer ([fetcher]). [preparer] e' quello della preparazione (uno per
 * processo: le decodifiche pesanti restano una alla volta) e prepara le anteprime dai byte del telefono (D10, P11b); [content] e' la regola M3
 * ([CatalogAiShareContent]) per chi vuole sapere se una chiave e' di un file di una share prima di metterla nell'outbox.
 */
class NasAiRuntime(
  val sync: AiShareSync,
  val publisher: OutboxAiIndexPublisher,
  val classes: AiPublishedClasses,
  val units: LeasedWorkUnits,
  val fetcher: IndexAnalysisFetcher,
  val work: CatalogAiWorkCatalog,
  val preparer: Preparer,
  val content: AiShareContent,
  /** Il cancello di scrittura dell'indice (F4-D31 e gli altri): lo scaffale dei modelli sul NAS (D18) lo applica prima di scrivere o togliere una copia. */
  val rules: IndexWriteRules,
) {
  companion object {
    /** XS-2: al piu' cosi' tanti mesi con media chiesti passano davanti nella finestra del calcolo (la finestra e' di 48 mesi). */
    const val PRIORITY_MONTHS: Int = 16

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
      preparer: Preparer,
    ): NasAiRuntime {
      val content = CatalogAiShareContent(catalog, account)
      val publisher = OutboxAiIndexPublisher(
        identity = identity,
        clock = nasClock,
        fileSystem = fileSystem,
        store = hooks.publishStore,
        content = content,
        rules = rules,
        counters = hooks.counters,
        locks = locks,
        // R14: le teste proprie si ricontrollano una volta al giorno, e subito dopo una revoca.
        ownBodies = hooks.ownBodies,
        compactions = hooks.compactions,
        compactEveryMillis = OutboxAiIndexPublisher.DEFAULT_COMPACT_EVERY_MILLIS,
      )
      hooks.publisher = publisher
      val recipe = hooks.recipe
      val classes = AiPublishedClasses(sync, hooks.replicaStore) { cls -> if (cls == AiClass.VEC) recipe.version else recipe.ocrVersion }
      // IX-6: anche cio' che e' gia' in fila per la share conta come a posto.
      val work = CatalogAiWorkCatalog(catalog, account, hooks.replicaStore, hooks.unsupported, recipe, hooks.facts, hooks.publishStore)
      val units = LeasedWorkUnits(
        identity = identity,
        source = AiWorkSource(work, hooks.wanted),
        published = classes,
        leases = leases,
        rules = rules,
        // PR-3: un'unita' con risultati ancora nell'outbox non si rilascia (un altro indicizzatore li rifarebbe).
        unpublished = publisher::hasPending,
        // XS-2: i mesi con media chiesti dall'utente non riempiono mai la finestra: il resto e' dei mesi ordinari.
        maxPriorityMonthsPerCall = PRIORITY_MONTHS,
      )
      // R5: le anteprime di questo dispositivo sono proprie; quelle degli altri arrivano al buffer con share e dispositivo.
      val fetcher = IndexAnalysisFetcher(remote, replicaStore, hooks.traffic, local = blobs, selfDevice = identity.deviceId)
      return NasAiRuntime(sync, publisher, classes, units, fetcher, work, preparer, content, rules)
    }
  }
}
