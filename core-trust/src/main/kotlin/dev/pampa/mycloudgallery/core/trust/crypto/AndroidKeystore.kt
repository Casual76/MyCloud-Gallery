package dev.pampa.mycloudgallery.core.trust.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * La chiave di famiglia a riposo sul dispositivo admin: PKCS#8 cifrato con AES-256-GCM da una chiave del Keystore (StrongBox quando
 * c'e'), non estraibile, usabile solo a telefono sbloccato **e solo dopo l'impronta o il PIN, a ogni uso** (revisione 1.4, M3;
 * decisione 2(a) dell'utente del 31/10), in un file dello spazio privato dell'app.
 *
 * - **Autenticazione per operazione** ([WrappingPolicy]): la chiave AES vuole l'autenticazione per ogni cifratura e ogni decifrazione
 *   (durata 0, impronta forte o credenziale del telefono; su Android 10 solo l'impronta forte). [store] e [load] preparano il `Cipher`,
 *   lo passano al foglio di sistema ([VaultAuthenticator]: `BiometricPrompt` con `CryptoObject`) e lo usano solo dopo. Annullare il
 *   foglio non cifra e non decifra niente; e il Keystore stesso rifiuta l'operazione senza autenticazione (un foglio che mentisse non
 *   aprirebbe niente).
 * - **Blocco schermo sicuro obbligatorio** ([isDeviceSecure], verifica M3 e domanda 9): senza, la cassaforte non lavora.
 * - **Biometria o blocco cambiati** (E-S9, E-H3): Android invalida la chiave AES quando si aggiunge un'impronta o si toglie il blocco
 *   schermo. La cassaforte lo dice ([VaultRefusal.INVALIDATED]) e la chiave di famiglia si riprende dal kit di recupero o dal secondo
 *   admin; niente si cancella da solo: il file resta finche' un nuovo [store] (il recupero) non lo sostituisce.
 * - **Migrazione** delle cassaforti della versione 1 (chiave AES `setUnlockedDeviceRequired`, senza autenticazione): al primo [load]
 *   interattivo si chiede l'impronta o il PIN sul `Cipher` della chiave **nuova**, poi si decifra con la vecchia, si ricifra con la
 *   nuova (file sostituito in modo atomico) e solo allora si toglie la chiave vecchia. Annullato: niente cambia, si riprova al gesto
 *   dopo. Se la chiave vecchia non apre piu' il file: [VaultRefusal.INVALIDATED] (il kit).
 *
 * Il file, versione 2: `MCGW`, 2, l'hash SHA-256 della chiave pubblica di famiglia (32 byte in chiaro: non e' un segreto, e permette
 * di rispondere "non e' questa la chiave" senza chiedere l'impronta), l'IV (12), il cifrato AES-GCM con AAD = i 37 byte prima dell'IV.
 * Versione 1: `MCGW`, 1, IV, cifrato con AAD = i 5 byte dell'intestazione.
 *
 * Il file ([file]) deve stare fuori da backup e trasferimenti (gia' esclusi per tutta l'app, piano 1.5).
 */
