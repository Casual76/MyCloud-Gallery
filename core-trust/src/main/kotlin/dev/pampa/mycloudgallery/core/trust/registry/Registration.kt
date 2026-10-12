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
 * possiede quella chiave. Chi e' davvero lo decide l'admin DIGITANDO il [approvalCode] che legge sullo
 * schermo del dispositivo nuovo, o scansionandone la chiave ([ApprovalProof]): un'iniezione dal NAS con un
 * nome rassicurante non basta.
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

  /** Il codice che l'admin digita: 80 bit, `ABCD-EFGH-JKMN-PQRS`. */
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
          nasUser = f.string("nasUser", TrustLimits.MAX_NAS_ACCOUNT_CHARS),
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
 * Il codice di approvazione: i primi 80 bit di SHA-256 della chiave del dispositivo, 16 caratteri. La chiave
 * e' leggibile nell'area comune prima dell'approvazione, quindi chi vuole farsi approvare al posto del
 * dispositivo vero puo' cercare una chiave con lo stesso codice con tutto il tempo che vuole: con 80 bit
 * servono circa 10^24 tentativi (con 50 bit, come nella prima versione, bastavano giorni di GPU: revisione
 * 1.4, M2). In piu' una richiesta si approva solo entro [TrustLimits.MAX_REQUEST_AGE_MILLIS].
 */
object ApprovalCodes {
  private const val CHARS = 16

  fun of(key: EcPublicKey): String {
    val digest = Hashes.sha256("mycloudgallery/approval/v1".toByteArray(), key.encoded)
    return Base32.encode(digest).substring(0, CHARS).uppercase().chunked(4).joinToString("-")
  }

  /** Vero se [typed] (scritto a mano: maiuscole, spazi, trattini, alias O/0 e I/L/1) e' il codice di [key]. */
  fun matches(key: EcPublicKey, typed: String): Boolean =
    Base32.normalizeHumanInput(typed) == of(key).lowercase().replace("-", "")
}

/** La prova che l'admin ha davvero davanti il dispositivo che approva. */
sealed interface ApprovalProof {
  /** Il codice letto sullo schermo del dispositivo nuovo e digitato dall'admin (non confrontato a vista). */
  class TypedCode(val text: String) : ApprovalProof

  /** La chiave intera, letta da un QR mostrato dal dispositivo nuovo (stessa stanza). */
  class ScannedKey(val key: EcPublicKey) : ApprovalProof

  fun proves(request: RegistrationRequest): Boolean = when (this) {
    is TypedCode -> ApprovalCodes.matches(request.key, text)
    is ScannedKey -> key == request.key
  }
}

/**
 * Una richiesta si approva solo se l'admin l'ha vista per la prima volta (ora del NAS, annotata in locale
 * dalla sua app: non `requestedAt`, che scrive il richiedente) da non piu' di 15 minuti. Una richiesta
 * vecchia si rifa': il dispositivo nuovo rigenera la chiave e ripubblica.
 */
object ApprovalWindow {
  fun isOpen(firstSeenAt: Long, nasNow: Long): Boolean =
    nasNow >= firstSeenAt && nasNow - firstSeenAt <= TrustLimits.MAX_REQUEST_AGE_MILLIS
}

/**
 * La forma con cui due nomi da mostrare si confrontano per scoprire i "sosia" (revisione 1.4, B2): NFKC,
 * senza ZWJ ne' selettori di variante, minuscole, spazi compressi. Due richieste (o una richiesta e un
 * dispositivo gia' registrato) con la stessa forma vanno segnalate all'admin.
 */
object LookAlike {
  fun key(name: String): String =
    java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC)
      .filterNot { it.code == 0x200D || it.code in 0xFE00..0xFE0F }
      .lowercase(java.util.Locale.ROOT)
      .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
