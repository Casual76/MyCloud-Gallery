package dev.pampa.mycloudgallery.core.release.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.testing.FakeReleaseClock
import dev.pampa.mycloudgallery.core.release.testing.FragileReleaseStore
import dev.pampa.mycloudgallery.core.release.testing.InMemoryReleaseState
import dev.pampa.mycloudgallery.core.release.testing.InMemoryReleaseStateStore
import dev.pampa.mycloudgallery.core.release.testing.ReleaseSealer
import dev.pampa.mycloudgallery.core.release.testing.SyntheticManifests
import dev.pampa.mycloudgallery.core.release.testing.TableManifestVerifier
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto dello stato del manifest (Fase 10, P0; `docs/fase10-progetto.md` D29, D30, E-REL1-E-REL5, E-REL9, §5.2). Gira sul
 * riferimento in memoria e, da P1, sullo stato vero due volte: con il verificatore a tabella e il deposito in memoria, e con la firma vera
 * (`ReleaseTestSigner`, `SignedManifestVerifier`) e il deposito su file che muore fra il temporaneo e la rinomina
 * (`DefaultReleaseStateContractTest`). P1 ha solo allargato il banco ([ReleaseSealer], [FragileReleaseStore]): le prove sono quelle di P0.
 */
abstract class ReleaseStateContract {

  interface Rig {
    val verifier: ReleaseSealer
    val store: FragileReleaseStore
    val clock: FakeReleaseClock

    /** Lo stato per la versione [code] sopra lo stesso deposito (una "riapertura" dopo la morte o dopo un aggiornamento dell'app). */
    fun open(code: Int = CODE): ReleaseState
  }

  protected abstract fun rig(): Rig

  companion object {
    const val CODE: Int = 7
    val ISSUED: Instant = Instant.parse("2026-10-01T00:00:00Z")
  }

  private fun manifest(
    seq: Long,
    blocks: List<VersionBlock> = emptyList(),
    min: Int? = null,
    models: List<ModelHint> = emptyList(),
    issued: Instant = ISSUED,
    days: Long = 90,
    app: String = ManifestLimits.APP_ID,
  ) = ReleaseManifest(ManifestLimits.SCHEMA, app, seq, issued, issued.plus(Duration.ofDays(days)), min, blocks, models)

  private val writes = VersionBlock(5, 8, BlockLevel.WRITES, ReleaseMessage.SECURITY)
  private val all = VersionBlock(7, 7, BlockLevel.ALL, ReleaseMessage.DATA_RISK)

  @Test
  fun senzaManifestNessunEffetto() = runTest {
    val state = rig().open()
    state.load()
    assertThat(state.status.value).isEqualTo(ReleaseStatus.Normal)
    assertThat(state.extraUrls("model-a", SyntheticManifests.SHA_A)).isEmpty()
  }

