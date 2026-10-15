package dev.pampa.mycloudgallery.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState

/**
 * Che cosa dice la notifica delle operazioni sul NAS (Fase 4B, D29, §1.5 "NasOperationState ... notifica UIDT"): **solo numeri**, mai
 * nomi di share, cartelle o file. Pura: la provano i test su JVM.
 */
data class NasOpsProgress(
  /** Elementi fatti e totali delle operazioni attive. */
  val done: Int,
  val total: Int,
  /** File aperti dal PC in attesa (E-N13). */
  val locked: Int,
  /** Tutte le operazioni attive sono in pausa (NAS che non risponde, scritture ferme, condizioni). */
  val paused: Boolean,
  val active: Int,
) {
  companion object {
    val NONE = NasOpsProgress(0, 0, 0, paused = false, active = 0)

    fun of(states: List<NasOperationState>): NasOpsProgress {
      val active = states.filter { it.active }
      if (active.isEmpty()) return NONE
      var done = 0
      var total = 0
      var locked = 0
      var paused = 0
      for (state in active) {
        when (state) {
          is NasOperationState.Planned -> total += state.summary.itemCount
          is NasOperationState.Running -> {
            done += state.done
            total += state.total
          }
          is NasOperationState.Paused -> {
            done += state.done
            total += state.total
            paused++
          }
          is NasOperationState.WaitingLocked -> {
            done += state.done
            total += state.total
            locked += state.lockedCount
          }
          else -> Unit
        }
      }
      return NasOpsProgress(done, total, locked, paused = paused == active.size, active = active.size)
    }
  }
}

/**
 * La notifica dell'avanzamento delle operazioni lunghe sul NAS (UIDT). Il tocco apre l'app (l'intent del launcher, immutabile, senza
 * extra: C17); l'**unica azione** e' "Ferma", che non distrugge niente (si ferma prima dell'elemento successivo, toglie i propri
 * temporanei, lascia fatto cio' che e' fatto) e arriva solo al ricevitore **non esportato** che il servizio registra mentre lavora
 * (`NasOpsJobService`): nessun'altra app la puo' mandare.
 */
object NasOpsNotification {

  /** L'azione del broadcast di "Ferma": solo dentro il pacchetto (Intent esplicito sul pacchetto, ricevitore non esportato). */
  fun stopAction(context: Context): String = context.packageName + ACTION_STOP_SUFFIX

  fun build(context: Context, progress: NasOpsProgress, stopping: Boolean = false): Notification {
    val app = context.applicationContext ?: context
    val text = when {
      stopping -> app.getString(R.string.notify_nas_ops_stopping)
      progress.active == 0 -> app.getString(R.string.notify_nas_ops_starting)
      progress.locked > 0 && progress.done + progress.locked >= progress.total ->
        app.resources.getQuantityString(R.plurals.notify_nas_ops_locked, progress.locked, GalleryNumbers.format(progress.locked))
      progress.paused -> app.getString(R.string.notify_nas_ops_paused)
      else -> app.resources.getQuantityString(
        R.plurals.notify_nas_ops_progress,
        progress.total.coerceAtLeast(1),
        GalleryNumbers.format(progress.done),
        GalleryNumbers.format(progress.total),
      )
    }
    val publicVersion = NotificationCompat.Builder(app, AppNotifications.CHANNEL_NAS_OPS)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_nas_ops_title))
      .build()
    val builder = NotificationCompat.Builder(app, AppNotifications.CHANNEL_NAS_OPS)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_nas_ops_title))
      .setContentText(text)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(publicVersion)
      .setContentIntent(AppNotifications(app).openAppIntent())
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
    if (progress.total > 0 && !stopping) {
      builder.setProgress(progress.total, progress.done.coerceIn(0, progress.total), false)
    } else {
      builder.setProgress(0, 0, true)
    }
    if (progress.active > 0 && !stopping) {
      builder.addAction(0, app.getString(R.string.notify_nas_ops_stop), stopIntent(app))
    }
    return builder.build()
  }

  /** "Ferma": un broadcast esplicito sul pacchetto, **immutabile**. */
  private fun stopIntent(context: Context): PendingIntent {
    val intent = Intent(stopAction(context)).setPackage(context.packageName)
    return PendingIntent.getBroadcast(context, REQUEST_STOP, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  private const val ACTION_STOP_SUFFIX = ".action.NAS_OPS_STOP"
  private const val REQUEST_STOP = 41
}
