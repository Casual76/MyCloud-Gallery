package dev.pampa.mycloudgallery.core.family.invite

import dev.pampa.mycloudgallery.core.family.contract.DeviceKeyQr
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.QrPayloadCodec
import dev.pampa.mycloudgallery.core.family.contract.QrPayloads
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import java.util.Locale

/**
 * Il formato comune dei tre QR dell'app: `PREFISSO:` + base32 MAIUSCOLO di un JSON canonico (di
 * `core-trust`), al massimo [QrPayloads.MAX_CHARS] caratteri in tutto, campi fissi.
 *
 * Maiuscolo di proposito: prefisso, cifre e lettere maiuscole stanno nella modalita' "alfanumerica" del QR
 * (5,5 bit per carattere invece di 8), cosi' un QR da 600 caratteri resta leggibile a schermo. La lettura
 * accetta anche le minuscole (lo stesso testo digitato a mano) ma niente altro: l'alfabeto e' quello di
 * Crockford, i bit di coda sono nulli, la lunghezza e' esatta, il JSON deve essere canonico e un campo
 * sconosciuto e' un errore (una novita' del formato cambia il prefisso, non aggiunge campi).
 *
 * Il testo scansionato e' ostile per definizione: nessuna funzione qui lancia verso chi legge.
 */
internal object QrText {

  private val ALL_PREFIXES = listOf(QrPayloads.INVITE_PREFIX, QrPayloads.DEVICE_KEY_PREFIX, QrPayloads.FAMILY_STATE_PREFIX)

  /** Profondita' e dimensioni dei JSON dei QR: molto piu' strette dei tetti generali di `core-trust`. */
  private val LIMITS = CanonicalJson.Limits(maxDepth = 3, maxStringChars = 256, maxKeyChars = 8, maxObjectMembers = 16, maxArrayItems = 8)

  fun wrap(prefix: String, payload: JsonObject): String {
    val text = prefix + Base32.encode(CanonicalJson.encode(payload)).uppercase(Locale.ROOT)
    require(text.length <= QrPayloads.MAX_CHARS) { "QR troppo lungo" }
    return text
  }

  fun <T> unwrap(prefix: String, text: String, read: (Fields) -> T): Verdict<T> =
    try {
      if (text.length > QrPayloads.MAX_CHARS) throw TrustViolation(RejectReason.TOO_LARGE, "QR troppo lungo")
      if (!text.startsWith(prefix)) {
        val other = ALL_PREFIXES.any { text.startsWith(it) }
        throw TrustViolation(if (other) RejectReason.WRONG_TYPE else RejectReason.MALFORMED, "QR: prefisso")
      }
      val body = text.substring(prefix.length)
      if (body.isEmpty() || !body.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }) {
        throw TrustViolation(RejectReason.MALFORMED, "QR: corpo")
      }
      val bytes = Base32.decode(body.lowercase(Locale.ROOT))
      Verdict.Accepted(Fields.read(CanonicalJson.parseCanonical(bytes, LIMITS), read))
    } catch (v: TrustViolation) {
      Verdict.Rejected(v.reason)
    } catch (e: RuntimeException) {
      Verdict.Rejected(RejectReason.MALFORMED)
    }

  /** Una violazione in scrittura e' un errore di chi chiama: eccezione di programmazione, non un rifiuto. */
  inline fun <T> writing(what: String, block: () -> T): T =
    try {
      block()
    } catch (v: TrustViolation) {
      throw IllegalArgumentException("$what non valido", v)
    }
}

/**
 * Il QR d'invito (`MCG1:`), mostrato dall'admin al familiare (§2.2). Chiavi del JSON: `fam` id della famiglia,
 * `fp` impronta della chiave di famiglia, `min` versione minima del registro, `ip`/`nm` indirizzo e nome del NAS,
 * `gd` GUID del NAS, `ula` prefissi di casa, `cs`/`ps` area comune e privata, `usr` account NAS, `dev` nome del
 * dispositivo, `pre` preset (facoltativo), `exp` scadenza (ora del NAS). Chiavi corte per stare nei 600 caratteri.
 *
 * Mai una password (C8, E-S8). La scadenza non si controlla qui ([decode] non ha un orologio): vedi
 * [InviteExpiry] e [InviteScanCheck].
 */
