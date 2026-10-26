package dev.pampa.mycloudgallery.core.ai.chaos

import dev.pampa.mycloudgallery.core.ai.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.LocalModels
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelNasArea
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.ShelfCopy
import dev.pampa.mycloudgallery.core.models.testing.FakeModelFetcher
import dev.pampa.mycloudgallery.core.models.testing.FakeModelNetworkGate
import dev.pampa.mycloudgallery.core.models.testing.InMemoryModelShelf
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers

// Il MODELLO dentro il giro del calcolo: `LocalModels` di produzione sulla stessa cartella `models/` a ogni vita, un fetcher finto a pezzi
// (un pezzo per chiamata: la morte cade a ogni salvataggio), una copia sul NAS che a volte ha byte diversi (file cambiato a meta') e il
// server HTTPS (qui finto) che ha quelli giusti. Il calcolo parte solo se `ModelFiles.open` consegna un file verificato (invariante 1):
// e' il `modelReady` del calcolo e il guardiano del motore delle immagini. Il banco dello scaricamento vero (HTTPS e SMB) e' in core-models.

class AiChaosModel(private val plan: AiRoundPlan, private val stats: AiRoundStats, private val sick: () -> Boolean) {
  private val dir: File = Files.createTempDirectory("mcg-chaos-ai-models").toFile().apply { deleteOnExit() }
  private val bytes: ByteArray = Random(plan.seed).nextBytes(CHUNK * 3 + 777)
  val spec: ModelSpec = ModelCatalog.INDEX_440M.copy(sizeBytes = bytes.size.toLong(), sha256 = Sha256.of(bytes))
  private val fetcher = FakeModelFetcher(CHUNK)
  private val shelf = InMemoryModelShelf()
  private val calls = AtomicInteger()
  private val nas = ModelSource.NasCopy(SHARE, "nasdevice0000000000000000000")
  private var tampered = false

  /** Vero se il caricamento di QUESTA vita ha consegnato un file verificato: il motore delle immagini non gira senza. */
  @Volatile var loadedThisLife = false
    private set
  val violations: MutableList<String> = mutableListOf()

  private val dying = ModelFetcher { s, source, partial, budget ->
    val n = calls.incrementAndGet()
    val result: FetchResult = fetcher.fetch(s, source, partial, budget)
    if (sick() && n in plan.modelDeaths) {
      stats.modelDeaths++
      throw ProcessDeath()
    }
    result
  }

  init {
    fetcher.serve(ModelSource.Https(spec.https.single()), bytes)
    // Una copia sul NAS di un altro dispositivo: giusta, o con un byte cambiato (cambiata a meta', o rovinata).
    fetcher.serve(nas, if (plan.modelCopyChanged) bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2].toInt() xor 1).toByte() } else bytes)
    shelf.add(ShelfCopy(SHARE, nas.device, spec.sha256, spec.sizeBytes))
  }

  private var models = create()

  private fun create() = LocalModels(
    root = dir,
    fetcher = dying,
    networkGate = FakeModelNetworkGate(),
    shelfFor = { shelf },
    area = ModelNasArea { SHARE },
    catalog = listOf(spec),
    freeSpace = { Long.MAX_VALUE },
    io = Dispatchers.IO,
    progressPoll = Duration.ZERO,
  )

  /** Un processo nuovo: la cartella e' quella che ha lasciato il processo morto. */
  fun restart() {
    loadedThisLife = false
    models = create()
  }

  /** Porta il modello in fondo (con la ripresa) o dice "non adesso". */
  suspend fun ensure(trigger: ModelTrigger = ModelTrigger.RULES): Boolean =
    models.manager.ensure(spec.id, trigger, 5.minutes) == EnsureResult.Ready

  /** Il caricamento: consegna solo un file verificato. Controllato sul file consegnato (invariante 1). */
  suspend fun ready(): Boolean {
    stats.modelLoads++
    val model = models.files.open(spec.id)
    if (model == null) {
      loadedThisLife = false
      return false
    }
    model.use {
      if (it.descriptor == null || !it.file.isFile || it.file.length() != spec.sizeBytes || Sha256.of(it.file.readBytes()) != spec.sha256) {
        violations += "caricato un modello non verificato (dimensione o impronta diverse dal catalogo)"
      }
    }
    loadedThisLife = true
    return true
  }

  /** Un solo byte del file pronto cambia: la verifica a ogni apertura lo deve rifiutare (E-A11). */
  fun tamperOnce() {
    if (tampered || !plan.modelTamper) return
    val file = File(dir, spec.sha256.hex).takeIf { it.isFile } ?: return
    tampered = true
    file.setWritable(true) // il modello verificato e' in sola lettura (I1)
    RandomAccessFile(file, "rw").use {
      it.seek(10)
      val old = it.read()
      it.seek(10)
      it.write(old xor 1)
    }
  }

  /** Dopo ogni controllo: in `models/` solo il file intero e verificato, nessun parziale promosso. */
  fun directoryViolations(): List<String> {
    val out = ArrayList<String>()
    dir.listFiles().orEmpty().forEach { entry ->
      when {
        entry.isDirectory -> if (entry.name !in setOf("partial", "meta", "cache")) out += "cartella estranea in models/"
        entry.name != spec.sha256.hex -> out += "file estraneo in models/"
        entry.length() != spec.sizeBytes || Sha256.of(entry.readBytes()) != spec.sha256 -> out += "in models/ un file che non e' quello del catalogo"
      }
    }
    return out
  }

  fun close() {
    dir.deleteRecursively()
  }

  private companion object {
    const val SHARE = "test-comune"
    const val CHUNK = 64 * 1024
  }
}
