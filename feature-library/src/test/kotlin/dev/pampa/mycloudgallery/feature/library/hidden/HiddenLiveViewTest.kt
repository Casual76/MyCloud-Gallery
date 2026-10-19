package dev.pampa.mycloudgallery.feature.library.hidden

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * GA-1 (verifica funzionale), il finto: la vista dei Nascosti segue le azioni, come quella del catalogo vero. Prima il finto dava una
 * fotografia, e i test del viewer e dei Nascosti passavano anche se a schermo non cambiava niente. Lo stesso scenario sul catalogo vero
 * sta in `VerificaFunzionaleGalleriaTest.ga1_…` di `:app` (stessi passi, stesse attese): se uno dei due cambia, l'altro lo dice.
 */
class HiddenLiveViewTest {

  private val world = FakeCollectionsWorld()

  private fun TimelineView.isFavorite(id: MediaId): Boolean {
    val index = snapshot.indexOf(id)
    return index >= 0 && CellFlags.has(snapshot.flagsAt(index), CellFlags.FAVORITE)
  }

  private fun TimelineView.firstTwo(): Pair<MediaId, MediaId> = snapshot.idAt(snapshotIndex(0)) to snapshot.idAt(snapshotIndex(1))

  @Test
  fun mostraNellaLibreriaTogliELElementoDallaVistaDeiNascosti() = runBlocking<Unit> {
    val items = world.hiddenLibrary.read()
    val (first, second) = items.view.value.firstTwo()
    val before = items.view.value.size

    world.actions.setHidden(setOf(first), false)

    assertThat(items.view.value.viewIndexOf(first)).isLessThan(0)
    assertThat(items.view.value.viewIndexOf(second)).isAtLeast(0)
    assertThat(items.view.value.size).isEqualTo(before - 1)
    // E in Libreria e' tornato.
    assertThat(world.repository.view(dev.pampa.mycloudgallery.core.library.contract.LibraryFilter.ALL).value.viewIndexOf(first)).isAtLeast(0)
  }

  @Test
  fun ilPreferitoDalViewerSiVedeNellaVista() = runBlocking<Unit> {
    val items = world.hiddenLibrary.read()
    val id = items.view.value.snapshot.let { s -> (0 until s.size).map { s.idAt(it) }.first { !items.view.value.isFavorite(it) } }

    world.actions.setFavorite(setOf(id), true)

    assertThat(items.view.value.isFavorite(id)).isTrue()
  }

  @Test
  fun ogniLetturaEUnaVistaAGiustoTitolo() = runBlocking<Unit> {
    val old = world.hiddenLibrary.read()
    val (first, _) = old.view.value.firstTwo()
    world.actions.setHidden(setOf(first), false)

    // Una lettura nuova parte dallo stato di adesso.
    val fresh = world.hiddenLibrary.read()

    assertThat(fresh.view.value.viewIndexOf(first)).isLessThan(0)
    assertThat(world.hiddenLibrary.reads).isEqualTo(2)
  }
}
