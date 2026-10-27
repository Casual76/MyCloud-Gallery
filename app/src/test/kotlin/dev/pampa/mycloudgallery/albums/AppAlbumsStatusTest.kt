package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsStatus
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Lo stato degli album nei primi secondi (Fase 7, revisione di fine fase, AP-1): prima del montaggio l'elenco vuoto vuol dire «non ho ancora letto», non
 * «nessun album». Lo stato resta `Loading` finche' gli album non sono montati o non e' chiaro che non lo saranno a breve (nessun NAS, nessuna famiglia:
 * dopo [AlbumsGraph.LOADING_GRACE]); poi `Ready`, e l'elenco vuoto dice quello che e'. Il tempo e' quello virtuale del test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppAlbumsStatusTest {

  @Test
  fun primaDelMontaggioGliAlbumSiStannoLeggendo() = runTest {
    val graph = AlbumsGraph(backgroundScope, { null }, MutableStateFlow(false))
    val albums = AppAlbums(graph, backgroundScope)
    runCurrent()
    assertThat(albums.status.value).isEqualTo(AlbumsStatus.Loading)
    graph.start()
    runCurrent()
    assertThat(albums.status.value).isEqualTo(AlbumsStatus.Loading)
    // Poco prima del limite si legge ancora.
    advanceTimeBy(AlbumsGraph.LOADING_GRACE - 1.seconds)
    runCurrent()
    assertThat(albums.status.value).isEqualTo(AlbumsStatus.Loading)
  }

  @Test
  fun senzaFamigliaDopoLAttesaGliAlbumSonoPronti_EVuotiVuolDireNessunAlbum() = runTest {
    val graph = AlbumsGraph(backgroundScope, { null }, MutableStateFlow(false))
    val albums = AppAlbums(graph, backgroundScope)
    graph.start()
    advanceTimeBy(AlbumsGraph.LOADING_GRACE + 1.seconds)
    runCurrent()
    assertThat(graph.settled.value).isTrue()
    assertThat(albums.status.value).isEqualTo(AlbumsStatus.Ready)
  }
}
