package dev.pampa.mycloudgallery.core.security.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.credentials.AesGcm
import dev.pampa.mycloudgallery.core.security.credentials.GeneratedKey
import dev.pampa.mycloudgallery.core.security.credentials.KeyProtectionLevel
import dev.pampa.mycloudgallery.core.security.credentials.SecretKeyStore
import dev.pampa.mycloudgallery.core.security.testing.FacesKeysContract
import dev.pampa.mycloudgallery.core.security.testing.FakeSecretKeyStore
import dev.pampa.mycloudgallery.core.security.testing.RecordingLogSink
import java.io.File
import java.io.IOException
import javax.crypto.SecretKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fase 8 (P6, D14, D29, E-F19, E-F35): le chiavi dei volti sul Keystore, provate con un Keystore finto (`FakeSecretKeyStore`) e file veri in
 * una cartella temporanea. La stessa suite di contratto gira sul Keystore vero nel test strumentato (`FacesKeyStoreInstrumentedTest`, non
 * eseguito qui: serve il telefono). Quello che il contratto non dice e questa classe prova: la chiave dati non e' mai in chiaro su disco, la
 * distruzione toglie **prima** la chiave del Keystore e poi il file avvolto, una chiave sparita non manda in crash e si distingue da un
 * guasto passeggero (che non autorizza a cancellare niente), nessuna chiave senza Keystore hardware in release, nessun alias orfano, e
 * nessuna generazione, alias o byte nei log.
 */
@RunWith(RobolectricTestRunner::class)
class FacesKeyStoreTest {

  @get:Rule
  val folder = TemporaryFolder()

  private lateinit var fake: FakeSecretKeyStore
  private lateinit var directory: File
  private lateinit var logs: RecordingLogSink
  private val deleteHooks = mutableListOf<(String) -> Unit>()
  private val createHooks = mutableListOf<(String) -> Unit>()

  /** Il Keystore finto con un gancio sulla cancellazione: l'ordine "prima la chiave, poi il file" lo guarda chi lo registra. */
  private inner class HookedKeystore : SecretKeyStore {
    override fun find(alias: String): SecretKey? = fake.find(alias)

    override fun create(alias: String): GeneratedKey = fake.create(alias).also { createHooks.forEach { hook -> hook(alias) } }

    override fun delete(alias: String) {
      deleteHooks.forEach { it(alias) }
      fake.delete(alias)
    }

    override fun protectionLevel(key: SecretKey): KeyProtectionLevel = fake.protectionLevel(key)
  }

  private fun store(allowSoftwareKey: Boolean = false) =
    FacesKeyStore(HookedKeystore(), { fake.keys.keys.toList() }, directory, allowSoftwareKey)

  private fun blobOf(generation: FaceGeneration) = File(directory, FacesKeyAliases.aliasOf(generation) + ".key")

  private fun aadOf(generation: FaceGeneration) = "dev.pampa.mycloudgallery.faces.key.v1:${FacesKeyAliases.aliasOf(generation)}".toByteArray()

  @Before
  fun setUp() {
    logs = RecordingLogSink().install()
    fake = FakeSecretKeyStore()
    directory = File(folder.root, "faces-keys")
  }

  @After
  fun tearDown() {
    logs.uninstall()
  }

  @Test
  fun laSuiteDiContrattoPassaSulKeystoreFinto() = runBlocking {
    FacesKeysContract.verify(store())
  }

  @Test
  fun laChiaveDatiNonEMaiInChiaroSuDisco() = runBlocking<Unit> {
    val g = FaceGeneration.random()
    val keys = store()
    assertThat(keys.create(g)).isTrue()
    val blob = blobOf(g).readBytes()
    // [versione][IV 12][chiave dati 32 + tag 16]: avvolta dalla chiave del Keystore, mai la chiave dati da sola.
    assertThat(blob.size).isEqualTo(1 + 12 + 32 + 16)
    val material = AesGcm.open(fake.keys.getValue(FacesKeyAliases.aliasOf(g)), blob, aadOf(g))
    assertThat(material).hasLength(32)
    assertThat(indexOf(blob, material)).isEqualTo(-1)
    // L'unica cosa che apre il blocco e' la chiave del Keystore: con quella di un'altra generazione (o un altro aad) no.
    val other = FaceGeneration.random()
    keys.create(other)
    val wrong = runCatching { AesGcm.open(fake.keys.getValue(FacesKeyAliases.aliasOf(other)), blob, aadOf(g)) }
    assertThat(wrong.isFailure).isTrue()
    material.fill(0)
  }

