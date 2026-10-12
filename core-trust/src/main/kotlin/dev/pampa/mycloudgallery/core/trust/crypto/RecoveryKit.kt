package dev.pampa.mycloudgallery.core.trust.crypto

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.verdictOf
import dev.pampa.mycloudgallery.core.trust.violation
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Il codice di recupero: 128 bit casuali generati dall'app (mai scelti dall'utente: niente password
 * deboli) piu' 10 bit di controllo per scoprire gli errori di battitura prima di provare a decifrare.
 * Si stampa o si scrive su carta come 7 gruppi di 4 caratteri: `ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-2345`.
 */
class RecoveryCode private constructor(private val secret: ByteArray) {

  internal fun secretCopy(): ByteArray = secret.copyOf()

  /** La forma da stampare. Va mostrata solo nello schermo protetto dell'onboarding (FLAG_SECURE). */
  fun toDisplayString(): String =
    (Base32.encode(secret) + checkChars(secret)).uppercase().chunked(4).joinToString("-")

  /** Mai il codice nei log o nei messaggi d'errore. */
  override fun toString(): String = "RecoveryCode([nascosto])"

  companion object {
    const val SECRET_BYTES = 16
    private const val SECRET_CHARS = 26
    private const val TOTAL_CHARS = 28

    fun generate(random: SecureRandom = SecureRandom()): RecoveryCode =
      RecoveryCode(ByteArray(SECRET_BYTES).also(random::nextBytes))

    /**
     * Legge un codice scritto a mano (spazi, trattini, maiuscole e alias O/0, I/L/1 ammessi). Null se la
     * forma o i caratteri di controllo non tornano: errore di battitura.
     */
    fun parse(text: String): RecoveryCode? {
      val normalized = Base32.normalizeHumanInput(text)
      if (normalized.length != TOTAL_CHARS) return null
      val secretText = normalized.substring(0, SECRET_CHARS)
      if (!Base32.isCanonical(secretText, SECRET_BYTES)) return null
      val secret = Base32.decodeExact(secretText, SECRET_BYTES)
      if (checkChars(secret) != normalized.substring(SECRET_CHARS)) return null
      return RecoveryCode(secret)
    }

    private fun checkChars(secret: ByteArray): String {
      val h = Hashes.sha256("mycloudgallery/recovery-check/v1".toByteArray(), secret)
      val tenBits = ((h[0].toInt() and 0xff) shl 2) or ((h[1].toInt() and 0xff) ushr 6)
      return Base32.encode(byteArrayOf((tenBits ushr 2).toByte(), ((tenBits and 0x3) shl 6).toByte())).substring(0, 2)
    }
  }
}

/** Il contenuto aperto di un kit. */
class OpenedRecoveryKit(val familyId: String, val familyKey: FamilyKey, val createdAt: Long)

/**
 * Il kit di recupero della chiave di famiglia (`docs/formato-nas.md` §4.5): la chiave privata cifrata con
 * AES-256-GCM sotto una chiave derivata (HKDF-SHA256) dal [RecoveryCode]. Il file cifrato sta nell'area
 * privata dell'admin sul NAS; il codice sta su carta. Servono entrambi.
 *
 * Il codice ha 128 bit di entropia: una derivazione lenta (PBKDF2, scrypt) non aggiungerebbe nulla.
 */
object RecoveryKit {

