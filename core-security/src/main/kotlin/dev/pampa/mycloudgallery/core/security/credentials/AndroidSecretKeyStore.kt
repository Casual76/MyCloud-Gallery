package dev.pampa.mycloudgallery.core.security.credentials

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Genera il materiale della chiave. Separato per poter provare il ripiego StrongBox -> TEE senza un telefono. */
internal fun interface KeyMaterialGenerator {
  fun generate(alias: String, strongBox: Boolean): SecretKey
}

/**
 * Il Keystore vero: chiave AES-256 GCM nell'`AndroidKeyStore`, in StrongBox se il telefono lo ha,
 * altrimenti nel TEE.
 *
 * **Scelta dichiarata (piano 1.5, E-S9, docs/sicurezza.md C14 e L4):** la chiave NON richiede
 * l'autenticazione dell'utente e NON richiede il telefono sbloccato
 * (`setUserAuthenticationRequired(false)`, `setUnlockedDeviceRequired(false)`). Serve cosi': il
 * backup notturno gira con il telefono bloccato e non puo' chiedere un'impronta. Conseguenza voluta:
 * una nuova impronta o un nuovo PIN NON invalidano la chiave (non e' legata alla biometria), quindi
 * le credenziali non spariscono per un cambio di biometria. La chiave puo' sparire solo per eventi
 * del Keystore (ripristino, aggiornamento che la rende inutilizzabile, disinstallazione): li gestisce
 * [CredentialVault].
 */
internal class AndroidSecretKeyStore(
  private val hasStrongBox: Boolean,
  private val generator: KeyMaterialGenerator = SystemKeyMaterialGenerator,
  private val keyStore: () -> KeyStore = { loadKeyStore() },
) : SecretKeyStore {

  override fun find(alias: String): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

  override fun create(alias: String): GeneratedKey {
    var key: SecretKey? = null
    if (hasStrongBox) {
      key = try {
        generator.generate(alias, strongBox = true)
      } catch (e: ProviderException) {
        // StrongBoxUnavailableException e' una ProviderException: il chip c'e' sulla carta ma non
        // risponde (o non regge AES-GCM). Si ripiega sul TEE, senza lasciare un alias a meta'.
        GalleryLog.w(TAG) { "StrongBox non disponibile (${e.javaClass.simpleName}): ripiego sul TEE" }
        runCatching { delete(alias) }
        null
      }
    }
    val created = key ?: generator.generate(alias, strongBox = false)
    return GeneratedKey(created, protectionLevel(created))
  }

  override fun delete(alias: String) {
    val store = keyStore()
    if (store.containsAlias(alias)) store.deleteEntry(alias)
  }

  override fun protectionLevel(key: SecretKey): KeyProtectionLevel = try {
    val factory = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
    val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
    if (Build.VERSION.SDK_INT >= 31) {
      when (info.securityLevel) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyProtectionLevel.STRONGBOX
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyProtectionLevel.TRUSTED_ENVIRONMENT
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyProtectionLevel.SOFTWARE
        else -> KeyProtectionLevel.UNKNOWN
      }
    } else {
      levelBeforeAndroid12(info)
    }
  } catch (e: Exception) {
    GalleryLog.w(TAG) { "livello di protezione della chiave non leggibile (${e.javaClass.simpleName})" }
    KeyProtectionLevel.UNKNOWN
  }

  // `isInsideSecureHardware` e' deprecato dall'API 31 (c'e' `securityLevel`), ma sotto e' l'unica risposta.
  @Suppress("DEPRECATION")
  private fun levelBeforeAndroid12(info: KeyInfo): KeyProtectionLevel =
    if (info.isInsideSecureHardware) KeyProtectionLevel.TRUSTED_ENVIRONMENT else KeyProtectionLevel.SOFTWARE

  companion object {
    const val PROVIDER = "AndroidKeyStore"
    private const val TAG = "AndroidSecretKeyStore"

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
  }
}

/** La generazione vera, con i parametri di sicurezza (vedi KDoc di [AndroidSecretKeyStore]). */
internal object SystemKeyMaterialGenerator : KeyMaterialGenerator {

  override fun generate(alias: String, strongBox: Boolean): SecretKey {
    val spec = KeyGenParameterSpec.Builder(
      alias,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(KEY_SIZE_BITS)
      // IV scelto sempre dal Keystore: non si puo' riusare un nonce per errore.
      .setRandomizedEncryptionRequired(true)
      // Utilizzabile in background senza sblocco: serve al backup notturno (scelta dichiarata, L4).
      .setUserAuthenticationRequired(false)
      .setUnlockedDeviceRequired(false)
      .setIsStrongBoxBacked(strongBox)
      .build()
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidSecretKeyStore.PROVIDER)
    generator.init(spec)
    return generator.generateKey()
  }

  private const val KEY_SIZE_BITS = 256
}