  @Test
  fun bloccoScritturaFermoEVersioneMinima() = runTest {
    val rig = rig()
    val state = rig.open().also { it.load() }
    assertThat(state.offer(rig.verifier.seal(manifest(1, listOf(writes))), ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    assertThat(state.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
    state.offer(rig.verifier.seal(manifest(2, listOf(writes, all))), ManifestOrigin.NAS)
    assertThat(state.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
    state.offer(rig.verifier.seal(manifest(3, min = 8)), ManifestOrigin.NAS)
    assertThat(state.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.UPDATE))
    assertWithMessage("un manifest piu' nuovo toglie il blocco").that(state.offer(rig.verifier.seal(manifest(4)), ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    assertThat(state.status.value).isEqualTo(ReleaseStatus.Normal)
  }

  @Test
  fun unaBustaRifiutataNonCambiaNienteESiConta() = runTest {
    val rig = rig()
    val state = rig.open().also { it.load() }
    val good = rig.verifier.seal(manifest(1, listOf(writes)))
    state.offer(good, ManifestOrigin.NAS)
    val attempts = listOf(
      rig.verifier.tamper(rig.verifier.seal(manifest(9))) to RejectReason.SIGNATURE,
      rig.verifier.seal(manifest(9), keyId = 77) to RejectReason.UNKNOWN_KEY,
      rig.verifier.seal(manifest(9, app = "dev.pampa.altra")) to RejectReason.WRONG_APP,
      "spazzatura".toByteArray() to RejectReason.MALFORMED,
      ByteArray(ManifestLimits.MAX_BYTES + 1) to RejectReason.TOO_LARGE,
    )
    for ((bytes, reason) in attempts) {
      assertWithMessage(reason.code).that(state.offer(bytes, ManifestOrigin.NAS)).isEqualTo(OfferResult.REJECTED)
      assertThat(state.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
    }
    assertThat(state.diag.value.rejected.keys).containsExactlyElementsIn(attempts.map { it.second })
    assertThat(state.diag.value.seq).isEqualTo(1)
  }

  @Test
  fun unManifestRigiocatoNonToglieIlBlocco() = runTest {
    val rig = rig()
    val state = rig.open().also { it.load() }
    val old = rig.verifier.seal(manifest(1))
    state.offer(rig.verifier.seal(manifest(2, listOf(all))), ManifestOrigin.NAS)
    assertThat(state.offer(old, ManifestOrigin.NAS)).isEqualTo(OfferResult.ROLLBACK)
    assertThat(state.offer(rig.verifier.seal(manifest(2)), ManifestOrigin.NAS)).isEqualTo(OfferResult.SAME)
    assertThat(state.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
    val reopened = rig.open().also { it.load() }
    assertThat(reopened.offer(old, ManifestOrigin.NAS)).isEqualTo(OfferResult.ROLLBACK)
    assertThat(reopened.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
  }

  @Test
  fun scadenzaConLOraDelNas() = runTest {
    val rig = rig()
    val state = rig.open().also { it.load() }
    val hint = ModelHint("model-a", SyntheticManifests.SHA_A, listOf("https://huggingface.co/a/1"))
    state.offer(rig.verifier.seal(manifest(1, listOf(writes), models = listOf(hint), days = 10)), ManifestOrigin.NAS)
    rig.clock.now = ISSUED.plus(Duration.ofDays(5))
    assertThat(state.extraUrls("model-a", SyntheticManifests.SHA_A)).containsExactly("https://huggingface.co/a/1")
    assertThat(state.extraUrls("model-a", SyntheticManifests.SHA_B)).isEmpty()
    assertThat(state.extraUrls("model-b", SyntheticManifests.SHA_A)).isEmpty()
    rig.clock.now = ISSUED.plus(Duration.ofDays(11))
    assertWithMessage("E-REL4: gli indirizzi decadono").that(state.extraUrls("model-a", SyntheticManifests.SHA_A)).isEmpty()
    assertWithMessage("E-REL4: i blocchi no").that(state.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
    val expiredLifting = rig.verifier.seal(manifest(2, issued = ISSUED, days = 10))
    assertThat(state.offer(expiredLifting, ManifestOrigin.NAS)).isEqualTo(OfferResult.EXPIRED_KEPT_BLOCKS)
    assertWithMessage("scaduto: solo piu' severo").that(state.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
    state.offer(rig.verifier.seal(manifest(3, listOf(all), issued = ISSUED, days = 10)), ManifestOrigin.NAS)
    assertThat(state.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
  }

  @Test
  fun lAggiornamentoDellAppRicalcolaEIlSalvataggioMortoNonPerdeNiente() = runTest {
    val rig = rig()
    val state = rig.open().also { it.load() }
    state.offer(rig.verifier.seal(manifest(1, listOf(all))), ManifestOrigin.NAS)
    val updated = rig.open(CODE + 1).also { it.load() }
    assertWithMessage("versione nuova fuori dal blocco").that(updated.status.value).isEqualTo(ReleaseStatus.Normal)
    val same = rig.open().also { it.load() }
    assertThat(same.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
    rig.store.dieDuringNextWrite()
    val lifting = rig.verifier.seal(manifest(2))
    assertThat(runCatching { same.offer(lifting, ManifestOrigin.NAS) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    assertWithMessage("un blocco non si toglie se il salvataggio muore").that(same.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
    assertThat(rig.open().also { it.load() }.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
    rig.store.corrupt()
    val broken = rig.open().also { it.load() }
    assertWithMessage("un file rovinato si legge come assente").that(broken.status.value).isEqualTo(ReleaseStatus.Normal)
    rig.store.dieDuringNextWrite()
    val stricter = rig.verifier.seal(manifest(3, listOf(all)))
    assertThat(runCatching { broken.offer(stricter, ManifestOrigin.NAS) }.isSuccess).isFalse()
    assertWithMessage("piu' severo vale in memoria anche se il salvataggio muore").that(broken.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
  }

  /** P1: "scaduto = solo piu' severo" vale rispetto allo stato in vigore, anche quello che vale solo in memoria dopo un salvataggio morto. */
  @Test
  fun unoScadutoDopoUnSalvataggioMortoNonAmmorbidisce() = runTest {
    val rig = rig()
    val state = rig.open().also { it.load() }
    state.offer(rig.verifier.seal(manifest(1, listOf(writes))), ManifestOrigin.NAS)
    rig.store.dieDuringNextWrite()
    assertThat(runCatching { state.offer(rig.verifier.seal(manifest(2, listOf(all))), ManifestOrigin.NAS) }.isFailure).isTrue()
    assertThat(state.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
    rig.clock.now = ISSUED.plus(Duration.ofDays(30))
    assertThat(state.offer(rig.verifier.seal(manifest(3, days = 10)), ManifestOrigin.NAS)).isEqualTo(OfferResult.EXPIRED_KEPT_BLOCKS)
    assertWithMessage("lo scaduto non toglie il blocco in vigore").that(state.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
    assertThat(rig.open().also { it.load() }.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.DATA_RISK))
  }

  @Test
  fun lAzzeramentoDimenticaELaLetturaDopoRimetteIlBlocco() = runTest {
    val rig = rig()
    val state = rig.open().also { it.load() }
    val sealed = rig.verifier.seal(manifest(1, listOf(writes)))
    state.offer(sealed, ManifestOrigin.NAS)
    state.clear()
    assertThat(state.status.value).isEqualTo(ReleaseStatus.Normal)
    assertThat(rig.open().also { it.load() }.status.value).isEqualTo(ReleaseStatus.Normal)
    assertThat(state.offer(sealed, ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    assertThat(state.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
  }

  @Test
  fun proprieta_UnSeqGiaVistoNonCambiaMaiLoStato() = runTest {
    for (seed in 1L..200L) {
      val rig = rig()
      val random = Random(seed)
      val generator = SyntheticManifests(seed)
      val state = rig.open().also { it.load() }
      val offered = mutableListOf<ByteArray>()
      var seq = 0L
      repeat(12) {
        rig.clock.now = ISSUED.plus(Duration.ofDays(random.nextLong(0, 200)))
        val bytes = if (offered.isNotEmpty() && random.nextInt(3) == 0) {
          offered.random(random)
        } else {
          seq += 1 + random.nextInt(2)
          rig.verifier.seal(generator.next(seq, ISSUED.plus(Duration.ofDays(random.nextLong(0, 30))))).also { offered += it }
        }
        val before = state.status.value
        val result = state.offer(bytes, ManifestOrigin.NAS)
        if (result == OfferResult.ROLLBACK || result == OfferResult.SAME) {
          assertWithMessage("seme $seed: un rigioco ha cambiato lo stato").that(state.status.value).isEqualTo(before)
        }
        if (result == OfferResult.EXPIRED_KEPT_BLOCKS) {
          assertWithMessage("seme $seed: uno scaduto ha ammorbidito").that(state.status.value.severity).isAtLeast(before.severity)
        }
      }
    }
  }
}

class InMemoryReleaseStateContractTest : ReleaseStateContract() {
  override fun rig(): Rig = object : Rig {
    override val verifier = TableManifestVerifier()
    override val store = InMemoryReleaseStateStore()
    override val clock = FakeReleaseClock(ISSUED.plus(Duration.ofDays(1)))

    override fun open(code: Int): ReleaseState = InMemoryReleaseState(store, AppVersion(code, "1.0.$code", ReleaseChannel.BETA), clock, verifier)
  }
}
