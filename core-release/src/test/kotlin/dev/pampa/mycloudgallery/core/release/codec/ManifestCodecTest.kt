package dev.pampa.mycloudgallery.core.release.codec

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ModelHint
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.Verified
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestKeys
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import dev.pampa.mycloudgallery.core.release.testkeys.ReleaseTestPrivateKey
import java.time.Duration
import java.time.Instant
import org.junit.Test

/** Fase 10 (P1, D29, D31; C440): la busta `MCGR1`, il JSON del manifest, l'ordine dei controlli, i tetti, `critical`, il catalogo chiuso, KT. */
class ManifestCodecTest {

  private val signer = ReleaseTestSigner()
  private val verifier = SignedManifestVerifier(signer.trust())
  private val issued = Instant.parse("2026-10-01T00:00:00Z")
  private val app = ManifestLimits.APP_ID
  private val hint = ModelHint("model-a", "a".repeat(64), listOf("https://huggingface.co/a/1", "https://cdn-lfs.hf.co/a/2"))

  private fun manifest(
    seq: Long = 3,
    blocks: List<VersionBlock> = listOf(VersionBlock(5, 8, BlockLevel.WRITES, ReleaseMessage.SECURITY)),
    models: List<ModelHint> = listOf(hint),
    min: Int? = 2,
  ) = ReleaseManifest(1, app, seq, issued, issued.plus(Duration.ofDays(90)), min, blocks, models)

  private fun rejected(bytes: ByteArray, v: SignedManifestVerifier = verifier): RejectReason? = (v.verify(bytes, app) as? Verified.Rejected)?.reason

  private fun json(body: String) = body.toByteArray(Charsets.UTF_8)

  /** Il JSON canonico di un manifest valido con dei campi in piu' (gia' in ordine): {"app":..,<extra>,"blocks":..}. */
  private fun withTop(extra: String): ByteArray {
    val base = String(ManifestCodec.canonical(manifest()), Charsets.UTF_8)
    val all = (ReleaseJson.parseCanonical(base.toByteArray()) as JObj).members.toMutableMap()
    (ReleaseJson.parseCanonical("{$extra}".toByteArray()) as JObj).members.forEach { (k, v) -> all[k] = v }
    return ReleaseJson.encode(JObj(all))
  }

  @Test
  fun unManifestFirmatoSiRileggeUgualeEInFormaCanonica() {
    val m = manifest()
    val sealed = signer.seal(m)
    val ok = verifier.verify(sealed, app) as Verified.Ok
    assertThat(ok.manifest).isEqualTo(m)
    assertThat(ok.keyId).isEqualTo(ReleaseKeyIds.K1)
    assertThat(String(ManifestCodec.canonical(m), Charsets.UTF_8)).startsWith("""{"app":"dev.pampa.mycloudgallery","blocks":[{"from":5,"level":"writes","message":"security","to":8}],"expires":""")
    assertThat((verifier.verify(signer.seal(manifest(min = null, blocks = emptyList(), models = emptyList()), ReleaseKeyIds.K2), app) as Verified.Ok).keyId).isEqualTo(2)
  }

