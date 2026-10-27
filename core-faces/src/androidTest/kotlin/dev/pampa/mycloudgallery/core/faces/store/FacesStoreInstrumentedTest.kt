package dev.pampa.mycloudgallery.core.faces.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabases
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyAliases
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyStore
import java.io.File
import java.security.KeyStore
import java.util.Random
import java.util.UUID
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il database dei volti cifrato con la chiave VERA del Keystore dell'S25 (Fase 8, P6; `docs/fase8-progetto.md` D14, D29, E-F19;
 * `docs/sicurezza.md` C39, C45). **Scritto e non eseguito** durante P6 (nessun telefono): lo esegue chi ha il dispositivo.
 *
 *     gradlew :core-faces:connectedDebugAndroidTest --tests "*FacesStoreInstrumentedTest"
 *
 * Le stesse garanzie della prova sulla JVM (`FacesStoreEncryptionTest`, `FacesStoresTest`) ma con il Keystore vero e il SQLite vero del
 * telefono: le colonne si cifrano e si decifrano con la chiave dati avvolta dal Keystore; nel file (compreso il `-wal`) non c'e' un solo
 * valore seminato in chiaro; con la chiave distrutta (o sparita dal Keystore, come dopo un ripristino) il file non si legge piu' e il negozio
 * riparte vuoto senza crollo (`FACES_KEY_LOST`, E-F19); la cancellazione toglie l'alias dal Keystore e tutti i file del database.
 *
 * Gira nel processo del test (un'altra UID dell'app vera): le chiavi `mcg-faces-*` dell'app non si toccano. Ogni prova usa una generazione
 * casuale e una cartella propria in `noBackupFilesDir`, e ripulisce.
 */
