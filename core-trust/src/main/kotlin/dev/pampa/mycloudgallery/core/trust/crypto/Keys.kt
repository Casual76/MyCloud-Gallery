package dev.pampa.mycloudgallery.core.trust.crypto

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.violation
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec

/** Gli algoritmi del formato v1: nessuna negoziazione, un algoritmo nuovo vuol dire un formato nuovo. */
object TrustAlgorithms {
  /** ECDSA su P-256 con SHA-256, firma in DER. Keystore e StrongBox lo supportano da Android 6. */
  const val SIGNATURE = "SHA256withECDSA"
  const val KEY_ALGORITHM = "EC"
  const val CURVE = "secp256r1"

  /** La firma DER di P-256 sta tra 8 e 72 byte. */
  const val MAX_SIGNATURE_BYTES = 72
}

object Hashes {
  fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach(md::update)
    return md.digest()
  }

  /** L'impronta usata nei nomi dei file e nei riferimenti: SHA-256 in base32 (52 caratteri). */
  fun sha256Name(bytes: ByteArray): String = Base32.encode(sha256(bytes))

  const val NAME_LENGTH: Int = 52

  fun isHashName(text: String): Boolean = Base32.isCanonical(text, 32)
}

/**
 * Una chiave pubblica ECDSA P-256 nella forma SubjectPublicKeyInfo DER con punto non compresso: 91 byte,
 * di cui i primi 27 fissi. Qualunque altra forma (altra curva, punto compresso, parametri espliciti) e'
 * rifiutata prima di arrivare al provider crittografico.
 */
class EcPublicKey private constructor(private val spki: ByteArray) {

  /** I byte SPKI (copia). */
  val encoded: ByteArray get() = spki.copyOf()

  private val hash: ByteArray = Hashes.sha256(spki)

  /** SHA-256 dello SPKI (copia): identifica la chiave. */
  val keyHash: ByteArray get() = hash.copyOf()

  /** L'impronta da mostrare o confrontare a mano: i primi 130 bit dell'hash, 26 caratteri base32. */
  val fingerprint: String = Base32.encode(hash).substring(0, FINGERPRINT_CHARS)

  /** La forma testuale nel JSON. */
  fun toText(): String = Base32.encode(spki)

  fun toJca(): PublicKey = KeyFactory.getInstance(TrustAlgorithms.KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(spki))

  /** Verifica una firma DER; un errore del provider (firma malformata) vale "non valida". */
  fun verify(message: ByteArray, signature: ByteArray): Boolean =
    try {
      Signature.getInstance(TrustAlgorithms.SIGNATURE).run {
        initVerify(toJca())
        update(message)
        verify(signature)
      }
    } catch (e: SignatureException) {
      false
    } catch (e: java.security.InvalidKeyException) {
      false
    } catch (e: java.security.spec.InvalidKeySpecException) {
      false
    } catch (e: IllegalArgumentException) {
      false
    }

  override fun equals(other: Any?): Boolean = other is EcPublicKey && other.spki.contentEquals(spki)

  override fun hashCode(): Int = spki.contentHashCode()

  override fun toString(): String = "EcPublicKey($fingerprint)"

  companion object {
    const val SPKI_BYTES = 91
    const val FINGERPRINT_CHARS = 26

    private val P256_SPKI_PREFIX = byteArrayOf(
      0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
      0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
    )

    /** Lancia una violazione [RejectReason.INVALID_FIELD] se non e' uno SPKI P-256 non compresso. */
    fun fromSpki(spki: ByteArray): EcPublicKey {
      if (spki.size != SPKI_BYTES) violation(RejectReason.INVALID_FIELD, "chiave: lunghezza non valida")
      for (i in P256_SPKI_PREFIX.indices) {
        if (spki[i] != P256_SPKI_PREFIX[i]) violation(RejectReason.INVALID_FIELD, "chiave: non e' P-256")
      }
      return EcPublicKey(spki.copyOf())
    }

    fun fromText(text: String): EcPublicKey = fromSpki(Base32.decodeExact(text, SPKI_BYTES))

    fun fromJca(key: PublicKey): EcPublicKey = fromSpki(key.encoded ?: violation(RejectReason.INVALID_FIELD, "chiave: senza codifica"))
  }
}

/**
 * Chi firma. La chiave privata non esce mai da qui: per i dispositivi vive nel Keystore
 * ([AndroidKeystoreKeyProvider]), per la chiave di famiglia in [FamilyKey].
 */
interface Signer {
  val publicKey: EcPublicKey

  /** Firma ECDSA P-256/SHA-256 in DER di [message]. */
  fun sign(message: ByteArray): ByteArray
}

/**
 * Le chiavi del dispositivo (`docs/formato-nas.md` §4.2). Implementazione reale:
 * [AndroidKeystoreKeyProvider]; nei test JVM una software equivalente.
 */
interface KeyProvider {
  /** La chiave di firma del dispositivo; la crea al primo uso. Non estraibile. */
  fun deviceSigner(): Signer

  fun hasDeviceKey(): Boolean

  /** Distrugge la chiave (dispositivo segnato come perso, reinstallazione logica). Irreversibile. */
  fun destroyDeviceKey()
}

/**
 * Gli identificatori del formato: 128 bit in base32, 26 caratteri `[0-9a-z]`, sicuri come nomi di file.
 * - id di un dispositivo: i primi 128 bit dell'hash della sua chiave (auto-certificante);
 * - id della famiglia, di un elemento del cestino, di un album: 128 bit casuali.
 */
object TrustIds {
  const val LENGTH = 26

  fun deviceIdOf(key: EcPublicKey): String = Base32.encode(key.keyHash.copyOf(16))

  fun random(random: java.security.SecureRandom = java.security.SecureRandom()): String =
    Base32.encode(ByteArray(16).also(random::nextBytes))

  fun isValid(text: String): Boolean = text.length == LENGTH && Base32.isCanonical(text, 16)
}
