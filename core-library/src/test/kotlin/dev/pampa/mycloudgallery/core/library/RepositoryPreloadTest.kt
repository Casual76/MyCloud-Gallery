package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineRepository
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Avvio istantaneo (28/10): l'istantanea su file si pubblica **prima** del catalogo ([CatalogTimelineRepository.preload]), la riconciliazione la riusa
 * senza rileggere ne' ripubblicare (le viste restano le stesse: niente layout rifatti), e un media nascosto prima della chiusura non compare
 * nell'istantanea della partenza dopo, nemmeno per un fotogramma (E-H1: il "togli subito" riscrive il file prima che il catalogo nasconda).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryPreloadTest {

  private val dir: File = Files.createTempDirectory("preload").toFile()

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun model(n: Int): ModelSource {
    val r = Random(7)
    return ModelSource().apply {
      repeat(n) { k ->
        val id = UUID(r.nextLong(), r.nextLong()).toString()
        media[id] = ModelSource.Media(
          indexRow(id, sortTime = 1_700_000_000_000L - k * 60_000L, localDay = 20231114 - k / 500, source = "camera", kind = "photo", duration = null),
          ref = MediaRef.Phone("external_primary", 1L + k, "image/jpeg"),
        )
      }
    }
  }

  private fun TestScope.settle() {
    advanceTimeBy(60_000)
    runCurrent()
  }

  /** Un'"esecuzione dell'app": un repository con il suo scope, sullo stesso file. */
  private fun TestScope.run(source: ModelSource): Pair<CatalogTimelineRepository, CoroutineScope> {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val scope = CoroutineScope(dispatcher + Job())
    return CatalogTimelineRepository(source, SnapshotStore(dir, io = dispatcher), scope, worker = dispatcher, writeDelayMs = 5_000) to scope
  }

  @Test
  fun lIstantaneaSiPubblicaPrimaDelCatalogoELaRiconciliazioneNonLaRipubblica() = runTest {
    val source = model(1_200)
    val (first, firstScope) = run(source)
    first.start()
    settle()
    firstScope.cancel()
    settle()
    val builds = source.builds.get()

    val (second, secondScope) = run(source)
    second.preload()
    // Prima di qualunque lettura del catalogo: l'indice e la vista sono quelli del file.
    val preloaded = second.snapshot.value!!
    assertSameIndex(preloaded, source.expected())
    val view = second.view(LibraryFilter.ALL).value
    assertThat(view.size).isEqualTo(1_200)

    second.start()
    settle()
    assertThat(second.reconciled.value).isTrue()
    assertThat(second.rebuilds).isEqualTo(0)
    assertThat(source.builds.get()).isEqualTo(builds)
    // Stessa istantanea e stessa vista: la griglia non rifa' il layout preparato all'avvio.
    assertThat(second.snapshot.value).isSameInstanceAs(preloaded)
    assertThat(second.view(LibraryFilter.ALL).value).isSameInstanceAs(view)
    // preload() e' idempotente.
    second.preload()
    assertThat(second.snapshot.value).isSameInstanceAs(preloaded)
    secondScope.cancel()
    settle()
  }

  @Test
  fun unMediaNascostoPrimaDellaChiusuraNonCompareNellIstantaneaDellaPartenzaDopo() = runTest {
    val source = model(600)
    val (first, firstScope) = run(source)
    first.start()
    settle()
    val secret = source.media.keys.drop(10).take(4).map { MediaId(it) }
    // Il "togli subito" prima di nascondere (E-H1); il processo muore prima che il catalogo scriva.
    first.forget(secret)
    firstScope.cancel()
    settle()

    val (next, nextScope) = run(source)
    next.preload()
    // Al primo istante della partenza dopo: ne' nell'indice, ne' nella vista della Libreria.
    assertThat(secret.all { next.snapshot.value!!.indexOf(it) < 0 }).isTrue()
    assertThat(secret.all { next.view(LibraryFilter.ALL).value.viewIndexOf(it) < 0 }).isTrue()
    // Il file era provvisorio: la riconciliazione ricostruisce dal catalogo, che intanto li nasconde.
    secret.forEach { source.media[it.value]!!.hidden = true }
    source.commit(secret.map { it.value }, structural = true)
    next.start()
    settle()
    assertThat(next.rebuilds).isAtLeast(1)
    assertThat(secret.all { next.snapshot.value!!.indexOf(it) < 0 }).isTrue()
    nextScope.cancel()
    settle()
  }

  @Test
  fun senzaIstantaneaSulDiscoNonSiPubblicaNienteESiRicostruisce() = runTest {
    val source = model(100)
    val (repo, scope) = run(source)
    repo.preload()
    assertThat(repo.snapshot.value).isNull()
    assertThat(repo.view(LibraryFilter.ALL).value.size).isEqualTo(0)
    repo.start()
    settle()
    assertThat(repo.rebuilds).isEqualTo(1)
    assertSameIndex(repo.snapshot.value!!, source.expected())
    scope.cancel()
    settle()
  }
}
