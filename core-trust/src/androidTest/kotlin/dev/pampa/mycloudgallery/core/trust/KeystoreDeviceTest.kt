package dev.pampa.mycloudgallery.core.trust

import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import dev.pampa.mycloudgallery.core.trust.crypto.AndroidKeystoreFamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.AndroidKeystoreKeyProvider
import dev.pampa.mycloudgallery.core.trust.crypto.AndroidVaultKeystore
import dev.pampa.mycloudgallery.core.trust.crypto.EcdsaDer
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.KeyMaterial
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProtection
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.crypto.VaultAuth
import dev.pampa.mycloudgallery.core.trust.crypto.VaultAuthenticator
import dev.pampa.mycloudgallery.core.trust.crypto.VaultRefusal
import dev.pampa.mycloudgallery.core.trust.crypto.VaultResult
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadLocation
import dev.pampa.mycloudgallery.core.trust.data.HeadVerifier
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.Segments
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.runBlocking

/**
 * Firma e verifica con la chiave VERA del Keystore, sul dispositivo (S25, o l'emulatore `Medium_Phone`): la chiave del
 * dispositivo e' non estraibile e in hardware, le sue firme passano la verifica JCA pura che usano gli altri dispositivi.
 *
 * La cassaforte della chiave di famiglia (Fase 10, M3, decisione 2(a)): la politica della sua chiave AES sul Keystore vero
 * (autenticazione a ogni uso, impronta forte o credenziale) e il Keystore che **rifiuta** di cifrare o decifrare senza
 * un'autenticazione vera, anche quando il foglio dice "autenticato" senza esserlo. Il giro completo con l'impronta o il PIN
 * passa da `BiometricPrompt` e si prova sul telefono (prove sul campo). I test della cassaforte vogliono un blocco schermo
 * sicuro (sull'emulatore: `adb shell locksettings set-pin`); senza, si saltano (assunzione). Alias propri del test, cancellati
 * alla fine.
 */
class KeystoreDeviceTest {

  private val deviceAlias = "mycloudgallery.test.device-signing"
  private val vaultAlias = "mycloudgallery.test.family-key-wrap.v2"
  private val legacyAlias = "mycloudgallery.test.family-key-wrap.v1"
  private val provider = AndroidKeystoreKeyProvider(deviceAlias)
  private val vaultFile = File(KeystoreTestRunner.testContext.cacheDir, "family-key-vault-test")
  private val deviceSecure: Boolean
    get() = KeystoreTestRunner.testContext.getSystemService(KeyguardManager::class.java).isDeviceSecure

  /** Un foglio che mente: dice "autenticato" senza nessuna autenticazione. Il Keystore vero non deve credergli. */
  private var sheets = 0
  private val lyingSheet = VaultAuthenticator { cipher, _ ->
    sheets++
    VaultAuth.Authenticated(cipher)
  }
  private val vault = AndroidKeystoreFamilyKeyVault(
    vaultFile,
    isDeviceSecure = { deviceSecure },
    authenticator = lyingSheet,
    alias = vaultAlias,
    legacyAlias = legacyAlias,
  )
  private val clock = NasClock { 1_791_806_400_000L }

  @After
  fun cleanUp() {
    provider.destroyDeviceKey()
    vault.destroy()
  }

  @Test
  fun laChiaveDelDispositivoENonEstraibileEInHardware() {
    provider.deviceSigner()

    val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(deviceAlias, null) as PrivateKey
    assertNull("la chiave privata non deve essere esportabile", key.encoded)
    val protection = provider.protection()
    assertTrue("chiave in $protection", protection == KeyProtection.STRONGBOX || protection == KeyProtection.TRUSTED_ENVIRONMENT || protection == KeyProtection.SECURE_HARDWARE)
    // StrongBox dove c'e' (sull'S25 c'e'): allora la chiave deve stare li'.
    val hasStrongBox = KeystoreTestRunner.testContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    if (hasStrongBox) assertEquals(KeyProtection.STRONGBOX, protection)
  }