  private val MAGIC = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'K'.code.toByte())
  private const val FAMILY_ID_BYTES = 16
  private const val SALT_BYTES = 16
  private const val NONCE_BYTES = 12
  private const val TAG_BITS = 128
  private const val HEADER_BYTES = 4 + 1 + FAMILY_ID_BYTES + 32 + 8 + SALT_BYTES + NONCE_BYTES

  fun seal(
    familyKey: FamilyKey,
    familyId: String,
    code: RecoveryCode,
    createdAt: Long,
    random: SecureRandom = SecureRandom(),
  ): ByteArray {
    val familyIdRaw = Base32.decodeExact(familyId, FAMILY_ID_BYTES)
    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    val header = ByteBuffer.allocate(HEADER_BYTES)
      .put(MAGIC).put(TrustFormat.CURRENT_VERSION.toByte()).put(familyIdRaw).put(familyKey.publicKey.keyHash)
      .putLong(createdAt).put(salt).put(nonce).array()
    val pkcs8 = familyKey.exportPkcs8()
    val plain = KeyMaterial.pack(familyKey.publicKey, pkcs8)
    val kek = deriveKey(code, salt, familyIdRaw)
    try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
      cipher.updateAAD(header)
      return header + cipher.doFinal(plain)
    } finally {
      pkcs8.fill(0)
      plain.fill(0)
      kek.fill(0)
    }
  }

  /**
   * Apre un kit. [expectedFamilyId] e [expectedKeyFingerprint] vengono da una fonte INDIPENDENTE dal NAS (il
   * foglio del codice, che li riporta, o il QR "stato della famiglia" di un dispositivo approvato): un kit
   * con un'altra chiave messo nell'area dell'admin da chi conosce il codice non passa (revisione 1.4, M7).
   *
   * Rifiuti: [RejectReason.MALFORMED] (struttura), [RejectReason.NEWER_FORMAT], [RejectReason.WRONG_FAMILY],
   * [RejectReason.SIGNER_NOT_APPROVED] (chiave diversa da quella attesa), [RejectReason.BAD_SIGNATURE]
   * (codice sbagliato o file manomesso: GCM non autentica), [RejectReason.HASH_MISMATCH] (la chiave dentro
   * non e' quella dichiarata).
   */
  fun open(
    blob: ByteArray,
    code: RecoveryCode,
    expectedFamilyId: String,
    expectedKeyFingerprint: String,
  ): Verdict<OpenedRecoveryKit> = verdictOf {
    ensure(blob.size in (HEADER_BYTES + 16)..TrustLimits.MAX_RECOVERY_KIT_BYTES, RejectReason.MALFORMED, "kit: dimensione")
    ensure(blob.copyOf(4).contentEquals(MAGIC), RejectReason.MALFORMED, "kit: firma del formato")
    val version = blob[4].toInt() and 0xff
    if (version > TrustFormat.CURRENT_VERSION) violation(RejectReason.NEWER_FORMAT, "kit: formato piu' nuovo")
    ensure(TrustFormat.isReadable(version), RejectReason.MALFORMED, "kit: versione")
    val buffer = ByteBuffer.wrap(blob, 5, HEADER_BYTES - 5)
    val familyIdRaw = ByteArray(FAMILY_ID_BYTES).also { buffer.get(it) }
    val keyHash = ByteArray(32).also { buffer.get(it) }
    val createdAt = buffer.long
    val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
    val nonce = ByteArray(NONCE_BYTES).also { buffer.get(it) }
    val familyId = Base32.encode(familyIdRaw)
    if (expectedFamilyId != familyId) violation(RejectReason.WRONG_FAMILY, "kit: altra famiglia")
    val declaredFingerprint = Base32.encode(keyHash).substring(0, EcPublicKey.FINGERPRINT_CHARS)
    if (Base32.normalizeHumanInput(expectedKeyFingerprint) != declaredFingerprint) {
      violation(RejectReason.SIGNER_NOT_APPROVED, "kit: chiave diversa da quella attesa")
    }

    val kek = deriveKey(code, salt, familyIdRaw)
    val plain = try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
      cipher.updateAAD(blob, 0, HEADER_BYTES)
      cipher.doFinal(blob, HEADER_BYTES, blob.size - HEADER_BYTES)
    } catch (e: AEADBadTagException) {
      violation(RejectReason.BAD_SIGNATURE, "kit: codice sbagliato o file alterato")
    } finally {
      kek.fill(0)
    }
    try {
      val (publicKey, pkcs8) = KeyMaterial.unpack(plain)
      try {
        ensure(publicKey.keyHash.contentEquals(keyHash), RejectReason.HASH_MISMATCH, "kit: chiave diversa da quella dichiarata")
        OpenedRecoveryKit(familyId, FamilyKey.restore(pkcs8, publicKey), createdAt)
      } finally {
        pkcs8.fill(0)
      }
    } finally {
      plain.fill(0)
    }
  }

  private fun deriveKey(code: RecoveryCode, salt: ByteArray, familyIdRaw: ByteArray): ByteArray {
    val ikm = code.secretCopy()
    try {
      return Hkdf.sha256(ikm, salt, "mycloudgallery/recovery-kit/v1".toByteArray() + familyIdRaw, 32)
    } finally {
      ikm.fill(0)
    }
  }
}

/** HKDF (RFC 5869) con HMAC-SHA256: solo quello che serve al kit. */
internal object Hkdf {
  fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..255 * 32)
    val extract = Mac.getInstance("HmacSHA256")
    extract.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = extract.doFinal(ikm)
    val expand = Mac.getInstance("HmacSHA256")
    expand.init(SecretKeySpec(prk, "HmacSHA256"))
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < length) {
      expand.update(previous)
      expand.update(info)
      expand.update(counter.toByte())
      previous = expand.doFinal()
      val n = minOf(previous.size, length - offset)
      System.arraycopy(previous, 0, out, offset, n)
      offset += n
      counter++
    }
    prk.fill(0)
    return out
  }
}

/** Chiave pubblica + PKCS#8, con lunghezze a 16 bit: il contenuto cifrato del kit e della cassaforte. */
internal object KeyMaterial {
  fun pack(publicKey: EcPublicKey, pkcs8: ByteArray): ByteArray {
    val spki = publicKey.encoded
    return ByteBuffer.allocate(4 + spki.size + pkcs8.size)
      .putShort(spki.size.toShort()).put(spki).putShort(pkcs8.size.toShort()).put(pkcs8).array()
  }

  fun unpack(plain: ByteArray): Pair<EcPublicKey, ByteArray> {
    val buffer = ByteBuffer.wrap(plain)
    ensure(buffer.remaining() >= 2, RejectReason.MALFORMED, "materiale: troncato")
    val spkiLength = buffer.short.toInt() and 0xffff
    ensure(spkiLength == EcPublicKey.SPKI_BYTES && buffer.remaining() >= spkiLength + 2, RejectReason.MALFORMED, "materiale: troncato")
    val spki = ByteArray(spkiLength).also { buffer.get(it) }
    val pkcs8Length = buffer.short.toInt() and 0xffff
    ensure(pkcs8Length in 1..TrustLimits.MAX_PKCS8_BYTES && buffer.remaining() == pkcs8Length, RejectReason.MALFORMED, "materiale: lunghezza")
    val pkcs8 = ByteArray(pkcs8Length).also { buffer.get(it) }
    return EcPublicKey.fromSpki(spki) to pkcs8
  }
}