object InviteCodec : QrPayloadCodec<FamilyInvite> {

  override fun encode(value: FamilyInvite): String =
    QrText.writing("invito") {
      InviteRules.check(value)
      QrText.wrap(
        QrPayloads.INVITE_PREFIX,
        jsonObject(
          "fam" to value.familyId.json(),
          "fp" to value.familyKeyFingerprint.json(),
          "min" to value.minRegistryVersion.json(),
          "ip" to value.nasIp.json(),
          "nm" to value.nasName?.json(),
          "gd" to value.nasGuid.json(),
          "ula" to value.homeUla.map { it.json() }.json(),
          "cs" to value.commonShare.json(),
          "ps" to value.privateShare.json(),
          "usr" to value.nasUser.json(),
          "dev" to value.deviceName.json(),
          "pre" to value.presetName?.json(),
          "exp" to value.expiresAtNas.json(),
        ),
      )
    }

  override fun decode(text: String): Verdict<FamilyInvite> =
    QrText.unwrap(QrPayloads.INVITE_PREFIX, text) { f ->
      InviteRules.check(
        FamilyInvite(
          familyId = f.string("fam", TrustIds.LENGTH),
          familyKeyFingerprint = f.string("fp", EcPublicKey.FINGERPRINT_CHARS),
          minRegistryVersion = f.long("min", 0..TrustLimits.MAX_VERSION),
          nasIp = f.string("ip", MAX_IP_CHARS),
          nasName = f.optString("nm", InviteRules.MAX_NAME_CHARS),
          nasGuid = f.string("gd", InviteRules.MAX_GUID_CHARS),
          homeUla = f.array("ula", InviteRules.MAX_ULA).map { Fields.asString(it, MAX_ULA_CHARS) },
          commonShare = f.string("cs", TrustLimits.MAX_SHARE_NAME_CHARS),
          privateShare = f.string("ps", TrustLimits.MAX_SHARE_NAME_CHARS),
          nasUser = f.string("usr", TrustLimits.MAX_NAS_ACCOUNT_CHARS),
          deviceName = f.string("dev", InviteRules.MAX_NAME_CHARS),
          presetName = f.optString("pre", InviteRules.MAX_PRESET_CHARS),
          expiresAtNas = f.time("exp"),
        ),
      )
    }

  private const val MAX_IP_CHARS = 45
  private const val MAX_ULA_CHARS = 64
}

/**
 * Il QR della chiave del dispositivo (`MCGK1:`), mostrato dal dispositivo nuovo all'admin per l'approvazione
 * "stessa stanza" (§2.2): al posto del codice da digitare, l'admin scansiona la chiave intera. Chiavi: `fam`,
 * `dev`, `key` (la chiave pubblica nella forma testuale di `core-trust`).
 *
 * L'id del dispositivo e' auto-certificante: deve coincidere con l'impronta della chiave, e la chiave deve
 * essere un punto vero della curva P-256 (altrimenti [decode] la rifiuta).
 */
object DeviceKeyCodec : QrPayloadCodec<DeviceKeyQr> {

  override fun encode(value: DeviceKeyQr): String =
    QrText.writing("QR della chiave") {
      val key = checked(value.familyId, value.deviceId, value.publicKeySpki)
      QrText.wrap(
        QrPayloads.DEVICE_KEY_PREFIX,
        jsonObject("fam" to value.familyId.json(), "dev" to value.deviceId.json(), "key" to key.toText().json()),
      )
    }

  override fun decode(text: String): Verdict<DeviceKeyQr> =
    QrText.unwrap(QrPayloads.DEVICE_KEY_PREFIX, text) { f ->
      val familyId = f.string("fam", TrustIds.LENGTH)
      val deviceId = f.string("dev", TrustIds.LENGTH)
      val key = EcPublicKey.fromText(f.string("key", Base32.encodedLength(EcPublicKey.SPKI_BYTES)))
      DeviceKeyQr(familyId, deviceId, checked(familyId, deviceId, key.encoded).encoded)
    }

