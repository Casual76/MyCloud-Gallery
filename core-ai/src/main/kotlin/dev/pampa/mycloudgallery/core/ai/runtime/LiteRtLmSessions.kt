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

internal class LiteRtLmSessions(
  private val files: ModelFiles,
  private val spec: ModelSpec,
  private val cacheRoot: File,
  private val recipe: AiRecipe,
  private val withVision: Boolean,
  private val nanos: () -> Long = System::nanoTime,
  private val hooks: EngineHooks = EngineHooks.None,
  val timings: OpenTimings = OpenTimings(),
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
        withContext(thread.dispatcher) { RealSession.create(model, spec, backend, withVision, cacheRoot, recipe, hooks, thread) }
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
    ): RealSession {
      val native = hooks.nativeBackend(backend)
      val litertBackend: Backend = if (native == AiBackend.GPU) Backend.GPU() else Backend.CPU()
      val cache = File(cacheRoot, "${spec.sha256.hex}-${native.name.lowercase()}").apply { mkdirs() }

      // D16: il motore legge dallo stesso descrittore su cui il verificatore ha calcolato dimensione e SHA-256 (riportato all'inizio),
      // duplicato perche' il motore lo tiene finche' vive e `VerifiedModel` ne e' il proprietario. I fake non hanno il descrittore:
      // in quel caso (solo test) si passa il percorso.
      val dup: ParcelFileDescriptor? = model.descriptor?.let { ParcelFileDescriptor.dup(it) }
      val identity = dup?.let { d -> Os.fstat(d.fileDescriptor).let { it.st_dev to it.st_ino } }

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
      } catch (t: Throwable) {
        runCatching { engine.close() }
        FdOwnership.closeIfOurs(dup, identity)
        throw t
      }
      return RealSession(engine, model, dup, identity, backend, thread, hooks)
    }
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
