package dev.pampa.mycloudgallery.core.backup.upload

import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOpsFactory
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasCredentialSource
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Il **simulatore SMB** di core-nas (semantica Samba: maiuscole, `FILE_CREATE`, rinomina senza sostituire, condivisioni e blocchi, fine
 * file sullo stesso handle) dietro la **sessione di produzione** ([SmbNasSessionManager]) con le **primitive vere** del backup
 * ([NasBackupOpsFactory] → `SmbNasBackupOps` → `SmbNasFileOps`) e la lettura vera (`SmbNasMediaReader`, `readAll` a 4 flussi). Il resto
 * del banco (telefono, diario, catalogo, piano, cancello, orologi) e' quello del P0 ([BackupTestKit]); [checks] e' il verificatore degli
 * invarianti del §5.4 sui file del simulatore.
 */
internal class SimWorld(scope: CoroutineScope, val kit: BackupTestKit = BackupTestKit()) {
  /** Il tempo virtuale delle latenze del simulatore (e della sessione). */
  val clock = ManualMonotonicClock(START_MONOTONIC)
  val backend = InMemorySmbBackend().apply {
    addUser(USER, PASSWORD)
    addShare(kit.share, mapOf(USER to SimAccess.READ_WRITE))
    addShare(kit.commonShare, mapOf(USER to SimAccess.READ_WRITE))
  }
  val manager = SmbNasSessionManager(
    account = { NasAccount(NasAddress(HOST), USER, "slot-prova", backend.serverGuid) },
    routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding("wifi-casa")) },
    credentials = FakeNasCredentialSource { CredentialLoad.Found(NasCredentials(USER, PASSWORD.toCharArray())) },
    breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope, wallClock = { WALL_CLOCK }),
    scope = scope,
    transport = backend,
    clock = clock,
    io = Dispatchers.Unconfined,
    deviceId = { kit.deviceId },
    backoff = { },
    shareGuard = null,
  )
  val ops: NasBackupOps = NasBackupOpsFactory.create(manager, kit.deviceId)
  val reader: NasMediaReader = manager.mediaReader()

  /** Il motore vero sopra il simulatore. */
  fun engineKit(parallel: Int = 2, config: UploadConfig? = null): RealEngineKit = RealEngineKit(
    kit = kit,
    parallel = parallel,
    config = config ?: UploadConfig(chunkBytes = kit.chunkBytes, checkpointBytes = kit.checkpointBytes, parallel = parallel, lockedFirstWaitMs = 10, lockedRetries = 3),
    ops = ops,
    reader = reader,
    finder = { catalog -> RereadingCopyFinder(catalog, reader, kit.clock) },
  )

  /** Un file che c'era prima del backup (il PC, un vecchio backup): solo sul simulatore, il catalogo non lo conosce. */
  fun putFile(path: String, bytes: ByteArray) {
    backend.put(kit.share, path, bytes, lastWriteMs = START_NAS - DAY_MS)
  }

  /** Tutti i file della share: percorso → (SHA-256, `fileId`). */
  fun files(): Map<String, Pair<Sha256, Long?>> {
    val out = LinkedHashMap<String, Pair<Sha256, Long?>>()
    fun walk(dir: String) {
      for (name in backend.children(kit.share, dir)) {
        val path = if (dir.isEmpty()) name else "$dir/$name"
        val bytes = backend.bytes(kit.share, path)
        if (bytes == null) walk(path) else out[path] = Sha256.of(bytes) to backend.fileId(kit.share, path)
      }
    }
    walk("")
    return out
  }

  /**
   * Gli invarianti del §5.4 sui file del simulatore rispetto a [before] (1, 2, 3, 6, 8, 9, 10): nessun file a meta' visibile, nessun
   * doppione, nessuna sostituzione, temporanei solo propri e citati (a lavoro finito nessuno), transizioni ammesse, file solo in `Backup/`.
   */
  suspend fun checks(before: Map<String, Pair<Sha256, Long?>>, phoneShas: Set<Sha256>, final: Boolean): List<String> {
    val v = ArrayList<String>()
    val now = files()
    val beforeKeys = before.keys.associateBy { it.lowercase(Locale.ROOT) }
    for ((path, old) in before) {
      val cur = now.entries.firstOrNull { it.key.equals(path, ignoreCase = true) }
      when {
        cur == null -> if (!path.startsWith(".mycloudgallery/")) v += "3: sparito $path"
        cur.value.first != old.first -> v += "3: byte cambiati in $path"
        cur.value.second != old.second -> v += "3: fileId cambiato in $path"
        cur.key != path -> v += "3: nome cambiato in $path"
      }
    }
    val cited = kit.store.citedTemps().map { it.value.lowercase(Locale.ROOT) }.toSet()
    val created = now.filterKeys { it.lowercase(Locale.ROOT) !in beforeKeys }
    val visibleBefore = before.filterKeys { !it.startsWith(".mycloudgallery/") }.values.map { it.first }.toSet()
    for ((path, fact) in created) {
      if (path.startsWith(".mycloudgallery/")) {
        val temp = SharePath.parse(path)
        when {
          !NasBackupPaths.isBackupTempOf(temp, kit.deviceId) -> v += "6: file dell'app che non e' un temporaneo del backup ($path)"
          final -> v += "6: temporaneo rimasto ($path)"
          path.lowercase(Locale.ROOT) !in cited -> v += "6: temporaneo non citato ($path)"
        }
        continue
      }
      if (!path.startsWith("Backup/")) v += "10: fuori dalle radici ($path)"
      if (fact.first !in phoneShas) v += "1: file visibile con byte di nessun media ($path)"
      if (fact.first in visibleBefore) v += "2: doppione di un file che c'era ($path)"
    }
    created.filterKeys { !it.startsWith(".mycloudgallery/") }.values.groupBy { it.first }.filterValues { it.size > 1 }.forEach { _ ->
      v += "2: gli stessi byte pubblicati due volte"
    }
    val flows = kit.store.all().associate { it.id to it.flow }
    for (t in kit.store.transitions) {
      val flow = flows[t.item] ?: continue
      if (!UploadStateMachine.canTransition(flow, t.from, t.to)) v += "8: transizione ${t.from.code} -> ${t.to.code}"
    }
    if (final) {
      for (item in kit.store.all()) {
        if (item.state !in FINISHED) v += "9: elemento #${item.id} fermo in ${item.state.code}"
        if (item.state == UploadState.DONE || item.state == UploadState.LINKED_EXISTING) {
          if (!kit.catalog.isSafe(item.mediaId)) v += "7: #${item.id} senza copia al sicuro"
          val fp = item.finalPath
          if (fp == null || now.keys.none { it.equals(fp.value, ignoreCase = true) }) v += "8: nome finale di #${item.id} assente"
        }
      }
    }
    return v
  }

  fun phoneShas(): Set<Sha256> = kit.phone.allSha256()

  companion object {
    const val USER = "test"
    const val PASSWORD = "pw-di-prova-lunga"
    const val HOST = "192.168.178.58"
    const val START_MONOTONIC = 10_000_000L
    const val WALL_CLOCK = 1_791_763_200_000L
    const val START_NAS = 1_791_763_200_000L
    const val DAY_MS = 86_400_000L
    val FINISHED = setOf(UploadState.DONE, UploadState.LINKED_EXISTING, UploadState.GONE)

    /** Lo SHA-256 di un contenuto del telefono finto. */
    fun sha(content: NasContent): Sha256 = FakePhoneBytes.sha256Of(content)
  }
}
