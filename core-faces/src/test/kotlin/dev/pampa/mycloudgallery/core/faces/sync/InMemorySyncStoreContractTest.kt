package dev.pampa.mycloudgallery.core.faces.sync

// Fase 8 (P3 e P6, §5.2): gli stati in memoria di P3 passano le suite di contratto della replica e del pubblicatore (le stesse che passano gli
// adattatori sul database dei volti di P6: `store/RoomFacesSyncStoresContractTest`). Per la versione in memoria la "morte del processo" non
// cambia niente: lo stato e' lo stesso oggetto.

/** La replica in memoria. */
class InMemoryFacesReplicaStoreContractTest : FacesReplicaStoreContract() {
  private var current: FacesReplicaStore? = null

  override fun create(): FacesReplicaStore = InMemoryFacesReplicaStore().also { current = it }

  override fun reopen(): FacesReplicaStore = current!!
}

/** Il pubblicatore in memoria. */
class InMemoryFacesPublishStoreContractTest : FacesPublishStoreContract() {
  private var current: FacesPublishStore? = null

  override fun create(): FacesPublishStore = InMemoryFacesPublishStore().also { current = it }

  override fun reopen(): FacesPublishStore = current!!
}
