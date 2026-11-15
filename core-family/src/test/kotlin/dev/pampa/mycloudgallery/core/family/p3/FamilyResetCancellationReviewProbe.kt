package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.lifecycle.NasFamilyReset
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.NasShareScope
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Probe sul percorso reale: trasporto cancellato prima della prima scrittura del reset. */
class FamilyResetCancellationReviewProbe {
  @Test fun callerCancellationDuringFoundationFinishesTheLocalInstallAndKeepsTheCode() = runTest {
    val world = FamilyWorld()
    val admin = world.admin
    admin.engine.ensureLoaded()
    val before = admin.engine.state!!.registry.familyId
    lateinit var operation: Job
    val transport = object : NasSessionManager by admin.sessions {
      override suspend fun <T> withShare(share: String, purpose: Purpose, block: suspend (NasShareScope) -> T): NasResult<T> =
        admin.sessions.withShare(share, purpose) { scope ->
          if (share == admin.engine.profile()!!.commonShare) operation.cancel()
          block(scope)
        }
    }
    val reset = NasFamilyReset(admin.engine, transport, admin.keys, admin.vault, world.clock)
    operation = launch(start = CoroutineStart.LAZY) { reset.reset(confirmedTwice = true) }
    operation.start()
    operation.join()
    assertThat(operation.isCancelled).isTrue()
    assertThat(admin.engine.state!!.registry.familyId).isNotEqualTo(before)
    assertThat(admin.vault.key!!.publicKey.fingerprint).isEqualTo(admin.engine.state!!.trustedFamilyKey.fingerprint)
    assertThat(reset.recoveryCodeForDisplay()).isNotNull()
  }

  @Test fun cancellationBeforeTheFirstNasWriteRestoresThePreviousFamilyKey() = runTest {
    val world = FamilyWorld()
    val admin = world.admin
    admin.engine.ensureLoaded()
    val beforeKey = checkNotNull(admin.vault.key).publicKey.fingerprint
    val beforeFamily = checkNotNull(admin.engine.state).registry.familyId
    val cancelledTransport = object : NasSessionManager by admin.sessions {
      override suspend fun <T> withShare(share: String, purpose: Purpose, block: suspend (NasShareScope) -> T): NasResult<T> {
        throw CancellationException("cancelled while waiting for NAS")
      }
    }
    val reset = NasFamilyReset(admin.engine, cancelledTransport, admin.keys, admin.vault, world.clock)
    var cancelled = false
    try { reset.reset(confirmedTwice = true) } catch (_: CancellationException) { cancelled = true }
    assertThat(cancelled).isTrue()
    assertThat(admin.engine.state!!.registry.familyId).isEqualTo(beforeFamily)
    assertThat(admin.vault.key!!.publicKey.fingerprint).isEqualTo(beforeKey)
  }
}
