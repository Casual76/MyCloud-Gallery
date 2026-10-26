package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.PermissionCondition
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * L'area comune finta degli album condivisi (Fase 7, P0): lo stato **fuso** che ogni familiare legge (§2.5 "Ricevere"), con le regole di
 * chi legge applicate da [apply]: id legato al creatore (D7, E-SH14), permessi con la stessa tabella di chi scrive (D10, C25), stati
 * `closed`/`deleted` definitivi (D31), eredita' solo con il creatore uscito (D11). Il registro firmato finto e' [admins] e [gone]. Non e'
 * il codec (P2): e' cio' che il codec e la fusione devono produrre, per provare chi sta sopra (P4, P10, P11, P13).
 */
class FakeSharedWorld(val commonShare: String = "test-comune") {

  data class WorldItem(val media: MediaId, val contentKey: ContentKey, val path: SharePath, val position: Long, val addedBy: AccountId) {
    override fun toString(): String = "WorldItem"
  }

  class AlbumState(
    val id: AlbumId,
    val creator: AccountId,
    val nonceHex: String,
    var title: String,
    var folder: SharePath,
    var state: SharedState,
    var afterClose: OthersAfterClose?,
    val levels: MutableList<ShareLevel>,
    var heir: AccountId? = null,
    val items: LinkedHashMap<MediaId, WorldItem> = LinkedHashMap(),
    var cover: MediaId? = null,
    var sort: AlbumSort = AlbumSort.NEWEST_FIRST,
    var updatedAt: Long = 0,
  ) {
    val level: ShareLevel get() = levels.last()

    override fun toString(): String = "AlbumState(${state.code}, ${items.size})"
  }

  private val albums = LinkedHashMap<AlbumId, AlbumState>()
  private val contributors = HashMap<AlbumId, MutableSet<AccountId>>()
  private val listeners = mutableListOf<() -> Unit>()

  /** Il registro firmato finto: chi e' admin, chi non ha piu' dispositivi approvati ne' dismessi (D11). */
  val admins: MutableSet<AccountId> = mutableSetOf()
  val gone: MutableSet<AccountId> = mutableSetOf()

  /** Gli account della famiglia (per "quanti familiari lo vedono"). */
  val accounts: MutableSet<AccountId> = mutableSetOf()

  /** Gli album lasciati, per account (voce personale `shared_album_left`, E-SH21). */
  val left: MutableMap<AccountId, MutableSet<AlbumId>> = mutableMapOf()

  /** Voci scartate per permesso (`ALBUM_ENTRY_DENIED`) e contributi rifiutati (`ALBUM_CONTRIB_REJECTED`). */
  var denied: Int = 0
    private set
  var rejectedContributions: Int = 0
    private set

  private var clock = 0L

  fun addListener(listener: () -> Unit) {
    listeners += listener
  }

  fun albumIds(): Set<AlbumId> = albums.keys.toSet()

  fun album(id: AlbumId): AlbumState? = albums[id]

  fun contributions(): Int = contributors.values.sumOf { it.size }

  fun leftBy(account: AccountId): MutableSet<AlbumId> = left.getOrPut(account) { mutableSetOf() }

  /** Avvisa chi guarda (una voce personale come "lasciato" non passa da [apply]). */
  fun touch() = listeners.toList().forEach { it() }

  /**
   * Applica le voci di [author] (il suo account, `nasUser` del firmatario) come le applica chi legge: ogni voce non permessa si scarta,
   * le altre valgono (E-SH14). Restituisce quante voci sono entrate.
   */
  fun apply(album: AlbumId, author: AccountId, changes: List<SharedAlbumChange>): Int {
    accounts += author
    var applied = 0
    for (change in changes) if (applyOne(album, author, change)) applied++ else denied++
    if (applied > 0) contributors.getOrPut(album) { mutableSetOf() } += author
    touch()
    return applied
  }

