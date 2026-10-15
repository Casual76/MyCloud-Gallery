package dev.pampa.mycloudgallery.core.nasops.testing

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Tutto cio' che serve per provare la 4B senza NAS: un NAS finto in memoria con le sue share, il diario, gli orologi, il cancello e le
 * operazioni, il cestino, lo spazio e le cartelle di riferimento collegati fra loro. E' il punto di partenza di P12 (test), P14
 * (ViewModel), P15 (banco delle 500 interruzioni) e P16 (integrazione): niente da montare a mano.
 *
 * Una **morte del processo** si simula con [restart]: gli oggetti si buttano e se ne costruiscono di nuovi sullo stesso diario
 * ([store]) e sullo stesso NAS, come dopo un `am kill`. Il NAS finto, in `internal`, serve ai test dentro `core-nasops` (guasti, file
 * bloccati, contenuti); gli altri moduli usano i metodi pubblici qui sotto, che non nominano nessun tipo di `core-nas`.
 */
class NasOpsTestKit(
  shares: List<String> = listOf(PRIVATE_SHARE, COMMON_SHARE),
  val deviceId: String = DEVICE_A,
  val accountId: String = "test",
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

  var operations: InMemoryNasOperations = newOperations()
    private set

  var trash: InMemoryNasTrash = newTrash()
    private set

  val space: InMemoryNasSpace = InMemoryNasSpace(fileOps, clocks)

  val folders: InMemoryNasFolders = InMemoryNasFolders(nas.reader, clocks) { it in readOnlyShares }

  init {
    shares.forEach { nas.addShare(it) }
  }

  private fun newOperations() = InMemoryNasOperations(
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

  private fun newTrash() = InMemoryNasTrash(
    deviceId = deviceId,
    store = store,
    fileOps = fileOps,
    reader = nas.reader,
    clocks = clocks,
    gate = gate,
    remoteRecords = { remoteTrash.toList() },
    readOnly = { it in readOnlyShares },
  )

  /** Id ripetibili, validi: l'n-esimo id del kit. */
  private fun idSource(): () -> String = {
    val n = ++idCounter
    Base32.encode(ByteArray(16) { i -> if (i >= 12) ((n shr (8 * (15 - i))) and 0xff).toByte() else 0x40 })
  }

  /** La morte del processo: operazioni e cestino nuovi, stesso diario, stesso NAS. */
  fun restart() {
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
  }
}
