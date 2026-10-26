package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
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
import java.util.Locale

// Fase 7 (P9, D22; integrazione 26/10): la FAMIGLIA VERA per le sonde degli album condivisi veri (P2) e della condivisione (P4). Un registro firmato
// con la chiave di famiglia, i dispositivi approvati (o revocati) con le loro chiavi vere: lo stesso che usano i test di `core-albums`
// (`AlbumTestFamily`), qui perche' i loro attrezzi stanno nei test di quel modulo e `:app` non li vede. La share dell'area comune e' in memoria.

/** Una share in memoria con la semantica di [NasFileSystem] che serve al registro (lettura, elenco, pubblicazione esclusiva). */
internal class MiniNas(override val shareName: String) : NasFileSystem {
  private val lock = Any()
  private val files = LinkedHashMap<String, Pair<String, ByteArray>>()

  override fun list(dir: SharePath, limit: Int): NasListing = synchronized(lock) {
    val prefix = dir.value.lowercase(Locale.ROOT) + "/"
    val names = files.values.map { it.first }.filter { it.lowercase(Locale.ROOT).startsWith(prefix) }
      .map { it.substring(prefix.length).substringBefore('/') }.distinct()
    NasListing(names.take(limit), names.size > limit)
  }

  override fun read(path: SharePath, maxBytes: Int): NasRead = synchronized(lock) {
    val file = files[path.value.lowercase(Locale.ROOT)] ?: return NasRead.NotFound
    if (file.second.size > maxBytes) NasRead.TooLarge else NasRead.Data(file.second.copyOf())
  }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = synchronized(lock) {
    val key = path.value.lowercase(Locale.ROOT)
    if (key in files) return PublishResult.ALREADY_EXISTS
    files[key] = path.value to bytes.copyOf()
    PublishResult.CREATED
  }
}

/** Un dispositivo della famiglia: la sua chiave e l'account (il `nasUser` del registro). */
internal class RealDevice(val signer: Signer, val account: String) {
  val id: String get() = TrustIds.deviceIdOf(signer.publicKey)

  override fun toString(): String = "RealDevice($account)"
}

/**
 * Una famiglia vera (chiave di famiglia, registro firmato) con quattro dispositivi: il mio telefono ([me], account `test`), un secondo mio
 * telefono poi revocato ([myOldPhone], per "Ripristina gli elementi di un dispositivo revocato", E-SH20), quello di un familiare ([other], `test2`) e uno
 * ancora piu' in la ([third], `test3`). Si costruisce una volta per processo: non dipende dal mondo.
 */
internal class RealFamily {
  private val common = MiniNas(HiddenWorldBuilder.COMMON_SHARE)
  private val familyKey = FamilyKey.generate()
  private val familyId: String = TrustIds.random()
  private val nowMs = 1_790_000_000_000L
  private val authority = FamilyAuthority(familyKey) { nowMs }

  @Volatile
  lateinit var state: RegistryState
    private set

  init {
    val founder = verified(request(FamilyKey.generate(), "Telefono admin", "admin"))
    val signed = authority.found(familyId, FamilyConfig(nasHost = "192.168.178.58", commonShare = common.shareName), founder)
    check(FamilyAuthority.publishFounding(common, signed) == PublishResult.CREATED)
    state = (RegistryVerifier.accept(signed.bytes, null, TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 0)!!) as Verdict.Accepted).value
  }

  val me: RealDevice = approve("Il mio telefono", "test")
  val myOldPhone: RealDevice = approve("Il mio vecchio telefono", "test").also { revoke(it) }
  val other: RealDevice = approve("Telefono di test2", "test2")
  val third: RealDevice = approve("Telefono di test3", "test3")

  private fun approve(name: String, nasUser: String): RealDevice {
    val device = FamilyKey.generate()
    val request = verified(request(device, name, nasUser))
    commit(authority.approve(state, request, ApprovalProof.TypedCode(request.approvalCode), firstSeenAt = nowMs, admin = false))
    return RealDevice(device, nasUser)
  }

  private fun revoke(device: RealDevice) {
    commit(authority.changeStatus(state, device.id, DeviceStatus.REVOKED, emptyList()))
  }

  private fun commit(draft: RegistryDraft) {
    val result = authority.commit(common, draft)
    check(result is CommitResult.Published) { "registro non pubblicato: $result" }
    state = result.state
  }

  private fun request(device: Signer, name: String, nasUser: String): ByteArray = RegistrationRequest.sign(device, familyId, name, "SM-S931B", nasUser, nowMs)

  private fun verified(bytes: ByteArray): RegistrationRequest =
    (RegistrationRequest.verify(bytes, familyId, TrustIds.deviceIdOf(EcPublicKey.fromSpki(bytes.copyOfRange(6, 6 + 91)))) as Verdict.Accepted).value

  companion object {
    /** Una sola per processo: le chiavi costano e il registro non dipende dal mondo casuale. */
    val shared: RealFamily by lazy { RealFamily() }
  }
}
