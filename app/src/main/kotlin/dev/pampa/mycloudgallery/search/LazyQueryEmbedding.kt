package dev.pampa.mycloudgallery.search

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.WarmUpResult
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.engine.QueryEmbedding
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Il motore delle query (270M su CPU, D3) visto dalla ricerca, **caricato al bisogno e rilasciato** (D35): si carica all'apertura di Cerca
 * ([preload], CE-9) o, al piu' tardi, alla prima query (stato `PreparingContent` mentre si apre: 0,4-0,5 s a caldo sull'S25, P4), si rilascia
 * [idle] dopo l'ultima (3 minuti) e subito a memoria bassa ([release], da `onTrimMemory`). Senza il modello verificato ([modelReady] falso) niente
 * motore: solo filtri e testo (E-Q11), e la pagina dice "modello assente".
 *
 * **Una ricerca non aspetta il caricamento** ([vectorIfLoaded]): a motore freddo risponde subito (filtri e testo) e il contenuto si aggiunge quando il
 * motore e' pronto (`readiness` torna `Ready` e Cerca rifa' la ricerca).
 *
 * Un vettore di un altro spazio, non finito o della dimensione sbagliata non si usa mai; un guasto del motore spegne la ricerca per
 * contenuto di questa query (`FiltersAndTextOnly`) e il motore si chiude: la prossima query riprova da capo.
 */
class LazyQueryEmbedding(
  private val scope: CoroutineScope,
  private val modelReady: suspend () -> Boolean,
  private val factory: () -> TextEmbedder,
  private val idle: Duration = 3.minutes,
  private val space: SpaceCode = SpaceCode.CURRENT,
) : QueryEmbedding {

  override val readiness = MutableStateFlow<SearchReadiness>(SearchReadiness.Ready)

  private val mutex = Mutex()

  @Volatile private var engine: TextEmbedder? = null

  /** Il motore e' caricato **e** ha risposto (o si e' scaldato): una ricerca puo' usarlo senza aspettare. */
  @Volatile private var warm = false
  private var releaseJob: Job? = null

  @Volatile private var loading: Job? = null

  @Volatile private var stopped = false

  /** Vero se adesso il motore e' in memoria (per i test e per la Diagnostica). */
  val loaded: Boolean get() = engine != null

  override suspend fun vector(text: String): FloatArray? {
    if (stopped) return null
    if (!modelReady()) {
      readiness.value = SearchReadiness.FiltersAndTextOnly(modelMissing = true)
      return null
    }
    return mutex.withLock {
      releaseJob?.cancel()
      try {
        val current = engine ?: run {
          readiness.value = SearchReadiness.PreparingContent
          factory().also { engine = it }
        }
        val v = current.embed(text)
        val values = v.values
        if (v.space != space.space || values.size != space.space.dimension || values.any { !it.isFinite() }) {
          null
        } else {
          warm = true
          readiness.value = SearchReadiness.Ready
          values
        }
      } catch (e: AiEngineException) {
        readiness.value = SearchReadiness.FiltersAndTextOnly(modelMissing = e.error == AiEngineError.MODEL_UNAVAILABLE)
        closeLocked()
        null
      } finally {
        scheduleRelease()
      }
    }
  }

  /**
   * CE-9, E-Q11: se il motore e' pronto risponde come [vector]; altrimenti **non aspetta**. Comincia a caricarlo ([preload]) e risponde null subito, con
   * `readiness` su `PreparingContent` (o su "modello assente" se il modello non c'e'): filtri e testo escono senza i secondi di un avvio a freddo.
   */
  override suspend fun vectorIfLoaded(text: String): FloatArray? {
    if (stopped) return null
    if (warm && engine != null) return vector(text)
    if (!modelReady()) {
      readiness.value = SearchReadiness.FiltersAndTextOnly(modelMissing = true)
      return null
    }
    readiness.value = SearchReadiness.PreparingContent
    preload()
    return null
  }

  /** Carica e scalda il motore in un lavoro a parte (all'apertura di Cerca, D35): niente aspetta. Idempotente mentre il lavoro gira o il motore c'e'. */
  override fun preload() {
    if (stopped || warm) return
    if (loading?.isActive == true) return
    loading = scope.launch { load() }
  }

  private suspend fun load() {
    if (!modelReady()) {
      readiness.value = SearchReadiness.FiltersAndTextOnly(modelMissing = true)
      return
    }
    mutex.withLock {
      if (stopped) return
      releaseJob?.cancel()
      try {
        if (warm && engine != null) return
        readiness.value = SearchReadiness.PreparingContent
        val current = engine ?: factory().also { engine = it }
        when (val result = current.warmUp()) {
          is WarmUpResult.Ready -> {
            warm = true
            readiness.value = SearchReadiness.Ready
          }
          is WarmUpResult.Failed -> {
            readiness.value = SearchReadiness.FiltersAndTextOnly(modelMissing = result.error == AiEngineError.MODEL_UNAVAILABLE)
            closeLocked()
          }
        }
      } catch (e: AiEngineException) {
        readiness.value = SearchReadiness.FiltersAndTextOnly(modelMissing = e.error == AiEngineError.MODEL_UNAVAILABLE)
        closeLocked()
      } finally {
        scheduleRelease()
      }
    }
  }

  /** Rilascia subito il motore (memoria bassa, azzeramento). */
  suspend fun release() = mutex.withLock {
    releaseJob?.cancel()
    closeLocked()
  }

  /** Per sempre in questo processo (azzeramento). */
  suspend fun stop() {
    stopped = true
    release()
  }

  private fun scheduleRelease() {
    if (engine == null) return
    releaseJob = scope.launch {
      delay(idle)
      mutex.withLock { closeLocked() }
    }
  }

  private suspend fun closeLocked() {
    val current = engine ?: return
    engine = null
    warm = false
    withContext(NonCancellable) { runCatching { current.release() } }
  }
}
