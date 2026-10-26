package dev.pampa.mycloudgallery.feature.albums

import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumContent
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.testing.SmartMembership
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumBanner
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumListState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPeople
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPolicy
import dev.pampa.mycloudgallery.feature.albums.contract.PermissionInfo
import dev.pampa.mycloudgallery.feature.albums.contract.SuggestionsState
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListUi
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageUi
import dev.pampa.mycloudgallery.feature.albums.page.ItemMenu
import dev.pampa.mycloudgallery.feature.albums.contract.ItemActions
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * I dati delle scene degli screenshot e dei test Compose (Fase 7, P10): una libreria di foto sintetiche e gli album veri di `InMemoryAlbums` sopra
 * l'area comune finta, con i familiari "Marco" e "Chiara". Gli stati delle schermate si costruiscono dagli album veri (le viste, i conti, le copertine
 * le fa il riferimento), non a mano: cosi' lo screenshot mostra quello che il controllore darebbe.
 */
class Scenes {
  val world = AlbumsTestWorld()
  val thumbnails = TestAlbumThumbnails()
  val marco = AccountId("test2")
  val chiara = AccountId("test3")

  val people = object : AlbumPeople {
    override fun nameOf(account: AccountId): String = when (account) {
      marco -> "Marco"
      chiara -> "Chiara"
      else -> "Tu"
    }
  }

  /** Gli album della scena. */
  private class Built(
    val birthday: AlbumId,
    val lake: AlbumId,
    val empty: AlbumId,
    val oslo: AlbumId,
    val osloShared: AlbumId,
    val christmas: AlbumId,
    val grandpa: AlbumId,
    val cabin: AlbumId,
  )

  /** Le foto del compleanno (le prime 42 della libreria). */
  val birthdayPhotos: List<MediaId> = (1..42).map { AlbumsTestWorld.media(it) }

  private val built: Built = runBlocking { build() }

  val birthday: AlbumId get() = built.birthday
  val lake: AlbumId get() = built.lake
  val empty: AlbumId get() = built.empty
  val oslo: AlbumId get() = built.oslo
  val osloShared: AlbumId get() = built.osloShared
  val christmas: AlbumId get() = built.christmas
  val grandpa: AlbumId get() = built.grandpa

  /** Un album condiviso mio piccolo (4 foto): lo sfondo dei fogli negli screenshot, cosi' le immagini restano leggere. */
  val cabin: AlbumId get() = built.cabin

  private suspend fun build(): Built {
    // 120 foto private, una ogni 5 ore, cosi' i giorni cambiano nella parete.
    world.library.put(*(1..120).map { world.photo(it, hoursFromBase = it * 5) }.toTypedArray())
    world.smart = SmartMembership(
      confident = (60..100).map { AlbumsTestWorld.media(it) },
      suggested = (101..112).map { AlbumsTestWorld.media(it) },
    )
    val birthday = (world.albums.create("Compleanno di Anna", AlbumKind.MANUAL) as CreateOutcome.Created).id
    world.albums.add(birthday, birthdayPhotos)
    val lake = (world.albums.create("Gita al lago", AlbumKind.MANUAL) as CreateOutcome.Created).id
    world.albums.add(lake, (43..48).map { AlbumsTestWorld.media(it) })
    val empty = (world.albums.create("Da riempire", AlbumKind.MANUAL) as CreateOutcome.Created).id
    val rules = SmartRules(SmartPeriod.Ranges(listOf(LocalDayRange(LocalDate.of(2026, 1, 8), LocalDate.of(2026, 1, 14)))), null, null)
    val oslo = (world.albums.create("Oslo 2026", AlbumKind.SMART, rules) as CreateOutcome.Created).id

    val osloShared = world.albumBy(world.me, "Vacanza a Oslo", ShareLevel.ADD)
    world.addToShared(osloShared, world.me, *(300..317).map { world.commonPhoto(it, hoursFromBase = it * 3, mine = true) }.toTypedArray())
    world.addToShared(osloShared, marco, *(320..325).map { world.commonPhoto(it, hoursFromBase = it * 3, mine = false) }.toTypedArray())
    val christmas = world.albumBy(marco, "Natale in famiglia", ShareLevel.ADD)
    world.addToShared(christmas, marco, *(400..423).map { world.commonPhoto(it, hoursFromBase = it * 2, mine = false) }.toTypedArray())
    val grandpa = world.albumBy(chiara, "Le foto del nonno", ShareLevel.VIEW)
    world.addToShared(grandpa, chiara, *(500..511).map { world.commonPhoto(it, hoursFromBase = it * 2, mine = false) }.toTypedArray())
    val cabin = world.albumBy(world.me, "Weekend in baita", ShareLevel.ADD)
    world.addToShared(cabin, world.me, *(600..603).map { world.commonPhoto(it, hoursFromBase = it * 3, mine = true) }.toTypedArray())
    return Built(birthday, lake, empty, oslo, osloShared, christmas, grandpa, cabin)
  }

  fun content(id: AlbumId): AlbumContent = runBlocking { world.albums.content(id).first()!! }

  fun album(id: AlbumId): Album = world.albums.all.value.byId(id)!!

  /** L'elenco com'e' adesso nel mondo. */
  fun listReady(sort: AlbumListSort = AlbumListSort.RECENT, notices: List<dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumNotice> = emptyList()): AlbumListState.Ready {
    val list = world.albums.all.value
    return AlbumListState.Ready(list.manualAndShared, list.smart, sort, notices)
  }

  fun listUi(state: AlbumListState = listReady(), left: List<Album.Shared> = emptyList(), reorderable: Boolean = false) = AlbumListUi(state, left, reorderable)

  /** La pagina di [id] come la darebbe il controllore: contenuto vero, permessi dalla tabella, azioni dalla politica. */
  fun pageUi(
    id: AlbumId,
    banners: List<AlbumBanner> = emptyList(),
    suggested: Boolean = false,
    toShare: Boolean = false,
    pickingCover: Boolean = false,
    itemMenu: ItemMenu? = null,
  ): AlbumPageUi {
    val content = content(id)
    val album = content.album
    val shared = album as? Album.Shared
    val ops = if (shared != null) world.sharedAlbums.allowedOps(id) else emptySet()
    val permissions = shared?.let { PermissionInfo(it.level, it.creator, it.mine, ops) }
    val suggestedView = content.suggested
    val toShareView = content.toShare
    return AlbumPageUi(
      page = AlbumPageState.Ready(content, album.sort, permissions, banners),
      actions = AlbumPolicy.pageActions(album, ops),
      suggested = if (suggested && suggestedView != null) SuggestionsState.Some(suggestedView.size, suggestedView) else SuggestionsState.None,
      toShare = if (toShare && toShareView != null) SuggestionsState.ToShare(toShareView.size, toShareView) else SuggestionsState.None,
      creatorName = shared?.takeIf { !it.mine }?.let { people.nameOf(it.creator) },
      pickingCover = pickingCover,
      itemMenu = itemMenu,
    )
  }

  /** Il menu di un elemento della pagina di [id], con le azioni che la politica da' in quell'album. */
  fun itemMenu(id: AlbumId, media: MediaId, addedBy: AccountId? = null): ItemMenu {
    val album = album(id)
    val shared = album as? Album.Shared
    val ops = if (shared != null) world.sharedAlbums.allowedOps(id, media) else emptySet()
    val actions: ItemActions = AlbumPolicy.itemActions(album, if (shared != null) world.sharedAlbums.allowedOps(id) else emptySet(), ops)
    return ItemMenu(media, actions, addedBy, isCover = false, removeIsExclude = album is Album.Smart)
  }
}
