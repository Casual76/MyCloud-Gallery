package dev.pampa.mycloudgallery.core.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupRunResult
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Il banco di prova del backup (Fase 5A, P0): NAS finto con l'area privata e quella comune, telefono finto, diario, catalogo, cancello,
 * orologi e il motore di riferimento, collegati. [restart] simula la morte del processo: il motore si ricrea, NAS, telefono, diario e
 * catalogo restano (sono il NAS, il telefono e il disco). Lo usano `EveryCallTest` (P0) e il banco delle 500 interruzioni (P6), che
 * passera' al motore vero di P2/P3 cambiando solo [newEngine].
 *
 * Scritture piccole di proposito ([chunkBytes], [checkpointBytes]): i punti di ripresa e i guasti a ogni chiamata si provano con file
 * da decine di KiB; i video da GB virtuali passano dallo stesso codice (`InMemoryNasMedia.registerKnownContent`).
 */
class BackupTestKit(
  val deviceId: String = DEVICE,
  val share: String = PRIVATE_SHARE,
  val commonShare: String = COMMON_SHARE,
  val chunkBytes: Int = 16 * 1024,
  val checkpointBytes: Long = 32L * 1024,
) {
  val clock = FakeBackupClock()
  val nas = InMemoryNasMedia(nasNow = { clock.nasNow ?: FakeBackupClock.DEFAULT_NAS_NOW }).apply {
    addShare(share)
    addShare(commonShare)
  }
  val ops: NasBackupOps = nas.backupOps(deviceId)
  val phone = FakePhoneBytes()
  val store = InMemoryBackupStore()
  val catalog = InMemoryBackupCatalog(share, commonShare)
  val gate = FakeBackupGate()
  var sources: BackupSources = BackupSources.DEFAULT
  var folder: DeviceFolder? = DeviceFolder(DEVICE_FOLDER)
  val planner = ReferenceBackupPlanner(catalog, { sources })

  private var nextPhoneId = 100L
  private var nextSort = 1_000_000L

  /** Il motore del "processo" attuale (nuovo a ogni [restart]). */
  var engine: InMemoryBackupEngine = newEngine()
    private set

  /** Un motore sopra lo stesso NAS, telefono, diario e catalogo (anche con un diario o un catalogo che "muoiono"). */
  fun newEngine(store: BackupStore = this.store, catalog: BackupCatalog = this.catalog): InMemoryBackupEngine = InMemoryBackupEngine(
    deviceId = deviceId,
    share = share,
    ops = ops,
    reader = nas.reader,
    store = store,
    catalog = catalog,
    phone = phone.port,
    gate = gate,
    clock = clock,
    planner = planner,
    folder = { folder },
    chunkBytes = chunkBytes,
    checkpointBytes = checkpointBytes,
  )

  /** La morte del processo: si ricrea solo cio' che sta in memoria. */
  fun restart() {
    engine = newEngine()
  }

  /**
   * Un media del telefono, gia' letto dal catalogo (proprio, nella sorgente [source]): restituisce l'id del media. I contenuti oltre
   * 1 MiB si registrano nel NAS finto come "noti" (un video virtuale da GB non occupa memoria anche quando e' caricato).
   */
  fun addPhoneMedia(
    name: String,
    content: NasContent,
    kind: MediaKind = MediaKind.PHOTO,
    localDay: Int? = 20261012,
    source: BackupSource = BackupSource.Camera,
    modifiedMs: Long = 1_000,
  ): MediaId {
    val id = nextPhoneId++
    val ref = phone.add(id, content, modifiedMs)
    if (content.size > 1L shl 20) nas.registerKnownContent(content)
    val mediaId = MediaId.random()
    catalog.put(
      InMemoryBackupCatalog.Media(
        mediaId = mediaId, versionId = VersionId.random(), kind = kind, size = content.size, quickHash = null, phone = ref,
        phoneStamp = phone.stamp(id), source = source, localDay = localDay, fileName = name, sortTime = nextSort--, fullHash = null,
      ),
    )
    return mediaId
  }

  /** L'`_ID` del telefono di un media aggiunto con [addPhoneMedia]. */
  fun phoneIdOf(mediaId: MediaId): Long = requireNotNull(catalog.media(mediaId)?.phone).mediaStoreId

  /**
   * Un file che c'era **prima** del backup (vecchi backup, il PC, un altro dispositivo). Con [knownToCatalog] la scansione l'ha gia'
   * visto: la deduplica lo trova (§2.11 punto 1); senza, il backup lo scopre solo pubblicando (nome occupato, E-B3).
   */
  suspend fun putNasFile(share: String, path: String, content: NasContent, knownToCatalog: Boolean = true) {
    val fileId = nas.putFile(share, path, content, modifiedAt = (clock.nasNow ?: FakeBackupClock.DEFAULT_NAS_NOW) - 86_400_000L)
    if (!knownToCatalog) return
    val entry = nas.reader.stat(share, SharePath.parse(path), Purpose.BACKGROUND).okOrNull() ?: error("File appena messo assente.")
    val owner = InMemoryBackupCatalog.Media(
      mediaId = MediaId.random(), versionId = VersionId.random(), kind = MediaKind.PHOTO, size = content.size, quickHash = null, phone = null,
      phoneStamp = null, source = BackupSource.Camera, localDay = null, fileName = SharePath.parse(path).name, sortTime = 0, fullHash = null,
      mine = false,
    )
    catalog.put(owner)
    catalog.putCopy(owner.mediaId, owner.versionId, share, SharePath.parse(path), NasStamp(entry.size, entry.modifiedAt, entry.fileId ?: fileId))
  }

  /** Un giro delle foto e uno dei video (D11: prima le foto). */
  suspend fun runAll(budget: Duration = 1.hours): List<BackupRunResult> = listOf(
    engine.run(BackupKind.PHOTOS, budget, BackupTrigger.SCHEDULED),
    engine.run(BackupKind.VIDEOS, budget, BackupTrigger.SCHEDULED),
  )

  /** Giri finche' il diario non ha piu' niente da fare o niente cambia (al massimo [maxRounds]). */
  suspend fun resumeUntilQuiet(maxRounds: Int = 10) {
    repeat(maxRounds) {
      val results = runAll()
      val open = store.all().count { !it.state.terminal }
      if (open == 0 && results.all { it.stoppedBy == RunStop.COMPLETE }) return
    }
  }

  /** Cosa c'e' adesso (per [BackupInvariants]). */
  suspend fun snapshot(): BackupInvariants.Snapshot = BackupInvariants.snapshot(this)

  /** Gli invarianti del §5.4 rispetto a [before]: l'elenco delle violazioni (vuoto = tutto bene). */
  suspend fun violations(before: BackupInvariants.Snapshot, final: Boolean): List<String> = BackupInvariants.check(this, before, final)

  companion object {
    /** Un id di dispositivo valido (`TrustIds`). */
    val DEVICE: String = Base32.encode(ByteArray(16) { 0x5A })

    const val PRIVATE_SHARE: String = "test"
    const val COMMON_SHARE: String = "test-comune"
    const val DEVICE_FOLDER: String = "S25 di prova"
  }
}
