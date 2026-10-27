package dev.pampa.mycloudgallery.core.faces.store

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.log.LogSink
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyProbe
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyState
import dev.pampa.mycloudgallery.core.security.faces.FacesKeys
import dev.pampa.mycloudgallery.core.security.testing.FakeFacesKeys
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P6, D14, D29, E-F8, E-F19, E-F33, E-F35): dove stanno i database dei volti e cosa succede quando si aprono e quando si cancellano.
 * Un database per generazione; i dati sopravvivono a una chiusura e a un processo nuovo; **chiave sparita = `FACES_KEY_LOST` senza crollo** (il
 * negozio e' nuovo e vuoto, si riscarica o si ricalcola) ma un guasto passeggero del Keystore non cancella mai niente; un file danneggiato si
 * rifa'; un database di una versione futura non si tocca; la cancellazione toglie **prima la chiave, poi il database con `-wal` e `-shm`**, con
 * la cancellazione verificata, e si puo' ripetere finche' non e' finita.
 */
@RunWith(AndroidJUnit4::class)
class FacesStoresTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val log = mutableListOf<String>()

  /** Un registratore delle operazioni sulle chiavi con, a ogni distruzione, quali file del database c'erano ancora in quel momento. */
  private class OrderedKeys(
    val inner: FakeFacesKeys,
    val snapshot: () -> List<String>,
  ) : FacesKeys {
    val filesAtDestroy = mutableListOf<List<String>>()

    override suspend fun create(generation: FaceGeneration) = inner.create(generation)

    override suspend fun open(generation: FaceGeneration): FacesDataKey? = inner.open(generation)

    override suspend fun destroy(generation: FaceGeneration): Boolean {
      filesAtDestroy += snapshot()
      return inner.destroy(generation)
    }

    override suspend fun destroyAll(): Boolean {
      filesAtDestroy += snapshot()
      return inner.destroyAll()
    }
  }

  /** Chiavi che sanno dire come stanno ([FacesKeyProbe]) e che si possono far tacere: il Keystore vero. */
  private class ProbedKeys(val inner: FakeFacesKeys) : FacesKeys, FacesKeyProbe {
    var silent = false
    var createRefused = false

    override suspend fun create(generation: FaceGeneration) = if (createRefused) false else inner.create(generation)

    override suspend fun open(generation: FaceGeneration): FacesDataKey? = if (silent) null else inner.open(generation)

    override suspend fun destroy(generation: FaceGeneration) = inner.destroy(generation)

    override suspend fun destroyAll() = inner.destroyAll()

    override suspend fun state(generation: FaceGeneration): FacesKeyState = when {
      silent -> FacesKeyState.UNAVAILABLE
      inner.exists(generation) -> FacesKeyState.USABLE
      else -> FacesKeyState.MISSING
    }
  }

  @Before
  fun installLog() {
    GalleryLog.install(LogSink { level, tag, message, throwable -> log += "$level $tag $message ${throwable?.javaClass?.simpleName.orEmpty()}" })
  }

  @After
  fun uninstallLog() {
    GalleryLog.uninstall()
  }

  private fun newWorld(wrap: (FakeFacesKeys) -> FacesKeys = { it }) =
    StoreWorld(File(folder.root, "faces"), wrap = wrap)

  private val g1 = FaceGeneration.fromBytes(ByteArray(16) { 1 })
  private val g2 = FaceGeneration.fromBytes(ByteArray(16) { 2 })
  private val g3 = FaceGeneration.fromBytes(ByteArray(16) { 3 })

  private suspend fun fill(store: FacesStore, count: Int = 3) {
    for (n in 1..count) store.archive.saveBody(FaceFixtures.body(FaceFixtures.key(n)))
    store.archive.addPeople(listOf(PeopleRecord(PeopleEdit.Name(PersonId.fromBytes(ByteArray(16) { 5 }), "Nome-Di-Prova-1"), 100L, FaceFixtures.AUTHOR_SMALL)))
  }

  private suspend fun assertFilled(store: FacesStore, count: Int = 3) {
    assertThat(store.archive.winners((1..count).map { FaceFixtures.key(it) })).hasSize(count)
    assertThat(store.archive.people()).hasSize(1)
  }

  /** Il file principale diventa spazzatura e non ha un giornale che lo ricostruisca: un file danneggiato davvero. */
  private fun corrupt(world: StoreWorld, generation: FaceGeneration) {
    world.filesOf(generation).filter { it != world.mainFile(generation) }.forEach { it.delete() }
    world.mainFile(generation).writeBytes(ByteArray(8_192) { (it * 31).toByte() })
  }

  // --- aprire --------------------------------------------------------------------------------------------------------------------

  @Test
  fun ogniGenerazioneHaIlSuoFileELaSuaChiaveEIDatiSopravvivonoAUnaChiusura() = runBlocking<Unit> {
    val world = newWorld()
    val first = world.stores.open(g1)
    assertThat(first).isInstanceOf(FacesStoreOpen.Ready::class.java)
    val store = (first as FacesStoreOpen.Ready).store
    assertThat(world.mainFile(g1).isFile).isTrue()
    assertThat(world.fake.exists(g1)).isTrue()
    assertThat(world.stores.generationsOnDisk()).containsExactly(g1)
    fill(store)
    // Lo stesso oggetto finche' e' aperto.
    assertThat((world.stores.open(g1) as FacesStoreOpen.Ready).store).isSameInstanceAs(store)

    store.close()
    assertThat(store.isOpen).isFalse()
    val again = (world.stores.open(g1) as FacesStoreOpen.Ready).store
    assertThat(again).isNotSameInstanceAs(store)
    assertFilled(again)

    // Un'altra generazione e' un altro file, un'altra chiave, un altro archivio.
    val other = (world.stores.open(g2) as FacesStoreOpen.Ready).store
    assertThat(other.archive.winners(listOf(FaceFixtures.key(1)))).isEmpty()
    assertThat(world.stores.generationsOnDisk()).containsExactly(g1, g2).inOrder()
    again.close()
    other.close()
    assertThat(world.fake.openKeys).isEqualTo(0)
  }

  @Test
  fun unProcessoNuovoRitrovaIDati() = runBlocking<Unit> {
    val world = newWorld()
    val store = world.ready(g1)
    fill(store)
    store.close()
    // Un processo nuovo: altri oggetti sulla stessa cartella e sulle stesse chiavi.
    val restarted = StoreWorld(world.directory, world.fake)
    assertFilled(restarted.ready(g1))
  }

  @Test
  fun aperturaInParalleloDaUnSoloNegozio() = runBlocking<Unit> {
    val world = newWorld()
    val stores = (1..8).map { async(Dispatchers.Default) { (world.stores.open(g1) as FacesStoreOpen.Ready).store } }.awaitAll()
    assertThat(stores.toSet()).hasSize(1)
    stores.first().close()
  }

  // --- E-F19: la chiave sparita --------------------------------------------------------------------------------------------------

  @Test
  fun chiaveSparitaFaccioFacesKeyLostSenzaCrolloEIlNegozioRiparteVuoto() = runBlocking<Unit> {
    val world = newWorld()
    val old = world.ready(g1)
    fill(old)
    old.close()
    val oldBytes = world.mainFile(g1).readBytes()

    world.fake.lose(g1) // ripristino del telefono o aggiornamento del sistema: la chiave non c'e' piu'
    val result = world.stores.open(g1)
    assertThat(result).isInstanceOf(FacesStoreOpen.Recovered::class.java)
    result as FacesStoreOpen.Recovered
    assertThat(result.reason).isEqualTo(FacesRecovery.KEY_LOST)
    assertThat(result.reason.diag).isEqualTo(FacesDiag.FACES_KEY_LOST)
    // Nuovo, vuoto e funzionante: niente dei dati di prima, e il file di prima non c'e' piu'.
    assertThat(result.store.counts()).isEqualTo(FacesStoreCounts(0, 0))
    assertThat(result.store.archive.people()).isEmpty()
    assertThat(world.mainFile(g1).readBytes()).isNotEqualTo(oldBytes)
    assertThat(world.fake.exists(g1)).isTrue()
    result.store.archive.saveBody(FaceFixtures.body(FaceFixtures.key(1)))
    result.store.close()
    // Una volta sola: la volta dopo il negozio e' pronto e ha i dati nuovi.
    val next = world.stores.open(g1)
    assertThat(next).isInstanceOf(FacesStoreOpen.Ready::class.java)
    assertThat((next as FacesStoreOpen.Ready).store.counts().bodies).isEqualTo(1)
    next.store.close()
  }

  @Test
  fun unaChiaveRicreataDaAltriNonFaLeggereIlDatabaseDiUnaChiaveDiversa() = runBlocking<Unit> {
    val world = newWorld()
    val old = world.ready(g1)
    fill(old)
    old.close()
    // Qualcuno ricrea la chiave della stessa generazione (create dopo la perdita) senza passare dal negozio: il file e' di un'altra chiave.
    world.fake.lose(g1)
    assertThat(world.keys.create(g1)).isTrue()
    val result = world.stores.open(g1)
    assertThat(result).isInstanceOf(FacesStoreOpen.Recovered::class.java)
    result as FacesStoreOpen.Recovered
    assertThat(result.reason).isEqualTo(FacesRecovery.KEY_LOST)
    assertThat(result.store.counts().bodies).isEqualTo(0)
    result.store.close()
  }

  @Test
  fun unGuastoPasseggeroDelKeystoreNonCancellaMaiNiente() = runBlocking<Unit> {
    // Chiavi senza il modo di dire come stanno (il fake di P0): una risposta "nessuna chiave" isolata non basta a dichiararla sparita.
    val world = newWorld()
    val store = world.ready(g1)
    fill(store)
    store.close()
    world.fake.failNext = true
    val result = world.stores.open(g1)
    assertThat(result).isInstanceOf(FacesStoreOpen.Ready::class.java)
    assertFilled((result as FacesStoreOpen.Ready).store)
    result.store.close()

    // Chiavi che dicono come stanno (il Keystore vero): "non risponde" non e' "sparita", e non cancella niente.
    val probed = ProbedKeys(world.fake)
    val world2 = StoreWorld(world.directory, world.fake, wrap = { probed })
    probed.silent = true
    assertThat(world2.stores.open(g1)).isEqualTo(FacesStoreOpen.Unavailable(FacesUnavailable.KEYSTORE))
    assertThat(world2.mainFile(g1).isFile).isTrue()
    probed.silent = false
    assertFilled((world2.stores.open(g1) as FacesStoreOpen.Ready).store)
  }

  @Test
  fun unaChiaveCheNonSiCreaDiceNonAdessoENonLasciaNiente() = runBlocking<Unit> {
    val probed = ProbedKeys(FakeFacesKeys())
    val world = StoreWorld(File(folder.root, "faces"), probed.inner, wrap = { probed })
    probed.createRefused = true
    assertThat(world.stores.open(g1)).isEqualTo(FacesStoreOpen.Unavailable(FacesUnavailable.KEYSTORE))
    assertThat(world.filesOf(g1)).isEmpty()
    probed.createRefused = false
    assertThat(world.stores.open(g1)).isInstanceOf(FacesStoreOpen.Ready::class.java)
  }

  // --- un file danneggiato o di una versione futura --------------------------------------------------------------------------

  @Test
  fun unFileDanneggiatoSiRifaConLaStessaChiave() = runBlocking<Unit> {
    val world = newWorld()
    val store = world.ready(g1)
    fill(store)
    store.close()
    corrupt(world, g1)
    val result = world.stores.open(g1)
    assertThat(result).isInstanceOf(FacesStoreOpen.Recovered::class.java)
    result as FacesStoreOpen.Recovered
    assertThat(result.reason).isEqualTo(FacesRecovery.DATABASE_DAMAGED)
    assertThat(result.reason.diag).isNull()
    assertThat(result.store.counts().bodies).isEqualTo(0)
    // La chiave non e' stata toccata: nessuna distruzione.
    assertThat(world.fake.events.filter { it.startsWith("destroy") }).isEmpty()
    result.store.close()
  }

  @Test
  fun unDatabaseDiUnaVersioneFuturaNonSiApreENonSiCancella() = runBlocking<Unit> {
    val world = newWorld()
    val store = world.ready(g1)
    fill(store)
    store.close()
    SQLiteDatabase.openDatabase(world.mainFile(g1).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
    val before = world.mainFile(g1).readBytes()
    assertThat(world.stores.open(g1)).isEqualTo(FacesStoreOpen.Unavailable(FacesUnavailable.NEWER_DATABASE))
    assertThat(world.mainFile(g1).readBytes()).isEqualTo(before)
    assertThat(world.fake.exists(g1)).isTrue()
    // Anche con la chiave sparita (non si capisce niente di quel file: non si tocca).
    world.fake.lose(g1)
    assertThat(world.stores.open(g1)).isEqualTo(FacesStoreOpen.Unavailable(FacesUnavailable.NEWER_DATABASE))
    assertThat(world.mainFile(g1).readBytes()).isEqualTo(before)
  }

  // --- cancellare ----------------------------------------------------------------------------------------------------------------

  @Test
  fun laCancellazioneTogliePrimaLaChiaveEPoiIlDatabaseConWalEShm() = runBlocking<Unit> {
    lateinit var world: StoreWorld
    lateinit var ordered: OrderedKeys
    world = StoreWorld(
      File(folder.root, "faces"),
      wrap = { fake ->
        ordered = OrderedKeys(fake) { world.filesOf(g1).map { it.name.substringAfter(".db") } }
        ordered
      },
    )
    val store = world.ready(g1)
    fill(store)
    // Il database e' aperto con il giornale WAL: ci sono il file principale, il -wal e il -shm.
    assertThat(world.filesOf(g1).map { it.name.substringAfter(".db") }).containsAtLeast("", "-wal", "-shm")

    val result = world.stores.eraseLocal(g1)

    assertThat(result).isEqualTo(LocalErase(keyDestroyed = true, filesGone = true))
    assertThat(result.done).isTrue()
    // Nel momento in cui la chiave e' stata distrutta, TUTTI i file del database c'erano ancora: la chiave e' il primo passo.
    assertThat(ordered.filesAtDestroy).hasSize(1)
    assertThat(ordered.filesAtDestroy.single()).containsAtLeast("", "-wal", "-shm")
    // Dopo: nessun file, nessuna chiave, il negozio aperto non risponde piu', i file non risultano piu'.
    assertThat(world.filesOf(g1)).isEmpty()
    assertThat(world.stores.hasFiles(g1)).isFalse()
    assertThat(world.stores.generationsOnDisk()).isEmpty()
    assertThat(world.fake.exists(g1)).isFalse()
    assertThat(store.isOpen).isFalse()
    val failure = runCatching { store.archive.winners(listOf(FaceFixtures.key(1))) }
    assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    assertThat(world.fake.openKeys).isEqualTo(0)
    // Idempotente.
    assertThat(world.stores.eraseLocal(g1).done).isTrue()
  }

  @Test
  fun unFileCheNonSiTogliePerDavveroSiRiprovaEFinoAllaFineNonEDone() = runBlocking<Unit> {
    val world = newWorld()
    world.ready(g1).also { fill(it) }.close()
    // Il -journal non si toglie: una cartella piena al suo posto (DirectoryNotEmptyException su ogni sistema operativo).
    val obstacle = File(world.mainFile(g1).path + "-journal")
    assertThat(obstacle.mkdirs()).isTrue()
    File(obstacle, "resto").writeText("x")

    val first = world.stores.eraseLocal(g1)
    // La chiave e' distrutta (i dati sono gia' illeggibili) e il database principale e' sparito, ma la cancellazione NON e' finita.
    assertThat(first.keyDestroyed).isTrue()
    assertThat(first.filesGone).isFalse()
    assertThat(first.done).isFalse()
    assertThat(world.fake.exists(g1)).isFalse()
    assertThat(world.mainFile(g1).exists()).isFalse()
    assertThat(world.stores.hasFiles(g1)).isTrue()

    File(obstacle, "resto").delete()
    val second = world.stores.eraseLocal(g1)
    assertThat(second.done).isTrue()
    assertThat(world.stores.hasFiles(g1)).isFalse()
  }

  @Test
  fun unaChiaveCheNonSiDistruggeDiceNonFinitaMaIFileSiTolgono() = runBlocking<Unit> {
    val world = newWorld()
    world.ready(g1).also { fill(it) }.close()
    world.fake.failNext = true
    val first = world.stores.eraseLocal(g1)
    assertThat(first.keyDestroyed).isFalse()
    assertThat(first.filesGone).isTrue()
    assertThat(first.done).isFalse()
    // Si ripete: la chiave questa volta si distrugge.
    assertThat(world.stores.eraseLocal(g1)).isEqualTo(LocalErase(true, true))
    assertThat(world.fake.exists(g1)).isFalse()
  }

  @Test
  fun leGenerazioniNonCorrentiSiTolgonoTutteTranneLaCorrente() = runBlocking<Unit> {
    val world = newWorld()
    for (g in listOf(g1, g2, g3)) world.ready(g).also { fill(it) }.close()
    val keep = world.ready(g2)

    val result = world.stores.eraseAllExcept(keep = g2)

    assertThat(result.done).isTrue()
    assertThat(world.stores.generationsOnDisk()).containsExactly(g2)
    assertThat(world.fake.exists(g1)).isFalse()
    assertThat(world.fake.exists(g3)).isFalse()
    assertThat(world.fake.exists(g2)).isTrue()
    assertThat(keep.isOpen).isTrue()
    assertFilled(keep)
    // Nessuna corrente (Persone spenta): non resta niente.
    assertThat(world.stores.eraseAllExcept(keep = null).done).isTrue()
    assertThat(world.stores.generationsOnDisk()).isEmpty()
    assertThat(world.fake.aliases()).isEmpty()
  }

  @Test
  fun lAzzeramentoDelDispositivoDistruggeTutteLeChiaviPrimaDeiFile() = runBlocking<Unit> {
    lateinit var world: StoreWorld
    lateinit var ordered: OrderedKeys
    world = StoreWorld(
      File(folder.root, "faces"),
      wrap = { fake ->
        ordered = OrderedKeys(fake) { listOf(g1, g2, g3).flatMap { g -> world.filesOf(g) }.map { it.name } }
        ordered
      },
    )
    for (g in listOf(g1, g2)) world.ready(g).also { fill(it) }
    world.ready(g3).close()
    val result = world.stores.eraseEverything()
    assertThat(result.done).isTrue()
    assertThat(ordered.filesAtDestroy).hasSize(1)
    assertThat(ordered.filesAtDestroy.single().count { it.endsWith(".db") }).isEqualTo(3)
    assertThat(world.fake.aliases()).isEmpty()
    assertThat(world.stores.generationsOnDisk()).isEmpty()
    assertThat(world.fake.openKeys).isEqualTo(0)
  }

  // --- niente nei log ------------------------------------------------------------------------------------------------------------

  @Test
  fun neGenerazioniNeNomiNeChiaviNeiLog() = runBlocking<Unit> {
    val world = newWorld()
    val store = world.ready(g1)
    fill(store)
    store.close()
    corrupt(world, g1) // danneggiato
    (world.stores.open(g1) as FacesStoreOpen.Recovered).store.close()
    world.fake.lose(g1)
    (world.stores.open(g1) as FacesStoreOpen.Recovered).store.close()
    world.fake.failNext = true
    world.stores.eraseLocal(g1)
    world.stores.eraseLocal(g1)
    val everything = log.joinToString("\n")
    assertThat(everything).doesNotContain(g1.base32)
    assertThat(everything).doesNotContain("Nome-Di-Prova")
    assertThat(everything).doesNotContain(FaceFixtures.key(1).hex)
    assertThat(everything).doesNotContain(folder.root.name)
    assertThat(world.stores.toString()).doesNotContain(g1.base32)
  }
}
