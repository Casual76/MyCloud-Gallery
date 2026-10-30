package dev.pampa.mycloudgallery.core.faces.erase

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.EraseStart
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.common.files.Patience
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabases
import dev.pampa.mycloudgallery.core.faces.store.FaceFixtures
import dev.pampa.mycloudgallery.core.faces.store.FacesStores
import dev.pampa.mycloudgallery.core.faces.store.StoreWorld
import dev.pampa.mycloudgallery.core.faces.switch.LocalEraseWhy
import dev.pampa.mycloudgallery.core.faces.testing.FailingFacesPorts
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesClock
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesRequirements
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.core.faces.testing.FakeSearchHistoryPurge
import dev.pampa.mycloudgallery.core.faces.testing.FakeSmartAlbumsPeople
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesLocalStore
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.noop.NoPersonalHeadsView
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryFacesPersonalSettings
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * "Disattiva e cancella" sul negozio vero dei volti (Room, Robolectric; chiavi finte di P0): l'invariante 1 del §2.8 sul disco (nessun
 * `faces-<G>.db*`, nessuna chiave `mcg-faces-<G>` dopo `LOCAL_DONE`), la chiave tolta quando tutti i file c'erano ancora, la cronologia ripulita, una
 * lettura dei nomi che non fa nascere un negozio, il recinto sulle porte vere degli archivi. Con la recinzione (D34) nessuna porta del NAS si chiama.
 */
