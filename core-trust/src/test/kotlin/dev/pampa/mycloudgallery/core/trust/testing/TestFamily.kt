package dev.pampa.mycloudgallery.core.trust.testing

import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.SignedRegistry
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult

/**
 * Una famiglia di prova: chiave di famiglia, telefono dell'admin (fondatore) e area comune in memoria.
 * Ogni operazione dell'admin pubblica la nuova versione del registro e aggiorna [state].
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

  private var authority = FamilyAuthority(familyKey, clock)

  /** Tutte le versioni firmate, in ordine. */
  val history = ArrayList<SignedRegistry>()

  lateinit var state: RegistryState
    private set

  /** L'ancora che il QR darebbe a un dispositivo nuovo adesso. */
  fun anchor(): TrustAnchor = TrustAnchor.create(familyId, state.trustedFamilyKey.fingerprint, state.registry.version)!!

  /** L'ancora di un dispositivo configurato a mano (solo impronta, nessuna versione minima). */
  fun manualAnchor(): TrustAnchor = TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 0)!!

  init {
    val founder = request(adminDevice, "Telefono di Admin", "admin")
    publish(authority.found(familyId, config, verified(founder)))
  }

  fun request(device: Signer, name: String, nasUser: String): ByteArray =
    RegistrationRequest.sign(device, familyId, name, "SM-S931B", nasUser, clock.nowMillis())

  fun verified(requestBytes: ByteArray): RegistrationRequest {
    val signer = RegistrationRequest.verify(requestBytes, familyId, fileNameOf(requestBytes))
    return (signer as Verdict.Accepted).value
  }

  /** Approva un dispositivo nuovo e restituisce il suo firmatario. */
  fun approveNewDevice(name: String = "Tablet profilo Mamma", nasUser: String = "mamma", admin: Boolean = false): Signer {
    val device = newSigner()
    publish(authority.approve(state, verified(request(device, name, nasUser)), admin))
    return device
  }

  fun changeStatus(device: Signer, status: DeviceStatus, frozenHeads: List<String> = emptyList()) {
    publish(authority.changeStatus(state, TrustIds.deviceIdOf(device.publicKey), status, frozenHeads))
  }

  fun rotate(): FamilyKey {
    val next = FamilyKey.generate()
    publish(authority.rotateTo(state, next))
    familyKey = next
    authority = FamilyAuthority(next, clock)
    return next
  }

  fun publish(signed: SignedRegistry) {
    check(FamilyAuthority.publish(common, signed) == PublishResult.CREATED)
    history.add(signed)
    val current = if (this::state.isInitialized) state else null
    val anchor = TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 0)!!
    state = (RegistryVerifier.accept(signed.bytes, current, anchor) as Verdict.Accepted).value
  }

  private fun fileNameOf(requestBytes: ByteArray): String {
    // La chiave pubblica sta nell'intestazione della busta (byte 6..96).
    val spki = requestBytes.copyOfRange(6, 6 + 91)
    return TrustIds.deviceIdOf(dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey.fromSpki(spki))
  }
}
