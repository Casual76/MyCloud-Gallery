package dev.pampa.mycloudgallery.core.nasops.p12

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.NasTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.AcceptedPlan
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.engine.ShareAccessView
import dev.pampa.mycloudgallery.core.nasops.engine.TrashRecordWriter
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.trash.TrashSigningIdentity
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasFileSystem
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryDraft
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import java.util.Locale
import kotlinx.coroutines.flow.Flow

// Il "mondo" dei test di P12: una famiglia vera (record del cestino e teste firmate), la morte del processo a ogni passo, gli invarianti
// del §5.5 di docs/fase4-progetto.md controllati sul NAS finto.

/** Una famiglia di prova con il registro firmato (record del cestino, teste e prenotazioni firmate dai dispositivi approvati). */
class SigningFamily(nowNas: Long) {
  private val clock = NasClock { nowNas }
  private val familyKey = FamilyKey.generate()
  val familyId: String = TrustIds.random()
  private val authority = FamilyAuthority(familyKey, clock)
  private val common = InMemoryNasFileSystem("test-comune")

  lateinit var state: RegistryState
    private set

  init {
    val admin = FamilyKey.generate()
    val founder = verified(request(admin, "Telefono admin", "admin"))
    val signed = authority.found(familyId, FamilyConfig(nasHost = "192.168.178.58", commonShare = "test-comune"), founder)
    check(FamilyAuthority.publishFounding(common, signed) == PublishResult.CREATED)
    val anchor = TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 0)!!
    state = (RegistryVerifier.accept(signed.bytes, null, anchor) as Verdict.Accepted).value
  }

  /** Approva un dispositivo nuovo dell'account [nasUser] e restituisce la sua chiave. */
  fun approveDevice(name: String = "Telefono", nasUser: String = "test"): Signer {
    val device = FamilyKey.generate()
    val request = verified(request(device, name, nasUser))
    commit(authority.approve(state, request, ApprovalProof.TypedCode(request.approvalCode), firstSeenAt = clock.nowMillis()))
    return device
  }

  fun identity(device: Signer) = TrashSigningIdentity(familyId, device)

  private fun commit(draft: RegistryDraft) {
    val result = authority.commit(common, draft)
    check(result is CommitResult.Published) { "registro non pubblicato: $result" }
    state = result.state
  }

  private fun request(device: Signer, name: String, nasUser: String): ByteArray =
    RegistrationRequest.sign(device, familyId, name, "SM-S931B", nasUser, clock.nowMillis())

  private fun verified(bytes: ByteArray): RegistrationRequest =
    (RegistrationRequest.verify(bytes, familyId, TrustIds.deviceIdOf(EcPublicKey.fromSpki(bytes.copyOfRange(6, 6 + 91)))) as Verdict.Accepted).value
}

fun Signer.deviceId(): String = TrustIds.deviceIdOf(publicKey)

// --- La morte del processo a ogni passo ----------------------------------------------------------------------------------------

/** La morte del processo: un `Error`, cosi' nessun `catch` del codice di produzione la intercetta. */
class ProcessDeath : Error("morte simulata del processo")

/**
 * Il contatore dei passi di un processo: ogni scrittura del diario e ogni chiamata al NAS e' un passo; al passo [dieAt] il processo
 * muore PRIMA o DOPO che la chiamata abbia avuto effetto ([afterEffect]).
 */
class Death(private val dieAt: Int = Int.MAX_VALUE, private val afterEffect: Boolean = false) {
  var steps = 0
    private set
  var died = false
    private set

  suspend fun <T> around(block: suspend () -> T): T {
    val here = ++steps
    if (here == dieAt && !afterEffect) {
      died = true
      throw ProcessDeath()
    }
    val result = block()
    if (here == dieAt && afterEffect) {
      died = true
      throw ProcessDeath()
    }
    return result
  }
}

/** Il diario che muore: le scritture contano come passi (le letture no: non cambiano niente). */
class DyingStore(private val inner: OpsStore, private val death: Death) : OpsStore by inner {
  override suspend fun create(accepted: AcceptedPlan, id: OpId, accountId: String, nowNas: Long): OpRecord =
    death.around { inner.create(accepted, id, accountId, nowNas) }