  private fun checked(familyId: String, deviceId: String, spki: ByteArray): EcPublicKey {
    requireField(TrustIds.isValid(familyId), "QR chiave: id di famiglia")
    val key = EcPublicKey.fromSpki(spki)
    requireField(TrustIds.isValid(deviceId) && TrustIds.deviceIdOf(key) == deviceId, "QR chiave: id e chiave non corrispondono")
    return key
  }
}

/** La chiave pubblica del QR, pronta per `ApprovalProof.ScannedKey`. Lancia solo se il QR non e' passato da [DeviceKeyCodec]. */
fun DeviceKeyQr.toPublicKey(): EcPublicKey = EcPublicKey.fromSpki(publicKeySpki)

/**
 * Il QR "stato della famiglia" (`MCGS1:`, M7), mostrato da qualunque dispositivo approvato a chi entra senza QR
 * o recupera l'admin: alza la versione minima del registro accettabile. Chiavi: `fam`, `fp` (impronta della
 * chiave che firma la versione corrente), `ver` (la versione), `hash` (impronta del contenuto di quella versione).
 */
object FamilyStateCodec : QrPayloadCodec<FamilyStateQr> {

  override fun encode(value: FamilyStateQr): String =
    QrText.writing("QR di stato") {
      checked(value)
      QrText.wrap(
        QrPayloads.FAMILY_STATE_PREFIX,
        jsonObject(
          "fam" to value.familyId.json(),
          "fp" to value.familyKeyFingerprint.json(),
          "ver" to value.registryVersion.json(),
          "hash" to value.registryHash.json(),
        ),
      )
    }

  override fun decode(text: String): Verdict<FamilyStateQr> =
    QrText.unwrap(QrPayloads.FAMILY_STATE_PREFIX, text) { f ->
      checked(
        FamilyStateQr(
          familyId = f.string("fam", TrustIds.LENGTH),
          familyKeyFingerprint = f.string("fp", EcPublicKey.FINGERPRINT_CHARS),
          registryVersion = f.long("ver", 1..TrustLimits.MAX_VERSION),
          registryHash = f.string("hash", Hashes.NAME_LENGTH),
        ),
      )
    }

  private fun checked(value: FamilyStateQr): FamilyStateQr {
    requireField(Base32.normalizeHumanInput(value.familyKeyFingerprint) == value.familyKeyFingerprint, "QR di stato: impronta non canonica")
    requireField(value.registryVersion in 1..TrustLimits.MAX_VERSION, "QR di stato: versione")
    requireField(
      TrustAnchor.create(value.familyId, value.familyKeyFingerprint, value.registryVersion) != null &&
        value.familyKeyFingerprint.length == EcPublicKey.FINGERPRINT_CHARS,
      "QR di stato: ancora",
    )
    requireField(Hashes.isHashName(value.registryHash), "QR di stato: impronta del registro")
    return value
  }
}

/** Dal registro accettato al QR di stato, e dal QR all'ancora di fiducia. */
object FamilyStateQrs {

  /**
   * Lo stato da mostrare adesso, oppure null mentre la versione corrente e' solo l'annuncio di una rotazione
   * (`RegistryState.toAnchor` e' null: un QR li' ancorerebbe i dispositivi nuovi alla chiave vecchia, N4).
   */
  fun of(state: RegistryState): FamilyStateQr? {
    val anchor = state.toAnchor() ?: return null
    return FamilyStateQr(
      familyId = anchor.familyId,
      familyKeyFingerprint = anchor.familyKeyFingerprint,
      registryVersion = anchor.minRegistryVersion,
      registryHash = state.contentHash,
    )
  }

  /** L'ancora che il QR di stato dichiara: id, impronta e versione minima. */
  fun anchorOf(qr: FamilyStateQr): TrustAnchor? = TrustAnchor.create(qr.familyId, qr.familyKeyFingerprint, qr.registryVersion)
}
