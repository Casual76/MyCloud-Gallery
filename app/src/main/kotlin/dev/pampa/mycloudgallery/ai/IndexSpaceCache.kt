package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.feature.search.contract.IndexSpace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Una lettura del filesystem al minuto al massimo; la stessa cache vive quanto IndexerControl. */
internal class IndexSpaceCache(
  private val read: () -> IndexSpace,
  private val now: () -> Long = { System.nanoTime() / 1_000_000 },
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {
  private val lock = Mutex()
  private var cached: IndexSpace? = null
  private var readAt = 0L
  suspend fun current(): IndexSpace = withContext(io) {
    lock.withLock {
      val before = cached
      if (before != null && now() - readAt < 60_000L) before else read().also {
        cached = it
        readAt = now()
      }
    }
  }
}
