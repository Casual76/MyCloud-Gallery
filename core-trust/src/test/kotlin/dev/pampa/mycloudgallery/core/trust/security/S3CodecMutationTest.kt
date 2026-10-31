package dev.pampa.mycloudgallery.core.trust.security

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.EcdsaDer
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.AlbumDocs
import dev.pampa.mycloudgallery.core.trust.data.AlbumSegmentContext
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FramesBody
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadLocation
import dev.pampa.mycloudgallery.core.trust.data.HeadVerifier
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.data.MetaRecords
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import java.nio.ByteBuffer
import org.junit.Test

/**
 * Fase 10 P11, voce **S3 (a)** della revisione finale (docs/fase10-progetto.md §2.11, D36; C9, E-I13, E-I14): "un segmento ostile non fa
 * danni", sul codec. Per ogni codec che legge byte dal NAS (segmenti `meta`, miniature, fotogrammi, vettori, testo letto, album condivisi,
 * volti, gruppi, persone, il record JSON e il contenuto firmato di una testa) **2.000 mutazioni** del corpus del repository in `check.ps1` e
 * **100.000** con `MCG_SECURITY_FULL=1`. L'impronta si ricalcola sempre sui byte mutati (come se un dispositivo approvato ma compromesso li
 * elencasse nella sua testa firmata), e la testa si rifirma con un dispositivo approvato: cosi' si arriva davvero al contenuto.
 *
 * Ogni mutazione: nessun `Throwable` fuori dal codec (nemmeno memoria o pila esaurite), meno di 2 s; un `meta` accettato si riscrive
 * **uguale byte per byte** (una sola forma valida). Un caso che rompe si salva in `build/security/crashers/`.
 */
class S3CodecMutationTest {

  private val count = SecurityCorpus.mutationsPerCodec

