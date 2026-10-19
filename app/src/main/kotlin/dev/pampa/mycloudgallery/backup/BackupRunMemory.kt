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
 *
 * **La pausa dell'utente** (R4 della revisione di sicurezza della 5A): "Ferma" non e' un tasto che vale per un giro, e' una pausa che dura
 * finche' l'utente non tocca "Riprendi" o "Fai ora". Il segno sta qui, in un file, cosi' regge fra un giro e l'altro e alla morte del
 * processo; il cancello del backup lo guarda a ogni elemento ([isPaused], senza attese: una lettura in memoria dopo la prima).
 */
class BackupRunMemory(private val dir: File) {

  /** Il battito dei giri delle foto e dei video che hanno fatto strada (E-P15): l'ora del dispositivo, come gli altri battiti. */
  val photoBeat = HeartbeatStore(File(dir, HEARTBEAT_PHOTOS))
  val videoBeat = HeartbeatStore(File(dir, HEARTBEAT_VIDEOS))

  private val completeFile = File(dir, LAST_COMPLETE)
  private val problemFile = File(dir, LAST_PROBLEM)
  private val startedFile = File(dir, STARTED_AT)
  private val pausedFile = File(dir, USER_PAUSED)

  /** Il segno della pausa in memoria: letto dal file una volta sola, poi aggiornato da [pauseNow] e [setPaused]. */
  @Volatile private var pausedCache: Boolean? = null

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

  /**
   * R4: l'utente ha fermato il backup e non l'ha ripreso? Vero finche' non tocca "Riprendi" o "Fai ora". Sincrona (il cancello non
   * aspetta): la prima volta una sola `stat` sul file, poi la memoria.
   */
  fun isPaused(): Boolean = pausedCache ?: pausedFile.isFile.also { pausedCache = it }

  /** La pausa vale **subito** (in memoria), senza aspettare la scrittura del file: il cancello la vede al passo dopo. */
  fun pauseNow() {
    pausedCache = true
  }

  /** Scrive (o toglie) il segno della pausa: dura anche se il processo muore. */
  suspend fun setPaused(paused: Boolean) {
    pausedCache = paused
    if (paused) writeText(pausedFile, "1") else withContext(Dispatchers.IO) { pausedFile.delete() }
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
    private const val USER_PAUSED = "backup-user-paused"
    private const val MAX_LENGTH = 40

    /** I file che l'azzeramento toglie da `noBackupFilesDir`. */
    val FILES: List<String> = listOf(HEARTBEAT_PHOTOS, HEARTBEAT_VIDEOS, LAST_COMPLETE, LAST_PROBLEM, STARTED_AT, USER_PAUSED)
  }
}
