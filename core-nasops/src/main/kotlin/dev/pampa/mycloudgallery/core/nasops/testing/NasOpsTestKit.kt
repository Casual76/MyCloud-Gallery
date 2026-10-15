package dev.pampa.mycloudgallery.core.nasops.testing

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolders
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.engine.ShareAccessView
import dev.pampa.mycloudgallery.core.nasops.engine.TrashRecordWriter
import dev.pampa.mycloudgallery.core.nasops.folders.NasFolderService
import dev.pampa.mycloudgallery.core.nasops.space.NasSpaceService
import dev.pampa.mycloudgallery.core.nasops.trash.NasTrashService
import dev.pampa.mycloudgallery.core.nasops.trash.RemoteTrashEntry
import dev.pampa.mycloudgallery.core.nasops.trash.RemoteTrashRead
import dev.pampa.mycloudgallery.core.nasops.trash.RemoteTrashSource
import dev.pampa.mycloudgallery.core.nasops.trash.SignedTrashRecords
import dev.pampa.mycloudgallery.core.nasops.trash.TrashSigningIdentity
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.data.TrashRecord
import dev.pampa.mycloudgallery.core.trust.data.TrashRecordReason
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.Dispatchers

/** Quale esecutore delle operazioni usa il kit. */
enum class KitEngine {
  /** L'esecutore di riferimento di P0-B ([InMemoryNasOperations]) e il cestino di riferimento ([InMemoryNasTrash]). */
  REFERENCE,

  /** L'esecutore vero di P12 (`NasOpsEngine`), il cestino vero (`NasTrashService`), Spazio NAS e Cartelle NAS veri. */
  PRODUCTION,
}

/**
 * Tutto cio' che serve per provare la 4B senza NAS: un NAS finto in memoria con le sue share, il diario, gli orologi, il cancello e le
 * operazioni, il cestino, lo spazio e le cartelle collegati fra loro. E' il punto di partenza di P12 (test), P14 (ViewModel), P15 (banco
 * delle 500 interruzioni) e P16 (integrazione): niente da montare a mano.
 *
 * [engine] sceglie l'esecutore: quello di riferimento (default, P0-B) o quello vero di P12 (`KitEngine.PRODUCTION`), sullo stesso diario
 * e sullo stesso NAS, con le stesse regole e gli stessi test (`OperationsTest` e `FaultMatrixTest` girano su entrambi).
 *
 * Una **morte del processo** si simula con [restart]: gli oggetti si buttano e se ne costruiscono di nuovi sullo stesso diario
 * ([store]) e sullo stesso NAS, come dopo un `am kill`. Il NAS finto, in `internal`, serve ai test dentro `core-nasops` (guasti, file
 * bloccati, contenuti); gli altri moduli usano i metodi pubblici qui sotto, che non nominano nessun tipo di `core-nas`.
 */
