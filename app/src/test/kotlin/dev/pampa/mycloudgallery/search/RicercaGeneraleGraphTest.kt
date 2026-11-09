package dev.pampa.mycloudgallery.search

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.engine.EmbedderQueryEmbedding
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import dev.pampa.mycloudgallery.core.trust.data.VecItem
import java.io.File
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** RG-A: passa dal grafo di produzione, inclusi attesa dell'apertura e adattatori dei dispatcher. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RicercaGeneraleGraphTest {
  @get:Rule val temp = TemporaryFolder()

  private fun ids(view: OrderedTimelineView) = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }

  private suspend fun withGraph(test: suspend (SearchGraph, TimelineSnapshot, MutableList<Thread>, Thread) -> Unit) {
    val db = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Application>(), name = null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val compute = Executors.newSingleThreadExecutor { Thread(it, "rg-a-calcolo") }.asCoroutineDispatcher()
    val caller = Thread.currentThread()
    assertThat(caller).isSameInstanceAs(Looper.getMainLooper().thread)
    var graph: SearchGraph? = null
    try {
      val account = AccountId.of("prova")
      val catalog = MediaCatalog(db, account)
      val observations = (0 until 240).map { i ->
        val fingerprint = Fingerprint.ofContent(ByteArray(256) { (it + i).toByte() })
        Observation(Location.Phone("external_primary", "DCIM/", "$i.jpg", i + 1L, "prova"),
          FileStamp(256, 1_800_000_000_000L), fingerprint, MediaKind.PHOTO, MediaSource.CAMERA)
      }
      catalog.recordAll(observations)
      val cells = catalog.cellContentKeys().cells
      assertThat(cells).hasSize(240)
      val synthetic = SyntheticLibrary(seed = 9, count = cells.size, placeIds = intArrayOf(1)).snapshot
      val threads: MutableList<Thread> = CopyOnWriteArrayList()
      val snapshot = object : TimelineSnapshot by synthetic {
        override fun idAt(i: Int) = cells[i].mediaId
        override fun indexOf(id: MediaId): Int {
          threads += Thread.currentThread()
          return cells.indexOfFirst { it.mediaId == id }
        }
        override fun placeAt(i: Int): Int {
          threads += Thread.currentThread()
          return 1
        }
        override fun groupSizeAt(i: Int) = 1
      }
      val people = object : PeopleLookup by NoPeopleLookup {
        override val available = MutableStateFlow(true)
        override suspend fun dictionary(): PeopleDictionary {
          threads += Thread.currentThread()
          return PeopleDictionary.EMPTY
        }
      }
      val search = SearchGraph(scope, File(temp.root, "search"), { catalog }, db, MutableStateFlow(snapshot),
        PlaceLookup { PlaceName("Roma", "Lazio", "Italia") }, EmbedderQueryEmbedding(null), people = people, compute = compute)
      graph = search
      assertThat(search.open()).isTrue()
      search.keys.sync()
      val random = Random(12)
      val items = cells.mapIndexedNotNull { i, cell ->
        if (i == 2) return@mapIndexedNotNull null // Selezionata senza vettore.
        val vector = when (i) {
          0 -> FloatArray(768) { if (it == 0) 1f else 0f }
          1 -> FloatArray(768) { if (it == 1) 1f else 0f }
          3 -> FloatArray(768) { if (it < 2) 1f else 0f } // Centroide esatto.
          4 -> FloatArray(768) { if (it == 0) 1f else 0f } // Simile alla sola prima foto.
          // Rumore in 16 dimensioni: .707 e 1 non saturano entrambi la pertinenza e distinguono prima foto e centroide.
          else -> FloatArray(768) { if (it < 16) random.nextGaussian().toFloat() else 0f }
        }
        val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        val body = VecBody(SpaceCode.CURRENT, 1, VecSource.ANALYSIS,
          listOf(VecItem(null, Float16.encode(FloatArray(768) { vector[it] / norm }))))
        LocalAiIndex.ReplicaItem.Vectors(cell.own.single(), "prova", body)
      }
      search.index.applyReplica("comune", items)
      threads.clear()
      test(search, snapshot, threads, caller)
    } finally {
      graph?.stop()
      scope.cancel()
      compute.close()
      db.close()
    }
  }

  @Test fun laSelezioneUsaIlCentroideEscludeTuttaLaSceltaEDiceChiNonHaVettori() = runBlocking {
    withGraph { graph, snapshot, _, _ ->
      val picked = (0..2).map(snapshot::idAt)
      val result = graph.engine.similarToAll(picked, ResultOrder.RELEVANT)
      assertThat((result.chips.single() as SearchChip.Similar).all).containsExactlyElementsIn(picked).inOrder()
      assertThat(result.notices).containsExactly(SearchNotice.SimilarWithoutVectors(1))
      val shown = ids(result.confident) + ids(result.likely)
      assertThat(shown).containsNoneIn(picked)
      assertThat(ids(result.confident).first()).isEqualTo(snapshot.idAt(3))
      assertThat(ids(graph.engine.similar(picked.first(), ResultOrder.RELEVANT).confident).first()).isEqualTo(snapshot.idAt(4))
    }
  }

  @Test fun ilCalcoloDellaSelezioneGiraFuoriDalThreadChiamante() = runBlocking {
    withGraph { graph, snapshot, threads, caller ->
      graph.engine.similarToAll(listOf(snapshot.idAt(0), snapshot.idAt(1)))
      assertThat(threads).isNotEmpty()
      assertThat(threads.none { it === caller }).isTrue()
      assertThat(threads.all { it.name.startsWith("rg-a-calcolo") }).isTrue()
    }
  }

  @Test fun dopoLoStopAncheLaRicercaDallaSelezioneRispettaIlCicloDiVitaDelGrafo() = runBlocking {
    withGraph { graph, snapshot, _, _ ->
      graph.stop()
      val failure = runCatching { graph.engine.similarToAll(listOf(snapshot.idAt(0), snapshot.idAt(1))) }.exceptionOrNull()
      assertThat(failure).isInstanceOf(IllegalStateException::class.java)
      assertThat(failure).isNotInstanceOf(CancellationException::class.java)
    }
  }

  @Test fun suggerimentiARiposoECompletamentiCalcolanoILuoghiFuoriDalThreadChiamante() = runBlocking {
    withGraph { graph, _, threads, caller ->
      assertThat(graph.suggestions.idle().places.map { it.label }).containsExactly("Roma")
      assertThat(threads).isNotEmpty()
      assertThat(threads.none { it === caller }).isTrue()
      threads.clear()
      assertThat(graph.suggestions.complete("ro").map { it.label }).contains("Roma")
      // Anche se il dizionario e' caldo, il completamento deve cambiare dispatcher.
      assertThat(threads).isNotEmpty()
      assertThat(threads.none { it === caller }).isTrue()
      assertThat(threads.all { it.name.startsWith("rg-a-calcolo") }).isTrue()
    }
  }
}