class AndroidKeystoreFamilyKeyVault internal constructor(
  private val file: File,
  private val isDeviceSecure: () -> Boolean,
  private val authenticator: VaultAuthenticator,
  private val keystore: VaultKeystore,
  private val alias: String,
  private val legacyAlias: String,
) : FamilyKeyVault {

  constructor(
    file: File,
    isDeviceSecure: () -> Boolean,
    authenticator: VaultAuthenticator,
    alias: String = DEFAULT_ALIAS,
    legacyAlias: String = LEGACY_ALIAS,
  ) : this(file, isDeviceSecure, authenticator, AndroidVaultKeystore, alias, legacyAlias)

  override suspend fun store(key: FamilyKey, use: FamilyKeyUse): VaultResult<Unit> {
    if (!isDeviceSecure()) return VaultResult.Refused(VaultRefusal.NO_SECURE_LOCK)
    return LOCK.withLock {
      val cipher = when (val prepared = encrypter()) {
        is Prepared.Ready -> prepared.cipher
        is Prepared.Refused -> return@withLock VaultResult.Refused(prepared.reason)
      }
      val authenticated = when (val auth = authenticator.authenticate(cipher, use)) {
        is VaultAuth.Authenticated -> auth.cipher
        is VaultAuth.Refused -> return@withLock VaultResult.Refused(auth.reason)
      }
      val sealed = seal(authenticated, key) ?: return@withLock VaultResult.Refused(VaultRefusal.UNAVAILABLE)
      write(sealed)
      VaultResult.Done(Unit)
    }
  }

  override suspend fun load(expected: EcPublicKey, use: FamilyKeyUse): VaultResult<FamilyKey> {
    if (!isDeviceSecure()) return VaultResult.Refused(VaultRefusal.NO_SECURE_LOCK)
    return LOCK.withLock {
      if (!file.exists()) return@withLock VaultResult.Absent
      val bytes = try {
        file.readBytes()
      } catch (e: IOException) {
        return@withLock VaultResult.Refused(VaultRefusal.UNAVAILABLE)
      }
      if (bytes.size !in MIN_FILE_BYTES..MAX_FILE_BYTES || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) {
        GalleryLog.w(TAG) { "cassaforte della chiave di famiglia illeggibile" }
        return@withLock VaultResult.Refused(VaultRefusal.INVALIDATED)
      }
      when (bytes[MAGIC.size]) {
        VERSION_2 -> open(bytes, expected, use)
        VERSION_1 -> migrate(bytes, expected, use)
        else -> VaultResult.Refused(VaultRefusal.INVALIDATED)
      }
    }
  }

  override fun keep(): VaultCopy? {
    val bytes = try {
      if (file.exists()) file.readBytes() else null
    } catch (e: IOException) {
      null
    } ?: return null
    return VaultCopy { write(bytes) }
  }

  override fun destroy() {
    // Se il file resta (il log di `StateFiles` lo dice) e' un blocco cifrato senza piu' la chiave per aprirlo: gli alias si tolgono lo stesso.
    StateFiles.delete(file)
    keystore.delete(alias)
    keystore.delete(legacyAlias)
  }

  /** Versione 2: niente foglio se la chiave e' un'altra; poi foglio, decifrazione con il `Cipher` autenticato, controllo della chiave. */
  private suspend fun open(bytes: ByteArray, expected: EcPublicKey, use: FamilyKeyUse): VaultResult<FamilyKey> {
    val ivAt = V2_HEADER_BYTES
    val dataAt = ivAt + IV_BYTES
    if (bytes.size < dataAt + TAG_BYTES) return VaultResult.Refused(VaultRefusal.INVALIDATED)
    if (!bytes.copyOfRange(MAGIC.size + 1, V2_HEADER_BYTES).contentEquals(expected.keyHash)) return VaultResult.Absent
    val cipher = try {
      keystore.decrypter(alias, bytes.copyOfRange(ivAt, dataAt))
    } catch (e: WrappingKeyInvalidated) {
      null
    } ?: return VaultResult.Refused(VaultRefusal.INVALIDATED)
    val authenticated = when (val auth = authenticator.authenticate(cipher, use)) {
      is VaultAuth.Authenticated -> auth.cipher
      is VaultAuth.Refused -> return VaultResult.Refused(auth.reason)
    }
    val plain = try {
      keystore.finish(authenticated, bytes.copyOf(V2_HEADER_BYTES), bytes, dataAt, bytes.size - dataAt)
    } catch (e: AEADBadTagException) {
      return VaultResult.Refused(VaultRefusal.INVALIDATED)
    } catch (e: Exception) {
      // Il Keystore ha rifiutato l'operazione (per esempio senza un'autenticazione valida): niente in chiaro.
      GalleryLog.w(TAG) { "decifrazione della cassaforte rifiutata (${e.javaClass.simpleName})" }
      return VaultResult.Refused(VaultRefusal.UNAVAILABLE)
    }
    val opened = restore(plain, expected)
    // Una chiave della versione 1 rimasta (un reset fatto prima della migrazione) non apre piu' niente: via.
    if (opened is VaultResult.Done) dropLegacy()
    return opened
  }

  /**
   * Versione 1 → 2. Prima il foglio sul `Cipher` della chiave nuova (l'autenticazione viene prima di tutto), poi la decifrazione con la
   * chiave vecchia, la ricifratura con il `Cipher` autenticato, la sostituzione atomica del file e, solo dopo, la chiave vecchia tolta.
   */
  private suspend fun migrate(bytes: ByteArray, expected: EcPublicKey, use: FamilyKeyUse): VaultResult<FamilyKey> {
    val ivAt = MAGIC.size + 1
    val dataAt = ivAt + IV_BYTES
    if (bytes.size < dataAt + TAG_BYTES) return VaultResult.Refused(VaultRefusal.INVALIDATED)
    val encrypter = when (val prepared = encrypter()) {
      is Prepared.Ready -> prepared.cipher
      is Prepared.Refused -> return VaultResult.Refused(prepared.reason)
    }
    val authenticated = when (val auth = authenticator.authenticate(encrypter, use)) {
      is VaultAuth.Authenticated -> auth.cipher
      is VaultAuth.Refused -> return VaultResult.Refused(auth.reason)
    }
    val legacy = try {
      keystore.decrypter(legacyAlias, bytes.copyOfRange(ivAt, dataAt))
    } catch (e: WrappingKeyInvalidated) {
      null
    } ?: return VaultResult.Refused(VaultRefusal.INVALIDATED)
    val plain = try {
      keystore.finish(legacy, bytes.copyOf(MAGIC.size + 1), bytes, dataAt, bytes.size - dataAt)
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "cassaforte della versione 1 non decifrata (${e.javaClass.simpleName})" }
      return VaultResult.Refused(VaultRefusal.INVALIDATED)
    }
    val opened = restore(plain, expected)
    if (opened !is VaultResult.Done) return opened
    // La chiave vecchia non chiede l'autenticazione: la prova che l'admin c'e' e' la cifratura con la chiave nuova, che il Keystore fa
    // solo con il `Cipher` autenticato. Senza, la chiave non esce di qui (un foglio che mentisse non la otterrebbe nemmeno ora).
    val sealed = seal(authenticated, opened.value)
    if (sealed == null) {
      GalleryLog.w(TAG) { "migrazione della cassaforte rifiutata: il Keystore non ha cifrato con la chiave nuova" }
      return VaultResult.Refused(VaultRefusal.UNAVAILABLE)
    }
    try {
      write(sealed)
      keystore.delete(legacyAlias)
      GalleryLog.i(TAG) { "cassaforte della chiave di famiglia migrata alla versione 2" }
    } catch (e: Exception) {
      // Il file vecchio e' intero (scrittura atomica) e la sua chiave c'e' ancora: si riprova al prossimo gesto.
      GalleryLog.w(TAG) { "migrazione della cassaforte rimandata (${e.javaClass.simpleName})" }
    }
    return opened
  }

  /** La chiave dal blocco in chiaro (azzerato qui), se e' quella attesa. */
  private fun restore(plain: ByteArray, expected: EcPublicKey): VaultResult<FamilyKey> {
    try {
      val (publicKey, pkcs8) = KeyMaterial.unpack(plain)
      try {
        if (publicKey != expected) return VaultResult.Absent
        return VaultResult.Done(FamilyKey.restore(pkcs8, publicKey))
      } finally {
        pkcs8.fill(0)
      }
    } catch (e: TrustViolation) {
      return VaultResult.Refused(VaultRefusal.INVALIDATED)
    } finally {
      plain.fill(0)
    }
  }

  /** Il file della versione 2 con [cipher] gia' autenticato; null se il Keystore rifiuta. */
  private fun seal(cipher: Cipher, key: FamilyKey): ByteArray? {
    val pkcs8 = key.exportPkcs8()
    val plain = KeyMaterial.pack(key.publicKey, pkcs8)
    try {
      val header = HEADER_V2 + key.publicKey.keyHash
      val sealed = try {
        keystore.finish(cipher, header, plain, 0, plain.size)
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "cifratura della cassaforte rifiutata (${e.javaClass.simpleName})" }
        return null
      }
      val iv = cipher.iv
      check(iv != null && iv.size == IV_BYTES) { "IV inatteso dal Keystore." }
      return header + iv + sealed
    } finally {
      plain.fill(0)
      pkcs8.fill(0)
    }
  }

  private fun write(bytes: ByteArray) {
    try {
      // Temporaneo, fsync e rinomina atomica: o la cassaforte vecchia intera o la nuova intera (il file non e' mai a meta').
      StateFiles.writeBytes(file, bytes)
    } catch (e: IOException) {
      error("Impossibile salvare la chiave di famiglia.")
    }
  }

  private sealed interface Prepared {
    class Ready(val cipher: Cipher) : Prepared

    class Refused(val reason: VaultRefusal) : Prepared
  }

  /**
   * Il `Cipher` per cifrare con la chiave della versione 2 (init fatto, prima del foglio). Se manca o e' invalidata se ne crea una
   * nuova: chi cifra sta custodendo una chiave (nuova o migrata), e una chiave invalidata non apriva comunque piu' niente.
   */
  private fun encrypter(): Prepared {
    try {
      keystore.encrypter(alias)?.let { return Prepared.Ready(it) }
    } catch (e: WrappingKeyInvalidated) {
      keystore.delete(alias)
    }
    try {
      keystore.create(alias)
    } catch (e: GeneralSecurityException) {
      // Nessun blocco schermo sicuro (o, su Android 10, nessuna impronta): Android non crea chiavi con l'autenticazione.
      GalleryLog.w(TAG) { "chiave della cassaforte non creata (${e.javaClass.simpleName})" }
      return Prepared.Refused(VaultRefusal.NO_SECURE_LOCK)
    } catch (e: RuntimeException) {
      GalleryLog.w(TAG) { "chiave della cassaforte non creata (${e.javaClass.simpleName})" }
      return Prepared.Refused(VaultRefusal.UNAVAILABLE)
    }
    return try {
      keystore.encrypter(alias)?.let { Prepared.Ready(it) } ?: Prepared.Refused(VaultRefusal.UNAVAILABLE)
    } catch (e: WrappingKeyInvalidated) {
      Prepared.Refused(VaultRefusal.INVALIDATED)
    }
  }

  private fun dropLegacy() {
    try {
      keystore.delete(legacyAlias)
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "chiave della versione 1 non tolta (${e.javaClass.simpleName})" }
    }
  }

  companion object {
    /** La chiave AES con l'autenticazione a ogni uso (versione 2 del file). */
    const val DEFAULT_ALIAS = "mycloudgallery.family-key-wrap.v2"

    /** La chiave AES della versione 1 (solo `setUnlockedDeviceRequired`): resta finche' la cassaforte non e' migrata. */
    const val LEGACY_ALIAS = "mycloudgallery.family-key-wrap.v1"

    private const val TAG = "FamilyKeyVault"
    internal const val AES_GCM = "AES/GCM/NoPadding"
    internal const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HASH_BYTES = 32
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte())
    private const val VERSION_1: Byte = 1
    private const val VERSION_2: Byte = 2
    internal val HEADER_V1: ByteArray = MAGIC + VERSION_1
    private val HEADER_V2: ByteArray = MAGIC + VERSION_2
    private val V2_HEADER_BYTES = HEADER_V2.size + HASH_BYTES
    private val MIN_FILE_BYTES = MAGIC.size + 1 + IV_BYTES + TAG_BYTES
    private const val MAX_FILE_BYTES = 4096

    /** Una sola operazione della cassaforte alla volta nel processo (il foglio compreso). */
    private val LOCK = Mutex()

    /**
     * La chiave che cifra la chiave di famiglia: AES-256-GCM, StrongBox se richiesto, solo a telefono sbloccato (revisione 1.4, M3) e con
     * l'autenticazione **a ogni uso** (decisione 2(a), [WrappingPolicy]): la chiave di famiglia serve solo in operazioni interattive
     * dell'admin, quindi ne' un telefono rubato acceso e bloccato, ne' uno strumento forense, ne' un malware che agisce a telefono
     * sbloccato la decifrano senza l'impronta o il PIN dell'admin. Un'impronta nuova o il blocco schermo tolto la invalidano.
     */
    internal fun wrappingKeySpec(alias: String, strongBox: Boolean): KeyGenParameterSpec {
      val policy = WrappingPolicy.forSdk(Build.VERSION.SDK_INT)
      val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUnlockedDeviceRequired(policy.unlockedDeviceRequired)
        .setUserAuthenticationRequired(policy.userAuthenticationRequired)
        .setInvalidatedByBiometricEnrollment(policy.invalidatedByBiometricEnrollment)
        .setIsStrongBoxBacked(strongBox)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setUserAuthenticationParameters(policy.authTimeoutSeconds, policy.authTypes)
      } else {
        @Suppress("DEPRECATION")
        builder.setUserAuthenticationValidityDurationSeconds(policy.authTimeoutSeconds)
      }
      return builder.build()
    }
  }
}

