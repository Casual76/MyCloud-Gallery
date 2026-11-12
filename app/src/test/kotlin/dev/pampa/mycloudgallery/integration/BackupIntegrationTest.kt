package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.FencedFlags
import dev.pampa.mycloudgallery.backup.BackupDiagCodes
import dev.pampa.mycloudgallery.backup.BackupDiagnostics
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.JoinStep
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.work.BackupJobs
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
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
 * Il backup con il grafo vero (Fase 5A, P7; docs/fase5-progetto.md §4.4 riga P7): `GalleryGraph` con NAS e backup sopra il NAS finto di
 * core-nas (`InMemorySmbBackend`) e un MediaStore finto con i byte delle foto. Nessun NAS vero, nessun telefono.
 *
 * - **flusso completo**: una foto nuova, un giro, la copia verificata in `Backup/<dispositivo>/AAAA/MM/`, `SAFE`, un secondo dispositivo
 *   dello stesso account senza doppioni, lo spegnimento;
 * - **D12**: con la recinzione accesa su una share vera non si scrive niente e lo stato e' `TestOnly`. Dal passo unico del 12/11/2026 D12 e'
 *   spenta in `FeatureFlags`: la prova usa flag con la recinzione accesa (`FencedFlags`, solo nelle prove) per dimostrare che il cancello, se la
 *   si riaccende, ferma tutto come prima. R2 (un account di prova in release) non e' cambiata e si prova con `FeatureFlags.RELEASE`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class BackupIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()

  /** I lavori avviati dall'utente registrati (JobScheduler non c'e'). */
  private class RecordingJobs : BackupJobs {
    val started = AtomicInteger()
    val cancelled = AtomicInteger()

    override fun userInitiated(trigger: BackupTrigger, confirmed: Boolean): Boolean {
      started.incrementAndGet()
      return true
    }

    override fun cancelAll() {
      cancelled.incrementAndGet()
    }
  }

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  private fun device(name: String, backend: InMemorySmbBackend, flags: FeatureFlags = DEBUG_LIKE): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend), flags).also { devices += it }

  private fun rig(device: TestDevice, account: String, jobs: BackupJobs = BackupJobs.NONE, name: String = "Pixel di prova"): BackupRig =
    BackupRig(app, device, account, jobs = jobs, deviceName = name).also { rigs += it }

  @Test
  fun fotoNuovaGiroCopiaVerificataSafeSecondoDispositivoSenzaDoppioniESpegnimento() = runBlocking {
    val backend = TestNas.backend(time)
    val phone = device("telefono", backend)
    phone.graph.start()
    phone.graph.foundFamily()

    // Due foto nuove sul telefono (diverse), scattate poche ore fa.
    val photos = listOf(BackupRig.jpeg(tone = 40), BackupRig.jpeg(tone = 140))
    val jobs = RecordingJobs()
    val first = rig(phone, TestNas.ADMIN_USER, jobs)
    photos.forEachIndexed { i, bytes -> BackupRig.photo(first.store, id = i + 1L, bytes = bytes, takenAt = time.now() - (3 - i) * HOUR) }
    val backup = first.open()
    first.chooseFolder("Pixel di prova")

    // Un giro (come "Fai ora"): le regole non frenano, il cancello e' aperto sulla share di prova.
    assertThat(backup.gate.stateOf(TestNas.ADMIN_USER)).isEqualTo(BackupGateState.OPEN)
    val outcome = checkNotNull(first.round()) { "il giro non e' partito" }
    val last = checkNotNull(outcome.last) { "tappa: ${outcome.stage}" }
    assertWithMessage("giro: $last").that(last.done).isEqualTo(2)
    assertThat(last.stoppedBy).isEqualTo(RunStop.COMPLETE)

    // I due file sono sul NAS, byte per byte, in Backup/<dispositivo>/AAAA/MM/ dell'area privata.
    val stored = filesUnder(backend, TestNas.ADMIN_USER, "Backup")
    assertThat(stored).hasSize(2)
    assertThat(stored.all { it.startsWith("Backup/Pixel di prova/2026/10/") }).isTrue()
    val storedBytes = stored.map { backend.bytes(TestNas.ADMIN_USER, it)!!.toList() }.toSet()
    assertThat(storedBytes).containsExactlyElementsIn(photos.map { it.toList() }.toSet())
    // Niente rimasto in giro: nessun temporaneo del backup nell'area (D33).
    assertThat(filesUnder(backend, TestNas.ADMIN_USER, ".mycloudgallery/tmp").filter { "-bk-" in it }).isEmpty()

    // Ogni media e' al sicuro (copia riletta qui) e lo stato dice "tutto al sicuro".
    val catalog = checkNotNull(backup.catalogOrNull())
    val view = first.gallery.repository.view(LibraryFilter.ALL).first { it.size == 2 }
    val ids = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
    ids.forEach { id -> assertThat(catalog.mediaSafety(id)?.status).isEqualTo(SafetyStatus.SAFE) }
    withTimeout(Attese.TETTO) { backup.status.first { it is BackupStatus.UpToDate } }
    assertThat((backup.status.value as BackupStatus.UpToDate).safeCount).isEqualTo(2)
    withTimeout(Attese.TETTO) { backup.libraryStatus.first { it is LibraryBackupStatus.AllSafe } }

    // Il secondo dispositivo dello stesso account: le stesse due foto (stesso scatto, stessi byte) e un'altra cartella. Nessun doppione.
    val tablet = device("tablet", backend)
    enrolSecondDevice(phone, tablet)
    val second = rig(tablet, TestNas.ADMIN_USER, name = "Tablet di prova")
    photos.forEachIndexed { i, bytes -> BackupRig.photo(second.store, id = i + 1L, bytes = bytes, takenAt = time.now() - (3 - i) * HOUR) }
    val secondBackup = second.open()
    // I due file del telefono sono nuovi per il NAS: la verifica li cataloga solo se li vede stabili (uguali a due minuti di distanza, E-N4).
    time.advance(3 * MINUTE)
    checkNotNull(second.nas.scan(ScanMode.Full, 5.minutes))
    second.chooseFolder("Tablet di prova")
    val secondOutcome = checkNotNull(second.round()) { "il giro del secondo dispositivo non e' partito" }
    val secondLast = checkNotNull(secondOutcome.last) { "tappa: ${secondOutcome.stage}" }
    assertWithMessage("secondo giro: $secondLast").that(secondLast.linked).isEqualTo(2)
    assertThat(secondLast.bytesUploaded).isEqualTo(0L)
    assertWithMessage("nessun doppione sul NAS").that(filesUnder(backend, TestNas.ADMIN_USER, "Backup")).containsExactlyElementsIn(stored)
    assertThat(backend.exists(TestNas.ADMIN_USER, "Backup/Tablet di prova")).isFalse()
    assertThat(secondBackup.catalogOrNull()).isNotNull()

    // La Diagnostica del backup: solo numeri, chiavi dell'elenco chiuso e codici del catalogo; nessun nome di file, cartella o share.
    val diag = BackupDiagnostics(backup, first.gallery.similarCopies).collect()
    assertThat(diag.map { it.code }).contains(BackupDiagCodes.STATE)
    assertThat(diag.map { it.code }).contains(BackupDiagCodes.LAST_ROUND)
    assertThat(diag.flatMap { it.export.keys }.filterNot(ExportKeys::isAllowed)).isEmpty()
    assertThat(diag.map { it.code }.filterNot { it in DiagCatalog }).isEmpty()
    assertThat(diag.toString()).doesNotContain("Pixel di prova")
    assertThat(diag.toString()).doesNotContain("IMG_")

    // Lo spegnimento (l'azzeramento ferma il backup per primo): niente parte piu', i lavori in coda tolti, nessun giro.
    first.gallery.shutdown()
    assertThat(backup.parts).isNull()
    assertThat(jobs.cancelled.get()).isAtLeast(1)
    assertThat(first.round()).isNull()
  }

  /**
   * **R2 della revisione 5A**: con la configurazione di **release** un account di prova (`test`) non fa scrivere il backup nemmeno nella sua
   * share di prova, dove D12 lo lascerebbe: la recinzione del telefono di D13 c'e' solo in debug, e la libreria vera finirebbe nella share di
   * prova. Lo stato dice "Backup in prova", il cancello risponde `TEST_ONLY`, e ne' un giro, ne' il primo backup, ne' le verifiche scrivono.
   * Con la configurazione di debug lo stesso account e la stessa share aprono il cancello (provato da `fotoNuovaGiroCopiaVerificata...`).
   */
  @Test
  fun r2_inReleaseUnAccountDiProvaNonScriveNemmenoNellaSuaShareDiProva() = runBlocking {
    val backend = TestNas.backend(time)
    val phone = device("telefono", backend, flags = FeatureFlags.RELEASE)
    phone.graph.start()
    phone.graph.foundFamily()

    val jobs = RecordingJobs()
    val rig = rig(phone, TestNas.ADMIN_USER, jobs)
    BackupRig.photo(rig.store, id = 1, bytes = BackupRig.jpeg(tone = 91), takenAt = time.now() - 2 * HOUR)
    val backup = rig.open()
    rig.chooseFolder("Pixel di prova")

    assertThat(TestNas.ADMIN_USER).isEqualTo("test")
    assertThat(backup.gate.stateOf(TestNas.ADMIN_USER)).isEqualTo(BackupGateState.TEST_ONLY)
    withTimeout(Attese.TETTO) { backup.status.first { it == BackupStatus.TestOnly } }
    withTimeout(Attese.TETTO) { backup.libraryStatus.first { it == LibraryBackupStatus.TestOnly } }

    val filesBefore = backend.fileCount(TestNas.ADMIN_USER)
    for (trigger in listOf(BackupTrigger.USER_NOW, BackupTrigger.FIRST_BACKUP)) {
      val outcome = rig.round(BackupKind.PHOTOS, trigger)
      assertThat(outcome?.last?.stoppedBy).isEqualTo(RunStop.GATE)
      assertThat(outcome?.last?.done).isEqualTo(0)
    }
    val safety = rig.safety()
    assertThat(safety?.verified ?: 0).isEqualTo(0)
    assertThat(safety?.guardCreated ?: 0).isEqualTo(0)
    assertThat(backend.exists(TestNas.ADMIN_USER, "Backup")).isFalse()
    assertThat(backend.fileCount(TestNas.ADMIN_USER)).isEqualTo(filesBefore)
    assertThat(backup.store.counts().open).isEqualTo(0)
    Unit
  }

  @Test
  fun d12UnaShareVeraNonSiScriveNienteEIlBackupEInProva() = runBlocking {
    // Un NAS "vero": un account e due share che non sono nell'elenco delle share di prova. La recinzione D12 accesa (com'era prima del passo unico).
    val backend = InMemorySmbBackend(nasTime = time::now).apply {
      addUser(REAL_USER, REAL_PASSWORD)
      addShare(REAL_COMMON, mapOf(REAL_USER to SimAccess.READ_WRITE))
      addShare(REAL_USER, mapOf(REAL_USER to SimAccess.READ_WRITE))
    }
    val phone = device("telefono", backend, flags = FencedFlags.of(DEBUG_LIKE))
    phone.graph.start()
    phone.graph.foundFamilyOn(REAL_USER, REAL_PASSWORD, REAL_COMMON, REAL_USER)

    val jobs = RecordingJobs()
    val rig = rig(phone, REAL_USER, jobs)
    BackupRig.photo(rig.store, id = 1, bytes = BackupRig.jpeg(tone = 77), takenAt = time.now() - 2 * HOUR)
    val backup = rig.open()
    rig.chooseFolder("Pixel di prova")

    // D12: nessuna ragione puo' aprire il cancello. Lo stato dice "in prova" e il piede della Libreria lo ripete.
    assertThat(backup.gate.stateOf(REAL_USER)).isEqualTo(BackupGateState.TEST_ONLY)
    withTimeout(Attese.TETTO) { backup.status.first { it == BackupStatus.TestOnly } }
    withTimeout(Attese.TETTO) { backup.libraryStatus.first { it == LibraryBackupStatus.TestOnly } }

    val filesBefore = backend.fileCount(REAL_USER)
    val outcome = rig.round(BackupKind.PHOTOS, BackupTrigger.USER_NOW)
    assertThat(outcome?.last?.stoppedBy).isEqualTo(RunStop.GATE)
    assertThat(outcome?.last?.done).isEqualTo(0)
    // Nemmeno il primo backup (UIDT) ne' le verifiche, ne' il guardiano: tutti si fermano al cancello.
    val first = rig.round(BackupKind.PHOTOS, BackupTrigger.FIRST_BACKUP)
    assertThat(first?.last?.stoppedBy).isEqualTo(RunStop.GATE)
    val safety = rig.safety()
    assertThat(safety?.verified ?: 0).isEqualTo(0)
    assertThat(safety?.guardCreated ?: 0).isEqualTo(0)

    // Sul NAS non e' comparso niente: ne' una cartella Backup/, ne' un temporaneo, ne' un file in piu'.
    assertThat(backend.exists(REAL_USER, "Backup")).isFalse()
    assertThat(backend.fileCount(REAL_USER)).isEqualTo(filesBefore)
    assertThat(filesUnder(backend, REAL_USER, ".mycloudgallery/tmp").filter { "-bk-" in it }).isEmpty()
    assertThat(filesUnder(backend, REAL_COMMON, "Backup")).isEmpty()
    assertThat(backup.store.counts().open).isEqualTo(0)
    Unit
  }

  /**
   * PU-2 della revisione del passo unico (12/11/2026): il guardiano del grafo vero ha il cancello del primo backup. Con il cancello del backup aperto,
   * prima della scelta della cartella del dispositivo ("Primo backup pronto") il giro si ferma al cancello senza toccare il NAS; dopo la scelta gira.
   * La prova del guardiano con un candidato valido e le primitive di scrittura contate e' `FirstBackupGuardianTest` (core-backup).
   */
  @Test
  fun passoUnico_ilGuardianoAspettaLaSceltaDelPrimoBackup() = runBlocking {
    val backend = TestNas.backend(time)
    val phone = device("telefono", backend)
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = rig(phone, TestNas.ADMIN_USER)
    val backup = rig.open()
    val parts = checkNotNull(backup.parts)
    assertThat(backup.gate.stateOf(parts.share)).isEqualTo(BackupGateState.OPEN)
    assertThat(backup.settings.deviceFolder.value).isNull()
    val filesBefore = backend.fileCount(TestNas.ADMIN_USER)

    for (scope in listOf(GuardScope.All, GuardScope.Media(emptySet()))) {
      assertWithMessage("$scope prima della scelta").that(parts.guardian.ensure(scope, 1.minutes).stoppedBy).isEqualTo(RunStop.GATE)
    }
    assertThat(backend.fileCount(TestNas.ADMIN_USER)).isEqualTo(filesBefore)

    rig.chooseFolder("Pixel di prova")
    assertThat(parts.guardian.ensure(GuardScope.All, 1.minutes).stoppedBy).isEqualTo(RunStop.COMPLETE)
    Unit
  }

  /**
   * Il passo unico del 12/11/2026 (D35): con i valori di **release di adesso** (D12 spenta, R2 accesa) e un account vero su share vere il cancello e'
   * aperto e un giro carica la foto in `Backup/<dispositivo>/AAAA/MM/` dell'area privata, e da nessun'altra parte del backup (niente nell'area comune,
   * nessun temporaneo rimasto).
   */
  @Test
  fun passoUnico_conLaReleaseDiAdessoUnaShareVeraRiceveIlBackup() = runBlocking {
    val backend = InMemorySmbBackend(nasTime = time::now).apply {
      addUser(REAL_USER, REAL_PASSWORD)
      addShare(REAL_COMMON, mapOf(REAL_USER to SimAccess.READ_WRITE))
      addShare(REAL_USER, mapOf(REAL_USER to SimAccess.READ_WRITE))
    }
    val phone = device("telefono", backend, flags = FeatureFlags.RELEASE)
    phone.graph.start()
    phone.graph.foundFamilyOn(REAL_USER, REAL_PASSWORD, REAL_COMMON, REAL_USER)

    val rig = rig(phone, REAL_USER, RecordingJobs())
    val photo = BackupRig.jpeg(tone = 55)
    BackupRig.photo(rig.store, id = 1, bytes = photo, takenAt = time.now() - 2 * HOUR)
    val backup = rig.open()
    rig.chooseFolder("Pixel di prova")

    assertThat(backup.gate.stateOf(REAL_USER)).isEqualTo(BackupGateState.OPEN)
    val outcome = checkNotNull(rig.round()) { "il giro non e' partito" }
    val last = checkNotNull(outcome.last) { "tappa: ${outcome.stage}" }
    assertWithMessage("giro: $last").that(last.done).isEqualTo(1)
    val stored = filesUnder(backend, REAL_USER, "Backup")
    assertThat(stored).hasSize(1)
    assertThat(stored.single()).startsWith("Backup/Pixel di prova/")
    assertThat(backend.bytes(REAL_USER, stored.single())!!.toList()).isEqualTo(photo.toList())
    assertThat(filesUnder(backend, REAL_USER, ".mycloudgallery/tmp").filter { "-bk-" in it }).isEmpty()
    assertThat(filesUnder(backend, REAL_COMMON, "Backup")).isEmpty()
    Unit
  }

  /**
   * **E-B15 / D34** (L38): Samsung "Salva" riscrive il file di una foto appena scattata. Il giro di "Proteggi subito le foto nuove"
   * (anche fuori fascia notte) porta sul NAS i byte originali appena scattata; la modifica che arriva dopo non tocca ne' sostituisce mai la
   * copia verificata dell'originale (il backup non puo' sostituire un file: D2) e la versione nuova non ritorna in coda come se fosse
   * l'originale. Il test non prova il caso "modificata prima di qualunque giro" (i byte originali sono persi sul telefono): quello e' del
   * motore (`FaultPlan`, `DuringRead.Rewritten`, core-backup).
   */
  @Test
  fun eB15LaFotoAppenaScattataSiCaricaSubitoELaModificaNonToccaLOriginaleVerificato() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val phone = device("telefono", backend)
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = rig(phone, TestNas.ADMIN_USER)
    val original = BackupRig.jpeg(tone = 33)
    val takenAt = time.now() - 2 * MINUTE
    val row = BackupRig.photo(rig.store, id = 1, bytes = original, takenAt = takenAt)
    val backup = rig.open()
    rig.chooseFolder("Pixel di prova")

    // D34: la foto di due minuti fa e' un candidato del giro delle foto nuove (con le regole di D34, senza fascia notte).
    val quick = checkNotNull(rig.round(BackupKind.PHOTOS, BackupTrigger.QUICK_NEW_PHOTOS)) { "il giro delle foto nuove non e' partito" }
    assertWithMessage("giro: ${quick.last}").that(quick.last?.done).isEqualTo(1)
    val stored = filesUnder(backend, TestNas.ADMIN_USER, "Backup")
    assertThat(stored).hasSize(1)
    assertThat(backend.bytes(TestNas.ADMIN_USER, stored.single())).isEqualTo(original)

    // "Salva": lo stesso file (stessa riga) con i byte della modifica, piu' grande e con una data di modifica nuova.
    val edited = BackupRig.jpeg(tone = 33, width = 640, height = 480, quality = 60)
    rig.store.put(
      dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow(
        volume = row.volume, id = row.id, type = row.type, displayName = row.displayName, relativePath = row.relativePath, mime = row.mime,
        size = edited.size.toLong(), dateModifiedMs = takenAt + 90_000, dateTakenMs = takenAt,
      ),
      edited,
    )
    rig.readPhone()
    val next = rig.round(BackupKind.PHOTOS, BackupTrigger.USER_NOW)

    // L'originale sul NAS e' intatto, byte per byte: nessuna sostituzione, nessun file tolto. Dalla 5B (P14, D17) la modifica e' una versione
    // dello stesso media: l'originale passa in `Versioni/` con una rinomina (l'unica automatica, D2) e la modifica prende il solito percorso, al
    // posto di un "nome (2)".
    val files = filesUnder(backend, TestNas.ADMIN_USER, "Backup") + filesUnder(backend, TestNas.ADMIN_USER, "Versioni")
    assertThat(files.count { backend.bytes(TestNas.ADMIN_USER, it)!!.contentEquals(original) }).isEqualTo(1)
    assertThat(filesUnder(backend, TestNas.ADMIN_USER, "Versioni")).hasSize(1)
    assertThat(backend.bytes(TestNas.ADMIN_USER, stored.single())).isEqualTo(edited)
    assertWithMessage("giro dopo la modifica: ${next?.last}").that(next?.last?.stoppedBy).isNotEqualTo(RunStop.NAS)
    // Nessun temporaneo rimasto, ne' un file che non sia l'originale o la modifica (mai byte misti).
    assertThat(filesUnder(backend, TestNas.ADMIN_USER, ".mycloudgallery/tmp").filter { "-bk-" in it }).isEmpty()
    files.forEach { path ->
      val bytes = backend.bytes(TestNas.ADMIN_USER, path)!!
      assertWithMessage("contenuto di un file del backup").that(bytes.contentEquals(original) || bytes.contentEquals(edited)).isTrue()
    }
    assertThat(backup.store.counts().byState[dev.pampa.mycloudgallery.core.backup.contract.UploadState.FAILED] ?: 0).isEqualTo(0)
  }

  /**
   * **D33**: un temporaneo del backup (`.mycloudgallery/tmp/<id del dispositivo>-bk-<n>`) citato da un elemento non finito del diario non
   * lo toglie la pulizia dei propri file (anche se ha piu' di un giorno: un video a meta' con il telefono fuori casa da giorni, E-B21);
   * chiuso l'elemento, il giro dopo lo toglie. Prova il collegamento `BackupGraph.citedTemps` -> `NasOpsConfig.citedTemps`.
   */
  @Test
  fun d33UnTemporaneoCitatoDalDiarioDelBackupNonLoTogliELaPuliziaDeiPropriFile() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val phone = device("telefono", backend)
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = rig(phone, TestNas.ADMIN_USER)
    BackupRig.photo(rig.store, id = 1, bytes = BackupRig.jpeg(tone = 12), takenAt = time.now() - HOUR)
    val backup = rig.open()
    rig.chooseFolder("Pixel di prova")
    val deviceId = checkNotNull(phone.graph.engine.myDeviceId())

    // Un temporaneo vecchio di tre giorni, citato da un elemento aperto del diario (uploading, con il suo punto di ripresa).
    val tempPath = ".mycloudgallery/tmp/$deviceId-bk-9"
    backend.put(TestNas.ADMIN_USER, tempPath, ByteArray(256) { 3 }, lastWriteMs = time.now() - 3 * DAY)
    val db = rig.gallery.database.openHelper.writableDatabase
    val ids = db.query("SELECT media_id, current_version_id FROM media LIMIT 1").use { c -> check(c.moveToFirst()); c.getString(0) to c.getString(1) }
    db.execSQL(
      "INSERT INTO backup_item (kind, state, is_open, media_id, version_id, size, share, planned_path, name_attempt, temp_path, resume_at, " +
        "verify_attempts, created_at, updated_at, archive_attempt) VALUES ('new_copy', 'uploading', 1, ?, ?, 256, ?, 'Backup/x/a.jpg', 0, ?, 0, 0, 0, 0, 0)",
      arrayOf<Any?>(ids.first, ids.second, TestNas.ADMIN_USER, tempPath),
    )
    assertThat(backup.citedTemps().map { it.value }).contains(tempPath)

    // Il giro di pulizia (manutenzione quotidiana): il temporaneo, citato, resta.
    time.advance(25 * HOUR)
    rig.nas.acceptClockJump()
    val first = rig.nas.organizer.maintainIndex(5.minutes)
    assertWithMessage("manutenzione: $first").that(first).isNotNull()
    assertThat(backend.exists(TestNas.ADMIN_USER, tempPath)).isTrue()

    // L'elemento si chiude (terminale): nessuno cita piu' il temporaneo, e il giro dopo lo toglie.
    db.execSQL("UPDATE backup_item SET state = 'done', is_open = 0")
    assertThat(backup.citedTemps().map { it.value }).doesNotContain(tempPath)
    time.advance(25 * HOUR)
    rig.nas.acceptClockJump()
    val second = rig.nas.organizer.maintainIndex(5.minutes)
    assertWithMessage("manutenzione: $second").that(second).isNotNull()
    assertWithMessage("temporaneo non piu' citato: ${second?.clean}").that(backend.exists(TestNas.ADMIN_USER, tempPath)).isFalse()
  }

  // --- Il secondo dispositivo dello stesso account e la famiglia con i nomi di una share vera --------------------------------------

  /** Come `foundFamily`, ma con un account e due share a scelta (qui nomi che non sono di prova). */
  private suspend fun AppGraph.foundFamilyOn(user: String, password: String, common: String, private: String) {
    settle(coordinator.submit(StepInput.ChoosePath(OnboardingPath.FOUND_FAMILY)))
    loop@ for (attempt in 0 until 20) {
      when (coordinator.progress.value.current) {
        OnboardingStep.NAS_ADDRESS -> {
          settle(coordinator.submit(StepInput.ConfirmHome(true)))
          settle(coordinator.submit(StepInput.NasAddressChosen(TEST_NAS_ADDRESS)))
        }
        OnboardingStep.ACCOUNT -> settle(coordinator.submit(StepInput.Credentials(user, password.toCharArray())))
        OnboardingStep.AREAS -> {
          coordinator.proposeAreas(listOf(common))
          settle(coordinator.submit(StepInput.Areas(common, private)))
        }
        OnboardingStep.FOLDERS -> settle(coordinator.submit(StepInput.Folders(emptySet())))
        OnboardingStep.FAMILY_KEY -> familyKey()
        else -> break@loop
      }
    }
    localSteps(until = OnboardingStep.FAMILY_KEY)
    familyKey()
    localSteps(until = OnboardingStep.SUMMARY)
    settle(coordinator.submit(StepInput.Finish))
    awaitSetup { it is SetupState.Ready }
  }

  private companion object {
    const val HOUR = 3_600_000L

    /** Una build di debug: le funzioni della release, ma l'account di prova `test` puo' provare il backup (senza R2, dove D13 recinta il telefono). */
    val DEBUG_LIKE: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)
    const val MINUTE = 60_000L
    const val DAY = 24 * HOUR
    const val REAL_USER = "mario"
    const val REAL_PASSWORD = "Chiave-Di-Mario-Uno-7"
    const val REAL_COMMON = "famiglia"
  }
}
