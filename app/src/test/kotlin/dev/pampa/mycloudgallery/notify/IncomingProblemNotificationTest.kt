package dev.pampa.mycloudgallery.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * IN-7 (correzioni 5B): la notifica dei file ricevuti. Quella del lavoro ("Salvataggio sul NAS") dice anche quanti file non si sono caricati; quella dei
 * problemi ("File ricevuti non salvati") resta dopo la fine del lavoro. **Solo numeri**; il tocco apre soltanto l'app (C17).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class IncomingProblemNotificationTest {

  private val app: Application = ApplicationProvider.getApplicationContext()

  private fun text(n: Notification): String = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

  private fun title(n: Notification): String = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

  @Test
  fun laNotificaDeiProblemiDiceQuantiFileNonSonoStatiSalvatiESoloQuello() {
    val many = IncomingProblemNotification.build(app, 3)
    assertThat(title(many)).isEqualTo("File ricevuti non salvati")
    assertThat(text(many)).isEqualTo("3 file ricevuti non sono stati salvati sul NAS. Apri Backup per riprovare o scartarli.")
    val one = IncomingProblemNotification.build(app, 1)
    assertThat(text(one)).isEqualTo("1 file ricevuto non è stato salvato sul NAS. Apri Backup per riprovare o scartarlo.")
    // Sulla schermata di blocco: il titolo e basta, nemmeno i numeri.
    assertThat(title(many.publicVersion)).isEqualTo("File ricevuti non salvati")
    assertThat(text(many.publicVersion)).isEmpty()
    assertThat(many.channelId).isEqualTo(AppNotifications.CHANNEL_NAS)
  }

  @Test
  fun ilTocco_ApreSoloLApp_NessunaAzione_ESiTogliCol_Tocco() {
    val notification = IncomingProblemNotification.build(app, 2)
    assertThat(notification.actions).isNull()
    assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
    assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(0)
    val open = shadowOf(notification.contentIntent)
    assertThat(open.isActivityIntent).isTrue()
    assertThat(open.savedIntent.extras).isNull()
    assertThat(open.flags and PendingIntent.FLAG_IMMUTABLE).isEqualTo(PendingIntent.FLAG_IMMUTABLE)
  }

  @Test
  fun unNumeroNonPositivoNonDiceZero() {
    assertThat(text(IncomingProblemNotification.build(app, 0))).startsWith("1 file ricevuto")
  }

  @Test
  fun conIlPermessoLaNotificaSiPostaEConNullaSiToglie() {
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notices = AppIncomingProblemNotices(app)
    notices.problems(2)
    val posted = shadowOf(manager).getNotification(AppNotifications.ID_INCOMING_PROBLEMS)
    assertThat(posted).isNotNull()
    assertThat(text(posted)).startsWith("2 file ricevuti")
    // Il numero cambia sulla stessa notifica.
    notices.problems(1)
    assertThat(shadowOf(manager).activeNotifications.count { it.id == AppNotifications.ID_INCOMING_PROBLEMS }).isEqualTo(1)
    notices.clear()
    assertThat(shadowOf(manager).getNotification(AppNotifications.ID_INCOMING_PROBLEMS)).isNull()
  }

  @Test
  fun senzaIlPermessoNonSiNotificaNienteENonSiChiede() {
    shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    AppIncomingProblemNotices(app).problems(2)
    assertThat(shadowOf(manager).getNotification(AppNotifications.ID_INCOMING_PROBLEMS)).isNull()
  }

  // --- La notifica del lavoro dei file ricevuti ----------------------------------------------------------------------------------------------

  /**
   * IN-7: un file che non si e' caricato esce dalla coda ma non e' "fatto": la notifica del lavoro non lo conta fra i fatti, lo dice a parte, e si
   * ferma con il solito "Ferma" (solo loro).
   */
  @Test
  fun laNotificaDelLavoroDiceAncheQuantiFileHannoUnProblema() {
    val notification = BackupNotification.build(app, BackupProgress.incoming(done = 2, total = 5, problems = 1))
    assertThat(text(notification)).isEqualTo("2 di 5 file ricevuti · 1 con un problema")
    assertThat(text(BackupNotification.build(app, BackupProgress.incoming(2, 5, problems = 3)))).isEqualTo("2 di 5 file ricevuti · 3 con un problema")
    // Senza problemi com'era.
    assertThat(text(BackupNotification.build(app, BackupProgress.incoming(2, 5)))).isEqualTo("2 di 5 file ricevuti")
    // Mentre si ferma, la frase di sempre.
    assertThat(text(BackupNotification.build(app, BackupProgress.incoming(2, 5, problems = 1), stopping = true))).isEqualTo("Mi fermo prima del prossimo file…")
    // Il "Ferma" resta solo dei file ricevuti.
    assertThat(shadowOf(notification.actions[0].actionIntent).savedIntent.action).isEqualTo(BackupNotification.stopAction(app, incoming = true))
    // Un numero negativo non esiste.
    assertThat(BackupProgress.incoming(1, 2, problems = -4).problems).isEqualTo(0)
  }
}
