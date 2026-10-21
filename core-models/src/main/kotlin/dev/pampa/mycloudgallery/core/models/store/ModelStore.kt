package dev.pampa.mycloudgallery.core.models.store

import dev.pampa.mycloudgallery.core.models.contract.ModelCounters
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

// La CARTELLA dei modelli di questo dispositivo (docs/fase6-progetto.md §2.1, D16, D20): `noBackupFilesDir/models/`, fuori dai backup,
// una per profilo Android. Qui sta la geografia dei file e lo stato che il resto dell'app vede.
//
//   models/<sha256>                 il modello VERIFICATO (nome = impronta del catalogo): in questa cartella non entra altro
//   models/partial/<sha256>.part    lo scaricamento in corso (e, accanto, `.part.etag`: il validatore di una ripresa)
//   models/meta/<sha256>            da dove e' arrivato il modello (una riga: il tipo di sorgente); `meta/strikes`: gli errori delle sorgenti
//   models/cache/<sha256>-<backend> i programmi compilati dei motori (li scrive core-ai, li toglie questa classe quando il modello cambia)
//
// Invariante 2 del §5.5: in `models/` stanno solo file con dimensione e SHA-256 del catalogo. Un file arriva li' soltanto con una
// rinomina atomica da `partial/`, dopo la verifica del file finito; a ogni apertura si verifica di nuovo (E-A11).

