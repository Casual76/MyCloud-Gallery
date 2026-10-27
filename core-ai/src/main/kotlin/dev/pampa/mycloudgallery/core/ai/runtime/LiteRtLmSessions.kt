package dev.pampa.mycloudgallery.core.ai.runtime

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.models.contract.FileStat
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Le SESSIONI VERE dei motori dei vettori: LiteRT-LM (`litertlm-android` 0.18.1, `EmbeddingEngine`), il solo posto di core-ai/runtime
// che la nomina (Phase6ArchitectureTest: le librerie dei motori solo in core-ai). Le classi della libreria sono bytecode Java 21: sul
// dispositivo le converte D8, sulla JVM dei test no (UnsupportedClassVersionError). Per questo qui non c'e' logica da provare sulla
// JVM: la politica sta in LiteRtLmTextEmbedder / LiteRtLmImageEmbedder (provata con sessioni finte) e questo file si prova sul
// dispositivo (core-ai/src/androidTest: suite di contratto, vettori di riferimento, ripiego).

/**
 * Misure dell'ultima apertura di una sessione (millisecondi, orologio monotono): verifica del modello (D16: dimensione e SHA-256 a ogni
 * caricamento) e inizializzazione del motore. Le legge la prova delle misure (docs/decisioni.md, P4); chi usa il motore non ne ha bisogno.
 */
class OpenTimings {
  @Volatile
  var verifyMs: Long = -1
    internal set

  @Volatile
  var initMs: Long = -1
    internal set
}

/**
 * Prove sulle sessioni: un punto in cui un test fa fallire un'inizializzazione o sporca un vettore, per provare il ripiego su CPU
 * (E-A12) con il motore vero senza una GPU rotta. Nell'app non si passa mai: [None].
 */
internal interface EngineHooks {
  fun beforeInit(backend: AiBackend) = Unit

  /** Il backend su cui il motore nativo gira davvero. La sessione si etichetta con quello chiesto; un test puo' dire "GPU" e girare su CPU. */
  fun nativeBackend(requested: AiBackend): AiBackend = requested

  fun afterEmbed(backend: AiBackend, vector: FloatArray): FloatArray = vector

  object None : EngineHooks
}

/**
 * Come il motore riceve il modello gia' verificato da `ModelFiles.open` (D16). Misurato sull'S25 (docs/decisioni.md, "Fase 6 P4"):
 * LiteRT-LM salva i programmi compilati e i pesi preparati solo quando il modello gli arriva **per percorso**; con il descrittore la
 * cartella della cache resta vuota e ogni caricamento ricompila (440M su GPU: avvio a caldo 5,1 s invece di 2,2 s; 270M su CPU: 0,77 s
 * invece di 0,39 s e 130 MB di PSS in piu'). Per questo il predefinito e' [PATH_CHECKED].
 */
enum class ModelAccess {
  /** Il descrittore verificato (`dup`), senza cache dei programmi compilati: D16 alla lettera, piu' lento e piu' pesante in memoria. */
  DESCRIPTOR,

  /**
   * Il percorso del file dell'app (`noBackupFilesDir/models/<sha256>`, **in sola lettura** dopo la verifica, privato dell'app) con la cache.
   * Il file che il motore apre per nome si confronta con lo `stat` **preso durante la verifica** sul descrittore su cui sono stati calcolati
   * dimensione e SHA-256 (`VerifiedModel.stat`: dispositivo, inode, dimensione e `ctime`, I1), **prima e dopo** l'inizializzazione, e il
   * descrittore resta aperto finche' il motore vive (cosi' l'inode non si puo' riusare): un file rinominato, sostituito o **scritto** (la
   * `ctime` cambia a ogni scrittura, anche sullo stesso inode) fra la verifica e l'uso e' rifiutato (`MODEL_UNAVAILABLE`, E-A11).
   *
   * Cosa **non** copre (C22): la cache dei programmi compilati e dei pesi preparati che il motore legge dal percorso (`cache/<sha256>-<backend>/`)
   * non e' sotto l'impronta del modello. Si ripara cosi' (R9): una cache e' valida solo se porta il segno `.complete`, scritto dopo
   * un'inizializzazione riuscita; senza il segno (morte del processo a meta' scrittura) la cartella si toglie prima di riaprire, e si toglie
   * anche a ogni inizializzazione fallita. Una GPU che fa cadere il processo lascia il segno "in prova" ([GpuTrial]) e non si riprova subito.
   */
  PATH_CHECKED,
}

