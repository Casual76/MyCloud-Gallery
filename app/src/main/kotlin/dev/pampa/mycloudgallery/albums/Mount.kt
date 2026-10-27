package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il montaggio di una cosa sola per processo (gli album, [AlbumsGraph]) e il suo stop (Fase 7, revisione di fine fase, I11). Prima `stop()` metteva
 * `live` a null e annullava l'ambito **senza aspettare un montaggio in corso**: un `build` che finiva dopo lo stop assegnava `live` con un ambito gia'
 * annullato (e `watch` lanciava i suoi collettori nel vuoto). Ora lo stop prende lo stesso lucchetto del montaggio, quindi lo aspetta, e un montaggio
 * che si accorge dello stop butta cio' che ha costruito (e il suo ambito) invece di pubblicarlo.
 *
 * [scope] e' l'ambito del grafo: il montaggio ne riceve uno suo, figlio, che si annulla con lui. [onMounted] parte dopo la pubblicazione, sotto il
 * lucchetto (i collettori non vedono mai un montaggio a meta').
 */
internal class Mount<T : Any>(
  private val scope: CoroutineScope,
  private val build: suspend (CoroutineScope) -> T?,
  private val onMounted: (T) -> Unit,
) {
  private val state = MutableStateFlow<T?>(null)
  private val building = Mutex()
  private val stopped = AtomicBoolean(false)

  val flow: StateFlow<T?> = state.asStateFlow()

  val current: T? get() = state.value

  /** Il montaggio, fatto adesso se si puo' (null se la famiglia non e' pronta, se la costruzione fallisce o dopo lo stop). */
  suspend fun ensure(): T? {
    state.value?.let { return it }
    if (stopped.get()) return null
    return building.withLock {
      state.value ?: run {
        if (stopped.get()) return@withLock null
        val mountScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val built = try {
          build(mountScope)
        } catch (e: CancellationException) {
          mountScope.cancel()
          throw e
        } catch (e: Exception) {
          GalleryLog.w(TAG) { "album non montati: ${e.javaClass.simpleName}" }
          null
        }
        // Lo stop e' arrivato mentre si costruiva: niente di cio' che e' nato resta in vita.
        if (built == null || stopped.get()) {
          mountScope.cancel()
          return@withLock null
        }
        state.value = built
        onMounted(built)
        built
      }
    }
  }

  /** Lo stop: da qui in poi niente si monta piu', e un montaggio in corso e' finito (o scartato) quando questa torna. */
  suspend fun stop() {
    stopped.set(true)
    building.withLock { state.value = null }
  }

  private companion object {
    const val TAG = "Albums"
  }
}
