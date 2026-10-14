package dev.pampa.mycloudgallery.core.nas.noop

import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionReport
import dev.pampa.mycloudgallery.core.nas.contract.ConnectionTester
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.DirPage
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasBrowser
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasCredentialSource
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasHousekeeping
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.NasShareScope
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.ServerCopy
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.nas.contract.ShareProbe
import dev.pampa.mycloudgallery.core.nas.contract.Stage
import dev.pampa.mycloudgallery.core.nas.contract.StageOutcome
import dev.pampa.mycloudgallery.core.nas.contract.StageResult
import dev.pampa.mycloudgallery.core.nas.contract.TestTarget
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

// I null-object di core-nas (release-safe, fuori da `testing`): nessuna sessione, nessuna autenticazione,
// nessun byte verso il NAS. Ogni operazione risponde "percorso non configurato".

private val notConfigured = NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED)

/** Nessun NAS configurato: ogni operazione e' negata prima di aprire qualsiasi socket. */
object NoNasSession : NasSessionManager {
  override val state: StateFlow<NasConnState> = MutableStateFlow(NasConnState.Idle)
  override val lastDiskTouchAt: StateFlow<Long?> = MutableStateFlow(null)

  override suspend fun <T> withShare(share: String, purpose: Purpose, block: suspend (NasShareScope) -> T): NasResult<T> =
    NasResult.Err(notConfigured)

  override fun fileSystem(share: String): NasFileSystem = UnreachableNasFileSystem(share)

  override fun browser(share: String): NasBrowser = NoNasBrowser

  override suspend fun invalidate(reason: InvalidateReason) = Unit
}

/** Un `NasFileSystem` che non raggiunge niente: ogni chiamata lancia [NasIoException]. */
class UnreachableNasFileSystem(override val shareName: String) : NasFileSystem {
  override fun list(dir: SharePath, limit: Int): NasListing = throw NasIoException(notConfigured)

  override fun read(path: SharePath, maxBytes: Int): NasRead = throw NasIoException(notConfigured)

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = throw NasIoException(notConfigured)
}

object NoNasBrowser : NasBrowser {
  override suspend fun list(dir: SharePath?, limit: Int): NasResult<DirPage> = NasResult.Err(notConfigured)
}

/** Interruttore chiuso e immobile (non c'e' nessuna autenticazione da proteggere). */
object IdleAuthCircuitBreaker : AuthCircuitBreaker {
  override val state: StateFlow<BreakerState> = MutableStateFlow(BreakerState.Closed(generation = 0))

  override suspend fun onNewPasswordSaved() = Unit
}

/** Il test di connessione si ferma alla prima fase: percorso non configurato. */
object NoConnectionTester : ConnectionTester {
  override suspend fun run(target: TestTarget, credentials: NasCredentials?, stages: Set<Stage>): ConnectionReport =
    ConnectionReport(
      stages = Stage.entries.filter { it in stages }.mapIndexed { index, stage ->
        if (index == 0) {
          StageResult(stage, StageOutcome.FAILED, Duration.ZERO, notConfigured)
        } else {
          StageResult(stage, StageOutcome.NOT_RUN, Duration.ZERO)
        }
      },
      negotiation = null,
    )
}

object NoShareProbe : ShareProbe {
  override suspend fun probe(share: String): NasResult<ShareAccess> = NasResult.Err(notConfigured)
}

object NoCredentialSource : NasCredentialSource {
  override suspend fun load(slot: String): CredentialLoad = CredentialLoad.Absent
}

object NoNasHousekeeping : NasHousekeeping {
  override suspend fun purgeOwnStaleTemps(deviceId: String, olderThan: Duration): NasResult<Int> = NasResult.Err(notConfigured)
}

/** Fase 4: nessun NAS, nessuna lettura di media. Ogni chiamata e' negata prima di aprire un socket. */
object NoNasMediaReader : NasMediaReader {
  override suspend fun list(
    share: String,
    dir: SharePath?,
    purpose: Purpose,
    pageSize: Int,
    onPage: suspend (List<NasEntry>) -> Unit,
  ): NasResult<ListOutcome> = NasResult.Err(notConfigured)

  override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> = NasResult.Err(notConfigured)

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> = NasResult.Err(notConfigured)

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> =
    NasResult.Err(notConfigured)
}

/** Fase 4: nessuna copia lato server. */
object NoServerCopy : ServerCopy {
  override suspend fun copy(fromShare: String, from: SharePath, toShare: String, to: SharePath): NasResult<Long> =
    NasResult.Err(notConfigured)
}
