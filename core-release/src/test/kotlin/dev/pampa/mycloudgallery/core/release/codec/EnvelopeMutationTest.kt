package dev.pampa.mycloudgallery.core.release.codec

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.ModelHint
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import dev.pampa.mycloudgallery.core.release.state.DefaultReleaseState
import dev.pampa.mycloudgallery.core.release.testing.FakeReleaseClock
import dev.pampa.mycloudgallery.core.release.testing.InMemoryReleaseStateStore
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il "Fatto quando" di P1 (docs/fase10-progetto.md §4.4, riga P1; C440, E-S7, E-REL2): su **10.000 buste mutate**, **solo** una busta intatta e
 * firmata da K1 o K2 (o da KT nella build debug) cambia lo stato. Lo stato ha gia' un blocco `WRITES` (seq 5); la busta candidata (seq 6) lo
 * toglierebbe e darebbe un indirizzo in piu': ogni mutazione (bit, byte, tagli, aggiunte, inserti, lunghezza, keyId, firme scambiate o di
 * un'altra chiave, la seconda firma `(r, n - s)`, JSON rifirmato da chi non ha la chiave) deve lasciare stato, `seq` e indirizzi come prima.
 * Le chiavi negli id di K1, K2 e KT sono generate nel test (le vere non le tocca nessuno); KT qui e' una chiave del test nel suo id.
 */
class EnvelopeMutationTest {

  private val signer = ReleaseTestSigner()
  private val attacker = ReleaseTestSigner()
  private val issued = Instant.parse("2026-10-01T00:00:00Z")
  private val app = ManifestLimits.APP_ID
  private val sha = "c".repeat(64)

  private fun manifest(seq: Long, blocks: List<VersionBlock>, url: String = "https://huggingface.co/c/$seq") =
    ReleaseManifest(1, app, seq, issued, issued.plus(Duration.ofDays(120)), null, blocks, listOf(ModelHint("model-c", sha, listOf(url))))

  private val blocked = manifest(5, listOf(VersionBlock(7, 7, BlockLevel.WRITES, ReleaseMessage.SECURITY)))
  private val lifting = manifest(6, emptyList(), url = "https://huggingface.co/c/nuovo")

