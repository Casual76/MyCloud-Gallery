package dev.pampa.mycloudgallery.core.trust.security

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.data.AlbumDocs
import dev.pampa.mycloudgallery.core.trust.data.AlbumFx
import dev.pampa.mycloudgallery.core.trust.data.AlbumHead
import dev.pampa.mycloudgallery.core.trust.data.AlbumHeadLocation
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.core.trust.data.DeviceStatusReport
import dev.pampa.mycloudgallery.core.trust.data.DeviceStatusVerifier
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHead
import dev.pampa.mycloudgallery.core.trust.data.FacesHeadLocation
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadLocation
import dev.pampa.mycloudgallery.core.trust.data.HeadVerifier
import dev.pampa.mycloudgallery.core.trust.data.Lease
import dev.pampa.mycloudgallery.core.trust.data.Leases
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.Segments
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Test

/**
 * Fase 10 P11, voce **S2** della revisione finale (docs/fase10-progetto.md §2.11, D36; C8, E-I15, E-S14), il livello JVM di
 * `tools/security/rogue-device`: "dati firmati da dispositivi non approvati o revocati rifiutati". Per **ogni** dato che un dispositivo firma
 * e un altro legge dal NAS (teste dell'indice, dei dati personali e AI, testa di un album condiviso, testa dei volti, stato del dispositivo,
 * prenotazione) la stessa matrice di firmatari:
 *
 * - approvato: accettato (il controllo positivo: la matrice non rifiuta tutto per un errore del banco);
 * - chiave mai vista, chiave **in attesa** (richiesta pubblicata, mai approvata): `SIGNER_NOT_APPROVED`;
 * - revocato e perso, anche per un dato firmato **prima** della revoca (nessun "valido fino a": si potrebbe retrodatare): `SIGNER_REVOKED`;
 * - dismesso senza teste congelate: `SIGNER_RETIRED`;
 * - un'altra chiave che firma il contenuto di un dispositivo approvato (si spaccia per lui): rifiutato.
 */
class S2RogueDeviceTest {

  /** Un tipo di dato: come lo firma un dispositivo e come lo legge un altro, nella famiglia [family]. */
  private class Kind(
    val name: String,
    val family: TestFamily,
    val type: DocType,
    val sign: (Signer) -> ByteArray,
    val verify: (ByteArray, Signer) -> Verdict<*>,
  )

  private val segment = Segments.wrap("segmento di prova S2".toByteArray()).second

  private fun headKind(kind: HeadKind, share: String, cls: SegmentClass, reader: String): Kind {
    val family = TestFamily()
    return Kind(
      name = "testa ${kind.wire}",
      family = family,
      type = DocType.DEVICE_HEAD,
      sign = { s -> DeviceHead(family.familyId, s.deviceId, share, kind, 1, family.state.registry.version, family.clock.nowMillis(), listOf(ClassedSegment(cls, segment))).sign(s) },
      verify = { bytes, s -> HeadVerifier.accept(bytes, family.state, HeadLocation(share, s.deviceId, kind, 1), null, reader) },
    )
  }

  private fun albumKind(): Kind {
    val fx = AlbumFx()
    return Kind(
      name = "testa d'album condiviso",
      family = fx.family,
      type = DocType.SHARED_ALBUM_HEAD,
      sign = { s -> AlbumDocs.encodeHead(AlbumHead(fx.family.familyId, fx.album, s.deviceId, 1, fx.family.state.registry.version, fx.now, listOf(segment)), s).bytes },
      verify = { bytes, s -> AlbumDocs.decodeHead(bytes, fx.family.state, AlbumHeadLocation(fx.album, s.deviceId, 1), null) },
    )
  }

  private fun facesKind(): Kind {
    val family = TestFamily()
    val generation = FaceGeneration.fromBytes(ByteArray(16) { (it * 7 + 5).toByte() })
    return Kind(
      name = "testa dei volti",
      family = family,
      type = DocType.FACES_HEAD,
      sign = { s -> FacesDocs.encodeHead(FacesHead(family.familyId, "test", generation, s.deviceId, 1, family.state.registry.version, family.clock.nowMillis(), null, emptyList()), s).bytes },
      verify = { bytes, s -> FacesDocs.decodeHead(bytes, family.state, FacesHeadLocation(generation, s.deviceId, 1), "test", null, family.clock.nowMillis()) },
    )
  }

  private fun statusKind(): Kind {
    val family = TestFamily()
    return Kind(
      name = "stato del dispositivo",
      family = family,
      type = DocType.DEVICE_STATUS,
      sign = { s -> DeviceStatusReport(family.familyId, s.deviceId, 1, "1.0.0", setOf(DeviceRole.INDEXER), family.clock.nowMillis(), family.state.registry.version).sign(s) },
      verify = { bytes, s -> DeviceStatusVerifier.accept(bytes, family.state, s.deviceId, 1, null, family.clock.nowMillis()) },
    )
  }