class NasOpsTestKit(
  shares: List<String> = listOf(PRIVATE_SHARE, COMMON_SHARE),
  val deviceId: String = DEVICE_A,
  val accountId: String = "test",
  val engine: KitEngine = KitEngine.REFERENCE,
  /** Solo con [KitEngine.PRODUCTION]: firma dei record del cestino (scritti nel NAS finto); null = nessun record. */
  private val signing: TrashSigningIdentity? = null,
) {
  val clocks = FakeOpClocks()
  private var nasMs: Long = FakeOpClocks.DEFAULT_NAS_NOW
  private var idCounter = 0

  internal val nas = InMemoryNasMedia(nasNow = { nasMs })
  internal val fileOps: NasFileOps = nas.fileOps(deviceId)

  /** Il diario: sopravvive a [restart]. */
  val store = InMemoryOpsStore()
  val gate = FakeOpsGate()

  /** Le share in sola lettura per questo account (E-N9). */
  val readOnlyShares: MutableSet<String> = HashSet()

  /** I record del cestino "letti dal NAS": solo da mostrare, non entrano mai in una pulizia. */
  val remoteTrash: MutableList<RemoteTrashRecord> = ArrayList()

  private val access = ShareAccessView { share -> readOnlyShares.any { it.equals(share, ignoreCase = true) } }
  private val shareNames = shares.toList()

  /** Il lucchetto delle scritture del "processo" corrente (nuovo a ogni [restart]). */
  internal var lock = NasOpsLock()
    private set

  var operations: NasOperations = newOperations()
    private set

  var trash: NasTrash = newTrash()
    private set

  val space: NasSpace = when (engine) {
    KitEngine.REFERENCE -> InMemoryNasSpace(fileOps, clocks)
    KitEngine.PRODUCTION -> NasSpaceService(fileOps, clocks, store)
  }

  val folders: NasFolders = when (engine) {
    KitEngine.REFERENCE -> InMemoryNasFolders(nas.reader, clocks) { it in readOnlyShares }
    KitEngine.PRODUCTION -> NasFolderService(nas.reader, clocks, access)
  }

  init {
    shares.forEach { nas.addShare(it) }
  }

  private fun newOperations(): NasOperations = when (engine) {
    KitEngine.REFERENCE -> InMemoryNasOperations(
      deviceId = deviceId,
      accountId = accountId,
      fileOps = fileOps,
      reader = nas.reader,
      store = store,
      clocks = clocks,
      gate = gate,
      readOnly = { it in readOnlyShares },
      newId = idSource(),
    )
    KitEngine.PRODUCTION -> NasOpsEngine(
      deviceId = deviceId,
      accountId = accountId,
      fileOps = fileOps,
      reader = nas.reader,
      store = store,
      clocks = clocks,
      gate = gate,
      access = access,
      records = records(),
      lock = lock,
      newId = idSource(),
    )
  }

  private fun records(): TrashRecordWriter =
    signing?.let { id -> SignedTrashRecords(deviceId, { id }, { share -> nas.fileSystem(share) }, Dispatchers.Unconfined) } ?: TrashRecordWriter.NONE

  private fun newTrash(): NasTrash = when (engine) {
    KitEngine.REFERENCE -> InMemoryNasTrash(
      deviceId = deviceId,
      store = store,
      fileOps = fileOps,
      reader = nas.reader,
      clocks = clocks,
      gate = gate,
      remoteRecords = { remoteTrash.toList() },
      readOnly = { it in readOnlyShares },
    )
    KitEngine.PRODUCTION -> NasTrashService(
      deviceId = deviceId,
      accountId = accountId,
      shares = { shareNames },
      store = store,
      fileOps = fileOps,
      reader = nas.reader,
      clocks = clocks,
      gate = gate,
      access = access,
      remote = remoteSource(),
      lock = lock,
      authorName = { owner -> remoteTrash.firstOrNull { it.ownerDeviceId == owner }?.author },
    )
  }

  /** I record "letti dal NAS" del kit come li darebbe la lettura vera: solo per i file che ci sono davvero nel cestino. */
  private fun remoteSource(): RemoteTrashSource = RemoteTrashSource { share ->
    val entries = remoteTrash.filter { it.share.equals(share, ignoreCase = true) }.mapNotNull { r ->
      if (!nas.exists(share, NasLayout.trashItemFile(r.ownerDeviceId, r.trashId).value)) return@mapNotNull null
      val record = if (!r.valid) {
        null
      } else {
        TrashRecord(
          familyId = KIT_FAMILY,
          deviceId = r.ownerDeviceId,
          share = r.share,
          trashId = r.trashId,
          originalPath = r.originalPath,
          size = r.size,
          trashedAt = r.trashedAtNas,
          reason = if (r.reason == TrashReason.MOVE) TrashRecordReason.MOVE else TrashRecordReason.DELETE,
          folder = r.isFolder,
          count = if (r.isFolder) r.fileCount ?: 0 else null,
        )
      }
      RemoteTrashEntry(share, r.ownerDeviceId, r.trashId, r.isFolder, record)
    }
    RemoteTrashRead.Entries(entries, truncated = false)
  }

  /** Id ripetibili, validi: l'n-esimo id del kit. */
  private fun idSource(): () -> String = {
    val n = ++idCounter
    Base32.encode(ByteArray(16) { i -> if (i >= 12) ((n shr (8 * (15 - i))) and 0xff).toByte() else 0x40 })
  }

  /** La morte del processo: operazioni e cestino nuovi (e un lucchetto nuovo), stesso diario, stesso NAS. */
  fun restart() {
    lock = NasOpsLock()
    operations = newOperations()
    trash = newTrash()
  }

  /** Fa passare il tempo: NAS e orologi dell'app (se l'ora del NAS e' ancorata). */
  fun advance(ms: Long) {
    nasMs += ms
    clocks.advance(ms)
  }

  // --- Il NAS visto da fuori (il PC) ---------------------------------------------------------------------------------------

  /** Mette un file con [bytes] nella share (crea le cartelle) e restituisce il suo timbro. */
  fun putFile(share: String, path: String, bytes: ByteArray, modifiedAt: Long = nasMs): NasStamp {
    val id = nas.putFile(share, path, NasContent.of(bytes), modifiedAt)
    return NasStamp(bytes.size.toLong(), modifiedAt, id)
  }

  fun putDir(share: String, path: String) = nas.putDir(share, path)

  fun exists(share: String, path: String): Boolean = nas.exists(share, path)

  fun bytes(share: String, path: String): ByteArray? = nas.bytes(share, path)

  fun lock(share: String, path: String) = nas.lock(share, path)

  fun unlock(share: String, path: String) = nas.unlock(share, path)

  /** Modifica sul posto dal PC (stesso `fileId`, byte e data nuovi). */
  fun modify(share: String, path: String, bytes: ByteArray, modifiedAt: Long = nasMs) = nas.modify(share, path, NasContent.of(bytes), modifiedAt)

  /** Rinomina dal PC. */
  fun rename(share: String, from: String, to: String) = nas.rename(share, from, to)

  /** Cancellazione dal PC. */
  fun deleteFromPc(share: String, path: String) = nas.delete(share, path)

  /** I file (non le cartelle) di una share, come percorsi, in ordine. */
  fun filesIn(share: String): List<String> = nas.filePaths(share)

  // --- Scorciatoie per i test ----------------------------------------------------------------------------------------------

  /** L'elemento che l'utente selezionerebbe: file o cartella con il timbro visto adesso. */
  suspend fun itemOf(share: String, path: String, folderSize: Long = 0): NasItem {
    val sharePath = SharePath.parse(path)
    val entry = (nas.reader.stat(share, sharePath, Purpose.INTERACTIVE) as? NasResult.Ok)?.value
      ?: error("L'elemento di prova non esiste sul NAS finto.")
    return NasItem(
      share = share,
      path = sharePath,
      isFolder = entry.isDirectory,
      size = if (entry.isDirectory) folderSize else entry.size,
      stamp = if (entry.isDirectory) null else NasStamp(entry.size, entry.modifiedAt, entry.fileId),
    )
  }

  /** La conferma come la darebbe la UI dopo il tocco dell'utente. */
  fun confirm(plan: OpPlan): UserConfirmation = TestConfirmations.forPlan(plan, clocks.monotonicMs())

  /** Pianifica, conferma e avvia: il giro intero di un'operazione senza ostacoli. */
  suspend fun run(request: OpRequest): StartOutcome {
    val plan = (operations.plan(request) as? PlanOutcome.Ready)?.plan ?: error("La richiesta non diventa un piano: $request")
    return operations.start(plan, confirm(plan))
  }

  companion object {
    const val PRIVATE_SHARE: String = "test"
    const val COMMON_SHARE: String = "test-comune"

    /** Due dispositivi di prova: id validi e stabili. */
    val DEVICE_A: String = Base32.encode(ByteArray(16) { 0x11 })
    val DEVICE_B: String = Base32.encode(ByteArray(16) { 0x22 })

    /** La famiglia dei record "letti dal NAS" del kit (solo da mostrare). */
    private val KIT_FAMILY: String = Base32.encode(ByteArray(16) { 0x33 })
  }
}
