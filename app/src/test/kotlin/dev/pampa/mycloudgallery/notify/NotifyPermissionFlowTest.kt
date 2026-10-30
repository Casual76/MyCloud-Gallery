package dev.pampa.mycloudgallery.notify

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.noop.NoMemories
import dev.pampa.mycloudgallery.core.memories.notify.NotifyAccessState
import dev.pampa.mycloudgallery.core.memories.shelf.MemoryVisibility
import dev.pampa.mycloudgallery.core.memories.testing.FakeNotifyAccess
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.main.PermissionAsks
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Il flusso del permesso delle notifiche (Fase 9, P10; docs/fase9-progetto.md D18, E-NT1, E-NT2, E-O4): la pagina delle impostazioni di P9 legge i quattro stati e al tocco chiede il
 * dialogo di sistema **solo se Android lo permette ancora**, altrimenti apre le impostazioni dell'app o del canale. **Mai una richiesta che l'utente non ha chiesto**: leggere lo
 * stato, ripianificare o far girare il lavoro non apre niente.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotifyPermissionFlowTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val permission = Manifest.permission.POST_NOTIFICATIONS
  private var dir: File? = null

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("notify-flow").toFile()
  }

  @After
  fun tearDown() {
    dir?.deleteRecursively()
  }

  private val visibility = object : MemoryVisibility {
    override val snapshot: StateFlow<TimelineSnapshot?> = MutableStateFlow(null)

    override suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = ids.toSet()

    override suspend fun hiddenOrThrow(ids: Collection<MediaId>): Set<MediaId> = emptySet()
  }

  private class Rig(val app: Application, val dir: File, val visibility: MemoryVisibility, val explain: (String) -> Boolean?) {
    val asks = PermissionAsks { app.getSharedPreferences("permission_asks_test_${System.nanoTime()}", 0) }
    val notifier = MemoryNotifier(app, NoMemories, visibility, dir)
    val access = NotificationPermissionAccess(notifier, asks, explain)
    var systemDialogs = 0
    val opened = ArrayList<Intent>()
    val actions = NotifyPermissionActions(access, notifier, asks, { systemDialogs++ }, { opened += it })
  }

  private fun rig(explain: (String) -> Boolean? = { null }) = Rig(app, dir!!, visibility, explain)

  /** I quattro stati della pagina hanno gli **stessi codici** di `NotifyPermission` di P9: P13 li trasforma l'uno nell'altro per codice. */
  @Test
  fun gliStatiHannoGliStessiCodiciDiP9() {
    assertThat(NotifyPagePermission.entries.map { it.code }).containsExactlyElementsIn(NotifyPermission.entries.map { it.code })
    for (page in NotifyPagePermission.entries) assertThat(NotifyPermission.entries.first { it.code == page.code }.code).isEqualTo(page.code)
  }

  /** I quattro stati del sistema nei quattro della pagina; "Consenti" (chiesto, Android propone ancora di spiegare) resta "si puo' chiedere". */
  @Test
  fun iQuattroStatiDellaPagina() {
    assertThat(NotifyPermissionFlow.permissionOf(NotifyAccessState.GRANTED, deniedForGood = false)).isEqualTo(NotifyPagePermission.GRANTED)
    assertThat(NotifyPermissionFlow.permissionOf(NotifyAccessState.PERMISSION_MISSING, deniedForGood = false)).isEqualTo(NotifyPagePermission.ASKABLE)
    assertThat(NotifyPermissionFlow.permissionOf(NotifyAccessState.PERMISSION_MISSING, deniedForGood = true)).isEqualTo(NotifyPagePermission.DENIED)
    assertThat(NotifyPermissionFlow.permissionOf(NotifyAccessState.APP_OFF, deniedForGood = false)).isEqualTo(NotifyPagePermission.DENIED)
    assertThat(NotifyPermissionFlow.permissionOf(NotifyAccessState.CHANNEL_OFF, deniedForGood = false)).isEqualTo(NotifyPagePermission.CHANNEL_OFF)
    assertThat(NotifyPagePermission.entries.map { NotifyPermissionFlow.stepFor(it) }.toSet()).containsExactlyElementsIn(NotifyPermissionStep.entries)
  }

  /** Mai chiesto: "Consenti" lancia il dialogo di sistema, una volta, e si ricorda di averlo chiesto. */
  @Test
  fun consentiChiedeIlPermessoSeAndroidLoPermette() {
    shadowOf(app).denyPermissions(permission)
    val rig = rig()
    assertThat(rig.access.state.value).isEqualTo(NotifyPagePermission.ASKABLE)
    rig.actions.requestNotificationPermission()
    assertThat(rig.systemDialogs).isEqualTo(1)
    assertThat(rig.opened).isEmpty()
    assertThat(rig.asks.asked(permission)).isTrue()
  }

  /** Chiesto e rifiutato, e Android non propone piu' di spiegare: negato per sempre. "Consenti" apre le impostazioni dell'app, non il dialogo. */
  @Test
  fun negatoPerSempreApreLeImpostazioniDellApp() {
    shadowOf(app).denyPermissions(permission)
    val rig = rig(explain = { false })
    rig.asks.markAsked(listOf(permission))
    rig.access.refresh()
    assertThat(rig.access.state.value).isEqualTo(NotifyPagePermission.DENIED)
    rig.actions.requestNotificationPermission()
    assertThat(rig.systemDialogs).isEqualTo(0)
    assertThat(rig.opened.single().action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
  }

  /** Android propone ancora di spiegare (rifiutato una volta sola): si puo' chiedere di nuovo, da un tocco. */
  @Test
  fun rifiutatoUnaVoltaSiPuoRichiedere() {
    shadowOf(app).denyPermissions(permission)
    val rig = rig(explain = { true })
    rig.asks.markAsked(listOf(permission))
    rig.access.refresh()
    assertThat(rig.access.state.value).isEqualTo(NotifyPagePermission.ASKABLE)
  }

  @Test
  fun canaleSpentoApreLeImpostazioniDelCanale() {
    AppNotifications(app).createChannels()
    val manager = app.getSystemService(android.app.NotificationManager::class.java)
    manager.deleteNotificationChannel("memories")
    manager.createNotificationChannel(android.app.NotificationChannel("memories", "Ricordi", android.app.NotificationManager.IMPORTANCE_NONE))
    shadowOf(app).grantPermissions(permission)
    val rig = rig()
    assertThat(rig.access.state.value).isEqualTo(NotifyPagePermission.CHANNEL_OFF)
    rig.actions.requestNotificationPermission()
    assertThat(rig.opened.single().action).isEqualTo(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
    rig.actions.openNotificationChannel()
    assertThat(rig.opened).hasSize(2)
    assertThat(rig.systemDialogs).isEqualTo(0)
  }

  /** Permesso concesso: "Consenti" non fa niente; e un permesso concesso non resta "gia' chiesto" (se poi lo toglie dalle impostazioni il dialogo torna a funzionare). */
  @Test
  fun concessoNonFaNienteEDimenticaLaVecchiaRichiesta() {
    shadowOf(app).grantPermissions(permission)
    AppNotifications(app).createChannels()
    val rig = rig()
    rig.asks.markAsked(listOf(permission))
    rig.access.refresh()
    assertThat(rig.access.state.value).isEqualTo(NotifyPagePermission.GRANTED)
    assertThat(rig.asks.asked(permission)).isFalse()
    rig.actions.requestNotificationPermission()
    assertThat(rig.systemDialogs).isEqualTo(0)
    assertThat(rig.opened).isEmpty()
  }

  /** Nessuna richiesta automatica: costruire, leggere lo stato e rileggerlo (anche col permesso negato e il canale spento) non apre niente e non chiede niente. */
  @Test
  fun nessunaRichiestaAutomatica() {
    shadowOf(app).denyPermissions(permission)
    val rig = rig()
    repeat(3) { rig.access.refresh() }
    FakeNotifyAccess(NotifyAccessState.PERMISSION_MISSING).refresh()
    assertThat(rig.systemDialogs).isEqualTo(0)
    assertThat(rig.opened).isEmpty()
    assertThat(shadowOf(app).nextStartedActivity).isNull()
    assertThat(rig.asks.asked(permission)).isFalse()
  }
}