internal class LiteRtLmSessions(
  private val files: ModelFiles,
  private val spec: ModelSpec,
  private val cacheRoot: File,
  private val recipe: AiRecipe,
  private val withVision: Boolean,
  private val nanos: () -> Long = System::nanoTime,
  private val hooks: EngineHooks = EngineHooks.None,
  val timings: OpenTimings = OpenTimings(),
  private val access: ModelAccess = ModelAccess.PATH_CHECKED,
) : SessionFactory {

  override suspend fun open(backend: AiBackend): VectorSession {
    val verifyStart = nanos()
    val model = files.open(spec.id) ?: throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
    timings.verifyMs = (nanos() - verifyStart) / 1_000_000
    val thread = EngineThread("mcg-${spec.role.name.lowercase()}-${backend.name.lowercase()}")
    val initStart = nanos()
    val session = try {
      // L'inizializzazione nativa non si interrompe: se il chiamante viene annullato nel frattempo, la sessione nasce comunque e si
      // chiude qui sotto (altrimenti resterebbe un motore da centinaia di MB che nessuno possiede).
      withContext(NonCancellable) {
        withContext(thread.dispatcher) { RealSession.create(model, spec, backend, withVision, cacheRoot, recipe, hooks, thread, access) }
      }
    } catch (t: Throwable) {
      model.close()
      thread.shutdown()
      throw t
    }
    timings.initMs = (nanos() - initStart) / 1_000_000
    try {
      currentCoroutineContext().ensureActive()
    } catch (cancelled: CancellationException) {
      session.close()
      throw cancelled
    }
    return session
  }
}

/**
 * Il motore caricato. Possiede: il modello verificato (si chiude per ultimo), una **copia** del suo descrittore (`dup`, D16: il motore
 * legge i byte su cui e' stata calcolata l'impronta, non un file riaperto per nome) e il thread del motore.
 */
private class RealSession(
  private val engine: EmbeddingEngine,
  private val model: VerifiedModel,
  private val fd: ParcelFileDescriptor?,
  private val fdIdentity: Pair<Long, Long>?,
  override val backend: AiBackend,
  private val thread: EngineThread,
  private val hooks: EngineHooks,
) : VectorSession {

  private var closed = false

  override suspend fun embedText(text: String): FloatArray = withContext(thread.dispatcher) {
    hooks.afterEmbed(backend, engine.computeEmbedding(listOf(InputData.Text(text)), NORMALIZED).embedding)
  }

  override suspend fun embedImage(jpeg: ByteArray): FloatArray = withContext(thread.dispatcher) {
    hooks.afterEmbed(backend, engine.computeEmbedding(listOf(InputData.Image(jpeg)), NORMALIZED).embedding)
  }

  override suspend fun close() {
    if (closed) return
    closed = true
    // Chi rilascia da una coroutine annullata deve lasciare comunque il motore chiuso: senza NonCancellable il blocco non partirebbe.
    withContext(NonCancellable) {
      try {
        withContext(thread.dispatcher) {
          runCatching { engine.close() }
          releaseDescriptor()
        }
      } finally {
        runCatching { model.close() }
        thread.shutdown()
      }
    }
  }

  private fun releaseDescriptor() = FdOwnership.closeIfOurs(fd, fdIdentity)

  companion object {
    private val NORMALIZED = EmbeddingOptions(normalize = true)

    /**
     * Il file `file` e' ancora quello verificato ([verified]): stesso dispositivo, stesso inode, stessa dimensione e stessa `ctime` (I1: una
     * scrittura sullo stesso inode, che per dispositivo, inode e dimensione sarebbe invisibile, cambia la `ctime`).
     */
    private fun checkSameFile(file: File, verified: FileStat) {
      val now = try {
        Os.stat(file.path)
      } catch (e: ErrnoException) {
        throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE, e)
      }
      if (!FileStat(now.st_dev, now.st_ino, now.st_size, now.st_ctime).isSameFileAs(verified)) throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE)
    }

    /** Lo `stat` preso alla verifica; se chi ha aperto il modello non lo ha dato, quello del descrittore adesso (meno forte: manca l'istante della verifica). */
    private fun verifiedStat(model: VerifiedModel): FileStat? =
      model.stat ?: model.descriptor?.let { fd -> Os.fstat(fd).let { FileStat(it.st_dev, it.st_ino, it.st_size, it.st_ctime) } }

    /** Bloccante: da chiamare sul thread del motore. In caso d'errore chiude cio' che ha aperto (non il modello, che e' del chiamante). */
    fun create(
      model: VerifiedModel,
      spec: ModelSpec,
      backend: AiBackend,
      withVision: Boolean,
      cacheRoot: File,
      recipe: AiRecipe,
      hooks: EngineHooks,
      thread: EngineThread,
      access: ModelAccess,
    ): RealSession {
      val native = hooks.nativeBackend(backend)
      val litertBackend: Backend = if (native == AiBackend.GPU) Backend.GPU() else Backend.CPU()
      val cache = File(cacheRoot, "${spec.sha256.hex}-${native.name.lowercase()}")
      // R9: una cache senza il segno di "completa" e' di un'inizializzazione interrotta (morte del processo a meta' scrittura): potrebbe
      // essere troncata, il motore potrebbe non riconoscerla e cadere a ogni apertura. Si toglie e il motore la rifa'.
      ProgramCache.prepare(cache)

      // D16, DESCRIPTOR: il motore legge dallo stesso descrittore su cui il verificatore ha calcolato dimensione e SHA-256 (riportato
      // all'inizio), duplicato perche' il motore lo tiene finche' vive e `VerifiedModel` ne e' il proprietario. I fake non hanno il
      // descrittore: in quel caso (solo test) si passa il percorso.
      val byDescriptor = access == ModelAccess.DESCRIPTOR && model.descriptor != null
      val dup: ParcelFileDescriptor? = if (byDescriptor) ParcelFileDescriptor.dup(model.descriptor) else null
      val identity = dup?.let { d -> Os.fstat(d.fileDescriptor).let { it.st_dev to it.st_ino } }

      // PATH_CHECKED: il file per nome deve essere quello verificato, adesso e dopo l'inizializzazione.
      val verified = if (!byDescriptor) verifiedStat(model) else null
      if (verified != null) checkSameFile(model.file, verified)

      val config = EmbeddingEngineConfig(
        modelPath = if (dup == null) model.file.path else "",
        backend = litertBackend,
        visionBackend = if (withVision) litertBackend else null,
        audioBackend = null,
        cacheDir = cache.path,
        modelFd = dup?.fd,
        maxInputLength = recipe.maxInputTokens,
        visionTokensPerImage = if (withVision) recipe.visionTokens else null,
      )
      val engine = EmbeddingEngine(config)
      try {
        hooks.beforeInit(backend)
        engine.initialize()
        if (verified != null) checkSameFile(model.file, verified)
        // Inizializzazione riuscita: la cache e' completa (R9).
        ProgramCache.markComplete(cache)
      } catch (t: Throwable) {
        runCatching { engine.close() }
        FdOwnership.closeIfOurs(dup, identity)
        // R9: a ogni inizializzazione fallita la cache si toglie: se era rovinata e' lei che faceva fallire il motore, se no si rifa'.
        ProgramCache.discard(cache)
        throw t
      }
      return RealSession(engine, model, dup, identity, backend, thread, hooks)
    }
  }
}