  override suspend fun updateItem(item: OpItemRecord) = death.around { inner.updateItem(item) }

  override suspend fun setOpState(id: OpId, state: OpState, errorCode: OpErrorCode?, nowNas: Long) =
    death.around { inner.setOpState(id, state, errorCode, nowNas) }

  override suspend fun recordTrash(entry: LocalTrashRecord) = death.around { inner.recordTrash(entry) }

  override suspend fun markTrash(trashId: String, state: TrashEntryState, nowNas: Long) = death.around { inner.markTrash(trashId, state, nowNas) }

  override fun observeOps(): Flow<List<OpRecord>> = inner.observeOps()
}

/** Le primitive di scrittura che muoiono: ogni chiamata e' un passo. */
class DyingFileOps(private val inner: NasFileOps, private val death: Death) : NasFileOps {
  override val ownerDeviceId: String get() = inner.ownerDeviceId

  override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose) = death.around { inner.mkdirs(share, dir, purpose) }

  override suspend fun rename(share: String, from: SharePath, to: SharePath, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> =
    death.around { inner.rename(share, from, to, expect, purpose) }

  override suspend fun copyToTemp(fromShare: String, from: SharePath, toShare: String, temp: SharePath, expect: NasStamp?, purpose: Purpose): NasResult<CopyOutcome> =
    death.around { inner.copyToTemp(fromShare, from, toShare, temp, expect, purpose) }

  override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<NasTempWriter> =
    death.around { inner.openTemp(share, temp, purpose) }

  override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> =
    death.around { inner.publishTemp(share, temp, final, purpose) }

  override suspend fun trashMove(share: String, path: SharePath, trashId: String, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> =
    death.around { inner.trashMove(share, path, trashId, expect, purpose) }

  override suspend fun deleteIfUnchanged(share: String, path: SharePath, size: Long, purpose: Purpose): NasResult<DeleteOutcome> =
    death.around { inner.deleteIfUnchanged(share, path, size, purpose) }

  override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> =
    death.around { inner.deleteOwnTemp(share, temp, purpose) }

  override suspend fun rmdirIfEmpty(share: String, dir: SharePath, purpose: Purpose): NasResult<RmdirOutcome> =
    death.around { inner.rmdirIfEmpty(share, dir, purpose) }

  override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> = death.around { inner.space(share, purpose) }
}

/** La lettura che muore: ogni chiamata e' un passo. */
class DyingReader(private val inner: NasMediaReader, private val death: Death) : NasMediaReader {
  override suspend fun list(share: String, dir: SharePath?, purpose: Purpose, pageSize: Int, onPage: suspend (List<NasEntry>) -> Unit): NasResult<ListOutcome> =
    death.around { inner.list(share, dir, purpose, pageSize, onPage) }

  override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> = death.around { inner.stat(share, path, purpose) }

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> = death.around { inner.open(share, path, purpose) }

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> =
    death.around { inner.readAll(file, sink, progress) }
}

/** Un esecutore vero sul diario e sul NAS del [kit], con la morte del processo programmata da [death]. */
fun NasOpsTestKit.engineWith(death: Death, records: TrashRecordWriter = TrashRecordWriter.NONE): NasOpsEngine = NasOpsEngine(
  deviceId = deviceId,
  accountId = accountId,
  fileOps = DyingFileOps(fileOps, death),
  reader = DyingReader(nas.reader, death),
  store = DyingStore(store, death),
  clocks = clocks,
  gate = gate,
  access = ShareAccessView { share -> readOnlyShares.any { it.equals(share, ignoreCase = true) } },
  records = records,
  lock = NasOpsLock(),
)

// --- Gli invarianti del §5.5 ----------------------------------------------------------------------------------------------------

/** Gli originali del mondo di prova: ogni file presente all'inizio (visibile o in un cestino), per contenuto. */
class Originals(val byContent: Map<List<Byte>, String>) {
  companion object {
    fun of(kit: NasOpsTestKit, shares: List<String>): Originals {
      val map = LinkedHashMap<List<Byte>, String>()
      for (share in shares) for (path in kit.filesIn(share)) {
        // I media: visibili o dentro un cestino (`trash/<dispositivo>/files/...`); non i file dell'app (record, indice, temporanei).
        if (isAppArea(path) && !path.lowercase(Locale.ROOT).contains("/files/")) continue
        map[kit.bytes(share, path)!!.toList()] = "$share/$path"
      }
      return Originals(map)
    }
  }
}

fun isAppArea(path: String) = path.lowercase(Locale.ROOT).startsWith(".mycloudgallery/")

fun isTemp(path: String) = path.lowercase(Locale.ROOT).startsWith(".mycloudgallery/tmp/")

/**
 * Gli invarianti del §5.5 sul NAS finto, dopo un guasto (a meta') o alla fine:
 *  1. nessun file a meta' visibile: ogni file fuori da `.mycloudgallery/` ha i byte di un originale;
 *  2. (alla fine) nessun doppione: ogni originale compare al massimo una volta fra i file visibili;
 *  3. nessuna perdita: ogni originale (tranne quelli eliminati per sempre su richiesta, [purged]) e' visibile o in un cestino;
 *  4. (alla fine) i byte sono quelli originali (implicito in 1 e 3: si confronta il contenuto intero);
 *  5. temporanei: solo i propri, citati da un elemento non chiuso del diario (alla fine: nessuno);
 *  cestino coerente: un file nel proprio cestino ha la sua voce TRASHED nel diario locale.
 */
suspend fun NasOpsTestKit.checkInvariants(originals: Originals, shares: List<String>, label: String, final: Boolean, purged: Set<List<Byte>> = emptySet()) {
  val visibleCount = HashMap<List<Byte>, Int>()
  val trashedCount = HashMap<List<Byte>, Int>()
  val own = ".mycloudgallery/trash/${deviceId}/files/"
  val entries = store.allTrash().associateBy { it.trashId }
  val cited = HashSet<String>()
  for (op in store.ops(setOf(OpState.PLANNED, OpState.RUNNING, OpState.PAUSED))) {
    store.items(op.id).filter { !it.state.terminal }.forEach { item -> item.tempName?.let { cited += it.value.lowercase(Locale.ROOT) } }
  }
  for (share in shares) {
    for (path in filesIn(share)) {
      val content = bytes(share, path)!!.toList()
      when {
        isTemp(path) -> {
          assertWithMessage("$label: temporaneo $share/$path non proprio").that(path.lowercase(Locale.ROOT)).startsWith(".mycloudgallery/tmp/${deviceId}-")
          if (final) assertWithMessage("$label: temporaneo rimasto alla fine ($share/$path)").fail()
          assertWithMessage("$label: temporaneo non citato dal diario ($share/$path)").that(path.lowercase(Locale.ROOT)).isIn(cited)
        }
        isAppArea(path) -> {
          val lower = path.lowercase(Locale.ROOT)
          if (lower.startsWith(own.lowercase(Locale.ROOT))) {
            val trashId = path.substring(own.length).substringBefore('/')
            val entry = entries[trashId]
            assertWithMessage("$label: file nel proprio cestino senza voce TRASHED ($share/$path)").that(entry?.state).isEqualTo(TrashEntryState.TRASHED)
          }
          if (lower.contains("/files/")) trashedCount.merge(content, 1, Int::plus)
        }
        else -> {
          assertWithMessage("$label: file visibile che non e' un originale ($share/$path)").that(originals.byContent).containsKey(content)
          visibleCount.merge(content, 1, Int::plus)
        }
      }
    }
  }
  for ((content, where) in originals.byContent) {
    if (content in purged) continue
    val visible = visibleCount[content] ?: 0
    val trashed = trashedCount[content] ?: 0
    assertWithMessage("$label: originale perso ($where)").that(visible + trashed).isAtLeast(1)
    if (final) assertWithMessage("$label: originale visibile due volte ($where)").that(visible).isAtMost(1)
  }
}

/** Riprende dal diario finche' resta qualcosa di attivo (al massimo [rounds] giri, facendo passare il tempo delle attese). */
suspend fun NasOpsTestKit.resumeUntilQuiet(rounds: Int = 8) {
  repeat(rounds) {
    operations.resumeAll(kotlin.time.Duration.parse("5m"))
    if (operations.operations.value.none { it.active }) return
    advance(61_000)
  }
}
