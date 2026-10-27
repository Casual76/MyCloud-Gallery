package dev.pampa.mycloudgallery.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.credentials.AndroidSecretKeyStore
import dev.pampa.mycloudgallery.core.security.credentials.KeyProtectionLevel
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyAliases
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyState
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyStore
import dev.pampa.mycloudgallery.core.security.testing.FacesKeysContract
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le chiavi dei volti sul Keystore VERO dell'S25 (Fase 8, P6; `docs/fase8-progetto.md` D14, D29, E-F19, E-F35; `docs/sicurezza.md` C39,
 * C45). **Scritto e non eseguito** durante P6 (nessun telefono): lo esegue chi ha il dispositivo (vedi `docs/decisioni.md`, "Fase 8 P6").
 *
 *     gradlew :core-security:connectedDebugAndroidTest --tests "*FacesKeyStoreInstrumentedTest"
 *
 * Cosa deve dimostrare sul telefono: la stessa suite di contratto del fake (`FacesKeysContract`) passa sul Keystore vero; la chiave che
 * avvolge la chiave dati sta in hardware (StrongBox se c'e', altrimenti TEE), non si estrae e non chiede ne' autenticazione ne' telefono
 * sbloccato (il calcolo gira di notte); distruggere toglie davvero l'alias dal Keystore; una chiave sparita dal Keystore (ripristino,
 * aggiornamento del sistema: la si toglie a mano dall'`AndroidKeyStore`) fa `open` -> null e `state` -> `UNUSABLE` senza crollo; i file
 * avvolti non contengono la chiave dati.
 *
 * Gira nel processo del test (un'altra UID dell'app vera): le chiavi `mcg-faces-*` dell'app non si toccano, e la suite di contratto, che
 * chiama `destroyAll`, toglie solo quelle del test. Ogni prova usa generazioni casuali e una cartella in `cacheDir`.
 */
@RunWith(AndroidJUnit4::class)
class FacesKeyStoreInstrumentedTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

  private lateinit var dir: File
  private lateinit var keystore: AndroidSecretKeyStore
  private lateinit var keys: FacesKeyStore

  @Before
  fun setUp() {
    dir = File(context.cacheDir, "faces-keys-test-" + UUID.randomUUID()).apply { mkdirs() }
    keystore = AndroidSecretKeyStore(hasStrongBox)
    keys = FacesKeyStore(keystore, { androidAliases() }, dir, allowSoftwareKey = false)
  }

  @After
  fun tearDown() {
    runBlocking { keys.destroyAll() }
    dir.deleteRecursively()
  }

  private fun androidAliases(): List<String> = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList()

  private fun keyInfoOf(generation: FaceGeneration): KeyInfo {
    val key = keystore.find(FacesKeyAliases.aliasOf(generation))!!
    return SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
  }

  @Test
  fun laSuiteDiContrattoPassaSulKeystoreVero() = runBlocking<Unit> {
    FacesKeysContract.verify(keys)
  }

  @Test
  fun laChiaveCheAvvolgeStaInHardwareNonSiEstraeENonChiedeAutenticazione() = runBlocking<Unit> {
    val g = FaceGeneration.random()
    assertTrue(keys.create(g))
    val level = keys.protectionLevel(g)
    assertNotNull(level)
    assertTrue("la chiave deve stare in hardware, invece e' $level", level!!.isHardwareBacked)
    assertEquals(if (hasStrongBox) KeyProtectionLevel.STRONGBOX else KeyProtectionLevel.TRUSTED_ENVIRONMENT, level)
    val key = keystore.find(FacesKeyAliases.aliasOf(g))!!
    assertNull("la chiave non deve essere estraibile", key.encoded)
    assertEquals("AES", key.algorithm)
    val info = keyInfoOf(g)
    assertFalse("l'autenticazione dell'utente non deve servire (calcolo notturno)", info.isUserAuthenticationRequired)
    assertEquals(256, info.keySize)
    assertEquals(listOf("GCM"), info.blockModes.toList())
    assertEquals(KeyProperties.ORIGIN_GENERATED, info.origin)
    if (Build.VERSION.SDK_INT >= 31) {
      val expected = if (hasStrongBox) KeyProperties.SECURITY_LEVEL_STRONGBOX else KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
      assertEquals(expected, info.securityLevel)
    }
  }

  @Test
  fun distruggereToglieDavveroLAliasDalKeystoreEIlFileAvvolto() = runBlocking<Unit> {
    val g = FaceGeneration.random()
    assertTrue(keys.create(g))
    assertTrue(FacesKeyAliases.aliasOf(g) in androidAliases())
    assertTrue(File(dir, FacesKeyAliases.aliasOf(g) + ".key").isFile)
    assertTrue(keys.destroy(g))
    assertFalse(FacesKeyAliases.aliasOf(g) in androidAliases())
    assertFalse(File(dir, FacesKeyAliases.aliasOf(g) + ".key").exists())
    assertNull(keys.open(g))
    assertEquals(FacesKeyState.MISSING, keys.state(g))
  }

  @Test
  fun unaChiaveSparitaDalKeystoreEIllegibileSenzaCrolloEsiRifaConCreate() = runBlocking<Unit> {
    val g = FaceGeneration.random()
    assertTrue(keys.create(g))
    val sealed = keys.open(g)!!.use { it.seal("nome di prova".toByteArray(), "aad".toByteArray()) }
    // Il ripristino del telefono: l'alias sparisce dal Keystore, il file avvolto resta.
    keystore.delete(FacesKeyAliases.aliasOf(g))
    assertEquals(FacesKeyState.UNUSABLE, keys.state(g))
    assertNull(keys.open(g))
    // create ne fa una nuova; i dati cifrati con la vecchia non si leggono piu' (si cancellano e si riscaricano, E-F19).
    assertTrue(keys.create(g))
    assertEquals(FacesKeyState.USABLE, keys.state(g))
    assertNull(keys.open(g)!!.use { it.open(sealed, "aad".toByteArray()) })
  }

  @Test
  fun ilFileAvvoltoNonContieneLaChiaveDatiNeIlTesto() = runBlocking<Unit> {
    val g = FaceGeneration.random()
    assertTrue(keys.create(g))
    val blob = File(dir, FacesKeyAliases.aliasOf(g) + ".key").readBytes()
    // [versione][IV 12][chiave dati 32 + tag 16]: la chiave dati sta solo avvolta.
    assertEquals(1 + 12 + 32 + 16, blob.size)
    val sealed = keys.open(g)!!.use { it.seal("Nome Di Prova".toByteArray(), "aad".toByteArray()) }
    assertFalse(String(sealed, Charsets.ISO_8859_1).contains("Nome Di Prova"))
  }
}
