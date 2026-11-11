package dev.pampa.mycloudgallery.albums

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.AlbumList
import dev.pampa.mycloudgallery.core.albums.contract.Album
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryAlbums
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * H2, giro di correzione 1: la porta vera degli album di un media per il pannello informazioni. In quali album sta il media lo dicono gli
 * elementi del catalogo; nomi e ordine vengono dall'elenco degli album; un album che l'elenco non mostra non compare; un errore di lettura
 * non mostra niente; il tocco apre l'album con la sua identita'.
 */
class AppMediaAlbumsTest {

  private val albums = InMemoryAlbums(FakeAlbumLibrary(), FakeAlbumFamily(AccountId("test")), FakeAlbumClocks())
  private val media = MediaId.random()

  private suspend fun manual(title: String): AlbumId = (albums.create(title, AlbumKind.MANUAL) as CreateOutcome.Created).id

  @Test
  fun gliAlbumDelMediaConINomiDellElenco(): Unit = runBlocking {
    val vacanze = manual("Vacanze 2024")
    manual("Lavoro")
    val famiglia = manual("Famiglia")
    val gone = AlbumId.random().value
    val port = AppMediaAlbums({ albums }, itemsOf = { setOf(vacanze.value, famiglia.value, gone) }, opener = AlbumOpener())
    val titles = port.albumsOf(media).first().map { it.title }
    // L'ordine e' quello dell'elenco degli album; un'identita' che l'elenco non ha (un album tolto) non compare.
    assertThat(titles).containsExactlyElementsIn(albums.all.value.albums.filter { it.id == vacanze || it.id == famiglia }.map { it.title }).inOrder()
    assertThat(titles).containsExactly("Vacanze 2024", "Famiglia")
  }

  @Test
  fun inNessunAlbumOConUnErroreNienteSezione(): Unit = runBlocking {
    manual("Vacanze 2024")
    assertThat(AppMediaAlbums({ albums }, itemsOf = { emptySet() }, opener = AlbumOpener()).albumsOf(media).first()).isEmpty()
    assertThat(AppMediaAlbums({ albums }, itemsOf = { error("catalogo chiuso") }, opener = AlbumOpener()).albumsOf(media).first()).isEmpty()
  }

  @Test
  fun ilToccoApreLAlbum(): Unit = runBlocking {
    val vacanze = manual("Vacanze 2024")
    val opened = mutableListOf<AlbumId>()
    val opener = AlbumOpener().apply { onOpen = { opened += it } }
    val port = AppMediaAlbums({ albums }, itemsOf = { setOf(vacanze.value) }, opener = opener)
    port.open(port.albumsOf(media).first().single())
    assertThat(opened).containsExactly(vacanze)
  }

  @Test
  fun unAlbumCondivisoDellaFamigliaCompareConIlSuoSegno(): Unit = runBlocking {
    // Giro 2: il media sta in un album condiviso (fra i suoi elementi visibili) e in uno personale; un altro condiviso non lo contiene.
    val vacanze = manual("Vacanze 2024")
    val famiglia = shared("Famiglia")
    val lavoro = shared("Lavoro di gruppo")
    val list = MutableStateFlow(AlbumList(albums.all.value.albums + famiglia + lavoro, albums.all.value.sort))
    val withShared = object : Albums by albums {
      override val all: StateFlow<AlbumList> = list
    }
    val port = AppMediaAlbums(
      albums = { withShared },
      itemsOf = { setOf(vacanze.value) },
      opener = AlbumOpener(),
      sharedItemsOf = { id -> if (id == famiglia.id) listOf(MediaId.random(), media) else listOf(MediaId.random()) },
    )
    val entries = port.albumsOf(media).first()
    assertThat(entries.map { it.title to it.shared }).containsExactly("Vacanze 2024" to false, "Famiglia" to true).inOrder()
    // Senza album personali: il condiviso basta perche' la sezione ci sia; un errore di lettura su un condiviso lo lascia fuori e basta.
    val onlyShared = AppMediaAlbums({ withShared }, itemsOf = { emptySet() }, opener = AlbumOpener(), sharedItemsOf = { id ->
      if (id == lavoro.id) error("replica chiusa") else listOf(media)
    })
    assertThat(onlyShared.albumsOf(media).first().map { it.title }).containsExactly("Famiglia")
  }

  private fun shared(title: String): Album.Shared = Album.Shared(
    id = AlbumId.random(), title = title, kind = AlbumKind.MANUAL, cover = CoverRef.None, count = 3, sort = AlbumSort.defaultFor(AlbumKind.MANUAL),
    updatedAtNas = 0L, newestDay = null, level = ShareLevel.ALL, creator = AccountId("altro"), heir = null, mine = false, state = SharedState.ACTIVE,
  )

  @Test
  fun lAlbumGiaInCimaNonSiApreDiNuovo(): Unit = runBlocking {
    // CS-22: il viewer aperto dalla pagina di "Vacanze": la sua capsula non apre una seconda copia della pagina; un altro album si apre.
    val vacanze = manual("Vacanze 2024")
    val famiglia = manual("Famiglia")
    val opened = mutableListOf<AlbumId>()
    val opener = AlbumOpener().apply {
      onOpen = { opened += it }
      onTop = { vacanze }
    }
    val port = AppMediaAlbums({ albums }, itemsOf = { setOf(vacanze.value, famiglia.value) }, opener = opener)
    val entries = port.albumsOf(media).first()
    port.open(entries.single { it.key == vacanze.value })
    assertThat(opened).isEmpty()
    port.open(entries.single { it.key == famiglia.value })
    assertThat(opened).containsExactly(famiglia)
  }
}
