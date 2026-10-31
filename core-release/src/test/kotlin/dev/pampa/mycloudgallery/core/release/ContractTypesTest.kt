package dev.pampa.mycloudgallery.core.release

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestRead
import dev.pampa.mycloudgallery.core.release.contract.ModelHint
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.PinnedKey
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.StoredRelease
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import dev.pampa.mycloudgallery.core.release.noop.NoManifestSource
import dev.pampa.mycloudgallery.core.release.noop.NormalReleaseState
import dev.pampa.mycloudgallery.core.release.noop.RejectAllManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.Verified
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 10 (P0, D29-D31, Q8 (a)): i tipi del contratto del manifest, i `toString()` senza URL, il catalogo chiuso, i null-object. */
class ContractTypesTest {

  private val issued = Instant.parse("2026-10-01T00:00:00Z")
  private val url = "https://huggingface.co/segreto/percorso"
  private val hint = ModelHint("model-a", "a".repeat(64), listOf(url))

  @Test
  fun iToStringNonPortanoUrlNeContenuto() {
    val manifest = ReleaseManifest(1, ManifestLimits.APP_ID, 4, issued, issued.plus(Duration.ofDays(30)), 3, emptyList(), listOf(hint))
    for (text in listOf(hint.toString(), manifest.toString(), StoredRelease(4, manifest, 1, 7, ReleaseStatus.Normal).toString())) {
      assertThat(text).doesNotContain("huggingface")
      assertThat(text).doesNotContain("segreto")
      assertThat(text).doesNotContain("model-a")
    }
    assertThat(ManifestRead.Found(ByteArray(10)).toString()).isEqualTo("ManifestRead.Found(10 B)")
  }

  @Test
  fun iTettiDelManifest() {
    assertThrows(IllegalArgumentException::class.java) { ReleaseManifest(1, "x", 1, issued, issued.plus(Duration.ofDays(181)), null, emptyList(), emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { ReleaseManifest(1, "x", 1, issued, issued, null, emptyList(), emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { ReleaseManifest(1, "x", 0, issued, issued.plusSeconds(1), null, emptyList(), emptyList()) }
    val block = VersionBlock(1, 1, BlockLevel.WRITES, ReleaseMessage.UPDATE)
    assertThrows(IllegalArgumentException::class.java) {
      ReleaseManifest(1, "x", 1, issued, issued.plusSeconds(1), null, List(ManifestLimits.MAX_BLOCKS + 1) { block }, emptyList())
    }
    assertThrows(IllegalArgumentException::class.java) { VersionBlock(3, 2, BlockLevel.ALL, ReleaseMessage.UPDATE) }
    assertThrows(IllegalArgumentException::class.java) { ModelHint("model-a", "a".repeat(64), listOf("http://huggingface.co/x")) }
    assertThrows(IllegalArgumentException::class.java) { ModelHint("model-a", "a".repeat(64), List(5) { url }) }
    assertThrows(IllegalArgumentException::class.java) { ModelHint("model-a", "A".repeat(64), listOf(url)) }
    assertThat(ManifestLimits.MAX_BYTES).isEqualTo(65_536)
    assertThat(ManifestLimits.MAX_VALIDITY).isEqualTo(Duration.ofDays(180))
  }

  @Test
  fun ilCatalogoChiusoNonPerdeUnBlocco() {
    assertThat(ReleaseMessage.ofCode("inserisci la password")).isEqualTo(ReleaseMessage.UPDATE)
    assertThat(BlockLevel.ofCode("nuovo")).isEqualTo(BlockLevel.ALL)
    assertThat(ReleaseStatus.strictest(ReleaseStatus.Normal, ReleaseStatus.ReadOnly(ReleaseMessage.UPDATE)).severity).isEqualTo(1)
    assertThat(ReleaseStatus.strictest(ReleaseStatus.Stopped(ReleaseMessage.UPDATE), ReleaseStatus.Normal).severity).isEqualTo(2)
  }

  @Test
  fun soloIlNas() {
    assertThat(ManifestOrigin.entries).containsExactly(ManifestOrigin.NAS)
  }

  @Test
  fun leChiaviFissateSiConfrontanoPerContenuto() {
    val a = PinnedKey(1, byteArrayOf(1, 2, 3), "f".repeat(64))
    val spki = byteArrayOf(1, 2, 3)
    val b = PinnedKey(1, spki, "f".repeat(64))
    spki[0] = 9
    assertThat(a).isEqualTo(b)
    assertThat(b.spki[0]).isEqualTo(1.toByte())
    assertThat(a.toString()).isEqualTo("PinnedKey[1, ffffffffffff]")
    assertThrows(IllegalArgumentException::class.java) { PinnedKey(300, spki, "f".repeat(64)) }
  }

  @Test
  fun iNullObjectNonAccettanoNiente() = runTest {
    assertThat(NoManifestSource.read()).isEqualTo(ManifestRead.Absent)
    assertThat(RejectAllManifestVerifier.verify(ByteArray(3), ManifestLimits.APP_ID)).isInstanceOf(Verified.Rejected::class.java)
    assertThat(NormalReleaseState.offer(ByteArray(3), ManifestOrigin.NAS)).isEqualTo(OfferResult.REJECTED)
    assertThat(NormalReleaseState.status.value).isEqualTo(ReleaseStatus.Normal)
    assertThat(NormalReleaseState.extraUrls("model-a", "a".repeat(64))).isEmpty()
  }
}