/**
 * La politica della chiave AES della cassaforte, come dati: la applica [AndroidKeystoreFamilyKeyVault.wrappingKeySpec], la provano i test
 * JVM (`FamilyKeyVaultTest`) e quello sul dispositivo (`KeystoreDeviceTest`, la chiave vera).
 *
 * Autenticazione **a ogni uso**: da Android 11 durata 0 con impronta forte o credenziale del telefono (PIN, sequenza, password); su
 * Android 10, dove la credenziale non si lega a una chiave per ogni uso, durata -1, cioe' solo l'impronta forte, a ogni uso.
 */
internal data class WrappingPolicy(
  val unlockedDeviceRequired: Boolean,
  val userAuthenticationRequired: Boolean,
  val authTimeoutSeconds: Int,
  val authTypes: Int,
  val invalidatedByBiometricEnrollment: Boolean,
) {
  companion object {
    fun forSdk(sdk: Int): WrappingPolicy =
      WrappingPolicy(
        unlockedDeviceRequired = true,
        userAuthenticationRequired = true,
        authTimeoutSeconds = if (sdk >= Build.VERSION_CODES.R) 0 else -1,
        authTypes = if (sdk >= Build.VERSION_CODES.R) {
          KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        } else {
          KeyProperties.AUTH_BIOMETRIC_STRONG
        },
        invalidatedByBiometricEnrollment = true,
      )
  }
}

