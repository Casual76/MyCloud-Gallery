package dev.pampa.mycloudgallery.core.faces.compute

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.store.FacesStoreOpen
import dev.pampa.mycloudgallery.core.faces.store.FacesUnitsDone
import dev.pampa.mycloudgallery.core.faces.store.StoreWorld
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.security.testing.FakeFacesKeys
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Il calcolo sul **negozio vero** di P6 (Room, cifratura, Robolectric): la chiave che sparisce (`FACES_KEY_LOST`, E-F19), la morte del processo con il
 * database vero (il salvataggio e' una transazione per contenuto: dopo la morte un media salvato non si rifa') e un errore di SQLite a meta' di un
 * salvataggio (P6: esce come eccezione, qui e' un errore del media).
 */
@RunWith(AndroidJUnit4::class)
class RealStoreComputeTest {
  @get:Rule val folder = TemporaryFolder()

  private val forever = SimWorld.FOREVER

  private class Setup(val w: SimWorld, val phone: SimDevice, val keys: List<ContentKey>)

  private fun setup(media: Int = 6): Setup {
    val w = SimWorld()
    val keys = (1..media).map { w.nas.add(w.anna.privateShare, faces = listOf(Faces.big(it % 9 + 1)), month = "2024-0${1 + it % 2}") }
    val phone = w.device(w.anna, "telefono-anna")
    return Setup(w, phone, keys)
  }

  /** Un processo: il negozio vero in [dir] (stesse chiavi), il pubblicatore che legge da lui, e il motore. Il vecchio negozio si chiude (il processo e' finito). */
  private class Process(val s: Setup, dir: File, keys: FakeFacesKeys) {
    val world = StoreWorld(dir, keys)
    private var archive: FacesArchive? = null
    var wrapper: CrashingArchive? = null

    val source = FacesStoreSource { generation ->
      when (val opened = world.stores.open(generation)) {
        is FacesStoreOpen.Ready -> ComputeStoreOpen.Ready(wrap(opened.store.archive, opened.store.units), null)
        is FacesStoreOpen.Recovered -> ComputeStoreOpen.Ready(wrap(opened.store.archive, opened.store.units), opened.reason)
        is FacesStoreOpen.Unavailable -> ComputeStoreOpen.Unavailable(opened.reason)
      }
    }

    private fun wrap(real: FacesArchive, units: FacesUnitsDone): ComputeStore {
      val crashing = wrapper?.takeIf { it.inner === real } ?: CrashingArchive(real).also { wrapper = it }
      archive = crashing
      return ComputeStore(crashing, units)
    }

    val publisher = SimPublisher(s.w.nas, s.phone.id, s.w.anna.generation) { archive!! }

    fun engine(config: FacesComputeConfig = FacesComputeConfig.DEFAULT): FacesComputeEngine =
      FacesComputeEngine(s.phone.parts().copy(stores = source, publisher = publisher), config)

    fun close() = runBlocking { (world.stores.open(s.w.anna.generation) as? FacesStoreOpen.Ready)?.store?.close() }
  }

  @Test
  fun chiaveSparita_FacesKeyLostSegnalato_ilCalcoloRiparteDaZero() = runBlocking<Unit> {
    val s = setup()
    val keys = FakeFacesKeys()
    val first = Process(s, folder.newFolder("dati"), keys)
    val engine1 = first.engine()
    assertThat(engine1.run(s.phone.area(AreaKind.PRIVATE), forever).analyzed).isEqualTo(6)
    assertThat(engine1.counters.keyLost.get()).isEqualTo(0)
    assertThat(s.phone.signals.map { it.first }).doesNotContain(FacesDiag.FACES_KEY_LOST)
    first.close()

    // Ripristino del telefono o aggiornamento del sistema: la chiave del Keystore non c'e' piu'. Processo nuovo.
    keys.lose(s.w.anna.generation)
    val second = Process(s, File(first.world.directory.path), keys)
    val engine2 = second.engine()
    val result = engine2.run(s.phone.area(AreaKind.PRIVATE), forever)

    assertThat(engine2.counters.keyLost.get()).isEqualTo(1)
    assertThat(s.phone.signals).contains(FacesDiag.FACES_KEY_LOST to 1)
    // Nessun crollo: il negozio e' nuovo e vuoto, il calcolo riparte da zero e arriva in fondo.
    assertThat(result.stop).isEqualTo(FacesStop.DONE)
    assertThat(result.analyzed).isEqualTo(6)
    val store = (second.world.stores.open(s.w.anna.generation) as FacesStoreOpen.Ready).store
    assertThat(store.counts().bodies).isEqualTo(6)

    // Una volta sola: la tappa dopo il negozio e' pronto, non c'e' niente da segnalare ne' da rifare.
    val third = engine2.run(s.phone.area(AreaKind.PRIVATE), forever)
    assertThat(third.analyzed).isEqualTo(0)
    assertThat(engine2.counters.keyLost.get()).isEqualTo(1)
    store.close()
  }

  @Test
  fun morteDelProcessoDopoOgniSalvataggioConIlDatabaseVero() = runBlocking<Unit> {
    for (n in 1..4) {
      val s = setup(media = 4)
      val keys = FakeFacesKeys()
      val dir = folder.newFolder("morte-$n")
      var process = Process(s, dir, keys)
      var engine = process.engine()
      var crashed = false
      var rounds = 0
      while (rounds++ < 10) {
        // La morte scatta dopo il salvataggio n-esimo del primo processo.
        if (rounds == 1) {
          // Il wrapper si crea al primo open: la prima tappa apre il negozio, poi si arma la morte.
          process.source.open(s.w.anna.generation)
          process.wrapper!!.dieAfterSave = n
        }
        try {
          engine.run(s.phone.area(AreaKind.PRIVATE), forever)
          if (s.w.nas.publishedBy(s.w.anna.generation, s.phone.id).size == 4) break
        } catch (e: ProcessDeath) {
          crashed = true
          process.close()
          process = Process(s, dir, keys)
          engine = process.engine()
        }
      }
      assertThat(crashed).isTrue()
      val store = (process.world.stores.open(s.w.anna.generation) as FacesStoreOpen.Ready).store
      // Quattro corpi nel database vero: nessun media salvato e' stato rifatto (un solo corpo per chiave, e tutti presenti).
      assertThat(store.counts().bodies).isEqualTo(4)
      assertThat(store.archive.analyzed(s.keys, 1)).containsExactlyElementsIn(s.keys)
      store.close()
    }
  }

  /** P6: un errore di SQLite a meta' di un salvataggio esce come eccezione; per il calcolo e' un errore del media, che resta da fare. */
  @Test
  fun erroreDiSQLiteASalvataggio_ilMediaRestaDaFare() = runBlocking<Unit> {
    val s = setup(media = 4)
    val process = Process(s, folder.newFolder("sqlite"), FakeFacesKeys())
    val engine = process.engine()
    process.source.open(s.w.anna.generation)
    process.wrapper!!.failSaves = 1
    process.wrapper!!.failure = { SQLiteFullException("database or disk is full") }

    val first = engine.run(s.phone.area(AreaKind.PRIVATE), forever)

    assertThat(first.stop).isEqualTo(FacesStop.DONE)
    assertThat(first.analyzed).isEqualTo(3)
    assertThat(engine.counters.storeFailures.get()).isEqualTo(1)
    val second = engine.run(s.phone.area(AreaKind.PRIVATE), forever)
    assertThat(second.analyzed).isEqualTo(1)
    process.close()
  }
}
