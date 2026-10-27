package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.FaceGeneration

/**
 * Dove un dispositivo di prova tiene il suo stato locale (Fase 8, P3 e P6): l'archivio dei volti e gli stati della replica e del pubblicatore.
 * I test di `sync/` (replica, pubblicatore, isolamento, banco) girano sopra [SyncStorage.Memory] (P3: tutto in memoria, e' il riferimento) e,
 * con le stesse prove, sopra il database dei volti cifrato di P6 (`store/RoomSyncStorage`): ogni "morte del processo" del banco allora
 * chiude e riapre il database davvero.
 */
interface SyncArchive : FacesArchive {
  /** L'archivio non risponde piu' (generazione cambiata, chiave distrutta): `IllegalStateException` a ogni chiamata. */
  val closed: Boolean

  fun close()
}

interface SyncDeviceStorage {
  val archive: SyncArchive
  val replicaStore: FacesReplicaStore
  val publishStore: FacesPublishStore

  /** La morte del processo: quello che e' su disco resta, quello in memoria no. In memoria non cambia niente (lo stato e' gia' "su disco"). */
  fun reopen()

  /** Lo stato locale perso (i dati si cancellano, la chiave di firma no): archivio e stati nuovi e vuoti. */
  fun lose()
}

interface SyncStorage {
  fun open(device: String, generation: FaceGeneration): SyncDeviceStorage

  companion object {
    /** Il riferimento di P3: tutto in memoria. */
    val Memory: SyncStorage = object : SyncStorage {
      override fun open(device: String, generation: FaceGeneration): SyncDeviceStorage = MemoryDeviceStorage(generation)
    }
  }
}

private class MemoryArchive(val inner: InMemoryFacesArchive) : SyncArchive, FacesArchive by inner {
  override val closed: Boolean get() = inner.closed

  override fun close() = inner.close()
}

private class MemoryDeviceStorage(private val generation: FaceGeneration) : SyncDeviceStorage {
  private var memory = InMemoryFacesArchive(generation)
  override var archive: SyncArchive = MemoryArchive(memory)
    private set
  override var replicaStore: FacesReplicaStore = InMemoryFacesReplicaStore()
    private set
  override var publishStore: FacesPublishStore = InMemoryFacesPublishStore()
    private set

  override fun reopen() = Unit

  override fun lose() {
    memory = InMemoryFacesArchive(generation)
    archive = MemoryArchive(memory)
    replicaStore = InMemoryFacesReplicaStore()
    publishStore = InMemoryFacesPublishStore()
  }
}
