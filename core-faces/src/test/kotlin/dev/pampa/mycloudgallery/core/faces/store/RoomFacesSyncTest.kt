package dev.pampa.mycloudgallery.core.faces.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.faces.sync.FacesIsolationTest
import dev.pampa.mycloudgallery.core.faces.sync.FacesLostStateTest
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishStore
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishStoreContract
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublisherTest
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaStore
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaStoreContract
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaTest
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncBenchTest
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncWorld
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

// Fase 8 (P6, §5.2): la replica e il pubblicatore di P3 sopra il database dei volti cifrato. Due modi di provarli, con le prove di P3 e non con
// altre:
// 1. le **suite di contratto degli stati** (`FacesReplicaStoreContract`, `FacesPublishStoreContract`), le stesse che passano gli stati in memoria
//    (`InMemorySyncStoreContractTest`): quello che si salva si rilegge uguale, anche dopo la chiusura e la riapertura del database;
// 2. le **prove di P3 intere** (replica, pubblicatore, isolamento e il banco da 200 giri con i guasti, le morti del processo e lo stato locale
//    perso) con ogni dispositivo sul suo database: l'archivio, la replica e il pubblicatore veri, e ogni morte del processo chiude e riapre il file.

/** La replica sul database. */
@RunWith(AndroidJUnit4::class)
class RoomFacesReplicaStoreContractTest : FacesReplicaStoreContract() {
  @get:Rule
  val folder = TemporaryFolder()

  private val world by lazy { StoreWorld(File(folder.root, "faces")) }
  private var store: FacesStore? = null

  override fun create(): FacesReplicaStore = runBlocking { world.ready(generation).also { store = it }.replicaStore }

  override fun reopen(): FacesReplicaStore = runBlocking {
    store!!.close()
    world.ready(generation).also { store = it }.replicaStore
  }

  @After
  fun chiude() {
    store?.close()
  }
}

/** Il pubblicatore sul database. */
@RunWith(AndroidJUnit4::class)
class RoomFacesPublishStoreContractTest : FacesPublishStoreContract() {
  @get:Rule
  val folder = TemporaryFolder()

  private val world by lazy { StoreWorld(File(folder.root, "faces")) }
  private var store: FacesStore? = null

  override fun create(): FacesPublishStore = runBlocking { world.ready(generation).also { store = it }.publishStore }

  override fun reopen(): FacesPublishStore = runBlocking {
    store!!.close()
    world.ready(generation).also { store = it }.publishStore
  }

  @After
  fun chiude() {
    store?.close()
  }
}

@RunWith(AndroidJUnit4::class)
class RoomFacesReplicaTest : FacesReplicaTest() {
  @get:Rule
  val folder = TemporaryFolder()
  private val storages = mutableListOf<RoomSyncStorage>()

  override fun newWorld(): FacesSyncWorld = FacesSyncWorld(storage = RoomSyncStorage(File(folder.root, "w${storages.size}")).also { storages += it })

  @After
  fun chiude() = storages.forEach { it.closeAll() }
}

@RunWith(AndroidJUnit4::class)
class RoomFacesPublisherTest : FacesPublisherTest() {
  @get:Rule
  val folder = TemporaryFolder()
  private val storages = mutableListOf<RoomSyncStorage>()

  override fun newWorld(): FacesSyncWorld = FacesSyncWorld(storage = RoomSyncStorage(File(folder.root, "w${storages.size}")).also { storages += it })

  @After
  fun chiude() = storages.forEach { it.closeAll() }
}

@RunWith(AndroidJUnit4::class)
class RoomFacesIsolationTest : FacesIsolationTest() {
  @get:Rule
  val folder = TemporaryFolder()
  private val storages = mutableListOf<RoomSyncStorage>()

  override fun newWorld(): FacesSyncWorld = FacesSyncWorld(storage = RoomSyncStorage(File(folder.root, "w${storages.size}")).also { storages += it })

  @After
  fun chiude() = storages.forEach { it.closeAll() }
}

@RunWith(AndroidJUnit4::class)
class RoomFacesLostStateTest : FacesLostStateTest() {
  @get:Rule
  val folder = TemporaryFolder()
  private val storages = mutableListOf<RoomSyncStorage>()

  override fun newWorld(): FacesSyncWorld = FacesSyncWorld(storage = RoomSyncStorage(File(folder.root, "w${storages.size}")).also { storages += it })

  @After
  fun chiude() = storages.forEach { it.closeAll() }
}

@RunWith(AndroidJUnit4::class)
class RoomFacesSyncBenchTest : FacesSyncBenchTest() {
  @get:Rule
  val folder = TemporaryFolder()
  private val storages = mutableListOf<RoomSyncStorage>()

  override fun newWorld(): FacesSyncWorld = FacesSyncWorld(storage = RoomSyncStorage(File(folder.root, "w${storages.size}")).also { storages += it })

  @After
  fun chiude() = storages.forEach { it.closeAll() }
}