  private fun leaseKind(): Kind {
    val family = TestFamily()
    val resource = "index:Public:batch-0042"
    return Kind(
      name = "prenotazione",
      family = family,
      type = DocType.LEASE,
      sign = { s -> Lease(family.familyId, s.deviceId, "Public", resource, 1, family.clock.nowMillis(), 15L * 60 * 1000).sign(s) },
      verify = { bytes, _ -> Leases.verify(bytes, family.state, "Public", resource, 1) },
    )
  }

  private fun kinds(): List<Kind> = listOf(
    headKind(HeadKind.INDEX, "Public", SegmentClass.META, reader = "babbo"),
    headKind(HeadKind.PERSONAL, "test", SegmentClass.PERSONAL, reader = "test"),
    headKind(HeadKind.AI, "Public", SegmentClass.VEC, reader = "babbo"),
    albumKind(),
    facesKind(),
    statusKind(),
    leaseKind(),
  )

  @Test
  fun nessunDatoDiUnDispositivoNonApprovatoORevocatoEAccettato() {
    var cases = 0
    for (kind in kinds()) {
      val family = kind.family
      val approved = family.approveNewDevice(name = "Telefono approvato", nasUser = "test")
      val unknown = newSigner()
      val pending = newSigner().also { family.common.publishExclusive(NasLayout.requestFile(it.deviceId), family.request(it, "In attesa", "test")) }
      val revoked = family.approveNewDevice(name = "Telefono revocato", nasUser = "test")
      val lost = family.approveNewDevice(name = "Telefono perso", nasUser = "test")
      val retired = family.approveNewDevice(name = "Telefono dismesso", nasUser = "test")
      // Firmati quando erano ancora approvati: dopo la revoca non valgono piu' (nessuna data dichiarata li salva).
      val beforeRevoke = kind.sign(revoked)
      val beforeLost = kind.sign(lost)
      val beforeRetire = kind.sign(retired)
      assertWithMessage("${kind.name}: prima della revoca vale").that(kind.verify(beforeRevoke, revoked)).isInstanceOf(Verdict.Accepted::class.java)
      family.changeStatus(revoked, DeviceStatus.REVOKED)
      family.changeStatus(lost, DeviceStatus.LOST)
      family.changeStatus(retired, DeviceStatus.RETIRED)

      fun expect(case: String, bytes: ByteArray, signer: Signer, reason: RejectReason?) {
        cases++
        val verdict = kind.verify(bytes, signer)
        if (reason == null) {
          assertWithMessage("${kind.name}, $case").that(verdict).isInstanceOf(Verdict.Accepted::class.java)
        } else {
          assertWithMessage("${kind.name}, $case").that(verdict.rejectionOrNull()).isEqualTo(reason)
        }
      }
      expect("approvato", kind.sign(approved), approved, null)
      expect("chiave mai vista", kind.sign(unknown), unknown, RejectReason.SIGNER_NOT_APPROVED)
      expect("chiave in attesa", kind.sign(pending), pending, RejectReason.SIGNER_NOT_APPROVED)
      expect("revocato, firmato dopo", kind.sign(revoked), revoked, RejectReason.SIGNER_REVOKED)
      expect("revocato, firmato prima", beforeRevoke, revoked, RejectReason.SIGNER_REVOKED)
      expect("perso, firmato dopo", kind.sign(lost), lost, RejectReason.SIGNER_REVOKED)
      expect("perso, firmato prima", beforeLost, lost, RejectReason.SIGNER_REVOKED)
      expect("dismesso senza teste congelate", beforeRetire, retired, RejectReason.SIGNER_RETIRED)

      // Una chiave canaglia firma il contenuto di un dispositivo approvato: la busta nomina la sua chiave, che il registro non conosce.
      val envelope = Envelope.open(kind.sign(approved), kind.type)
      val forged = AlbumFx.rawSeal(kind.type, envelope.payloadJson(), unknown, envelope.version)
      cases++
      val verdict = kind.verify(forged, approved)
      assertWithMessage("${kind.name}: contenuto di un approvato firmato da un'altra chiave").that(verdict).isInstanceOf(Verdict.Rejected::class.java)
      assertWithMessage("${kind.name}: motivo della firma canaglia").that(verdict.rejectionOrNull())
        .isAnyOf(RejectReason.SIGNER_NOT_APPROVED, RejectReason.WRONG_LOCATION)
    }
    assertWithMessage("casi della matrice").that(cases).isEqualTo(7 * 9)
    println("MCG-SEC S2 tipi=7 casi=$cases accettati_attesi=7 rifiutati=${cases - 7} versione_formato=${TrustFormat.CURRENT_VERSION}")
  }
}
