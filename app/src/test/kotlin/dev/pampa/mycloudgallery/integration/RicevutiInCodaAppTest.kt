package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.backup.FiveBInputs
import dev.pampa.mycloudgallery.backup.FiveBUi
import dev.pampa.mycloudgallery.backup.IncomingStates
import dev.pampa.mycloudgallery.backup.BackupStatusReducers
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSaveOutcome
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSavePlan
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.database.entity.IncomingItemEntity
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaRewriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedDiscard
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedId
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedItem
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedPhase
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedRetry
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedStatus
import dev.pampa.mycloudgallery.feature.integrations.receive.DefaultIncomingUploads
import dev.pampa.mycloudgallery.feature.integrations.receive.ImageSize
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingStager
import dev.pampa.mycloudgallery.integrations.IncomingSpace
import dev.pampa.mycloudgallery.integrations.IntegrationsGraph
import dev.pampa.mycloudgallery.notify.IncomingProblemNotices
import dev.pampa.mycloudgallery.work.BackupJobs
import dev.pampa.mycloudgallery.work.BackupStages
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
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
 * **"Ricevuti in coda"** (correzioni 5B, IN-7 della verifica funzionale; R15 della revisione di sicurezza) sul **grafo vero**: il diario degli arrivi, il
 * diario del backup, il motore, la catena dei cancelli e il catalogo. Finti solo il NAS (simulatore SMB di core-nas) e il telefono.
 *
 * - la lista dice a che punto e' un file ricevuto (il suo media e' invisibile finche' non e' sul NAS);
 * - "Riprova" rimette in coda con gli stessi cancelli di "Salva sul NAS" (D12, R2, D13, la share) e chiede il lavoro dell'utente;
 * - "Scarta" toglie la riga e la copia sul telefono e **non tocca mai il NAS**; un file gia' pubblicato non si scarta;
 * - un arrivo uguale a un media nascosto non gli si aggancia (R15, E-H1);
 * - il piede della Libreria dice quanti file non sono stati salvati.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class RicevutiInCodaAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()

  /** I lavori che il backup chiede, registrati (JobScheduler non c'e'): ogni voce e' `userInitiated` della richiesta dei file ricevuti. */
  private class RecordingJobs : BackupJobs {
    val incoming = CopyOnWriteArrayList<Boolean>()

    override fun userInitiated(trigger: dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger, confirmed: Boolean) = true

    override fun incoming(userInitiated: Boolean) {
      incoming += userInitiated
    }

    override fun cancelAll() = Unit
  }

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  private fun device(name: String, backend: InMemorySmbBackend, flags: FeatureFlags): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend), flags).also { devices += it }

  private val anyCopyIsValid = WrittenFileCheck { _, _, _ -> WrittenCheck.VALID }

  private fun rig(device: TestDevice, jobs: BackupJobs): BackupRig = BackupRig(
    app = app,
    device = device,
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

  /** Un telefono con la famiglia fondata, il backup aperto e la cartella scelta (nessuna foto). */
  private suspend fun openedRig(backend: InMemorySmbBackend, jobs: BackupJobs = RecordingJobs(), flags: FeatureFlags = DEBUG_LIKE, name: String = "telefono"): BackupRig {
    val phone = device(name, backend, flags)
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = rig(phone, jobs)
    rig.open()
    rig.chooseFolder("Pixel di prova")
    return rig
  }

  private fun mp4(seed: Int, size: Int = 4_000): ByteArray =
    byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 2, 0) +
      "isomiso2".toByteArray() + ByteArray(size - 24) { ((it * 31 + seed) and 0x7F or 0x80).toByte() }

  private fun stream(bytes: ByteArray, name: String, mime: String) =
    IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, name, mime, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

  /** Lo stesso stager dell'app, con le misure delle immagini date dal test (il lettore di `BitmapFactory` su Robolectric e Windows tiene aperto il file). */
  private fun uploadsOf(rig: BackupRig): DefaultIncomingUploads {
    val fiveB = rig.backup.fiveB
    val stager = IncomingStager(fiveB.incomingRoot, freeBytes = { IncomingSpace.usableBytes(fiveB.incomingRoot) }, bounds = { ImageSize(320, 240) })
    return DefaultIncomingUploads(stager, fiveB.incomingQueue, rig.backup.clock)
  }

  private val privateDest = NasDestination(TestNas.ADMIN_USER, SharePath.of("Ricevute"), DestinationArea.PRIVATE)

  /** Un file condiviso da un'altra app e mandato sul NAS con "Salva sul NAS": il suo stage, e l'esito (messo in coda). */
  private suspend fun receive(rig: BackupRig, bytes: ByteArray, name: String, mime: String = "video/mp4", dest: NasDestination = privateDest): IncomingStage {
    val uploads = uploadsOf(rig)
    val stage = uploads.stage(listOf(stream(bytes, name, mime)))
    assertWithMessage("rifiutati: ${stage.rejected}").that(stage.accepted).hasSize(1)
    val saved = uploads.save(stage, dest, TestConfirmations.confirm(IncomingSavePlan(stage, dest), rig.backup.clock.monotonicMs()))
    assertThat(saved).isInstanceOf(IncomingSaveOutcome.Queued::class.java)
    return stage
  }

  private suspend fun runIncoming(rig: BackupRig) = checkNotNull(BackupStages(rig.device.graph, rig.backup).incoming()) { "il lavoro dei file ricevuti non e' partito" }

  private suspend fun items(rig: BackupRig, until: (List<ReceivedItem>) -> Boolean): List<ReceivedItem> =
    withTimeout(Attese.TETTO_MS) { rig.backup.fiveB.receivedQueue.items.first(until) }

  private fun rowsIn(rig: BackupRig, state: String): List<IncomingItemEntity> = runBlocking { rig.gallery.database.incomingDao().inState(state) }

  private fun localFileOf(rig: BackupRig, row: IncomingItemEntity): File? = rig.backup.fiveB.incomingFiles.localFileOf(row)

  /** Ogni file sotto [dir] con il suo contenuto: la fotografia del NAS, per dire che "Scarta" non l'ha toccato. */
  private fun nasSnapshot(backend: InMemorySmbBackend): Map<String, List<Byte>> =
    filesUnder(backend, TestNas.ADMIN_USER, "Ricevute").associateWith { checkNotNull(backend.bytes(TestNas.ADMIN_USER, it)).toList() }

  // --- La lista: a che punto e' un file ricevuto ----------------------------------------------------------------------------------------

  /**
   * IN-7: il media di un arrivo nasce invisibile e nessuna schermata leggeva `incoming_item`: l'utente non sapeva se il file fosse in coda, in
   * caricamento o perso. Adesso la lista c'e': il file e' "in attesa" con la sua cartella, e sparisce quando il motore lo ha caricato e riletto.
   */
  @Test
  fun unFileRicevutoCompareInCodaConLaSuaCartellaEScompareQuandoESulNas() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val rig = openedRig(backend)
    assertThat(rig.backup.fiveB.receivedQueue.items.first()).isEmpty()

    receive(rig, mp4(1, size = 6_000), "Mare.mp4")

    // Lo stato preciso, non il primo elenco da una riga: la frase di un file in coda dipende anche dallo stato del backup (`ReceivedStates.of`), che dopo
    // `chooseFolder` si assesta da solo e, con altre build sul PC, poteva essere ancora quello di prima (un elenco da una riga con un'altra fase).
    val queued = items(rig) { it.singleOrNull()?.phase == ReceivedPhase.WAITING }.single()
    assertThat(queued.folder).isEqualTo("Ricevute")
    assertThat(queued.isVideo).isTrue()
    assertThat(queued.size).isEqualTo(6_000L)
    assertThat(queued.canRetry).isTrue()
    assertThat(queued.canDiscard).isTrue()
    // Il suo media e' invisibile: la Libreria non lo mostra finche' non e' sul NAS.
    val media = rig.gallery.database.openHelper.readableDatabase.query("SELECT presence FROM media").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
    assertThat(media).containsExactly(Presence.NONE.code)

    val last = checkNotNull(runIncoming(rig).last)
    assertWithMessage("giro: $last").that(last.done + last.linked).isEqualTo(1)
    items(rig) { it.isEmpty() }
    assertThat(backend.bytes(TestNas.ADMIN_USER, "Ricevute/Mare.mp4")).isNotNull()
  }

  // --- Riprova ----------------------------------------------------------------------------------------------------------------------------

  /**
   * IN-7: un arrivo `FAILED` teneva la copia locale per sempre, senza modo di riprovarlo. La copia sul telefono cambia dopo il tocco (illeggibile per il
   * motore): `FAILED`, con la sua frase; "Riprova" con la copia ancora storta ripete il problema, con la copia a posto il file sale.
   */
  @Test
  fun riprovaRimetteInCodaUnFileFallitoEQuandoLaCopiaEAPostoArrivaSulNas() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val jobs = RecordingJobs()
    val rig = openedRig(backend, jobs)
    val bytes = mp4(2, size = 5_000)
    receive(rig, bytes, "Gita.mp4")
    val row = rowsIn(rig, IncomingStates.QUEUED).single()
    val copy = checkNotNull(localFileOf(rig, row))
    copy.writeBytes(bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })

    val failed = checkNotNull(runIncoming(rig).last)
    assertWithMessage("giro: $failed").that(failed.failed).isEqualTo(1)
    assertThat(rowsIn(rig, IncomingStates.FAILED)).hasSize(1)
    val problem = items(rig) { it.singleOrNull()?.phase == ReceivedPhase.PROBLEM }.single()
    assertThat(problem.status).isEqualTo(ReceivedStatus.COPY_CHANGED)
    assertThat(problem.canRetry).isTrue()
    assertThat(problem.canDiscard).isTrue()
    // La copia locale resta: l'utente non perde il file.
    assertThat(localFileOf(rig, row)).isNotNull()
    val firstItem = checkNotNull(rowsIn(rig, IncomingStates.FAILED).single().backupItemId)

    // "Riprova" con la copia ancora storta: rimesso in coda (un elemento nuovo, il vecchio e' terminale), il motore ritrova lo stesso problema.
    assertThat(rig.backup.fiveB.receivedQueue.retry(ReceivedId(row.incomingId))).isEqualTo(ReceivedRetry.Queued)
    val secondItem = checkNotNull(rowsIn(rig, IncomingStates.QUEUED).single().backupItemId)
    assertThat(secondItem).isNotEqualTo(firstItem)
    assertThat(jobs.incoming).containsExactly(false, false)
    assertThat(checkNotNull(runIncoming(rig).last).failed).isEqualTo(1)
    assertThat(rowsIn(rig, IncomingStates.FAILED)).hasSize(1)

    // La copia torna a posto: "Riprova" e il file sale sul NAS, riletto; la copia locale se ne va solo adesso.
    copy.writeBytes(bytes)
    assertThat(rig.backup.fiveB.receivedQueue.retry(ReceivedId(row.incomingId))).isEqualTo(ReceivedRetry.Queued)
    assertThat(jobs.incoming).hasSize(3)
    val done = checkNotNull(runIncoming(rig).last)
    assertWithMessage("giro: $done").that(done.done + done.linked).isEqualTo(1)
    assertThat(backend.bytes(TestNas.ADMIN_USER, "Ricevute/Gita.mp4")?.toList()).isEqualTo(bytes.toList())
    assertThat(rowsIn(rig, IncomingStates.UPLOADED)).hasSize(1)
    assertThat(localFileOf(rig, row)).isNull()
    items(rig) { it.isEmpty() }
  }

  /** Una riga `FAILED` di un telefono "in prova" (release): dalla riga all'elemento del diario, niente cambia se un cancello vieta. */
  private suspend fun failedRow(rig: BackupRig, bytes: ByteArray, destShare: String = TestNas.ADMIN_USER, destFolder: String = "Ricevute"): IncomingItemEntity {
    val dao = rig.gallery.database.incomingDao()
    val stageId = "ab".repeat(16)
    val name = "c".repeat(63) + (dao.inState(IncomingStates.FAILED).size + 1)
    File(File(rig.backup.fiveB.incomingRoot, stageId).also { it.mkdirs() }, name).writeBytes(bytes)
    val id = dao.insert(
      IncomingItemEntity(
        stageId = stageId,
        state = IncomingStates.FAILED,
        fileName = name,
        displayName = "Vecchio.mp4",
        sha256 = dev.pampa.mycloudgallery.core.model.Fingerprint.ofContent(bytes).fullHash!!.hex,
        size = bytes.size.toLong(),
        mime = "video/mp4",
        destShare = destShare,
        destFolder = destFolder,
        backupItemId = null,
        createdAt = 1L,
        updatedAt = 1L,
      ),
    )
    return checkNotNull(dao.get(id))
  }

  private fun backupItemCount(rig: BackupRig): Int =
    rig.gallery.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM backup_item").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

  /**
   * IN-7: "Riprova" ha gli **stessi cancelli** di "Salva sul NAS": in release D12 e R2 chiudono ogni scrittura della 5B (la share e' vera o l'account di
   * prova), quindi non si rimette in coda niente e non si chiede nessun lavoro: l'utente sa che e' il "Backup in prova".
   */
  @Test
  fun riprovaInReleaseDiceBackupInProvaENonCambiaNiente() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val rig = openedRig(TestNas.backend(time), jobs, FeatureFlags.RELEASE)
    val row = failedRow(rig, mp4(3))

    val outcome = rig.backup.fiveB.receivedQueue.retry(ReceivedId(row.incomingId))

    assertThat(outcome).isEqualTo(ReceivedRetry.Blocked(ReceivedStatus.TRIAL))
    assertThat(rowsIn(rig, IncomingStates.FAILED).map { it.incomingId }).containsExactly(row.incomingId)
    assertThat(backupItemCount(rig)).isEqualTo(0)
    assertThat(jobs.incoming).isEmpty()
    assertThat(localFileOf(rig, row)).isNotNull()
  }

  /** IN-7: la cartella `Versioni/` e l'area dell'app non ricevono file (D13, E-S11): "Riprova" non li rimette in coda, e dice "cartella bloccata". */
  @Test
  fun riprovaVersoUnaCartellaChiusaOUnaShareDiAltriDiceCartellaBloccata() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val rig = openedRig(TestNas.backend(time), jobs)
    val queue = rig.backup.fiveB.receivedQueue
    val versions = failedRow(rig, mp4(4), destFolder = "Versioni")
    val foreign = failedRow(rig, mp4(5), destShare = "famiglia-altrui")
    val appArea = failedRow(rig, mp4(6), destFolder = ".mycloudgallery/tmp")

    for (row in listOf(versions, foreign, appArea)) {
      assertThat(queue.retry(ReceivedId(row.incomingId))).isEqualTo(ReceivedRetry.Blocked(ReceivedStatus.FOLDER_LOCKED))
    }
    assertThat(backupItemCount(rig)).isEqualTo(0)
    assertThat(jobs.incoming).isEmpty()
    assertThat(rowsIn(rig, IncomingStates.FAILED)).hasSize(3)
  }

  /** IN-7: senza la copia sul telefono non c'e' niente da riprovare: il file si segna "sparito" e resta solo "Scarta". */
  @Test
  fun riprovaSenzaLaCopiaLocaleDiceCheNonCePiu() = runBlocking<Unit> {
    val jobs = RecordingJobs()
    val rig = openedRig(TestNas.backend(time), jobs)
    val row = failedRow(rig, mp4(7))
    checkNotNull(localFileOf(rig, row)).delete()

    assertThat(rig.backup.fiveB.receivedQueue.retry(ReceivedId(row.incomingId))).isEqualTo(ReceivedRetry.NoCopy)

    assertThat(rowsIn(rig, IncomingStates.GONE).map { it.incomingId }).containsExactly(row.incomingId)
    assertThat(jobs.incoming).isEmpty()
    val shown = items(rig) { it.size == 1 }.single()
    assertThat(shown.status).isEqualTo(ReceivedStatus.COPY_MISSING)
    assertThat(shown.canRetry).isFalse()
    assertThat(shown.canDiscard).isTrue()
  }

  @Test
  fun riprovaUnFileCheNonCePiuODiUnAltroGiroDiceChiEra() = runBlocking<Unit> {
    val rig = openedRig(TestNas.backend(time))
    assertThat(rig.backup.fiveB.receivedQueue.retry(ReceivedId(999L))).isEqualTo(ReceivedRetry.Unknown)
    assertThat(rig.backup.fiveB.receivedQueue.discard(ReceivedId(999L))).isEqualTo(ReceivedDiscard.Unknown)
  }

  // --- Scarta -----------------------------------------------------------------------------------------------------------------------------

  /**
   * IN-7: "Scarta" toglie dal diario degli arrivi la riga e la copia sul telefono; **il NAS non si tocca mai**. Il motore, che aveva ancora aperto
   * l'elemento del diario, lo chiude da solo come "sparito" al giro dopo (mai un errore, mai un file sul NAS).
   */
  @Test
  fun scartaTogliaLaRigaELaCopiaLocaleENonTocca_IlNas_EIlMotoreSistemaLElemento() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val rig = openedRig(backend)
    val queue = rig.backup.fiveB.receivedQueue
    // Un file gia' sul NAS, e un altro ancora in coda.
    receive(rig, mp4(8, size = 3_000), "Primo.mp4")
    check(checkNotNull(runIncoming(rig).last).let { it.done + it.linked } == 1)
    receive(rig, mp4(9, size = 3_500), "Secondo.mp4")
    val row = rowsIn(rig, IncomingStates.QUEUED).single()
    val localCopy = checkNotNull(localFileOf(rig, row))
    val before = nasSnapshot(backend)
    assertThat(before.keys).containsExactly("Ricevute/Primo.mp4")

    assertThat(queue.discard(ReceivedId(row.incomingId))).isEqualTo(ReceivedDiscard.Discarded)

    assertThat(localCopy.exists()).isFalse()
    assertThat(localCopy.parentFile?.exists()).isFalse()
    assertThat(rowsIn(rig, IncomingStates.QUEUED)).isEmpty()
    assertThat(rig.gallery.database.incomingDao().get(row.incomingId)).isNull()
    items(rig) { it.isEmpty() }
    // Il NAS e' com'era, byte per byte.
    assertThat(nasSnapshot(backend)).isEqualTo(before)
    // Il motore trova l'elemento senza la sua riga e lo chiude come "sparito": niente falliti, niente scritto, niente di aperto.
    val settle = checkNotNull(runIncoming(rig).last)
    assertWithMessage("giro: $settle").that(settle.failed).isEqualTo(0)
    assertThat(settle.done + settle.linked).isEqualTo(0)
    assertThat(nasSnapshot(backend)).isEqualTo(before)
    assertThat(rig.backup.hasOpenIncoming()).isFalse()
  }

  /** IN-7: un file `FAILED` si scarta (la copia sul telefono se ne va con la riga) e il NAS non cambia. */
  @Test
  fun scartaUnFileFallitoTogliCopiaERiga() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val rig = openedRig(backend)
    val bytes = mp4(10, size = 4_200)
    receive(rig, bytes, "Rotto.mp4")
    val row = rowsIn(rig, IncomingStates.QUEUED).single()
    val copy = checkNotNull(localFileOf(rig, row))
    copy.writeBytes(bytes.copyOf().also { it[10] = (it[10] + 1).toByte() })
    check(checkNotNull(runIncoming(rig).last).failed == 1)
    val before = nasSnapshot(backend)

    assertThat(rig.backup.fiveB.receivedQueue.discard(ReceivedId(row.incomingId))).isEqualTo(ReceivedDiscard.Discarded)

    assertThat(copy.exists()).isFalse()
    assertThat(rowsIn(rig, IncomingStates.FAILED)).isEmpty()
    assertThat(nasSnapshot(backend)).isEqualTo(before)
    items(rig) { it.isEmpty() }
  }

  /**
   * IN-7: un file **gia' pubblicato** non e' "scartabile", e' "caricato". Qui l'elemento e' arrivato a `PUBLISHED` (il file e' sul NAS, manca solo il
   * collegamento) e la riga e' ancora "in coda": "Scarta" non toglie niente, ne' la riga ne' la copia sul telefono, ne' il file sul NAS.
   */
  @Test
  fun unFileGiaPubblicatoNonSiScartaEDiceCheEGiaSulNas() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val rig = openedRig(backend)
    val bytes = mp4(11, size = 3_300)
    receive(rig, bytes, "Pubblicato.mp4")
    val row = rowsIn(rig, IncomingStates.QUEUED).single()
    backend.put(TestNas.ADMIN_USER, "Ricevute/Pubblicato.mp4", bytes)
    val diary = rig.gallery.database.backupDiaryDao()
    val item = checkNotNull(diary.get(checkNotNull(row.backupItemId)))
    diary.update(item.copy(state = "published", finalPath = "Ricevute/Pubblicato.mp4"))
    val before = nasSnapshot(backend)

    val shown = items(rig) { it.size == 1 }.single()
    assertThat(shown.canDiscard).isFalse()
    assertThat(rig.backup.fiveB.receivedQueue.discard(ReceivedId(row.incomingId))).isEqualTo(ReceivedDiscard.AlreadySaved)

    assertThat(rowsIn(rig, IncomingStates.QUEUED).map { it.incomingId }).containsExactly(row.incomingId)
    assertThat(localFileOf(rig, row)).isNotNull()
    assertThat(nasSnapshot(backend)).isEqualTo(before)
  }

  /** IN-7: un file che il motore ha gia' caricato e collegato ("uploaded") non c'e' piu' in lista e non si scarta. */
  @Test
  fun unFileCaricatoNonSiScartaENonEPiuInLista() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val rig = openedRig(backend)
    receive(rig, mp4(12, size = 3_100), "Fatto.mp4")
    val row = rowsIn(rig, IncomingStates.QUEUED).single()
    check(checkNotNull(runIncoming(rig).last).let { it.done + it.linked } == 1)
    val before = nasSnapshot(backend)

    items(rig) { it.isEmpty() }
    assertThat(rig.backup.fiveB.receivedQueue.discard(ReceivedId(row.incomingId))).isEqualTo(ReceivedDiscard.AlreadySaved)
    assertThat(rig.backup.fiveB.receivedQueue.retry(ReceivedId(row.incomingId))).isEqualTo(ReceivedRetry.AlreadySaved)

    assertThat(nasSnapshot(backend)).isEqualTo(before)
    assertThat(rowsIn(rig, IncomingStates.UPLOADED)).hasSize(1)
  }

  // --- Il piede della Libreria -------------------------------------------------------------------------------------------------------------

  /** IN-7: il piede della Libreria dice quanti file ricevuti non si sono salvati (porta alla pagina Backup), e smette quando non ce ne sono piu'. */
  @Test
  fun ilPiedeDellaLibreriaDiceIFileRicevutiNonSalvati() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val rig = openedRig(backend)
    val queue = rig.backup.fiveB.receivedQueue
    val row = failedRow(rig, mp4(13))
    rig.backup.kick()
    val footer = withTimeout(Attese.TETTO_MS) {
      rig.backup.libraryStatus.first { it is LibraryBackupStatus.Problem && it.code == BackupStatusReducers.INCOMING_FAILED_CODE }
    } as LibraryBackupStatus.Problem
    assertThat(footer.count).isEqualTo(1)

    // Lo scarta: il piede si ricalcola da solo (l'azione avvisa il backup) e non dice piu' niente di ricevuto.
    assertThat(queue.discard(ReceivedId(row.incomingId))).isEqualTo(ReceivedDiscard.Discarded)
    withTimeout(Attese.TETTO_MS) {
      rig.backup.libraryStatus.first { !(it is LibraryBackupStatus.Problem && it.code == BackupStatusReducers.INCOMING_FAILED_CODE) }
    }
  }

  // --- La notifica dei file non salvati -----------------------------------------------------------------------------------------------------------

  private class RecordingNotices : IncomingProblemNotices {
    val events = CopyOnWriteArrayList<String>()

    override fun problems(count: Int) {
      events += "problemi:$count"
    }

    override fun clear() {
      events += "via"
    }
  }

  private fun integrations(rig: BackupRig): IntegrationsGraph = IntegrationsGraph(
    context = rig.device.context,
    gallery = rig.gallery,
    scope = rig.device.scope,
    lock = AppLockController().apply { updateSettings(AppLockSettings()) },
    lockSettings = flowOf(AppLockSettings()),
    familyReady = { true },
    shareProbe = rig.device.graph.shareProbe,
  )

  /**
   * IN-7: un file ricevuto non si salva mentre l'app e' in secondo piano: parte la notifica "N file ricevuti non salvati" (solo il numero), l'app chiede di
   * portare l'utente alla pagina Backup quando torna, e quando il file si scarta la notifica se ne va. All'avvio del processo i problemi di prima non
   * fanno rumore.
   */
  @Test
  fun unFileCheNonSiSalvaAvvisaConLaNotificaEPortaAllaPagina() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val rig = openedRig(backend)
    val notices = RecordingNotices()
    val ui = FiveBUi(
      context = rig.device.context,
      backup = rig.backup,
      catalog = { rig.gallery.coreIfReady()?.catalog },
      operations = { null },
      monotonicMs = rig.gallery.parts.monotonicMs,
      integrations = integrations(rig),
      scope = rig.device.scope,
      incomingNotices = notices,
    )
    // La prima lettura (nessun problema) e' il punto di partenza.
    until("la prima lettura") { notices.events.contains("via") }
    assertThat(ui.receivedRequests.value).isEqualTo(0L)

    val bytes = mp4(14, size = 4_000)
    receive(rig, bytes, "Storto.mp4")
    val row = rowsIn(rig, IncomingStates.QUEUED).single()
    checkNotNull(localFileOf(rig, row)).writeBytes(bytes.copyOf().also { it[100] = (it[100] + 1).toByte() })
    check(checkNotNull(runIncoming(rig).last).failed == 1)

    until("la notifica") { "problemi:1" in notices.events }
    // La notifica e la richiesta di portare l'utente alla pagina Backup sono due effetti dello stesso passo del controllo, **uno dopo l'altro** (`IncomingProblemWatcher`:
    // prima `notices.problems`, poi `openRequest`): visto l'uno non e' detto che ci sia gia' l'altro (con il PC carico il thread del controllo si fermava in mezzo e la
    // prova leggeva 0). Si aspetta la richiesta, lo stato preciso che questa riga vuole.
    until("la richiesta di aprire la pagina Backup") { ui.receivedRequests.value > 0L }

    assertThat(rig.backup.fiveB.receivedQueue.discard(ReceivedId(row.incomingId))).isEqualTo(ReceivedDiscard.Discarded)
    until("la notifica tolta") { notices.events.last() == "via" }
  }

  // --- R15 sul grafo vero -------------------------------------------------------------------------------------------------------------------

  /**
   * R15: la foto dell'utente e' **nascosta**; un'altra app gli manda gli stessi byte e lui li salva sul NAS. Prima l'arrivo si agganciava al nascosto (il file
   * salvato non si vedeva mai, e il nascosto prendeva una copia che non aveva chiesto: E-H1); adesso nasce un media suo, visibile appena e' sul NAS, e il
   * nascosto resta com'era.
   */
  @Test
  fun r15_ilFileRicevutoUgualeAUnNascostoDiventaUnMediaVisibileEIlNascostoRestaCom_Era() = runBlocking<Unit> {
    val backend = TestNas.backend(time)
    val phone = device("telefono", backend, DEBUG_LIKE)
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = rig(phone, RecordingJobs())
    val bytes = BackupRig.jpeg(tone = 77)
    BackupRig.photo(rig.store, id = 1L, bytes = bytes, takenAt = time.now() - 60L * DAY)
    rig.open()
    rig.chooseFolder("Pixel di prova")
    check(checkNotNull(checkNotNull(rig.round()).last).done == 1)
    val core = checkNotNull(rig.gallery.coreIfReady()).catalog
    val hidden = rig.gallery.database.openHelper.readableDatabase
      .query("SELECT DISTINCT v.media_id FROM media_version v JOIN media_location l ON l.version_id = v.version_id WHERE l.location_type = 'phone'")
      .use { c ->
        check(c.moveToFirst())
        MediaId(c.getString(0))
      }
    core.setPersonalFlags(hidden, PersonalFlags(hidden = true))
    val nasCopiesBefore = checkNotNull(core.media(hidden)).locations.count { it.isNas }

    receive(rig, bytes, "Doppia.jpg", mime = "image/jpeg")
    val last = checkNotNull(runIncoming(rig).last)
    assertWithMessage("giro: $last").that(last.done + last.linked).isEqualTo(1)
    assertThat(backend.bytes(TestNas.ADMIN_USER, "Ricevute/Doppia.jpg")?.toList()).isEqualTo(bytes.toList())

    val location = checkNotNull(core.nasLocationAt(Location.Nas(AccountId.of(TestNas.ADMIN_USER), TestNas.ADMIN_USER, NasPath.of("Ricevute/Doppia.jpg"))))
    val received = checkNotNull(core.mediaIdOfLocation(location.locationId))
    assertThat(received).isNotEqualTo(hidden)
    val row = checkNotNull(rig.gallery.database.mediaDao().get(received.value))
    assertThat(row.presence).isEqualTo(Presence.NAS_ONLY.code)
    assertThat(rig.gallery.database.personalDao().get(received.value)?.hidden ?: false).isFalse()
    // Il nascosto: sempre nascosto, nessuna copia sul NAS in piu'.
    assertThat(rig.gallery.database.personalDao().get(hidden.value)!!.hidden).isTrue()
    assertThat(checkNotNull(core.media(hidden)).locations.count { it.isNas }).isEqualTo(nasCopiesBefore)
  }

  private companion object {
    const val DAY = 24L * 3_600_000L
    val DEBUG_LIKE: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)
  }
}
