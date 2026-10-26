package dev.pampa.mycloudgallery.core.library.visibility

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 7 (P9, D22): la sorgente del widget di produzione gira sull'istantanea e non da' mai un nascosto. Logica pura con `FakeVisibility`:
 * l'istantanea e' quella di prima del "Nascondi", il filtro finale no.
 */
class RotatingWidgetFeedTest {

  private val library = SyntheticLibrary(count = 40, seed = 11)
  private val snapshot = library.snapshot
  private val ids: List<MediaId> = (0 until snapshot.size).map { snapshot.idAt(it) }

  private fun visibility(hidden: Set<MediaId> = emptySet(), groups: List<Set<MediaId>> = emptyList()) =
    FakeVisibility(snapshot = snapshot, present = ids.toSet(), hidden = hidden, groups = groups)

  @Test
  fun giraSuTuttaLaLibreriaESiRipete() = runBlocking<Unit> {
    val feed = RotatingWidgetFeed(visibility(), visibility())
    val first = ArrayList<MediaId>()
    repeat(5) { first += feed.next(8) }

    assertThat(first).containsExactlyElementsIn(ids).inOrder()
    assertThat(feed.next(8)).containsExactlyElementsIn(ids.take(8)).inOrder()
  }

  @Test
  fun unaCellaScartataNonOccupaUnPostoENonEsceMai() = runBlocking<Unit> {
    val hidden = ids.take(3).toSet() + ids[10] + ids[11]
    val v = visibility(hidden = hidden)
    val feed = RotatingWidgetFeed(v, v)

    val shown = feed.next(6)

    assertThat(shown).hasSize(6)
    assertThat(shown).containsNoneIn(hidden)
    assertThat(shown).containsExactlyElementsIn(ids.filter { it !in hidden }.take(6)).inOrder()
  }

  @Test
  fun ilNascostoDiUnGruppoNascondeTuttiIMembri() = runBlocking<Unit> {
    val group = setOf(ids[0], ids[1], ids[2])
    val v = visibility(hidden = setOf(ids[1]), groups = listOf(group))
    val feed = RotatingWidgetFeed(v, v)

    assertThat(feed.next(WidgetFeed.MAX_SIZE)).containsNoneIn(group)
  }

  @Test
  fun ilFiltroFinaleSiApplicaAnchePerUnMediaNascostoDopoL_Istantanea() = runBlocking<Unit> {
    val v = visibility()
    val feed = RotatingWidgetFeed(v, v)
    assertThat(feed.next(4)).containsExactlyElementsIn(ids.take(4))

    v.hidden += ids[4] // nascosto un attimo fa: l'istantanea ce l'ha ancora

    assertThat(feed.next(4)).doesNotContain(ids[4])
    assertThat(v.maskCalls).isAtLeast(2)
  }

  @Test
  fun nonOltrepassaIlTettoNeLaLibreria() = runBlocking<Unit> {
    val v = visibility()
    val feed = RotatingWidgetFeed(v, v)

    assertThat(feed.next(1_000)).hasSize(WidgetFeed.MAX_SIZE)
    assertThat(feed.next(0)).isEmpty()
    assertThat(feed.next(-3)).isEmpty()
    val tiny = RotatingWidgetFeed(FakeVisibility(snapshot = snapshot, present = ids.take(2).toSet()), FakeVisibility())
    assertThat(tiny.next(8).size).isAtMost(2)
  }

  @Test
  fun senzaIstantaneaOSenzaCelleNienteDaMostrare() = runBlocking<Unit> {
    val none = RotatingWidgetFeed(FakeVisibility(snapshot = null), FakeVisibility())
    assertThat(none.next(4)).isEmpty()
    val empty = RotatingWidgetFeed(FakeVisibility(snapshot = EmptyTimelineSnapshot), FakeVisibility())
    assertThat(empty.next(4)).isEmpty()
  }

  @Test
  fun tuttoNascostoDaListaVuotaSenzaGirareAVuoto() = runBlocking<Unit> {
    val v = visibility(hidden = ids.toSet())
    val feed = RotatingWidgetFeed(v, v)

    assertThat(feed.next(4)).isEmpty()
  }

  @Test
  fun unErroreDelFiltroDaListaVuota_FallireChiusi() = runBlocking<Unit> {
    val throwingMask = HiddenMask { error("catalogo non raggiungibile") }
    val feed = RotatingWidgetFeed(visibility(), throwingMask)
    assertThat(feed.next(4)).isEmpty()

    val library: TimelineSnapshot = snapshot
    val throwingVisible = object : VisibleMedia {
      override val snapshot: StateFlow<TimelineSnapshot?> = MutableStateFlow(library)

      override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = error("catalogo non raggiungibile")
    }
    assertThat(RotatingWidgetFeed(throwingVisible, visibility()).next(4)).isEmpty()
  }

  @Test
  fun l_AnnullamentoPassaENonSiIngoia() {
    val cancelling = HiddenMask { throw CancellationException("annullato") }
    val feed = RotatingWidgetFeed(visibility(), cancelling)

    assertThrows(CancellationException::class.java) { runBlocking { feed.next(4) } }
  }

  @Test
  fun piuChiamateInsiemeNonSiPestanoLaStessaFinestra() = runBlocking<Unit> {
    val v = visibility()
    val feed = RotatingWidgetFeed(v, v)

    val parts = (0 until 5).map { async { feed.next(8) } }.awaitAll()

    assertThat(parts.flatten()).containsExactlyElementsIn(ids)
  }
}
