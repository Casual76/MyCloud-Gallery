package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La testa `ai` (Fase 6, P2; D6; formato-nas.md §5.5, §10): tipo 3 con `kind` = `ai`, serie propria in `devices/<id>/ai/head/`,
 * **versione 2** della busta, sole classi `vec` e `ocr`. Le classi nella testa sbagliata e le versioni sbagliate per il tipo
 * rifiutano la testa intera.
 */
class AiHeadTest {

  private val family = TestFamily()
  private val tablet = family.approveNewDevice(name = "Tablet", nasUser = "mamma")
  private val vec = AiSegments.encode(AiClass.VEC, listOf(AiFx.key(1) to AiFx.photo(1)))
  private val ocr = AiSegments.encode(AiClass.OCR, listOf(AiFx.key(1) to AiFx.text("Via Roma")))
  private val meta = Segments.wrap("contenuto".toByteArray())

  private fun aiHead(version: Long = 1, segments: List<ClassedSegment> = listOf(vec.classed, ocr.classed).sortedBy { it.ref.hash }) =
    DeviceHead(family.familyId, tablet.deviceId, "Public", HeadKind.AI, version, family.state.registry.version, family.clock.nowMillis(), segments)

  private fun accept(bytes: ByteArray, kind: HeadKind = HeadKind.AI, version: Long = 1) =
    HeadVerifier.accept(bytes, family.state, HeadLocation("Public", tablet.deviceId, kind, version), null, "mamma")

  /** Una testa firmata scritta a mano (come un dispositivo approvato ma compromesso o difettoso): classi e versione a piacere. */
  private fun forged(kind: String, classes: List<Pair<String, SegmentRef>>, formatVersion: Int, signer: Signer = tablet): ByteArray {
    val payload = jsonObject(
      "family" to family.familyId.json(),
      "device" to signer.deviceId.json(),
      "share" to "Public".json(),
      "kind" to kind.json(),
      "version" to 1L.json(),
      "registryVersion" to family.state.registry.version.json(),
      "createdAt" to family.clock.nowMillis().json(),
      "segments" to classes.sortedBy { it.second.hash }.map { (cls, ref) ->
        jsonObject("class" to cls.json(), "hash" to ref.hash.json(), "size" to ref.size.json())
      }.json(),
    )
    return Envelope.seal(DocType.DEVICE_HEAD, payload, signer, formatVersion)
  }

