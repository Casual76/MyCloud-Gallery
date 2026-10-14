package dev.pampa.mycloudgallery.core.index

import dev.pampa.mycloudgallery.core.index.replica.store.MemoryReplicaStore
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.store.InMemoryIndexPublishStore
import dev.pampa.mycloudgallery.core.index.store.IndexPublishStore

/**
 * Le implementazioni delle porte di persistenza sotto prova (P11): di default quelle in memoria di P3 e P4; le sottoclassi
 * Robolectric di `store/RoomStoresSuiteTest.kt` le sostituiscono con gli adattatori Room per far girare **le stesse** prove
 * di pubblicazione, sincronizzazione e qualita' sul database vero.
 */
object TestStores {
  @Volatile var publish: () -> IndexPublishStore = { InMemoryIndexPublishStore() }

  @Volatile var replica: () -> ReplicaStore = { MemoryReplicaStore() }

  fun reset() {
    publish = { InMemoryIndexPublishStore() }
    replica = { MemoryReplicaStore() }
  }
}
