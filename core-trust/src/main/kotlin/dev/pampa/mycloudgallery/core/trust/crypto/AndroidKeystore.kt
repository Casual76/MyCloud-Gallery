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
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
      initialize(keySpec(alias, strongBox))
      generateKeyPair()
    }
  }

  private class KeystoreSigner(private val privateKey: PrivateKey, override val publicKey: EcPublicKey) : Signer {
    // Il Keystore non garantisce "s basso": si normalizza sempre (EcdsaDer).
    override fun sign(message: ByteArray): ByteArray =
      EcdsaDer.normalize(
        Signature.getInstance(TrustAlgorithms.SIGNATURE).run {
          initSign(privateKey)
          update(message)
          sign()
        },
      )
  }

  companion object {
    const val DEFAULT_ALIAS = "mycloudgallery.device-signing.v1"
    private val LOCK = Any()

    /**
     * La politica della chiave del dispositivo: solo firma, P-256/SHA-256, StrongBox se richiesto. Di
     * proposito NESSUN obbligo di sblocco ne' di autenticazione: i lavori notturni firmano a telefono
     * bloccato (docs/formato-nas.md §4.3).
     */
    internal fun keySpec(alias: String, strongBox: Boolean): KeyGenParameterSpec =
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec(TrustAlgorithms.CURVE))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setUserAuthenticationRequired(false)
        .setIsStrongBoxBacked(strongBox)
        .build()
  }
}

/**
 * La chiave di famiglia a riposo sul dispositivo admin: PKCS#8 cifrato con AES-256-GCM da una chiave del
 * Keystore (StrongBox quando c'e'), non estraibile, usabile solo a telefono sbloccato, in un file dello
 * spazio privato dell'app.
 *
 * "Solo a telefono sbloccato" non vale nulla su un telefono senza blocco schermo sicuro: li' il telefono e'
 * sempre "sbloccato". Per questo la cassaforte rifiuta di lavorare se [isDeviceSecure] e' falso (revisione
 * 1.4, verifica M3 e domanda 9): il dispositivo admin DEVE avere PIN, sequenza o password. L'app passa
 * `KeyguardManager.isDeviceSecure`.
 *
 * Il file ([file]) deve stare fuori da backup e trasferimenti (gia' esclusi per tutta l'app, piano 1.5).
 */
class AndroidKeystoreFamilyKeyVault(
  private val file: File,
  private val isDeviceSecure: () -> Boolean,
  private val alias: String = DEFAULT_ALIAS,
) : FamilyKeyVault {

  override fun store(key: FamilyKey) {
    requireSecureDevice()
    synchronized(LOCK) { storeLocked(key) }
  }

  private fun requireSecureDevice() {
    check(isDeviceSecure()) { "Il dispositivo admin deve avere un blocco schermo sicuro (PIN, sequenza o password)." }
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
    requireSecureDevice()
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

  private fun generateWrappingKey(strongBox: Boolean): SecretKey =
    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
      init(wrappingKeySpec(alias, strongBox))
      generateKey()
    }

  companion object {
    const val DEFAULT_ALIAS = "mycloudgallery.family-key-wrap.v1"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private val HEADER = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), 1)
    private val LOCK = Any()

    /**
     * La politica della chiave che cifra la chiave di famiglia: AES-256-GCM, StrongBox se richiesto, e
     * utilizzabile SOLO a telefono sbloccato (revisione 1.4, M3): la chiave di famiglia serve solo in
     * operazioni interattive dell'admin, quindi a schermo bloccato non deve potersi decifrare (telefono
     * rubato acceso, strumenti forensi). L'obbligo di autenticazione a ogni uso e' la decisione 2 di
     * docs/formato-nas.md §13.
     */
    internal fun wrappingKeySpec(alias: String, strongBox: Boolean): KeyGenParameterSpec =
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUnlockedDeviceRequired(true)
        .setIsStrongBoxBacked(strongBox)
        .build()
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
