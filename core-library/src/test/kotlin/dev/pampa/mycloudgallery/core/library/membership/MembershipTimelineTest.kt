package dev.pampa.mycloudgallery.core.library.membership

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.FilterExtras
import dev.pampa.mycloudgallery.core.library.contract.FilterMembership
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.index.AdjustedTimelineView
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** G2b: la vista con le correzioni del catalogo (Fotocamera, Messaggistica) e il repository che la applica solo ai filtri che ne hanno bisogno. */
@OptIn(ExperimentalCoroutinesApi::class)
class MembershipTimelineTest {

  private val library = SyntheticLibrary(seed = 7L, count = 600)
  private val snapshot = library.snapshot

  private fun idsOf(view: TimelineView): List<MediaId> = List(view.size) { view.snapshot.idAt(view.snapshotIndex(it)) }

  private fun indicesWhere(source: MediaSource): List<Int> = (0 until snapshot.size).filter { snapshot.sourceAt(it) == source }

  @Test
  fun aggiungeETogliePerIdNellOrdineDellIndice() {
    val base = FilteredTimelineView(snapshot, LibraryFilter.MESSAGING)
    val whatsapp = indicesWhere(MediaSource.WHATSAPP)
    val others = indicesWhere(MediaSource.QUICK_SHARE)
    assertThat(whatsapp.size).isAtLeast(3)
    assertThat(others.size).isAtLeast(3)
    val plus = others.take(3).map { snapshot.idAt(it) }.toSet()
    val minus = setOf(snapshot.idAt(whatsapp[1]))
    // Un id che non e' nell'indice (nascosto, sparito) non conta.
    val ghost = MediaId("00000000-0000-4000-8000-00000000beef")
    val view = AdjustedTimelineView.of(base, FilterExtras(plus = plus + ghost, minus = minus))

    val expected = (whatsapp + others.take(3)).filter { it != whatsapp[1] }.sorted()
    assertThat((0 until view.size).map { view.snapshotIndex(it) }).containsExactlyElementsIn(expected).inOrder()
    assertThat(view.filter).isEqualTo(LibraryFilter.MESSAGING)
    // La posizione nella vista ritrova ogni id, e nessun altro.
    idsOf(view).forEachIndexed { position, id -> assertThat(view.viewIndexOf(id)).isEqualTo(position) }
    assertThat(view.viewIndexOf(snapshot.idAt(whatsapp[1]))).isEqualTo(-1)
    assertThat(view.viewIndexOf(ghost)).isEqualTo(-1)
  }

  @Test
  fun unIdGiaNellaVistaNonSiRaddoppiaESenzaCorrezioniLaVistaELaStessa() {
    val base = FilteredTimelineView(snapshot, LibraryFilter.CAMERA)
    val already = snapshot.idAt(base.snapshotIndex(0))
    val view = AdjustedTimelineView.of(base, FilterExtras(plus = setOf(already)))
    assertThat(view.size).isEqualTo(base.size)
    assertThat(AdjustedTimelineView.of(base, FilterExtras.NONE)).isSameInstanceAs(base)
  }

  @Test
  fun ilRepositoryCorreggeSoloFotocameraEMessaggistica() = runTest(UnconfinedTestDispatcher()) {
    val repo = FakeTimelineRepository(snapshot)
    val telegram = snapshot.idAt(indicesWhere(MediaSource.QUICK_SHARE).first())
    val notMine = snapshot.idAt(indicesWhere(MediaSource.CAMERA).first())
    val extras = MutableStateFlow(FilterExtras.NONE)
    val membership = object : FilterMembership {
      override fun extras(filter: LibraryFilter): Flow<FilterExtras> = when (filter) {
        LibraryFilter.MESSAGING -> extras
        LibraryFilter.CAMERA -> flowOf(FilterExtras(minus = setOf(notMine)))
        else -> error("nessuna correzione per $filter")
      }
    }
    val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
    val timeline = MembershipTimeline(repo, membership, scope)

    // Gli altri filtri sono il repository di sotto, la stessa StateFlow.
    assertThat(timeline.view(LibraryFilter.ALL)).isSameInstanceAs(repo.view(LibraryFilter.ALL))
    assertThat(timeline.view(LibraryFilter.WHATSAPP)).isSameInstanceAs(repo.view(LibraryFilter.WHATSAPP))

    val messaging = timeline.view(LibraryFilter.MESSAGING)
    assertThat(messaging.value.size).isEqualTo(indicesWhere(MediaSource.WHATSAPP).size)
    extras.value = FilterExtras(plus = setOf(telegram))
    assertThat(messaging.value.size).isEqualTo(indicesWhere(MediaSource.WHATSAPP).size + 1)
    assertThat(messaging.value.viewIndexOf(telegram)).isAtLeast(0)

    val camera = timeline.view(LibraryFilter.CAMERA)
    assertThat(camera.value.size).isEqualTo(indicesWhere(MediaSource.CAMERA).size - 1)
    assertThat(camera.value.viewIndexOf(notMine)).isEqualTo(-1)

    // Un indice nuovo: la vista si rifa' sopra quello, con le stesse correzioni.
    val smaller = SyntheticLibrary(seed = 7L, count = 300).snapshot
    repo.publish(smaller)
    assertThat(messaging.value.snapshot).isSameInstanceAs(smaller)
    scope.cancel()
  }
}