@RunWith(AndroidJUnit4::class)
class FacesStoreInstrumentedTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val generation = FaceGeneration.random()
  private lateinit var directory: File
  private lateinit var keys: FacesKeyStore
  private lateinit var stores: FacesStores

  // Nomi inventati e vettori seminati (nessuna persona vera).
  private val name = "Nome-Seminato-Aurora-7731"

  private fun vector(seed: Int): ShortArray {
    val random = Random(seed * 7_919L + 13)
    val v = FloatArray(FaceSpaceCode.CURRENT.dimension) { random.nextGaussian().toFloat() }
    val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
    return Float16.encode(FloatArray(v.size) { v[it] / norm })
  }

  private fun body(n: Int): FaceBody {
    val faces = (0 until 3).map { i ->
      FaceEntry(
        i, 0, FaceBox(100 + i * 700, 1_000, 600 + i * 700, 6_000), ShortArray(10) { (1_000 + n * 13 + i * 31 + it * 97).toShort() }, 200,
        FaceTier.CORE, 0, 120, 0, 100, vector(n * 10 + i),
      )
    }
    return FaceBody(GroupingStories.contentKey(5, n), FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, 1, FaceSource.ANALYSIS, "dispositivo-di-prova", faces, 1_000L + n)
  }

  private fun bytesOfDatabase(): List<ByteArray> =
    listOf("", "-wal", "-shm").map { File(directory, "faces-${generation.base32}.db$it") }.filter { it.exists() }.map { it.readBytes() }

  private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return true
    }
    return false
  }

  @Before
  fun setUp() {
    directory = File(context.noBackupFilesDir, "faces-test-" + UUID.randomUUID())
    keys = FacesKeyStore.forContext(context, allowSoftwareKey = false)
    stores = FacesStores(directory, FacesDatabases.forContext(context), keys)
  }

  @After
  fun tearDown() {
    runBlocking { stores.eraseLocal(generation) }
    directory.deleteRecursively()
  }

  @Test
  fun lePrimeColonneSiCifranoSiDecifranoENelFileNonCeNienteInChiaro() = runBlocking<Unit> {
    val store = (stores.open(generation) as FacesStoreOpen.Ready).store
    val seeded = (1..5).map(::body)
    seeded.forEach { store.archive.saveBody(it) }
    val person = PersonId.fromBytes(ByteArray(16) { 3 })
    store.archive.addPeople(listOf(PeopleRecord(PeopleEdit.Name(person, name), 1_000L, "dispositivo-di-prova")))

    // Si rilegge tutto con la chiave vera.
    val read = store.archive.winners(seeded.map { it.contentKey })
    assertEquals(5, read.size)
    for (body in seeded) {
      val back = read.getValue(body.contentKey)
      for (i in body.faces.indices) {
        assertTrue(back.faces[i].vector!!.contentEquals(body.faces[i].vector!!))
        assertTrue(back.faces[i].landmarks.contentEquals(body.faces[i].landmarks))
      }
    }
    assertEquals(PeopleEdit.Name(person, name), store.archive.people().values.single().edit)
    assertEquals(15, store.archive.groupFaces().size)

    // Nel file e nel -wal, ancora aperto: nessun nome, nessun vettore (float16 in due ordini di byte), nessun landmark.
    fun check(files: List<ByteArray>) {
      assertTrue(files.isNotEmpty())
      for (file in files) {
        assertFalse(contains(file, name.toByteArray(Charsets.UTF_8)))
        assertFalse(contains(file, name.toByteArray(Charsets.UTF_16LE)))
        for (body in seeded) for (face in body.faces) {
          val v = face.vector!!
          val be = ByteArray(16) { i -> val h = v[i / 2].toInt(); (if (i % 2 == 0) h shr 8 else h).toByte() }
          val le = ByteArray(16) { i -> val h = v[i / 2].toInt(); (if (i % 2 == 0) h else h shr 8).toByte() }
          assertFalse(contains(file, be))
          assertFalse(contains(file, le))
        }
      }
    }
    check(bytesOfDatabase())
    store.close()
    check(bytesOfDatabase())
  }

  @Test
  fun conLaChiaveDistruttaIlFileNonSiLeggePiuEIlNegozioRipartePulito() = runBlocking<Unit> {
    val store = (stores.open(generation) as FacesStoreOpen.Ready).store
    store.archive.saveBody(body(1))
    store.close()
    assertTrue(keys.destroy(generation))
    assertTrue(File(directory, "faces-${generation.base32}.db").exists())

    val result = stores.open(generation)
    assertTrue("atteso Recovered, trovato $result", result is FacesStoreOpen.Recovered)
    result as FacesStoreOpen.Recovered
    assertEquals(FacesRecovery.KEY_LOST, result.reason)
    assertEquals(0, result.store.counts().bodies)
    assertTrue(result.store.archive.winners(listOf(body(1).contentKey)).isEmpty())
    result.store.close()
  }

  @Test
  fun unaChiaveSparitaDalKeystoreComeDopoUnRipristinoNonFaCrollare() = runBlocking<Unit> {
    val store = (stores.open(generation) as FacesStoreOpen.Ready).store
    store.archive.saveBody(body(1))
    store.close()
    // Il ripristino del telefono: l'alias sparisce dal Keystore, il file avvolto e il database restano.
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(FacesKeyAliases.aliasOf(generation))

    val result = stores.open(generation)
    assertTrue("atteso Recovered, trovato $result", result is FacesStoreOpen.Recovered)
    assertEquals(FacesRecovery.KEY_LOST, (result as FacesStoreOpen.Recovered).reason)
    // Il negozio nuovo funziona con la chiave nuova.
    result.store.archive.saveBody(body(2))
    assertEquals(1, result.store.counts().bodies)
    result.store.close()
    val again = stores.open(generation)
    assertTrue(again is FacesStoreOpen.Ready)
    assertEquals(1, (again as FacesStoreOpen.Ready).store.counts().bodies)
    again.store.close()
  }

  @Test
  fun laCancellazioneTogliePrimaLAliasDalKeystoreEPoiTuttiIFileDelDatabase() = runBlocking<Unit> {
    val store = (stores.open(generation) as FacesStoreOpen.Ready).store
    store.archive.saveBody(body(1))
    val alias = FacesKeyAliases.aliasOf(generation)
    val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    assertTrue(keystore.containsAlias(alias))
    assertNotNull(store.archive)

    val result = stores.eraseLocal(generation)

    assertEquals(LocalErase(keyDestroyed = true, filesGone = true), result)
    assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    assertFalse(stores.hasFiles(generation))
    assertTrue(stores.generationsOnDisk().isEmpty())
    assertFalse(store.isOpen)
  }
}
