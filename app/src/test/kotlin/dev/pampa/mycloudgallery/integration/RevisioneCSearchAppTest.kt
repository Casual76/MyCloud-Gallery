package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.testing.FakeTextEmbedder
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine
import dev.pampa.mycloudgallery.core.database.SearchHistoryRecord
import dev.pampa.mycloudgallery.search.CatalogHistoryStore
import dev.pampa.mycloudgallery.search.OffMainSearchEngine
import dev.pampa.mycloudgallery.search.liveResultsView
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 6, revisione C (docs/review/funzionale-6.md): i collegamenti di `:app` della scheda Cerca che le prove della sola scheda non vedono. **CE-7**: il
 * lavoro delle ricerche non gira sul thread principale. **CE-10**: la cronologia non resta vuota se il catalogo nasce dopo il processo. **CE-11**: una
 * ricerca ferma lancia un errore vero, non una `CancellationException` (la schermata resterebbe su "Sto cercando"). **TS-1**: le chiavi delle celle (da cui
 * "Trova simili" nel viewer sa se la voce c'e') ci sono dopo un avvio a freddo, senza dover prima fare una ricerca.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class RevisioneCSearchAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var rig: AiRig

  @Before
  fun setUp() {
    rig = AiRig(app, temp.root)
  }

  @After
  fun tearDown() = rig.close()

  /** CE-7: l'engine di `SearchGraph` fa il suo lavoro su un altro dispatcher, non su quello di chi chiama (la schermata, sul thread principale). */
  @Test
  fun ilLavoroDelleRicercheGiraSuUnDispatcherAParte_CE7() = runBlocking<Unit> {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "calcolo-prova") }
    try {
      val library = SyntheticLibrary(seed = 5, count = 20)
      val data = FakeSearchData(library.snapshot)
      val embedder = FakeTextEmbedder()
      val base = InMemorySearchEngine(data, embedder, ItalianQueryParser(), FixedToday())
      val threads = ArrayList<String>()
      val recording = object : SearchEngine by base {
        override suspend fun search(request: SearchRequest): SearchResults {
          threads += Thread.currentThread().name
          return base.search(request)
        }

        override suspend fun similar(media: MediaId, order: ResultOrder): SearchResults {
          threads += Thread.currentThread().name
          return base.similar(media, order)
        }
      }
      val engine = OffMainSearchEngine(recording, executor.asCoroutineDispatcher())
      val caller = Thread.currentThread().name

      engine.search(SearchRequest("cane"))
      engine.similar(library.snapshot.idAt(0), ResultOrder.RELEVANT)

      // Il nome del thread porta, con il debug delle coroutine, anche il suffisso "@coroutine#N".
      assertThat(threads).hasSize(2)
      assertThat(threads.all { it.startsWith("calcolo-prova") }).isTrue()
      assertThat(threads.none { it.startsWith(caller.substringBefore(" @coroutine")) }).isTrue()
      // I flussi di copertura e di stato passano senza lavoro e senza cambiare thread.
      assertThat(engine.coverage).isSameInstanceAs(recording.coverage)
    } finally {
      executor.shutdownNow()
    }
  }

  /**
   * CE-10: la cronologia delle ricerche nasce vuota ma **aspetta** che il catalogo ci sia (dopo l'onboarding, nello stesso processo). Prima il flusso
   * guardava il catalogo una volta sola alla nascita: trovato nullo finiva con un elenco vuoto e le ricerche fatte dopo non comparivano fino al riavvio.
   */
  @Test
  fun laCronologiaAspettaIlCatalogoEPoiSiAggiorna_CE10() = runBlocking<Unit> {
    val phone = rig.founder()
    val catalog = phone.gallery.core().catalog
    var available = false
    val gate = CompletableDeferred<Unit>()
    val store = CatalogHistoryStore({ if (available) catalog else null }, { gate.await() })
    val seen = java.util.concurrent.CopyOnWriteArrayList<List<SearchHistoryRecord>>()
    val collector = launch(Dispatchers.Default) { store.changes(10).collect { seen += it } }
    try {
      withTimeout(Attese.TETTO_MS) { while (seen.isEmpty()) delay(10) }
      assertThat(seen.last()).isEmpty()

      // Il catalogo arriva (la famiglia e' pronta) e la ricerca si apre: da qui in poi le ricerche fatte compaiono, senza riavviare.
      available = true
      gate.complete(Unit)
      store.record("spiaggia", listOf("2024"))
      withTimeout(Attese.TETTO_MS) { while (seen.last().isEmpty()) delay(10) }
      assertThat(seen.last().single().text).isEqualTo("spiaggia")
      assertThat(seen.last().single().chipLabels).containsExactly("2024")
    } finally {
      collector.cancel()
    }
  }

  /**
   * CE-4, E-Q13: il viewer aperto da Cerca scorre una vista che segue la Libreria viva. Un media nascosto altrove, a viewer aperto, esce anche dal pager;
   * prima il viewer riceveva un flusso fermo (`MutableStateFlow(results)`) e la foto restava li' mentre la griglia dietro la toglieva.
   */
  @Test
  fun ilViewerApertoDaCercaSegueLaLibreriaViva_CE4() = runBlocking<Unit> {
    val lib = SyntheticLibrary(seed = 7, count = 60)
    val data = FakeSearchData(lib.snapshot)
    val results = IndexOrderedTimelineView(lib.snapshot, intArrayOf(4, 2, 9, 7))
    val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    val live = liveResultsView(results, data, scope)
    val seen = java.util.concurrent.CopyOnWriteArrayList<List<MediaId>>()
    fun idsOf(view: TimelineView): List<MediaId> = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
    val collector = launch(Dispatchers.Unconfined) { live.collect { seen += idsOf(it as OrderedTimelineView) } }
    try {
      val all = listOf(4, 2, 9, 7).map { lib.snapshot.idAt(it) }
      assertThat(seen.last()).containsExactlyElementsIn(all).inOrder()

      data.hiddenIds += lib.snapshot.idAt(2)
      data.snapshot.value = lib.snapshot.withFlags(0, lib.snapshot.flagsAt(0))
      withTimeout(Attese.TETTO_MS) { while (seen.last().size == all.size) delay(10) }
      assertThat(seen.last()).containsExactly(all[0], all[2], all[3]).inOrder()
    } finally {
      collector.cancel()
      scope.cancel()
    }
  }

  /** CE-11: una ricerca ferma (azzeramento) lancia un errore vero; una `CancellationException` la prenderebbe per "annullata" chi aspetta. */
  @Test
  fun unaRicercaFermaLanciaUnErroreVeroNonUnaCancellazione_CE11() = runBlocking<Unit> {
    val phone = rig.founder()
    val search = phone.stack.search
    assertThat(search.open()).isTrue()
    search.stop()

    val search1 = runCatching { search.engine.search(SearchRequest("cane")) }.exceptionOrNull()
    val similar = runCatching { search.engine.similar(MediaId.random()) }.exceptionOrNull()

    assertThat(search1).isInstanceOf(IllegalStateException::class.java)
    assertThat(search1).isNotInstanceOf(CancellationException::class.java)
    assertThat(similar).isInstanceOf(IllegalStateException::class.java)
    assertThat(similar).isNotInstanceOf(CancellationException::class.java)
  }

  /**
   * TS-1: "Trova simili" nel menu del viewer legge le chiavi delle celle (`SearchGraph.keys`) per sapere se la voce c'e'. Prima si riempivano solo alla
   * prima ricerca: dopo un avvio a freddo la voce mancava finche' l'utente non cercava qualcosa. Adesso l'apertura della ricerca le sincronizza e le
   * segue a ogni cambiamento del catalogo.
   */
  @Test
  fun leChiaviDelleCelleCiSonoDopoLAperturaSenzaUnaRicerca_TS1() = runBlocking<Unit> {
    val phone = rig.founder()
    rig.backend.put(TestNas.COMMON, "Foto/2024/IMG_0042.jpg", AiRig.jpeg(60), lastWriteMs = rig.time.now() - 2 * AiRig.DAY)
    checkNotNull(checkNotNull(phone.gallery.nas).scan(ScanMode.Full, 5.minutes))
    val id = phone.gallery.core().catalog.cellContentKeys().cells.single().mediaId
    val search = phone.stack.search

    assertThat(search.open()).isTrue()

    // Nessuna ricerca: le chiavi arrivano da sole.
    withTimeout(Attese.TETTO_MS) { while (search.keys[id] == null) delay(20) }
    assertThat(search.keys[id]!!.own).isNotEmpty()

    // E una foto che arriva dopo ci arriva senza una ricerca.
    rig.backend.put(TestNas.COMMON, "Foto/2024/IMG_0043.jpg", AiRig.jpeg(120), lastWriteMs = rig.time.now() - 2 * AiRig.DAY)
    checkNotNull(checkNotNull(phone.gallery.nas).scan(ScanMode.Full, 5.minutes))
    val ids = phone.gallery.core().catalog.cellContentKeys().cells.map { it.mediaId }
    assertThat(ids).hasSize(2)
    withTimeout(Attese.TETTO_MS) { while (ids.any { search.keys[it] == null }) delay(20) }
  }
}