/** La chiave AES della cassaforte non vale piu' (Android l'ha invalidata) o non si legge. */
internal class WrappingKeyInvalidated : Exception()

/**
 * Il Keystore visto dalla cassaforte. E' il punto che i test JVM sostituiscono con un falso che, come il Keystore vero, non cifra ne'
 * decifra con una chiave "con autenticazione" se il `Cipher` non e' passato da un'autenticazione riuscita.
 */
internal interface VaultKeystore {
  /** Crea la chiave della versione 2 ([AndroidKeystoreFamilyKeyVault.wrappingKeySpec]); lancia se Android non la permette. */
  fun create(alias: String)

  /** Un `Cipher` pronto per cifrare (init fatto, IV scelto dal Keystore); null se la chiave non c'e'. */
  fun encrypter(alias: String): Cipher?

  /** Un `Cipher` pronto per decifrare con [iv]; null se la chiave non c'e'. */
  fun decrypter(alias: String, iv: ByteArray): Cipher?

  /** L'operazione vera (AAD e `doFinal`): con una chiave che vuole l'autenticazione il Keystore la rifiuta se il `Cipher` non e' autenticato. */
  fun finish(cipher: Cipher, aad: ByteArray, input: ByteArray, offset: Int, length: Int): ByteArray

  /** Toglie la chiave, se c'e'. */
  fun delete(alias: String)
}

