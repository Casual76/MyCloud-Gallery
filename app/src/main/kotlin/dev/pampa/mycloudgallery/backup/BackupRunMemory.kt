package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.common.files.Patience
import dev.pampa.mycloudgallery.core.common.files.StateFiles
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
 *
 * **File di stato verificati**: si scrive e si toglie con [StateFiles] (temporaneo + rinomina atomica, `Files.deleteIfExists`, con un po' di
 * pazienza se un altro handle tiene il file). Le funzioni che cambiano un segno rispondono **vero solo se il file dice ora cio' che si e'
 * chiesto**: un segno che non si riesce a togliere (o a scrivere) finisce nel log e rende falso, non passa in silenzio. [patience] serve alle prove.
 */
class BackupRunMemory(private val dir: File, private val patience: Patience = Patience.STANDARD) {

  /** Il battito dei giri delle foto e dei video che hanno fatto strada (E-P15): l'ora del dispositivo, come gli altri battiti. */
  val photoBeat = HeartbeatStore(File(dir, HEARTBEAT_PHOTOS), patience)
  val videoBeat = HeartbeatStore(File(dir, HEARTBEAT_VIDEOS), patience)

  private val completeFile = File(dir, LAST_COMPLETE)
  private val problemFile = File(dir, LAST_PROBLEM)
  private val startedFile = File(dir, STARTED_AT)
  private val pausedFile = File(dir, USER_PAUSED)
  private val progressFile = File(dir, PROGRESS)
  private val busyPhotosFile = File(dir, BUSY_PHOTOS)
  private val busyVideosFile = File(dir, BUSY_VIDEOS)

  private fun busyFile(kind: BackupKind) = if (kind == BackupKind.VIDEOS) busyVideosFile else busyPhotosFile

  /**
   * BK-5: l'avanzamento del lavoro in corso (`RunProgress.snapshot`, solo sei numeri), cosi' la morte del processo fra due tappe non lo azzera.
   * Null se non c'e'.
   */
  suspend fun progress(): String? = readText(progressFile)

  /** Salva l'avanzamento ([RunProgress.EMPTY] = la campagna e' finita: il file si toglie). Vero se il file dice ora cio' che si e' chiesto. */
  suspend fun setProgress(text: String): Boolean = if (text.isEmpty()) remove(progressFile) else writeText(progressFile, text)

  /** Il segno della pausa in memoria: letto dal file una volta sola, poi aggiornato da [pauseNow] e [setPaused]. */
  @Volatile private var pausedCache: Boolean? = null

  /**
   * Da quando il backup ha una cartella del dispositivo (ora del dispositivo, ms), o null: "fermo da 3 giorni" parte da qui, non dalla
   * configurazione del NAS (un backup iniziato oggi non e' fermo da settimane).
   */
  suspend fun startedAt(): Long? = readLong(startedFile)

  /** Segna l'inizio del backup, una volta sola. Vero se c'e' (gia' o adesso). */
  suspend fun markStartedOnce(deviceNowMs: Long): Boolean = startedAt() != null || writeText(startedFile, deviceNowMs.toString())

  /** L'ultima volta che un giro e' finito senza altro da fare (ora del NAS, ms), o null. */
  suspend fun lastComplete(): Long? = readLong(completeFile)

  suspend fun markComplete(nasMs: Long): Boolean = writeText(completeFile, nasMs.toString())

  /** L'ultimo problema che ha fermato un giro, o null se l'ultimo giro e' andato bene. */
  suspend fun lastProblem(): BackupProblemCode? = readText(problemFile)?.let { code -> BackupProblemCode.entries.firstOrNull { it.code == code } }

  /** Ricorda [problem], o toglie il segno (null). Vero se il file dice ora cio' che si e' chiesto: un problema che non si toglie resterebbe per sempre. */
  suspend fun setProblem(problem: BackupProblemCode?): Boolean = if (problem == null) remove(problemFile) else writeText(problemFile, problem.code)

  /**
   * D-13 (prove sul campo): l'ultimo giro di [kind] e' finito perche' il NAS teneva occupato un file del backup (`RunStop.BUSY`): l'ora del NAS di
   * allora, o null. Regge alla morte del processo: la pagina dice "Il NAS tiene ancora occupato un file: riprovo tra poco" finche' un giro dello
   * stesso tipo non va avanti (le foto finite non lo tolgono ai video).
   */
  suspend fun busySince(kind: BackupKind): Long? = readLong(busyFile(kind))

  /** Un tipo di media ha l'ultimo giro fermo su un file occupato ([busySince]). */
  suspend fun anyBusy(): Boolean = busySince(BackupKind.PHOTOS) != null || busySince(BackupKind.VIDEOS) != null

  /** Segna (con l'ora del NAS, se non c'era gia') o toglie (null) il giro di [kind] fermo su un file occupato. Vero se il file dice ora cio' che si e' chiesto. */
  suspend fun setBusy(kind: BackupKind, nasMs: Long?): Boolean {
    val file = busyFile(kind)
    return if (nasMs == null) remove(file) else busySince(kind) != null || writeText(file, nasMs.toString())
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

  /**
   * Scrive (o toglie) il segno della pausa: dura anche se il processo muore. In questo processo vale subito la scelta dell'utente; il risultato
   * dice se anche il file la ricorda (falso = al prossimo avvio potrebbe tornare quella di prima, e il log lo dice).
   */
  suspend fun setPaused(paused: Boolean): Boolean {
    pausedCache = paused
    return if (paused) writeText(pausedFile, "1") else remove(pausedFile)
  }

  private suspend fun readLong(file: File): Long? = readText(file)?.toLongOrNull()?.takeIf { it > 0 }

  private suspend fun readText(file: File): String? = withContext(Dispatchers.IO) {
    try {
      StateFiles.readText(file, Charsets.US_ASCII, patience = patience)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    } catch (e: IOException) {
      null
    }
  }

  /** Scrive [text] in modo atomico. Vero se e' salvato; falso (con il motivo nel log di [StateFiles]) se no. */
  private suspend fun writeText(file: File, text: String): Boolean = withContext(Dispatchers.IO) {
    try {
      StateFiles.writeText(file, text, Charsets.US_ASCII, patience = patience)
      true
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "memoria del backup non salvata" }
      false
    }
  }

  /** Toglie il file. Vero solo se non c'e' piu'; falso se c'e' ancora (il log di [StateFiles] dice quale): il chiamante non lo dia per tolto. */
  private suspend fun remove(file: File): Boolean = withContext(Dispatchers.IO) {
    StateFiles.delete(file, patience = patience).also { gone ->
      if (!gone) GalleryLog.w(TAG) { "memoria del backup non tolta" }
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
    private const val PROGRESS = "backup-progress"
    private const val BUSY_PHOTOS = "backup-nas-busy-photos"
    private const val BUSY_VIDEOS = "backup-nas-busy-videos"
    private const val MAX_LENGTH = 120

    /** I file che l'azzeramento toglie da `noBackupFilesDir`. */
    val FILES: List<String> = listOf(
      HEARTBEAT_PHOTOS, HEARTBEAT_VIDEOS, LAST_COMPLETE, LAST_PROBLEM, STARTED_AT, PROGRESS, BUSY_PHOTOS, BUSY_VIDEOS, USER_PAUSED,
    )
  }
}