@RunWith(AndroidJUnit4::class)
class RealStoreEraseTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val g1 = FaceGeneration.fromBytes(ByteArray(16) { 31 })
  private val g2 = FaceGeneration.fromBytes(ByteArray(16) { 32 })
  private val author = FaceFixtures.AUTHOR_SMALL

  private suspend fun filled(world: StoreWorld, generation: FaceGeneration, name: String) {
    val store = world.ready(generation)
    store.archive.saveBody(FaceFixtures.body(FaceFixtures.key(1), author, faces = listOf(FaceFixtures.entry(0, FaceTier.CORE))))
    store.archive.addPeople(listOf(PeopleRecord(PeopleEdit.Name(PersonId.random(), name), 1_800_000_000_000L, author)))
  }

  @Test
  fun laCancellazioneVeraTogliePrimaLaChiaveEPoiIFileELaCronologia() = runBlocking<Unit> {
    val world = StoreWorld(File(folder.root, "faces"))
    filled(world, g1, "Lontano")
    val history = FakeSearchHistoryPurge(mutableListOf("Lontano al lago", "tramonto"))
    val fence = FacesEraseFence(FakeFacesSwitch(initial = FacesState.On(g1, HereState.ACTIVE)))
    val erase = FacesLocalErase(StoresLocalData(world.stores), fence, history = history)
    assertThat(erase.erase(g1, LocalEraseWhy.ACCOUNT_OFF)).isTrue()
    assertThat(world.filesOf(g1)).isEmpty()
    assertThat(world.fake.exists(g1)).isFalse()
    assertThat(world.fake.events.indexOfFirst { it.startsWith("destroy") }).isAtLeast(0)
    assertThat(history.entries).containsExactly("tramonto")
    assertThat(erase.erase(g1, LocalEraseWhy.ACCOUNT_OFF)).isTrue()
  }

  @Test
  fun leggereINomiDiUnaGenerazioneSenzaFileNonFaNascereNiente() = runBlocking<Unit> {
    val world = StoreWorld(File(folder.root, "faces"))
    val local = StoresLocalData(world.stores)
    assertThat(local.people(g2)).isEmpty()
    assertThat(local.hasData(g2)).isFalse()
    assertThat(world.filesOf(g2)).isEmpty()
    assertThat(world.fake.exists(g2)).isFalse()
  }

  /**
   * I6 (correzioni A della Fase 8): la cancellazione e' morta fra la chiave distrutta e i file tolti. Alla ripresa la lettura dei nomi (per la cronologia)
   * **non fa nascere** una chiave nuova e un database vuoto, non toglie niente da sola e non segnala il falso `FACES_KEY_LOST`: dice "nessun nome", e i file li
   * toglie la cancellazione che segue.
   */
  @Test
  fun i6_conLaChiaveGiaDistruttaLaLetturaDeiNomiNonRifaNienteENonSegnalaUnaPerditaFalsa() = runBlocking<Unit> {
    val world = StoreWorld(File(folder.root, "faces"))
    filled(world, g1, "Lontano")
    // Il processo muore: il database non e' piu' aperto (i file restano), poi la chiave distrutta della cancellazione a meta'.
    world.ready(g1).close()
    world.fake.destroy(g1)
    // Un processo nuovo sugli stessi file e sulle stesse chiavi (la chiave non c'e' piu').
    val recovered = mutableListOf<Any>()
    val restarted = FacesStores(world.directory, FacesDatabases.forContext(world.context), world.fake, patience = Patience.NONE, onRecovered = { recovered += it })
    assertThat(world.filesOf(g1)).isNotEmpty()
    assertThat(StoresLocalData(restarted).people(g1)).isEmpty()
    // Niente chiave nuova, nessun file tolto, nessuna perdita segnalata (il database si e' solo guardato per capire la versione).
    assertThat(world.fake.exists(g1)).isFalse()
    assertThat(recovered).isEmpty()
    assertThat(world.mainFile(g1).exists()).isTrue()
    assertThat(world.mainFile(g1).length()).isGreaterThan(0L)
    // La cancellazione che segue toglie i file e la chiave resta assente.
    assertThat(restarted.eraseLocal(g1).done).isTrue()
    assertThat(world.filesOf(g1)).isEmpty()
    assertThat(world.fake.exists(g1)).isFalse()
  }

  @Test
  fun ilRecintoSulleArchiviVereNonRiapreUnaGenerazioneCancellata() = runBlocking<Unit> {
    val world = StoreWorld(File(folder.root, "faces"))
    filled(world, g1, "Vicino")
    val switch = FakeFacesSwitch(initial = FacesState.On(g1, HereState.ACTIVE))
    val fence = FacesEraseFence(switch)
    val archives = fence.archives(world.stores.archives())
    assertThat(archives.open(g1)).isNotNull()
    fence.block(listOf(g1))
    FacesLocalErase(StoresLocalData(world.stores), fence).erase(g1, LocalEraseWhy.ACCOUNT_OFF)
    assertThat(archives.open(g1)).isNull()
    assertThat(world.filesOf(g1)).isEmpty()
    assertThat(world.fake.exists(g1)).isFalse()
  }

  @Test
  fun conLaRecinzioneTuttoInLocaleSulNegozioVero() = runBlocking<Unit> {
    val world = StoreWorld(File(folder.root, "faces"))
    filled(world, g1, "Altrove")
    filled(world, g2, "Prima")
    val settings = InMemoryFacesPersonalSettings(deviceId = author)
    settings.set(FacesSetting.On(g1, 1))
    val switch = FakeFacesSwitch(initial = FacesState.On(g1, HereState.LOCAL_ONLY))
    val fence = FacesEraseFence(switch)
    val history = FakeSearchHistoryPurge(mutableListOf("Altrove a casa", "Prima o poi", "mare"))
    val local = StoresLocalData(world.stores)
    val clock = FakeFacesClock()
    val identity = object : FacesEraseIdentity {
      override val deviceId: String = author

      override fun registry() = null
    }
    val journal = FileEraseJournalStore(File(folder.root, FileEraseJournalStore.FILE))
    val eraser = DefaultFacesEraser(
      FacesEraseParts(
        journal = journal, switch = switch, settings = settings, localStore = InMemoryFacesLocalStore(),
        requirements = FakeFacesRequirements(privateArea = PrivateAreaState.Declared("vera"), localOnly = true), local = local, fence = fence,
        localErase = FacesLocalErase(local, fence, history = history), albums = FakeSmartAlbumsPeople(), history = history,
        nas = FailingFacesPorts.eraseOps, announcer = EraseAnnouncer { error("nessun annuncio con la recinzione") }, heads = NoPersonalHeadsView,
        identity = identity, clock = clock,
      ),
    )
    val preview = eraser.preview()
    assertThat(preview.localOnly).isTrue()
    assertThat(preview.namedPeople).isEqualTo(1)
    assertThat(eraser.erase(FacesEraseConfirmation(preview.digest, clock.monotonic), PeopleAlbumsChoice.KEEP_PHOTOS)).isEqualTo(EraseStart.Started)
    eraser.resume(5.minutes)
    assertThat(eraser.status.value!!.step).isEqualTo(EraseStep.DONE)
    // Tutte le generazioni sul disco: l'account e' spento.
    assertThat(world.filesOf(g1) + world.filesOf(g2)).isEmpty()
    assertThat(world.fake.aliases()).isEmpty()
    assertThat(world.stores.generationsOnDisk()).isEmpty()
    // Anche i nomi della generazione vecchia trovata sul disco: i suoi dati se ne vanno con lei.
    assertThat(history.entries).containsExactly("mare")
    assertThat(settings.current()).isEqualTo(FacesSetting.Off(g1))
  }
}