/** Il Keystore di Android. */
internal object AndroidVaultKeystore : VaultKeystore {

  override fun create(alias: String) {
    try {
      generate(alias, strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
      generate(alias, strongBox = false)
    }
  }

  private fun generate(alias: String, strongBox: Boolean) {
    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
      init(AndroidKeystoreFamilyKeyVault.wrappingKeySpec(alias, strongBox))
      generateKey()
    }
  }

  override fun encrypter(alias: String): Cipher? {
    val key = secretKey(alias) ?: return null
    return initialized { Cipher.getInstance(AndroidKeystoreFamilyKeyVault.AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key) } }
  }

  override fun decrypter(alias: String, iv: ByteArray): Cipher? {
    val key = secretKey(alias) ?: return null
    return initialized {
      Cipher.getInstance(AndroidKeystoreFamilyKeyVault.AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
    }
  }

  override fun finish(cipher: Cipher, aad: ByteArray, input: ByteArray, offset: Int, length: Int): ByteArray {
    cipher.updateAAD(aad)
    return cipher.doFinal(input, offset, length)
  }

  override fun delete(alias: String) {
    val keyStore = androidKeyStore()
    if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
  }

  private fun secretKey(alias: String): SecretKey? =
    try {
      androidKeyStore().getKey(alias, null) as? SecretKey
    } catch (e: UnrecoverableKeyException) {
      throw WrappingKeyInvalidated()
    }

  private inline fun initialized(block: () -> Cipher): Cipher =
    try {
      block()
    } catch (e: KeyPermanentlyInvalidatedException) {
      throw WrappingKeyInvalidated()
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