  private fun allowed(state: AlbumState, author: AccountId, op: AlbumOp, addedBy: AccountId?): Boolean {
    val roles = AlbumPermissions.rolesOf(author, state.creator, state.heir, addedBy, author in admins)
    if (!AlbumPermissions.admits(op, state.level, roles)) return false
    return when (AlbumPermissions.cell(op, state.level).condition) {
      PermissionCondition.NONE, PermissionCondition.ADDED_WHILE_PERMITTED -> true
      PermissionCondition.CREATOR_LEFT_FAMILY -> state.creator in gone
    }
  }

  private fun applyOne(album: AlbumId, author: AccountId, change: SharedAlbumChange): Boolean {
    clock++
    val state = albums[album]
    if (change is SharedAlbumChange.Definition) return applyDefinition(album, author, change, state)
    if (state == null || state.state != SharedState.ACTIVE) return false
    val ok = when (change) {
      is SharedAlbumChange.Level -> allowed(state, author, AlbumOp.CHANGE_LEVEL, null) && state.levels.size < SharedAlbumLimits.MAX_LEVEL_CHANGES &&
        state.levels.add(change.level)
      is SharedAlbumChange.Item -> {
        // E-SH12: una voce sola vince (la prima resta: stesso file, stesso elemento).
        val existing = state.items[change.media]
        if (change.addedBy != author || !allowed(state, author, AlbumOp.ADD_MEDIA, null)) {
          false
        } else {
          if (existing == null) state.items[change.media] = WorldItem(change.media, change.contentKey, change.path, change.position, change.addedBy)
          true
        }
      }
      is SharedAlbumChange.ItemRemoved -> {
        // La lapide di chi l'ha aggiunto vale anche come "Riporta" (§2.4: a "Gli altri guardano" solo cosi'), come per chi legge (P2).
        val existing = state.items[change.media]
        existing != null &&
          (allowed(state, author, AlbumOp.REMOVE_ITEM, existing.addedBy) || (author == existing.addedBy && allowed(state, author, AlbumOp.BRING_BACK, existing.addedBy))) &&
          state.items.remove(change.media) != null
      }
      is SharedAlbumChange.Cover -> allowed(state, author, AlbumOp.SET_COVER, null).also { if (it) state.cover = change.media }
      is SharedAlbumChange.Sort -> allowed(state, author, AlbumOp.SET_SORT, null).also { if (it) state.sort = change.sort }
      is SharedAlbumChange.Heir -> (change.account == author && allowed(state, author, AlbumOp.INHERIT, null)).also { if (it) state.heir = author }
      is SharedAlbumChange.LevelHistory -> allowed(state, author, AlbumOp.CHANGE_LEVEL, null).also { ok ->
        if (ok) {
          state.levels.clear()
          state.levels.addAll(change.steps.map { it.level })
        }
      }
      is SharedAlbumChange.Definition -> false
    }
    if (ok) state.updatedAt = clock
    return ok
  }

  private fun applyDefinition(album: AlbumId, author: AccountId, change: SharedAlbumChange.Definition, state: AlbumState?): Boolean {
    if (state == null) {
      // D7, E-SH14: l'id deve tornare con account e nonce del creatore, e il creatore e' chi scrive.
      val nonce = change.nonceHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
      if (change.creator != author || AlbumId.derived(author, nonce) != album || change.state != SharedState.ACTIVE) {
        rejectedContributions++
        return false
      }
      albums[album] = AlbumState(album, author, change.nonceHex, change.title, change.folder, SharedState.ACTIVE, null, mutableListOf(ShareLevel.DEFAULT), updatedAt = clock)
      return true
    }
    // D31: chiuso o eliminato e' definitivo.
    if (state.state != SharedState.ACTIVE) return false
    if (change.creator != state.creator) return false
    var ok = true
    if (change.title != state.title || change.folder != state.folder) {
      ok = allowed(state, author, AlbumOp.RENAME, null)
      if (ok) {
        state.title = change.title
        state.folder = change.folder
      }
    }
    if (ok && change.state != SharedState.ACTIVE) {
      val op = if (change.state == SharedState.CLOSED) AlbumOp.STOP_SHARING else AlbumOp.DELETE_ALBUM
      ok = allowed(state, author, op, null)
      if (ok) {
        state.state = change.state
        state.afterClose = change.afterClose
      }
    }
    if (ok) state.updatedAt = clock
    return ok
  }
}
