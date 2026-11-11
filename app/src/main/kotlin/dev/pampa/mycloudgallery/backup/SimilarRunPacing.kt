package dev.pampa.mycloudgallery.backup

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lo stesso giudizio primo piano di YieldToApp; niente decodifiche nei primi secondi utili. */
internal suspend fun runSimilarSlices(
  inUse: () -> Boolean,
  awaitStart: suspend () -> Unit,
  slice: suspend (Duration) -> Boolean,
) {
  awaitStart()
  var used = Duration.ZERO
  while (used < 600.seconds) {
    val budget = minOf(if (inUse()) 1.seconds else 30.seconds, 600.seconds - used)
    if (!slice(budget)) return
    used += budget
    if (inUse()) delay(1_000)
  }
}

/** Il ritardo protegge soltanto il primo uso del processo, non le normali sincronizzazioni successive. */
internal class SimilarRunStart(private val firstDraw: suspend () -> Unit) {
  private val lock = Mutex()
  private var ready = false
  suspend fun await() = lock.withLock {
    if (!ready) {
      firstDraw()
      delay(10_000)
      ready = true
    }
  }
}
