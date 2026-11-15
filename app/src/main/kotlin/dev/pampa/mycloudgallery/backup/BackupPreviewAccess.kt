package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.ai.VisibilitySignal
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.contract.BackupPreviewAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Filtro finale delle copertine e del nastro Backup. Le revoche invalidano anche bitmap gia' in cache. */
class AppBackupPreviewAccess(
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
  private val signal: VisibilitySignal,
) : BackupPreviewAccess {
  private class Approval(val snapshot: TimelineSnapshot, val ids: Set<MediaId>)
  private val lock = Any()
  private val changes = MutableStateFlow(0L)
  override val revision: StateFlow<Long> = changes
  private var generation = 0L
  private var observers = 0
  private var listener: AutoCloseable? = null
  private val approved = HashMap<String, Approval>()

  private fun invalidate(): Unit = synchronized(lock) {
    generation++
    approved.clear()
    changes.value++
    Unit
  }

  override fun open(): AutoCloseable {
    synchronized(lock) {
      if (observers++ == 0) {
        invalidate()
        listener = signal.register(::invalidate)
      }
    }
    var closed = false
    return AutoCloseable {
      synchronized(lock) {
        if (!closed) {
          closed = true
          if (--observers == 0) {
            listener?.close()
            listener = null
            invalidate()
          }
        }
      }
    }
  }

  override suspend fun approve(owner: String, ids: Set<MediaId>) {
    val start = synchronized(lock) { generation }
    val snapshot = visible.snapshot.value
    val allowed = try {
      if (snapshot == null || !signal.canResolve()) emptySet()
      else visible.visible(ids) - mask.hiddenAmong(ids)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      emptySet()
    }
    synchronized(lock) {
      if (observers == 0 || generation != start || snapshot !== visible.snapshot.value || !signal.canResolve()) return
      val previous = approved[owner]
      if (snapshot != null && (previous?.snapshot !== snapshot || previous?.ids != allowed)) {
        approved[owner] = Approval(snapshot, allowed)
        changes.value++
      }
    }
  }

  override fun release(owner: String) = synchronized(lock) {
    generation++ // Una risoluzione gia' partita non puo' ripristinare una finestra chiusa.
    if (approved.remove(owner) != null) changes.value++
    Unit
  }

  override fun allows(id: MediaId): Boolean = synchronized(lock) {
    observers > 0 && signal.canResolve() && approved.values.any { it.snapshot === visible.snapshot.value && id in it.ids }
  }
}
