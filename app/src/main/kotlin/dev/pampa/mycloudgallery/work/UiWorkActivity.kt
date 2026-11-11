package dev.pampa.mycloudgallery.work

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Un segnale di processo per il dosaggio esistente: tocchi, aperture e inerzia; nessuna lettura per cella. */
internal class UiActivityBudget(private val now: () -> Long) {
  private val active = AtomicInteger()
  private val until = AtomicLong(Long.MIN_VALUE)
  val busy: Boolean get() = active.get() > 0 || now() < until.get()
  fun interacted() { until.updateAndGet { old -> maxOf(old, now() + 800L) } }
  fun lease() = Lease()
  inner class Lease {
    private var held = false
    @Synchronized fun set(value: Boolean) {
      if (held == value) return
      held = value
      active.addAndGet(if (value) 1 else -1)
      interacted()
    }
    fun close() = set(false)
  }
}
internal object UiWorkActivity {
  val budget = UiActivityBudget(android.os.SystemClock::elapsedRealtime)
}