  private suspend fun state(verifier: ManifestVerifier, channel: ReleaseChannel): ReleaseState =
    DefaultReleaseState(InMemoryReleaseStateStore(), AppVersion(7, "1.0.7", channel), FakeReleaseClock(issued.plus(Duration.ofDays(2))), verifier).also {
      it.load()
      assertThat(it.offer(signer.seal(blocked, ReleaseKeyIds.K1), ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    }

  private fun mutate(original: ByteArray, others: List<ByteArray>, random: Random): ByteArray {
    val b = original.copyOf()
    val jsonEnd = ManifestCodec.HEADER_BYTES + (b.size - ManifestCodec.HEADER_BYTES - 1 - 72).coerceAtLeast(1)
    return when (random.nextInt(14)) {
      0 -> b.also { val i = random.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl random.nextInt(8))).toByte() }
      1 -> b.also { repeat(2 + random.nextInt(7)) { _ -> val i = random.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl random.nextInt(8))).toByte() } }
      2 -> b.also { val i = random.nextInt(it.size); it[i] = random.nextInt(256).toByte() }
      3 -> b.copyOf(random.nextInt(b.size))
      4 -> b + random.nextBytes(1 + random.nextInt(100))
      5 -> random.nextInt(b.size + 1).let { at -> b.copyOfRange(0, at) + byteArrayOf(random.nextInt(256).toByte()) + b.copyOfRange(at, b.size) }
      6 -> random.nextInt(b.size).let { at -> b.copyOfRange(0, at) + b.copyOfRange((at + 1 + random.nextInt(8)).coerceAtMost(b.size), b.size) }
      7 -> b.also { it[5 + random.nextInt(4)] = random.nextInt(256).toByte() }
      8 -> keyIdAt(b).let { at -> b.also { it[at] = listOf(ReleaseKeyIds.K2, ReleaseKeyIds.KT, 0, 77, 255, random.nextInt(256)).random(random).toByte() } }
      9 -> others.random(random).let { other -> b.copyOfRange(0, keyIdAt(b) + 1) + other.copyOfRange(keyIdAt(other) + 1, other.size) }
      10 -> others.random(random).let { other -> other.copyOfRange(0, keyIdAt(other) + 1) + b.copyOfRange(keyIdAt(b) + 1, b.size) }
      11 -> signer.highS(original)
      12 -> attacker.seal(lifting, listOf(ReleaseKeyIds.K1, ReleaseKeyIds.K2, ReleaseKeyIds.KT).random(random))
      else -> {
        val json = ManifestCodec.canonical(lifting).copyOf()
        val i = random.nextInt(json.size)
        json[i] = (json[i].toInt() xor (1 shl random.nextInt(7))).toByte()
        try {
          attacker.sealJson(json, ReleaseKeyIds.K1)
        } catch (e: IllegalArgumentException) {
          b.copyOf(jsonEnd)
        }
      }
    }
  }

  private fun keyIdAt(sealed: ByteArray): Int {
    val length = ((sealed[5].toInt() and 0xff) shl 24) or ((sealed[6].toInt() and 0xff) shl 16) or
      ((sealed[7].toInt() and 0xff) shl 8) or (sealed[8].toInt() and 0xff)
    return ManifestCodec.HEADER_BYTES + length
  }

  @Test
  fun soloUnaBustaIntattaEFirmataCambiaLoStato() = runTest(timeout = 10.minutes) {
    val release = SignedManifestVerifier(signer.trust(ReleaseKeyIds.K1, ReleaseKeyIds.K2).forChannel(ReleaseChannel.STABLE))
    val debug = SignedManifestVerifier(signer.trust(ReleaseKeyIds.K1, ReleaseKeyIds.K2, ReleaseKeyIds.KT).forChannel(ReleaseChannel.DEBUG))
    val stable = state(release, ReleaseChannel.STABLE)
    val dev = state(debug, ReleaseChannel.DEBUG)
    val candidates = listOf(ReleaseKeyIds.K1, ReleaseKeyIds.K2, ReleaseKeyIds.KT).map { signer.seal(lifting, it) }
    val others = listOf(signer.seal(blocked, ReleaseKeyIds.K2), signer.seal(manifest(9, emptyList()), ReleaseKeyIds.KT), attacker.seal(lifting))
    val random = Random(20261031)
    var rejected = 0
    var mutants = 0
    while (mutants < 10_000) {
      val original = candidates.random(random)
      val mutated = mutate(original, others, random)
      if (candidates.any { it.contentEquals(mutated) }) continue
      mutants++
      for (s in listOf(stable, dev)) {
        val result = s.offer(mutated, ManifestOrigin.NAS)
        assertWithMessage("mutazione $mutants: $result").that(result).isNotIn(listOf(OfferResult.APPLIED, OfferResult.EXPIRED_KEPT_BLOCKS))
        if (result == OfferResult.REJECTED) rejected++
        assertThat(s.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
        assertThat(s.diag.value.seq).isEqualTo(5)
        assertThat(s.extraUrls("model-c", sha)).containsExactly("https://huggingface.co/c/5")
      }
    }
    assertWithMessage("le mutazioni sono rifiutate dal verificatore, non ignorate per altro").that(rejected).isEqualTo(20_000)
    assertThat(stable.diag.value.rejected.values.sum()).isEqualTo(10_000)

    // Il controllo positivo: le buste intatte cambiano lo stato. KT solo nella build debug.
    assertThat(stable.offer(candidates[2], ManifestOrigin.NAS)).isEqualTo(OfferResult.REJECTED)
    assertThat(stable.offer(candidates[1], ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    assertThat(stable.status.value).isEqualTo(ReleaseStatus.Normal)
    assertThat(stable.extraUrls("model-c", sha)).containsExactly("https://huggingface.co/c/nuovo")
    assertThat(dev.offer(candidates[2], ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    assertThat(dev.status.value).isEqualTo(ReleaseStatus.Normal)
    val fresh = state(release, ReleaseChannel.STABLE)
    assertThat(fresh.offer(candidates[0], ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
  }
}
