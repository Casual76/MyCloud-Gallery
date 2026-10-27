package dev.pampa.mycloudgallery.albums

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.ShareDiary
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpRecord
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanHash
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareStateMachine
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.dao.ShareStateCountRow
import dev.pampa.mycloudgallery.core.database.entity.AlbumShareItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumShareOpEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Il diario della condivisione su Room (Fase 7, P13; L76): `album_share_op` e `album_share_item` di P1 (D34), campo per campo come scritto in
 * `docs/decisioni.md` ("Fase 7 P1", porte di P0). **Locale**: mai sincronizzato; lo toglie l'azzeramento (il database intero) a operazioni ferme.
 *
 * Ogni scrittura e' atomica: [create] scrive operazione ed elementi in una transazione, [update] controlla il passo con [ShareStateMachine]
 * (un passo saltato e' un errore di programmazione, come nel diario di riferimento) e lo salva. Una riga con un codice che questa versione non
 * conosce (scritta da un'app piu' nuova) non si legge: l'operazione resta ferma, non si inventa un passo.
 *
 * [now] e' l'ora del dispositivo, solo per l'ordine delle operazioni aperte (le piu' vecchie prima) e per la Diagnostica: non decide niente.
 */
class RoomShareDiary(private val database: GalleryDatabase, private val now: () -> Long) : ShareDiary {
  private val dao get() = database.shareDiaryDao()

  override suspend fun create(op: ShareOpRecord, items: List<ShareItemRecord>) {
    require(items.all { it.op == op.id }) { "Elementi di un'altra operazione." }
    val at = now()
    database.transaction {
      dao.insertOp(op.toEntity(at))
      if (items.isNotEmpty()) dao.insertItems(items.map { it.toEntity(at) })
    }
  }

  override suspend fun opByPlan(planHash: SharePlanHash): ShareOpRecord? = dao.opByPlan(planHash.hex)?.toRecord()

  override suspend fun openOps(): List<ShareOpRecord> = dao.openOps().mapNotNull { it.toRecord() }

  override suspend fun items(op: ShareOpId): List<ShareItemRecord> = dao.items(op.value).mapNotNull { it.toRecord() }

  override suspend fun update(item: ShareItemRecord) {
    database.transaction {
      val before = checkNotNull(dao.item(item.op.value, item.index)) { "Elemento sconosciuto." }
      val from = checkNotNull(enumByCodeOrNull<ShareItemState>(before.state)) { "Stato sconosciuto nel diario." }
      check(from == item.state || ShareStateMachine.allowed(item.action, from, item.state)) {
        "Passo non ammesso: ${from.code} -> ${item.state.code}"
      }
      dao.updateItem(item.toEntity(now()))
    }
  }

  override suspend fun finish(op: ShareOpId) {
    dao.finish(op.value, now())
  }

  /** Quanti elementi per stato (Diagnostica: solo numeri). */
  suspend fun countsByState(): List<ShareStateCountRow> = dao.countsByState()

  /**
   * I problemi della condivisione per la Diagnostica (§2.8, solo numeri): le attese degli elementi ancora aperti (spazio, scritture ferme, file in
   * uso), gli esiti per codice e le copie al sicuro chieste al guardiano.
   */
  suspend fun problemCounts(): ShareProblemCounts = withContext(Dispatchers.IO) {
    val db = database.openHelper.readableDatabase
    val waits = HashMap<String, Int>()
    db.query("SELECT wait, COUNT(*) FROM album_share_item WHERE wait IS NOT NULL AND state NOT IN ('done', 'failed', 'cancelled') GROUP BY wait").use { c ->
      while (c.moveToNext()) waits[c.getString(0)] = c.getInt(1)
    }
    val failures = HashMap<String, Int>()
    db.query("SELECT failure, COUNT(*) FROM album_share_item WHERE failure IS NOT NULL GROUP BY failure").use { c ->
      while (c.moveToNext()) failures[c.getString(0)] = c.getInt(1)
    }
    val safe = db.query("SELECT COUNT(*) FROM album_share_item WHERE safe_copy_requested = 1").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    ShareProblemCounts(
      waits = waits.mapNotNull { (code, n) -> enumByCodeOrNull<ShareWait>(code)?.let { it to n } }.toMap(),
      failures = failures.mapNotNull { (code, n) -> enumByCodeOrNull<ShareFailure>(code)?.let { it to n } }.toMap(),
      safeCopies = safe,
    )
  }

