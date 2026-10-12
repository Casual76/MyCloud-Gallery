package dev.pampa.mycloudgallery.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.security.credentials.AesGcm
import dev.pampa.mycloudgallery.core.security.credentials.AndroidSecretKeyStore
import dev.pampa.mycloudgallery.core.security.credentials.CredentialStatus
import dev.pampa.mycloudgallery.core.security.credentials.CredentialVault
import dev.pampa.mycloudgallery.core.security.credentials.KeyProtectionLevel
import dev.pampa.mycloudgallery.core.security.credentials.LoadResult
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.security.credentials.SystemKeyMaterialGenerator
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'archivio delle credenziali sul Keystore VERO dell'S25 (piano 1.5; docs/sicurezza.md C14, E-S9): cifra e
 * decifra davvero, la chiave sta in StrongBox (o nel TEE come ripiego), non si estrae, non richiede ne'
 * autenticazione ne' il telefono sbloccato (quindi vale per il backup notturno), e si comporta come previsto
 * quando la chiave sparisce, viene sostituita o il blocco viene manomesso.
 *
 * Usa alias casuali e un DataStore in `cacheDir`: non tocca mai le credenziali dell'app vera.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreVaultInstrumentedTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
  private val alias = "mcg.test." + UUID.randomUUID()
  private val extraAliases = mutableListOf<String>()

  private lateinit var dir: File
  private lateinit var scope: CoroutineScope
  private lateinit var store: DataStore<Preferences>
  private lateinit var keys: AndroidSecretKeyStore
  private lateinit var vault: CredentialVault

  @Before
  fun setUp() {
    dir = File(context.cacheDir, "vault-test-" + UUID.randomUUID()).apply { mkdirs() }
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "credentials.preferences_pb") }
    keys = AndroidSecretKeyStore(hasStrongBox)
    vault = CredentialVault(keys, store, allowSoftwareKey = false, dispatchers = DefaultDispatcherProvider, keyAlias = alias)
  }

  @After
  fun tearDown() {
    runCatching { keys.delete(alias) }
    extraAliases.forEach { runCatching { keys.delete(it) } }
    scope.cancel()
    dir.deleteRecursively()
  }

  private fun creds(password: String = "password-lunga-e-unica-di-prova") = NasCredentials("utente-di-prova", password.toCharArray())

  private fun keyInfo(key: SecretKey): KeyInfo =
    SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo

  private fun blobKey() = CredentialVault.blobKey("nas")

  // --- cifra e decifra davvero --------------------------------------------------------------------------------

  @Test
  fun salvaERilegge_conLaChiaveVera_edeiDatiNonInChiaro() {
    val saved = runBlocking { creds().use { vault.save("nas", it) } }
    assertTrue("atteso Saved, trovato $saved", saved is SaveResult.Saved)
    val level = (saved as SaveResult.Saved).protection
    Log.i(TAG, "livello della chiave sull'S25: $level (feature StrongBox: $hasStrongBox, SDK ${Build.VERSION.SDK_INT})")
    assertTrue("la chiave deve stare in hardware, invece e' $level", level.isHardwareBacked)

    val found = runBlocking { vault.load("nas") }
    assertTrue("atteso Found, trovato $found", found is LoadResult.Found)
    (found as LoadResult.Found).credentials.use {
      assertEquals("utente-di-prova", it.username)
      assertEquals("password-lunga-e-unica-di-prova", String(it.password.value))
    }
    assertEquals(CredentialStatus.READY, runBlocking { vault.verify() })

    val onDisk = File(dir, "credentials.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
    assertFalse(onDisk.contains("utente-di-prova"))
    assertFalse(onDisk.contains("password-lunga"))
  }

  @Test
  fun strongBox_seIlTelefonoLoHa_altrimentiTee() {
    val saved = runBlocking { creds().use { vault.save("nas", it) } } as SaveResult.Saved
    val expected = if (hasStrongBox) KeyProtectionLevel.STRONGBOX else KeyProtectionLevel.TRUSTED_ENVIRONMENT
    assertEquals(expected, saved.protection)
    assertEquals(expected, runBlocking { vault.protectionLevel() })
    if (Build.VERSION.SDK_INT >= 31) {
      val info = keyInfo(keys.find(alias)!!)
      val expectedLevel = if (hasStrongBox) KeyProperties.SECURITY_LEVEL_STRONGBOX else KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
      assertEquals(expectedLevel, info.securityLevel)
    }
  }

  @Test
  fun ilGeneratoreVero_inStrongBoxETee_dandoIlLivelloGiusto() {
    val teeAlias = "$alias.tee".also { extraAliases += it }
    val tee = SystemKeyMaterialGenerator.generate(teeAlias, strongBox = false)
    assertEquals(KeyProtectionLevel.TRUSTED_ENVIRONMENT, keys.protectionLevel(tee))

    val boxAlias = "$alias.box".also { extraAliases += it }
    if (hasStrongBox) {
      val box = SystemKeyMaterialGenerator.generate(boxAlias, strongBox = true)
      assertEquals(KeyProtectionLevel.STRONGBOX, keys.protectionLevel(box))
    } else {
      try {
        SystemKeyMaterialGenerator.generate(boxAlias, strongBox = true)
        fail("senza StrongBox la generazione doveva fallire")
      } catch (expected: StrongBoxUnavailableException) {
        // e' proprio il caso che il ripiego deve coprire
      }
    }
  }

  @Test
  fun ripiegoSulTee_conIlKeystoreVero_quandoStrongBoxNonRisponde() {
    val fallbackAlias = "$alias.fallback".also { extraAliases += it }
    val failingStrongBox = AndroidSecretKeyStore(
      hasStrongBox = true,
      generator = { a, strongBox ->
        if (strongBox) throw StrongBoxUnavailableException("simulato") else SystemKeyMaterialGenerator.generate(a, strongBox = false)
      },
    )

    val created = failingStrongBox.create(fallbackAlias)

    assertEquals(KeyProtectionLevel.TRUSTED_ENVIRONMENT, created.level)
    val sealed = AesGcm.seal(created.key, "prova".toByteArray(), "aad".toByteArray())
    assertEquals("prova", String(AesGcm.open(created.key, sealed, "aad".toByteArray())))
  }

  // --- la chiave e' usabile in background senza sblocco (scelta dichiarata) ---------------------------------

  @Test
  fun laChiaveNonEEstraibile_enonRichiedeAutenticazioneNeTelefonoSbloccato() {
    runBlocking { creds().use { vault.save("nas", it) } }
    val key = keys.find(alias)!!

    assertNull("la chiave non deve essere estraibile", key.encoded)
    assertEquals("AES", key.algorithm)
    val info = keyInfo(key)
    assertFalse("l'autenticazione dell'utente non deve servire (backup notturno)", info.isUserAuthenticationRequired)
    assertEquals(256, info.keySize)
    assertEquals(listOf("GCM"), info.blockModes.toList())
    assertEquals(listOf("NoPadding"), info.encryptionPaddings.toList())
    assertEquals(KeyProperties.ORIGIN_GENERATED, info.origin)
    assertTrue(keys.protectionLevel(key).isHardwareBacked)
  }

  @Test
  fun siUsaDaUnThreadInBackground_senzaActivity() {
    runBlocking { creds().use { vault.save("nas", it) } }
    val executor = Executors.newSingleThreadExecutor()
    try {
      val result = executor.submit<LoadResult> { runBlocking { vault.load("nas") } }.get(30, TimeUnit.SECONDS)
      assertTrue(result is LoadResult.Found)
      (result as LoadResult.Found).credentials.close()
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun tempiDiCifraturaEDecifratura_conStrongBox() {
    runBlocking { creds().use { vault.save("nas", it) } }
    val started = System.nanoTime()
    repeat(5) { runBlocking { (vault.load("nas") as LoadResult.Found).credentials.close() } }
    val perLoadMs = (System.nanoTime() - started) / 5 / 1_000_000
    Log.i(TAG, "decifratura media: $perLoadMs ms (livello ${runBlocking { vault.protectionLevel() }})")
    assertTrue("una decifratura non deve richiedere secondi: $perLoadMs ms", perLoadMs < 2_000)
  }

  // --- E-S9: chiave sparita, sostituita, blocco manomesso -----------------------------------------------------

  @Test
  fun chiaveCancellataDalKeystore_serveDiNuovoLaPassword() {
    runBlocking { creds().use { vault.save("nas", it) } }
    keys.delete(alias) // come dopo un ripristino: i dati ci sono, la chiave no

    assertEquals(LoadResult.NeedsPassword, runBlocking { vault.load("nas") })
    assertEquals(CredentialStatus.NEEDS_PASSWORD, runBlocking { vault.status.first() })

    // l'utente reinserisce la password: tutto torna a posto con una chiave nuova
    assertTrue(runBlocking { creds("nuova-password").use { vault.save("nas", it) } } is SaveResult.Saved)
    (runBlocking { vault.load("nas") } as LoadResult.Found).credentials.use { assertEquals("nuova-password", String(it.password.value)) }
  }

  @Test
  fun chiaveSostituitaConLoStessoAlias_ilBloccoVecchioNonSiApre() {
    runBlocking { creds().use { vault.save("nas", it) } }
    keys.delete(alias)
    keys.create(alias) // un'altra chiave, stesso nome

    assertEquals(LoadResult.NeedsPassword, runBlocking { vault.load("nas") })
    assertNotNull("la chiave nuova non va distrutta per un blocco vecchio", keys.find(alias))
  }

  @Test
  fun bloccoManomesso_inUnByte_vieneRifiutato() {
    runBlocking { creds().use { vault.save("nas", it) } }
    val encoded = runBlocking { store.data.first()[blobKey()]!! }
    val bytes = java.util.Base64.getDecoder().decode(encoded)
    bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
    runBlocking { store.edit { it[blobKey()] = java.util.Base64.getEncoder().encodeToString(bytes) } }

    assertEquals(LoadResult.NeedsPassword, runBlocking { vault.load("nas") })
    assertNotNull("la chiave resta: il danno e' nel blocco, non nella chiave", keys.find(alias))
  }

  @Test
  fun unBloccoCopiatoInUnAltraVoce_nonSiApre() {
    runBlocking {
      creds().use { vault.save("nas", it) }
      store.edit { it[CredentialVault.blobKey("altro")] = it[blobKey()]!! }
    }
    assertEquals(LoadResult.NeedsPassword, runBlocking { vault.load("altro") })
  }

  // --- cancellazione sicura sul Keystore vero ----------------------------------------------------------------------

  @Test
  fun wipeAll_distruggeLaChiaveVera_poiIDati() {
    runBlocking { creds().use { vault.save("nas", it) } }
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    assertTrue(keyStore.containsAlias(alias))

    val result = runBlocking { vault.wipeAll() }

    assertTrue("wipe incompleto: $result", result.complete)
    assertFalse("la chiave deve essere sparita dal Keystore", keyStore.containsAlias(alias))
    assertEquals(CredentialStatus.EMPTY, runBlocking { vault.status.first() })
    assertEquals(LoadResult.Absent, runBlocking { vault.load("nas") })
  }

  private companion object {
    const val TAG = "KeystoreVaultIT"
  }
}
