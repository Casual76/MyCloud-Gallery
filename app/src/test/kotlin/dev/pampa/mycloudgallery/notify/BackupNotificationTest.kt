package dev.pampa.mycloudgallery.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Le notifiche del backup (Fase 5A, P7; docs/fase5-progetto.md §2.6, proposta 12): l'avanzamento del backup avviato dall'utente (solo numeri,
 * una sola azione "Ferma" immutabile e interna al pacchetto) e il riepilogo giornaliero esteso (soli numeri, una notifica al giorno).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupNotificationTest {

  private val app: Application = ApplicationProvider.getApplicationContext()

  private fun text(n: Notification): String = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

  @Test
  fun lAvanzamentoDiceSoloNumeriEHaLaSolaAzioneFerma() {
    val running = BackupStatus.Running(BackupKind.PHOTOS, done = 1_240, total = 6_658, bytesDone = 1, bytesTotal = 2, etaMillis = null, trigger = BackupTrigger.FIRST_BACKUP)
    val progress = BackupProgress.of(running)
    assertThat(progress.running).isTrue()
    val notification = BackupNotification.build(app, progress)
    assertThat(text(notification)).isEqualTo("1.240 di 6.658 elementi")
    // Nessun nome, percorso o cartella: il testo e' fatto di numeri e di parole fisse.
    assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Backup di foto e video")
    // La versione pubblica (schermata di blocco) non porta nemmeno i numeri.
    assertThat(text(notification.publicVersion)).isEmpty()
    // Una sola azione, "Ferma": un broadcast immutabile ed esplicito sul pacchetto.
    assertThat(notification.actions).hasLength(1)
    assertThat(notification.actions[0].title.toString()).isEqualTo("Ferma")
    val intent = shadowOf(notification.actions[0].actionIntent)
    assertThat(intent.isBroadcastIntent).isTrue()
    assertThat(intent.savedIntent.`package`).isEqualTo(app.packageName)
    assertThat(intent.savedIntent.action).isEqualTo(BackupNotification.stopAction(app))
    assertThat(intent.flags and PendingIntent.FLAG_IMMUTABLE).isEqualTo(PendingIntent.FLAG_IMMUTABLE)
    assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
  }

  @Test
  fun inPausaESiFermandoDiconoLoStatoSenzaNumeri() {
    assertThat(text(BackupNotification.build(app, BackupProgress.NONE))).isEqualTo("Sto riprendendo il backup…")
    assertThat(text(BackupNotification.build(app, BackupProgress.of(BackupStatus.Waiting(5, 10, WaitReason.CHARGING)))))
      .isEqualTo("In pausa: il NAS non risponde o il backup è fermo")
    val stopping = BackupNotification.build(app, BackupProgress.NONE, stopping = true)
    // R4: "Ferma" e' una pausa, e la notifica lo dice come la pagina.
    assertThat(text(stopping)).isEqualTo("Metto in pausa prima del prossimo elemento…")
    // Fermandosi non resta nessuna azione (non si ferma due volte).
    assertThat(stopping.actions).isNull()
    assertThat(BackupProgress.of(BackupStatus.Stopped(StopBy.USER)).paused).isTrue()
    assertThat(BackupProgress.of(BackupStatus.TestOnly)).isEqualTo(BackupProgress.NONE)
  }

  /** R4: dopo "Ferma" lo stato e' "In pausa" (la parola della pagina Backup e della voce delle Impostazioni); Android che ferma il lavoro no. */
  @Test
  fun r4_unaPausaDellUtenteDiceInPausaComeLaPagina() {
    val user = BackupProgress.of(BackupStatus.Stopped(StopBy.USER))
    assertThat(user.userPaused).isTrue()
    assertThat(text(BackupNotification.build(app, user))).isEqualTo("In pausa: riprende quando tocchi «Riprendi» nell’app")
    // Android ferma il lavoro e lo riprende da solo: non e' una pausa dell'utente, il testo resta quello generico.
    val system = BackupProgress.of(BackupStatus.Stopped(StopBy.SYSTEM))
    assertThat(system.userPaused).isFalse()
    assertThat(text(BackupNotification.build(app, system))).isEqualTo("In pausa: il NAS non risponde o il backup è fermo")
    // Nessun numero e nessun nome nemmeno qui.
    assertThat(text(BackupNotification.build(app, user))).doesNotContainMatch("[0-9]")
  }

  @Test
  fun ilRiepilogoGiornalieroDelBackupDiceSoloNumeri() {
    shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    AppNotifications(app).createChannels()
    val problems = listOf(
      NasDailySummary.Problem.BackupStalled(4),
      NasDailySummary.Problem.BackupQuota,
      NasDailySummary.Problem.BackupNasFull,
      NasDailySummary.Problem.BackupLost(3),
      NasDailySummary.Problem.BackupNotIntact(2),
      NasDailySummary.Problem.BackupCommonCopyLost(1),
    )
    assertThat(AppNotifications(app).notifyDailySummary(problems)).isTrue()
    val posted = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications.single()
    val body = posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
    assertThat(body).contains("Il backup è fermo da 4 giorni")
    assertThat(body).contains("quota sul NAS")
    assertThat(body).contains("il NAS è pieno")
    assertThat(body).contains("3 foto o video sono stati cancellati dal telefono prima del backup")
    assertThat(body).contains("2 copie sul NAS non sono integre")
    assertThat(body).contains("Una foto o un video aveva l'unica copia nell'area comune")
    // Una sola notifica al giorno, sul canale dei problemi del NAS, che apre solo l'app.
    assertThat(posted.channelId).isEqualTo(AppNotifications.CHANNEL_NAS)
  }

  @Test
  fun ilCanaleDelBackupEsisteESilenzioso() {
    AppNotifications(app).createChannels()
    val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = manager.getNotificationChannel(AppNotifications.CHANNEL_BACKUP)
    assertThat(channel).isNotNull()
    assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    assertThat(channel.lockscreenVisibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
  }
}
