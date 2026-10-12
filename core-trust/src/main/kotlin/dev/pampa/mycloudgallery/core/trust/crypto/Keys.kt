package dev.pampa.mycloudgallery.core.trust.crypto

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.violation
import java.math.BigInteger
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

  /**
   * Verifica una firma. Accetta SOLO la forma canonica ([EcdsaDer.isCanonical]: DER minimo con `s` basso),
   * cosi' a una firma valida non corrisponde una seconda firma valida ottenuta senza chiave (`s → n − s`).
   * Un errore del provider vale "non valida".
   */
  fun verify(message: ByteArray, signature: ByteArray): Boolean {
    if (!EcdsaDer.isCanonical(signature)) return false
    return try {
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

    /**
     * Lancia una violazione [RejectReason.INVALID_FIELD] se non e' uno SPKI P-256 non compresso con un
     * punto della curva: coordinate minori di p e `y² = x³ − 3x + b (mod p)`. Il controllo e' nostro e non
     * del provider, cosi' JVM (test) e Conscrypt (telefono) decidono allo stesso modo e ogni chiave ha una
     * sola codifica, quindi un solo id e un solo codice di approvazione.
     */
    fun fromSpki(spki: ByteArray): EcPublicKey {
      if (spki.size != SPKI_BYTES) violation(RejectReason.INVALID_FIELD, "chiave: lunghezza non valida")
      for (i in P256_SPKI_PREFIX.indices) {
        if (spki[i] != P256_SPKI_PREFIX[i]) violation(RejectReason.INVALID_FIELD, "chiave: non e' P-256")
      }
      val x = BigInteger(1, spki.copyOfRange(P256_SPKI_PREFIX.size, P256_SPKI_PREFIX.size + 32))
      val y = BigInteger(1, spki.copyOfRange(P256_SPKI_PREFIX.size + 32, SPKI_BYTES))
      if (!P256.isOnCurve(x, y)) violation(RejectReason.INVALID_FIELD, "chiave: punto fuori dalla curva")
      return EcPublicKey(spki.copyOf())
    }

    fun fromText(text: String): EcPublicKey = fromSpki(Base32.decodeExact(text, SPKI_BYTES))

    fun fromJca(key: PublicKey): EcPublicKey = fromSpki(key.encoded ?: violation(RejectReason.INVALID_FIELD, "chiave: senza codifica"))
  }
}

/** I parametri di P-256 (FIPS 186-4, D.1.2.3) che servono ai controlli fatti da noi. */
internal object P256 {
  val P: BigInteger = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
  val N: BigInteger = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
  val HALF_N: BigInteger = N.shiftRight(1)
  private val B: BigInteger = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
  private val THREE: BigInteger = BigInteger.valueOf(3)

  fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
    if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) return false
    val left = y.multiply(y).mod(P)
    val right = x.multiply(x).multiply(x).subtract(THREE.multiply(x)).add(B).mod(P)
    return left == right
  }
}

/**
 * La forma canonica delle firme ECDSA del formato: `SEQUENCE { INTEGER r, INTEGER s }` in DER minimo
 * (lunghezze corte, interi positivi senza zeri superflui), `1 ≤ r < n`, `1 ≤ s ≤ n/2` ("s basso").
 *
 * ECDSA ammette due firme valide, `(r, s)` e `(r, n − s)`, per lo stesso messaggio: chi firma normalizza
 * ([normalize]) e chi verifica rifiuta l'altra forma ([isCanonical]). Il Keystore non garantisce `s` basso,
 * quindi si normalizza sempre dopo aver firmato.
 */
object EcdsaDer {

  /** (r, s) di una firma in DER minimo, oppure null. Non controlla `s` basso. */
  internal fun parse(der: ByteArray): Pair<BigInteger, BigInteger>? {
    if (der.size < 8 || der.size > TrustAlgorithms.MAX_SIGNATURE_BYTES) return null
    if (der[0].toInt() != 0x30 || (der[1].toInt() and 0xff) != der.size - 2) return null
    var at = 2
    fun integer(): BigInteger? {
      if (at + 2 > der.size || der[at].toInt() != 0x02) return null
      val length = der[at + 1].toInt() and 0xff
      val start = at + 2
      if (length !in 1..33 || start + length > der.size) return null
      val first = der[start].toInt() and 0xff
      if (first >= 0x80) return null // negativo
      if (length > 1 && first == 0 && (der[start + 1].toInt() and 0x80) == 0) return null // zero superfluo
      at = start + length
      return BigInteger(1, der.copyOfRange(start, start + length))
    }
    val r = integer() ?: return null
    val s = integer() ?: return null
    if (at != der.size) return null
    if (r.signum() <= 0 || r >= P256.N || s.signum() <= 0 || s >= P256.N) return null
    return r to s
  }

  fun isCanonical(der: ByteArray): Boolean {
    val (_, s) = parse(der) ?: return false
    return s <= P256.HALF_N
  }

  /** La stessa firma in forma canonica (`s` basso). Lancia se [der] non e' una firma DER valida. */
  fun normalize(der: ByteArray): ByteArray {
    val (r, s) = requireNotNull(parse(der)) { "Firma DER non valida dal provider." }
    return encode(r, if (s > P256.HALF_N) P256.N.subtract(s) else s)
  }

  internal fun encode(r: BigInteger, s: BigInteger): ByteArray {
    val rb = r.toByteArray()
    val sb = s.toByteArray()
    val body = byteArrayOf(0x02, rb.size.toByte()) + rb + byteArrayOf(0x02, sb.size.toByte()) + sb
    return byteArrayOf(0x30, body.size.toByte()) + body
  }
}

/**
 * Chi firma. La chiave privata non esce mai da qui: per i dispositivi vive nel Keystore
 * ([AndroidKeystoreKeyProvider]), per la chiave di famiglia in [FamilyKey].
 */
interface Signer {
  val publicKey: EcPublicKey

  /** Firma ECDSA P-256/SHA-256 di [message], in forma canonica ([EcdsaDer]). */
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