  // --- Traduzione campo per campo (nessuna logica) ------------------------------------------------------------------

  private fun ShareOpRecord.toEntity(at: Long) = AlbumShareOpEntity(
    opId = id.value,
    albumId = album.value,
    kind = kind.code,
    planHash = planHash.hex,
    title = title,
    level = level?.code,
    nonceHex = nonceHex,
    folderShare = folder?.share,
    folderPath = folder?.path?.value,
    hidden = hidden.code,
    finished = finished,
    createdAt = at,
    updatedAt = at,
  )

  private fun AlbumShareOpEntity.toRecord(): ShareOpRecord? = runCatching {
    ShareOpRecord(
      id = ShareOpId(opId),
      album = AlbumId(albumId),
      kind = enumByCodeOrNull<SharePlanKind>(kind) ?: return null,
      planHash = SharePlanHash(planHash),
      title = title,
      level = level?.let { enumByCodeOrNull<ShareLevel>(it) ?: return null },
      nonceHex = nonceHex,
      folder = refOf(folderShare, folderPath)?.let { ShareFolder(it.share, it.path) },
      hidden = enumByCodeOrNull<HiddenChoice>(hidden) ?: return null,
      finished = finished,
    )
  }.getOrNull()

  private fun ShareItemRecord.toEntity(at: Long) = AlbumShareItemEntity(
    opId = op.value,
    itemIndex = index,
    mediaId = media.value,
    action = action.code,
    state = state.code,
    mine = mine,
    size = size,
    contentKey = contentKey?.hex,
    sourceShare = source?.share,
    sourcePath = source?.path?.value,
    destShare = dest?.share,
    destPath = dest?.path?.value,
    transferKey = transferKey?.value,
    ticket = ticket?.value,
    stageSeq = stageSeq,
    safeCopyRequested = safeCopyRequested,
    failure = failure?.code,
    wait = wait?.code,
    updatedAt = at,
  )

  private fun AlbumShareItemEntity.toRecord(): ShareItemRecord? = runCatching {
    ShareItemRecord(
      op = ShareOpId(opId),
      index = itemIndex,
      media = MediaId(mediaId),
      action = enumByCodeOrNull<ShareItemAction>(action) ?: return null,
      state = enumByCodeOrNull<ShareItemState>(state) ?: return null,
      mine = mine,
      size = size,
      contentKey = contentKey?.let(::ContentKey),
      source = refOf(sourceShare, sourcePath),
      dest = refOf(destShare, destPath),
      transferKey = transferKey?.let(::TransferKey),
      ticket = ticket?.let(::TransferTicket),
      stageSeq = stageSeq,
      safeCopyRequested = safeCopyRequested,
      failure = failure?.let { enumByCodeOrNull<ShareFailure>(it) ?: ShareFailure.TRANSFER_FAILED },
      wait = wait?.let { enumByCodeOrNull<ShareWait>(it) },
    )
  }.getOrNull()

  private fun refOf(share: String?, path: String?): NasItemRef? =
    if (share != null && path != null) NasItemRef(share, SharePath.parse(path)) else null
}

/** I conteggi dei problemi della condivisione (Diagnostica): solo codici e numeri. */
class ShareProblemCounts(val waits: Map<ShareWait, Int>, val failures: Map<ShareFailure, Int>, val safeCopies: Int)
