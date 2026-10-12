package dev.pampa.mycloudgallery.core.trust.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

/** Dove sta davvero una chiave del Keystore: per la Diagnostica e per il test sul dispositivo. */
enum class KeyProtection { STRONGBOX, TRUSTED_ENVIRONMENT, SECURE_HARDWARE, SOFTWARE, UNKNOWN }

/**
 * La chiave di firma del dispositivo nel Keystore Android (`docs/formato-nas.md` §4.2): ECDSA P-256,
 * StrongBox quando c'e' (altrimenti TEE), NON estraibile.
 *
 * Non richiede lo sblocco dell'utente: i lavori notturni (indice, prenotazioni) firmano a telefono
 * bloccato, come per le credenziali del NAS (`docs/sicurezza.md` §8). Il prezzo e' dichiarato: un malware
 * con privilegi sul telefono puo' FAR firmare la chiave finche' il dispositivo non e' revocato, ma non puo'
 * portarla via.
 *
 * Ogni profilo Android ha il suo Keystore: un profilo e' un dispositivo a se' (piano 2.8).
 */
class AndroidKeystoreKeyProvider(private val alias: String = DEFAULT_ALIAS) : KeyProvider {

  override fun hasDeviceKey(): Boolean = androidKeyStore().containsAlias(alias)

  override fun deviceSigner(): Signer {
    synchronized(LOCK) {
      val keyStore = androidKeyStore()
      if (!keyStore.containsAlias(alias)) generate()
      val entry = androidKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        ?: error("La voce del Keystore non e' una coppia di chiavi.")
      return KeystoreSigner(entry.privateKey, EcPublicKey.fromJca(entry.certificate.publicKey))
    }
  }

  override fun destroyDeviceKey() {
    synchronized(LOCK) {
      val keyStore = androidKeyStore()
      if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }
  }

  /** Dove il Keystore ha messo la chiave; [KeyProtection.UNKNOWN] se non c'e'. */
  fun protection(): KeyProtection {
    val key = androidKeyStore().getKey(alias, null) as? PrivateKey ?: return KeyProtection.UNKNOWN
    return protectionOf(key)
  }

  private fun generate() {
    try {
      generate(strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
      generate(strongBox = false)
    }
  }

  private fun generate(strongBox: Boolean) {
    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec(TrustAlgorithms.CURVE))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .setUserAuthenticationRequired(false)
      .setIsStrongBoxBacked(strongBox)
      .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
      initialize(spec)
      generateKeyPair()
    }
  }

  private class KeystoreSigner(private val privateKey: PrivateKey, override val publicKey: EcPublicKey) : Signer {
    override fun sign(message: ByteArray): ByteArray =
      Signature.getInstance(TrustAlgorithms.SIGNATURE).run {
        initSign(privateKey)
        update(message)
        sign()
      }
  }

  companion object {
    const val DEFAULT_ALIAS = "mycloudgallery.device-signing.v1"
    private val LOCK = Any()
  }
}

/**
 * La chiave di famiglia a riposo sul dispositivo admin: PKCS#8 cifrato con AES-256-GCM da una chiave del
 * Keystore (StrongBox quando c'e'), non estraibile, in un file dello spazio privato dell'app.
 *
 * Il file ([file]) deve stare fuori da backup e trasferimenti (gia' esclusi per tutta l'app, piano 1.5).
 */
class AndroidKeystoreFamilyKeyVault(
  private val file: File,
  private val alias: String = DEFAULT_ALIAS,
) : FamilyKeyVault {

  override fun store(key: FamilyKey) {
    synchronized(LOCK) { storeLocked(key) }
  }

  private fun storeLocked(key: FamilyKey) {
    val pkcs8 = key.exportPkcs8()
    try {
      val plain = KeyMaterial.pack(key.publicKey, pkcs8)
      try {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(HEADER)
        val sealed = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "IV inatteso dal Keystore." }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(HEADER + iv + sealed)
        if (!tmp.renameTo(file)) {
          tmp.delete()
          error("Impossibile salvare la chiave di famiglia.")
        }
      } finally {
        plain.fill(0)
      }
    } finally {
      pkcs8.fill(0)
    }
  }

  override fun load(expected: EcPublicKey): FamilyKey? {
    synchronized(LOCK) { return loadLocked(expected) }
  }

  private fun loadLocked(expected: EcPublicKey): FamilyKey? {
    if (!file.exists()) return null
    val bytes = file.readBytes()
    require(bytes.size in (HEADER.size + IV_BYTES + 16)..4096) { "Cassaforte della chiave di famiglia illeggibile." }
    require(bytes.copyOf(HEADER.size).contentEquals(HEADER)) { "Cassaforte della chiave di famiglia illeggibile." }
    val keyStore = androidKeyStore()
    val secret = keyStore.getKey(alias, null) as? SecretKey ?: return null
    val cipher = Cipher.getInstance(AES_GCM)
    cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes, HEADER.size, IV_BYTES))
    cipher.updateAAD(HEADER)
    val plain = cipher.doFinal(bytes, HEADER.size + IV_BYTES, bytes.size - HEADER.size - IV_BYTES)
    try {
      val (publicKey, pkcs8) = KeyMaterial.unpack(plain)
      try {
        if (publicKey != expected) return null
        return FamilyKey.restore(pkcs8, publicKey)
      } finally {
        pkcs8.fill(0)
      }
    } finally {
      plain.fill(0)
    }
  }

  override fun destroy() {
    synchronized(LOCK) {
      file.delete()
      val keyStore = androidKeyStore()
      if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }
  }

  private fun wrappingKey(): SecretKey {
    val keyStore = androidKeyStore()
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    return try {
      generateWrappingKey(strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
      generateWrappingKey(strongBox = false)
    }
  }

  private fun generateWrappingKey(strongBox: Boolean): SecretKey {
    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .setIsStrongBoxBacked(strongBox)
      .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
      init(spec)
      generateKey()
    }
  }

  companion object {
    const val DEFAULT_ALIAS = "mycloudgallery.family-key-wrap.v1"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private val HEADER = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), 1)
    private val LOCK = Any()
  }
}

internal fun protectionOf(key: PrivateKey): KeyProtection {
  val info = try {
    KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)
  } catch (e: java.security.GeneralSecurityException) {
    return KeyProtection.UNKNOWN
  }
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    when (info.securityLevel) {
      KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyProtection.STRONGBOX
      KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyProtection.TRUSTED_ENVIRONMENT
      KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyProtection.SOFTWARE
      else -> KeyProtection.UNKNOWN
    }
  } else {
    @Suppress("DEPRECATION")
    if (info.isInsideSecureHardware) KeyProtection.SECURE_HARDWARE else KeyProtection.SOFTWARE
  }
}
