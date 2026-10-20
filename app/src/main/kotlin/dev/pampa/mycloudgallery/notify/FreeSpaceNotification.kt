package dev.pampa.mycloudgallery.notify

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Correzioni 5B (LS-2; docs/fase5-progetto.md §2.5 punto 4, D21): le notifiche di "Libera spazio". **Solo numeri**, mai nomi di file, cartelle,
 * share o account (§4.3). Il tocco apre soltanto l'app (l'intent del launcher, immutabile, senza extra: C17); "Libera spazio" la apre poi l'app
 * stessa (`FiveBFreeSpaceRuns.openRequest`). Nessuna azione sulla notifica: il cestino si chiede dalla pagina, con un tocco.
 */
object FreeSpaceNotification {

  /** L'avanzamento della verifica, mentre il lavoro avviato dall'utente la ospita. [run] null = sta per ripartire. */
  fun progress(context: Context, run: FreeSpaceRun?): Notification {
    val app = context.applicationContext ?: context
    val text = when (run?.state) {
      FreeSpaceRunState.GUARDING -> app.getString(R.string.notify_free_space_guarding, GalleryNumbers.format(run.guardedDone), GalleryNumbers.format(run.guardedTotal))
      FreeSpaceRunState.VERIFYING -> app.getString(R.string.notify_free_space_verifying, GalleryNumbers.format(run.verifiedDone), GalleryNumbers.format(run.verifiedTotal))
      else -> app.getString(R.string.notify_free_space_starting)
    }
    val builder = base(app)
      .setContentText(text)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
    val total = run?.let { if (it.state == FreeSpaceRunState.GUARDING) it.guardedTotal else it.verifiedTotal } ?: 0
    val done = run?.let { if (it.state == FreeSpaceRunState.GUARDING) it.guardedDone else it.verifiedDone } ?: 0
    if (total > 0) builder.setProgress(total, done.coerceIn(0, total), false) else builder.setProgress(0, 0, true)
    return builder.build()
  }

  /** "Pronto: N elementi, X GB. Tocca per liberare." (§2.5 punto 4). */
  fun ready(context: Context, run: FreeSpaceRun): Notification {
    val app = context.applicationContext ?: context
    val text = app.resources.getQuantityString(R.plurals.notify_free_space_ready, run.readyItems.coerceAtLeast(1), GalleryNumbers.format(run.readyItems), size(run.readyBytes))
    return base(app)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      .build()
  }

  private fun base(app: Context): NotificationCompat.Builder {
    val publicVersion = NotificationCompat.Builder(app, AppNotifications.CHANNEL_BACKUP)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_free_space_title))
      .build()
    return NotificationCompat.Builder(app, AppNotifications.CHANNEL_BACKUP)
      .setSmallIcon(R.drawable.ic_stat_gallery)
      .setContentTitle(app.getString(R.string.notify_free_space_title))
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(publicVersion)
      .setContentIntent(AppNotifications(app).openAppIntent())
  }

  /** Il peso come lo scrivono le schermate del backup ("4,1 GB"): unita' da 1.000, la virgola, uno spazio che non va a capo. */
  fun size(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    if (value < THOUSAND) return "$value${NBSP}B"
    var scaled = value.toDouble()
    var unit = 0
    while (scaled >= THOUSAND && unit < UNITS.lastIndex) {
      scaled /= THOUSAND
      unit++
    }
    if (scaled.roundToLong() >= THOUSAND && unit < UNITS.lastIndex) {
      scaled /= THOUSAND
      unit++
    }
    val tenths = (scaled * 10).roundToLong() / 10.0
    val text = if (tenths < 10.0) String.format(Locale.ROOT, "%.1f", tenths).replace('.', ',') else scaled.roundToLong().toString()
    return "$text$NBSP${UNITS[unit]}"
  }

  private val UNITS = listOf("B", "kB", "MB", "GB", "TB")
  private const val THOUSAND = 1_000.0
  private const val NBSP = ' '
}

/** Chi annuncia "Libera spazio" fuori dalla pagina (LS-2): la notifica "Pronto", e la sua fine. I test usano [NONE] o un registratore. */
interface FreeSpaceNotices {
  /** Il giro e' pronto per i lotti: "Pronto: N elementi, X GB. Tocca per liberare". */
  fun ready(run: FreeSpaceRun)

  /** Il giro e' finito (liberato, scaduto, annullato): la notifica non serve piu'. */
  fun clear()

  companion object {
    val NONE: FreeSpaceNotices = object : FreeSpaceNotices {
      override fun ready(run: FreeSpaceRun) = Unit

      override fun clear() = Unit
    }
  }
}

/** Le notifiche vere: solo se l'utente le ha permesse (come le altre: senza permesso non si chiede niente). */
class AppFreeSpaceNotices(context: Context) : FreeSpaceNotices {
  private val app: Context = context.applicationContext ?: context

  override fun ready(run: FreeSpaceRun) {
    if (!AppNotifications(app).canNotify()) return
    try {
      NotificationManagerCompat.from(app).notify(AppNotifications.ID_FREE_SPACE, FreeSpaceNotification.ready(app, run))
    } catch (e: SecurityException) {
      GalleryLog.w(TAG) { "notifica di Libera spazio non consentita" }
    }
  }

  override fun clear() {
    NotificationManagerCompat.from(app).cancel(AppNotifications.ID_FREE_SPACE)
  }

  private companion object {
    const val TAG = "FreeSpaceNotices"
  }
}
