package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.backup.FiveBInputs
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionOutcome
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRequest
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionRoute
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaRewriter
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.di.FiveBModule
import dev.pampa.mycloudgallery.integrations.AppIncomingMetadata
import dev.pampa.mycloudgallery.work.BackupJobs
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
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
 * Le correzioni dal campo, gruppo A (`docs/prove-campo-esiti.md`, blocco B; `docs/decisioni.md`, "Correzioni dal campo, gruppo A") **sul grafo vero
 * dell'app**: `GalleryGraph` con NAS, backup e 5B sopra il NAS finto di core-nas e il MediaStore finto. Ogni test falliva prima della sua correzione.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class CampoAAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()
  private val anyCopyIsValid = WrittenFileCheck { _, _, _ -> WrittenCheck.VALID }

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  /** I lavori chiesti dal grafo (WorkManager e JobScheduler non ci sono): quelli a tappe del tipo di un media. */
  private class RecordingJobs : BackupJobs {
    val versionWork = CopyOnWriteArrayList<BackupKind>()
    val quickWork = java.util.concurrent.atomic.AtomicInteger()

    override fun quickWork() {
      quickWork.incrementAndGet()
    }

    override fun userInitiated(trigger: BackupTrigger, confirmed: Boolean) = Unit

    override fun versionWork(kind: BackupKind) {
      versionWork += kind
    }

    override fun cancelAll() = Unit
  }

  /** Un telefono con la galleria montata e la famiglia pronta, **senza** avviare ne' il NAS ne' il backup (CA-1 li fa partire a mano, in ordine). */
  private suspend fun newRig(jobs: BackupJobs = RecordingJobs()): BackupRig {
    val backend = TestNas.backend(time)
    val phone = TestDevice(app, File(temp.root, "telefono-${devices.size}"), TestPlatform(time, backend), DEBUG_LIKE).also { devices += it }
    phone.graph.start()
    phone.graph.foundFamily()
    return BackupRig(
      app = app,
      device = phone,
      account = TestNas.ADMIN_USER,
      jobs = jobs,
      fiveB = { store ->
        FiveBInputs(
          intents = FakePhoneMediaIntents(canTrash = true),
          rewriter = { FakePhoneMediaRewriter(store) },
          fileCheck = { anyCopyIsValid },
          bootCounter = { 1L },
          rowFolders = null,
        )
      },
    ).also { rigs += it }
  }

  private suspend fun openRig(jobs: BackupJobs = RecordingJobs(), rows: (FakeMediaStore) -> Unit): BackupRig {
    val rig = newRig(jobs)
    rows(rig.store)
    rig.open()
    rig.chooseFolder("Pixel di prova")
    return rig
  }

  /** Il media della riga del telefono [rowId]. */
  private suspend fun mediaOfRow(rig: BackupRig, rowId: Long): MediaId {
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    val view = withTimeout(60_000) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.size >= 1 } }
    return (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }.first { id ->
      catalog.media(id)?.locations?.any { (it.location as? Location.Phone)?.mediaStoreId == rowId } == true
    }
  }

  // --- D3 (F36): "Correggi data e luogo" ----------------------------------------------------------------------------------------------

  /**
   * F36: una foto del telefono al sicuro, "Correggi data e luogo" con un giorno dopo. Il catalogo ha la versione corretta **con i metadati letti dai byte
   * corretti**: la Libreria ordina la foto con la data nuova (prima la versione nasceva senza metadati e restava la data di prima, finche' una lettura
   * del telefono non rileggeva il file).
   */
  @Test
  fun d3_laCorrezioneDellaDataSiVedeSubitoInLibreria() = runBlocking<Unit> {
    val old = Instant.parse("2024-03-12T10:15:30Z").toEpochMilli()
    val rig = openRig { store ->
      BackupRig.photo(store, id = 1, bytes = BackupRig.withExif(BackupRig.jpeg(tone = 40), "2024:03:12 10:15:30"), takenAt = old)
    }
    assertWithMessage("foto").that(checkNotNull(rig.round(BackupKind.PHOTOS)).last?.done).isEqualTo(1)
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    // Il lettore dei metadati dei file locali lo lega `IntegrationsGraph` nell'app vera.
    rig.backup.fiveB.incomingQueue.metadata = AppIncomingMetadata(rig.gallery.newExtractor(), { catalog })
    val id = mediaOfRow(rig, 1)
    val corrected = Instant.parse("2024-03-13T10:15:30Z").toEpochMilli()

    val corrections = FiveBModule.corrections(rig.gallery)
    val plan = corrections.plan(id, CorrectionRequest(MetadataChange(takenAtUtcMillis = corrected, offsetMinutes = 0)))
    assertThat(plan.readiness).isEqualTo(CorrectionReadiness.READY)
    assertThat(plan.route).isEqualTo(CorrectionRoute.PHONE)
    val confirmation = TestConfirmations.confirm(plan, System.nanoTime() / 1_000_000)
    assertThat(corrections.apply(plan, confirmation, null)).isInstanceOf(CorrectionOutcome.NeedsWriteGrant::class.java)
    // Il dialogo di Android, accettato.
    rig.store.resolveRequest(
      FakeMediaStore.SystemRequest.WRITE,
      plan.phoneRows.map { dev.pampa.mycloudgallery.core.model.MediaRef.Phone(it.volume, it.mediaStoreId, "image/jpeg") },
      FakeMediaStore.DialogAnswer.Accept,
    )
    val outcome = corrections.apply(plan, confirmation, PhoneWriteGrant(plan.phoneRows.toSet()))
    assertWithMessage("$outcome").that(outcome).isInstanceOf(CorrectionOutcome.Done::class.java)

    val media = checkNotNull(catalog.media(id))
    assertThat(media.currentVersionId).isEqualTo((outcome as CorrectionOutcome.Done).version)
    assertWithMessage("data della versione corretta").that(catalog.versionMetadata(media.currentVersionId)?.captureTime?.utcMillis).isEqualTo(corrected)
    assertWithMessage("ordine in Libreria").that(checkNotNull(rig.gallery.database.mediaDao().get(id.value)).sortTime).isEqualTo(corrected)
  }

  // --- D6 (VE-10, B-S7): una modifica fatta ad app chiusa -------------------------------------------------------------------------

  /**
   * VE-10 sul campo: "Salva" in Galleria con l'app chiusa (`force-stop`), poi l'app si apre. La lettura del telefono di primo piano vede la riga
   * cambiata e la versione nasce nel catalogo, ma nessuno chiedeva il lavoro delle foto: la versione aspettava il giro periodico (70 minuti). Adesso la
   * fine della lettura di primo piano (`BackupGraph.afterPhoneRead`, chiamata da `GalleryGraph`) chiede il lavoro del tipo giusto.
   */
  @Test
  fun d6_unaModificaVistaDallaLetturaDiPrimoPianoChiedeIlLavoroDelleFoto() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val rig = openRig(jobs) { store -> BackupRig.photo(store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - 3 * DAY) }
    assertWithMessage("foto").that(checkNotNull(rig.round(BackupKind.PHOTOS)).last?.done).isEqualTo(1)
    val old = checkNotNull(rig.store.row(BackupRig.PRIMARY, 1))
    rig.store.rewrite(BackupRig.PRIMARY, 1, BackupRig.jpeg(tone = 200), old.dateModifiedMs + 60_000)
    rig.readPhone()
    jobs.versionWork.clear()

    rig.backup.afterPhoneRead()

    assertThat(jobs.versionWork).contains(BackupKind.PHOTOS)
    assertThat(jobs.versionWork).doesNotContain(BackupKind.VIDEOS)
  }

  /**
   * CA-1 (D6, a freddo): la lettura del telefono di primo piano puo' finire PRIMA che il backup sia partito (il backup parte dopo il NAS). Allora
   * `afterPhoneRead` usciva senza fare niente e nessuno richiedeva i lavori: la versione aspettava il giro periodico, proprio nel caso che D6 voleva coprire
   * (`force-stop`, modifica, apertura a freddo). Adesso la chiamata si ricorda e si ripete appena il backup e' partito.
   */
  @Test
  fun d6_laLetturaFinitaPrimaCheIlBackupPartaChiedeComunqueIlLavoro_CA1() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val rig = newRig(jobs)
    BackupRig.photo(rig.store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - 3 * DAY)
    // A freddo: parte il NAS, la lettura del telefono finisce, e SOLO DOPO parte il backup.
    check(rig.nas.start()) { "il NAS non parte" }
    rig.readPhone()
    checkNotNull(rig.nas.scan(ScanMode.Full, 5.minutes))
    rig.chooseFolder("Pixel di prova")

    rig.backup.afterPhoneRead()
    assertThat(jobs.versionWork).isEmpty()
    assertThat(jobs.quickWork.get()).isEqualTo(0)

    check(rig.backup.start()) { "il backup non parte" }
    withTimeout(30_000) { while (jobs.versionWork.isEmpty()) delay(50) }
    assertThat(jobs.versionWork).contains(BackupKind.PHOTOS)
    assertThat(jobs.versionWork).doesNotContain(BackupKind.VIDEOS)
  }

  /** CA-1: se nessuna lettura e' finita prima della partenza, `start()` non chiede lavori da solo (niente polling, niente lavori a vuoto). */
  @Test
  fun d6_senzaUnaLetturaFinitaPrimaIlBackupCheParteNonChiedeNiente_CA1() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val rig = newRig(jobs)
    BackupRig.photo(rig.store, id = 1, bytes = BackupRig.jpeg(tone = 40), takenAt = time.now() - 3 * DAY)
    check(rig.nas.start())
    rig.readPhone()
    checkNotNull(rig.nas.scan(ScanMode.Full, 5.minutes))
    rig.chooseFolder("Pixel di prova")
    check(rig.backup.start())
    delay(500)
    assertThat(jobs.versionWork).isEmpty()
    assertThat(jobs.quickWork.get()).isEqualTo(0)
  }

  private companion object {
    const val DAY = 24L * 3_600_000L
    val DEBUG_LIKE: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)
  }
}
