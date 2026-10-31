package dev.pampa.mycloudgallery.core.release.security

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.codec.ManifestCodec
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ModelHint
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust
import dev.pampa.mycloudgallery.core.release.state.ReleaseStates
import dev.pampa.mycloudgallery.core.release.testing.FakeReleaseClock
import dev.pampa.mycloudgallery.core.release.testing.InMemoryReleaseStateStore
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestKeys
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import dev.pampa.mycloudgallery.core.release.testkeys.ReleaseTestPrivateKey
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 10 P11, voce **S7b** della revisione finale (docs/fase10-progetto.md §2.11, D29-D31; C440, C441): "un manifest manomesso viene
 * rifiutato", con i **casi nominati** della prova pratica, uno per uno, sullo stato vero (`ReleaseStates.create`): valido, firma rotta, altra
 * app, troppo grande, `seq` vecchio, campo `critical` ignoto, firmato con una chiave sconosciuta e, sulla **release**, firmato con `KT`.
 * Solo il valido cambia lo stato; un rigioco non toglie un blocco; `WRITES` = sola lettura, `ALL` = fermo. La proprieta' su 10.000 buste
 * mutate e' `EnvelopeMutationTest` (P1).
 *
 * Con `MCG_SECURITY_OUT=<cartella>` scrive anche le stesse buste firmate con la **KT vera di prova** (per l'emulatore e l'S25 della seconda
 * parte: `tools/security/run-all.ps1 -Livello jvm` le mette in `build/security/<data>/manifest/`). Nessuna chiave vera.
 */
class S7bManifestCasesTest {

  private val signer = ReleaseTestSigner()
  private val issued = Instant.parse("2026-10-01T00:00:00Z")
  private val clock = FakeReleaseClock(issued.plus(Duration.ofDays(2)))
  private val sha = "c".repeat(64)

  /** La versione bloccata: 7 nelle prove; per le buste della seconda parte `MCG_SECURITY_VERSION_CODE` (la `versionCode` della build). */
  private var blocked = 7

  private fun manifest(seq: Long, level: BlockLevel? = BlockLevel.WRITES, app: String = ManifestLimits.APP_ID) = ReleaseManifest(
    1, app, seq, issued, issued.plus(Duration.ofDays(120)), null,
    level?.let { listOf(VersionBlock(blocked, blocked, it, ReleaseMessage.SECURITY)) }.orEmpty(),
    listOf(ModelHint("model-c", sha, listOf("https://huggingface.co/c/$seq"))),
  )

  /** Il JSON canonico di [m] con un campo `critical` che nessuna versione conosce (in ordine di chiave: dopo `blocks`). */
  private fun withUnknownCritical(m: ReleaseManifest): ByteArray {
    val text = String(ManifestCodec.canonical(m), Charsets.UTF_8)
    val patched = text.replaceFirst("\"expires\":", "\"critical\":[\"zzz\"],\"expires\":")
    check(patched != text)
    return patched.toByteArray(Charsets.UTF_8)
  }

  private suspend fun state(channel: ReleaseChannel, trust: ReleaseTrust): ReleaseState =
    ReleaseStates.create(InMemoryReleaseStateStore(), AppVersion(7, "1.0.7", channel), clock, trust).also { it.load() }

  /** Le buste dei casi ostili, ognuna con il rifiuto atteso; [seal] firma un JSON con la chiave e l'id dati. */
  private fun hostile(seal: (ByteArray, Int) -> ByteArray, keyId: Int): Map<String, Pair<ByteArray, RejectReason>> {
    val valid = seal(ManifestCodec.canonical(manifest(9, level = null)), keyId)
    val broken = valid.copyOf().also { it[ManifestCodec.HEADER_BYTES + 1] = (it[ManifestCodec.HEADER_BYTES + 1].toInt() xor 1).toByte() }
    return linkedMapOf(
      "firma-rotta" to (broken to RejectReason.SIGNATURE),
      "altra-app" to (seal(ManifestCodec.canonical(manifest(9, level = null, app = "dev.pampa.altra")), keyId) to RejectReason.WRONG_APP),
      "troppo-grande" to ((valid + ByteArray(ManifestLimits.MAX_BYTES)) to RejectReason.TOO_LARGE),
      "critical-ignoto" to (seal(withUnknownCritical(manifest(9, level = null)), keyId) to RejectReason.CRITICAL_FIELD),
      "chiave-sconosciuta" to (seal(ManifestCodec.canonical(manifest(9, level = null)), 77) to RejectReason.UNKNOWN_KEY),
    )
  }