  @Test
  fun distruggereTogliePrimaLaChiaveDelKeystoreEPoiIlFileAvvolto() = runBlocking<Unit> {
    val keys = store()
    val g = FaceGeneration.random()
    assertThat(keys.create(g)).isTrue()
    val seenAtDelete = mutableListOf<Pair<Boolean, Boolean>>()
    deleteHooks += { alias ->
      // Nel momento in cui la chiave del Keystore viene tolta, il file avvolto c'e' ancora e la chiave pure.
      seenAtDelete += (blobOf(g).isFile to (alias in fake.keys))
    }
    assertThat(keys.destroy(g)).isTrue()
    assertThat(seenAtDelete).containsExactly(true to true)
    assertThat(fake.keys).isEmpty()
    assertThat(blobOf(g).exists()).isFalse()
    assertThat(keys.open(g)).isNull()
    assertThat(keys.state(g)).isEqualTo(FacesKeyState.MISSING)
    // Idempotente.
    assertThat(keys.destroy(g)).isTrue()
  }

  @Test
  fun seIlFileAvvoltoNonSiTogliePerDavveroDestroyDiceDiRiprovareMaLaChiaveEGiaSparita() = runBlocking<Unit> {
    val keys = store()
    val g = FaceGeneration.random()
    keys.create(g)
    val file = blobOf(g)
    val sealed = keys.open(g)!!.use { it.seal(byteArrayOf(1, 2, 3), byteArrayOf(9)) }
    // Un file che non si toglie: una cartella piena al suo posto (DirectoryNotEmptyException, su ogni sistema operativo).
    assertThat(file.delete()).isTrue()
    assertThat(file.mkdirs()).isTrue()
    File(file, "resto").writeText("x")
    assertThat(keys.destroy(g)).isFalse()
    // La chiave del Keystore non c'e' piu': i dati cifrati sono gia' illeggibili, anche se la cancellazione non e' finita.
    assertThat(fake.keys).isEmpty()
    assertThat(keys.open(g)).isNull()
    // Si ritenta: tolto l'ostacolo, riesce.
    File(file, "resto").delete()
    assertThat(keys.destroy(g)).isTrue()
    assertThat(sealed).isNotEmpty()
  }

  @Test
  fun distruggereConIlKeystoreCheNonRispondeDiceFalsoMaIlFileAvvoltoSiTogliComunque() = runBlocking<Unit> {
    val keys = store()
    val g = FaceGeneration.random()
    keys.create(g)
    fake.findFailure = IOException("occupato")
    assertThat(keys.destroy(g)).isFalse()
    // La chiave dati esiste solo nel file avvolto: tolto quello non si ricostruisce piu', anche se l'alias fosse ancora nel Keystore.
    assertThat(blobOf(g).exists()).isFalse()
    fake.findFailure = null
    assertThat(keys.destroy(g)).isTrue()
    assertThat(fake.keys).isEmpty()
  }

