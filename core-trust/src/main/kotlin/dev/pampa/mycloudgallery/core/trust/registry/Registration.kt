package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.verdictOf

/**
 * La richiesta di registrazione di un dispositivo nuovo (`docs/formato-nas.md` §4.4), scritta in
 * `family/requests/<id>` e firmata dalla chiave del dispositivo stesso: prova soltanto che chi la scrive
 * possiede quella chiave. Chi e' davvero lo decide l'admin confrontando il [approvalCode] mostrato sul
 * dispositivo nuovo (o scansionandolo): un'iniezione dal NAS con un nome rassicurante non basta.
 */
data class RegistrationRequest(
  val familyId: String,
  val deviceId: String,
  val key: EcPublicKey,
  val name: String,
  val model: String,
  val nasUser: String,
  val requestedAt: Long,
) {
  init {
    ensure(TrustIds.isValid(familyId), RejectReason.INVALID_FIELD, "richiesta: id di famiglia")
    ensure(TrustIds.deviceIdOf(key) == deviceId, RejectReason.INVALID_FIELD, "richiesta: id e chiave non corrispondono")
    TextRules.displayText(name, TrustLimits.MAX_DISPLAY_TEXT_CHARS)
    TextRules.displayText(model, TrustLimits.MAX_DISPLAY_TEXT_CHARS)
    TextRules.nasAccount(nasUser)
  }

  /** Il codice da confrontare a voce o a vista: 50 bit, `ABCDE-FGHJK`. */
  val approvalCode: String get() = ApprovalCodes.of(key)

  companion object {
    /** Crea e firma la richiesta di QUESTO dispositivo. */
    fun sign(signer: Signer, familyId: String, name: String, model: String, nasUser: String, requestedAt: Long): ByteArray {
      val request = RegistrationRequest(familyId, TrustIds.deviceIdOf(signer.publicKey), signer.publicKey, name, model, nasUser, requestedAt)
      val payload = jsonObject(
        "family" to request.familyId.json(),
        "device" to request.deviceId.json(),
        "key" to request.key.toText().json(),
        "name" to request.name.json(),
        "model" to request.model.json(),
        "nasUser" to request.nasUser.json(),
        "requestedAt" to request.requestedAt.json(),
      )
      return Envelope.seal(DocType.REGISTRATION_REQUEST, payload, signer)
    }

    /**
     * Verifica una richiesta letta da `family/requests/<fileName>`: busta e firma integre, chiave nel
     * payload uguale a quella che firma, id derivato dalla chiave e uguale al nome del file, famiglia giusta.
     */
    fun verify(bytes: ByteArray, familyId: String, fileName: String): Verdict<RegistrationRequest> = verdictOf {
      val envelope = Envelope.open(bytes, DocType.REGISTRATION_REQUEST)
      val request = Fields.read(envelope.payloadJson()) { f ->
        RegistrationRequest(
          familyId = f.string("family", TrustIds.LENGTH),
          deviceId = f.string("device", TrustIds.LENGTH),
          key = EcPublicKey.fromText(f.string("key", Base32.encodedLength(EcPublicKey.SPKI_BYTES))),
          name = f.string("name", TrustLimits.MAX_DISPLAY_TEXT_CHARS),
          model = f.string("model", TrustLimits.MAX_DISPLAY_TEXT_CHARS),
          nasUser = f.string("nasUser", 64),
          requestedAt = f.time("requestedAt"),
        )
      }
      ensure(request.key == envelope.signer, RejectReason.BAD_SIGNATURE, "richiesta: firmata da un'altra chiave")
      ensure(request.familyId == familyId, RejectReason.WRONG_FAMILY, "richiesta: altra famiglia")
      ensure(request.deviceId == fileName, RejectReason.WRONG_LOCATION, "richiesta: nome del file")
      request
    }
  }
}

/**
 * Il codice di approvazione: i primi 50 bit di SHA-256 della chiave del dispositivo. Per farsi approvare
 * al posto del dispositivo vero, un attaccante dovrebbe trovare una chiave con lo stesso codice: circa
 * 2^50 tentativi.
 */
object ApprovalCodes {
  fun of(key: EcPublicKey): String {
    val digest = Hashes.sha256("mycloudgallery/approval/v1".toByteArray(), key.encoded)
    return Base32.encode(digest).substring(0, 10).uppercase().chunked(5).joinToString("-")
  }
}
