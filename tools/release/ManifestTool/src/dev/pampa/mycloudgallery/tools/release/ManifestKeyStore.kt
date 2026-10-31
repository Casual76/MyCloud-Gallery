package dev.pampa.mycloudgallery.tools.release

import dev.pampa.mycloudgallery.core.release.codec.EcdsaP256
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// LE CHIAVI DI PUBBLICAZIONE SUL PC DELL'ADMIN (docs/fase10-progetto.md D31, §2.8; C442). Solo `java.security`, nessuna libreria: un PKCS#12
// con frase segreta (voci `k1` e `k2`, ognuna con un certificato autofirmato che la JCA pretende per una chiave privata, scritto qui in DER),
// il "foglio" di K2 da tenere offline (lo scalare privato in esadecimale con un codice di controllo) e la ricostruzione di K2 dal foglio.
// Le chiavi vere non entrano mai nel repository (`tools/check.ps1` passi 3 e 3b): il file sta in `%USERPROFILE%\.mycloudgallery\release\`.

internal object ManifestKeyStore {
  const val FILE_NAME: String = "manifest-keys.p12"
  const val MIN_PASSPHRASE: Int = 12

  /** Una coppia P-256 nuova. */
  fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()

  /** Scrive il PKCS#12 con le voci [pairs] (`k1`, `k2`) e la [passphrase]; mai sopra un file che c'e' gia'. */
  fun write(file: File, pairs: Map<String, KeyPair>, passphrase: CharArray, now: Instant) {
    require(!file.exists()) { "Il file delle chiavi c'e' gia': ${file.name}. Non lo sovrascrivo." }
    val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    for ((alias, pair) in pairs) {
      store.setKeyEntry(alias, pair.private, passphrase, arrayOf(selfSigned(pair, "MyCloud Gallery manifest ${alias.uppercase()}", now)))
    }
    val bytes = ByteArrayOutputStream().also { store.store(it, passphrase) }.toByteArray()
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, file.name + ".tmp")
    temp.writeBytes(bytes)
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
  }

  /** La chiave privata e lo SPKI della voce [alias]; lancia con un messaggio chiaro se la frase e' sbagliata o la voce manca. */
  fun load(file: File, alias: String, passphrase: CharArray): Pair<PrivateKey, ByteArray> {
    require(file.isFile) { "Non trovo il file delle chiavi: ${file.path}" }
    val store = KeyStore.getInstance("PKCS12")
    try {
      ByteArrayInputStream(file.readBytes()).use { store.load(it, passphrase) }
    } catch (e: java.io.IOException) {
      throw IllegalArgumentException("Frase segreta sbagliata o file delle chiavi rovinato.")
    }
    val key = store.getKey(alias, passphrase) as? PrivateKey ?: throw IllegalArgumentException("La voce $alias non c'e' nel file delle chiavi.")
    val spki = store.getCertificate(alias).publicKey.encoded
    require(EcdsaP256.isValidSpki(spki)) { "La voce $alias non e' una chiave P-256." }
    return key to spki
  }

  /** Il foglio di K2 (D31): lo scalare privato in 8 gruppi di 8 cifre esadecimali e un codice di controllo di 8 cifre. */
  fun sheet(key: PrivateKey): List<String> {
    val scalar = fixed32((key as ECPrivateKey).s)
    val hex = EcdsaP256.hex(scalar)
    return hex.chunked(8) + listOf("controllo " + EcdsaP256.sha256Hex(scalar).take(8))
  }

  /** La chiave privata dal foglio ([digits]: le 64 cifre, spazi ammessi; [check]: le 8 cifre di controllo). */
  fun fromSheet(digits: String, check: String): PrivateKey {
    val hex = digits.filter { !it.isWhitespace() }.lowercase()
    require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "Servono 64 cifre esadecimali." }
    val scalar = ByteArray(32) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    require(EcdsaP256.sha256Hex(scalar).take(8) == check.trim().lowercase()) { "Il codice di controllo non torna: c'e' un errore di battitura." }
    val s = BigInteger(1, scalar)
    require(s.signum() > 0 && s < EcdsaP256.N) { "Numero fuori dalla curva." }
    val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(s, params))
  }

  /** Vero se [private] e la chiave pubblica [spki] sono una coppia (si firma e si verifica un messaggio). */
  fun matches(private: PrivateKey, spki: ByteArray): Boolean {
    val message = "MyCloud Gallery: prova della coppia".toByteArray()
    val signature = Signature.getInstance(EcdsaP256.SIGNATURE).run {
      initSign(private)
      update(message)
      sign()
    }
    return EcdsaP256.verify(EcdsaP256.publicKey(spki), message, EcdsaP256.normalize(signature))
  }

  /** Un certificato X.509 v1 autofirmato (ECDSA con SHA-256) intorno alla chiave pubblica: lo pretende il PKCS#12 di Java. */
  fun selfSigned(pair: KeyPair, name: String, now: Instant): X509Certificate {
    val algorithm = der(0x30, OID_ECDSA_SHA256)
    val subject = der(0x30, der(0x31, der(0x30, OID_COMMON_NAME + der(0x0c, name.toByteArray(Charsets.UTF_8)))))
    val serial = ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7f or 0x01).toByte() }
    val validity = der(0x30, utcTime(now) + utcTime(now.atOffset(ZoneOffset.UTC).plusYears(20).toInstant()))
    val tbs = der(0x30, der(0x02, serial) + algorithm + subject + validity + subject + pair.public.encoded)
    val signature = Signature.getInstance(EcdsaP256.SIGNATURE).run {
      initSign(pair.private)
      update(tbs)
      sign()
    }
    val certificate = der(0x30, tbs + algorithm + der(0x03, byteArrayOf(0) + signature))
    return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
  }

  private val OID_ECDSA_SHA256 = byteArrayOf(0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02)
  private val OID_COMMON_NAME = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
  private val UTC = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)

  private fun utcTime(instant: Instant): ByteArray = der(0x17, UTC.format(instant).toByteArray(Charsets.US_ASCII))

  private fun der(tag: Int, content: ByteArray): ByteArray {
    val length = content.size
    val header = when {
      length < 0x80 -> byteArrayOf(tag.toByte(), length.toByte())
      length < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), length.toByte())
      else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (length shr 8).toByte(), length.toByte())
    }
    return header + content
  }

  private fun fixed32(value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    val out = ByteArray(32)
    val n = minOf(32, bytes.size)
    System.arraycopy(bytes, bytes.size - n, out, 32 - n, n)
    return out
  }
}
