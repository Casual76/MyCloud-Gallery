package dev.pampa.mycloudgallery.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.noop.NoSharedAlbums
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryAlbums
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerItems
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.menu.AlbumMenuResult
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.pampa.mycloudgallery.integration.Attese
import org.junit.After
import org.junit.Test

/**
 * Fase 7 (P13; P10-10, D35, D22): gli album fuori dalle loro schermate. "Aggiungi ad album" come richiesta (una per volta, mai vuota, dai Nascosti
 * lo dice), le voci ⋮ del viewer decise dalla tabella dei permessi (fuori da un album solo "Aggiungi"), il viewer aperto da un album che segue
 * l'indice della Libreria (un nascosto esce anche dal viewer aperto, E-H1) e le miniature che falliscono chiuse.
 */
class AlbumsOutsideScreensTest {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
  private val library = FakeAlbumLibrary()
  private val albums = InMemoryAlbums(library, FakeAlbumFamily(AccountId("test")), FakeAlbumClocks())
  private val requests = AlbumRequests()
  private val actions = AppViewerAlbumActions({ albums }, { NoSharedAlbums }, requests, scope)

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun shot(n: Int, hidden: Boolean = false) =
    FakeMedia(MediaId(UUID(7L, n.toLong()).toString()), takenAtUtcMs = 1_760_000_000_000L - n * 3_600_000L, hidden = hidden)

  @Test
  fun unaRichiestaAllaVoltaMaiVuotaEDaiNascostiLoDice() {
    val a = MediaId(UUID(1, 1).toString())
    val b = MediaId(UUID(1, 2).toString())
    requests.add(emptyList())
    assertThat(requests.addToAlbum.value).isNull()

    requests.add(listOf(a, a, b))
    val first = checkNotNull(requests.addToAlbum.value)
    assertThat(first.media).containsExactly(a, b).inOrder()
    assertThat(first.fromHidden).isFalse()

    // Una richiesta nuova arriva mentre la prima e' aperta: chiudere la prima non toglie la nuova.
    actions.within(null, hidden = true).addToAlbum(b)
    val second = checkNotNull(requests.addToAlbum.value)
    assertThat(second.fromHidden).isTrue()
    requests.done(first)
    assertThat(requests.addToAlbum.value).isSameInstanceAs(second)
    requests.done(second)
    assertThat(requests.addToAlbum.value).isNull()
  }

  @Test
  fun fuoriDaUnAlbumIlViewerOffreSoloAggiungi() {
    val menu = actions.menu(MediaId(UUID(2, 2).toString()))
    assertThat(menu.addToAlbum).isTrue()
    assertThat(menu.removeFromAlbum).isFalse()
    assertThat(menu.setAsCover).isFalse()
  }

  @Test
  fun dallaPaginaDiUnAlbumLeVociVengonoDallaTabellaEScrivonoNellAlbum() = runBlocking<Unit> {
    val photos = (1..3).map { shot(it) }
    library.put(*photos.toTypedArray())
    val id = (albums.create("Oslo", AlbumKind.MANUAL) as CreateOutcome.Created).id
    assertThat(albums.add(id, photos.map { it.id })).isInstanceOf(AddOutcome.Added::class.java)
    val inside = actions.within(id)

    // La prima richiesta risponde subito con "Aggiungi" e calcola il resto; la seconda legge la risposta della tabella (album personale: tutto).
    inside.menu(photos[0].id)
    val menu = inside.menu(photos[0].id)
    assertThat(menu.removeFromAlbum).isTrue()
    assertThat(menu.setAsCover).isTrue()
    assertThat(menu.removeIsExclude).isFalse()

    assertThat(inside.setAsCover(photos[1].id)).isEqualTo(AlbumMenuResult.DONE)
    assertThat(inside.removeFromAlbum(photos[0].id)).isEqualTo(AlbumMenuResult.DONE)
    val content = checkNotNull(albums.content(id).first())
    assertThat(content.items.viewIndexOf(photos[0].id)).isEqualTo(-1)
    assertThat(content.items.viewIndexOf(photos[1].id)).isAtLeast(0)
    // Fuori da un album nessuna scrittura parte, anche chiedendola.
    assertThat(actions.removeFromAlbum(photos[1].id)).isEqualTo(AlbumMenuResult.NOT_ALLOWED)
  }

  @Test
  fun ilViewerApertoDaUnAlbumSegueLIndiceEUnNascostoEsce() = runBlocking<Unit> {
    val photos = (1..4).map { shot(it) }
    library.put(*photos.toTypedArray())
    val snapshot = checkNotNull(library.snapshot.value)
    // L'ordine dell'album non e' quello della Libreria: il viewer lo tiene.
    val order = listOf(photos[2], photos[0], photos[3], photos[1]).map { it.id }
    val start = IndexOrderedTimelineView.of(snapshot, order)
    val items = AlbumViewerItems(start, library.snapshot, FakeViewerItems(start) { error("nessun riferimento nel test") }, scope)
    val seen = ArrayList<TimelineView>()
    val watcher = scope.launch { items.view.collect { seen += it } }

    library.hide(photos[0].id)

    val after = withTimeout(Attese.TETTO) { items.view.first { it.viewIndexOf(photos[0].id) < 0 } }
    assertThat(List(after.size) { after.snapshot.idAt(after.snapshotIndex(it)) }).containsExactly(photos[2].id, photos[3].id, photos[1].id).inOrder()
    watcher.cancel()
    assertThat(seen.first()).isSameInstanceAs(start)
  }

  @Test
  fun leMiniatureFuoriDallIndiceVeroFallisconoChiuse() {
    val refs = LibraryAlbumRefs { library.snapshot.value }
    library.put(shot(1))
    // L'istantanea finta non e' l'indice a colonne: nessuna sorgente, il segnaposto (mai una lettura del NAS).
    assertThat(refs.request(shot(1).id)).isNull()
    assertThat(refs.refAt(checkNotNull(library.snapshot.value), 0)).isNull()
    assertThat(LibraryAlbumRefs { null }.request(shot(1).id)).isNull()
  }
}