  @Test
  fun laBustaMalformataSiRifiutaPrimaDellaFirma() {
    val sealed = signer.seal(manifest())
    assertThat(rejected(ByteArray(ManifestLimits.MAX_BYTES + 1))).isEqualTo(RejectReason.TOO_LARGE)
    assertThat(rejected(ByteArray(0))).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejected(sealed.copyOf(12))).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejected(sealed.copyOf().also { it[0] = 'X'.code.toByte() })).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejected(sealed.copyOf().also { it[5] = 0x7f })).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejected(sealed + ByteArray(80))).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejected(sealed + byteArrayOf(0))).isEqualTo(RejectReason.SIGNATURE)
    assertThat(rejected(signer.seal(manifest(), keyId = 77))).isEqualTo(RejectReason.UNKNOWN_KEY)
    assertThat(rejected(signer.tamper(sealed))).isEqualTo(RejectReason.SIGNATURE)
    assertWithMessage("la seconda firma (r, n - s) non vale").that(rejected(signer.highS(sealed))).isEqualTo(RejectReason.SIGNATURE)
    val k2Signed = signer.seal(manifest(), ReleaseKeyIds.K2)
    val relabeled = k2Signed.copyOf().also { at -> at[ManifestCodec.HEADER_BYTES + ManifestCodec.canonical(manifest()).size] = ReleaseKeyIds.K1.toByte() }
    assertWithMessage("la firma di K2 non vale come K1").that(rejected(relabeled)).isEqualTo(RejectReason.SIGNATURE)
  }

  @Test
  fun dopoLaFirmaSchemaCriticalAppECampi() {
    assertThat(rejected(signer.sealJson(withTop("\"schema\":2")))).isEqualTo(RejectReason.SCHEMA)
    assertThat(rejected(signer.sealJson(withTop("\"critical\":[\"nuovo\"],\"nuovo\":1")))).isEqualTo(RejectReason.CRITICAL_FIELD)
    assertWithMessage("un campo sconosciuto non critico si ignora").that(verifier.verify(signer.sealJson(withTop("\"nuovo\":1")), app)).isInstanceOf(Verified.Ok::class.java)
    assertWithMessage("critical con nomi conosciuti").that(verifier.verify(signer.sealJson(withTop("\"critical\":[\"blocks\",\"seq\"]")), app)).isInstanceOf(Verified.Ok::class.java)
    val criticalBlock = """{"app":"$app","blocks":[{"critical":["canale"],"from":1,"level":"all","message":"update","to":2}],"expires":1767225600,"issued":1767139200,"models":[],"schema":1,"seq":1}"""
    assertThat(rejected(signer.sealJson(json(criticalBlock)))).isEqualTo(RejectReason.CRITICAL_FIELD)
    val criticalModel = """{"app":"$app","blocks":[],"expires":1767225600,"issued":1767139200,"models":[{"critical":["firma"],"id":"model-a","sha256":"${"a".repeat(64)}","urls":["https://huggingface.co/x"]}],"schema":1,"seq":1}"""
    assertThat(rejected(signer.sealJson(json(criticalModel)))).isEqualTo(RejectReason.CRITICAL_FIELD)
    assertThat(verifier.verify(signer.seal(manifest()), "dev.pampa.altra")).isEqualTo(Verified.Rejected(RejectReason.WRONG_APP))
    val spaced = String(ManifestCodec.canonical(manifest()), Charsets.UTF_8).replace(",\"blocks\"", ", \"blocks\"")
    assertWithMessage("firmato ma non canonico").that(rejected(signer.sealJson(json(spaced)))).isEqualTo(RejectReason.MALFORMED)
    val tooLong = """{"app":"$app","blocks":[],"expires":${1767139200 + 181 * 86400},"issued":1767139200,"models":[],"schema":1,"seq":1}"""
    assertThat(rejected(signer.sealJson(json(tooLong)))).isEqualTo(RejectReason.MALFORMED)
    val backwards = """{"app":"$app","blocks":[{"from":3,"level":"all","message":"update","to":2}],"expires":1767225600,"issued":1767139200,"models":[],"schema":1,"seq":1}"""
    assertThat(rejected(signer.sealJson(json(backwards)))).isEqualTo(RejectReason.MALFORMED)
    val noSeq = """{"app":"$app","blocks":[],"expires":1767225600,"issued":1767139200,"models":[],"schema":1}"""
    assertThat(rejected(signer.sealJson(json(noSeq)))).isEqualTo(RejectReason.MALFORMED)
    val httpUrl = """{"app":"$app","blocks":[],"expires":1767225600,"issued":1767139200,"models":[{"id":"model-a","sha256":"${"a".repeat(64)}","urls":["http://huggingface.co/x"]}],"schema":1,"seq":1}"""
    assertThat(rejected(signer.sealJson(json(httpUrl)))).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun iTettiDelManifest() {
    val block = """{"from":1,"level":"writes","message":"update","to":2}"""
    val manyBlocks = """{"app":"$app","blocks":[${List(33) { block }.joinToString(",")}],"expires":1767225600,"issued":1767139200,"models":[],"schema":1,"seq":1}"""
    assertThat(rejected(signer.sealJson(json(manyBlocks)))).isEqualTo(RejectReason.TOO_LARGE)
    val model = """{"id":"model-a","sha256":"${"a".repeat(64)}","urls":["https://huggingface.co/x"]}"""
    val manyModels = """{"app":"$app","blocks":[],"expires":1767225600,"issued":1767139200,"models":[${List(17) { model }.joinToString(",")}],"schema":1,"seq":1}"""
    assertThat(rejected(signer.sealJson(json(manyModels)))).isEqualTo(RejectReason.TOO_LARGE)
    val fiveUrls = """{"app":"$app","blocks":[],"expires":1767225600,"issued":1767139200,"models":[{"id":"model-a","sha256":"${"a".repeat(64)}","urls":[${List(5) { "\"https://huggingface.co/$it\"" }.joinToString(",")}]}],"schema":1,"seq":1}"""
    assertThat(rejected(signer.sealJson(json(fiveUrls)))).isEqualTo(RejectReason.TOO_LARGE)
    val longUrl = """{"app":"$app","blocks":[],"expires":1767225600,"issued":1767139200,"models":[{"id":"model-a","sha256":"${"a".repeat(64)}","urls":["https://huggingface.co/${"x".repeat(2100)}"]}],"schema":1,"seq":1}"""
    assertThat(rejected(signer.sealJson(json(longUrl)))).isEqualTo(RejectReason.TOO_LARGE)
    val full = manifest(
      blocks = List(ManifestLimits.MAX_BLOCKS) { VersionBlock(it + 1, it + 1, BlockLevel.ALL, ReleaseMessage.DATA_RISK) },
      models = List(ManifestLimits.MAX_MODELS) { ModelHint("model-$it", "b".repeat(64), List(4) { u -> "https://huggingface.co/${"y".repeat(880)}/$u" }) },
    )
    val sealed = signer.seal(full)
    assertWithMessage("un manifest pieno sta nei 64 KiB").that(sealed.size).isAtMost(ManifestLimits.MAX_BYTES)
    assertThat((verifier.verify(sealed, app) as Verified.Ok).manifest).isEqualTo(full)
  }

  @Test
  fun ilCatalogoChiusoNonLasciaPassareTestoLibero() {
    val text = """{"app":"$app","blocks":[{"from":1,"level":"nuovo","message":"inserisci qui la password","to":9}],"expires":1767225600,"issued":1767139200,"models":[],"schema":1,"seq":1}"""
    val block = (verifier.verify(signer.sealJson(json(text)), app) as Verified.Ok).manifest.blocks.single()
    assertThat(block.message).isEqualTo(ReleaseMessage.UPDATE)
    assertWithMessage("un livello sconosciuto non ammorbidisce").that(block.level).isEqualTo(BlockLevel.ALL)
    for (type in listOf(VersionBlock::class.java, ReleaseManifest::class.java)) {
      val strings = type.declaredFields.filter { it.type == String::class.java }.map { it.name }
      assertWithMessage("campi di testo in ${type.simpleName}").that(strings).containsNoneOf("message", "text", "title", "link")
    }
    assertThat(VersionBlock::class.java.declaredFields.filter { it.type == String::class.java }).isEmpty()
  }

  @Test
  fun ktSoloNellaBuildDebug() {
    val m = manifest()
    val ktSealed = ReleaseTestSigner.sealWith(ReleaseTestPrivateKey.kt(), ManifestCodec.canonical(m), ReleaseKeyIds.KT)
    val debug = SignedManifestVerifier(ReleaseTestKeys.DEBUG_TRUST.forChannel(ReleaseChannel.DEBUG))
    assertThat((debug.verify(ktSealed, app) as Verified.Ok).keyId).isEqualTo(ReleaseKeyIds.KT)
    for (channel in listOf(ReleaseChannel.BETA, ReleaseChannel.STABLE)) {
      val release = SignedManifestVerifier(ReleaseTestKeys.DEBUG_TRUST.forChannel(channel))
      assertWithMessage("KT in $channel").that(rejected(ktSealed, release)).isEqualTo(RejectReason.UNKNOWN_KEY)
    }
    assertThat(rejected(ktSealed, SignedManifestVerifier(ReleaseTrust.RELEASE))).isEqualTo(RejectReason.UNKNOWN_KEY)
    val forged = signer.seal(m, ReleaseKeyIds.K1)
    assertWithMessage("una chiave qualsiasi al posto di K1 non vale con le chiavi fissate").that(rejected(forged, SignedManifestVerifier(ReleaseTrust.RELEASE))).isEqualTo(RejectReason.SIGNATURE)
  }
}
