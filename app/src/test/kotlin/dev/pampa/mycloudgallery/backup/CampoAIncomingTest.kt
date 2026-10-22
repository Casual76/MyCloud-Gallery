package dev.pampa.mycloudgallery.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.noop.MonotonicOnlyBackupClock
import dev.pampa.mycloudgallery.core.backup.store.RoomBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.integrations.receive.DefaultIncomingUploads
import dev.pampa.mycloudgallery.feature.integrations.receive.ImageBoundsReader
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingStager
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Campo, gruppo A, difetto 1 (`docs/prove-campo-esiti.md`, B-S14, `IMG_mcg_0106`): un file in coda di "Salva sul NAS" non sparisce mai senza un esito
 * registrato. Database vero in memoria, la copia immediata vera (`IncomingStager`) e la coda vera dell'app; il NAS non c'entra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CampoAIncomingTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var db: GalleryDatabase
  private lateinit var catalog: MediaCatalog
  private lateinit var stager: IncomingStager
  private val dest = NasDestination("alessio", SharePath.of("Ricevute"), DestinationArea.PRIVATE)

  @Before
  fun setUp() {
    db = GalleryDatabase.build(app, name = null)
    catalog = MediaCatalog(db, AccountId.of("alessio"), zone = { ZoneId.of("Europe/Rome") }) { NOW }
    stager = IncomingStager(temp.newFolder("incoming"), freeBytes = { 100L shl 30 }, bounds = ImageBoundsReader { null })
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun queue(): AppIncomingQueue = AppIncomingQueue(
    database = db,
    catalog = { catalog },
    store = RoomBackupStore(db, MonotonicOnlyBackupClock),
    gate = BackupGate { BackupGateState.OPEN },
    privateShare = { "alessio" },
    commonShare = { "famiglia" },
    fenceRoot = { null },
    clock = MonotonicOnlyBackupClock,
    onQueued = {},
  )

  /** Un JPEG finto (basta l'intestazione: il tipo si legge dai byte) diverso per [seed]. */
  private fun jpeg(seed: Int): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2_000) { (it * 31 + seed).toByte() }

  private fun stage(vararg seeds: Int): IncomingStage = runBlocking {
    stager.stage(seeds.map { seed -> jpeg(seed).let { bytes -> IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "IMG_$seed.jpg", "image/jpeg", bytes.size.toLong()) { ByteArrayInputStream(bytes) } } })
  }

  private fun rows(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM incoming_item").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

  private fun items(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM backup_item").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

  /**
   * La schermata si chiude (o il salvataggio si annulla) mentre la coda prepara il secondo file: prima il primo era gia' in coda con la sua riga, la
   * schermata lo credeva "non salvato" e ne toglieva la copia (il file finiva "copia sparita"). Adesso o tutti o nessuno: nessuna riga, nessun elemento.
   */
  @Test
  fun d1_unSalvataggioAnnullatoAMetaNonLasciaNienteInCoda() = runBlocking<Unit> {
    val stage = stage(1, 2)
    assertThat(stage.accepted).hasSize(2)
    val queue = queue()
    val calls = AtomicInteger()
    lateinit var saving: kotlinx.coroutines.Job
    queue.metadata = IncomingMetadata { _, _, _, _, _, _ ->
      if (calls.incrementAndGet() == 2) {
        saving.cancel()
        delay(10)
      }
    }
    saving = launch { queue.enqueue(stage, dest, stager) }
    saving.join()

    assertWithMessage("righe in coda").that(rows()).isEqualTo(0)
    assertWithMessage("elementi del diario").that(items()).isEqualTo(0)
    // Niente e' in coda: la schermata puo' togliere le copie, e le toglie.
    assertThat(queue.cites(stage.id)).isFalse()
  }

  /**
   * Le righe gia' scritte valgono: la condivisione e' del diario e "Scarta" della schermata (chiusa dopo un salvataggio annullato quando le righe erano
   * gia' nella transazione) non toglie le sue copie. Il caricamento le trova ancora.
   */
  @Test
  fun d1_unaCondivisioneInCodaNonSiScartaDallaSchermata() = runBlocking<Unit> {
    val stage = stage(3, 4)
    val queue = queue()
    assertThat(queue.enqueue(stage, dest, stager)).isEqualTo(2)
    assertThat(queue.cites(stage.id)).isTrue()

    DefaultIncomingUploads(stager, queue, FakeBackupClock()).discard(stage.id)

    assertWithMessage("copie locali").that(stager.isIntact(stage)).isTrue()
    assertThat(rows()).isEqualTo(2)
    val files = AppIncomingLocalFiles(db, File(temp.root, "incoming"))
    for (row in db.incomingDao().ofStage(stage.id.value)) assertThat(files.localFileOf(row)).isNotNull()
  }

  private companion object {
    const val NOW = 1_760_000_000_000L
  }
}
