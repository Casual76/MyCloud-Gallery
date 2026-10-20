package dev.pampa.mycloudgallery.notify

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers

/**
 * IN-7 (correzioni 5B): "N file ricevuti non salvati". La notifica del lavoro dei file ricevuti ("Salvataggio sul NAS") sparisce con il lavoro; un file che
 * non si e' caricato resta in "Ricevuti in coda" (pagina Backup) e questa notifica lo dice a chi ha chiuso l'app. **Solo numeri**, mai nomi di file,
 * cartelle, share o account (§4.3). Il tocco apre soltanto l'app (l'intent del launcher, immutabile, senza extra: C17); e' l'app a portare l'utente alla
 * pagina Backup quando torna (`FiveBUi.receivedRequests`, come "Libera spazio" dopo la notifica "Pronto"). Nessuna azione sulla notifica: "Riprova" e
 * "Scarta" si toccano nella pagina, dopo il blocco dell'app.
 */
object IncomingProblemNotification {

  fun build(context: Context, count: Int): Notification {
    val app = context.applicationContext ?: context
    val shown = count.coerceAtLeast(1)
    val text = app.resources.getQuantityString(R.plurals.notify_incoming_problems_text, shown, GalleryNumbers.format(shown))
    val publicVersion = NotificationCompat.Builder(app, AppNotifications.CHANNEL_NAS)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_incoming_problems_title))
      .build()
    return NotificationCompat.Builder(app, AppNotifications.CHANNEL_NAS)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_incoming_problems_title))
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(publicVersion)
      .setContentIntent(AppNotifications(app).openAppIntent())
      .setOnlyAlertOnce(true)
      .setAutoCancel(true)
      .build()
  }
}

/** Chi annuncia i file ricevuti non salvati (IN-7): la notifica, e la sua fine. I test usano [NONE] o un registratore. */
interface IncomingProblemNotices {
  /** [count] file ricevuti non si sono caricati: "N file ricevuti non salvati". */
  fun problems(count: Int)

  /** Non ce ne sono piu' (riprovati, scartati): la notifica non serve piu'. */
  fun clear()

  companion object {
    val NONE: IncomingProblemNotices = object : IncomingProblemNotices {
      override fun problems(count: Int) = Unit

      override fun clear() = Unit
    }
  }
}

/** Le notifiche vere: solo se l'utente le ha permesse (come le altre: senza permesso non si chiede niente). */
class AppIncomingProblemNotices(context: Context) : IncomingProblemNotices {
  private val app: Context = context.applicationContext ?: context

  override fun problems(count: Int) {
    if (!AppNotifications(app).canNotify()) return
    try {
      NotificationManagerCompat.from(app).notify(AppNotifications.ID_INCOMING_PROBLEMS, IncomingProblemNotification.build(app, count))
    } catch (e: SecurityException) {
      GalleryLog.w(TAG) { "notifica dei file ricevuti non salvati non consentita" }
    }
  }

  override fun clear() {
    NotificationManagerCompat.from(app).cancel(AppNotifications.ID_INCOMING_PROBLEMS)
  }

  private companion object {
    const val TAG = "IncomingProblemNotices"
  }
}
