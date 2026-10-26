package dev.pampa.mycloudgallery.core.models.testing

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelShelf
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.ModelUsage
import dev.pampa.mycloudgallery.core.models.contract.ShelfCopy
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow

// I fake dei modelli (Fase 6, P0, §5.2): in src/main per i test di P4, P5, P8, P10 e P11. Mai nella release (tools/check.ps1).
// Fedeli dove conta: [FakeModelFiles] verifica DAVVERO dimensione e SHA-256 a ogni apertura (D16, E-A11) e toglie il file sbagliato;
// [FakeModelFetcher] rispetta il tetto della dimensione fissata e la ripresa dal parziale (E-A1).

/** Lo SHA-256 di un file, a blocchi (come fara' il verificatore vero). */
fun sha256Of(file: File): Sha256 {
  val digest = MessageDigest.getInstance("SHA-256")
  FileInputStream(file).use { input ->
    val buffer = ByteArray(1 shl 16)
    while (true) {
      val n = input.read(buffer)
      if (n < 0) break
      digest.update(buffer, 0, n)
    }
  }
  return Sha256(digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) })
}

/**
 * I modelli di prova in una cartella ([root]): [install] scrive i byte di un modello finto con la SUA specifica (dimensione e impronta
 * calcolate sui byte), [open] verifica a ogni apertura e, se il file non torna (E-A11: alterato dopo la verifica), lo toglie e
 * risponde null. [opens] e [rejected] servono ai test.
 */
class FakeModelFiles(private val root: File, private val nanos: () -> Long = { System.nanoTime() }) : ModelFiles {
  private val specs = HashMap<ModelId, ModelSpec>()
  var opens: Int = 0
    private set
  var rejected: Int = 0
    private set

  /** Installa [bytes] come modello [id] e restituisce la specifica coerente con quei byte. */
  fun install(base: ModelSpec, bytes: ByteArray): ModelSpec {
    root.mkdirs()
    val spec = base.copy(sizeBytes = bytes.size.toLong(), sha256 = Sha256.of(bytes))
    File(root, spec.sha256.hex).writeBytes(bytes)
    specs[spec.id] = spec
    return spec
  }

  /** Il file di [id] (per alterarlo nei test di E-A11). */
  fun fileOf(id: ModelId): File? = specs[id]?.let { File(root, it.sha256.hex) }

  override suspend fun open(id: ModelId): VerifiedModel? {
    opens++
    val spec = specs[id] ?: return null
    val file = File(root, spec.sha256.hex)
    if (!file.isFile) return null
    if (file.length() != spec.sizeBytes || sha256Of(file) != spec.sha256) {
      rejected++
      file.delete()
      return null
    }
    return VerifiedModel(spec, file, nanos())
  }
}

/**
 * Il gestore finto: lo stato di ogni modello lo sceglie il test ([set]); [ensure] risponde [EnsureResult.Ready] se pronto,
 * altrimenti [nextEnsure] (default: rinvio in coda). Registra chiamate e trigger.
 */
class FakeModelManager(initial: Map<ModelId, ModelState> = emptyMap()) : ModelManager {
  override val states: MutableStateFlow<Map<ModelId, ModelState>> = MutableStateFlow(initial)
  val ensured: MutableList<Pair<ModelId, ModelTrigger>> = mutableListOf()
  val removed: MutableList<ModelId> = mutableListOf()
  var nextEnsure: (ModelId, ModelTrigger) -> EnsureResult = { _, trigger -> EnsureResult.Deferred(ModelState.Queued(trigger)) }

  fun set(id: ModelId, state: ModelState) {
    states.value = states.value + (id to state)
  }

  override suspend fun ensure(id: ModelId, trigger: ModelTrigger, budget: Duration): EnsureResult {
    ensured += id to trigger
    if (states.value[id] is ModelState.Ready) return EnsureResult.Ready
    val result = nextEnsure(id, trigger)
    when (result) {
      EnsureResult.Ready -> set(id, ModelState.Ready(1, ModelSourceKind.HTTPS, 0))
      is EnsureResult.Deferred -> set(id, result.state)
      is EnsureResult.Failed -> set(id, if (result.code == ModelFailCode.NETWORK_REFUSED && result.neededBytes != null) ModelState.NeedsConfirmation(result.neededBytes) else ModelState.Failed(result.code))
    }
    return result
  }

  override suspend fun remove(id: ModelId): Boolean {
    removed += id
    val had = states.value[id] is ModelState.Ready
    set(id, ModelState.Absent)
    return had
  }

