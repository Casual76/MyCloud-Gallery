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
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.di.FiveBModule
import dev.pampa.mycloudgallery.integrations.AppIncomingMetadata
import dev.pampa.mycloudgallery.work.BackupJobs
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
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

    override fun userInitiated(trigger: BackupTrigger, confirmed: Boolean) = Unit

    override fun versionWork(kind: BackupKind) {
      versionWork += kind
    }

    override fun cancelAll() = Unit
  }

  private suspend fun openRig(jobs: BackupJobs = RecordingJobs(), rows: (FakeMediaStore) -> Unit): BackupRig {
    val backend = TestNas.backend(time)
    val phone = TestDevice(app, File(temp.root, "telefono-${devices.size}"), TestPlatform(time, backend), DEBUG_LIKE).also { devices += it }
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = BackupRig(
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

  private companion object {
    val DEBUG_LIKE: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)
  }
}
