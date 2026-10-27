package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineRepository
import dev.pampa.mycloudgallery.core.library.data.TimelineViewerItems
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Il repository sopra un catalogo in memoria ([ModelSource]): avvio dall'istantanea su file, riconciliazione con
 * `change_seq`, patch accorpate, salti di `seq`, scrittura accorpata dell'istantanea e "togli subito" dei nascosti (E-H1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogTimelineRepositoryTest {

  private val dir: File = Files.createTempDirectory("repo").toFile()

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun model(n: Int, seed: Int = 1): ModelSource {
    val r = Random(seed)
    return ModelSource().apply {
      repeat(n) { k ->
        val id = UUID(r.nextLong(), r.nextLong()).toString()
        media[id] = ModelSource.Media(
          indexRow(id, sortTime = 1_700_000_000_000L - k * 60_000L, localDay = 20231114 - k / 1_000, source = if (k % 5 == 0) "screenshot" else "camera",
            kind = if (k % 7 == 0) "video" else "photo", duration = if (k % 7 == 0) 1_000L else null),
          ref = MediaRef.Phone("external_primary", 1L + k, "image/jpeg"),
        )
      }
    }
  }

  /**
   * Tutto il lavoro in coda, compreso quello nel `backgroundScope`: `advanceUntilIdle` guarda solo il lavoro in primo
   * piano, `advanceTimeBy` esegue tutto (anche le scritture accorpate dell'istantanea, entro un minuto virtuale).
   */
  private fun TestScope.settle() {
    advanceTimeBy(60_000)
    runCurrent()
  }

  private fun TestScope.repository(source: ModelSource, scope: CoroutineScope = backgroundScope): Pair<CatalogTimelineRepository, SnapshotStore> {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = SnapshotStore(dir, io = dispatcher)
    return CatalogTimelineRepository(source, store, scope, worker = dispatcher, writeDelayMs = 5_000) to store
  }

  @Test
  fun primoAvvioRicostruisceEScriveLIstantanea() = runTest {
    val source = model(2_000)
    val (repo, store) = repository(source)
    repo.start()
    settle()
    assertThat(repo.reconciled.value).isTrue()
    assertThat(source.builds.get()).isEqualTo(1)
    assertSameIndex(repo.snapshot.value!!, source.expected())
    // Subito dopo una ricostruzione il file e' riscritto (puo' contenere elementi usciti).
    assertSameIndex(store.read()!!.snapshot, repo.snapshot.value!!)
    // start() e' idempotente.
    repo.start()
    settle()
    assertThat(source.builds.get()).isEqualTo(1)
  }

  @Test
  fun avvioAFreddoDallIstantaneaSenzaRicostruireSeIlSeqECorrente() = runTest {
    val source = model(1_500)
    // La prima "esecuzione dell'app" ha il suo scope: si chiude prima che il catalogo cambi.
    val firstRun = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
    val (first, store) = repository(source, firstRun)
    first.start()
    settle()
    firstRun.cancel()
    settle()
    val builds = source.builds.get()

    val secondRun = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
    val (second, _) = repository(source, secondRun)
    second.start()
    // Il primo indice pubblicato e' quello del file, prima di qualunque lettura del catalogo.
    assertSameIndex(second.snapshot.value!!, store.read()!!.snapshot)
    settle()
    assertThat(source.builds.get()).isEqualTo(builds)
    assertThat(second.rebuilds).isEqualTo(0)
    secondRun.cancel()
    settle()

    // Il catalogo e' andato avanti mentre l'app era chiusa: il file si mostra, poi si ricostruisce.
    val m = source.media.keys.first()
    source.media[m]!!.hidden = true
    source.commit(listOf(m), structural = true)
    assertThat(store.read()!!.snapshot.indexOf(MediaId(m))).isAtLeast(0)
    val (third, _) = repository(source)
    third.start()
    settle()
    assertThat(third.rebuilds).isEqualTo(1)
    assertThat(third.snapshot.value!!.indexOf(MediaId(m))).isEqualTo(-1)
    assertThat(store.read()!!.snapshot.indexOf(MediaId(m))).isEqualTo(-1)
  }

  /**
   * Una vista chiesta per la prima volta mentre si pubblica un indice nuovo non resta sul vecchio. `view` leggeva l'indice, creava la vista e
   * solo dopo la iscriveva; `publish` aggiorna le viste iscritte: un indice pubblicato in mezzo non la raggiungeva, e la vista restava vuota (o
   * vecchia) fino al cambiamento successivo. `GalleryIntegrationTest` aspettava 20 secondi un indice che era gia' arrivato, una volta ogni
   * qualche migliaio di giri e di piu' sotto carico. La corsa si provoca apposta: l'indice nuovo arriva proprio nella finestra.
   */
  @Test
  fun unaVistaChiestaMentreSiPubblicaUnIndiceNuovoNonRestaSulVecchio() = runBlocking<Unit> {
    val source = model(50)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val repo = CatalogTimelineRepository(source, null, scope)
      repo.start()
      val before = repo.snapshot.value!!
      val gone = source.media.keys.first()
      // Fra la lettura dell'indice e l'iscrizione della vista un altro thread pubblica l'indice senza il primo media.
      repo.viewRegistrationHook = { runBlocking { repo.forget(listOf(MediaId(gone))) } }

      val view = repo.view(LibraryFilter.ALL)

      repo.viewRegistrationHook = null
      val after = repo.snapshot.value!!
      assertThat(after).isNotSameInstanceAs(before)
      assertThat(view.value.snapshot).isSameInstanceAs(after)
      assertThat(view.value.size).isEqualTo(49)
      // La vista e' una sola per filtro, e da qui in poi segue gli indici come sempre.
      assertThat(repo.view(LibraryFilter.ALL)).isSameInstanceAs(view)
      repo.forget(listOf(MediaId(source.media.keys.drop(1).first())))
      assertThat(view.value.size).isEqualTo(48)
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun iCambiamentiSiApplicanoAccorpatiELeVisteLiSeguono() = runTest {
    val source = model(3_000)
    val (repo, store) = repository(source)
    repo.start()
    settle()
    val all = repo.view(LibraryFilter.ALL)
    val videos = repo.view(LibraryFilter.VIDEOS)
    val favorites = repo.view(LibraryFilter.FAVORITES)
    val items = TimelineViewerItems(repo, LibraryFilter.ALL)
    assertThat(all.value.size).isEqualTo(3_000)
    assertThat(favorites.value.size).isEqualTo(0)
    val writesBefore = repo.snapshotWrites

    // Una raffica di 50 preferiti, uno per transazione: una patch o poche, e l'istantanea scritta una volta.
    val ids = source.media.keys.take(50)
    for (id in ids) {
      val media = source.media[id]!!
      media.row = media.row.copy(cellFlags = media.row.cellFlags or CellFlags.FAVORITE)
      source.commit(listOf(id), structural = false)
    }
    runCurrent()
    repo.awaitSeq(source.seq)
    assertThat(repo.patches).isAtMost(50)
    assertThat(favorites.value.size).isEqualTo(50)
    assertThat(items.view.value.snapshot.seq).isEqualTo(source.seq)
    assertThat(repo.snapshotWrites).isEqualTo(writesBefore)
    advanceTimeBy(5_100)
    runCurrent()
    assertThat(repo.snapshotWrites).isEqualTo(writesBefore + 1)
    assertThat(store.read()!!.snapshot.seq).isEqualTo(source.seq)

    // Un media nuovo, il piu' recente: entra in testa; la vista dei video lo vede.
    val fresh = UUID(42, 42).toString()
    source.media[fresh] = ModelSource.Media(indexRow(fresh, sortTime = 1_800_000_000_000L, kind = "video", duration = 5_000), ref = MediaRef.Phone("external_primary", 9_999, "video/mp4"))
    source.commit(listOf(fresh), structural = true)
    runCurrent()
    repo.awaitSeq(source.seq)
    assertThat(all.value.snapshot.idAt(0).value).isEqualTo(fresh)
    assertThat(videos.value.viewIndexOf(MediaId(fresh))).isEqualTo(0)
    assertThat(items.refOrNull(MediaId(fresh))).isEqualTo(MediaRef.Phone("external_primary", 9_999, "video/mp4"))

    // Cancellato: esce dalla vista (E-V6), ma il viewer che lo mostra ha ancora il suo riferimento per un attimo; e
    // l'istantanea si riscrive subito, senza aspettare.
    val writes = repo.snapshotWrites
    source.media.remove(fresh)
    source.commit(listOf(fresh), structural = true)
    runCurrent()
    repo.awaitSeq(source.seq)
    advanceTimeBy(10)
    runCurrent()
    assertThat(items.view.value.viewIndexOf(MediaId(fresh))).isEqualTo(-1)
    assertThat(items.ref(MediaId(fresh))).isEqualTo(MediaRef.Phone("external_primary", 9_999, "video/mp4"))
    assertThat(repo.snapshotWrites).isEqualTo(writes + 1)
    assertSameIndex(repo.snapshot.value!!, source.expected())
  }

  @Test
  fun unSaltoDiSeqRicostruisce() = runTest {
    val source = model(1_000)
    val (repo, _) = repository(source)
    repo.start()
    settle()
    // Tre cambiamenti persi dal flusso, poi uno ricevuto: il buco porta a una ricostruzione.
    source.dropEmissions = true
    val keys = source.media.keys.toList()
    for (k in 0 until 3) {
      source.media[keys[k]]!!.hidden = true
      source.commit(listOf(keys[k]), structural = true)
    }
    source.dropEmissions = false
    val m = keys[10]
    source.media[m]!!.row = source.media[m]!!.row.copy(aspectMilli = 1_777)
    source.commit(listOf(m), structural = false)
    settle()
    assertThat(repo.rebuilds).isEqualTo(2)
    assertSameIndex(repo.snapshot.value!!, source.expected())
  }

  @Test
  fun dimenticarePrimaDiNascondereNonLasciaIlNascostoSulDisco() = runTest {
    val source = model(800)
    val (repo, store) = repository(source)
    repo.start()
    settle()
    val secret = source.media.keys.drop(100).take(3).map { MediaId(it) }
    repo.forget(secret)
    // Prima ancora che il catalogo scriva: fuori dall'indice, dalle viste e dal file (provvisorio).
    assertThat(secret.all { repo.snapshot.value!!.indexOf(it) < 0 }).isTrue()
    assertThat(secret.all { repo.view(LibraryFilter.ALL).value.viewIndexOf(it) < 0 }).isTrue()
    val onDisk = store.read()!!
    assertThat(onDisk.provisional).isTrue()
    assertThat(secret.all { onDisk.snapshot.indexOf(it) < 0 }).isTrue()

    // Poi il catalogo li nasconde: la patch e' coerente e il file torna definitivo.
    secret.forEach { source.media[it.value]!!.hidden = true }
    source.commit(secret.map { it.value }, structural = true)
    settle()
    assertSameIndex(repo.snapshot.value!!, source.expected())
    advanceTimeBy(6_000)
    settle()
    assertThat(store.read()!!.provisional).isFalse()
    assertThat(secret.all { store.read()!!.snapshot.indexOf(it) < 0 }).isTrue()

    // Un repository senza indice in memoria cancella il file.
    val (cold, _) = repository(source)
    cold.forget(secret)
    assertThat(store.read()).isNull()
  }

  @Test
  fun unFileProvvisorioSiRicostruisceAlProssimoAvvio() = runTest {
    val source = model(300)
    val (repo, store) = repository(source)
    repo.start()
    settle()
    repo.forget(listOf(MediaId(source.media.keys.first())))
    // Il processo muore qui: il catalogo non ha scritto niente.
    val (next, _) = repository(source)
    next.start()
    settle()
    assertThat(next.rebuilds).isEqualTo(1)
    assertSameIndex(next.snapshot.value!!, source.expected())
    assertThat(store.read()!!.provisional).isFalse()
  }

  /** Un file di istantanea che non si riscrive e non si toglie (disco in sola lettura, un altro handle): ricorda cosa gli e' stato chiesto. */
  private class StuckStore(dir: File, io: kotlinx.coroutines.CoroutineDispatcher) : SnapshotStore(dir, io) {
    val writes = ArrayList<Pair<Int, Boolean>>()
    var deletes = 0

    override suspend fun write(snapshot: dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot, provisional: Boolean): Boolean {
      writes += snapshot.size to provisional
      // Solo un'istantanea vuota "entra": quella e' la sostituzione di ripiego.
      return snapshot.size == 0
    }

    override suspend fun delete(): Boolean {
      deletes++
      return false
    }
  }

  @Test
  fun unNascostoCheNonSiRiesceAToglierDalFile_sostituisceIlFileConUnoVuotoEProvvisorio() = runTest {
    // R11: prima l'esito di `delete()` era ignorato, e il file con il nascosto restava per il riavvio (la prima immagine viene dal file).
    val source = model(300)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = StuckStore(dir, dispatcher)
    val repo = CatalogTimelineRepository(source, store, backgroundScope, worker = dispatcher, writeDelayMs = 5_000)
    repo.start()
    settle()
    store.writes.clear()
    store.deletes = 0

    repo.forget(listOf(MediaId(source.media.keys.first())))

    assertThat(store.deletes).isAtLeast(1)
    // L'ultima cosa scritta e' un'istantanea vuota e provvisoria: il riavvio la ricostruisce.
    assertThat(store.writes.last()).isEqualTo(0 to true)
  }
}
