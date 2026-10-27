package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.sync.MemoryShare
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
import java.security.SecureRandom

// La famiglia del banco: un registro firmato vero (chiave di famiglia, dispositivi approvati) con le chiavi ricavate da un seme, cosi' gli id dei
// dispositivi e gli spareggi (vince il dispositivo minore) sono gli stessi a ogni lancio dello stesso giro. Il registro sta in una share in memoria
// (`common`, l'area comune finta): i dispositivi lo tengono in memoria, come dopo il suo caricamento; sul NAS (finto, simulatore o vero) l'area comune
// non ha niente dei volti, e il banco lo controlla.

internal class ChaosFamily(private val clock: FacesClock, seed: Long, val common: MemoryShare = MemoryShare("test-comune")) {
  private val random: SecureRandom = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }
  private val familyKey = FamilyKey.generate(random)
  val familyId: String = TrustIds.random(random)
  private val authority = FamilyAuthority(familyKey) { clock.nasNowMillis()!! }

  @Volatile lateinit var state: RegistryState
    private set

  init {
    val founder = verified(request(FamilyKey.generate(random), "Telefono admin", "admin"))
    val signed = authority.found(familyId, FamilyConfig(nasHost = "192.168.178.58", commonShare = common.shareName), founder)
    check(FamilyAuthority.publishFounding(common, signed) == PublishResult.CREATED)
    state = (RegistryVerifier.accept(signed.bytes, null, TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 0)!!) as Verdict.Accepted).value
  }

  /** Approva un dispositivo nuovo dell'account [nasUser] e restituisce la sua chiave. */
  fun approve(name: String, nasUser: String): Signer {
    val device = FamilyKey.generate(random)
    val request = verified(request(device, name, nasUser))
    commit(authority.approve(state, request, ApprovalProof.TypedCode(request.approvalCode), firstSeenAt = clock.nasNowMillis()!!, admin = false))
    return device
  }

  fun changeStatus(device: Signer, status: DeviceStatus, frozenHeads: List<String> = emptyList()) {
    commit(authority.changeStatus(state, TrustIds.deviceIdOf(device.publicKey), status, frozenHeads))
  }

  private fun commit(draft: RegistryDraft) {
    val result = authority.commit(common, draft)
    check(result is CommitResult.Published) { "registro non pubblicato: $result" }
    state = result.state
  }

  private fun request(device: Signer, name: String, nasUser: String): ByteArray =
    RegistrationRequest.sign(device, familyId, name, "SM-S931B", nasUser, clock.nasNowMillis()!!)

  private fun verified(bytes: ByteArray): RegistrationRequest =
    (RegistrationRequest.verify(bytes, familyId, TrustIds.deviceIdOf(EcPublicKey.fromSpki(bytes.copyOfRange(6, 6 + 91)))) as Verdict.Accepted).value
}
