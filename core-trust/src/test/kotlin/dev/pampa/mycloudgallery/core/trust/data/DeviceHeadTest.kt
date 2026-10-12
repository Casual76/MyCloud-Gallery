package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Test

class DeviceHeadTest {

  private val family = TestFamily()
  private val tablet = family.approveNewDevice(name = "Tablet profilo Mamma", nasUser = "mamma")
  private val segment = Segments.wrap("contenuto del segmento".toByteArray())

  private fun head(
    signer: Signer = tablet,
    version: Long = 1,
    share: String = "Public",
    kind: HeadKind = HeadKind.INDEX,
    registryVersion: Long = family.state.registry.version,
    segments: List<SegmentRef> = listOf(segment.second),
  ) = DeviceHead(family.familyId, signer.deviceId, share, kind, version, registryVersion, family.clock.nowMillis(), segments).sign(signer)

  private fun location(signer: Signer = tablet, version: Long = 1, share: String = "Public", kind: HeadKind = HeadKind.INDEX) =
    HeadLocation(share, signer.deviceId, kind, version)

  private fun accept(bytes: ByteArray, location: HeadLocation = location(), previous: HeadMark? = null, reader: String = "babbo") =
    HeadVerifier.accept(bytes, family.state, location, previous, reader)

  @Test
  fun testaDiUnDispositivoApprovatoAccettata_eSegmentoVerificato() {
    val accepted = accept(head()).acceptedOrNull()!!

    assertThat(accepted.head.segments).containsExactly(segment.second)
    assertThat(Segments.unwrap(segment.first, accepted.head.segments[0]).acceptedOrNull()).isEqualTo("contenuto del segmento".toByteArray())
    assertThat(accepted.registryBehind).isFalse()
  }

