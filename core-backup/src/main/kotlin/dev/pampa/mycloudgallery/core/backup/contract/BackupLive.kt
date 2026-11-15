package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Attività reali del motore. Recenti = copie collegate e verificate, mai errori o file scomparsi. */
data class BackupLive(
  val active: List<MediaId> = emptyList(),
  val recent: List<MediaId> = emptyList(),
  val next: List<MediaId> = emptyList(),
  val finished: Long = 0L,
) {
  override fun toString(): String = "BackupLive(active=${active.size}, recent=${recent.size}, next=${next.size}, finished=$finished)"

  companion object {
    val EMPTY = BackupLive()
    val NONE: StateFlow<BackupLive> = MutableStateFlow(EMPTY)
    const val WINDOW = 8
  }
}
