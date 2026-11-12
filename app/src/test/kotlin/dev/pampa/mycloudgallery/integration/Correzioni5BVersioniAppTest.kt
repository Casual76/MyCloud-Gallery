package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.backup.BackupGraph
import dev.pampa.mycloudgallery.backup.FiveBInputs
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.SafeguardStart
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.versions.VersionHold
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaRewriter
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteRecovery
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaRewriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.work.BackupJobs
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 * Le correzioni delle versioni della 5B (`docs/review/funzionale-5b.md`: VE-1, VE-3, VE-9, VE-10, MC-3, LA-3; `docs/review/5b-fase5b.md`: R9) **sul grafo
 * vero dell'app**: `GalleryGraph` con NAS, backup e 5B sopra il NAS finto di core-nas e il MediaStore finto. Ogni test falliva prima.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class Correzioni5BVersioniAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()
  private val phoneIntents = FakePhoneMediaIntents(canTrash = true)
  private val anyCopyIsValid = WrittenFileCheck { _, _, _ -> WrittenCheck.VALID }

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  /** I lavori chiesti dal grafo (WorkManager e JobScheduler non ci sono): quelli delle versioni (VE-10, MC-3) e quelli dell'utente (VE-3). */
  private class RecordingJobs : BackupJobs {
    val versionWork = CopyOnWriteArrayList<BackupKind>()
    val userStarts = AtomicInteger()

    override fun userInitiated(trigger: BackupTrigger, confirmed: Boolean): Boolean {
      userStarts.incrementAndGet()
      return true
    }

    override fun versionWork(kind: BackupKind) {
      versionWork += kind
    }

    override fun cancelAll() = Unit
  }

  /** Il riscrittore del telefono finto che conta i recuperi all'avvio (R9). */
  private class CountingRewriter(private val inner: FakePhoneMediaRewriter) : PhoneMediaRewriter by inner {
    val recoveries = AtomicInteger()

    override suspend fun recover(): RewriteRecovery {
      recoveries.incrementAndGet()
      return RewriteRecovery(restored = 1, settled = 0, pending = 0)
    }
  }

  private var rewriter: CountingRewriter? = null

  private fun device(name: String, backend: InMemorySmbBackend): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend), DEBUG_LIKE).also { devices += it }

  private suspend fun openRig(
    backend: InMemorySmbBackend,
    jobs: BackupJobs,
    fence: BackupFence? = null,
    rows: (FakeMediaStore) -> Unit,
  ): BackupRig {
    val phone = device("telefono-${devices.size}", backend)
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = BackupRig(
      app = app,
      device = phone,
      account = TestNas.ADMIN_USER,
      jobs = jobs,
      fence = fence,
      fiveB = { store ->
        val counting = CountingRewriter(FakePhoneMediaRewriter(store)).also { rewriter = it }
        FiveBInputs(intents = phoneIntents, rewriter = { counting }, fileCheck = { anyCopyIsValid }, bootCounter = { 1L }, rowFolders = null)
      },
    ).also { rigs += it }
    rows(rig.store)
    rig.open()
    rig.chooseFolder("Pixel di prova")
    return rig
  }

  /** Il media della riga del telefono [rowId]. */
  private suspend fun mediaOfRow(rig: BackupRig, rowId: Long): MediaId {
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    val view = withTimeout(Attese.TETTO_MS) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.size >= 1 } }
    return (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }.first { id ->
      catalog.media(id)?.locations?.any { (it.location as? Location.Phone)?.mediaStoreId == rowId } == true
    }
  }

  private suspend fun mediaAt(rig: BackupRig, path: String): MediaId {
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    val info = checkNotNull(catalog.nasLocationAt(Location.Nas(AccountId.of(TestNas.ADMIN_USER), TestNas.ADMIN_USER, NasPath.of(path)))) { path }
    return checkNotNull(catalog.mediaIdOfLocation(info.locationId))
  }

  private fun video(store: FakeMediaStore, id: Long, takenAt: Long) {
    store.put(
      MediaStoreRow(
        volume = BackupRig.PRIMARY, id = id, type = RowType.VIDEO, displayName = "VID_$id.mp4", relativePath = "DCIM/Camera/", mime = "video/mp4",
        size = 60_000, dateModifiedMs = takenAt + 1_000, dateTakenMs = takenAt, durationMs = 3_000,
      ),
      FakeMediaContent.mp4(60_000, seed = id),
    )
  }

  /** Una foto e un video al sicuro; poi il video si modifica sul telefono (Samsung "Salva") e il suo lavoro `VERSION` nasce dal piano dei video. */
  private suspend fun editedVideo(jobs: RecordingJobs = RecordingJobs()): Pair<BackupRig, Long> {
    val backend = TestNas.backend(time)
    val rig = openRig(backend, jobs) { store ->
      BackupRig.photo(store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - 3 * DAY)
      video(store, 77, time.now() - 2 * DAY)
    }
    assertWithMessage("foto").that(checkNotNull(rig.round(BackupKind.PHOTOS)).last?.done).isEqualTo(1)
    assertWithMessage("video").that(checkNotNull(rig.round(BackupKind.VIDEOS)).last?.done).isEqualTo(1)
    val old = checkNotNull(rig.store.row(BackupRig.PRIMARY, 77))
    rig.store.rewrite(BackupRig.PRIMARY, 77, FakeMediaContent.mp4(61_000, seed = 99), old.dateModifiedMs + 60_000)
    rig.readPhone()
    val backup = rig.backup
    val versions = checkNotNull(backup.fiveB.parts?.versions)
    val planned = versions.planPhoneVersions(checkNotNull(backup.parts).planner.next(BackupKind.VIDEOS, 10, BackupTrigger.SCHEDULED))
    assertWithMessage("lavori: $planned").that(planned).hasSize(1)
    return rig to planned.single().id.value
  }

  // --- VE-1 --------------------------------------------------------------------------------------------------------------------

  /**
   * VE-1: il lavoro `VERSION` di un video modificato saliva con il giro delle **foto** (regole delle foto, tempo di "Proteggi subito"), perche'
   * `hasOpenVersionWork` chiedeva le foto e il motore delle versioni guidava tutti i lavori. Adesso lo vede e lo guida solo il lavoro dei video.
   */
  @Test
  fun ve1_unaVersioneDiUnVideoGiraSoloColLavoroDeiVideo() = runBlocking<Unit> {
    val (rig, jobId) = editedVideo()
    val backup = rig.backup
    assertThat(backup.hasWork(BackupKind.VIDEOS, BackupTrigger.SCHEDULED)).isTrue()
    assertThat(backup.hasWork(BackupKind.PHOTOS, BackupTrigger.SCHEDULED)).isFalse()
    assertThat(backup.nextAll()).containsExactly(BackupGraph.Next.Videos)

    // Un giro delle foto (anche dell'utente) non lo porta avanti; quello dei video si'.
    rig.round(BackupKind.PHOTOS)
    assertThat(backup.fiveB.journal.job(dev.pampa.mycloudgallery.core.backup.versions.VersionJobId(jobId))!!.state).isEqualTo(UploadState.PLANNED)
    rig.round(BackupKind.VIDEOS)
    assertThat(backup.fiveB.journal.job(dev.pampa.mycloudgallery.core.backup.versions.VersionJobId(jobId))!!.state).isEqualTo(UploadState.DONE)
  }

  // --- VE-9, VE-10 --------------------------------------------------------------------------------------------------------------

  /** Una foto al sicuro, modificata sul telefono; il suo lavoro `VERSION` e' fermo perche' il PC ha cambiato il file sul NAS (`SOURCE_CHANGED`). */
  private suspend fun heldPhoto(jobs: RecordingJobs): BackupRig {
    val backend = TestNas.backend(time)
    val rig = openRig(backend, jobs) { store -> BackupRig.photo(store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - 3 * DAY) }
    assertThat(checkNotNull(rig.round(BackupKind.PHOTOS)).last?.done).isEqualTo(1)
    val old = checkNotNull(rig.store.row(BackupRig.PRIMARY, 1))
    rig.store.rewrite(BackupRig.PRIMARY, 1, BackupRig.jpeg(tone = 200), old.dateModifiedMs + 60_000)
    rig.readPhone()
    val backup = rig.backup
    val job = checkNotNull(backup.fiveB.parts?.versions)
      .planPhoneVersions(checkNotNull(backup.parts).planner.next(BackupKind.PHOTOS, 10, BackupTrigger.SCHEDULED)).single()
    backup.fiveB.journal.update(job.copy(hold = VersionHold.SOURCE_CHANGED))
    return rig
  }

  /**
   * VE-9: una versione ferma per il PC e' la versione attuale del media, senza copia privata verificata: contava fra i media "in coda" e la pagina
   * diceva per sempre "1 in coda · prossimo giro". Adesso dice "Versioni in attesa del computer".
   */
  @Test
  fun ve9_unaVersioneFermaPerIlPcSiVedeNellaPagina() = runBlocking<Unit> {
    val rig = heldPhoto(RecordingJobs())
    val backup = rig.backup
    backup.kick()
    val status = withTimeout(Attese.TETTO) {
      backup.status.first { it is BackupStatus.Problem && it.code == BackupProblemCode.SOURCE_CHANGED }
    }
    assertThat(status).isEqualTo(BackupStatus.Problem(BackupProblemCode.SOURCE_CHANGED, 1))
  }

  /** VE-10: a scansione del NAS finita le versioni ferme per il PC ripartono con il lavoro del loro tipo (prima: il giro periodico, fino a 12 ore). */
  @Test
  fun ve10_dopoLaScansioneLeVersioniFermeChiedonoIlLavoro() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val rig = heldPhoto(jobs)
    jobs.versionWork.clear()
    rig.backup.afterNasScan()
    assertThat(jobs.versionWork).containsExactly(BackupKind.PHOTOS)
  }

  /**
   * VE-10: Samsung "Ripristina originale" (E-E2): la lettura del telefono riconosce il ritorno all'originale e lo scambio sul NAS si pianifica;
   * prima solo nel diario (lo prendeva il giro periodico), adesso chiede subito il lavoro delle foto.
   */
  @Test
  fun ve10_loScambioDopoUnRipristinoDelTelefonoChiedeIlLavoro() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val backend = TestNas.backend(time)
    val original = BackupRig.jpeg(tone = 40)
    val rig = openRig(backend, jobs) { store -> BackupRig.photo(store, id = 1, bytes = original, takenAt = time.now() - 3 * DAY) }
    assertThat(checkNotNull(rig.round(BackupKind.PHOTOS)).last?.done).isEqualTo(1)
    val old = checkNotNull(rig.store.row(BackupRig.PRIMARY, 1))
    rig.store.rewrite(BackupRig.PRIMARY, 1, BackupRig.jpeg(tone = 200), old.dateModifiedMs + 60_000)
    rig.readPhone()
    assertWithMessage("versione").that(checkNotNull(rig.round(BackupKind.PHOTOS)).last?.failed).isEqualTo(0)
    jobs.versionWork.clear()
    // Samsung "Ripristina originale": la riga torna ai byte dell'originale.
    rig.store.rewrite(BackupRig.PRIMARY, 1, original, old.dateModifiedMs + 120_000)
    rig.readPhone()
    withTimeout(Attese.TETTO) { while (jobs.versionWork.isEmpty()) delay(50) }
    assertThat(jobs.versionWork).contains(BackupKind.PHOTOS)
  }

  // --- LA-3 ---------------------------------------------------------------------------------------------------------------------

  /**
   * La recinzione del telefono di D13 come la vede il backup con un account di prova: [closed] chiude `Pictures/MyCloud Gallery/` (non e' fra le
   * cartelle seminate). Aperta all'inizio, perche' la copia scaricata deve prima stare sul NAS; nessuna radice di destinazione.
   */
  private class DownloadsFence : BackupFence {
    @Volatile var closed = false

    override fun allowsPhoneFolder(account: String, relativePath: String): Boolean = !(closed && relativePath.startsWith(DOWNLOADS))

    override fun destinationRoot(account: String): SharePath? = null
  }

  /**
   * Una copia in `Pictures/MyCloud Gallery/` al sicuro sul NAS (come dopo "Scarica sul telefono"; qui un giro con la cartella fra le sorgenti,
   * poi tolta) e modificata in Galleria (E-E7): il candidato delle versioni fuori dalle sorgenti che `FiveBGraph` aggiunge al piano. Con lei una
   * foto nuova della fotocamera, perche' il giro parta: i candidati di E-E7 da soli non lo fanno partire (`hasWork` non li conta).
   */
  private suspend fun editedDownload(fence: DownloadsFence): Pair<BackupRig, MediaId> {
    val rig = openRig(TestNas.backend(time), RecordingJobs(), fence) { store ->
      BackupRig.photo(store, id = 5, bytes = BackupRig.jpeg(tone = 70), takenAt = time.now() - 3 * DAY, folder = DOWNLOADS)
    }
    val settings = rig.backup.settings
    settings.setSources(BackupSources(setOf(BackupSource.Camera, BackupSource.Folder(BackupRig.PRIMARY, DOWNLOADS))))
    assertWithMessage("la copia sul NAS").that(checkNotNull(rig.round(BackupKind.PHOTOS)).last?.done).isEqualTo(1)
    settings.setSources(BackupSources(setOf(BackupSource.Camera)))
    val media = mediaOfRow(rig, 5)
    val old = checkNotNull(rig.store.row(BackupRig.PRIMARY, 5))
    rig.store.rewrite(BackupRig.PRIMARY, 5, BackupRig.jpeg(tone = 210), old.dateModifiedMs + 60_000)
    BackupRig.photo(rig.store, id = 6, bytes = BackupRig.jpeg(tone = 120), takenAt = time.now() - DAY)
    rig.readPhone()
    return rig to media
  }

  /** LA-3, il caso di controllo: senza recinzione la copia scaricata e modificata diventa una versione e sale (E-E7). */
  @Test
  fun la3_senzaRecinzioneLaCopiaScaricataModificataDiventaUnaVersione() = runBlocking<Unit> {
    val (rig, media) = editedDownload(DownloadsFence())
    rig.round(BackupKind.PHOTOS)
    assertThat(rig.backup.fiveB.journal.forMedia(media).map { it.state }).containsExactly(UploadState.DONE)
  }

  /**
   * LA-3: in debug con un account di prova (D13) la lettura del telefono recintata risponde `NOT_SELECTED` per `Pictures/MyCloud Gallery/`.
   * Prima il candidato di E-E7 diventava lo stesso un lavoro `VERSION` che aspettava per sempre, e `nextAll` continuava a chiedere il giro delle
   * foto. Adesso i candidati di quella cartella passano dalla recinzione, come le sorgenti del piano: nessun lavoro.
   */
  @Test
  fun la3_unaCopiaScaricataFuoriDallaRecinzioneNonDiventaUnLavoroCheAspettaPerSempre() = runBlocking<Unit> {
    val fence = DownloadsFence()
    val (rig, media) = editedDownload(fence)
    fence.closed = true
    rig.round(BackupKind.PHOTOS)
    assertThat(rig.backup.fiveB.journal.forMedia(media)).isEmpty()
    assertThat(rig.backup.hasWork(BackupKind.PHOTOS, BackupTrigger.SCHEDULED)).isFalse()
    assertThat(rig.backup.nextAll()).doesNotContain(BackupGraph.Next.Photos)
  }

  // --- MC-3 ---------------------------------------------------------------------------------------------------------------------

  /**
   * MC-3: "Modifica con…" di un solo-NAS: la versione preparata diventa un lavoro `VERSION` e **chiede subito** il lavoro delle foto. Prima scriveva
   * solo nel diario e la versione restava nell'app fino al giro periodico (con Notte, di notte).
   */
  @Test
  fun mc3_laModificaDiUnSoloNasChiedeSubitoIlLavoro() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val backend = TestNas.backend(time)
    backend.put(TestNas.ADMIN_USER, "Archivio/gita.jpg", BackupRig.jpeg(tone = 60), lastWriteMs = time.now() - 2 * DAY)
    val rig = openRig(backend, jobs) { store -> BackupRig.photo(store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - 3 * DAY) }
    assertThat(checkNotNull(rig.round(BackupKind.PHOTOS)).last?.done).isEqualTo(1)
    val id = mediaAt(rig, "Archivio/gita.jpg")
    val backup = rig.backup
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    // Il risultato dell'editor: file preparato e versione nel catalogo (come `DefaultEditSessions.recordEdited`).
    val edited = BackupRig.jpeg(tone = 160)
    val sha = Sha256.of(edited)
    val work = backup.fiveB.staged.newWorkFile().apply { writeBytes(edited) }
    backup.fiveB.staged.keep(work, sha, id)
    val parent = checkNotNull(catalog.media(id)).currentVersionId
    val version = checkNotNull(catalog.addEditedVersion(id, parent, sha, QuickHashAlgorithm.compute(edited.size.toLong(), edited, ByteArray(0)), edited.size.toLong()))
    jobs.versionWork.clear()
    assertThat(backup.fiveB.planStaged(id, version, sha, edited.size.toLong())).isTrue()
    assertThat(jobs.versionWork).containsExactly(BackupKind.PHOTOS)
    // MC-4: questo solo-NAS sta nell'area privata, quindi la modifica si puo' salvare sul NAS.
    assertThat(backup.fiveB.editVersionReady(id)).isTrue()
  }

  // --- VE-3 ---------------------------------------------------------------------------------------------------------------------

  /**
   * VE-3, "Metti al sicuro adesso": per una foto della fotocamera parte il lavoro dell'utente; per una foto di una cartella che il backup non
   * guarda il foglio lo dice (prima chiedeva "Fai ora" e non cambiava niente); per un solo-NAS non riletto parte la verifica sul momento della
   * sua copia, che la mette al sicuro.
   */
  @Test
  fun ve3_mettiAlSicuroAdessoFaCioCheServeAQuelMedia() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val backend = TestNas.backend(time)
    backend.put(TestNas.ADMIN_USER, "Archivio/gita.jpg", BackupRig.jpeg(tone = 60), lastWriteMs = time.now() - 2 * DAY)
    val rig = openRig(backend, jobs) { store ->
      BackupRig.photo(store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - 3 * DAY)
      BackupRig.photo(store, id = 2, bytes = BackupRig.jpeg(tone = 90), takenAt = time.now() - 4 * DAY, folder = "Pictures/Altro/")
    }
    val backup = rig.backup
    val camera = mediaOfRow(rig, 1)
    val outside = mediaOfRow(rig, 2)
    val nasOnly = mediaAt(rig, "Archivio/gita.jpg")

    assertThat(backup.safeguard(camera)).isEqualTo(SafeguardStart.STARTED)
    assertThat(jobs.userStarts.get()).isEqualTo(1)
    assertThat(backup.safeguard(outside)).isEqualTo(SafeguardStart.NOT_IN_SOURCES)
    assertThat(jobs.userStarts.get()).isEqualTo(1)

    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    assertThat(checkNotNull(catalog.media(nasOnly)).locations.single { it.isNas }.verifiedAtMillis).isNull()
    assertThat(backup.safeguard(nasOnly)).isEqualTo(SafeguardStart.STARTED)
    withTimeout(Attese.TETTO) {
      while (checkNotNull(catalog.media(nasOnly)).locations.single { it.isNas }.verifiedAtMillis == null) delay(50)
    }
  }

  // --- R9 -----------------------------------------------------------------------------------------------------------------------

  /** R9: all'avvio le riscritture del telefono lasciate a meta' da un processo morto si recuperano (prima nessuno le cercava). */
  @Test
  fun r9_allAvvioSiRecuperanoLeRiscrittureInterrotte() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val rig = openRig(backend, RecordingJobs()) { store -> BackupRig.photo(store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - 3 * DAY) }
    rig.backup.atStart()
    assertThat(checkNotNull(rewriter).recoveries.get()).isAtLeast(1)
  }

  private companion object {
    const val DAY = 24L * 3_600_000L
    const val DOWNLOADS = "Pictures/MyCloud Gallery/"
    val DEBUG_LIKE: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)
  }
}
