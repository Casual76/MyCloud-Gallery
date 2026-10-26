package dev.pampa.mycloudgallery.core.models.chaos

import dev.pampa.mycloudgallery.core.models.LocalModels
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCounters
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelNasArea
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelShelf
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfDevices
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfGate
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import dev.pampa.mycloudgallery.core.models.download.https.HttpsModelFetcher
import dev.pampa.mycloudgallery.core.models.shelf.NasModelShelf
import dev.pampa.mycloudgallery.core.models.source.NasModelFetcher
import dev.pampa.mycloudgallery.core.models.source.RoutingModelFetcher
import dev.pampa.mycloudgallery.core.models.support.FileBehavior
import dev.pampa.mycloudgallery.core.models.support.HttpsTestServer
import dev.pampa.mycloudgallery.core.models.support.TestConnections
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.support.serve
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers

// Il MONDO di un giro: un modello di prova (byte dal seme, specifica coerente), una cartella `models/` vera su disco, il server HTTPS di
// prova del JDK (condiviso fra i giri: lo possiede il banco), il NAS (in memoria o SMB) e i guasti decisi dal piano. Le "vite" del
// processo sono `LocalModels` nuovi sulla stessa cartella; il mondo sopravvive alla loro morte, come il disco.

/** La morte del processo: non un errore "normale", un'eccezione che nessuno prende (come in ModelManagerCrashTest). */
// Un `Error`: il gestore prende ogni `Exception` (R10), ma la morte del processo non e' un guasto da raccontare.
class ChaosModelDied : Error("processo morto (prova delle interruzioni)")

/** Il processo con cui il banco lavora: un gestore, i file verificati e lo scaffale (veri, o rotti da un difetto iniettato). */
class ModelStack(val manager: ModelManager, val files: ModelFiles, val shelf: ModelShelf, val refresh: suspend () -> Unit)

fun interface ModelStackFactory {
  fun create(world: ModelChaosWorld): ModelStack

  companion object {
    /** Lo stack di produzione: `LocalModels` sui fetcher veri (NAS, HTTPS) e sullo scaffale vero. */
    val REAL = ModelStackFactory { world -> world.realStack() }
  }
}

class ModelChaosWorld(val plan: ModelRoundPlan, private val server: HttpsTestServer, val dir: File = TestModels.tempDir("mcg-chaos")) : Closeable {
  val share = "test-comune"
  val bytes: ByteArray = Random(plan.seed).nextBytes(plan.size)
  val spec: ModelSpec = TestModels.spec(bytes)
  val device: String = TrustIds.random()
  private val otherDevice: String = TrustIds.random()
  val stats = ModelRoundStats()
  val counters = ModelCounters()

  val copyPath: SharePath = NasLayout.modelCopy(otherDevice, spec.sha256.hex)
  val pcPath: SharePath = SharePath.of(*ModelLimits.PC_FOLDER.toTypedArray(), spec.fileName)
  val finalFile: File get() = File(dir, spec.sha256.hex)
  val partialFile: File get() = File(dir, "partial/${spec.sha256.hex}.part")

  val log = ReadLog({ partialFile.takeIf { it.isFile }?.length() ?: 0L }, { spec.sizeBytes })
  val nas: ChaosNasSide = when (plan.backend) {
    ModelBackend.MEMORY -> MemoryNasSide(share, log, ::isModelFile)
    ModelBackend.SMB -> SmbNasSide(share, log, ::isModelFile)
  }

