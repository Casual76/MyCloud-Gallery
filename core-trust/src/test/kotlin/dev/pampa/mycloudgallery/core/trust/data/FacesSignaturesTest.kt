package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.verdictOf
import java.util.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Le firme della Fase 8 in core-trust (P0; `docs/fase8-progetto.md` D12, A1, A3, A5, A8): la busta dei volti (tipo 9) con la sola versione
 * 4, le classi 18-20 che nessun'altra testa accetta, i percorsi sotto `faces/<G>/` e la stessa base32 di core-model. Il codec delle teste
 * e dei segmenti dei volti e' di P3.
 */
class FacesSignaturesTest {

  private val family = TestFamily()
  private val phone = family.approveNewDevice(name = "Telefono", nasUser = "mamma")
  private val generation = FaceGeneration.fromBytes(ByteArray(16) { (it * 7).toByte() })

  /** A8: la 4 solo per il tipo 9; per chi non la conosce e' "piu' nuova", mai un errore del dato. */
  @Test
  fun laBustaDeiVoltiHaLaVersione4ESoloLei() {
    assertThat(DocType.fromCode(9)).isEqualTo(DocType.FACES_HEAD)
    assertThat(DocType.FACES_HEAD.maxPayload).isEqualTo(TrustLimits.MAX_FACES_HEAD_PAYLOAD)
    assertThat(TrustFormat.FACES_VERSION).isEqualTo(4)
    val payload = jsonObject("x" to 1L.json())
    val head = Envelope.seal(DocType.FACES_HEAD, payload, phone, TrustFormat.FACES_VERSION)
    assertThat(head[4].toInt()).isEqualTo(4)
    assertThat(Envelope.open(head, DocType.FACES_HEAD).version).isEqualTo(4)
    // Un lettore della versione 1 (le app senza la Fase 8) non interpreta mai un file dei volti (E-F11).
    assertThat(TrustFormat.isReadable(head[4].toInt())).isFalse()
    // La 5 e' "piu' nuova" (prima della firma); un altro tipo alla 4 non si scrive e, se letto, e' "piu' nuovo".
    val v5 = head.copyOf().also { it[4] = 5 }
    assertThat(verdictOf { Envelope.open(v5, DocType.FACES_HEAD) }.rejectionOrNull()).isEqualTo(RejectReason.NEWER_FORMAT)
    for (type in listOf(DocType.LEASE, DocType.DEVICE_HEAD, DocType.SHARED_ALBUM_HEAD, DocType.DEVICE_STATUS)) {
      assertThrows(IllegalArgumentException::class.java) { Envelope.seal(type, payload, phone, TrustFormat.FACES_VERSION) }
      val forged = Envelope.seal(type, payload, phone).also { it[4] = TrustFormat.FACES_VERSION.toByte() }
      assertWithMessage("$type alla 4").that(verdictOf { Envelope.open(forged, type) }.rejectionOrNull()).isEqualTo(RejectReason.NEWER_FORMAT)
    }
    // Una testa dei volti messa al posto di un'altra testa e' di un altro tipo.
    assertThat(verdictOf { Envelope.open(head, DocType.DEVICE_HEAD) }.rejectionOrNull()).isNotNull()
  }

  /** A3: una testa `index`, `personal` o `ai` che elenca una classe dei volti e' rifiutata intera (come prima per `faces`). */
  @Test
  fun leClassiDeiVoltiFuoriDallaTestaDeiVoltiRifiutanoLaTesta() {
    val seg = Segments.wrap("contenuto".toByteArray()).second
    for (cls in listOf("faces", "cluster", "people")) {
      for (kind in listOf(HeadKind.INDEX, HeadKind.PERSONAL, HeadKind.AI)) {
        val bytes = forged(kind, cls, seg)
        val reason = HeadVerifier.accept(bytes, family.state, HeadLocation("mamma", phone.deviceId, kind, 1), null, "mamma").rejectionOrNull()
        assertWithMessage("$cls in una testa ${kind.wire}").that(reason).isEqualTo(RejectReason.INVALID_FIELD)
      }
    }
    // E chi scrive non puo' nemmeno costruirle.
    for (cls in SegmentClass.FACES_CLASSES) {
      assertThrows(Exception::class.java) {
        DeviceHead(family.familyId, phone.deviceId, "mamma", HeadKind.INDEX, 1, 1, family.clock.nowMillis(), listOf(ClassedSegment(cls, seg)))
      }
    }
  }

