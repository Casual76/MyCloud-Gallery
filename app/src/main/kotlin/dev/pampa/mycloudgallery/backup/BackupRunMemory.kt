package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.graph.HeartbeatStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cio' che il backup ricorda fra un lavoro e l'altro, in `noBackupFilesDir` (Fase 5A, P7; docs/fase5-progetto.md §2.6, §2.8, E-P15): i
 * battiti dei lavori (per "backup fermo da 3 giorni"), l'ultimo giro finito e l'ultimo problema che ha fermato un giro (quota, NAS pieno:
 * il riepilogo giornaliero gira in un altro momento e non ha la memoria del processo). **Solo numeri e codici**: mai nomi, percorsi,
 * share, account o id. L'azzeramento cancella tutto ([FILES]).
 */
class BackupRunMemory(private val dir: File) {

  /** Il battito dei giri delle foto e dei video che hanno fatto strada (E-P15): l'ora del dispositivo, come gli altri battiti. */
  val photoBeat = HeartbeatStore(File(dir, HEARTBEAT_PHOTOS))
  val videoBeat = HeartbeatStore(File(dir, HEARTBEAT_VIDEOS))

  private val completeFile = File(dir, LAST_COMPLETE)
  private val problemFile = File(dir, LAST_PROBLEM)
  private val startedFile = File(dir, STARTED_AT)

  /**
   * Da quando il backup ha una cartella del dispositivo (ora del dispositivo, ms), o null: "fermo da 3 giorni" parte da qui, non dalla
   * configurazione del NAS (un backup iniziato oggi non e' fermo da settimane).
   */
  suspend fun startedAt(): Long? = readLong(startedFile)

  /** Segna l'inizio del backup, una volta sola. */
  suspend fun markStartedOnce(deviceNowMs: Long) {
    if (startedAt() == null) writeText(startedFile, deviceNowMs.toString())
  }

  /** L'ultima volta che un giro e' finito senza altro da fare (ora del NAS, ms), o null. */
  suspend fun lastComplete(): Long? = readLong(completeFile)

  suspend fun markComplete(nasMs: Long) = writeText(completeFile, nasMs.toString())

  /** L'ultimo problema che ha fermato un giro, o null se l'ultimo giro e' andato bene. */
  suspend fun lastProblem(): BackupProblemCode? = readText(problemFile)?.let { code -> BackupProblemCode.entries.firstOrNull { it.code == code } }

  suspend fun setProblem(problem: BackupProblemCode?) {
    if (problem == null) withContext(Dispatchers.IO) { problemFile.delete() } else writeText(problemFile, problem.code)
  }

  private suspend fun readLong(file: File): Long? = readText(file)?.toLongOrNull()?.takeIf { it > 0 }

  private suspend fun readText(file: File): String? = withContext(Dispatchers.IO) {
    try {
      if (file.isFile) file.readText(Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH } else null
    } catch (e: IOException) {
      null
    }
  }

  private suspend fun writeText(file: File, text: String) = withContext(Dispatchers.IO) {
    try {
      file.parentFile?.mkdirs()
      val temp = File(file.parentFile, file.name + ".tmp")
      temp.writeText(text, Charsets.US_ASCII)
      if (!temp.renameTo(file)) {
        file.delete()
        temp.renameTo(file)
      }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "memoria del backup non salvata" }
    }
  }

  companion object {
    private const val TAG = "BackupMemory"
    const val HEARTBEAT_PHOTOS = "heartbeat-backup-photos"
    const val HEARTBEAT_VIDEOS = "heartbeat-backup-videos"
    private const val LAST_COMPLETE = "backup-last-complete"
    private const val LAST_PROBLEM = "backup-last-problem"
    private const val STARTED_AT = "backup-started-at"
    private const val MAX_LENGTH = 40

    /** I file che l'azzeramento toglie da `noBackupFilesDir`. */
    val FILES: List<String> = listOf(HEARTBEAT_PHOTOS, HEARTBEAT_VIDEOS, LAST_COMPLETE, LAST_PROBLEM, STARTED_AT)
  }
}
