package dev.pampa.mycloudgallery.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.noop.MonotonicOnlyBackupClock
import dev.pampa.mycloudgallery.core.backup.store.RoomBackupStore
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.IncomingItemEntity
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedDiscard
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedId
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedRetry
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedStatus
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * IN-7: "Riprova" di "Ricevuti in coda" ha **gli stessi cancelli** di "Salva sul NAS", uno per uno (D12/R2 = `TEST_ONLY`, sola lettura, area non piu'
 * dell'utente, la radice di prova di D13, `Versioni/`, congelamento, emergenza, pausa), e "Scarta" guarda lo stato del motore. Database vero in memoria, finti
 * il cancello e lo stato del backup; il NAS non c'entra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReceivedGatesTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var db: GalleryDatabase
  private lateinit var catalog: MediaCatalog
  private lateinit var store: RoomBackupStore
  private lateinit var files: AppIncomingLocalFiles
  private lateinit var scope: CoroutineScope
  private val jobs = CopyOnWriteArrayList<Boolean>()
  private var changed = 0
  private val status = MutableStateFlow<BackupStatus>(BackupStatus.UpToDate(0, null))
  private var gateOf: (String) -> BackupGateState = { BackupGateState.OPEN }
  private var fence: SharePath? = null
  private var commonShare: String? = "famiglia"

  @Before
  fun setUp() {
    db = GalleryDatabase.build(app, name = null)
    catalog = MediaCatalog(db, AccountId.of("alessio"), zone = { ZoneId.of("Europe/Rome") }) { NOW }
    store = RoomBackupStore(db, MonotonicOnlyBackupClock)
    files = AppIncomingLocalFiles(db, temp.newFolder("incoming"))
    scope = CoroutineScope(Dispatchers.Unconfined)
  }

  @After
  fun tearDown() {
    scope.cancel()
    db.close()
  }

  private fun queue(): AppIncomingQueue = AppIncomingQueue(
    database = db,
    catalog = { catalog },
    store = store,
    gate = BackupGate { share -> gateOf(share) },
    privateShare = { "alessio" },
    commonShare = { commonShare },
    fenceRoot = { fence },
    clock = MonotonicOnlyBackupClock,
    onQueued = { jobs += it },
  )

  private fun received(queue: AppIncomingQueue = queue()): AppReceivedQueue = AppReceivedQueue(
    database = db,
    queue = queue,
    files = files,
    gate = BackupGate { share -> gateOf(share) },
    backupStatus = { status },
    scope = scope,
    onChanged = { changed++ },
  )

  private var counter = 0

  /** Un file ricevuto di [state] con la sua copia locale (se [withCopy]) e, se [itemState] non e' null, il suo elemento del diario in quello stato. */
  private fun row(
    state: String,
    destShare: String = "alessio",
    destFolder: String = "Ricevute",
    itemState: String? = null,
    withCopy: Boolean = true,
    size: Int = 2_000,
  ): IncomingItemEntity = runBlocking {
    counter++
    val bytes = ByteArray(size) { (it * 7 + counter).toByte() }
    val stageId = "stage-$counter"
    val name = "file-$counter"
    val file = File(File(temp.root, "incoming/$stageId").also { it.mkdirs() }, name)
    if (withCopy) file.writeBytes(bytes)
    val sha = Fingerprint.ofContent(bytes).fullHash!!
    val itemId = itemState?.let {
      val quick = checkNotNull(AppIncomingQueue.quickHashOf(file, size.toLong())) { "la copia di prova non si legge" }
      val media = catalog.incomingMedia(Fingerprint(size.toLong(), quick, sha), MediaKind.VIDEO, NOW)
      val created = store.createIncoming(
        NewBackupItem(UploadKind.INCOMING, media.mediaId, media.versionId, size.toLong(), null, null, destShare, SharePath.of("Ricevute", "$name.mp4"), quick, sha),
      )
      val entity = checkNotNull(db.backupDiaryDao().get(created.id.value))
      db.backupDiaryDao().update(entity.copy(state = it))
      created.id.value
    }
    val id = db.incomingDao().insert(
      IncomingItemEntity(
        stageId = stageId,
        state = state,
        fileName = name,
        displayName = "Nome.mp4",
        sha256 = sha.hex,
        size = size.toLong(),
        mime = "video/mp4",
        destShare = destShare,
        destFolder = destFolder,
        backupItemId = itemId,
        createdAt = NOW,
        updatedAt = NOW,
      ),
    )
    checkNotNull(db.incomingDao().get(id))
  }

  private fun backupItems(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM backup_item").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

  // --- Riprova: un cancello alla volta --------------------------------------------------------------------------------------------------------

  /** Chi lascia mettere in coda (e poi aspetta): il giro dell'utente parte, un file `FAILED` torna "in coda" con un elemento nuovo. */
  @Test
  fun riprovaConIlCancelloAperto_OInPausaCongelatoOFermo_RimetteInCodaEChiedeIlLavoro() = runBlocking<Unit> {
    for (open in listOf(BackupGateState.OPEN, BackupGateState.PAUSED, BackupGateState.FROZEN, BackupGateState.WRITES_STOPPED)) {
      gateOf = { open }
      jobs.clear()
      val failed = row("failed")
      val before = backupItems()

      val outcome = received().retry(ReceivedId(failed.incomingId))

      assertWithMessage("$open").that(outcome).isEqualTo(ReceivedRetry.Queued)
      val after = checkNotNull(db.incomingDao().get(failed.incomingId))
      assertThat(after.state).isEqualTo(IncomingStates.QUEUED)
      assertThat(after.backupItemId).isNotNull()
      assertThat(backupItems()).isEqualTo(before + 1)
      // Un lavoro dell'utente come "Salva sul NAS": chiesto una volta, e un file piccolo non e' un lavoro UIDT.
      assertThat(jobs).containsExactly(false)
    }
  }

  /** Chi vieta: niente cambia (ne' la riga, ne' il diario) e nessun lavoro si chiede; l'utente sa quale cancello. */
  @Test
  fun riprovaConUnCancelloCheVietaNonCambiaNienteEDiceQualeE() = runBlocking<Unit> {
    val blocking = mapOf(
      BackupGateState.TEST_ONLY to ReceivedStatus.TRIAL,
      BackupGateState.READ_ONLY_AREA to ReceivedStatus.FOLDER_LOCKED,
      BackupGateState.NO_PRIVATE_AREA to ReceivedStatus.UNAVAILABLE,
      BackupGateState.NOT_READY to ReceivedStatus.UNAVAILABLE,
    )
    for ((state, expected) in blocking) {
      gateOf = { state }
      val failed = row("failed")
      val before = backupItems()

      val outcome = received().retry(ReceivedId(failed.incomingId))

      assertWithMessage("$state").that(outcome).isEqualTo(ReceivedRetry.Blocked(expected))
      assertThat(checkNotNull(db.incomingDao().get(failed.incomingId)).state).isEqualTo(IncomingStates.FAILED)
      assertThat(backupItems()).isEqualTo(before)
      assertThat(jobs).isEmpty()
    }
  }

  /** D12/R2: la prova vale per share. Un file verso l'area comune ha il cancello dell'area comune, non quello dell'area privata. */
  @Test
  fun ilCancelloELaShareDelFile() = runBlocking<Unit> {
    gateOf = { share -> if (share == "famiglia") BackupGateState.TEST_ONLY else BackupGateState.OPEN }
    val toCommon = row("failed", destShare = "famiglia")
    val toPrivate = row("failed")
    val queue = received()
    assertThat(queue.retry(ReceivedId(toCommon.incomingId))).isEqualTo(ReceivedRetry.Blocked(ReceivedStatus.TRIAL))
    assertThat(queue.retry(ReceivedId(toPrivate.incomingId))).isEqualTo(ReceivedRetry.Queued)
    // Un'area che non e' piu' dell'utente (l'admin ha cambiato le aree): la share non e' piu' ne' la privata ne' la comune.
    commonShare = null
    val gone = row("failed", destShare = "famiglia")
    assertThat(queue.retry(ReceivedId(gone.incomingId))).isEqualTo(ReceivedRetry.Blocked(ReceivedStatus.FOLDER_LOCKED))
    val foreign = row("failed", destShare = "di-altri")
    assertThat(queue.retry(ReceivedId(foreign.incomingId))).isEqualTo(ReceivedRetry.Blocked(ReceivedStatus.FOLDER_LOCKED))
  }

  /** D13: con la recinzione solo la radice di prova riceve; l'area dell'app e `Versioni/` non ricevono mai. */
  @Test
  fun riprovaRispettaLaRadiceDiProvaELeCartelleChiuse() = runBlocking<Unit> {
    fence = SharePath.of("mcg-campo")
    val queue = received()
    val outside = row("failed", destFolder = "Ricevute")
    val inside = row("failed", destFolder = "mcg-campo/Ricevute")
    val versions = row("failed", destFolder = "mcg-campo/Versioni")
    assertThat(queue.retry(ReceivedId(outside.incomingId))).isEqualTo(ReceivedRetry.Blocked(ReceivedStatus.FOLDER_LOCKED))
    assertThat(queue.retry(ReceivedId(versions.incomingId))).isEqualTo(ReceivedRetry.Blocked(ReceivedStatus.FOLDER_LOCKED))
    assertThat(queue.retry(ReceivedId(inside.incomingId))).isEqualTo(ReceivedRetry.Queued)
    assertThat(jobs).hasSize(1)
    // La stessa regola vale per "Salva sul NAS": la destinazione ammessa e' la stessa.
    val dest = checkNotNull(queue().destinationOf(checkNotNull(db.incomingDao().get(inside.incomingId))))
    assertThat(queue().destinationAllowed(dest)).isTrue()
    assertThat(queue().destinationAllowed(checkNotNull(queue().destinationOf(outside)))).isFalse()
  }

  /** Come quando lo si salva, un file grande (oltre i 256 MiB) e' un lavoro UIDT. */
  @Test
  fun ilLavoroEUIDTSoloPerIFileGrandi() = runBlocking<Unit> {
    val big = row("failed", size = 3_000)
    // Il peso della riga dice "grande": la soglia e' di 256 MiB, qui la riga e' piccola ma il file che si salva ne ha lo stesso conto.
    val queue = queue()
    queue.askJob(256L * 1024 * 1024)
    queue.askJob(1_000L)
    assertThat(jobs).containsExactly(true, false).inOrder()
    assertThat(received().retry(ReceivedId(big.incomingId))).isEqualTo(ReceivedRetry.Queued)
    assertThat(jobs.last()).isFalse()
  }

  /** Un file in coda con l'elemento aperto non ha bisogno di una copia nuova: basta chiedere il lavoro. */
  @Test
  fun riprovaUnFileInCodaChiedeSoloIlLavoro() = runBlocking<Unit> {
    val waiting = row("queued", itemState = "hashed")
    val before = backupItems()
    assertThat(received().retry(ReceivedId(waiting.incomingId))).isEqualTo(ReceivedRetry.Queued)
    assertThat(backupItems()).isEqualTo(before)
    assertThat(checkNotNull(db.incomingDao().get(waiting.incomingId)).backupItemId).isEqualTo(waiting.backupItemId)
    assertThat(jobs).containsExactly(false)
  }

  /** Il motore ha finito (`done`) ma la riga non l'ha saputo: "Riprova" non carica due volte, la sistema. */
  @Test
  fun riprovaUnFileGiaFinitoNonLoCaricaDueVolte() = runBlocking<Unit> {
    val done = row("queued", itemState = "done")
    val before = backupItems()
    assertThat(received().retry(ReceivedId(done.incomingId))).isEqualTo(ReceivedRetry.AlreadySaved)
    assertThat(backupItems()).isEqualTo(before)
    assertThat(jobs).isEmpty()
    assertThat(checkNotNull(db.incomingDao().get(done.incomingId)).state).isEqualTo(IncomingStates.UPLOADED)
  }

  // --- Scarta ---------------------------------------------------------------------------------------------------------------------------------

  @Test
  fun scartaGuardaLoStatoDelMotore() = runBlocking<Unit> {
    val uploading = row("queued", itemState = "uploading")
    val queue = received()
    status.value = BackupStatus.Running(BackupKind.PHOTOS, 1, 3, 10, 30, null, BackupTrigger.INCOMING)
    // Sta salendo adesso: non si scarta, e niente si e' mosso.
    assertThat(queue.discard(ReceivedId(uploading.incomingId))).isEqualTo(ReceivedDiscard.Uploading)
    assertThat(files.localFileOf(uploading)).isNotNull()
    assertThat(db.incomingDao().get(uploading.incomingId)).isNotNull()
    // Il motore e' fermo: l'upload a meta' si scarta (la copia sul NAS resta del motore, che la toglie da solo).
    status.value = BackupStatus.UpToDate(0, null)
    assertThat(queue.discard(ReceivedId(uploading.incomingId))).isEqualTo(ReceivedDiscard.Discarded)
    assertThat(files.localFileOf(uploading)).isNull()
    assertThat(db.incomingDao().get(uploading.incomingId)).isNull()
    assertThat(changed).isEqualTo(1)
  }

  /** Con il motore in giro un file gia' letto (i byte possono stare in memoria del motore) sta salendo: non si scarta; uno ancora da leggere, si'. */
  @Test
  fun scartaConIlMotoreInGiroGuardaSeIlFileELettoOAncoraDaLeggere() = runBlocking<Unit> {
    status.value = BackupStatus.Running(BackupKind.PHOTOS, 1, 3, 10, 30, null, BackupTrigger.INCOMING)
    val read = row("queued", itemState = "hashed")
    val waiting = row("queued", itemState = "planned")
    val queue = received()
    assertThat(queue.discard(ReceivedId(read.incomingId))).isEqualTo(ReceivedDiscard.Uploading)
    assertThat(files.localFileOf(read)).isNotNull()
    assertThat(queue.discard(ReceivedId(waiting.incomingId))).isEqualTo(ReceivedDiscard.Discarded)
    assertThat(files.localFileOf(waiting)).isNull()
  }

  @Test
  fun scartaTogliaSoloLaCopiaDiQuellaRigaENonLaCartellaSeCeAltro() = runBlocking<Unit> {
    val first = row("failed")
    val second = row("failed")
    // Due file nella stessa condivisione: la cartella resta finche' c'e' l'altro.
    val shared = File(temp.root, "incoming/shared").also { it.mkdirs() }
    val a = File(shared, "a1").apply { writeBytes(byteArrayOf(1)) }
    val b = File(shared, "b1").apply { writeBytes(byteArrayOf(2)) }
    val rowA = first.copy(stageId = "shared", fileName = "a1")
    val rowB = second.copy(stageId = "shared", fileName = "b1")
    db.incomingDao().update(rowA)
    db.incomingDao().update(rowB)
    assertThat(received().discard(ReceivedId(rowA.incomingId))).isEqualTo(ReceivedDiscard.Discarded)
    assertThat(a.exists()).isFalse()
    assertThat(b.exists()).isTrue()
    assertThat(shared.exists()).isTrue()
    assertThat(received().discard(ReceivedId(rowB.incomingId))).isEqualTo(ReceivedDiscard.Discarded)
    assertThat(shared.exists()).isFalse()
  }

  /** I nomi di una riga non escono mai da `incoming/`: un nome che non sia dell'hash non tocca niente. */
  @Test
  fun scartaNonEsceMaiDaIncoming() = runBlocking<Unit> {
    val outside = temp.newFile("importante.jpg").apply { writeBytes(byteArrayOf(9)) }
    val hostile = row("failed").let { it.copy(fileName = "../../importante.jpg") }
    db.incomingDao().update(hostile)
    received().discard(ReceivedId(hostile.incomingId))
    assertThat(outside.exists()).isTrue()
  }
}

private const val NOW = 1_760_000_000_000L
