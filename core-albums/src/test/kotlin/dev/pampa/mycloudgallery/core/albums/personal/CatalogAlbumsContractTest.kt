package dev.pampa.mycloudgallery.core.albums.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.AlbumsContract
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ReferenceWorld
import dev.pampa.mycloudgallery.core.albums.contract.SmartChoices
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumSuggestionEntity
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IL VERO `Albums` PER GLI ALBUM PERSONALI PASSA LA SUITE DI CONTRATTO DI P0 (`AlbumsContract`, riga P3 del §4.4): le stesse dieci prove
 * dell'esecutore di riferimento, ma sopra i dati personali veri (store con il suo registro), la proiezione sulle tabelle v6 e il catalogo vero
 * (Room in memoria). La Libreria finta della suite pilota il catalogo: ogni media che mette, nasconde o toglie viene scritto anche nel
 * database con lo stesso id ([RoomMirror]), come farebbe una scansione. I membri degli smart li decide un motore finto ([TestSmartEngine],
 * il motore vero e' di P6) che scrive le righe `auto` e i suggeriti.
 */
@RunWith(AndroidJUnit4::class)
class CatalogAlbumsContractTest : AlbumsContract() {

  private val worlds = ArrayList<RealWorld>()

  override fun world(): AlbumsContract.World = RealWorld().also { worlds += it }

  @After
  fun closeWorlds() {
    worlds.forEach { it.close() }
  }

  private class RealWorld : AlbumsContract.World {
    val ref = ReferenceWorld()
    val room = AlbumsRoom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = TestSmartEngine(room)
    private lateinit var real: CatalogAlbums
    private val mirror = RoomMirror(ref.library, room) { runBlocking { engine.run(); real.onCatalogChanged() } }
    private val store = room.store(nasNow = { ref.clocks.advance(1); ref.clocks.nasNowMs() })

    init {
      mirror.sync()
      ref.library.addListener { mirror.changed() }
      real = CatalogAlbums(
        store = store,
        catalog = room.catalog,
        database = room.db,
        visible = ref.library,
        mask = ref.library,
        family = ref.family,
        scope = scope,
        shared = ref.sharedAlbums,
        runner = ref.runner(),
        newNonce = { ref.nextNonce() },
      )
    }

    /** Dopo ogni modifica un motore vero rivaluta gli smart (lo fanno i lavori di :app): qui subito, perche' la suite vuole vedere l'effetto. */
    private val hooked = object : Albums by real, SmartChoices by real {
      override suspend fun create(title: String, kind: AlbumKind, rules: SmartRules?): CreateOutcome = real.create(title, kind, rules).also { settle() }

      override suspend fun add(id: AlbumId, media: List<MediaId>): AddOutcome = real.add(id, media).also { settle() }

      override suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome = real.remove(id, media).also { settle() }

      override suspend fun exclude(id: AlbumId, media: List<MediaId>): EditOutcome = real.exclude(id, media).also { settle() }

      override suspend fun accept(id: AlbumId, media: List<MediaId>): EditOutcome = real.accept(id, media).also { settle() }

      override suspend fun freeze(id: AlbumId): EditOutcome = real.freeze(id).also { settle() }

      override suspend fun unfreeze(id: AlbumId): EditOutcome = real.unfreeze(id).also { settle() }
    }

    private suspend fun settle() {
      engine.run()
      real.onCatalogChanged()
    }

    override val albums: Albums get() = hooked

    override val library: FakeAlbumLibrary get() = ref.library

    override val choices: SmartChoices get() = hooked

    override fun setSmart(confident: List<MediaId>, suggested: List<MediaId>) {
      engine.confident = confident
      engine.suggested = suggested
      runBlocking { settle() }
    }

    override fun sharedAlbum(): AlbumId = ref.albumBy(AccountId("test2"))

    fun close() {
      scope.cancel()
      room.close()
    }
  }
}

/** La Libreria finta della suite e il catalogo vero: ogni cambiamento della prima si scrive nel secondo, con gli stessi id. */
class RoomMirror(private val library: FakeAlbumLibrary, private val room: AlbumsRoom, private val afterChange: () -> Unit) {
  private val hidden = HashMap<MediaId, Boolean>()

  fun changed() {
    sync()
    afterChange()
  }

  fun sync() {
    for (media in library.all()) {
      val known = hidden[media.id]
      if (known == null) {
        val lsb = UUID.fromString(media.id.value).leastSignificantBits
        val day = media.day.let { it.year * 10_000 + it.monthValue * 100 + it.dayOfMonth }
        room.insertRaw(media.id, 1_000 + lsb, "%064x".format(lsb), media.takenAtUtcMs, day, media.hidden)
      } else if (known != media.hidden) {
        room.setHidden(media.id, media.hidden)
      }
      hidden[media.id] = media.hidden
    }
  }
}

/** Il motore degli smart per la suite: scrive le righe `auto` e i suggeriti che il test ha detto (le regole non si valutano, D14 e' di P6). */
class TestSmartEngine(private val room: AlbumsRoom) {
  var confident: List<MediaId> = emptyList()
  var suggested: List<MediaId> = emptyList()

  suspend fun run() {
    val albums = room.db.albumDao()
    val choices = room.db.albumChoiceDao()
    for (row in albums.all().filter { it.state == null && it.kind == "smart" && !it.frozen }) {
      val excluded = choices.exclusions(row.albumId).map { it.mediaId }.toSet()
      val owned = albums.items(row.albumId).filter { it.origin != "auto" }.map { it.mediaId }.toSet()
      val auto = confident.filter { room.catalog.resolve(it) != null && it.value !in excluded && it.value !in owned }
      albums.clearItemsByOrigin(row.albumId, "auto")
      albums.upsertItems(auto.mapIndexed { i, m -> AlbumItemEntity(row.albumId, m.value, i.toLong(), 0, origin = "auto") })
      choices.clearSuggestions(row.albumId)
      val doubtful = suggested.filter { room.catalog.resolve(it) != null && it.value !in excluded && it.value !in owned && it !in auto }
      choices.upsertSuggestions(doubtful.map { AlbumSuggestionEntity(row.albumId, it.value, "s1", 0) })
    }
  }
}

/**
 * D22: il filtro finale dei nascosti. Anche se la fonte dei candidati ([dev.pampa.mycloudgallery.core.library.contract.VisibleMedia]) non sa ancora
 * che un media e' stato nascosto, `HiddenMask` lo ferma subito prima di mostrare: ne' fra gli elementi ne' come copertina.
 */
@RunWith(AndroidJUnit4::class)
class CatalogAlbumsHiddenMaskTest {

  private val ref = ReferenceWorld()
  private val room = AlbumsRoom()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val lateHidden = HashSet<MediaId>()
  private val mask = HiddenMask { ids -> ref.library.hiddenAmong(ids) + ids.filter { it in lateHidden } }
  private val mirror = RoomMirror(ref.library, room) {}
  private val albums = run {
    mirror.sync()
    ref.library.addListener { mirror.sync() }
    CatalogAlbums(
      store = room.store(nasNow = { ref.clocks.advance(1); ref.clocks.nasNowMs() }),
      catalog = room.catalog,
      database = room.db,
      visible = ref.library,
      mask = mask,
      family = ref.family,
      scope = scope,
    )
  }

  @After
  fun close() {
    scope.cancel()
    room.close()
  }

  @Test
  fun unNascostoCheLaFonteNonSaAncoraNonEsceNeComeElementoNeComeCopertina() = runBlocking<Unit> {
    ref.library.put(FakeMedia(FakeAlbumLibrary.id(1), 1_000_000L), FakeMedia(FakeAlbumLibrary.id(2), 2_000_000L), FakeMedia(FakeAlbumLibrary.id(3), 3_000_000L))
    val id = (albums.create("Gita", AlbumKind.MANUAL, null) as CreateOutcome.Created).id
    albums.add(id, listOf(FakeAlbumLibrary.id(1), FakeAlbumLibrary.id(2), FakeAlbumLibrary.id(3)))
    albums.setCover(id, FakeAlbumLibrary.id(3))
    assertThat(albums.all.value.byId(id)!!.cover).isEqualTo(CoverRef.Chosen(FakeAlbumLibrary.id(3)))
    val calls = ref.library.maskCalls

    // Il 3 e' appena stato nascosto: la fonte (e il database) non lo sanno ancora, il filtro finale si'.
    lateHidden += FakeAlbumLibrary.id(3)
    albums.onCatalogChanged()

    val content = albums.content(id).first { it != null }!!
    val shown = (0 until content.items.size).map { content.items.snapshot.idAt(content.items.snapshotIndex(it)) }
    assertThat(shown).doesNotContain(FakeAlbumLibrary.id(3))
    assertThat(albums.all.value.byId(id)!!.cover.media).isNotEqualTo(FakeAlbumLibrary.id(3))
    assertThat(albums.all.value.byId(id)!!.cover).isInstanceOf(CoverRef.Auto::class.java)
    assertThat(ref.library.maskCalls).isGreaterThan(calls)
  }
}