  @Test
  fun firmaDelKeystoreVerificataDaTuttaLaCatena() {
    val device = provider.deviceSigner()
    val familyKey = FamilyKey.generate()
    val familyId = TrustIds.random()

    // Il dispositivo chiede di entrare (firma Keystore), l'admin fonda la famiglia con lui.
    val requestBytes = RegistrationRequest.sign(device, familyId, "S25 di prova", "SM-S931B", "admin", clock.nowMillis())
    val request = RegistrationRequest.verify(requestBytes, familyId, TrustIds.deviceIdOf(device.publicKey)).acceptedOrNull()
    assertNotNull(request)
    val founded = FamilyAuthority(familyKey, clock).found(familyId, FamilyConfig("192.168.178.58", "Public", nasName = "mycloudex2ultra.fritz.box"), request!!)
    val anchor = TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 1)!!
    val registry = RegistryVerifier.accept(founded.bytes, null, anchor).acceptedOrNull()!!

    // Una testa dell'indice firmata dal Keystore e verificata con JCA puro.
    val (_, ref) = Segments.wrap(ByteArray(64) { it.toByte() })
    val deviceId = TrustIds.deviceIdOf(device.publicKey)
    val head = DeviceHead(familyId, deviceId, "Public", HeadKind.INDEX, 1, 1, clock.nowMillis(), listOf(ClassedSegment(SegmentClass.META, ref))).sign(device)
    val accepted = HeadVerifier.accept(head, registry, HeadLocation("Public", deviceId, HeadKind.INDEX, 1), null, "admin")
    assertNotNull(accepted.acceptedOrNull())