  @Test
  fun laTestaAiSiFirmaConLaVersione2_eQuellaIndexResta1() {
    val ai = aiHead().sign(tablet)
    assertThat(ai[4].toInt()).isEqualTo(TrustFormat.AI_VERSION)
    val index = DeviceHead(family.familyId, tablet.deviceId, "Public", HeadKind.INDEX, 1, family.state.registry.version, family.clock.nowMillis(), listOf(ClassedSegment(SegmentClass.META, meta.second))).sign(tablet)
    assertThat(index[4].toInt()).isEqualTo(TrustFormat.CURRENT_VERSION)
    val accepted = accept(ai).acceptedOrNull()!!
    assertThat(accepted.head.kind).isEqualTo(HeadKind.AI)
    assertThat(accepted.head.segments.map { it.cls }.toSet()).containsExactly(SegmentClass.VEC, SegmentClass.OCR)
    // Lo stesso file messo nella serie `index` non vale (il posto non torna).
    assertThat(accept(ai, kind = HeadKind.INDEX).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
  }

  @Test
  fun classiNellaTestaSbagliataRifiutanoLaTestaIntera() {
    val cases = listOf(
      Triple("ai", listOf("vec" to vec.ref, "thumbS" to meta.second), HeadKind.AI),
      Triple("ai", listOf("meta" to meta.second), HeadKind.AI),
      Triple("ai", listOf("personal" to meta.second), HeadKind.AI),
      Triple("index", listOf("meta" to meta.second, "vec" to vec.ref), HeadKind.INDEX),
      Triple("index", listOf("ocr" to ocr.ref), HeadKind.INDEX),
      Triple("personal", listOf("ocr" to ocr.ref), HeadKind.PERSONAL),
      Triple("ai", listOf("faces" to vec.ref), HeadKind.AI),
    )
    for ((kind, classes, location) in cases) {
      val version = if (kind == "ai") TrustFormat.AI_VERSION else TrustFormat.CURRENT_VERSION
      val reason = HeadVerifier.accept(forged(kind, classes, version), family.state, HeadLocation("Public", tablet.deviceId, location, 1), null, "mamma").rejectionOrNull()
      assertWithMessage("$kind con ${classes.map { it.first }}").that(reason).isEqualTo(RejectReason.INVALID_FIELD)
    }
    // E chi scrive non puo' nemmeno costruirle.
    assertThrows(Exception::class.java) { aiHead(segments = listOf(ClassedSegment(SegmentClass.THUMB_S, meta.second))) }
    assertThrows(Exception::class.java) {
      DeviceHead(family.familyId, tablet.deviceId, "Public", HeadKind.INDEX, 1, 1, family.clock.nowMillis(), listOf(vec.classed))
    }
  }

  @Test
  fun versioneDellaBustaDiversaDaQuellaDelTipoRifiutata_eLaPiuNuovaENewerFormat() {
    // Una testa `ai` alla versione 1 e una testa `index` alla versione 2: rifiutate (una `index` alla 2 la rifiuterebbero le app vecchie).
    assertThat(accept(forged("ai", listOf("vec" to vec.ref), formatVersion = 1)).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    val indexV2 = forged("index", listOf("meta" to meta.second), formatVersion = TrustFormat.AI_VERSION)
    assertThat(accept(indexV2, kind = HeadKind.INDEX).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    // La versione 3 non si interpreta: "aggiorna l'app" (E-I3), prima ancora della firma.
    val v3 = aiHead().sign(tablet).also { it[4] = 3 }
    assertThat(accept(v3).rejectionOrNull()).isEqualTo(RejectReason.NEWER_FORMAT)
    // Solo le teste possono avere la versione 2: un'altra busta alla 2 e' "piu' nuova".
    assertThrows(IllegalArgumentException::class.java) {
      Envelope.seal(DocType.LEASE, jsonObject("x" to 1L.json()), tablet, TrustFormat.AI_VERSION)
    }
    val lease = Envelope.seal(DocType.LEASE, jsonObject("x" to 1L.json()), tablet).also { it[4] = TrustFormat.AI_VERSION.toByte() }
    assertThat(dev.pampa.mycloudgallery.core.trust.verdictOf { Envelope.open(lease, DocType.LEASE) }.rejectionOrNull()).isEqualTo(RejectReason.NEWER_FORMAT)
    // Un lettore della versione 1 (la regola di prima: versione > 1 = piu' nuova) non interpreta mai un file `ai`.
    assertThat(TrustFormat.isReadable(aiHead().sign(tablet)[4].toInt())).isFalse()
    assertThat(TrustFormat.isReadable(vec.bytes[4].toInt())).isFalse()
  }

  @Test
  fun laSerieAiEPropria_pubblicataELettaPerNomeAccantoAQuellaIndex() {
    val fs = family.common
    val index = HeadSeries.publish(fs, family.state, tablet, HeadKind.INDEX, 0, "mamma") { v ->
      DeviceHead(family.familyId, tablet.deviceId, "Public", HeadKind.INDEX, v, family.state.registry.version, family.clock.nowMillis(), listOf(ClassedSegment(SegmentClass.META, meta.second))).sign(tablet)
    }
    assertThat(index).isInstanceOf(HeadPublish.Published::class.java)
    repeat(3) { i ->
      val published = HeadSeries.publish(fs, family.state, tablet, HeadKind.AI, i.toLong(), "mamma") { v -> aiHead(version = v).sign(tablet) }
      assertThat((published as HeadPublish.Published).version).isEqualTo(i + 1L)
    }
    assertThat(fs.bytesAt(NasLayout.aiHead(tablet.deviceId, 3))).isNotNull()
    assertThat(fs.bytesAt(NasLayout.headFile(tablet.deviceId, "index", 2))).isNull()
    val ai = HeadSeries.readLatest(fs, family.state, tablet.deviceId, HeadKind.AI, null, "mamma").result as HeadRead.Accepted
    assertThat(ai.head.head.version).isEqualTo(3)
    val idx = HeadSeries.readLatest(fs, family.state, tablet.deviceId, HeadKind.INDEX, null, "mamma").result as HeadRead.Accepted
    assertThat(idx.head.head.version).isEqualTo(1)
    assertThat(idx.head.head.segments.single().cls).isEqualTo(SegmentClass.META)
  }

  @Test
  fun percorsiDeiDatiAiEDelleCopieDeiModelli() {
    val id = tablet.deviceId
    assertThat(NasLayout.aiHead(id, 7).value).isEqualTo(".mycloudgallery/devices/$id/ai/head/00000000000000000007")
    assertThat(NasLayout.aiSegment(id, vec.ref.hash).value).isEqualTo(".mycloudgallery/devices/$id/ai/seg/${vec.ref.hash}")
    val sha = "ab".repeat(32)
    assertThat(NasLayout.modelCopy(id, sha).value).isEqualTo(".mycloudgallery/devices/$id/models/$sha")
    assertThrows(IllegalArgumentException::class.java) { NasLayout.modelCopy(id, "AB".repeat(32)) }
    assertThrows(IllegalArgumentException::class.java) { NasLayout.modelCopy(id, "../x") }
    // La pulizia dei propri file riconosce le teste e i segmenti `ai` (tipo `ai`), mai le copie dei modelli.
    assertThat((OwnFile.classify(NasLayout.aiSegment(id, vec.ref.hash)) as OwnFile.Segment).kind).isEqualTo(HeadKind.AI)
    assertThat((OwnFile.classify(NasLayout.aiHead(id, 2)) as OwnFile.Head).kind).isEqualTo(HeadKind.AI)
    assertThat(OwnFile.classify(NasLayout.modelCopy(id, sha))).isNull()
  }
}
