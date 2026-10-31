package dev.pampa.mycloudgallery.core.release.state

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.codec.SignedManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.testing.FakeReleaseClock
import dev.pampa.mycloudgallery.core.release.testing.InMemoryReleaseState
import dev.pampa.mycloudgallery.core.release.testing.InMemoryReleaseStateStore
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import dev.pampa.mycloudgallery.core.release.testing.SyntheticManifests
import dev.pampa.mycloudgallery.core.release.testing.TableManifestVerifier
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 10 (P1, D30, §5.2; C441): lo stato vero su storie casuali di manifest. (1) **Parita'** con il riferimento `InMemoryReleaseState` su
 * 2.000 storie (validi, rigiocati, rotti, scaduti, aggiornamenti dell'app, salvataggi che muoiono, file rovinati, azzeramenti): stesso esito,
 * stesso stato, stessa Diagnostica, stessi indirizzi. (2) **Solo piu' severo per la stessa `versionCode`**: lo stato si ammorbidisce solo con
 * un manifest piu' nuovo e non scaduto (`APPLIED`), un aggiornamento dell'app, un azzeramento o un file rovinato; mai per un rigioco, un
 * rifiuto, uno scaduto, un'ora che passa o una riapertura. (3) La **morte del processo a ogni salvataggio**, con firma vera e file veri.
 */
class ReleaseStateHistoriesTest {

  private val issued = Instant.parse("2026-10-01T00:00:00Z")

  private fun version(code: Int) = AppVersion(code, "1.0.$code", ReleaseChannel.STABLE)

  private enum class Step { NEW, OLD, BROKEN, TIME, UPDATE, DEATH, CORRUPT, CLEAR, REOPEN }

  @Test
  fun parita_ESoloPiuSeveroSu2000Storie() = runTest(timeout = 10.minutes) {
    var softenings = 0
    for (seed in 1L..2_000L) {
      val random = Random(seed)
      val generator = SyntheticManifests(seed)
      val table = TableManifestVerifier()
      val clock = FakeReleaseClock(issued)
      val refStore = InMemoryReleaseStateStore()
      val realStore = InMemoryReleaseStateStore()
      var code = 7
      var ref: ReleaseState = InMemoryReleaseState(refStore, version(code), clock, table).also { it.load() }
      var real: ReleaseState = DefaultReleaseState(realStore, version(code), clock, table).also { it.load() }
      val offered = mutableListOf<ByteArray>()
      var seq = 0L
      var memoryAhead = false
      repeat(16) { index ->
        val step = Step.entries[random.nextInt(Step.entries.size)]
        val before = real.status.value
        var results: Pair<OfferResult?, OfferResult?> = null to null
        suspend fun offerBoth(bytes: ByteArray) {
          val a = runCatching { ref.offer(bytes, ManifestOrigin.NAS) }
          val b = runCatching { real.offer(bytes, ManifestOrigin.NAS) }
          assertWithMessage("seme $seed, passo $index: eccezione").that(b.exceptionOrNull()?.javaClass).isEqualTo(a.exceptionOrNull()?.javaClass)
          if (b.isFailure) memoryAhead = true
          if (b.getOrNull() == OfferResult.APPLIED || b.getOrNull() == OfferResult.EXPIRED_KEPT_BLOCKS) memoryAhead = false
          results = a.getOrNull() to b.getOrNull()
        }
        suspend fun reopen(newCode: Int) {
          code = newCode
          ref = InMemoryReleaseState(refStore, version(code), clock, table).also { it.load() }
          real = DefaultReleaseState(realStore, version(code), clock, table).also { it.load() }
          memoryAhead = false
        }
        val wasAhead = memoryAhead
        when (step) {
          Step.NEW, Step.DEATH -> {
            if (step == Step.DEATH) {
              refStore.dieDuringNextWrite()
              realStore.dieDuringNextWrite()
            }
            seq += 1 + random.nextInt(2)
            val sealed = table.seal(generator.next(seq, issued.plus(Duration.ofDays(random.nextLong(0, 60)))))
            offered += sealed
            offerBoth(sealed)
          }
          Step.OLD -> if (offered.isNotEmpty()) offerBoth(offered.random(random))
          Step.BROKEN -> offerBoth(
            when (random.nextInt(4)) {
              0 -> if (offered.isEmpty()) "x".toByteArray() else table.tamper(offered.random(random))
              1 -> table.seal(generator.next(seq + 1, issued), keyId = 77)
              2 -> table.seal(generator.next(seq + 1, issued, app = "dev.pampa.altra"))
              else -> random.nextBytes(random.nextInt(40))
            },
          )
          Step.TIME -> clock.now = clock.now.plus(Duration.ofDays(random.nextLong(0, 90)))
          Step.UPDATE -> reopen(listOf(code - 1, code + 1, code + 2).filter { it >= 1 }.random(random))
          Step.CORRUPT -> {
            refStore.corrupt()
            realStore.corrupt()
            reopen(code)
          }
          Step.CLEAR -> {
            ref.clear()
            real.clear()
          }
          Step.REOPEN -> reopen(code)
        }
        val where = "seme $seed, passo $index ($step)"
        assertWithMessage("$where: esito").that(results.second).isEqualTo(results.first)
        assertWithMessage("$where: stato").that(real.status.value).isEqualTo(ref.status.value)
        assertWithMessage("$where: diagnostica").that(real.diag.value).isEqualTo(ref.diag.value)
        for ((id, sha) in listOf(SyntheticManifests.MODEL_A to SyntheticManifests.SHA_A, SyntheticManifests.MODEL_B to SyntheticManifests.SHA_B)) {
          assertWithMessage("$where: indirizzi").that(real.extraUrls(id, sha)).isEqualTo(ref.extraUrls(id, sha))
        }
        val after = real.status.value
        if (after.severity < before.severity) {
          softenings++
          val allowed = results.second == OfferResult.APPLIED || step in setOf(Step.UPDATE, Step.CLEAR, Step.CORRUPT) ||
            (step == Step.REOPEN && wasAhead)
          assertWithMessage("$where: lo stato si e' ammorbidito da $before a $after").that(allowed).isTrue()
        }
        if (results.second == OfferResult.ROLLBACK || results.second == OfferResult.SAME || results.second == OfferResult.REJECTED ||
          results.second == OfferResult.EXPIRED_KEPT_BLOCKS
        ) {
          assertWithMessage("$where: ${results.second} ha ammorbidito").that(after.severity).isAtLeast(before.severity)
        }
      }
    }
    assertWithMessage("le storie toccano davvero i casi che tolgono un blocco").that(softenings).isGreaterThan(100)
  }

  @Test
  fun laMorteDelProcessoAOgniSalvataggioDelloStato() = runTest(timeout = 10.minutes) {
    val signer = ReleaseTestSigner()
    val verifier = SignedManifestVerifier(signer.trust())
    var deaths = 0
    for (seed in 1L..15L) {
      val random = Random(seed)
      val generator = SyntheticManifests(seed)
      val store = FragileFileStore(FragileFileStore.tempDir())
      val clock = FakeReleaseClock(issued)
      suspend fun open(): ReleaseState = DefaultReleaseState(store, version(7), clock, verifier).also { it.load() }
      var seq = 0L
      repeat(10) {
        clock.now = issued.plus(Duration.ofDays(random.nextLong(0, 200)))
        val beforeSeq = seq
        seq += 1 + random.nextInt(2)
        val bytes = signer.seal(generator.next(seq, issued.plus(Duration.ofDays(random.nextLong(0, 30)))))
        val snapshot = store.snapshot()
        val before = open().status.value
        val clean = open()
        val expectedResult = clean.offer(bytes, ManifestOrigin.NAS)
        val expected = clean.status.value
        val after = store.snapshot()
        assertThat(expectedResult).isIn(listOf(OfferResult.APPLIED, OfferResult.EXPIRED_KEPT_BLOCKS))
        for (point in SavePoint.entries) {
          store.restore(snapshot)
          val dying = open()
          store.dieAt(point)
          val death = runCatching { dying.offer(bytes, ManifestOrigin.NAS) }.exceptionOrNull()
          store.dieAt(null)
          assertWithMessage("seme $seed, $point").that(death).isInstanceOf(SimulatedDeath::class.java)
          deaths++
          assertWithMessage("in memoria vale il piu' severo").that(dying.status.value).isEqualTo(ReleaseStatus.strictest(before, expected))
          val restarted = open()
          val renamed = point == SavePoint.AFTER_RENAME
          assertWithMessage("seme $seed, riavvio dopo $point").that(restarted.status.value).isEqualTo(if (renamed) expected else before)
          assertThat(restarted.diag.value.seq).isEqualTo(if (renamed) seq else beforeSeq)
          assertWithMessage("la lettura dopo rimette lo stato").that(restarted.offer(bytes, ManifestOrigin.NAS))
            .isEqualTo(if (renamed) OfferResult.SAME else expectedResult)
          assertThat(restarted.status.value).isEqualTo(expected)
        }
        store.restore(after)
      }
    }
    assertThat(deaths).isEqualTo(15 * 10 * SavePoint.entries.size)
  }
}
