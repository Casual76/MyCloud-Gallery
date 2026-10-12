package dev.pampa.mycloudgallery.core.family.contract

import dev.pampa.mycloudgallery.core.trust.Verdict

/**
 * L'invito di un familiare, mostrato come QR dall'admin (§2.2): `MCG1:` + base32 di un JSON canonico, al
 * massimo 600 caratteri, campi fissi. **Mai una password** (C8): senza la password dell'account NAS e senza
 * l'approvazione dell'admin un invito fotografato non serve a niente (E-S8).
 *
 * @property familyKeyFingerprint impronta della chiave di famiglia: l'ancora di fiducia del nuovo dispositivo.
 * @property minRegistryVersion il registro piu' vecchio accettabile (anti-rollback).
 * @property nasGuid il GUID del NAS: il nuovo dispositivo non manda la password se la NEGOTIATE ne da' un altro.
 * @property homeUla i prefissi ULA di casa: il controllo "sono a casa" prima di contattare il NAS.
 * @property expiresAtNas scadenza, ora del NAS (24 h dalla creazione).
 */
data class FamilyInvite(
  val familyId: String,
  val familyKeyFingerprint: String,
  val minRegistryVersion: Long,
  val nasIp: String,
  val nasName: String?,
  val nasGuid: String,
  val homeUla: List<String>,
  val commonShare: String,
  val privateShare: String,
  val nasUser: String,
  val deviceName: String,
  val presetName: String?,
  val expiresAtNas: Long,
)

/**
 * La chiave pubblica del dispositivo nuovo, mostrata come QR (`MCGK1:`) per l'approvazione "stessa
 * stanza": l'admin la scansiona invece di digitare il codice.
 *
 * @property publicKeySpki la chiave pubblica (SubjectPublicKeyInfo DER).
 */
class DeviceKeyQr(val familyId: String, val deviceId: String, publicKeySpki: ByteArray) {
  private val spki = publicKeySpki.copyOf()

  val publicKeySpki: ByteArray get() = spki.copyOf()

  override fun equals(other: Any?): Boolean =
    other is DeviceKeyQr && other.familyId == familyId && other.deviceId == deviceId && other.spki.contentEquals(spki)

  override fun hashCode(): Int = (familyId.hashCode() * 31 + deviceId.hashCode()) * 31 + spki.contentHashCode()
}

/**
 * Lo "stato della famiglia" (`MCGS1:`, M7), mostrato da un dispositivo gia' approvato a chi entra senza QR
 * o recupera l'admin: alza la versione minima del registro accettabile.
 */
data class FamilyStateQr(
  val familyId: String,
  val familyKeyFingerprint: String,
  val registryVersion: Long,
  val registryHash: String,
)

/** I prefissi dei QR dell'app e il tetto di lunghezza (§2.2). */
object QrPayloads {
  const val INVITE_PREFIX: String = "MCG1:"
  const val DEVICE_KEY_PREFIX: String = "MCGK1:"
  const val FAMILY_STATE_PREFIX: String = "MCGS1:"
  const val MAX_CHARS: Int = 600

  /** Il testo di un QR di configurazione di WireGuard: da riconoscere per avvisare che e' una password (E-S15). */
  fun looksLikeWireGuardConfig(text: String): Boolean = text.trimStart().startsWith("[Interface]")
}

/**
 * Codifica e decodifica di un QR dell'app (P4: `InviteCodec`, `DeviceKeyQr`, `FamilyStateQr`). Il testo
 * scansionato e' ostile per definizione: [decode] non lancia mai, rifiuta con un motivo di `core-trust`.
 */
interface QrPayloadCodec<T> {
  fun encode(value: T): String

  fun decode(text: String): Verdict<T>
}
