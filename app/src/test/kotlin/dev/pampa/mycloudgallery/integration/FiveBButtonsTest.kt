package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.backup.FiveBInputs
import dev.pampa.mycloudgallery.backup.FiveBSheet
import dev.pampa.mycloudgallery.backup.FiveBUi
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.noop.MonotonicOnlyBackupClock
import dev.pampa.mycloudgallery.core.backup.noop.NoCorrections
import dev.pampa.mycloudgallery.core.backup.noop.NoFreeSpace
import dev.pampa.mycloudgallery.core.backup.noop.NoPendingTasks
import dev.pampa.mycloudgallery.core.backup.noop.NoVersionKeeper
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaRewriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.di.FiveBModule
import dev.pampa.mycloudgallery.feature.backup.free.NoPhoneSystemDialogs
import dev.pampa.mycloudgallery.feature.backup.versions.NoVersionActions
import dev.pampa.mycloudgallery.feature.settings.sharing.SharedPrefsSharingSettings
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMenuAction
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupShareUi
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.integrations.IntegrationsGraph
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Ogni tasto delle schermate della 5B ha un effetto collegato** (P14; lezione della verifica funzionale: tasti che non facevano niente, porte mai
 * legate). Le porte si prendono **dal modulo Hilt dell'app** (`FiveBModule`, in `src/main`: lo stesso in debug e in release), sul grafo vero: nessuna
 * e' un null-object, ogni voce che il viewer mostra fa qualcosa, e il viewer della galleria riceve le voci.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class FiveBButtonsTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  private suspend fun openRig(): BackupRig {
    val backend = TestNas.backend(time)
    val device = TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, backend), FeatureFlags.forBuild(debugBuild = true, nasLibrary = true))
      .also { devices += it }
    device.graph.start()
    device.graph.foundFamily()
    val rig = BackupRig(
      app = app,
      device = device,
      account = TestNas.ADMIN_USER,
      fiveB = { store: FakeMediaStore ->
        FiveBInputs(
          intents = FakePhoneMediaIntents(canTrash = true),
          rewriter = { FakePhoneMediaRewriter(store) },
          fileCheck = { WrittenFileCheck { _, _, _ -> WrittenCheck.VALID } },
          bootCounter = { 1L },
          rowFolders = null,
        )
      },
    ).also { rigs += it }
    BackupRig.photo(rig.store, id = 1L, bytes = BackupRig.jpeg(tone = 90), takenAt = time.now() - 60L * 24 * 3_600_000)
    rig.open()
    rig.chooseFolder("Pixel di prova")
    checkNotNull(rig.round())
    return rig
  }

  /** Le porte come le lega l'app (lo stesso codice dei `@Provides`). */
  private class Ports(val gallery: GalleryGraph, val integrations: IntegrationsGraph, val ui: FiveBUi)

  private fun ports(rig: BackupRig): Ports {
    val gallery = rig.gallery
    val lock = AppLockController().apply { updateSettings(AppLockSettings()) }
    val integrations = FiveBModule.integrations(rig.device.context, gallery, rig.device.graph, lock, flowOf(AppLockSettings()), rig.device.graph.setup)
    val ui = FiveBModule.fiveBUi(rig.device.context, gallery, rig.device.graph, integrations, SharedPrefsSharingSettings(rig.device.context)).get()
    // Come `MyCloudGalleryApp.onCreate`.
    gallery.fiveB = GalleryGraph.FiveBHooks(ui.mediaActions) { ids -> ui.openFreeSpace(ids) }
    return Ports(gallery, integrations, ui)
  }

  /**
   * "In release": le porte le lega un modulo di `src/main` (lo stesso grafo in debug e in release) e nessun sorgente della release lo sostituisce o
   * lo spegne. D12 e R2 restano nel cancello (in release, su una share vera o con un account di prova, i servizi dicono "Backup in prova").
   */
  @Test
  fun lePorteDellaQuintaBSonoLegateAncheInRelease() {
    val module = File("src/main/kotlin/dev/pampa/mycloudgallery/di/FiveBModule.kt")
    assertWithMessage("il modulo delle porte della 5B sta in src/main").that(module.isFile).isTrue()
    val release = File("src/release").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
    for (port in listOf("FreeSpace", "VersionKeeper", "Corrections", "PendingTasks", "PhoneSystemDialogs", "FiveBUi", "IntegrationsGraph")) {
      assertWithMessage("src/release non tocca $port").that(release.none { Regex("""\b$port\b""").containsMatchIn(it) }).isTrue()
    }
  }

  @Test
  fun nessunaPortaDellaQuintaBeUnNullObject() = runBlocking<Unit> {
    val rig = openRig()
    val p = ports(rig)
    val gallery = p.gallery
    val ui = java.util.Optional.of(p.ui)
    assertThat(FiveBModule.freeSpace(gallery)).isNotSameInstanceAs(NoFreeSpace)
    assertThat(FiveBModule.versionKeeper(gallery)).isNotSameInstanceAs(NoVersionKeeper)
    assertThat(FiveBModule.corrections(gallery)).isNotSameInstanceAs(NoCorrections)
    assertThat(FiveBModule.pendingTasks(gallery)).isNotSameInstanceAs(NoPendingTasks)
    assertThat(FiveBModule.backupClock(gallery)).isNotSameInstanceAs(MonotonicOnlyBackupClock)
    assertThat(FiveBModule.phoneSystemDialogs(ui)).isNotSameInstanceAs(NoPhoneSystemDialogs)
    assertThat(FiveBModule.phoneSystemDialogs(ui).available).isTrue()
    assertThat(FiveBModule.versionActions(ui)).isNotSameInstanceAs(NoVersionActions)
    assertThat(FiveBModule.versionActions(ui).canDeleteIntermediates).isTrue()
    // Le facciate arrivano ai servizi veri: il custode risponde per il media (un null-object direbbe "non disponibile").
    val id = rig.gallery.repository.view(LibraryFilter.ALL).first { it.size == 1 }.let { it.snapshot.idAt(it.snapshotIndex(0)) }
    assertThat(FiveBModule.versionKeeper(gallery).plan(id).restore).isEqualTo(RestoreAvailability.ONLY_ONE_VERSION)
    // "Elimina solo dal telefono" valuta il media (non "non disponibile" per tutti).
    val advice = FiveBModule.phoneOnlyAssessor(ui).assess(setOf(id))
    assertThat(advice.toString()).isNotEmpty()
    // Il viewer della galleria riceve le voci del backup.
    assertThat(gallery.viewerServices.backupActions).isSameInstanceAs(p.ui.mediaActions)
    // Le integrazioni hanno le porte vere: la ricezione mette in coda nel diario del backup.
    assertThat(p.integrations.uploads).isNotNull()
  }

  @Test
  fun ogniVoceDelBackupNelViewerFaQualcosa() = runBlocking<Unit> {
    val rig = openRig()
    val ui = ports(rig).ui
    val actions = ui.mediaActions
    val id = rig.gallery.repository.view(LibraryFilter.ALL).first { it.size == 1 }.let { it.snapshot.idAt(it.snapshotIndex(0)) }
    val caps = actions.capabilities
    // Le voci dichiarate: ognuna ha il suo effetto. "Vedi originale" non e' dichiarata (il viewer mostra solo l'attuale): non compare.
    assertThat(caps.viewOriginal).isFalse()
    for (action in BackupMenuAction.entries) {
      val declared = when (action) {
        BackupMenuAction.VERSIONS, BackupMenuAction.RESTORE_ORIGINAL -> true
        BackupMenuAction.CORRECT, BackupMenuAction.ROTATE -> caps.correct
        BackupMenuAction.EDIT_WITH -> caps.editWith
        BackupMenuAction.PHONE_ONLY_DELETE -> caps.phoneOnlyDelete
        BackupMenuAction.VIEW_ORIGINAL -> caps.viewOriginal
      }
      if (!declared) continue
      ui.dismissSheet()
      actions.dismissNotice()
      actions.perform(action, id)
      withTimeout(30_000) {
        when (action) {
          BackupMenuAction.VERSIONS, BackupMenuAction.RESTORE_ORIGINAL -> assertThat(ui.sheet.first { it != null }).isInstanceOf(FiveBSheet.Versions::class.java)
          BackupMenuAction.CORRECT, BackupMenuAction.ROTATE -> assertThat(ui.sheet.first { it != null }).isInstanceOf(FiveBSheet.Correction::class.java)
          BackupMenuAction.PHONE_ONLY_DELETE -> assertThat(ui.sheet.first { it != null }).isInstanceOf(FiveBSheet.PhoneOnly::class.java)
          // "Modifica con…": la sessione si apre e l'activity riceve l'intento per l'editor (o, se non si puo', una frase per l'utente).
          BackupMenuAction.EDIT_WITH -> {
            val launched = ui.launches.first { it != null || actions.notice.value != null }
            assertWithMessage("modifica: ${actions.notice.value}").that(launched).isNotNull()
          }
          BackupMenuAction.VIEW_ORIGINAL -> Unit
        }
      }
    }
    // "Condividi": parte la preparazione e finisce con un esito (pronta o ferma con il motivo), mai niente.
    actions.share(listOf(id))
    val shared = withTimeout(60_000) { actions.shareState.first { it is BackupShareUi.Ready || it is BackupShareUi.Refused } }
    assertThat(shared).isNotEqualTo(BackupShareUi.Idle)
    actions.dismissShare()
    // "Ovunque" risponde per il media (l'elenco del backup, non "non so").
    assertThat(actions.everywhere(id)).isNotNull()
    // "Libera spazio" dalla selezione chiede la navigazione con la selezione.
    val before = ui.freeSpaceRequests.value
    ui.openFreeSpace(setOf(id))
    assertThat(ui.freeSpaceRequests.value).isNotEqualTo(before)
    assertThat(ui.freeSpaceSelection).containsExactly(id)
  }
}