  override suspend fun usage(): ModelUsage =
    ModelUsage(states.value.mapNotNull { (id, s) -> (s as? ModelState.Ready)?.let { id to it.sizeBytes } }.toMap(), 0)
}

/**
 * Una sorgente finta: i byte di ogni sorgente li sceglie il test ([serve]). Scrive in coda al parziale (ripresa, E-A1) al piu'
 * [chunk] byte per chiamata ([FetchResult.Partial] finche' non ha finito), **mai** oltre la dimensione fissata (byte in piu' =
 * [FetchResult.Mismatch]). Una sorgente senza byte = [FetchResult.NotFound].
 */
class FakeModelFetcher(private val chunk: Int = Int.MAX_VALUE) : ModelFetcher {
  private val served = HashMap<ModelSource, ByteArray>()
  val calls: MutableList<ModelSource> = mutableListOf()

  fun serve(source: ModelSource, bytes: ByteArray) {
    served[source] = bytes.copyOf()
  }

  override suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult {
    calls += source
    val bytes = served[source] ?: return FetchResult.NotFound
    // Un file di un'altra lunghezza non e' il modello fissato (piu' lungo o piu' corto): come il fetcher vero, prima di leggere un byte (R13).
    if (bytes.size.toLong() != spec.sizeBytes) return FetchResult.SizeMismatch
    partial.parentFile?.mkdirs()
    val have = if (partial.isFile) partial.length().toInt() else 0
    if (have > bytes.size) return FetchResult.Mismatch
    val n = minOf(chunk, bytes.size - have)
    partial.appendBytes(bytes.copyOfRange(have, have + n))
    val now = have + n
    return if (now.toLong() == spec.sizeBytes) FetchResult.Done else FetchResult.Partial(now.toLong())
  }
}

/** Lo scaffale finto: copie per (share, dispositivo, impronta), scrittura esclusiva, [pruneOwn] solo sul dispositivo [ownDevice]. */
class InMemoryModelShelf(val ownDevice: String = "fakedevice00000000000000000") : ModelShelf {
  private val copies = LinkedHashSet<ShelfCopy>()
  var blocked: String? = null

  /** Una copia di un altro dispositivo (per i test della sorgente NAS). */
  fun add(copy: ShelfCopy) {
    copies += copy
  }

  override suspend fun publish(spec: ModelSpec, share: String): ShelfResult {
    blocked?.let { return ShelfResult.Blocked(it) }
    val copy = ShelfCopy(share, ownDevice, spec.sha256, spec.sizeBytes)
    return if (copies.add(copy)) ShelfResult.Published else ShelfResult.AlreadyThere
  }

  /** I dispositivi del registro firmato (I5): se impostato, le copie di altri non elencati non sono sorgenti. Nullo = chiunque tranne noi. */
  var trusted: Set<String>? = null

  /** Come lo scaffale vero (I5): mai le copie proprie (il file l'ha gia' il dispositivo) e solo i dispositivi fidati. */
  override suspend fun copies(share: String, sha256: Sha256): List<ShelfCopy> =
    copies.filter {
      it.share.equals(share, ignoreCase = true) && it.sha256 == sha256 && it.device != ownDevice && (trusted?.contains(it.device) ?: true)
    }

  override suspend fun pruneOwn(share: String, keep: Set<Sha256>): Int {
    val gone = copies.filter { it.device == ownDevice && it.share.equals(share, ignoreCase = true) && it.sha256 !in keep }
    copies.removeAll(gone.toSet())
    return gone.size
  }
}

/** Il cancello di rete finto: il verdetto lo sceglie il test. */
class FakeModelNetworkGate(var verdict: ModelNetworkVerdict = ModelNetworkVerdict.Allowed) : ModelNetworkGate {
  val asked: MutableList<ModelTrigger> = mutableListOf()

  /** Le sorgenti per cui si e' chiesto il verdetto (R11: vale per ogni sorgente che usa la rete, non solo HTTPS). */
  val askedFor: MutableList<ModelSourceKind> = mutableListOf()

  override suspend fun verdict(trigger: ModelTrigger, bytes: Long, source: ModelSourceKind): ModelNetworkVerdict {
    asked += trigger
    askedFor += source
    return verdict
  }
}

/** I codici che un test si aspetta di vedere quando un modello manca (D35). */
val MISSING_MODEL_CODES: Set<ModelFailCode> = setOf(ModelFailCode.FETCH_FAILED, ModelFailCode.NETWORK_REFUSED)