  /** A1, A5: tutto sotto `faces/<G>/`, prenotazioni comprese; i percorsi li costruisce solo la regola, con valori validati. */
  @Test
  fun iPercorsiDeiVoltiStannoTuttiSottoLaGenerazione() {
    val g = generation.base32
    val device = phone.deviceId
    val hash = "0".repeat(52)
    assertThat(NasLayout.FACES_DIR.value).isEqualTo(".mycloudgallery/faces")
    assertThat(NasLayout.facesGenerationDir(generation).value).isEqualTo(".mycloudgallery/faces/$g")
    assertThat(NasLayout.facesHead(generation, device, 3).value).isEqualTo(".mycloudgallery/faces/$g/$device/head/${"3".padStart(20, '0')}")
    assertThat(NasLayout.facesSegment(generation, device, hash).value).isEqualTo(".mycloudgallery/faces/$g/$device/seg/$hash")
    val resource = NasLayout.facesWorkResource(generation, NasLayout.FACES_AREA_COMMON, "2025-07", 2)
    assertThat(resource).isEqualTo("faces/$g/work/comm/2025-07/2")
    assertThat(NasLayout.facesClusterResource(generation)).isEqualTo("faces/$g/cluster")
    val lease = NasLayout.facesLeaseFile(generation, resource, 257)
    assertThat(lease.value).startsWith(".mycloudgallery/faces/$g/leases/${NasLayout.resourceName(resource)}/")
    assertThat(lease.value).endsWith("/${"2".padStart(20, '0')}/${"257".padStart(20, '0')}")
    // La radice delle prenotazioni non e' mai un id di dispositivo, e nessun percorso dei volti esce da `faces/<G>/`.
    assertThat(TrustIdsCheck.isDeviceId(NasLayout.FACES_LEASES)).isFalse()
    for (path in listOf(NasLayout.facesHead(generation, device, 1), NasLayout.facesSegment(generation, device, hash), lease)) {
      assertThat(path.value).startsWith(NasLayout.facesGenerationDir(generation).value + "/")
    }
    assertThrows(IllegalArgumentException::class.java) { NasLayout.facesDeviceDir(generation, "../altro") }
    assertThrows(IllegalArgumentException::class.java) { NasLayout.facesSegment(generation, device, "non-un-hash") }
    assertThrows(IllegalArgumentException::class.java) { NasLayout.facesWorkResource(generation, "Public", "2025-07", 0) }
    assertThrows(IllegalArgumentException::class.java) { NasLayout.facesWorkResource(generation, NasLayout.FACES_AREA_PRIVATE, "2025-13", 0) }
  }

  /** La base32 di core-model (generazione, id delle persone, localizzatori) e' la stessa del formato (§3.1). */
  @Test
  fun laBase32DeiVoltiEQuellaDelFormato() {
    val random = Random(8)
    repeat(200) {
      val sixteen = ByteArray(16).also { random.nextBytes(it) }
      val g = FaceGeneration.fromBytes(sixteen)
      assertThat(g.base32).isEqualTo(Base32.encode(sixteen))
      assertThat(Base32.decodeExact(g.base32, 16)).isEqualTo(sixteen)
      val person = PersonId.fromBytes(sixteen)
      assertThat(person.base32()).isEqualTo(Base32.encode(sixteen))
      val key = ByteArray(32).also { random.nextBytes(it) }
      val ck = ContentKey(key.joinToString("") { "%02x".format(it.toInt() and 0xff) })
      val locator = FaceLocator(ck, 0, FaceBox(1, 2, 3, 4))
      assertThat(locator.encode().substringBefore(':')).isEqualTo(Base32.encode(key))
    }
  }

  private fun forged(kind: HeadKind, cls: String, ref: SegmentRef): ByteArray {
    val payload = jsonObject(
      "family" to family.familyId.json(),
      "device" to phone.deviceId.json(),
      "share" to "mamma".json(),
      "kind" to kind.wire.json(),
      "version" to 1L.json(),
      "registryVersion" to family.state.registry.version.json(),
      "createdAt" to family.clock.nowMillis().json(),
      "segments" to listOf(jsonObject("class" to cls.json(), "hash" to ref.hash.json(), "size" to ref.size.json())).json(),
    )
    return Envelope.seal(DocType.DEVICE_HEAD, payload, phone, kind.formatVersion)
  }

  private object TrustIdsCheck {
    fun isDeviceId(text: String): Boolean = dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.isValid(text)
  }
}
