package dev.pampa.mycloudgallery.feature.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerItems
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.backup.contract.BackupCoverPorts
import dev.pampa.mycloudgallery.feature.backup.contract.SourceNewestMedia
import dev.pampa.mycloudgallery.feature.backup.free.FreePreviewCount
import dev.pampa.mycloudgallery.feature.backup.free.freePreviewRequests
import dev.pampa.mycloudgallery.feature.backup.ui.BackupCovers
import dev.pampa.mycloudgallery.feature.backup.ui.coverWindow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le copertine del backup e le anteprime di Libera spazio (giro 1 della fase I, CS-11 e CG-3). **Ogni anteprima segue la Libreria visibile anche dopo
 * essere stata disegnata**: una foto che si nasconde smette di essere la copertina al passaggio dopo, e la sua richiesta non si fa piu' nemmeno nel
 * frattempo (il controllo al momento di disegnare). E le miniature si chiedono per le copertine sullo schermo e poche accanto, non per le prime 24.
 * Ogni prova cade se si toglie il controllo che prova.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BackupCoversTest {

  private val library = SyntheticLibrary(seed = 7L, count = 120)
  private val view: TimelineView = library.view()
  private val items = FakeViewerItems(view) { library.ref(it) }
  private val loader = FakeThumbnailLoader()

  private fun idAt(i: Int): MediaId = view.snapshot.idAt(view.snapshotIndex(i))

  /** La stessa vista senza [gone]: come la Libreria quando una foto si nasconde (non c'e' piu' nella vista visibile). */
  private class Without(private val base: TimelineView, private val gone: Set<MediaId>) : TimelineView by base {
    override fun viewIndexOf(id: MediaId): Int = if (id in gone) -1 else base.viewIndexOf(id)
  }

  private fun covers(newest: Map<String, List<MediaId>>): BackupCovers =
    BackupCovers(BackupCoverPorts(newest = SourceNewestMedia { _, _ -> newest }, library = items, loader = loader))

  /** CS-11: la richiesta di una foto che non e' nella Libreria visibile e' nulla (cade senza il controllo `viewIndexOf`). */
  @Test
  fun unaFotoFuoriDallaVistaNonHaRichiesta() {
    val photo = idAt(3)
    val covers = covers(emptyMap())
    assertThat(covers.request(view, photo)).isNotNull()
    assertThat(covers.request(Without(view, setOf(photo)), photo)).isNull()
  }

  /**
   * CS-11: la copertina e' la foto piu' recente **mostrabile**. Quando si nasconde, al passaggio dopo la copertina e' la seguente (cade senza il
   * filtro sulla vista in `refresh`); se si nascondono tutte, la cartella torna alla piastrella.
   */
  @Test
  fun unaFotoNascostaDopoEssereStataDisegnataSmetteDiEssereLaCopertina() = runTest {
    val first = idAt(0)
    val second = idAt(1)
    val covers = covers(mapOf(BackupSource.Camera.id to listOf(first, second)))
    val job = backgroundScope.launch { covers.refresh(listOf(BackupSource.Camera)) }
    runCurrent()
    assertThat(covers.cover(BackupSource.Camera)).isEqualTo(first)

    items.publish(Without(view, setOf(first)))
    advanceTimeBy(BackupCovers.PassPeriodMs + 1)
    runCurrent()
    assertThat(covers.cover(BackupSource.Camera)).isEqualTo(second)

    items.publish(Without(view, setOf(first, second)))
    advanceTimeBy(BackupCovers.PassPeriodMs + 1)
    runCurrent()
    assertThat(covers.cover(BackupSource.Camera)).isNull()
    job.cancel()
  }

  /** CS-11, Libera spazio: le anteprime sono solo foto della vista, nell'ordine del piano, al piu' quattro (cade senza il cancello della vista). */
  @Test
  fun leAnteprimeDiLiberaSpazioSaltanoLeFotoNascoste() {
    val covers = covers(emptyMap())
    val plan = (0 until 8).map { idAt(it) }
    val hidden = Without(view, setOf(plan[0], plan[2]))
    val keys = freePreviewRequests(plan) { covers.request(hidden, it) }.map { it.key.mediaId }
    assertThat(keys).containsExactly(plan[1], plan[3], plan[4], plan[5]).inOrder()
    assertThat(keys).hasSize(FreePreviewCount)
    assertThat(freePreviewRequests(listOf(plan[0], plan[2])) { covers.request(hidden, it) }).isEmpty()
  }

  /** CG-3: la finestra delle copertine e' quella sullo schermo, con poche sorgenti prima e dopo; senza niente sullo schermo, le prime. */
  @Test
  fun laFinestraSegueLoSchermo() {
    val sources = (0 until 40).map { BackupSource.Folder("external_primary", "Cartella/$it/") }
    val none = coverWindow(sources, emptySet(), 6)
    assertThat(none.visible).isEmpty()
    assertThat(none.ahead).containsExactlyElementsIn(sources.take(6)).inOrder()

    val window = coverWindow(sources, setOf(sources[30].id, sources[31].id), 6)
    assertThat(window.visible).containsExactly(sources[30], sources[31]).inOrder()
    assertThat(window.ahead).containsExactlyElementsIn(sources.subList(32, 38) + sources.subList(24, 30).reversed()).inOrder()

    val end = coverWindow(sources, setOf(sources[39].id), 6)
    assertThat(end.ahead).containsExactlyElementsIn(sources.subList(33, 39).reversed()).inOrder()
  }

  /**
   * CG-3 (la prova del telefono, qui): 40 cartelle, cache fredda, si scorre alla 30esima. Prima la sua miniatura non si chiedeva mai (le richieste
   * erano le prime 24 e basta); ora si chiede appena la copertina entra nello schermo, e la finestra si rimanda solo quando cambia.
   */
  @Test
  fun laTrentesimaCartellaSiChiedeQuandoEntraNelloSchermo() = runTest {
    val sources = (0 until 40).map { BackupSource.Folder("external_primary", "Cartella/$it/") }
    val newest = sources.mapIndexed { i, source -> source.id to listOf(idAt(i)) }.toMap()
    val covers = covers(newest)
    val job = backgroundScope.launch { covers.refresh(sources) }
    runCurrent()
    // Niente ancora sullo schermo: le prime, in anticipo.
    assertThat(loader.windows.last().first).isEmpty()
    assertThat(loader.windows.last().second.map { it.key.mediaId }).containsExactlyElementsIn((0 until 6).map { idAt(it) }).inOrder()

    val sent = loader.windows.size
    covers.enter(sources[30])
    advanceTimeBy(BackupCovers.WindowStepMs + 1)
    runCurrent()
    assertThat(loader.windows.size).isEqualTo(sent + 1)
    assertThat(loader.windows.last().first.map { it.key.mediaId }).containsExactly(idAt(30))
    assertThat(loader.windows.last().second.map { it.key.mediaId }).contains(idAt(31))
    assertThat(loader.windows.last().second.map { it.key.mediaId }).doesNotContain(idAt(0))

    // La stessa finestra non si rimanda (una vista nuova con le stesse foto).
    items.publish(Without(view, emptySet()))
    advanceTimeBy(BackupCovers.PassPeriodMs + BackupCovers.WindowStepMs + 1)
    runCurrent()
    assertThat(loader.windows.size).isEqualTo(sent + 1)

    covers.leave(sources[30])
    covers.enter(sources[35])
    advanceTimeBy(BackupCovers.WindowStepMs + 1)
    runCurrent()
    assertThat(loader.windows.last().first.map { it.key.mediaId }).containsExactly(idAt(35))
    job.cancel()
  }
}
