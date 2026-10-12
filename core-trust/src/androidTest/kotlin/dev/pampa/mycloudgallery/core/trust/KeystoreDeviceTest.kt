package dev.pampa.mycloudgallery.core.trust

import android.content.pm.PackageManager
import dev.pampa.mycloudgallery.core.trust.crypto.AndroidKeystoreFamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.AndroidKeystoreKeyProvider
import dev.pampa.mycloudgallery.core.trust.crypto.EcdsaDer
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProtection
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadLocation
import dev.pampa.mycloudgallery.core.trust.data.HeadVerifier
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
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey

/**
 * Firma e verifica con la chiave VERA del Keystore, sul dispositivo (S25): la chiave del dispositivo e'
 * non estraibile e in hardware, le sue firme passano la verifica JCA pura che usano gli altri dispositivi,
 * e la cassaforte della chiave di famiglia fa il giro. Alias propri del test, cancellati alla fine.
 */
class KeystoreDeviceTest {

  private val deviceAlias = "mycloudgallery.test.device-signing"
  private val vaultAlias = "mycloudgallery.test.family-key-wrap"
  private val provider = AndroidKeystoreKeyProvider(deviceAlias)
  private val vaultFile = File(KeystoreTestRunner.testContext.cacheDir, "family-key-vault-test")
  private val vault = AndroidKeystoreFamilyKeyVault(vaultFile, vaultAlias)
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
    val founded = FamilyAuthority(familyKey, clock).found(familyId, FamilyConfig("mycloudex2ultra.fritz.box", "Public"), request!!)
    val anchor = TrustAnchor.create(familyId, familyKey.publicKey.fingerprint, 1)!!
    val registry = RegistryVerifier.accept(founded.bytes, null, anchor).acceptedOrNull()!!

    // Una testa dell'indice firmata dal Keystore e verificata con JCA puro.
    val (_, ref) = Segments.wrap(ByteArray(64) { it.toByte() })
    val deviceId = TrustIds.deviceIdOf(device.publicKey)
    val head = DeviceHead(familyId, deviceId, "Public", HeadKind.INDEX, 1, 1, clock.nowMillis(), listOf(ref)).sign(device)
    val accepted = HeadVerifier.accept(head, registry, HeadLocation("Public", deviceId, HeadKind.INDEX, 1), null, "admin")
    assertNotNull(accepted.acceptedOrNull())

    // Un byte cambiato e la firma del Keystore non vale piu'.
    val tampered = head.copyOf().also { it[it.size - 10] = (it[it.size - 10].toInt() xor 1).toByte() }
    assertEquals(RejectReason.BAD_SIGNATURE, HeadVerifier.accept(tampered, registry, HeadLocation("Public", deviceId, HeadKind.INDEX, 1), null, "admin").rejectionOrNull())
  }

  /**
   * Revisione 1.4, M3: la chiave che cifra la chiave di famiglia si usa solo a telefono sbloccato; la chiave
   * del dispositivo no (i lavori notturni firmano a telefono bloccato) e non chiede autenticazione.
   */
  @Test
  fun politicheDelleChiavi_cassafortesoloSbloccato_chiaveDelDispositivoAncheBloccato() {
    val wrapping = AndroidKeystoreFamilyKeyVault.wrappingKeySpec("x", strongBox = false)
    val device = AndroidKeystoreKeyProvider.keySpec("y", strongBox = false)

    assertTrue(wrapping.isUnlockedDeviceRequired)
    assertFalse(device.isUnlockedDeviceRequired)
    assertFalse(device.isUserAuthenticationRequired)
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

  @Test
  fun cassafortedellaChiaveDiFamigliaFaIlGiro() {
    val familyKey = FamilyKey.generate()
    vault.store(familyKey)

    val loaded = vault.load(familyKey.publicKey)
    assertNotNull(loaded)
    val message = "registro".toByteArray()
    assertTrue(familyKey.publicKey.verify(message, loaded!!.sign(message)))
    assertNull("chiave attesa diversa", vault.load(FamilyKey.generate().publicKey))
    assertFalse("la chiave pubblica non compare in chiaro nel file", vaultFile.readBytes().containsSequence(familyKey.publicKey.encoded))

    vault.destroy()
    assertNull(vault.load(familyKey.publicKey))
  }

  private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
