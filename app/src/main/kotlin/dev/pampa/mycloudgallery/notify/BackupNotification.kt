package dev.pampa.mycloudgallery.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers

/**
 * Che cosa dice la notifica del backup avviato dall'utente (Fase 5A, P7; D11): **solo numeri**, mai nomi di file, cartelle, share o
 * account. Pura: la provano i test su JVM.
 */
data class BackupProgress(
  /** Elementi fatti e totali del giro. */
  val done: Int,
  val total: Int,
  /** Il backup c'e' ma non avanza adesso (un problema, le regole non lasciano partire). */
  val paused: Boolean,
  /** Un giro sta caricando. */
  val running: Boolean,
) {
  companion object {
    val NONE = BackupProgress(0, 0, paused = false, running = false)

    fun of(status: BackupStatus): BackupProgress = when (status) {
      is BackupStatus.Running -> BackupProgress(status.done, status.total, paused = false, running = true)
      is BackupStatus.Waiting, is BackupStatus.Problem, is BackupStatus.Stopped -> NONE.copy(paused = true)
      else -> NONE
    }
  }
}

/**
 * La notifica dell'avanzamento del backup avviato dall'utente ("Inizia ora", "Fai ora"; UIDT). Il tocco apre l'app (l'intent del
 * launcher, immutabile, senza extra: C17); l'**unica azione** e' "Ferma", che non distrugge niente (il giro si ferma prima dell'elemento
 * successivo, i temporanei restano con il loro punto di ripresa) e arriva solo al ricevitore **non esportato** che il servizio registra
 * mentre lavora (`BackupJobService`): nessun'altra app la puo' mandare.
 */
object BackupNotification {

  /** L'azione del broadcast di "Ferma": solo dentro il pacchetto (Intent esplicito sul pacchetto, ricevitore non esportato). */
  fun stopAction(context: Context): String = context.packageName + ACTION_STOP_SUFFIX

  fun build(context: Context, progress: BackupProgress, stopping: Boolean = false): Notification {
    val app = context.applicationContext ?: context
    val text = when {
      stopping -> app.getString(R.string.notify_backup_stopping)
      progress.paused -> app.getString(R.string.notify_backup_paused)
      !progress.running -> app.getString(R.string.notify_backup_starting)
      else -> app.resources.getQuantityString(
        R.plurals.notify_backup_progress,
        progress.total.coerceAtLeast(1),
        GalleryNumbers.format(progress.done),
        GalleryNumbers.format(progress.total),
      )
    }
    val publicVersion = NotificationCompat.Builder(app, AppNotifications.CHANNEL_BACKUP)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_backup_title))
      .build()
    val builder = NotificationCompat.Builder(app, AppNotifications.CHANNEL_BACKUP)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_backup_title))
      .setContentText(text)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(publicVersion)
      .setContentIntent(AppNotifications(app).openAppIntent())
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
    if (progress.running && progress.total > 0 && !stopping) {
      builder.setProgress(progress.total, progress.done.coerceIn(0, progress.total), false)
    } else {
      builder.setProgress(0, 0, true)
    }
    if (!stopping) builder.addAction(0, app.getString(R.string.notify_backup_stop), stopIntent(app))
    return builder.build()
  }

  /** "Ferma": un broadcast esplicito sul pacchetto, **immutabile**. */
  private fun stopIntent(context: Context): PendingIntent {
    val intent = Intent(stopAction(context)).setPackage(context.packageName)
    return PendingIntent.getBroadcast(context, REQUEST_STOP, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  private const val ACTION_STOP_SUFFIX = ".action.BACKUP_STOP"
  private const val REQUEST_STOP = 42
}
