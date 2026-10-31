package dev.pampa.mycloudgallery.core.release.contract

import dev.pampa.mycloudgallery.core.release.codec.SignedManifestVerifier
import dev.pampa.mycloudgallery.core.release.state.DefaultReleaseState
import dev.pampa.mycloudgallery.core.release.state.FragileFileStore
import dev.pampa.mycloudgallery.core.release.testing.FakeReleaseClock
import dev.pampa.mycloudgallery.core.release.testing.InMemoryReleaseStateStore
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import dev.pampa.mycloudgallery.core.release.testing.TableManifestVerifier
import java.time.Duration

/** Fase 10 (P1): la suite di P0 sullo stato vero con il verificatore a tabella e il deposito in memoria (le regole senza crittografia). */
class DefaultReleaseStateTableContractTest : ReleaseStateContract() {
  override fun rig(): Rig = object : Rig {
    override val verifier = TableManifestVerifier()
    override val store = InMemoryReleaseStateStore()
    override val clock = FakeReleaseClock(ISSUED.plus(Duration.ofDays(1)))

    override fun open(code: Int): ReleaseState =
      DefaultReleaseState(store, AppVersion(code, "1.0.$code", ReleaseChannel.BETA), clock, verifier)
  }
}

/**
 * Fase 10 (P1): la suite di P0 sullo stato vero **intero**: buste `MCGR1` firmate davvero (chiavi P-256 generate nel test negli id di K1, K2
 * e KT; una chiave 77 non fidata), `SignedManifestVerifier` di produzione e il deposito su file che muore fra il temporaneo e la rinomina.
 */
class DefaultReleaseStateContractTest : ReleaseStateContract() {
  override fun rig(): Rig = object : Rig {
    private val signer = ReleaseTestSigner()
    private val trusted = SignedManifestVerifier(signer.trust(ReleaseKeyIds.K1, ReleaseKeyIds.K2, ReleaseKeyIds.KT))
    override val verifier = signer
    override val store = FragileFileStore(FragileFileStore.tempDir())
    override val clock = FakeReleaseClock(ISSUED.plus(Duration.ofDays(1)))

    override fun open(code: Int): ReleaseState =
      DefaultReleaseState(store, AppVersion(code, "1.0.$code", ReleaseChannel.BETA), clock, trusted)
  }
}
