package dev.pampa.mycloudgallery.core.models.store

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelCounters
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
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
//   models/partial/<sha256>.part    lo scaricamento in corso (e, accanto, `.part.etag`: il validatore di una ripresa; `.part.src`: ogni
//                                   sorgente che ha scritto nel parziale, R13)
//   models/meta/<sha256>            da dove e' arrivato il modello (una riga: il tipo di sorgente); `meta/<sha256>.removed`: l'utente l'ha
//                                   tolto con "Rimuovi" e i lavori in background non lo riprendono (MO-3); `meta/strikes`: gli errori delle sorgenti
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

  // --- "Rimuovi" dura (MO-3) -----------------------------------------------------------------------------------------------

  private fun removedFile(spec: ModelSpec): File = File(File(root, META_DIR), spec.sha256.hex + REMOVED_SUFFIX)

  /** L'utente ha tolto questo modello con "Rimuovi": i lavori automatici non lo riscaricano finche' non lo chiede lui (o accende il ruolo). */
  fun removedByUser(spec: ModelSpec): Boolean = removedFile(spec).isFile

  fun setRemovedByUser(spec: ModelSpec, removed: Boolean) {
    try {
      val file = removedFile(spec)
      if (removed) {
        StateFiles.writeText(file, "1", Charsets.US_ASCII)
      } else if (!StateFiles.delete(file)) {
        // Il segno "tolto dall'utente" che resta terrebbe fermi i lavori automatici anche dopo "Scarica ora": non si da' per tolto.
        throw IOException("segno di rimozione non tolto")
      }
    } catch (e: IOException) {
      // Solo una preferenza: senza il segno il giro periodico riprende il modello, come prima (l'errore e' nel log di `StateFiles`).
    }
  }

  fun writeSource(spec: ModelSpec, kind: ModelSourceKind) {
    try {
      StateFiles.writeText(metaFile(spec), kind.name, Charsets.US_ASCII)
    } catch (e: IOException) {
      // Solo un'etichetta per la pagina: il modello c'e' lo stesso.
    }
  }

  /**
   * I programmi compilati dei motori di un modello (`cache/<sha256>-<backend>/`) e i piccoli file accanto che li riguardano (la memoria di
   * una GPU che ha fatto cadere il processo, `cache/<sha256>-gpu.trial` e `.crashes`, R9): vanno via insieme al modello.
   */
  private fun cacheDirs(spec: ModelSpec): List<File> =
    File(root, CACHE_DIR).listFiles { f -> f.name.startsWith(spec.sha256.hex + "-") }?.toList().orEmpty()

  /** Toglie il file verificato, la nota e i programmi compilati. True se il file verificato non c'e' piu'. */
  fun deleteVerified(spec: ModelSpec): Boolean {
    cacheDirs(spec).forEach { it.deleteRecursively() }
    StateFiles.delete(metaFile(spec))
    StateFiles.delete(File(root, "meta/${spec.sha256.hex}.checked"))
    val file = finalFile(spec)
    deleteFile(file)
    return !file.exists()
  }

  /** Toglie un file anche se e' in sola lettura (il modello verificato lo e': su Windows, dove girano i test, non si toglie altrimenti). */
  private fun deleteFile(file: File) {
    if (!file.exists()) return
    if (!file.delete()) {
      file.setWritable(true)
      file.delete()
    }
  }

  /** Toglie il parziale, il suo validatore e la nota di provenienza. */
  fun deletePartial(spec: ModelSpec) {
    val partial = partialFile(spec)
    partial.delete()
    StateFiles.delete(File(partial.path + ETAG_SUFFIX))
    StateFiles.delete(File(partial.path + SOURCE_SUFFIX))
  }

  /** Le note accanto a un parziale che non c'e' (la sorgente non ha dato niente) non servono a nessuno: si tolgono. */
  fun tidyPartialNotes(spec: ModelSpec) {
    val partial = partialFile(spec)
    if (partial.exists()) return
    StateFiles.delete(File(partial.path + ETAG_SUFFIX))
    StateFiles.delete(File(partial.path + SOURCE_SUFFIX))
  }

  /**
   * Chi ha scritto nel parziale (R13): una riga per sorgente, nell'ordine in cui hanno scritto, `<tipo> <identita'>` (l'identita' e' quella con
   * cui si contano gli errori della sorgente, mai un indirizzo ne' un nome). Serve a dire da dove arriva un file finito dopo una ripartenza e,
   * soprattutto, **a chi dare la colpa** se non torna: byte sbagliati letti a meta' dalla cartella dal PC e completati da HTTPS non sono
   * colpa di Hugging Face. Una sorgente gia' in coda alla nota non si ripete.
   */
  fun writePartialSource(spec: ModelSpec, writer: PartialWriter) {
    try {
      val partial = partialFile(spec)
      partial.parentFile?.mkdirs()
      val note = File(partial.path + SOURCE_SUFFIX)
      val lines = readWriterLines(note)
      val line = writer.line()
      if (lines.lastOrNull() == line) return
      StateFiles.writeText(note, (lines + line).takeLast(MAX_WRITERS).joinToString("\n"), Charsets.US_ASCII)
    } catch (e: IOException) {
      // Solo un'etichetta: senza la nota nessuno ha la colpa di niente (si riprova da zero senza segnare errori).
    }
  }

  /** Tutte le sorgenti che hanno scritto nel parziale, dalla prima. Una nota vecchia (solo il tipo) vale una sorgente di identita' ignota. */
  fun readPartialWriters(spec: ModelSpec): List<PartialWriter> =
    readWriterLines(File(partialFile(spec).path + SOURCE_SUFFIX)).mapNotNull { PartialWriter.parse(it) }

  fun readPartialSource(spec: ModelSpec): ModelSourceKind? = readPartialWriters(spec).lastOrNull()?.kind

  private fun readWriterLines(note: File): List<String> =
    try {
      if (note.isFile && note.length() < 1024) note.readText(Charsets.US_ASCII).lines().filter { it.isNotBlank() } else emptyList()
    } catch (e: IOException) {
      emptyList()
    }

  /** Quanti byte occupa il file verificato (0 se manca). */
  fun verifiedBytes(spec: ModelSpec): Long = finalFile(spec).takeIf { it.isFile }?.length() ?: 0

  /** Quanti byte occupano i parziali (i file `.part`; le note accanto sono di pochi byte e non contano). */
  fun partialBytes(): Long =
    File(root, PARTIAL_DIR).listFiles()?.filter { it.isFile && it.name.endsWith(PART_SUFFIX) }?.sumOf { it.length() } ?: 0

  /**
   * Sposta il parziale verificato al suo posto: rinomina atomica, il file verificato non e' mai a meta'. Il file verificato e' **in sola
   * lettura** (D16, I1): una scrittura per sbaglio dell'app sul modello verificato non passa inosservata.
   */
  fun promote(spec: ModelSpec) {
    val partial = partialFile(spec)
    val target = finalFile(spec)
    deleteFile(target)
    root.mkdirs()
    Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    StateFiles.delete(File(partial.path + ETAG_SUFFIX))
    target.setReadOnly()
  }

  /**
   * Un modello che l'app non elenca piu' si toglie da solo (D20): ogni file, parziale, nota e cartella di programmi compilati il cui
   * nome e' un'impronta (e solo quelli) che non e' nel catalogo. Il resto della cartella non si tocca.
   */
  fun removeUnlisted() {
    root.listFiles()?.forEach { f -> if (f.isFile && SHA.matches(f.name) && f.name !in shas) deleteFile(f) }
    File(root, PARTIAL_DIR).listFiles()?.forEach { f ->
      val sha = PARTIAL_NAME.matchEntire(f.name)?.groupValues?.get(1)
      if (f.isFile && sha != null && sha !in shas) f.delete()
    }
    File(root, META_DIR).listFiles()?.forEach { f ->
      val sha = META_NAME.matchEntire(f.name)?.groupValues?.get(1)
      if (f.isFile && sha != null && sha !in shas) f.delete()
    }
    File(root, CACHE_DIR).listFiles()?.forEach { f ->
      val sha = CACHE_NAME.matchEntire(f.name)?.groupValues?.get(1)
      if (sha != null && sha !in shas) f.deleteRecursively()
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
    const val REMOVED_SUFFIX: String = ".removed"
    const val STRIKES: String = "strikes"

    /** Quante sorgenti diverse ricorda la nota di un parziale (sono al piu' tre o quattro: NAS, HTTPS, cartella dal PC). */
    private const val MAX_WRITERS = 8

    private val SHA = Regex("^[0-9a-f]{64}$")
    private val META_NAME = Regex("^([0-9a-f]{64})(\\.(removed|checked))?$")
    private val PARTIAL_NAME = Regex("^([0-9a-f]{64})\\.part(\\.etag|\\.src)?$")
    private val CACHE_NAME = Regex("^([0-9a-f]{64})-.+$")
  }
}