  @Test
  fun unaChiaveDelKeystoreSparitaEInutilizzabileSenzaCrolloEDistingueDalGuastoPasseggero() = runBlocking<Unit> {
    val keys = store()
    val g = FaceGeneration.random()
    keys.create(g)
    val sealed = keys.open(g)!!.use { it.seal(byteArrayOf(5, 6, 7), byteArrayOf(1)) }
    assertThat(keys.state(g)).isEqualTo(FacesKeyState.USABLE)

    fake.loseAllKeys() // il ripristino del telefono: il file avvolto c'e', la chiave del Keystore no
    assertThat(keys.state(g)).isEqualTo(FacesKeyState.UNUSABLE)
    assertThat(keys.open(g)).isNull()

    // Un guasto passeggero del Keystore NON e' una chiave sparita: non si sa, non si cancella niente.
    fake.findFailure = IOException("occupato")
    assertThat(keys.state(g)).isEqualTo(FacesKeyState.UNAVAILABLE)
    assertThat(keys.open(g)).isNull()
    assertThat(keys.create(g)).isFalse()
    assertThat(blobOf(g).isFile).isTrue()
    fake.findFailure = null

    // Con la chiave sparita create ne fa una nuova: i dati cifrati con la vecchia non si leggono piu' (si cancellano e si riscaricano).
    assertThat(keys.create(g)).isTrue()
    assertThat(keys.state(g)).isEqualTo(FacesKeyState.USABLE)
    assertThat(keys.open(g)!!.use { it.open(sealed, byteArrayOf(1)) }).isNull()
  }