  @Test
  fun soloIlManifestValidoCambiaLoStato() = runTest {
    val release = state(ReleaseChannel.STABLE, signer.trust(ReleaseKeyIds.K1, ReleaseKeyIds.K2))
    // 1. Il valido: blocco WRITES per la 7 = sola lettura.
    assertThat(release.offer(signer.seal(manifest(6), ReleaseKeyIds.K1), ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    assertThat(release.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))

    // 2-6. I casi ostili (anche "togli il blocco" con seq piu' alto): nessuno cambia lo stato, ognuno con il suo motivo.
    val cases = hostile({ json, id -> signer.sealJson(json, id) }, ReleaseKeyIds.K1)
    for ((name, case) in cases) {
      assertWithMessage(name).that(release.offer(case.first, ManifestOrigin.NAS)).isEqualTo(OfferResult.REJECTED)
      assertWithMessage(name).that(release.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
      assertWithMessage(name).that(release.diag.value.seq).isEqualTo(6)
    }
    assertThat(release.diag.value.rejected).isEqualTo(cases.values.groupingBy { it.second }.eachCount())

    // 7. KT sulla release: rifiutata come chiave sconosciuta, anche se la busta e' intatta e il seq e' piu' alto.
    val kt = ReleaseTestSigner.sealWith(ReleaseTestPrivateKey.kt(), ManifestCodec.canonical(manifest(10, level = null)), ReleaseKeyIds.KT)
    val withKt = state(ReleaseChannel.STABLE, ReleaseTestKeys.DEBUG_TRUST)
    assertThat(withKt.offer(kt, ManifestOrigin.NAS)).isEqualTo(OfferResult.REJECTED)
    assertThat(withKt.diag.value.rejected).containsExactly(RejectReason.UNKNOWN_KEY, 1)
    // ...mentre la build debug con la stessa fiducia la applica (il controllo positivo).
    val debug = state(ReleaseChannel.DEBUG, ReleaseTestKeys.DEBUG_TRUST)
    assertThat(debug.offer(kt, ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)

    // 8. Il seq vecchio: un manifest valido e senza blocchi ma con seq 5 non toglie il blocco del 6 (rigioco).
    assertThat(release.offer(signer.seal(manifest(5, level = null), ReleaseKeyIds.K2), ManifestOrigin.NAS)).isEqualTo(OfferResult.ROLLBACK)
    assertThat(release.offer(signer.seal(manifest(6), ReleaseKeyIds.K1), ManifestOrigin.NAS)).isEqualTo(OfferResult.SAME)
    assertThat(release.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))

    // 9. ALL = fermo; poi solo un seq piu' alto e valido lo toglie.
    assertThat(release.offer(signer.seal(manifest(11, BlockLevel.ALL), ReleaseKeyIds.K2), ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    assertThat(release.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.SECURITY))
    assertThat(release.offer(signer.seal(manifest(6), ReleaseKeyIds.K1), ManifestOrigin.NAS)).isEqualTo(OfferResult.ROLLBACK)
    assertThat(release.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.SECURITY))
    assertThat(release.offer(signer.seal(manifest(12, level = null), ReleaseKeyIds.K1), ManifestOrigin.NAS)).isEqualTo(OfferResult.APPLIED)
    assertThat(release.status.value).isEqualTo(ReleaseStatus.Normal)
    println("MCG-SEC S7b casi=${cases.size + 4} rifiutati=${cases.size + 1} applicati=4 rigiochi=2 kt_in_release=rifiutata")
  }

  @Test
  fun scriveLeBusteDiProvaPerLaSecondaParte() {
    val out = System.getenv("MCG_SECURITY_OUT")?.let(::File) ?: return
    val dir = File(out, "manifest").apply { mkdirs() }
    blocked = System.getenv("MCG_SECURITY_VERSION_CODE")?.toIntOrNull() ?: 7
    val key = ReleaseTestPrivateKey.kt()
    val seal = { json: ByteArray, id: Int -> ReleaseTestSigner.sealWith(key, json, id) }
    val files = linkedMapOf(
      "01-valido-blocco-writes.mcgr" to seal(ManifestCodec.canonical(manifest(6)), ReleaseKeyIds.KT),
      "02-valido-blocco-all.mcgr" to seal(ManifestCodec.canonical(manifest(7, BlockLevel.ALL)), ReleaseKeyIds.KT),
      "03-valido-senza-blocchi.mcgr" to seal(ManifestCodec.canonical(manifest(8, level = null)), ReleaseKeyIds.KT),
      "04-seq-vecchio.mcgr" to seal(ManifestCodec.canonical(manifest(5, level = null)), ReleaseKeyIds.KT),
    )
    for ((name, case) in hostile(seal, ReleaseKeyIds.KT)) files["1${files.size - 4}-$name.mcgr"] = case.first
    for ((name, bytes) in files) File(dir, name).writeBytes(bytes)
    File(dir, "LEGGIMI.txt").writeText(
      "Buste di prova firmate con KT (solo debug). 01-04 valide (la 04 ha seq 5: dopo la 01 e' un rigioco); 1x ostili.\n" +
        "Versione bloccata: versionCode $blocked (MCG_SECURITY_VERSION_CODE). Emissione 01/10/2026, validita' 120 giorni. Mai sulle share vere: solo test-comune/mcg-it/<giro>/ o come dice la seconda parte di P11.\n",
    )
    println("MCG-SEC S7b buste_scritte=${files.size}")
  }
}
