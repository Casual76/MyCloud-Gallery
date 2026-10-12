package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.envelope.SignedEnvelope
import dev.pampa.mycloudgallery.core.trust.violation

/** Un dato firmato da un dispositivo, con il firmatario gia' giudicato fidato. */
internal class DeviceSigned(val envelope: SignedEnvelope, val signer: DeviceEntry)

/**
 * La regola unica per i dati firmati dai dispositivi (`docs/formato-nas.md` §4.4, E-I15, E-S14):
 *
 * - chiave assente dal registro (sconosciuta o ancora in attesa di approvazione) → rifiuto;
 * - dispositivo revocato o perso → rifiuto, qualunque data dichiari il dato: un dispositivo
 *   compromesso potrebbe retrodatare, quindi niente "valido fino a";
 * - dispositivo dismesso → valgono solo le teste congelate dall'admin al momento del ritiro;
 * - approvato → accettato (poi ogni tipo controlla posto, famiglia e anti-rollback).
 */
internal class DeviceTrust(val state: RegistryState) {

  val familyId: String get() = state.registry.familyId

  fun open(bytes: ByteArray, type: DocType): DeviceSigned {
    val envelope = Envelope.open(bytes, type)
    val signer = state.registry.deviceByKey(envelope.signer)
      ?: violation(RejectReason.SIGNER_NOT_APPROVED, "dato di un dispositivo non approvato")
    when (signer.status) {
      DeviceStatus.APPROVED -> Unit
      DeviceStatus.RETIRED -> ensure(
        type == DocType.DEVICE_HEAD && envelope.fileHash in signer.frozenHeads,
        RejectReason.SIGNER_RETIRED,
        "dato di un dispositivo dismesso",
      )
      DeviceStatus.REVOKED, DeviceStatus.LOST -> violation(RejectReason.SIGNER_REVOKED, "dato di un dispositivo revocato")
    }
    return DeviceSigned(envelope, signer)
  }

  /** I campi comuni a tutti i dati dei dispositivi: famiglia e dispositivo devono tornare. */
  fun checkCommon(f: Fields, signed: DeviceSigned) {
    ensure(f.string("family", TrustIds.LENGTH) == familyId, RejectReason.WRONG_FAMILY, "dato di un'altra famiglia")
    ensure(f.string("device", TrustIds.LENGTH) == signed.signer.id, RejectReason.WRONG_LOCATION, "dato attribuito a un altro dispositivo")
  }
}