internal class ModelStore(
  val root: File,
  val catalog: List<ModelSpec>,
  val nanos: () -> Long,
  val counters: ModelCounters,
) {
  /** Una sola modifica ai file alla volta: promozione, verifica di un'apertura, rimozione, pulizia. Mai tenuta durante lo scaricamento. */
  val lock = Mutex()

  private val byId: Map<ModelId, ModelSpec> = catalog.associateBy { it.id }
  private val shas: Set<String> = catalog.map { it.sha256.hex }.toSet()

  private val mutableStates = MutableStateFlow(initialStates())
  val states: StateFlow<Map<ModelId, ModelState>> get() = mutableStates

  init {
    require(byId.size == catalog.size) { "Due modelli con lo stesso id." }
    require(shas.size == catalog.size) { "Due modelli con la stessa impronta." }
  }

  fun spec(id: ModelId): ModelSpec? = byId[id]

  fun finalFile(spec: ModelSpec): File = File(root, spec.sha256.hex)

  fun partialFile(spec: ModelSpec): File = File(File(root, PARTIAL_DIR), spec.sha256.hex + PART_SUFFIX)

  private fun metaFile(spec: ModelSpec): File = File(File(root, META_DIR), spec.sha256.hex)

  fun strikesFile(): File = File(File(root, META_DIR), STRIKES)

  fun stateOf(id: ModelId): ModelState = mutableStates.value[id] ?: ModelState.Absent

  fun set(id: ModelId, state: ModelState) {
    mutableStates.update { it + (id to state) }
  }

  /** Da dove e' arrivato il modello (D20). Se la nota manca o non si legge: HTTPS (la sorgente di norma; solo un'etichetta, mai una scelta). */
  fun readSource(spec: ModelSpec): ModelSourceKind =
    try {
      metaFile(spec).takeIf { it.isFile && it.length() < 64 }?.readText(Charsets.US_ASCII)?.trim()
        ?.let { name -> ModelSourceKind.entries.firstOrNull { it.name == name } }
    } catch (e: IOException) {
      null
    } ?: ModelSourceKind.HTTPS

  fun writeSource(spec: ModelSpec, kind: ModelSourceKind) {
    try {
      val file = metaFile(spec)
      file.parentFile?.mkdirs()
      file.writeText(kind.name, Charsets.US_ASCII)
    } catch (e: IOException) {
      // Solo un'etichetta per la pagina: il modello c'e' lo stesso.
    }
  }

  /** I programmi compilati dei motori di un modello (`cache/<sha256>-<backend>/`). */
  private fun cacheDirs(spec: ModelSpec): List<File> =
    File(root, CACHE_DIR).listFiles { f -> f.isDirectory && f.name.startsWith(spec.sha256.hex + "-") }?.toList().orEmpty()

  /** Toglie il file verificato, la nota e i programmi compilati. True se il file verificato non c'e' piu'. */
  fun deleteVerified(spec: ModelSpec): Boolean {
    cacheDirs(spec).forEach { it.deleteRecursively() }
    metaFile(spec).delete()
    val file = finalFile(spec)
    file.delete()
    return !file.exists()
  }

  /** Toglie il parziale, il suo validatore e la nota di provenienza. */
  fun deletePartial(spec: ModelSpec) {
    val partial = partialFile(spec)
    partial.delete()
    File(partial.path + ETAG_SUFFIX).delete()
    File(partial.path + SOURCE_SUFFIX).delete()
  }

  /** Le note accanto a un parziale che non c'e' (la sorgente non ha dato niente) non servono a nessuno: si tolgono. */
  fun tidyPartialNotes(spec: ModelSpec) {
    val partial = partialFile(spec)
    if (partial.exists()) return
    File(partial.path + ETAG_SUFFIX).delete()
    File(partial.path + SOURCE_SUFFIX).delete()
  }

  /** Chi ha scritto per ultimo il parziale (una riga con il tipo di sorgente): serve a dire da dove arriva un file finito dopo una ripartenza. */
  fun writePartialSource(spec: ModelSpec, kind: ModelSourceKind) {
    try {
      val partial = partialFile(spec)
      partial.parentFile?.mkdirs()
      File(partial.path + SOURCE_SUFFIX).writeText(kind.name, Charsets.US_ASCII)
    } catch (e: IOException) {
      // Solo un'etichetta.
    }
  }

  fun readPartialSource(spec: ModelSpec): ModelSourceKind? =
    try {
      File(partialFile(spec).path + SOURCE_SUFFIX).takeIf { it.isFile && it.length() < 64 }?.readText(Charsets.US_ASCII)?.trim()
        ?.let { name -> ModelSourceKind.entries.firstOrNull { it.name == name } }
    } catch (e: IOException) {
      null
    }

  /** Quanti byte occupa il file verificato (0 se manca). */
  fun verifiedBytes(spec: ModelSpec): Long = finalFile(spec).takeIf { it.isFile }?.length() ?: 0

  /** Quanti byte occupano i parziali (i file `.part`; le note accanto sono di pochi byte e non contano). */
  fun partialBytes(): Long =
    File(root, PARTIAL_DIR).listFiles()?.filter { it.isFile && it.name.endsWith(PART_SUFFIX) }?.sumOf { it.length() } ?: 0

  /** Sposta il parziale verificato al suo posto: rinomina atomica, il file verificato non e' mai a meta'. */
  fun promote(spec: ModelSpec) {
    val partial = partialFile(spec)
    val target = finalFile(spec)
    Files.deleteIfExists(target.toPath())
    root.mkdirs()
    Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    File(partial.path + ETAG_SUFFIX).delete()
  }

  /**
   * Un modello che l'app non elenca piu' si toglie da solo (D20): ogni file, parziale, nota e cartella di programmi compilati il cui
   * nome e' un'impronta (e solo quelli) che non e' nel catalogo. Il resto della cartella non si tocca.
   */
  fun removeUnlisted() {
    root.listFiles()?.forEach { f -> if (f.isFile && SHA.matches(f.name) && f.name !in shas) f.delete() }
    File(root, PARTIAL_DIR).listFiles()?.forEach { f ->
      val sha = PARTIAL_NAME.matchEntire(f.name)?.groupValues?.get(1)
      if (f.isFile && sha != null && sha !in shas) f.delete()
    }
    File(root, META_DIR).listFiles()?.forEach { f -> if (f.isFile && SHA.matches(f.name) && f.name !in shas) f.delete() }
    File(root, CACHE_DIR).listFiles()?.forEach { f ->
      val sha = CACHE_NAME.matchEntire(f.name)?.groupValues?.get(1)
      if (f.isDirectory && sha != null && sha !in shas) f.deleteRecursively()
    }
  }

  private fun initialStates(): Map<ModelId, ModelState> =
    catalog.associate { spec ->
      // Un file della dimensione giusta non e' ancora "pronto": nessuno l'ha verificato in questo processo (`refresh()`, `ensure` o `open`).
      val present = finalFile(spec).let { it.isFile && it.length() == spec.sizeBytes }
      spec.id to (if (present) ModelState.Verifying else ModelState.Absent)
    }

  companion object {
    const val PARTIAL_DIR: String = "partial"
    const val META_DIR: String = "meta"
    const val CACHE_DIR: String = "cache"
    const val PART_SUFFIX: String = ".part"
    const val ETAG_SUFFIX: String = ".etag"
    const val SOURCE_SUFFIX: String = ".src"
    const val STRIKES: String = "strikes"

    private val SHA = Regex("^[0-9a-f]{64}$")
    private val PARTIAL_NAME = Regex("^([0-9a-f]{64})\\.part(\\.etag|\\.src)?$")
    private val CACHE_NAME = Regex("^([0-9a-f]{64})-.+$")
  }
}
