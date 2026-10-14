package dev.pampa.mycloudgallery.core.index.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.index.TestStores
import dev.pampa.mycloudgallery.core.index.p3.CompactorTest
import dev.pampa.mycloudgallery.core.index.p3.OutboxCrashTest
import dev.pampa.mycloudgallery.core.index.p3.PublisherTest
import dev.pampa.mycloudgallery.core.index.quality.ReplicaQualityTest
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.replica.store.RoomProvisionalStore
import dev.pampa.mycloudgallery.core.index.replica.store.RoomReplicaStore
import dev.pampa.mycloudgallery.core.index.sync.IndexSyncEngineTest
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.QuickHash
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Gli adattatori Room delle porte di persistenza di core-index (P11, schema v3 riconciliato) provati con **le stesse**
// prove di P3 (pubblicazione, morte del processo a ogni passo, compattazione) e di P4 (sincronizzazione, qualita'): ogni
// sottoclasse eredita tutti i test della prova in memoria e cambia solo la fabbrica degli archivi ([TestStores]). Un
// database in memoria per "dispositivo" (ogni outbox o replica e' di un telefono diverso), aperto al primo uso (le prove
// creano i dispositivi gia' nel costruttore, prima che Robolectric abbia l'applicazione) e chiuso alla fine del test.

/** Gli archivi Room aperti dai test, da chiudere a fine test. */
object RoomStores {
  private val opened = mutableListOf<GalleryDatabase>()

  /**
   * Quanti archivi Room sono stati chiesti dall'ultima chiusura: le prove non devono girare in memoria per sbaglio (un test
   * puo' non aprire il database, se non lo tocca mai, ma l'archivio che usa deve essere quello Room).
   */
  @Volatile var createdSinceClose: Int = 0
    private set

  private fun database(): GalleryDatabase =
    GalleryDatabase.build(ApplicationProvider.getApplicationContext<Application>(), name = null).also {
      synchronized(opened) { opened += it }
    }

  fun install() {
    TestStores.publish = {
      createdSinceClose++
      lazyProxy<IndexPublishStore> { RoomIndexPublishStore(database()) }
    }
    TestStores.replica = {
      createdSinceClose++
      lazyProxy<ReplicaStore> { RoomReplicaStore(database()) }
    }
  }

  /** Chiude gli archivi del test e dice quanti ne erano stati chiesti. */
  fun closeAll(): Int = synchronized(opened) {
    opened.forEach { it.close() }
    opened.clear()
    createdSinceClose.also { createdSinceClose = 0 }
  }

  /** Fine di un test: gli archivi si chiudono e almeno uno doveva essere Room. */
  fun finishTest() {
    assertThat(closeAll()).isGreaterThan(0)
  }

  fun uninstall() {
    TestStores.reset()
    closeAll()
  }

  /** Un'implementazione di [T] che costruisce quella vera alla prima chiamata (anche per le funzioni `suspend`). */
  private inline fun <reified T : Any> lazyProxy(noinline create: () -> T): T {
    val target by lazy(create)
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
      try {
        method.invoke(target, *(args ?: emptyArray()))
      } catch (e: InvocationTargetException) {
        throw e.targetException
      }
    } as T
  }
}

@RunWith(RobolectricTestRunner::class)
class RoomPublisherTest : PublisherTest() {
  @After fun closeDatabases() = RoomStores.finishTest()

  companion object {
    @JvmStatic @BeforeClass fun install() = RoomStores.install()

    @JvmStatic @AfterClass fun uninstall() = RoomStores.uninstall()
  }
}

@RunWith(RobolectricTestRunner::class)
class RoomOutboxCrashTest : OutboxCrashTest() {
  @After fun closeDatabases() = RoomStores.finishTest()

  companion object {
    @JvmStatic @BeforeClass fun install() = RoomStores.install()

    @JvmStatic @AfterClass fun uninstall() = RoomStores.uninstall()
  }
}

@RunWith(RobolectricTestRunner::class)
class RoomCompactorTest : CompactorTest() {
  @After fun closeDatabases() = RoomStores.finishTest()

  companion object {
    @JvmStatic @BeforeClass fun install() = RoomStores.install()

    @JvmStatic @AfterClass fun uninstall() = RoomStores.uninstall()
  }
}

@RunWith(RobolectricTestRunner::class)
class RoomIndexSyncEngineTest : IndexSyncEngineTest() {
  @After fun closeDatabases() = RoomStores.finishTest()

  companion object {
    @JvmStatic @BeforeClass fun install() = RoomStores.install()

    @JvmStatic @AfterClass fun uninstall() = RoomStores.uninstall()
  }
}

@RunWith(RobolectricTestRunner::class)
class RoomReplicaQualityTest : ReplicaQualityTest() {
  @After fun closeDatabases() = RoomStores.finishTest()

  companion object {
    @JvmStatic @BeforeClass fun install() = RoomStores.install()

    @JvmStatic @AfterClass fun uninstall() = RoomStores.uninstall()
  }
}

/** Le miniature provvisorie (D17) in `replica_provisional`: lettura bloccante per chiave, mai dal thread principale. */
@RunWith(RobolectricTestRunner::class)
class RoomProvisionalStoreTest {
  @Test
  fun scriveLeggeESostituisceTogliePerChiaveRidotta() = runBlocking {
    val database = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Application>(), name = null)
    try {
      val store = RoomProvisionalStore(database)
      val key = ContentKey.of(QuickHash("a".repeat(64)), 1234)
      assertThat(withContext(Dispatchers.IO) { store.get(key.short) }).isNull()
      store.put(key, byteArrayOf(1, 2, 3))
      store.put(key, byteArrayOf(4, 5))
      assertThat(withContext(Dispatchers.IO) { store.get(key.short) }!!.toList()).containsExactly(4.toByte(), 5.toByte()).inOrder()
      store.remove(key.short)
      assertThat(withContext(Dispatchers.IO) { store.get(key.short) }).isNull()
    } finally {
      database.close()
    }
  }
}