/**
 * Una sorgente che ha scritto nel parziale (R13): il tipo (per dire "da dove arriva") e l'**identita'** con cui si contano gli errori
 * (`nas:<dispositivo>`, `https:<host>`, `pc`, `bundled`): mai un indirizzo intero, un percorso o un nome di file. Identita' ignota = una nota
 * scritta prima di questa regola: non si puo' dare la colpa a nessuno.
 */
internal data class PartialWriter(val kind: ModelSourceKind, val id: String) {
  fun line(): String = "${kind.name} $id"

  companion object {
    private val ID = Regex("^[A-Za-z0-9._:-]{1,120}$")

    const val UNKNOWN = "?"

    fun of(source: ModelSource): PartialWriter =
      when (source) {
        is ModelSource.NasCopy -> PartialWriter(ModelSourceKind.NAS, "nas:${source.device}")
        is ModelSource.Https -> PartialWriter(ModelSourceKind.HTTPS, "https:${source.url.host}")
        is ModelSource.PcFolder -> PartialWriter(ModelSourceKind.PC_FOLDER, "pc")
        is ModelSource.Bundled -> PartialWriter(ModelSourceKind.BUNDLED, "bundled")
      }

    fun parse(line: String): PartialWriter? {
      val parts = line.trim().split(' ')
      val kind = ModelSourceKind.entries.firstOrNull { it.name == parts[0] } ?: return null
      val id = parts.getOrNull(1)?.takeIf { ID.matches(it) } ?: UNKNOWN
      return PartialWriter(kind, id)
    }
  }
}
