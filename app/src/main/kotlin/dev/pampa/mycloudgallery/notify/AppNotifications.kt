package dev.pampa.mycloudgallery.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.pampa.mycloudgallery.MainActivity
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.PendingRequestView
import java.io.File
import java.io.IOException

/**
 * Le notifiche dell'app (docs/sicurezza.md 4-bis, riga `POST_NOTIFICATIONS`; C17):
 *
 * - due canali: **Approvazioni** (all'admin, "Nuovo dispositivo...") e **Problemi del NAS**;
 * - ogni notifica apre **soltanto l'app**: un `PendingIntent` **immutabile** verso la `MainActivity` (l'unica activity,
 *   `singleTask`), senza extra, senza azioni sulla notifica. Approvare si fa dentro l'app, dopo il blocco app e con il
 *   confronto del codice;
 * - sulla schermata di blocco non compare il nome del dispositivo: la versione pubblica dice solo "Nuovo dispositivo da
 *   approvare";
 * - senza il permesso (negato al passo 9) non si notifica niente e non si chiede: lo dice la Diagnostica (E-O4).
 */
class AppNotifications(context: Context) {

  private val app: Context = context.applicationContext ?: context

  /** Crea (o aggiorna) i canali: si chiama all'avvio del processo, e' idempotente. */
  fun createChannels() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = app.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_APPROVALS, app.getString(R.string.channel_approvals_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = app.getString(R.string.channel_approvals_description)
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
      },
    )
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_NAS, app.getString(R.string.channel_nas_name), NotificationManager.IMPORTANCE_LOW).apply {
        description = app.getString(R.string.channel_nas_description)
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
      },
    )
  }

  /** Si possono mostrare notifiche? (permesso concesso e notifiche dell'app non spente) */
  fun canNotify(): Boolean {
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return granted && NotificationManagerCompat.from(app).areNotificationsEnabled()
  }

  /**
   * "Nuovo dispositivo: Tablet — profilo Mamma. Approvare?" per le richieste che l'admin non ha ancora visto notificate
   * (una volta per richiesta). Vero se ha notificato qualcosa.
   */
  fun notifyNewDevices(requests: List<PendingRequestView>, alreadyNotified: Set<String>): Boolean {
    val fresh = requests.filter { it.windowOpen && it.id !in alreadyNotified }
    if (fresh.isEmpty() || !canNotify()) return false
    val title = if (fresh.size == 1) {
      app.getString(R.string.notify_new_device_title, fresh.single().name)
    } else {
      app.getString(R.string.notify_new_devices_title, fresh.size)
    }
    val publicVersion = NotificationCompat.Builder(app, CHANNEL_APPROVALS)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_new_device_public))
      .build()
    val notification = NotificationCompat.Builder(app, CHANNEL_APPROVALS)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(title)
      .setContentText(app.getString(R.string.notify_new_device_text))
      .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(R.string.notify_new_device_text)))
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(publicVersion)
      .setContentIntent(openAppIntent())
      .setAutoCancel(true)
      .build()
    return post(ID_NEW_DEVICE, notification)
  }

  /** "Serve la password del NAS" (E-R8): l'interruttore di sicurezza e' aperto. */
  fun notifyPasswordNeeded(): Boolean {
    if (!canNotify()) return false
    val notification = NotificationCompat.Builder(app, CHANNEL_NAS)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_password_title))
      .setContentText(app.getString(R.string.notify_password_text))
      .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(R.string.notify_password_text)))
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setContentIntent(openAppIntent())
      .setOnlyAlertOnce(true)
      .setAutoCancel(true)
      .build()
    return post(ID_PASSWORD, notification)
  }

  /** Toglie la notifica della password (salvata di nuovo, o interruttore richiuso). */
  fun cancelPasswordNeeded() = NotificationManagerCompat.from(app).cancel(ID_PASSWORD)

  /**
   * L'unico modo in cui una notifica entra nell'app: la `MainActivity`, con l'intent del launcher. **Immutabile** e
   * senza extra: chi la tocca apre l'app e basta (C17).
   */
  internal fun openAppIntent(): PendingIntent {
    val intent = Intent(app, MainActivity::class.java)
      .setAction(Intent.ACTION_MAIN)
      .addCategory(Intent.CATEGORY_LAUNCHER)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return PendingIntent.getActivity(app, REQUEST_OPEN_APP, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  private fun post(id: Int, notification: Notification): Boolean =
    try {
      NotificationManagerCompat.from(app).notify(id, notification)
      true
    } catch (e: SecurityException) {
      GalleryLog.w(TAG) { "notifica non consentita" }
      false
    }

  companion object {
    const val CHANNEL_APPROVALS = "approvals"
    const val CHANNEL_NAS = "nas"
    const val ID_NEW_DEVICE = 1001
    const val ID_PASSWORD = 1002
    private const val REQUEST_OPEN_APP = 0
    private const val TAG = "Notifications"
  }
}

/**
 * Le richieste gia' notificate all'admin (una notifica per richiesta, non una ogni 15 minuti): gli id, in un file di
 * `noBackupFilesDir`. Si tengono solo quelli ancora in attesa.
 */
class NotifiedRequests(private val file: File) {

  fun read(): Set<String> = try {
    if (file.exists()) file.readLines(Charsets.US_ASCII).map { it.trim() }.filter { ID.matches(it) }.toSet() else emptySet()
  } catch (e: IOException) {
    emptySet()
  }

  fun write(ids: Set<String>) {
    try {
      file.parentFile?.mkdirs()
      file.writeText(ids.filter { ID.matches(it) }.take(MAX_IDS).joinToString("\n"), Charsets.US_ASCII)
    } catch (e: IOException) {
      // Al peggio una notifica in piu'.
    }
  }

  private companion object {
    val ID = Regex("[A-Za-z0-9_-]{1,64}")
    const val MAX_IDS = 64
  }
}
