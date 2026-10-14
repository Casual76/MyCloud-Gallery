package dev.pampa.mycloudgallery.core.nas

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.ServerCopy
import dev.pampa.mycloudgallery.core.nas.fs.SmbServerSideCopy
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import kotlinx.coroutines.test.TestScope

/**
 * Lo stesso contratto della lettura dei media ([NasMediaReaderContract]) sull'implementazione SMB vera
 * (`SmbNasMediaReader` sopra `SmbNasSessionManager`) e il simulatore Samba (`InMemorySmbBackend`): cio' che P4, P5,
 * P7 e P8 hanno provato sul NAS finto vale anche qui (P1).
 */
class SmbNasMediaContractTest : NasMediaReaderContract() {

  override fun TestScope.fixture(): MediaNasFixture = SmbFixture(this)

  private class SmbFixture(scope: TestScope) : MediaNasFixture {
    private var now = 1_791_936_000_000L

    val backend = InMemorySmbBackend(nasTime = { now }).apply {
      addUser(USER, GOOD_PASSWORD)
      addShare("alessio", mapOf(USER to SimAccess.READ_WRITE))
    }

    val harness = Harness(scope.backgroundScope, scope.virtualClock(), backend = backend)

    override val reader: NasMediaReader = harness.manager.mediaReader()
    override val serverCopy: ServerCopy = SmbServerSideCopy(harness.manager)

    override var nasNow: Long
      get() = now
      set(value) {
        now = value
      }

    override val bytesRead: Long get() = backend.bytesServed.get()

    override fun addShare(name: String) = backend.addShare(name, mapOf(USER to SimAccess.READ_WRITE))

    override fun putFile(share: String, path: String, content: NasContent, modifiedAt: Long, attributes: Int): Long =
      backend.putContent(share, path, content, modifiedAt, attributes)

    override fun putDir(share: String, path: String, attributes: Int) = backend.putDir(share, path, attributes)

    override fun rename(share: String, from: String, to: String) = backend.rename(share, from, to)

    override fun lock(share: String, path: String) = backend.lock(share, path)

    override fun unlock(share: String, path: String) = backend.unlock(share, path)

    override fun exists(share: String, path: String): Boolean = backend.exists(share, path)

    /** La connessione cade all'apertura della cartella, anche ai due nuovi tentativi della sessione. */
    override fun failListCall(n: Int) {
      backend.faults = FaultPlan().apply {
        for (attempt in 0..harness.manager.config.maxRetries) at(n + attempt, FaultPhase.BEFORE, SimFault.DropConnection, SimOp.OPEN_DIRECTORY)
      }
    }

    override fun clearFaults() {
      backend.faults = null
    }

    /** Una connessione caduta dopo i nuovi tentativi: "nessuna risposta" (tabella §3.4). */
    override val networkError: NasError = NasError.Timeout
  }
}
