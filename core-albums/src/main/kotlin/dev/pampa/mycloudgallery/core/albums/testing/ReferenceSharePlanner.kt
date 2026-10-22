package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanItem
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumFacts
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/** Il titolo e gli elementi mostrabili di un album personale (per "Condividi" su un album esistente). */
data class PersonalAlbumView(val title: String, val items: List<MediaId>)

/**
 * Il piano di riferimento (§2.5 passo 1), puro sopra le porte: nulla si scrive, nessuna porta che sposta si chiama. Lo usa
 * [InMemoryShareRunner] sia per `plan` sia per ricontrollare il piano allo `start` (stessa impronta o `PlanChanged`).
 */
class ReferenceSharePlanner(
  private val facts: CatalogFacts,
  private val shared: SharedAlbumFacts,
  private val allowed: (AlbumId, AlbumOp, MediaId?) -> Boolean,
  private val family: AlbumFamily,
  private val gate: AlbumWriteGate,
  private val personal: suspend (AlbumId) -> PersonalAlbumView? = { null },
) {

  private fun blockOf(block: AlbumWriteBlock): ShareBlock = when (block) {
    AlbumWriteBlock.TEST_SHARES_ONLY, AlbumWriteBlock.FENCE -> ShareBlock.TEST_SHARES_ONLY
    AlbumWriteBlock.READ_ONLY -> ShareBlock.READ_ONLY
    AlbumWriteBlock.NO_NAS -> ShareBlock.NO_NAS
    else -> ShareBlock.WRITES_BLOCKED
  }

  /** Il piano di [request]; [nonceHex] per rifare lo stesso piano di un album nuovo; [now] l'istante monotono. */
  suspend fun plan(request: ShareRequest, nonceHex: String?, now: Long, newNonce: () -> ByteArray): SharePlanOutcome {
    val me = family.me() ?: return SharePlanOutcome.Blocked(ShareBlock.NO_NAS)
    val common = family.commonShare() ?: return SharePlanOutcome.Blocked(ShareBlock.NO_NAS)
    gate.blockFor(common)?.let { return SharePlanOutcome.Blocked(blockOf(it)) }
    family.privateShare()?.let { share -> gate.blockFor(share)?.let { return SharePlanOutcome.Blocked(blockOf(it)) } }
    return when (request) {
      is ShareRequest.NewAlbum -> {
        AlbumTitles.problem(request.title)?.let { return SharePlanOutcome.InvalidTitle(it) }
        newAlbum(AlbumTitles.normalized(request.title), request.level, request.media, request.hidden, request, nonceHex, newNonce, now, me)
      }
      is ShareRequest.FromPersonal -> {
        val view = personal(request.album) ?: return SharePlanOutcome.Blocked(ShareBlock.ALBUM_NOT_ACTIVE)
        newAlbum(view.title, request.level, view.items, request.hidden, request, nonceHex, newNonce, now, me)
      }
      is ShareRequest.AddToShared -> existing(request.album, request.media, request.hidden, request, AlbumOp.ADD_MEDIA, now)
      is ShareRequest.FolderAdoption -> existing(request.album, request.media, HiddenChoice.SHARE_TOO, request, AlbumOp.ADOPT_FOLDER_FILES, now)
      is ShareRequest.BringBack -> bringBack(request, now)
    }
  }

  private suspend fun newAlbum(
    title: String,
    level: ShareLevel,
    media: List<MediaId>,
    hidden: HiddenChoice,
    request: ShareRequest,
    nonceHex: String?,
    newNonce: () -> ByteArray,
    now: Long,
    me: dev.pampa.mycloudgallery.core.model.AccountId,
  ): SharePlanOutcome {
    val nonce = nonceHex?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: newNonce()
    val album = AlbumId.derived(me, nonce)
    val hex = nonce.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return build(request, SharePlanKind.SHARE, album, title, folderLabel(title), level, hex, media, hidden, emptySet(), now)
  }

  private suspend fun existing(album: AlbumId, media: List<MediaId>, hidden: HiddenChoice, request: ShareRequest, op: AlbumOp, now: Long): SharePlanOutcome {
    if (shared.state(album) != SharedState.ACTIVE) return SharePlanOutcome.Blocked(ShareBlock.ALBUM_NOT_ACTIVE)
    if (!allowed(album, op, null)) return SharePlanOutcome.NotAllowed(op)
    val folder = shared.folder(album) ?: return SharePlanOutcome.Blocked(ShareBlock.ALBUM_NOT_ACTIVE)
    val kind = if (op == AlbumOp.ADOPT_FOLDER_FILES) SharePlanKind.ADOPT else SharePlanKind.SHARE
    return build(request, kind, album, folder.path.name, folder.path.value, null, null, media, hidden, shared.members(album).keys, now)
  }

  private suspend fun build(
    request: ShareRequest,
    kind: SharePlanKind,
    album: AlbumId,
    title: String,
    folderLabel: String,
    level: ShareLevel?,
    nonceHex: String?,
    media: List<MediaId>,
    hidden: HiddenChoice,
    members: Set<MediaId>,
    now: Long,
  ): SharePlanOutcome {
    val known = facts.facts(media)
    val excluded = LinkedHashMap<ShareExclusion, Int>()
    val items = ArrayList<SharePlanItem>()
    for (id in media.distinct()) {
      val f = known[id]
      val reason = when {
        f == null -> ShareExclusion.UNAVAILABLE
        id in members -> ShareExclusion.ALREADY_IN_ALBUM
        f.place == MediaPlace.OTHERS_PRIVATE -> ShareExclusion.OTHERS_AREA
        f.hidden && hidden == HiddenChoice.LEAVE_OUT -> ShareExclusion.HIDDEN_LEFT_OUT
        kind == SharePlanKind.ADOPT && f.place != MediaPlace.COMMON -> ShareExclusion.NOT_IN_COMMON
        else -> null
      }
      if (reason != null) {
        excluded.merge(reason, 1, Int::plus)
        continue
      }
      val action = when {
        kind == SharePlanKind.ADOPT -> ShareItemAction.ADOPT
        f!!.place == MediaPlace.PHONE_ONLY -> ShareItemAction.UPLOAD
        f.place == MediaPlace.MY_PRIVATE -> ShareItemAction.MOVE_IN
        else -> ShareItemAction.REFERENCE
      }
      items += SharePlanItem(id, action, f!!.size, f.isVideo, f.hidden)
    }
    if (items.size + members.size > SharedAlbumLimits.MAX_ITEMS) return SharePlanOutcome.Blocked(ShareBlock.TOO_MANY_ITEMS)
    if (items.isEmpty()) return SharePlanOutcome.NothingToShare(excluded)
    return SharePlanOutcome.Ready(SharePlan(request, kind, album, title, folderLabel, level, nonceHex, items, excluded, hidden, now))
  }

  private suspend fun bringBack(request: ShareRequest.BringBack, now: Long): SharePlanOutcome {
    val state = shared.state(request.album) ?: return SharePlanOutcome.Blocked(ShareBlock.ALBUM_NOT_ACTIVE)
    val known = facts.facts(request.media)
    val excluded = LinkedHashMap<ShareExclusion, Int>()
    val items = ArrayList<SharePlanItem>()
    for (id in request.media.distinct()) {
      val f = known[id]
      val reason = when {
        f == null -> ShareExclusion.UNAVAILABLE
        !f.mine -> ShareExclusion.NOT_MINE
        f.place != MediaPlace.COMMON -> ShareExclusion.NOT_IN_COMMON
        state == SharedState.ACTIVE && !allowed(request.album, AlbumOp.BRING_BACK, id) -> ShareExclusion.NOT_MINE
        else -> null
      }
      if (reason != null) excluded.merge(reason, 1, Int::plus) else items += SharePlanItem(id, ShareItemAction.MOVE_OUT, f!!.size, f.isVideo, f.hidden)
    }
    if (items.isEmpty()) return SharePlanOutcome.NothingToShare(excluded)
    val label = shared.folder(request.album)?.path?.name ?: ""
    return SharePlanOutcome.Ready(SharePlan(request, SharePlanKind.BRING_BACK, request.album, label, label, null, null, items, excluded, HiddenChoice.SHARE_TOO, now))
  }

  companion object {
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    /** Il nome della cartella dell'album (E-SH22): il titolo con i caratteri vietati sostituiti; il titolo resta quello scelto. */
    fun folderName(title: String): String {
      val cleaned = title.replace(FORBIDDEN, "_").trim().trimEnd('.', ' ').take(AlbumTitles.MAX_CHARS)
      return if (cleaned.isNotEmpty() && SharePath.isValidComponent(cleaned)) cleaned else "Album"
    }

    fun folderLabel(title: String): String = "${SharedAlbumLimits.FOLDER_ROOT}/${folderName(title)}"
  }
}