/**
 * La cartella dei programmi compilati di un modello e di un backend (`models/cache/<sha256>-<backend>/`), che il motore scrive e legge per percorso e
 * **non e' coperta dall'impronta del modello** (C22, R9). E' valida solo se porta il segno [COMPLETE], scritto dopo un'inizializzazione riuscita: senza
 * (morte del processo a meta' scrittura) potrebbe essere troncata e far cadere il motore a ogni apertura, quindi si toglie prima di riaprire; si toglie
 * anche a ogni inizializzazione fallita.
 */
internal object ProgramCache {
  const val COMPLETE: String = ".complete"

  /** Prepara [cache] per l'apertura: una cartella senza il segno si toglie, poi la cartella c'e'. */
  fun prepare(cache: File) {
    if (cache.exists() && !File(cache, COMPLETE).exists()) cache.deleteRecursively()
    cache.mkdirs()
  }

  /** L'inizializzazione e' riuscita: la cache e' completa. */
  fun markComplete(cache: File) {
    // Senza il segno la cartella si toglie alla prossima apertura e i programmi si ricompilano: lo dice il log di `StateFiles`, niente di piu'.
    runCatching { StateFiles.mark(File(cache, COMPLETE)) }
  }

  /** L'inizializzazione e' fallita: la cache si toglie (se era rovinata faceva fallire il motore, se no si rifa'). */
  fun discard(cache: File) {
    runCatching { cache.deleteRecursively() }
  }
}

/**
 * La chiusura della copia del descrittore passata al motore (D16). La libreria nativa potrebbe aver preso possesso del numero e
 * averlo chiuso lei (non e' documentato): chiuderlo due volte chiuderebbe un file qualunque che nel frattempo ha riusato quel numero.
 * Si confrontano dispositivo e inode con quelli presi alla copia: se tornano il descrittore e' ancora nostro e lo si chiude; se no (o
 * se `fstat` fallisce con EBADF) il numero non e' piu' nostro e lo si lascia stare (`detachFd`: l'oggetto non lo chiudera' piu').
 */
internal object FdOwnership {

  /** Quante volte la copia era ancora nostra e l'abbiamo chiusa / non lo era piu' (la libreria nativa l'aveva chiusa lei). Per le prove. */
  val closedByUs: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()
  val closedByLibrary: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()

  fun closeIfOurs(pfd: ParcelFileDescriptor?, identity: Pair<Long, Long>?) {
    if (pfd == null) return
    val stillOurs = try {
      val st = Os.fstat(pfd.fileDescriptor)
      identity != null && st.st_dev == identity.first && st.st_ino == identity.second
    } catch (_: ErrnoException) {
      false
    }
    if (stillOurs) {
      closedByUs.incrementAndGet()
      try {
        pfd.close()
      } catch (_: IOException) {
        // niente da fare: il descrittore e' gia' andato
      }
    } else {
      closedByLibrary.incrementAndGet()
      pfd.detachFd()
    }
  }
}
