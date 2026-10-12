package dev.pampa.mycloudgallery.core.trust.testing

import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryDraft
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor

/**
 * Una famiglia di prova: chiave di famiglia, telefono dell'admin (fondatore) e area comune in memoria.
 * Ogni operazione dell'admin pubblica la nuova versione del registro (`commit`) e aggiorna [state].
 */
class TestFamily(
  val clock: FakeNasClock = FakeNasClock(),
  val common: FakeNasFileSystem = FakeNasFileSystem("Public"),
) {
  var familyKey: FamilyKey = FamilyKey.generate()
    private set
  val familyId: String = TrustIds.random()
  val adminDevice: Signer = newSigner()
  val config = FamilyConfig(nasHost = "mycloudex2ultra.fritz.box", commonShare = "Public")

  var authority = FamilyAuthority(familyKey, clock)
    private set

  /** I byte firmati di tutte le versioni pubblicate da questo admin, in ordine. */
  val history = ArrayList<ByteArray>()

  lateinit var state: RegistryState
    private set

  /** L'ancora che il QR darebbe a un dispositivo nuovo adesso. */
  fun anchor(): TrustAnchor = state.toAnchor()

  /** L'ancora di un dispositivo configurato a mano (solo impronta, nessuna versione minima). */
  fun manualAnchor(): TrustAnchor = TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 0)!!

  init {
    val founder = verified(request(adminDevice, "Telefono di Admin", "admin"))
    val signed = authority.found(familyId, config, founder)
    check(FamilyAuthority.publishFounding(common, signed) == PublishResult.CREATED)
    history.add(signed.bytes)
    state = (RegistryVerifier.accept(signed.bytes, null, manualAnchor()) as Verdict.Accepted).value
  }

  fun request(device: Signer, name: String, nasUser: String): ByteArray =
    RegistrationRequest.sign(device, familyId, name, "SM-S931B", nasUser, clock.nowMillis())

  fun verified(requestBytes: ByteArray): RegistrationRequest {
    val verdict = RegistrationRequest.verify(requestBytes, familyId, fileNameOf(requestBytes))
    return (verdict as Verdict.Accepted).value
  }

  /** La prova normale: l'admin digita il codice che legge sul dispositivo nuovo. */
  fun typed(request: RegistrationRequest) = ApprovalProof.TypedCode(request.approvalCode)

  /** Approva un dispositivo nuovo e restituisce il suo firmatario. */
  fun approveNewDevice(name: String = "Tablet profilo Mamma", nasUser: String = "mamma", admin: Boolean = false): Signer {
    val device = newSigner()
    val request = verified(request(device, name, nasUser))
    commit(authority.approve(state, request, typed(request), firstSeenAt = clock.nowMillis(), admin = admin))
    return device
  }

  fun changeStatus(device: Signer, status: DeviceStatus, frozenHeads: List<String> = emptyList()) {
    commit(authority.changeStatus(state, TrustIds.deviceIdOf(device.publicKey), status, frozenHeads))
  }

  fun rotate(): FamilyKey {
    val next = FamilyKey.generate()
    commit(authority.rotateTo(state, next))
    familyKey = next
    authority = FamilyAuthority(next, clock)
    return next
  }

  fun commit(draft: RegistryDraft): RegistryState {
    val result = authority.commit(common, draft)
    check(result is CommitResult.Published) { "pubblicazione non riuscita: $result" }
    history.add(result.state.signedBytes)
    state = result.state
    return state
  }

  private fun fileNameOf(requestBytes: ByteArray): String {
    // La chiave pubblica sta nell'intestazione della busta (byte 6..96).
    val spki = requestBytes.copyOfRange(6, 6 + 91)
    return TrustIds.deviceIdOf(EcPublicKey.fromSpki(spki))
  }
}
