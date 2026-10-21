package dev.pampa.mycloudgallery.core.models.support

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.LocalModels
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelNasArea
import dev.pampa.mycloudgallery.core.models.contract.ModelShelf
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.testing.FakeModelFetcher
import dev.pampa.mycloudgallery.core.models.testing.FakeModelNetworkGate
import dev.pampa.mycloudgallery.core.models.testing.InMemoryModelShelf
import dev.pampa.mycloudgallery.core.models.testing.sha256Of
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers

/**
 * Il banco di prova del gestore: una cartella `models/` vera su disco, un fetcher finto (o uno scelto), uno scaffale finto, un cancello
 * di rete finto, uno spazio libero e due orologi (monotono per i tempi, "NAS" per i giorni) che decide il test.
 */
class ModelRig(
  val bytes: ByteArray = TestModels.bytes(),
  val dir: File = TestModels.tempDir(),
  chunk: Int = Int.MAX_VALUE,
  fetcher: ModelFetcher? = null,
  val spec: ModelSpec = TestModels.spec(bytes),
  extra: List<ModelSpec> = emptyList(),
) {
  val share = "test-comune"
  val device: String = TrustIds.random()
  val fake = FakeModelFetcher(chunk)
  val shelf = InMemoryModelShelf(ownDevice = TrustIds.random())
  val gate = FakeModelNetworkGate()
  val monotonic = AtomicLong(1_000L)
  val nasMillis = AtomicLong(1_700_000_000_000L)

  @Volatile var area: String? = share
  @Volatile var freeBytes: Long = Long.MAX_VALUE

  val https: ModelSource.Https get() = ModelSource.Https(spec.https.single())
  val pc = ModelSource.PcFolder(share)
  val nas = ModelSource.NasCopy(share, device)
  val bundled: ModelSource? = spec.bundledAsset?.let { ModelSource.Bundled(it) }

  val models = LocalModels(
    root = dir,
    fetcher = fetcher ?: fake,
    networkGate = gate,
    shelfFor = { shelf },
    area = ModelNasArea { area },
    catalog = listOf(spec) + extra,
    freeSpace = { freeBytes },
    clock = NasClock { nasMillis.get() },
    io = Dispatchers.IO,
    nanos = { monotonic.get() },
    progressPoll = Duration.ZERO,
  )
  val manager get() = models.manager
  val files get() = models.files
  val counters get() = models.counters

  val finalFile: File get() = File(dir, spec.sha256.hex)
  val partial: File get() = File(dir, "partial/${spec.sha256.hex}.part")

  /** Annuncia sul NAS finto la copia del dispositivo [device] (della dimensione giusta) e fa servire a [fake] questi byte. */
  fun nasCopy(content: ByteArray = bytes, size: Long = bytes.size.toLong(), deviceId: String = device) {
    shelf.add(dev.pampa.mycloudgallery.core.models.contract.ShelfCopy(share, deviceId, spec.sha256, size))
    fake.serve(ModelSource.NasCopy(share, deviceId), content)
  }

  fun stateOfModel(): ModelState = manager.states.value[spec.id] ?: ModelState.Absent

  /** Scrive il modello verificato direttamente (una cartella come l'ha lasciata un processo precedente). */
  fun installFinal(content: ByteArray = bytes) {
    dir.mkdirs()
    finalFile.writeBytes(content)
  }

  fun installPartial(content: ByteArray) {
    partial.parentFile.mkdirs()
    partial.writeBytes(content)
  }
}

/** Un fetcher che avanza l'orologio monotono di [step] per ogni chiamata e poi lascia fare a [inner]. */
class TickingFetcher(private val inner: ModelFetcher, private val clock: AtomicLong, private val stepNanos: Long) : ModelFetcher {
  override suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult {
    clock.addAndGet(stepNanos)
    return inner.fetch(spec, source, partial, budget)
  }
}

/** Un fetcher che per alcune sorgenti cade dopo N chiamate riuscite (NetworkError), per provare la ripresa da un'altra sorgente. */
class FlakyFetcher(private val inner: ModelFetcher, private val failAfter: Map<ModelSource, Int>) : ModelFetcher {
  private val counts = HashMap<ModelSource, Int>()

  override suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult {
    val limit = failAfter[source]
    val n = counts.merge(source, 1, Int::plus)!!
    if (limit != null && n > limit) return FetchResult.NetworkError
    return inner.fetch(spec, source, partial, budget)
  }
}

/** L'invariante 2 del §5.5: in `models/` stanno solo file con dimensione e SHA-256 del catalogo (piu' le sottocartelle di servizio). */
fun assertModelsDirInvariant(root: File, catalog: List<ModelSpec>) {
  val bySha = catalog.associateBy { it.sha256.hex }
  root.listFiles().orEmpty().forEach { entry ->
    if (entry.isDirectory) {
      check(entry.name in setOf("partial", "meta", "cache")) { "Cartella estranea in models/: ${entry.name}" }
    } else {
      val spec = bySha[entry.name] ?: error("File estraneo in models/: ${entry.name}")
      check(entry.length() == spec.sizeBytes) { "Dimensione sbagliata in models/: ${entry.name}" }
      check(sha256Of(entry) == Sha256(entry.name)) { "Impronta sbagliata in models/: ${entry.name}" }
    }
  }
}