  @Test
  fun unFileAvvoltoDanneggiatoOCopiatoDaUnAltraGenerazioneEInutilizzabile() = runBlocking<Unit> {
    val keys = store()
    val a = FaceGeneration.random()
    val b = FaceGeneration.random()
    keys.create(a)
    keys.create(b)
    val good = blobOf(a).readBytes()

    blobOf(a).writeBytes(good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
    assertThat(keys.state(a)).isEqualTo(FacesKeyState.UNUSABLE)
    assertThat(keys.open(a)).isNull()

    blobOf(a).writeBytes(ByteArray(3))
    assertThat(keys.state(a)).isEqualTo(FacesKeyState.UNUSABLE)

    // Il file di B messo al posto di quello di A: ha un'altra chiave e un altro aad.
    blobOf(a).writeBytes(blobOf(b).readBytes())
    assertThat(keys.state(a)).isEqualTo(FacesKeyState.UNUSABLE)

    // La chiave del Keystore senza il suo file avvolto (morte del processo fra le due scritture): inutilizzabile, e create la rifa'.
    blobOf(a).delete()
    assertThat(keys.state(a)).isEqualTo(FacesKeyState.UNUSABLE)
    assertThat(keys.create(a)).isTrue()
    assertThat(keys.state(a)).isEqualTo(FacesKeyState.USABLE)
    assertThat(keys.state(b)).isEqualTo(FacesKeyState.USABLE)
  }

  @Test
  fun senzaKeystoreHardwareInReleaseLaChiaveNonSiCreaENonRestaNiente() = runBlocking<Unit> {
    val g = FaceGeneration.random()
    fake.level = KeyProtectionLevel.SOFTWARE
    assertThat(store(allowSoftwareKey = false).create(g)).isFalse()
    assertThat(fake.keys).isEmpty()
    assertThat(blobOf(g).exists()).isFalse()
    assertThat(store(allowSoftwareKey = false).open(g)).isNull()
    // Nelle build di debug sugli emulatori si puo'.
    assertThat(store(allowSoftwareKey = true).create(g)).isTrue()
    fake.level = KeyProtectionLevel.STRONGBOX
    assertThat(store().protectionLevel(g)).isEqualTo(KeyProtectionLevel.STRONGBOX)
  }

  @Test
  fun unFileAvvoltoCheNonSiScriveNonLasciaUnAliasOrfano() = runBlocking<Unit> {
    val g = FaceGeneration.random()
    // Subito dopo che il Keystore ha creato la chiave, la cartella dei file avvolti diventa un file: scrivere non si puo'.
    createHooks += {
      directory.deleteRecursively()
      directory.writeText("non una cartella")
    }
    assertThat(store().create(g)).isFalse()
    // Senza il file avvolto la chiave del Keystore non serve a niente: non resta un alias orfano.
    assertThat(fake.keys).isEmpty()
    createHooks.clear()
    directory.delete()
    assertThat(store().create(g)).isTrue()
    assertThat(store().state(g)).isEqualTo(FacesKeyState.USABLE)
  }

  @Test
  fun unaCreazioneCheFallisceNelKeystoreDiceFalsoSenzaCrollo() = runBlocking<Unit> {
    fake.createFailure = IllegalStateException("keystore guasto")
    val g = FaceGeneration.random()
    assertThat(store().create(g)).isFalse()
    assertThat(fake.keys).isEmpty()
    assertThat(blobOf(g).exists()).isFalse()
    fake.createFailure = null
    assertThat(store().create(g)).isTrue()
  }

  @Test
  fun destroyAllTogliaTutteLeChiaviDeiVoltiAncheOrfaneMaMaiLeAltre() = runBlocking<Unit> {
    val keys = store()
    val g1 = FaceGeneration.random()
    val g2 = FaceGeneration.random()
    keys.create(g1)
    keys.create(g2)
    // Una chiave del Keystore senza file (g3), un file senza chiave (g4) e un'altra chiave dell'app (le credenziali): quest'ultima resta.
    val g3 = FaceGeneration.random()
    val g4 = FaceGeneration.random()
    fake.create(FacesKeyAliases.aliasOf(g3))
    blobOf(g4).writeBytes(ByteArray(61))
    fake.create("dev.pampa.mycloudgallery.credentials.v1")

    assertThat(keys.destroyAll()).isTrue()
    assertThat(fake.keys.keys).containsExactly("dev.pampa.mycloudgallery.credentials.v1")
    assertThat(directory.listFiles().orEmpty()).isEmpty()
    for (g in listOf(g1, g2, g3, g4)) assertThat(keys.open(g)).isNull()
    assertThat(keys.destroyAll()).isTrue()
  }

  @Test
  fun creazioniInParalleloDannoUnaChiaveSola() = runBlocking<Unit> {
    val keys = store()
    val g = FaceGeneration.random()
    val results = (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { keys.create(g) } }.awaitAll()
    assertThat(results).doesNotContain(false)
    assertThat(fake.keys).hasSize(1)
    val sealed = keys.open(g)!!.use { it.seal(byteArrayOf(1), byteArrayOf(2)) }
    assertThat(keys.open(g)!!.use { it.open(sealed, byteArrayOf(2)) }).isEqualTo(byteArrayOf(1))
  }

  @Test
  fun laChiaveDatiApertaCifraBlocchiGrandiEVuotiERifiutaIDatiOstili() = runBlocking<Unit> {
    val keys = store()
    val g = FaceGeneration.random()
    keys.create(g)
    keys.open(g)!!.use { key ->
      val aad = "faces/vector/1".toByteArray()
      for (size in listOf(0, 1, 16, 1_000_003)) {
        val plain = ByteArray(size) { (it * 31).toByte() }
        val sealed = key.seal(plain, aad)
        assertThat(sealed.size).isEqualTo(1 + 12 + size + 16)
        assertThat(key.open(sealed, aad)).isEqualTo(plain)
      }
      val sealed = key.seal(byteArrayOf(1, 2, 3, 4), aad)
      assertThat(key.open(sealed.copyOf().also { it[0] = 2 }, aad)).isNull() // versione sconosciuta
      assertThat(key.open(sealed.copyOf().also { it[5] = (it[5].toInt() xor 0x40).toByte() }, aad)).isNull() // nonce alterato
      assertThat(key.open(ByteArray(0), aad)).isNull()
      assertThat(key.open(ByteArray(28), aad)).isNull()
      assertThat(key.open(ByteArray(4096) { 7 }, aad)).isNull()
    }
  }

  @Test
  fun neGenerazioniNeAliasNeByteFinisconoNeiLog() = runBlocking<Unit> {
    val keys = store()
    val g = FaceGeneration.random()
    keys.create(g)
    fake.findFailure = IOException("occupato")
    keys.state(g)
    keys.create(g)
    keys.destroy(g)
    fake.findFailure = null
    fake.createFailure = IllegalStateException("guasto")
    keys.destroy(g)
    keys.create(g)
    fake.createFailure = null
    fake.loseAllKeys()
    keys.open(g)
    keys.destroyAll()
    val everything = logs.everything
    assertThat(everything).doesNotContain(g.base32)
    assertThat(everything).doesNotContain(FacesKeyAliases.aliasOf(g))
    assertThat(everything).doesNotContain("faces-keys")
    assertThat(keys.toString()).doesNotContain(g.base32)
  }

  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return i
    }
    return -1
  }
}
