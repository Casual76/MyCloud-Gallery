package dev.pampa.mycloudgallery.core.release.state

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.codec.ManifestCodec
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestRead
import dev.pampa.mycloudgallery.core.release.contract.ModelHint
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import dev.pampa.mycloudgallery.core.release.testing.FakeManifestSource
import dev.pampa.mycloudgallery.core.release.testing.FakeReleaseClock
import dev.pampa.mycloudgallery.core.release.testing.InMemoryReleaseStateStore
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestKeys
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import dev.pampa.mycloudgallery.core.release.testkeys.ReleaseTestPrivateKey
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 10 (P1): la fabbrica per :app (KT solo nella build debug, chiavi fissate di base), la lettura della sorgente nello stato (`offerRead`:
 * assente e NAS irraggiungibile senza effetto, troppo grande contato) e la **scadenza con l'ora del NAS** (E-REL9, `NasFirstReleaseClock`).
 */
class ReleaseStatesTest {

  private val issued = Instant.parse("2026-10-01T00:00:00Z")
  private val sha = "d".repeat(64)

  private fun manifest(seq: Long = 1, days: Long = 30) = ReleaseManifest(
    1, ManifestLimits.APP_ID, seq, issued, issued.plus(Duration.ofDays(days)), null,
    listOf(VersionBlock(7, 7, BlockLevel.WRITES, ReleaseMessage.SECURITY)), listOf(ModelHint("model-d", sha, listOf("https://huggingface.co/d"))),
  )

  private fun version(channel: ReleaseChannel) = AppVersion(7, "1.0.7", channel)

  @Test
  fun laFabbricaFidaKtSoloNellaBuildDebug() = runTest {
    val kt = ReleaseTestSigner.sealWith(ReleaseTestPrivateKey.kt(), ManifestCodec.canonical(manifest()), ReleaseKeyIds.KT)
    val clock = FakeReleaseClock(issued.plusSeconds(60))
    for (channel in ReleaseChannel.entries) {
      val state = ReleaseStates.create(InMemoryReleaseStateStore(), version(channel), clock, ReleaseTestKeys.DEBUG_TRUST).also { it.load() }
      val expected = if (channel == ReleaseChannel.DEBUG) OfferResult.APPLIED else OfferResult.REJECTED
      assertWithMessage("KT con il canale $channel").that(state.offer(kt, ManifestOrigin.NAS)).isEqualTo(expected)
    }
    val plain = ReleaseStates.create(InMemoryReleaseStateStore(), version(ReleaseChannel.DEBUG), clock).also { it.load() }
    assertThat(plain.offer(kt, ManifestOrigin.NAS)).isEqualTo(OfferResult.REJECTED)
    assertThat(plain.diag.value.rejected).containsExactly(RejectReason.UNKNOWN_KEY, 1)
    val forged = ReleaseTestSigner().seal(manifest(), ReleaseKeyIds.K1)
    assertWithMessage("una chiave che non e' K1 nel posto di K1").that(plain.offer(forged, ManifestOrigin.NAS)).isEqualTo(OfferResult.REJECTED)
    assertThat(plain.status.value).isEqualTo(ReleaseStatus.Normal)
  }

  @Test
  fun laLetturaDellaSorgenteEntraNelloStato() = runTest {
    val clock = FakeReleaseClock(issued.plusSeconds(60))
    val state = ReleaseStates.create(InMemoryReleaseStateStore(), version(ReleaseChannel.DEBUG), clock, ReleaseTestKeys.DEBUG_TRUST).also { it.load() }
    val source = FakeManifestSource()
    assertThat(state.offerRead(source.read(), ManifestOrigin.NAS)).isNull()
    source.next = ManifestRead.Unavailable
    assertThat(state.offerRead(source.read(), ManifestOrigin.NAS)).isNull()
    source.next = ManifestRead.Found(ByteArray(ManifestLimits.MAX_BYTES + 10))
    assertThat(state.offerRead(source.read(), ManifestOrigin.NAS)).isEqualTo(OfferResult.REJECTED)
    assertThat(state.diag.value.rejected).containsExactly(RejectReason.TOO_LARGE, 1)
    source.next = ManifestRead.Found(ReleaseTestSigner.sealWith(ReleaseTestPrivateKey.kt(), ManifestCodec.canonical(manifest()), ReleaseKeyIds.KT))
    assertThat(state.offerRead(source.read(), ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    assertThat(state.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
    assertThat(source.reads).isEqualTo(4)
  }

  @Test
  fun laScadenzaSiGiudicaConLOraDelNas() = runTest {
    val expires = issued.plus(Duration.ofDays(30))
    var nas: Instant? = expires.plus(Duration.ofDays(1))
    var phone = expires.minus(Duration.ofDays(10))
    val clock = NasFirstReleaseClock(nasNow = { nas }, phoneNow = { phone })
    val signer = ReleaseTestSigner()
    val state = DefaultReleaseState(InMemoryReleaseStateStore(), version(ReleaseChannel.STABLE), clock, dev.pampa.mycloudgallery.core.release.codec.SignedManifestVerifier(signer.trust()))
    state.load()
    assertWithMessage("il NAS dice scaduto, il telefono indietro no").that(state.offer(signer.seal(manifest()), ManifestOrigin.NAS)).isEqualTo(OfferResult.EXPIRED_KEPT_BLOCKS)
    assertThat(state.extraUrls("model-d", sha)).isEmpty()
    nas = null
    assertWithMessage("senza NAS vale l'ultima ora del NAS vista, se piu' tarda del telefono").that(state.extraUrls("model-d", sha)).isEmpty()
    assertThat(clock.bestNow()).isEqualTo(expires.plus(Duration.ofDays(1)))
    phone = expires.plus(Duration.ofDays(5))
    assertThat(clock.bestNow()).isEqualTo(phone)
    nas = expires.minus(Duration.ofDays(1))
    phone = expires.plus(Duration.ofDays(50))
    assertWithMessage("il NAS dice valido, il telefono avanti no").that(state.offer(signer.seal(manifest(seq = 2)), ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    assertThat(state.extraUrls("model-d", sha)).containsExactly("https://huggingface.co/d")
    assertThat(NasFirstReleaseClock({ error("NAS rotto") }, { phone }).bestNow()).isEqualTo(phone)
  }
}
