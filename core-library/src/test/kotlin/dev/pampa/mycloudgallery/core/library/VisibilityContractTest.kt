package dev.pampa.mycloudgallery.core.library

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.library.noop.AllHiddenMask
import dev.pampa.mycloudgallery.core.library.noop.NoVisibleMedia
import dev.pampa.mycloudgallery.core.library.noop.NoWidgetFeed
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.FakeWidgetFeed
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Fase 7 (P0, D22, D24): le firme della visibilita' (nascosti mai, da una sola fonte) e il filtro "Modificate". */
class VisibilityContractTest {

  private val a = MediaId("00000000-0000-4000-8000-000000000001")
  private val b = MediaId("00000000-0000-4000-8000-000000000002")
  private val c = MediaId("00000000-0000-4000-8000-000000000003")

  /** I null-object falliscono chiusi: niente visibile, tutto nascosto, nessun widget. */
  @Test
  fun iNullObjectFallisconoChiusi() = runBlocking<Unit> {
    assertThat(NoVisibleMedia.snapshot.value).isNull()
    assertThat(NoVisibleMedia.visible(listOf(a, b))).isEmpty()
    assertThat(AllHiddenMask.hiddenAmong(listOf(a, b))).containsExactly(a, b)
    assertThat(NoWidgetFeed.next(4)).isEmpty()
  }

  /** Il nascosto e' contagioso nei gruppi (F5-D36); un id sconosciuto non e' visibile; il filtro finale si conta. */
  @Test
  fun ilFintoDellaVisibilitaRispettaIlContratto() = runBlocking<Unit> {
    val v = FakeVisibility(present = setOf(a, b, c), hidden = setOf(b), groups = listOf(setOf(b, c)))
    assertThat(v.visible(listOf(a, b, c, MediaId("00000000-0000-4000-8000-000000000009")))).containsExactly(a)
    assertThat(v.hiddenAmong(listOf(a, b, c))).containsExactly(b, c)
    assertThat(v.maskCalls).isEqualTo(1)
  }

  /** Il widget finto gira sull'istantanea (che i nascosti non li ha) e applica il filtro finale: un nascosto adesso non esce. */
  @Test
  fun ilWidgetNonMostraMaiUnNascosto() = runBlocking<Unit> {
    val library = SyntheticLibrary(count = 20, seed = 7)
    val snapshot = library.snapshot
    val ids = (0 until snapshot.size).map { snapshot.idAt(it) }
    val hiddenNow = ids.take(3).toSet()
    val v = FakeVisibility(snapshot = snapshot, present = ids.toSet(), hidden = hiddenNow)
    val feed = FakeWidgetFeed(v, v)
    repeat(5) {
      val shown = feed.next(WidgetFeed.MAX_SIZE)
      assertThat(shown).isNotEmpty()
      assertThat(shown.toSet() intersect hiddenNow).isEmpty()
    }
    assertThat(feed.next(0)).isEmpty()
  }

  @Test
  fun ilFiltroModificate() {
    assertThat(LibraryFilter.EDITED.accepts(MediaKind.PHOTO, MediaSource.CAMERA, CellFlags.EDITED)).isTrue()
    assertThat(LibraryFilter.EDITED.accepts(MediaKind.PHOTO, MediaSource.CAMERA, 0)).isFalse()
    assertThat(LibraryFilter.fromCode("edited")).isEqualTo(LibraryFilter.EDITED)
  }
}