  private fun ref(bytes: ByteArray): SegmentRef {
    if (bytes.isEmpty() || bytes.size > TrustLimits.MAX_SEGMENT_BYTES) throw Skip()
    return SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong())
  }

  private fun reason(verdict: Verdict<*>): String? = verdict.rejectionOrNull()?.name

  private fun run(codec: String, seed: Long, decode: (ByteArray) -> String?): MutationTally {
    val tally = mutateAndDecode(codec, SecurityCorpus.read(codec), count, seed, decode = decode)
    println(tally.line())
    assertWithMessage("${tally.codec}: crolli\n" + tally.failures.take(10).joinToString("\n")).that(tally.failures).isEmpty()
    assertWithMessage("${tally.codec}: le mutazioni arrivano al codec").that(tally.rejected).isGreaterThan(count / 2)
    return tally
  }

  @Test
  fun ilCorpusSiRileggeIntero() {
    // Il controllo positivo: ogni voce del corpus, intatta, e' accettata (altrimenti le mutazioni partirebbero da un rifiuto).
    val album = SecurityCorpus.context("album")
    val context = AlbumSegmentContext(AlbumId(album.getValue("album")), album.getValue("device"), AccountId(album.getValue("account")))
    val checks = mapOf<String, (ByteArray) -> Verdict<*>>(
      "meta" to { b -> IndexSegments.decode(b, ref(b), SegmentClass.META) },
      "thumbS" to { b -> IndexSegments.decode(b, ref(b), SegmentClass.THUMB_S) },
      "frames" to { b -> IndexSegments.decode(b, ref(b), SegmentClass.FRAMES) },
      "vec" to { b -> AiSegments.decode(b, ref(b), AiClass.VEC) },
      "ocr" to { b -> AiSegments.decode(b, ref(b), AiClass.OCR) },
      "album" to { b -> AlbumDocs.decodeSegment(b, ref(b), context) },
      "faces" to { b -> FacesDocs.decodeSegment(b, ref(b), SegmentClass.FACES) },
      "cluster" to { b -> FacesDocs.decodeSegment(b, ref(b), SegmentClass.CLUSTER) },
      "people" to { b -> FacesDocs.decodeSegment(b, ref(b), SegmentClass.PEOPLE) },
      "json" to { b -> MetaRecords.verify(b) },
    )
    var entries = 0
    for ((codec, check) in checks) {
      for ((i, bytes) in SecurityCorpus.read(codec).withIndex()) {
        entries++
        assertWithMessage("corpus $codec/$i").that(check(bytes)).isInstanceOf(Verdict.Accepted::class.java)
      }
    }
    val head = HeadBench()
    assertWithMessage("corpus testa").that(head.accept(head.payload)).isNull()
    println("MCG-SEC S3a corpus voci=${entries + 1} codec=${checks.size + 1}")
  }

  @Test
  fun metaUnaSolaFormaValida() {
    var nonCanonical = 0
    run("meta", 101) { b ->
      when (val v = IndexSegments.decode(b, ref(b), SegmentClass.META)) {
        is Verdict.Accepted -> {
          val records = (v.value as SegmentContent.Meta).records
          if (!IndexSegments.meta(records).bytes.contentEquals(b)) nonCanonical++
          null
        }
        is Verdict.Rejected -> v.reason.name
      }
    }
    assertWithMessage("meta accettati in una forma non canonica").that(nonCanonical).isEqualTo(0)
  }

  @Test
  fun miniatureEFotogrammi() {
    run("thumbS", 102) { b -> reason(IndexSegments.decode(b, ref(b), SegmentClass.THUMB_S)) }
    run("frames", 103) { b ->
      when (val v = IndexSegments.decode(b, ref(b), SegmentClass.FRAMES)) {
        is Verdict.Accepted -> (v.value as SegmentContent.Blobs).let { blobs ->
          blobs.entries.firstNotNullOfOrNull { entry -> reason(FramesBody.read(blobs.bytes, entry)) }
        }
        is Verdict.Rejected -> v.reason.name
      }
    }
  }

  @Test
  fun vettoriETestoLetto() {
    run("vec", 104) { b -> reason(AiSegments.decode(b, ref(b), AiClass.VEC)) }
    run("ocr", 105) { b -> reason(AiSegments.decode(b, ref(b), AiClass.OCR)) }
  }

  @Test
  fun albumCondivisi() {
    val album = SecurityCorpus.context("album")
    val context = AlbumSegmentContext(AlbumId(album.getValue("album")), album.getValue("device"), AccountId(album.getValue("account")))
    run("album", 106) { b -> reason(AlbumDocs.decodeSegment(b, ref(b), context)) }
  }

  @Test
  fun voltiGruppiEPersone() {
    run("faces", 107) { b -> reason(FacesDocs.decodeSegment(b, ref(b), SegmentClass.FACES)) }
    run("cluster", 108) { b -> reason(FacesDocs.decodeSegment(b, ref(b), SegmentClass.CLUSTER)) }
    run("people", 109) { b -> reason(FacesDocs.decodeSegment(b, ref(b), SegmentClass.PEOPLE)) }
  }

  @Test
  fun recordJson() {
    run("json", 110) { b -> reason(MetaRecords.verify(b)) }
  }

  @Test
  fun contenutoDiUnaTestaRifirmatoDaUnDispositivoApprovato() {
    val bench = HeadBench()
    val corpus = listOf(bench.payload)
    // Meno mutazioni: ognuna e' una firma ECDSA vera.
    val tally = mutateAndDecode("testa", corpus, count / 4, 111) { b -> bench.accept(b) }
    println(tally.line())
    assertWithMessage("testa: crolli\n" + tally.failures.take(10).joinToString("\n")).that(tally.failures).isEmpty()
  }

  /** Una famiglia nuova con un telefono approvato; il contenuto del corpus con i suoi id al posto di quelli del generatore. */
  private class HeadBench {
    private val family = TestFamily()
    private val phone = family.approveNewDevice(name = "Telefono", nasUser = "test")
    val payload: ByteArray

    init {
      val context = SecurityCorpus.context("testa")
      val text = String(SecurityCorpus.read("testa").single(), Charsets.UTF_8)
        .replace(context.getValue("family"), family.familyId)
        .replace(context.getValue("device"), phone.deviceId)
      payload = text.toByteArray(Charsets.UTF_8)
    }

    fun accept(body: ByteArray): String? {
      if (body.size > DocType.DEVICE_HEAD.maxPayload) throw Skip()
      val sealed = seal(DocType.DEVICE_HEAD, body, phone)
      return HeadVerifier.accept(sealed, family.state, HeadLocation("Public", phone.deviceId, HeadKind.INDEX, 1), null, "babbo").rejectionOrNull()?.name
    }

    /** La busta firmata con [body] cosi' com'e' (anche non JSON): la forma di `Envelope.seal`, senza i suoi controlli. */
    private fun seal(type: DocType, body: ByteArray, signer: Signer): ByteArray {
      val signed = ByteBuffer.allocate(4 + 1 + 1 + 91 + 4 + body.size)
        .put("MCGS".toByteArray(Charsets.US_ASCII)).put(TrustFormat.CURRENT_VERSION.toByte()).put(type.code.toByte())
        .put(signer.publicKey.encoded).putInt(body.size).put(body).array()
      val signature = EcdsaDer.normalize(signer.sign("mycloudgallery/signed\u0000".toByteArray(Charsets.US_ASCII) + signed))
      return signed + byteArrayOf(signature.size.toByte()) + signature
    }
  }
}
