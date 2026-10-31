package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.feature.map.map.MapLibreInit
import dev.pampa.mycloudgallery.graph.AppLocalDataEraser
import dev.pampa.mycloudgallery.graph.GalleryStopper
import dev.pampa.mycloudgallery.memories.MemoriesWiring
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.work.MemoriesWorkScheduling
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Fase 9, P13 (§1.4, D37, E-NT15): l'azzeramento completo toglie **tutto** cio' che ricordi e mappa hanno scritto sul telefono. Ricordi e mappa si fermano **per
 * primi** (quando si fermano i file ci sono ancora: l'ordine e' "ferma, poi cancella"); poi il lavoro `memories-notify` si annulla, la notifica in tendina si toglie e
 * spariscono `memories/` (scaffale, registro e preferenze delle notifiche, chi e' in tendina, musica, ritardi) e `map/` (database di MapLibre, versione del TileJSON,
 * impostazioni e camera). Se i ricordi non si fermano, non si cancella niente (la fase resta da fare).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Phase9EraseTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  }

  @After
  fun tearDown() {
    devices.forEach { it.close() }
    WorkManager.getInstance(app).cancelAllWork().result.get()
  }

  private fun device(): TestDevice = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, TestNas.backend(time))).also { devices += it }

  /** I file che ricordi e mappa scrivono davvero (i nomi sono quelli dei loro pacchetti). */
  private fun seed(noBackup: File): List<File> {
    val memories = File(noBackup, MemoriesWiring.DIR)
    val map = File(noBackup, MapLibreInit.MAP_DIR)
    return listOf(
      File(memories, "shelf.bin"), File(memories, "notified"), File(memories, "notified-memory"), File(memories, "notify-prefs"), File(memories, "music"),
      File(memories, "notify-late"), File(map, "mbgl-offline.db"), File(map, "tilejson-version"), File(map, "settings/map.preferences_pb"),
    ).onEach { file ->
      file.parentFile!!.mkdirs()
      file.writeText("dati di prova")
    }
  }

  private fun postMemoryNotification() {
    val manager = app.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(android.app.NotificationChannel("memories", "Ricordi", NotificationManager.IMPORTANCE_DEFAULT))
    NotificationManagerCompat.from(app).notify(
      AppNotifications.ID_MEMORY,
      NotificationCompat.Builder(app, "memories").setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("Un ricordo per te").build(),
    )
  }

  @Test
  fun ricordiEMappaSiFermanoPerPrimiPoiSparisconoFileLavoroENotifica() = runBlocking<Unit> {
    val device = device()
    val files = seed(device.context.noBackupFilesDir)
    var filesWhenMemoriesStopped: Boolean? = null
    var filesWhenMapStopped: Boolean? = null
    device.graph.memoriesStopper = GalleryStopper { filesWhenMemoriesStopped = files.all { it.exists() } }
    device.graph.mapStopper = GalleryStopper { filesWhenMapStopped = files.all { it.exists() } }
    MemoriesWorkScheduling.schedule(app, TimeUnit.HOURS.toMillis(3))
    postMemoryNotification()
    assertThat(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications).hasSize(1)

    val done = device.graph.eraser.eraseAll()

    assertThat(done).isTrue()
    assertThat(filesWhenMemoriesStopped).isTrue()
    assertThat(filesWhenMapStopped).isTrue()
    for (file in files) assertWithMessage(file.path).that(file.exists()).isFalse()
    assertThat(File(device.context.noBackupFilesDir, MemoriesWiring.DIR).exists()).isFalse()
    assertThat(File(device.context.noBackupFilesDir, MapLibreInit.MAP_DIR).exists()).isFalse()
    val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork(MemoriesWorkScheduling.NOTIFY_WORK).get()
    assertThat(work.none { !it.state.isFinished }).isTrue()
    assertThat(work.map { it.state }).contains(WorkInfo.State.CANCELLED)
    assertThat(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications).isEmpty()
  }

  @Test
  fun iDatiDiUnAccountTolgonoAncheRicordiEMappa() = runBlocking<Unit> {
    val device = device()
    val files = seed(device.context.noBackupFilesDir)
    MemoriesWorkScheduling.schedule(app, TimeUnit.HOURS.toMillis(3))

    // Una galleria mai partita (nessuno da fermare): i file, il lavoro e la notifica del vecchio account si tolgono lo stesso.
    val done = (device.graph.eraser as AppLocalDataEraser).eraseAccountData(stopGallery = false)

    assertThat(done).isTrue()
    for (file in files) assertWithMessage(file.path).that(file.exists()).isFalse()
    val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork(MemoriesWorkScheduling.NOTIFY_WORK).get()
    assertThat(work.none { !it.state.isFinished }).isTrue()
  }

  @Test
  fun seIRicordiNonSiFermanoNonSiCancellaNiente() = runBlocking<Unit> {
    val device = device()
    val files = seed(device.context.noBackupFilesDir)
    device.graph.memoriesStopper = GalleryStopper { throw IllegalStateException("non si ferma") }

    val done = device.graph.eraser.eraseAll()

    assertThat(done).isFalse()
    for (file in files) assertWithMessage(file.path).that(file.exists()).isTrue()
  }
}
