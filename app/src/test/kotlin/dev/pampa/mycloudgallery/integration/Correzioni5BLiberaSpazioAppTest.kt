package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.backup.BackupActionsHost
import dev.pampa.mycloudgallery.backup.BackupGraph
import dev.pampa.mycloudgallery.backup.FiveBInputs
import dev.pampa.mycloudgallery.backup.FiveBSheet
import dev.pampa.mycloudgallery.backup.FiveBUi
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAdvice
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaRewriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.di.FiveBModule
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen
import dev.pampa.mycloudgallery.feature.nas.contract.NasScreen
import dev.pampa.mycloudgallery.feature.settings.sharing.SharedPrefsSharingSettings
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMenuAction
import dev.pampa.mycloudgallery.work.BackupJobs
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
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
 * Le correzioni di "Libera spazio" e di "Elimina… → Solo dal telefono" della verifica funzionale 5B (`docs/review/funzionale-5b.md` LS-1, LS-2,
 * LS-5, LS-9, LS-10, LS-14, e il giro che una copia scaricata e modificata non faceva partire) **sul grafo vero dell'app**: `GalleryGraph` con NAS,
 * backup e 5B sopra il NAS finto di core-nas e il MediaStore finto. Le porte si prendono dal modulo Hilt dell'app (`FiveBModule`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class Correzioni5BLiberaSpazioAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()
  private val intents = FakePhoneMediaIntents(canTrash = true)

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  /** Il lavoro avviato dall'utente di "Libera spazio" (JobScheduler non c'e'): si conta e basta. */
  private class RecordingJobs : BackupJobs {
    val freeSpace = AtomicInteger()

    override fun userInitiated(trigger: BackupTrigger, confirmed: Boolean) = Unit

    override fun freeSpaceWork(): Boolean {
      freeSpace.incrementAndGet()
      return true
    }

    override fun cancelAll() = Unit
  }

  private suspend fun openRig(
    flags: FeatureFlags = DEBUG_LIKE,
    jobs: BackupJobs = RecordingJobs(),
    fence: BackupFence? = null,
    round: Boolean = true,
    rows: (FakeMediaStore) -> Unit = { store -> BackupRig.photo(store, id = 1, bytes = BackupRig.jpeg(tone = 90), takenAt = time.now() - 60 * DAY) },
  ): BackupRig {
    val backend = TestNas.backend(time)
    val device = TestDevice(app, File(temp.root, "telefono-${devices.size}"), TestPlatform(time, backend), flags).also { devices += it }
    device.graph.start()
    device.graph.foundFamily()
    val rig = BackupRig(
      app = app,
      device = device,
      account = TestNas.ADMIN_USER,
      jobs = jobs,
      fence = fence,
      fiveB = { store: FakeMediaStore ->
        FiveBInputs(
          intents = intents,
          rewriter = { FakePhoneMediaRewriter(store) },
          fileCheck = { WrittenFileCheck { _, _, _ -> WrittenCheck.VALID } },
          bootCounter = { 1L },
          rowFolders = null,
        )
      },
    ).also { rigs += it }
    rows(rig.store)
    rig.open()
    rig.chooseFolder("Pixel di prova")
    if (round) rig.round()
    return rig
  }

  /** La [FiveBUi] come la lega l'app (lo stesso codice dei `@Provides`). */
  private fun ui(rig: BackupRig): FiveBUi {
    val gallery = rig.gallery
    val lock = AppLockController().apply { updateSettings(AppLockSettings()) }
    val integrations = FiveBModule.integrations(rig.device.context, gallery, rig.device.graph, lock, flowOf(AppLockSettings()), rig.device.graph.setup, SharedPrefsSharingSettings(rig.device.context))
    return FiveBModule.fiveBUi(rig.device.context, gallery, rig.device.graph, integrations).get()
  }

  private suspend fun mediaOfRow(rig: BackupRig, rowId: Long): MediaId {
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    val view = withTimeout(Attese.TETTO_MS) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.size >= 1 } }
    return (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }.first { id ->
      catalog.media(id)?.locations?.any { (it.location as? Location.Phone)?.mediaStoreId == rowId } == true
    }
  }

  // --- LS-1 -------------------------------------------------------------------------------------------------------------------

  /**
   * LS-1, decisione dell'utente "Elimina con avviso". In release, con un account di prova (R2) o con l'account vero su una share vera (D12) il
   * backup non scrive: "Libera spazio" non c'e' e la foto **non risulta al sicuro sul NAS**. Prima "Elimina… → Solo dal telefono" del viewer non
   * eliminava piu' niente (regressione rispetto alla 4B). Adesso il foglio la segna "non sul NAS" e, dopo il suo avviso, chiede al sistema il
   * cestino di Android per le righe del telefono, come la 4B. "Libera spazio" invece resta chiuso.
   */
  @Test
  fun ls1_inReleaseSoloDalTelefonoPortaAlCestinoDiAndroidDopoLAvviso() = runBlocking<Unit> {
    val rig = openRig(flags = FeatureFlags.forBuild(debugBuild = false, nasLibrary = true))
    val ui = ui(rig)
    val id = mediaOfRow(rig, 1)
    val assessment = FiveBModule.phoneOnlyAssessor(java.util.Optional.of(ui)).assess(setOf(id))
    assertThat(assessment.advice.getValue(id)).isEqualTo(PhoneOnlyAdvice.UNAVAILABLE)
    assertThat(assessment.notOnNas).containsExactly(id)
    assertThat(assessment.freeFilter).isNull()
    // Il viewer apre il foglio: dice che puo' offrire il cestino.
    ui.mediaActions.perform(BackupMenuAction.PHONE_ONLY_DELETE, id)
    val sheet = withTimeout(Attese.TETTO_MS) { ui.sheet.first { it != null } } as FiveBSheet.PhoneOnly
    assertThat(sheet.offerTrash).isTrue()
    // Il tocco su "Elimina dal telefono": il dialogo del sistema per il cestino (30 giorni), con la riga del telefono.
    ui.trashFromPhone(setOf(id))
    withTimeout(Attese.TETTO_MS) { while (intents.trashRequests.isEmpty()) kotlinx.coroutines.delay(20) }
    val (refs, trash) = intents.trashRequests.single()
    assertThat(trash).isTrue()
    assertThat(refs.map { it.mediaStoreId }).containsExactly(1L)
    // "Libera spazio" resta chiuso in release.
    val plan = rig.backup.fiveB.freeSpace.plan(FreeSpaceFilter(excludeRecentDays = 0), false)
    assertThat(plan.itemCount).isEqualTo(0)
  }

  // --- LS-2 -------------------------------------------------------------------------------------------------------------------

  /**
   * LS-2: un giro rimasto a meta' verifica (il processo che l'aveva fatto partire e' morto) lo ritrova il lavoro avviato dall'utente, che lo
   * ospita fino a "pronto"; a "pronto", con l'app non davanti all'utente, l'app si segna di riaprire "Libera spazio" (la notifica "Pronto").
   */
  @Test
  fun ls2_ilLavoroDellUtenteRitrovaIlGiroAMetaELoPortaAPronto() = runBlocking<Unit> {
    val rig = openRig()
    val fiveB = rig.backup.fiveB
    val free = checkNotNull(fiveB.parts).freeSpace
    val plan = fiveB.freeSpace.plan(FreeSpaceFilter(excludeRecentDays = 0), false)
    assertWithMessage("la foto e' al sicuro e si puo' liberare").that(plan.itemCount).isEqualTo(1)
    val half = free.verify(plan, TestConfirmations.confirm(plan, rig.backup.clock.monotonicMs()), Duration.ZERO)
    assertThat(half.state).isEqualTo(FreeSpaceRunState.VERIFYING)
    val progress = ArrayList<FreeSpaceRunState>()
    val end = withTimeout(Attese.TETTO_MS) { fiveB.freeRuns.host { progress += it.state } }
    assertThat(checkNotNull(end).state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(progress).contains(FreeSpaceRunState.VERIFYING)
    assertThat(fiveB.freeRuns.openRequest.value).isEqualTo(end.id)
    // Il lavoro non libera niente: i lotti e il dialogo partono solo dalla pagina.
    assertThat(intents.deleteRequests).isEmpty()
    assertThat(intents.trashRequests).isEmpty()
  }

  // --- LS-5, LS-10 ----------------------------------------------------------------------------------------------------------

  /**
   * LS-5: le parti del backup si tolgono a meta' giro (un cambio d'area, l'azzeramento). Prima la facciata restituiva il giro com'era e la pagina
   * chiedeva un'altra tappa senza fine sul thread principale; adesso il giro e' fermo "non disponibile". LS-10: la pagina sa quando le parti ci
   * sono (prima un riepilogo vuoto "niente da liberare" che non si ricalcolava).
   */
  @Test
  fun ls5_ls10_senzaLePartiIlGiroSiFerma_eLaPaginaLoSa() = runBlocking<Unit> {
    val rig = openRig()
    val fiveB = rig.backup.fiveB
    assertThat(fiveB.freeRuns.ready.value).isTrue()
    val plan = fiveB.freeSpace.plan(FreeSpaceFilter(excludeRecentDays = 0), false)
    val half = checkNotNull(fiveB.parts).freeSpace.verify(plan, TestConfirmations.confirm(plan, rig.backup.clock.monotonicMs()), Duration.ZERO)
    fiveB.clear()
    assertThat(fiveB.freeRuns.ready.value).isFalse()
    val after = fiveB.freeSpace.resume(half, Duration.ZERO)
    assertThat(after).isNotSameInstanceAs(half)
    assertThat(after.state).isEqualTo(FreeSpaceRunState.BLOCKED)
    assertThat(after.blocked).isEqualTo(FreeSpaceBlock.NOT_AVAILABLE)
  }

  // --- LS-9 -------------------------------------------------------------------------------------------------------------------

  private class RecordingHost : BackupActionsHost {
    val opened = ArrayList<String>()

    override fun openScreen(screen: BackupScreen) {
      opened += screen.route
    }

    override fun openDiagnostics() = Unit

    override fun openPassword() = Unit

    override fun openNas(screen: NasScreen) = Unit

    override fun openGalleryTrash() = Unit

    override fun requestMediaAccess() = Unit

    override fun requestMediaLocation() = Unit

    override fun openStorageSettings() {
      opened += "impostazioni"
    }

    override fun openBatteryGuide() = Unit
  }

  /** LS-9: "Libera spazio" del problema "Memoria del telefono quasi piena" apre la pagina della 5B, non le impostazioni di Android. */
  @Test
  fun ls9_memoriaQuasiPienaApreLiberaSpazio() = runBlocking<Unit> {
    val rig = openRig(round = false)
    val actions = rig.actions()
    val host = RecordingHost()
    actions.host = host
    actions.perform(BackupAction.FREE_SPACE)
    assertThat(host.opened).containsExactly(BackupScreen.FREE_SPACE.route)
  }

  // --- LS-14 ------------------------------------------------------------------------------------------------------------------

  /**
   * LS-14: "Ovunque" di un media con due copie sul telefono (la fotocamera e "Scarica sul telefono" in `Pictures/MyCloud Gallery`) le toglie
   * tutte e due con un solo dialogo del cestino; prima il viewer cestinava solo quella guardata e il backup poteva ricaricare l'altra.
   */
  @Test
  fun ls14_ovunqueToglieTutteLeCopieDelTelefono() = runBlocking<Unit> {
    val bytes = BackupRig.jpeg(tone = 77)
    val rig = openRig { store ->
      BackupRig.photo(store, id = 1, bytes = bytes, takenAt = time.now() - 60 * DAY)
      BackupRig.photo(store, id = 2, bytes = bytes, takenAt = time.now() - 60 * DAY, folder = "Pictures/MyCloud Gallery/", name = "IMG_copia.jpg")
    }
    val ui = ui(rig)
    val id = mediaOfRow(rig, 1)
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    val phoneRows = checkNotNull(catalog.media(id)).locations.mapNotNull { (it.location as? Location.Phone)?.mediaStoreId }
    assertWithMessage("le due righe sono lo stesso media").that(phoneRows).containsExactly(1L, 2L)
    assertThat(ui.mediaActions.deleteEverywhereFromPhone(id)).isTrue()
    assertThat(intents.trashRequests.single().first.map { it.mediaStoreId }).containsExactly(1L, 2L)
    assertThat(intents.trashRequests.single().second).isTrue()
  }

  /** LS-14: con una sola copia sul telefono resta il cestino del viewer (come prima). */
  @Test
  fun ls14_conUnaCopiaSolaRestaIlCestinoDelViewer() = runBlocking<Unit> {
    val rig = openRig()
    val ui = ui(rig)
    val id = mediaOfRow(rig, 1)
    assertThat(ui.mediaActions.deleteEverywhereFromPhone(id)).isFalse()
    assertThat(intents.trashRequests).isEmpty()
  }

  // --- Una copia scaricata e modificata fa partire il giro ------------------------------------------------------------------

  /** La recinzione di D13 sulla cartella di "Scarica sul telefono", aperta all'inizio (la copia deve prima stare sul NAS). */
  private class DownloadsFence : BackupFence {
    @Volatile var closed = false

    override fun allowsPhoneFolder(account: String, relativePath: String): Boolean = !(closed && relativePath.startsWith(DOWNLOADS))

    override fun destinationRoot(account: String): SharePath? = null
  }

  /**
   * Nota dell'agente delle versioni (correzioni 5B, «versioni e correzioni»): una copia in `Pictures/MyCloud Gallery/` al sicuro sul NAS e poi
   * modificata in Galleria (E-E7), **da sola** (nessuna foto nuova), fa partire il giro delle foto e diventa una versione; dopo, niente giri a
   * vuoto. Con la cartella fuori dalla recinzione di D13 non conta (non diventerebbe mai un lavoro).
   */
  @Test
  fun unaCopiaScaricataEModificataDaSolaFaPartireIlGiro_maiAVuoto() = runBlocking<Unit> {
    val fence = DownloadsFence()
    val rig = openRig(fence = fence, round = false) { store ->
      BackupRig.photo(store, id = 5, bytes = BackupRig.jpeg(tone = 70), takenAt = time.now() - 3 * DAY, folder = DOWNLOADS)
    }
    val settings = rig.backup.settings
    settings.setSources(BackupSources(setOf(BackupSource.Camera, BackupSource.Folder(BackupRig.PRIMARY, DOWNLOADS))))
    assertWithMessage("la copia sul NAS").that(checkNotNull(rig.round(BackupKind.PHOTOS)).last?.done).isEqualTo(1)
    settings.setSources(BackupSources(setOf(BackupSource.Camera)))
    val media = mediaOfRow(rig, 5)
    val old = checkNotNull(rig.store.row(BackupRig.PRIMARY, 5))
    rig.store.rewrite(BackupRig.PRIMARY, 5, BackupRig.jpeg(tone = 210), old.dateModifiedMs + 60_000)
    rig.readPhone()
    val backup = rig.backup
    // Fuori dalla recinzione non diventerebbe mai un lavoro: non si conta.
    fence.closed = true
    assertThat(backup.hasWork(BackupKind.PHOTOS, BackupTrigger.SCHEDULED)).isFalse()
    fence.closed = false
    assertThat(backup.hasWork(BackupKind.PHOTOS, BackupTrigger.SCHEDULED)).isTrue()
    assertThat(backup.nextAll()).contains(BackupGraph.Next.Photos)
    rig.round(BackupKind.PHOTOS)
    assertThat(backup.fiveB.journal.forMedia(media).map { it.state }).containsExactly(UploadState.DONE)
    // Fatta la versione, il giro non riparte a vuoto.
    assertThat(backup.hasWork(BackupKind.PHOTOS, BackupTrigger.SCHEDULED)).isFalse()
    assertThat(backup.nextAll()).doesNotContain(BackupGraph.Next.Photos)
  }

  private companion object {
    val DEBUG_LIKE: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)
    const val DAY = 24 * 3_600_000L
    const val DOWNLOADS = "Pictures/MyCloud Gallery/"
  }
}
