package dev.pampa.mycloudgallery.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
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
  /** R4: l'utente ha messo il backup in pausa ("Ferma") e non e' ancora ripreso: la notifica dice "In pausa" come la pagina. */
  val userPaused: Boolean = false,
  /** IN-7: sono i file ricevuti da un'altra app ("Salva sul NAS"), con il loro titolo, i loro numeri e il loro "Ferma". */
  val incoming: Boolean = false,
) {
  companion object {
    val NONE = BackupProgress(0, 0, paused = false, running = false)

    /** IN-7: [done] file ricevuti caricati su [total]; con total 0 la notifica dice che si sta cominciando. */
    fun incoming(done: Int, total: Int): BackupProgress = BackupProgress(done, total, paused = false, running = total > 0, incoming = true)

    fun of(status: BackupStatus): BackupProgress = when (status) {
      is BackupStatus.Running -> BackupProgress(status.done, status.total, paused = false, running = true)
      is BackupStatus.Stopped -> NONE.copy(paused = true, userPaused = status.by == StopBy.USER)
      is BackupStatus.Waiting, is BackupStatus.Problem -> NONE.copy(paused = true)
      else -> NONE
    }
  }
}

/**
 * La notifica dell'avanzamento del backup avviato dall'utente ("Inizia ora", "Fai ora"; UIDT). Il tocco apre l'app (l'intent del
 * launcher, immutabile, senza extra: C17); l'**unica azione** e' "Ferma" (R4: una **pausa** che dura finche' non tocchi "Riprendi"), che non distrugge niente (il giro si ferma prima dell'elemento
 * successivo, i temporanei restano con il loro punto di ripresa) e arriva solo al ricevitore **non esportato** che il servizio registra
 * mentre lavora (`BackupJobService`): nessun'altra app la puo' mandare.
 */
object BackupNotification {

  /**
   * L'azione del broadcast di "Ferma": solo dentro il pacchetto (Intent esplicito sul pacchetto, ricevitore non esportato). Quella dei file ricevuti
   * ([incoming]) e' un'altra (IN-7): ferma solo loro, e un "Ferma" non puo' arrivare al lavoro sbagliato.
   */
  fun stopAction(context: Context, incoming: Boolean = false): String =
    context.packageName + if (incoming) ACTION_INCOMING_STOP_SUFFIX else ACTION_STOP_SUFFIX

  fun build(context: Context, progress: BackupProgress, stopping: Boolean = false): Notification {
    val app = context.applicationContext ?: context
    if (progress.incoming) return buildIncoming(app, progress, stopping)
    val text = when {
      stopping -> app.getString(R.string.notify_backup_stopping)
      progress.userPaused -> app.getString(R.string.notify_backup_user_paused)
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

  /** IN-7: la notifica dei file ricevuti: "N di M", solo numeri, e un "Ferma" che ferma solo loro. */
  private fun buildIncoming(app: Context, progress: BackupProgress, stopping: Boolean): Notification {
    val text = when {
      stopping -> app.getString(R.string.notify_incoming_stopping)
      !progress.running -> app.getString(R.string.notify_incoming_starting)
      else -> app.resources.getQuantityString(
        R.plurals.notify_incoming_progress,
        progress.total.coerceAtLeast(1),
        GalleryNumbers.format(progress.done),
        GalleryNumbers.format(progress.total),
      )
    }
    val publicVersion = NotificationCompat.Builder(app, AppNotifications.CHANNEL_BACKUP)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_incoming_title))
      .build()
    val builder = NotificationCompat.Builder(app, AppNotifications.CHANNEL_BACKUP)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_incoming_title))
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
    if (!stopping) builder.addAction(0, app.getString(R.string.notify_backup_stop), stopIntent(app, incoming = true))
    return builder.build()
  }

  /** "Ferma": un broadcast esplicito sul pacchetto, **immutabile**. */
  private fun stopIntent(context: Context, incoming: Boolean = false): PendingIntent {
    val intent = Intent(stopAction(context, incoming)).setPackage(context.packageName)
    return PendingIntent.getBroadcast(
      context,
      if (incoming) REQUEST_INCOMING_STOP else REQUEST_STOP,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  private const val ACTION_STOP_SUFFIX = ".action.BACKUP_STOP"
  private const val ACTION_INCOMING_STOP_SUFFIX = ".action.INCOMING_STOP"
  private const val REQUEST_STOP = 42
  private const val REQUEST_INCOMING_STOP = 43
}