    // Un byte cambiato e la firma del Keystore non vale piu'.
    val tampered = head.copyOf().also { it[it.size - 10] = (it[it.size - 10].toInt() xor 1).toByte() }
    assertEquals(RejectReason.BAD_SIGNATURE, HeadVerifier.accept(tampered, registry, HeadLocation("Public", deviceId, HeadKind.INDEX, 1), null, "admin").rejectionOrNull())
  }

  /**
   * Revisione 1.4, M3 (e Fase 10, decisione 2(a)): la chiave che cifra la chiave di famiglia si usa solo a telefono sbloccato
   * e solo dopo un'autenticazione, a ogni uso; la chiave del dispositivo no (i lavori notturni firmano a telefono bloccato) e
   * non chiede autenticazione.
   */
  @Test
  fun politicheDelleChiavi_cassaforteAOgniUso_chiaveDelDispositivoAncheBloccato() {
    val wrapping = AndroidKeystoreFamilyKeyVault.wrappingKeySpec("x", strongBox = false)
    val device = AndroidKeystoreKeyProvider.keySpec("y", strongBox = false)

    assertTrue(wrapping.isUnlockedDeviceRequired)
    assertTrue(wrapping.isUserAuthenticationRequired)
    assertTrue(wrapping.isInvalidatedByBiometricEnrollment)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      assertEquals(0, wrapping.userAuthenticationValidityDurationSeconds)
      assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL, wrapping.userAuthenticationType)
    } else {
      assertEquals(-1, wrapping.userAuthenticationValidityDurationSeconds)
    }
    assertFalse(device.isUnlockedDeviceRequired)
    assertFalse(device.isUserAuthenticationRequired)
  }

  /**
   * Fase 10, M3, sul Keystore VERO: la chiave della cassaforte nasce con l'autenticazione a ogni uso (lo dice il Keystore stesso,
   * `KeyInfo`), il `Cipher` si prepara ma l'operazione senza autenticazione e' rifiutata.
   */
  @Test
  fun laChiaveVeraDellaCassaforte_vuoleLAutenticazioneAOgniUso() {
    assumeTrue("serve un blocco schermo sicuro (adb shell locksettings set-pin)", deviceSecure)
    AndroidVaultKeystore.create(vaultAlias)

    val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(vaultAlias, null) as SecretKey
    val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
    assertTrue(info.isUserAuthenticationRequired)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      assertEquals(0, info.userAuthenticationValidityDurationSeconds)
      assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL, info.userAuthenticationType)
    }
    // L'invalidazione per un'impronta nuova vale se alla creazione c'era un'impronta da legare (sull'emulatore senza impronte il
    // Keystore la riporta falsa): la politica la chiede sempre (test qui sopra), il Keystore la applica quando ha senso.
    Log.i(TAG, "chiave della cassaforte: livello ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.securityLevel else -1}, " +
      "autenticazione imposta dall'hardware ${info.isUserAuthenticationRequirementEnforcedBySecureHardware}, " +
      "invalidata da un'impronta nuova ${info.isInvalidatedByBiometricEnrollment}")

    val cipher = AndroidVaultKeystore.encrypter(vaultAlias)!!
    val refused = try {
      AndroidVaultKeystore.finish(cipher, ByteArray(5), ByteArray(64), 0, 64)
      null
    } catch (e: Exception) {
      e
    }
    assertNotNull("senza autenticazione il Keystore non deve cifrare", refused)
    Log.i(TAG, "operazione senza autenticazione rifiutata: ${refused!!.javaClass.name} (${refused.cause?.javaClass?.name})")
  }

  /** Fase 10, M3: un foglio che mente non custodisce e non apre niente: lo decide il Keystore, non il foglio. */
  @Test
  fun unFoglioCheMente_laCassaforteNonCustodisceNiente() = runBlocking<Unit> {
    assumeTrue("serve un blocco schermo sicuro (adb shell locksettings set-pin)", deviceSecure)

    assertEquals(VaultResult.Refused(VaultRefusal.UNAVAILABLE), vault.store(FamilyKey.generate()))
    assertEquals(1, sheets)
    assertFalse(vaultFile.exists())
  }

  /**
   * Fase 10, M3: una cassaforte della versione 1 (chiave AES senza autenticazione, come sul telefono di prova dell'admin) non si
   * migra e non da' la chiave con un foglio che mente: la cifratura con la chiave nuova la rifiuta il Keystore. Il file resta.
   */
  @Test
  fun migrazione_conUnFoglioCheMente_nonDaLaChiave_eIlFileResta() = runBlocking<Unit> {
    assumeTrue("serve un blocco schermo sicuro (adb shell locksettings set-pin)", deviceSecure)
    val familyKey = FamilyKey.generate()
    val legacy = writeLegacyVault(familyKey)

    assertEquals(VaultResult.Refused(VaultRefusal.UNAVAILABLE), vault.load(familyKey.publicKey))
    assertTrue(legacy.contentEquals(vaultFile.readBytes()))
    assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(legacyAlias))
  }

  /** Una cassaforte della versione 1 come la scriveva il codice di prima del 31/10: AES-GCM, solo `setUnlockedDeviceRequired`. */
  private fun writeLegacyVault(familyKey: FamilyKey): ByteArray {
    val legacyKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(
        KeyGenParameterSpec.Builder(legacyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .setUnlockedDeviceRequired(true)
          .build(),
      )
      generateKey()
    }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, legacyKey) }
    cipher.updateAAD(AndroidKeystoreFamilyKeyVault.HEADER_V1)
    val pkcs8 = familyKey.exportPkcs8()
    val sealed = cipher.doFinal(KeyMaterial.pack(familyKey.publicKey, pkcs8))
    pkcs8.fill(0)
    val bytes = AndroidKeystoreFamilyKeyVault.HEADER_V1 + cipher.iv + sealed
    vaultFile.writeBytes(bytes)
    return bytes
  }

  @Test
  fun leFirmeDelKeystoreSonoInFormaCanonica() {
    val device = provider.deviceSigner()
    repeat(50) {
      val signature = device.sign("messaggio $it".toByteArray())
      assertTrue(EcdsaDer.isCanonical(signature))
      assertTrue(device.publicKey.verify("messaggio $it".toByteArray(), signature))
    }
  }

  @Test
  fun distruggereLaChiaveNeCreaUnAltraAlPrimoUso() {
    val first = provider.deviceSigner().publicKey
    assertTrue(provider.hasDeviceKey())

    provider.destroyDeviceKey()
    assertFalse(provider.hasDeviceKey())
    assertNotEquals(first, provider.deviceSigner().publicKey)
  }

  private companion object {
    const val TAG = "KeystoreDeviceTest"
  }
}
