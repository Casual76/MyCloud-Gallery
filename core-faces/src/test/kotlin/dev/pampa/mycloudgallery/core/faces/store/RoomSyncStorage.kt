package dev.pampa.mycloudgallery.core.faces.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.common.files.Patience
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabases
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishStore
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaStore
import dev.pampa.mycloudgallery.core.faces.sync.SyncArchive
import dev.pampa.mycloudgallery.core.faces.sync.SyncDeviceStorage
import dev.pampa.mycloudgallery.core.faces.sync.SyncStorage
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.testing.FakeFacesKeys
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Lo stato dei dispositivi di prova di `sync/` sul database dei volti cifrato (Fase 8, P6): un `FacesStores` per dispositivo (una cartella, le
 * chiavi finte di P0), archivio, replica e pubblicatore dello stesso negozio. La "morte del processo" del banco ([reopen]) **chiude e riapre il
 * database**; lo stato locale perso ([lose]) cancella il database come "Disattiva e cancella" e ne apre uno nuovo. Con questo, le prove di P3
 * (`FacesReplicaTest`, `FacesPublisherTest`, `FacesIsolationTest`, `FacesSyncBenchTest`) girano identiche sopra il database vero.
 */
internal class RoomSyncStorage(private val root: File) : SyncStorage {
  private val devices = mutableListOf<RoomDeviceStorage>()

  override fun open(device: String, generation: FaceGeneration): SyncDeviceStorage =
    RoomDeviceStorage(File(root, device), generation).also { devices += it }

  /** Chiude i database di tutti i dispositivi (a fine prova: nessun file resta aperto). */
  fun closeAll() = devices.forEach { it.close() }
}

internal class RoomDeviceStorage(directory: File, private val generation: FaceGeneration) : SyncDeviceStorage {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val stores = FacesStores(directory, FacesDatabases.forContext(context), FakeFacesKeys(), patience = Patience.NONE)
  private var store: FacesStore = openStore()

  private fun openStore(): FacesStore = runBlocking { (stores.open(generation) as FacesStoreOpen.Ready).store }

  override val archive: SyncArchive get() = RoomArchive(store)
  override val replicaStore: FacesReplicaStore get() = store.replicaStore
  override val publishStore: FacesPublishStore get() = store.publishStore

  override fun reopen() {
    store.close()
    store = openStore()
  }

  fun close() = store.close()

  override fun lose() {
    runBlocking { stores.eraseLocal(generation) }
    store = openStore()
  }
}

/** Il negozio visto come l'archivio che le prove si aspettano: "chiuso" e' il negozio chiuso. */
private class RoomArchive(private val store: FacesStore) : SyncArchive, FacesArchive by store.archive {
  override val closed: Boolean get() = !store.isOpen

  override fun close() = store.close()
}
