package dev.pampa.mycloudgallery.core.memories.notify

import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **La revoca** (docs/fase9-progetto.md §2.6 "Revoca", D19; E-NT7, E-H8): la notifica in tendina si toglie quando il suo ricordo non si puo'
 * piu' mostrare, e in "Titolo e foto" anche quando la sua **copertina** e' diventata nascosta (o non c'e' piu'): una miniatura nascosta non
 * deve restare nella tendina. `MemoryNotifier` di :app la chiama a ogni cambio di nascosti e di scelte ([revalidate]) e le tiene dietro lo
 * scaffale ([watch]: un "Non mostrare", un "Mai" o una pulizia dei nascosti cambiano lo scaffale).
 *
 * - Il ricordo non c'e' piu' (scartato, uscito dallo scaffale, sotto 3 elementi visibili, persona tolta): la notifica sparisce, **anche** nella
 *   modalita' generica, che non ha niente da togliere ma non deve restare per un ricordo che non c'e'.
 * - "Titolo e foto": la copertina deve poter essere mostrata adesso (`MemoryVisibility.visibleOrThrow`: fuori da nascosti, eliminati e gruppi
 *   con un nascosto). Un guasto della lettura **toglie** la notifica (fallire chiusi: R17), poi tornera' tutto com'e' al prossimo giorno.
 * - Aprendo prima che sparisca, la pagina mostra il ricordo senza i nascosti, o "Questo ricordo non c'e' piu'" (D19: lo fa la pagina, P9).
 */
class MemoryNotifyRevocation(
  private val publisher: NotifyPublisher,
  private val memories: Memories,
  private val visibility: MemoryVisibility,
  private val events: NotifyEvents = NoNotifyEvents,
) {
  private val lock = Mutex()

  /** Vero se ha tolto la notifica. Senza notifica in tendina non fa niente. */
  suspend fun revalidate(): Boolean = lock.withLock {
    val posted = publisher.posted() ?: return@withLock false
    val keep = try {
      stillShowable(posted)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      false
    }
    if (!keep) {
      publisher.cancel()
      events.revoked()
    }
    !keep
  }

  /** Rivaluta a ogni cambio dello scaffale (e subito, una volta). Un cambio dei nascosti passa da `Memories.prune()`, che lo cambia. */
  fun watch(scope: CoroutineScope): Job = scope.launch {
    memories.shelf.collect { revalidate() }
  }

  private suspend fun stillShowable(posted: PostedNotice): Boolean {
    val view = memories.memory(posted.memory).first() ?: return false
    if (posted.mode != LockScreenMode.TITLE_AND_PHOTO) return true
    val cover = posted.cover ?: view.cover
    return cover in visibility.visibleOrThrow(setOf(cover))
  }
}
