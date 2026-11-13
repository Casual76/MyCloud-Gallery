package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.ai.VisibilitySignal
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import java.util.concurrent.CopyOnWriteArrayList

/** Confine sincrono: nessuna derivazione Flow fra il proprietario e il nastro. */
class AnalysisVisibilityBoundary(private val authorized: () -> Boolean, private val snapshot: () -> TimelineSnapshot?) : VisibilitySignal {
  private val listeners = CopyOnWriteArrayList<() -> Unit>()
  internal val listenerCount: Int get() = listeners.size
  @Volatile private var retired = false
  @Volatile private var blocked: TimelineSnapshot? = null
  @Volatile private var awaitingSnapshot = false

  override fun register(onChange: () -> Unit): AutoCloseable {
    listeners += onChange
    return AutoCloseable { listeners -= onChange }
  }

  override fun canResolve(): Boolean = !retired && authorized() && (!awaitingSnapshot || snapshot() !== blocked)

  fun changed() { listeners.forEach { it() } }

  /** Un osservatore conosce il cambiamento prima della rilettura del catalogo: aspetta una nuova istantanea. */
  fun contentChanged() {
    blocked = snapshot()
    awaitingSnapshot = true
    changed()
  }

  fun retire() {
    retired = true
    changed()
  }
}
