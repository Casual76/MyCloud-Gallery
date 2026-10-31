package dev.pampa.mycloudgallery.core.release.codec

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

// LA FIRMA DEL MANIFEST (docs/fase10-progetto.md D29, D31; docs/formato-nas.md §4.1 "Forma canonica delle firme", §15). ECDSA P-256 su
// SHA-256 con `java.security`, nessuna libreria. Come per i file della famiglia (core-trust, `EcdsaDer`), ma scritto qui perche' core-release
// vede solo le fondamenta: (1) la firma si accetta solo in DER minimo con `s` basso, cosi' una busta firmata ha **una sola** firma valida
// (`(r, n - s)` si otterrebbe senza chiave); (2) una chiave pubblica fissata si accetta solo come SubjectPublicKeyInfo P-256 non compresso
// (91 byte) con un punto della curva, controllato da noi e non dal provider: JVM dei test e telefono decidono allo stesso modo.

internal object EcdsaP256 {
  const val SIGNATURE: String = "SHA256withECDSA"
  const val SPKI_BYTES: Int = 91
  const val MIN_SIGNATURE_BYTES: Int = 8
  const val MAX_SIGNATURE_BYTES: Int = 72

  val P: BigInteger = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
  val N: BigInteger = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
  val HALF_N: BigInteger = N.shiftRight(1)
  val B: BigInteger = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
  private val THREE: BigInteger = BigInteger.valueOf(3)

  /** I primi 27 byte fissi di uno SPKI P-256 con punto non compresso (`04`). */
  val SPKI_PREFIX: ByteArray = byteArrayOf(
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
    0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
  )

  fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
    if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) return false
    val left = y.multiply(y).mod(P)
    val right = x.multiply(x).multiply(x).subtract(THREE.multiply(x)).add(B).mod(P)
    return left == right
  }

  /** Vero se [spki] e' uno SPKI P-256 non compresso con un punto della curva (B3 del formato: una sola codifica per chiave). */
  fun isValidSpki(spki: ByteArray): Boolean {
    if (spki.size != SPKI_BYTES) return false
    for (i in SPKI_PREFIX.indices) if (spki[i] != SPKI_PREFIX[i]) return false
    val x = BigInteger(1, spki.copyOfRange(SPKI_PREFIX.size, SPKI_PREFIX.size + 32))
    val y = BigInteger(1, spki.copyOfRange(SPKI_PREFIX.size + 32, SPKI_BYTES))
    return isOnCurve(x, y)
  }

  fun publicKey(spki: ByteArray): PublicKey {
    require(isValidSpki(spki)) { "Chiave pubblica non P-256." }
    return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
  }

  /** (r, s) di una firma in DER minimo, oppure null. Non controlla `s` basso. */
  fun parse(der: ByteArray): Pair<BigInteger, BigInteger>? {
    if (der.size < MIN_SIGNATURE_BYTES || der.size > MAX_SIGNATURE_BYTES) return null
    if (der[0].toInt() != 0x30 || (der[1].toInt() and 0xff) != der.size - 2) return null
    var at = 2
    fun integer(): BigInteger? {
      if (at + 2 > der.size || der[at].toInt() != 0x02) return null
      val length = der[at + 1].toInt() and 0xff
      val start = at + 2
      if (length !in 1..33 || start + length > der.size) return null
      val first = der[start].toInt() and 0xff
      if (first >= 0x80) return null
      if (length > 1 && first == 0 && (der[start + 1].toInt() and 0x80) == 0) return null
      at = start + length
      return BigInteger(1, der.copyOfRange(start, start + length))
    }
    val r = integer() ?: return null
    val s = integer() ?: return null
    if (at != der.size) return null
    if (r.signum() <= 0 || r >= N || s.signum() <= 0 || s >= N) return null
    return r to s
  }

  fun isCanonical(der: ByteArray): Boolean {
    val (_, s) = parse(der) ?: return false
    return s <= HALF_N
  }

  /** La stessa firma con `s` basso (chi firma normalizza sempre: il provider non lo garantisce). */
  fun normalize(der: ByteArray): ByteArray {
    val (r, s) = requireNotNull(parse(der)) { "Firma DER non valida dal provider." }
    return encode(r, if (s > HALF_N) N.subtract(s) else s)
  }

  fun encode(r: BigInteger, s: BigInteger): ByteArray {
    val rb = r.toByteArray()
    val sb = s.toByteArray()
    val body = byteArrayOf(0x02, rb.size.toByte()) + rb + byteArrayOf(0x02, sb.size.toByte()) + sb
    return byteArrayOf(0x30, body.size.toByte()) + body
  }

  /** Verifica [signature] su [message]: solo la forma canonica; un errore del provider vale "non valida". */
  fun verify(key: PublicKey, message: ByteArray, signature: ByteArray): Boolean {
    if (!isCanonical(signature)) return false
    return try {
      Signature.getInstance(SIGNATURE).run {
        initVerify(key)
        update(message)
        verify(signature)
      }
    } catch (e: java.security.GeneralSecurityException) {
      false
    } catch (e: IllegalArgumentException) {
      false
    }
  }

  fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

  fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))

  fun hex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
      val v = b.toInt() and 0xff
      out.append(HEX_DIGITS[v shr 4]).append(HEX_DIGITS[v and 0xf])
    }
    return out.toString()
  }

  private const val HEX_DIGITS = "0123456789abcdef"
}