  @Test
  fun datiNonFirmatiOFirmatiDaUnDispositivoNonApprovatoRifiutati() {
    val pending = newSigner() // ha chiesto di entrare ma non e' approvato
    family.common.publishExclusive(
      dev.pampa.mycloudgallery.core.trust.nas.NasLayout.requestFile(pending.deviceId),
      family.request(pending, "In attesa", "babbo"),
    )

    assertThat(accept(head(signer = pending), location(signer = pending)).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)
    assertThat(accept("non firmato".repeat(20).toByteArray()).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun datiDiUnDispositivoRevocatoOPersoRifiutatiAncheSeVecchi() {
    val oldHead = head() // firmato quando era approvato
    family.changeStatus(tablet, DeviceStatus.REVOKED)

    assertThat(accept(oldHead).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_REVOKED)

    val phone = family.approveNewDevice(name = "Telefono", nasUser = "babbo")
    val phoneHead = head(signer = phone)
    family.changeStatus(phone, DeviceStatus.LOST)
    assertThat(accept(phoneHead, location(signer = phone)).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_REVOKED)
  }

  @Test
  fun dispositivoDismesso_valeSoloLaTestaCongelata() {
    val frozen = head(version = 3)
    val frozenHash = Envelope.open(frozen, DocType.DEVICE_HEAD).fileHash
    val otherOld = head(version = 2)
    family.changeStatus(tablet, DeviceStatus.RETIRED, frozenHeads = listOf(frozenHash))
    val afterRetirement = head(version = 4)

    assertThat(accept(frozen, location(version = 3)).acceptedOrNull()).isNotNull()
    assertThat(accept(otherOld, location(version = 2)).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_RETIRED)
    assertThat(accept(afterRetirement, location(version = 4)).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_RETIRED)
  }

  @Test
  fun rollbackEdEquivocazioneDelleTeste() {
    val v2 = accept(head(version = 2), location(version = 2)).acceptedOrNull()!!

    assertThat(accept(head(version = 1), location(version = 1), previous = v2.mark).rejectionOrNull()).isEqualTo(RejectReason.ROLLBACK)
    val fork = head(version = 2, segments = emptyList())
    assertThat(accept(fork, location(version = 2), previous = v2.mark).rejectionOrNull()).isEqualTo(RejectReason.EQUIVOCATION)
    assertThat(accept(head(version = 3), location(version = 3), previous = v2.mark).acceptedOrNull()).isNotNull()
  }

  @Test
  fun unaTestaCopiataAltroveNonVale() {
    val bytes = head()

    assertThat(accept(bytes, location(share = "Privata-Mamma")).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    assertThat(accept(bytes, location(version = 9)).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    assertThat(accept(bytes, location(kind = HeadKind.PERSONAL)).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
    val phone = family.approveNewDevice(name = "Telefono", nasUser = "babbo")
    assertThat(accept(bytes, location(signer = phone)).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
  }

  @Test
  fun testaDichiaratadiUnAltroDispositivoRifiutata() {
    // Il tablet firma una testa che dice di essere del telefono.
    val phone = family.approveNewDevice(name = "Telefono", nasUser = "babbo")
    val payload = jsonObject(
      "family" to family.familyId.json(),
      "device" to phone.deviceId.json(),
      "share" to "Public".json(),
      "kind" to "index".json(),
      "version" to 1.json(),
      "registryVersion" to 1.json(),
      "createdAt" to family.clock.nowMillis().json(),
      "segments" to emptyList<dev.pampa.mycloudgallery.core.trust.codec.JsonValue>().json(),
    )
    val bytes = Envelope.seal(DocType.DEVICE_HEAD, payload, tablet)

    assertThat(accept(bytes, location(signer = phone)).rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
  }

  @Test
  fun datiPersonali_soloDelloStessoAccountEMaiNellAreaComune() {
    val personal = head(share = "Privata-Mamma", kind = HeadKind.PERSONAL)
    val where = location(share = "Privata-Mamma", kind = HeadKind.PERSONAL)

    assertThat(accept(personal, where, reader = "MAMMA").acceptedOrNull()).isNotNull()
    assertThat(accept(personal, where, reader = "babbo").rejectionOrNull()).isEqualTo(RejectReason.NOT_OWNER)
    val inCommon = head(share = "Public", kind = HeadKind.PERSONAL)
    assertThat(accept(inCommon, location(kind = HeadKind.PERSONAL), reader = "mamma").rejectionOrNull()).isEqualTo(RejectReason.WRONG_LOCATION)
  }

  @Test
  fun registroDelLettoreIndietro_segnalato() {
    val accepted = accept(head(registryVersion = family.state.registry.version + 5)).acceptedOrNull()!!

    assertThat(accepted.registryBehind).isTrue()
  }

  @Test
  fun segmentiAlteratiTroncatiODiFormatoNuovoRifiutati() {
    val (bytes, ref) = segment

    val altered = bytes.copyOf().also { it[it.size - 1] = 0 }
    assertThat(Segments.unwrap(altered, ref).rejectionOrNull()).isEqualTo(RejectReason.HASH_MISMATCH)
    assertThat(Segments.unwrap(bytes.copyOf(bytes.size - 1), ref).rejectionOrNull()).isEqualTo(RejectReason.HASH_MISMATCH)

    val newer = "MCGB".toByteArray() + byteArrayOf((TrustFormat.CURRENT_VERSION + 1).toByte()) + "x".toByteArray()
    val newerRef = SegmentRef(dev.pampa.mycloudgallery.core.trust.crypto.Hashes.sha256Name(newer), newer.size.toLong())
    assertThat(Segments.unwrap(newer, newerRef).rejectionOrNull()).isEqualTo(RejectReason.NEWER_FORMAT)

    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
    val jpegRef = SegmentRef(dev.pampa.mycloudgallery.core.trust.crypto.Hashes.sha256Name(jpeg), jpeg.size.toLong())
    assertThat(Segments.unwrap(jpeg, jpegRef).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun testaConSegmentiDisordinatiOEnormiRifiutata() {
    val a = Segments.wrap("a".toByteArray()).second
    val b = Segments.wrap("b".toByteArray()).second
    val (low, high) = listOf(a, b).sortedBy { it.hash }
    val payload = jsonObject(
      "family" to family.familyId.json(),
      "device" to tablet.deviceId.json(),
      "share" to "Public".json(),
      "kind" to "index".json(),
      "version" to 1.json(),
      "registryVersion" to 1.json(),
      "createdAt" to family.clock.nowMillis().json(),
      "segments" to listOf(high, low).map { jsonObject("hash" to it.hash.json(), "size" to it.size.json()) }.json(),
    )
    val unordered = Envelope.seal(DocType.DEVICE_HEAD, payload, tablet)
    val hugeSegment = Envelope.seal(
      DocType.DEVICE_HEAD,
      jsonObject(*payload.members.map { it.key to it.value }.toTypedArray(), "segments" to listOf(jsonObject("hash" to a.hash.json(), "size" to (1L shl 40).json())).json()),
      tablet,
    )

    assertThat(accept(unordered).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(accept(hugeSegment).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
  }
}