  /** Dopo [heal] nessun guasto del piano scatta piu': la rete e' tornata, il disco ha spazio. */
  @Volatile var healthy = false
    private set
  val saves = AtomicInteger()
  val fetchCalls = AtomicInteger()
  val httpRequests = AtomicInteger()
  private val noSpaceOnce = java.util.concurrent.atomic.AtomicBoolean(plan.noSpaceFirst)
  private val gateOnce = java.util.concurrent.atomic.AtomicBoolean(plan.gateRefusedFirst)
  val nasMillis = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)

  private fun isModelFile(path: SharePath) = path.name == spec.sha256.hex || path.name == spec.fileName

  init {
    if (plan.nasCopy) nas.put(copyPath, bytes)
    if (plan.pcFolder) nas.put(pcPath, bytes)
    val changed = TestModels.flipped(bytes, bytes.size / 2)
    val victim = if (plan.nasCopy) copyPath else if (plan.pcFolder) pcPath else null
    if (victim != null) plan.nasChangeAtRead?.let { nas.armChangeAtRead(it, victim, changed) }
    if (plan.nasDropPercent > 0) nas.armDrops(plan.seed, plan.nasDropPercent)
    server.handler = { exchange, request ->
      val n = httpRequests.incrementAndGet()
      val range = request.header("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
      if (range != null) {
        stats.rangedRequests++
        val expected = log.resumePoint()
        if (range != expected) log.violations += "richiesta HTTPS con Range $range, punto di ripresa $expected"
      }
      val sick = !healthy
      val ignore = sick && n in plan.ignoreRangeAt
      val etag = if (sick && plan.etagChangeAt != null && n >= plan.etagChangeAt) "\"v2\"" else "\"v1\""
      val behavior = FileBehavior(
        bytes = if (sick && n in plan.wrongBytesAt) TestModels.flipped(bytes, bytes.size - 7) else bytes,
        etag = etag,
        honorRange = !ignore,
        cutBodyAt = if (sick) plan.cutAt[n] else null,
      )
      val honored = range != null && !ignore && request.header("If-Range").let { it == null || it == etag }
      if (range != null && !honored) stats.serverRestarts++
      if (sick && plan.cutAt.containsKey(n)) stats.cuts++
      exchange.serve(behavior)
    }
  }

  /** Il traffico registrato dai fetcher dopo ogni blocco scritto e sincronizzato: li' il processo puo' morire (salvataggio n). */
  private val traffic = ModelTraffic { n ->
    stats.recordedBytes += n
    stats.savedBlocks++
    if (!healthy && saves.incrementAndGet() in plan.deathSaves) throw ChaosModelDied()
  }

  private fun fetcher(): ModelFetcher {
    val routing = RoutingModelFetcher(
      nas = NasModelFetcher(nas.reader, traffic),
      https = HttpsModelFetcher(traffic, TestConnections(server), Dispatchers.IO, System::nanoTime, connectTimeoutMs = 5_000, readTimeoutMs = 5_000),
      bundled = ModelFetcher { _, _, _, _ -> FetchResult.NotFound },
    )
    return object : ModelFetcher {
      override suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult {
        val n = fetchCalls.incrementAndGet()
        if (!healthy && n in plan.deathBeforeCalls) throw ChaosModelDied()
        val result = routing.fetch(spec, source, partial, budget)
        if (!healthy && n in plan.deathAfterCalls) throw ChaosModelDied()
        return result
      }
    }
  }

  /** Un processo di produzione nuovo sulla stessa cartella. */
  fun realStack(): ModelStack {
    val models = LocalModels(
      root = dir,
      fetcher = fetcher(),
      networkGate = ModelNetworkGate { _, _, _ -> if (gateOnce.getAndSet(false)) ModelNetworkVerdict.Refused("prova") else ModelNetworkVerdict.Allowed },
      shelfFor = { files -> NasModelShelf(device, files, nas.reader, nas.shelfWriter(device), ModelShelfGate { null }, ModelShelfDevices { setOf(otherDevice) }) },
      area = ModelNasArea { share },
      catalog = listOf(spec),
      freeSpace = { if (noSpaceOnce.getAndSet(false)) 1L else Long.MAX_VALUE },
      clock = NasClock { nasMillis.get() },
      io = Dispatchers.IO,
      nanos = System::nanoTime,
      progressPoll = Duration.ZERO,
      counters = counters,
    )
    return ModelStack(models.manager, models.files, models.shelf) { models.refresh() }
  }

  /** La rete torna e il disco ha spazio: da qui in poi nessun guasto del piano. */
  fun heal() {
    healthy = true
    nas.heal()
  }

  /** Quante interruzioni "vere" ci sono state, per il tetto dei byte riscaricati (invariante 3). */
  fun interruptions(): Int = stats.deaths + stats.cuts + nas.firedFaults

  override fun close() {
    nas.close()
    dir.deleteRecursively()
  }
}
