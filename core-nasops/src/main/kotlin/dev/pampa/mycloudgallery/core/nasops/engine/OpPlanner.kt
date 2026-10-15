package dev.pampa.mycloudgallery.core.nasops.engine

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.NameRules
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpRules
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpStateMachine
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PlanSummary
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.PlannedItem
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.contract.SkippedItem
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale

/**
 * La pianificazione (§2.7, D24): da una richiesta dell'utente al piano che il foglio di conferma mostra. **Non scrive niente**, ne'
 * sul NAS ne' nel diario: legge solo cio' che serve a decidere (esistenza, spazio, il contenuto di una cartella da spostare tra share).
 *
 * I nomi letti dal NAS (il contenuto di una cartella spostata tra share) diventano elementi del piano solo come figli della cartella
 * scelta dall'utente (`SharePath.child`, E-N6: un nome non valido si salta) e solo dopo la conferma del piano intero; i collegamenti
 * (punti di rianalisi, E-N10) non si seguono.
 */
internal class OpPlanner(
  private val deviceId: String,
  private val accountId: String,
  private val fileOps: NasFileOps,
  private val reader: NasMediaReader,
  private val store: OpsStore,
  private val gate: OpsGate,
  private val access: ShareAccessView,
  private val newId: () -> String,
) {
  private val purpose = Purpose.INTERACTIVE

  /** R2: il contenuto di una cartella da eliminare si conta qui (alla pianificazione basta contare: l'inventario lo scrive l'esecuzione). */
  private val inventory = FolderInventory(reader, CatalogPresence.NONE)

  suspend fun plan(request: OpRequest): PlanOutcome = try {
    when (request) {
      is OpRequest.CreateFolder -> createFolder(request)
      is OpRequest.Rename -> rename(request)
      is OpRequest.Move -> move(request)
      is OpRequest.Trash -> trash(request)
      is OpRequest.Restore -> restore(request)
      is OpRequest.PurgeNow -> purgeNow(request)
    }
  } catch (r: Rejected) {
    PlanOutcome.Rejected(r.reason)
  }

  /** Uscita anticipata con un motivo. */
  private class Rejected(val reason: PlanRejection) : RuntimeException(null, null, false, false)

  private fun reject(reason: PlanRejection): Nothing = throw Rejected(reason)

  private fun blocked(share: String): Boolean = gate.statusOf(share).blocksUserOps

  private fun requireMedia(path: SharePath) {
    if (!NasOpsPaths.isMediaPath(path)) reject(PlanRejection.PATH_NOT_ALLOWED)
  }

  /** Lo stato di [path], o null se non c'e'. Un NAS che non risponde rifiuta il piano (si riprova dal foglio). */
  private suspend fun stat(share: String, path: SharePath): NasEntry? = when (val r = reader.stat(share, path, purpose)) {
    is NasResult.Ok -> r.value
    is NasResult.Err -> when (r.error) {
      NasError.NotFound -> null
      NasError.PermissionDenied -> reject(PlanRejection.READ_ONLY)
      else -> reject(PlanRejection.NAS_UNREACHABLE)
    }
  }

  /**
   * Il timbro di un elemento scelto dall'utente: per un file quello che l'utente ha visto (se il file non e' piu' quello, l'esecuzione
   * si ferma: E-D13, E-N11); per una cartella l'identita' (`fileId`) letta adesso, per riconoscerla dopo una risposta persa.
   */
  private fun stampFor(item: NasItem, entry: NasEntry?): NasStamp? = when {
    item.isFolder -> entry?.let { NasStamp(item.size, it.modifiedAt, it.fileId) }
    else -> item.stamp ?: entry?.stamp
  }

  /** La dimensione di un file e' quella del suo timbro (il diario la salva una volta sola). */
  private fun sizeFor(item: NasItem, stamp: NasStamp?): Long = if (!item.isFolder && stamp != null) stamp.size else item.size

  private fun build(kind: OpKind, items: List<PlannedItem>, skipped: List<SkippedItem>, destShare: String?, destFolder: SharePath?): OpPlan {
    if (items.size > OpRules.MAX_ITEMS_PER_OP) reject(PlanRejection.TOO_MANY_ITEMS)
    // R2: i file che l'operazione tocca davvero, contando quelli dentro le cartelle da eliminare.
    val fileCount = items.count { !it.isFolder } + items.sumOf { it.contentFiles ?: 0 }
    val warnings = buildSet {
      if (items.any { it.flow == ItemFlow.MOVE_ACROSS_SHARES }) add(PlanWarning.CROSS_SHARE_COPY)
      if (kind.permanent) add(PlanWarning.PERMANENT_DELETE)
      if (items.any { it.isFolder }) add(PlanWarning.FOLDER_INCLUDED)
      if (skipped.isNotEmpty()) add(PlanWarning.SOME_SKIPPED)
      if (kind == OpKind.TRASH && fileCount > PlanSummary.MANY_FILES) add(PlanWarning.MANY_FILES)
    }
    val summary = PlanSummary(kind, items.size, items.count { it.isFolder }, items.sumOf { it.size }, destShare, destFolder, fileCount)
    return OpPlan(kind, items, skipped, summary, warnings)
  }

  private fun emptyRejection(skipped: List<SkippedItem>): PlanRejection = when {
    skipped.any { it.reason == SkipReason.READ_ONLY } -> PlanRejection.READ_ONLY
    skipped.any { it.reason == SkipReason.INTO_ITSELF } -> PlanRejection.INTO_ITSELF
    skipped.any { it.reason == SkipReason.SOURCE_MISSING } -> PlanRejection.SOURCE_MISSING
    skipped.any { it.reason == SkipReason.SAME_LOCATION } -> PlanRejection.SAME_LOCATION
    else -> PlanRejection.EMPTY
  }

  private fun key(share: String, path: SharePath) = share.lowercase(Locale.ROOT) + "/" + path.value.lowercase(Locale.ROOT)

  // --- crea cartella ----------------------------------------------------------------------------------------------------

  private suspend fun createFolder(request: OpRequest.CreateFolder): PlanOutcome {
    val share = request.share
    if (blocked(share)) reject(PlanRejection.WRITES_BLOCKED)
    if (access.readOnly(share)) reject(PlanRejection.READ_ONLY)
    // E-D9: il nome si controlla; la correzione la propone il foglio (`NameRules.suggest`).
    if (NameRules.check(request.name) !is NameRules.Check.Valid) reject(PlanRejection.INVALID_NAME)
    request.parent?.let(::requireMedia)
    val path = request.parent?.child(request.name) ?: SharePath.of(request.name)
    requireMedia(path)
    if (stat(share, path) != null) reject(PlanRejection.NAME_EXISTS)
    val item = PlannedItem(0, ItemFlow.CREATE_FOLDER, share, path, null, null, isFolder = true, size = 0, stamp = null)
    return PlanOutcome.Ready(build(OpKind.CREATE_FOLDER, listOf(item), emptyList(), null, null))
  }

  // --- rinomina ------------------------------------------------------------------------------------------------------------

  private suspend fun rename(request: OpRequest.Rename): PlanOutcome {
    val item = request.item
    if (blocked(item.share)) reject(PlanRejection.WRITES_BLOCKED)
    if (access.readOnly(item.share)) reject(PlanRejection.READ_ONLY)
    requireMedia(item.path)
    if (NameRules.check(request.newName) !is NameRules.Check.Valid) reject(PlanRejection.INVALID_NAME)
    val dst = item.path.parent()?.child(request.newName) ?: SharePath.of(request.newName)
    requireMedia(dst)
    if (dst == item.path) reject(PlanRejection.SAME_LOCATION)
    val caseOnly = item.path.name.equals(request.newName, ignoreCase = true)
    if (!caseOnly && stat(item.share, dst) != null) reject(PlanRejection.NAME_EXISTS)
    val entry = stat(item.share, item.path) ?: reject(PlanRejection.SOURCE_MISSING)
    val real = item.copy(isFolder = entry.isDirectory, stamp = if (entry.isDirectory) null else item.stamp)
    val stamp = stampFor(real, entry)
    val flow = OpStateMachine.flowOf(OpKind.RENAME, item.share, item.share, item.path, dst)
    val planned = PlannedItem(0, flow, item.share, item.path, item.share, dst, real.isFolder, sizeFor(real, stamp), stamp)
    return PlanOutcome.Ready(build(OpKind.RENAME, listOf(planned), emptyList(), null, null))
  }

  // --- sposta ---------------------------------------------------------------------------------------------------------------

  private suspend fun move(request: OpRequest.Move): PlanOutcome {
    if (request.items.isEmpty()) reject(PlanRejection.EMPTY)
    if (blocked(request.destShare) || request.items.any { blocked(it.share) }) reject(PlanRejection.WRITES_BLOCKED)
    if (access.readOnly(request.destShare)) reject(PlanRejection.READ_ONLY)
    request.destFolder?.let(::requireMedia)
    val planned = ArrayList<PlannedItem>()
    val skipped = ArrayList<SkippedItem>()
    val seen = HashSet<String>()
    // R11: i file che l'utente ha chiesto di lasciare dov'erano (i nascosti, spostando nell'area comune): niente elementi per loro.
    val leave = request.leaveBehind.mapTo(HashSet()) { key(it.share, it.path) }
    for (item in request.items) {
      requireMedia(item.path)
      if (!seen.add(key(item.share, item.path))) continue
      if (access.readOnly(item.share)) {
        skipped += SkippedItem(item.share, item.path, SkipReason.READ_ONLY)
        continue
      }
      if (!item.isFolder && key(item.share, item.path) in leave) {
        skipped += SkippedItem(item.share, item.path, SkipReason.LEFT_BEHIND)
        continue
      }
      val dst = request.destFolder?.child(item.path.name) ?: SharePath.of(item.path.name)
      val sameShare = item.share.equals(request.destShare, ignoreCase = true)
      if (sameShare && dst == item.path) {
        skipped += SkippedItem(item.share, item.path, SkipReason.SAME_LOCATION)
        continue
      }
      if (sameShare && item.isFolder && dst.isWithin(item.path)) {
        skipped += SkippedItem(item.share, item.path, SkipReason.INTO_ITSELF)
        continue
      }
      // Una cartella si legge (identita' e, tra share, contenuto); un file si fida del timbro visto dall'utente (letto solo se manca).
      val entry = if (item.isFolder || item.stamp == null) stat(item.share, item.path) else null
      if ((item.isFolder || item.stamp == null) && (entry == null || entry.isDirectory != item.isFolder || entry.isReparsePoint)) {
        skipped += SkippedItem(item.share, item.path, SkipReason.SOURCE_MISSING)
        continue
      }
      val stamp = stampFor(item, entry)
      when {
        sameShare -> planned += PlannedItem(
          planned.size, ItemFlow.RENAME, item.share, item.path, request.destShare, dst, item.isFolder, sizeFor(item, stamp), stamp,
        )
        !item.isFolder -> planned += PlannedItem(
          planned.size, ItemFlow.MOVE_ACROSS_SHARES, item.share, item.path, request.destShare, dst, false, sizeFor(item, stamp), stamp,
          trashId = newId(),
        )
        else -> expand(item.share, item.path, checkNotNull(entry), request.destShare, dst, planned, skipped, leave, depth = 0)
      }
      if (planned.size > OpRules.MAX_ITEMS_PER_OP) reject(PlanRejection.TOO_MANY_ITEMS)
    }
    if (planned.isEmpty()) reject(emptyRejection(skipped))
    val crossBytes = planned.filter { it.flow == ItemFlow.MOVE_ACROSS_SHARES }.sumOf { it.size }
    if (planned.any { it.flow == ItemFlow.MOVE_ACROSS_SHARES }) {
      // E-D5: spazio della destinazione >= dimensione + 1 GiB, prima di iniziare (mai spostamenti a meta' per lo spazio).
      when (val space = fileOps.space(request.destShare, purpose)) {
        is NasResult.Err -> reject(PlanRejection.NAS_UNREACHABLE)
        is NasResult.Ok -> if (space.value.callerFreeBytes < OpRules.requiredFreeBytes(crossBytes)) reject(PlanRejection.NOT_ENOUGH_SPACE)
      }
    }
    return PlanOutcome.Ready(build(OpKind.MOVE, planned, skipped, request.destShare, request.destFolder))
  }

  /**
   * Una cartella spostata tra share: un elemento per ogni file (ognuno con il suo cammino completo) e, **dopo** il suo contenuto, un
   * elemento per la cartella stessa (ricrea la cartella, anche vuota, nella destinazione e toglie quella d'origine se e' rimasta vuota).
   * Ordine stabile (per nome). Un collegamento non si segue e resta dov'e' (con la sua cartella).
   *
   * R11: un file in [leave] (un nascosto che l'utente lascia dov'e') non diventa un elemento: va fra gli esclusi, e la cartella d'origine
   * resta con lui (l'elemento della cartella toglie solo una cartella rimasta vuota). Una sottocartella che resterebbe vuota **solo** perche'
   * tutto cio' che aveva era da lasciare non si ricrea nell'area di arrivo: il suo nome non deve arrivarci da solo.
   */
  private suspend fun expand(
    share: String,
    dir: SharePath,
    self: NasEntry,
    destShare: String,
    dstDir: SharePath,
    out: MutableList<PlannedItem>,
    skipped: MutableList<SkippedItem>,
    leave: Set<String>,
    depth: Int,
  ) {
    if (depth > MAX_DEPTH) reject(PlanRejection.TOO_MANY_ITEMS)
    val entries = ArrayList<NasEntry>()
    when (val r = reader.list(share, dir, purpose) { entries += it }) {
      is NasResult.Err -> reject(if (r.error == NasError.PermissionDenied) PlanRejection.READ_ONLY else PlanRejection.NAS_UNREACHABLE)
      is NasResult.Ok -> if (r.value.cappedAt != null) reject(PlanRejection.TOO_MANY_ITEMS)
    }
    // In `expand` si aggiungono agli esclusi solo i file da lasciare: la differenza di dimensione dice se ne sono stati lasciati qui sotto.
    val plannedBefore = out.size
    val leftBefore = skipped.size
    for (entry in entries.sortedBy { it.name }) {
      if (entry.isReparsePoint) continue
      // E-N6: un nome non valido letto dal NAS non diventa mai un elemento (resta dov'e', con la sua cartella).
      val src = runCatching { dir.child(entry.name) }.getOrNull() ?: continue
      val dst = runCatching { dstDir.child(entry.name) }.getOrNull() ?: continue
      if (entry.isDirectory) {
        expand(share, src, entry, destShare, dst, out, skipped, leave, depth + 1)
      } else if (key(share, src) in leave) {
        skipped += SkippedItem(share, src, SkipReason.LEFT_BEHIND)
      } else {
        out += PlannedItem(
          out.size, ItemFlow.MOVE_ACROSS_SHARES, share, src, destShare, dst, false, entry.size, entry.stamp, trashId = newId(),
        )
      }
      if (out.size > OpRules.MAX_ITEMS_PER_OP) reject(PlanRejection.TOO_MANY_ITEMS)
    }
    val onlyLeftBehind = depth > 0 && out.size == plannedBefore && skipped.size > leftBefore
    if (onlyLeftBehind) return
    out += PlannedItem(
      out.size, ItemFlow.MOVE_ACROSS_SHARES, share, dir, destShare, dstDir, true, 0, NasStamp(0, self.modifiedAt, self.fileId),
    )
  }

  // --- elimina ---------------------------------------------------------------------------------------------------------------

  private suspend fun trash(request: OpRequest.Trash): PlanOutcome {
    if (request.items.isEmpty()) reject(PlanRejection.EMPTY)
    if (request.items.any { blocked(it.share) }) reject(PlanRejection.WRITES_BLOCKED)
    request.items.forEach { requireMedia(it.path) }
    val planned = ArrayList<PlannedItem>()
    val skipped = ArrayList<SkippedItem>()
    val seen = HashSet<String>()
    // Un elemento dentro una cartella scelta anch'essa va con la cartella: un solo cestino, nessun "spostato da altri".
    val folders = request.items.filter { it.isFolder }
    for (item in request.items) {
      if (!seen.add(key(item.share, item.path))) continue
      if (folders.any { f -> f !== item && f.share.equals(item.share, ignoreCase = true) && item.path != f.path && item.path.isWithin(f.path) }) continue
      // E-D11: in un'area senza permesso non si fa niente, e il foglio lo elenca.
      if (access.readOnly(item.share)) {
        skipped += SkippedItem(item.share, item.path, SkipReason.READ_ONLY)
        continue
      }
      val entry = if (item.isFolder || item.stamp == null) stat(item.share, item.path) else null
      if ((item.isFolder || item.stamp == null) && entry == null) {
        skipped += SkippedItem(item.share, item.path, SkipReason.SOURCE_MISSING)
        continue
      }
      val real = if (entry != null && entry.isDirectory != item.isFolder) item.copy(isFolder = entry.isDirectory, stamp = null) else item
      if (real.isFolder && entry?.isReparsePoint == true) {
        // Un collegamento non si conta e non si elimina da qui (E-N10).
        skipped += SkippedItem(item.share, item.path, SkipReason.SOURCE_MISSING)
        continue
      }
      // R2: una cartella si conta adesso, scendendo in tutte le sottocartelle: il foglio dice quanti file e quanti byte, e la conferma si
      // lega a quei numeri (entrano nell'impronta). Una cartella che non si riesce a contare non si elimina in un colpo solo.
      val counted = if (real.isFolder) countForPlan(item.share, item.path, entry?.fileId) else null
      val stamp = if (counted != null) NasStamp(counted.bytes, checkNotNull(entry).modifiedAt, entry.fileId) else stampFor(real, entry)
      planned += PlannedItem(
        planned.size, ItemFlow.TRASH, item.share, item.path, null, null, real.isFolder,
        counted?.bytes ?: sizeFor(real, stamp), stamp, trashId = newId(), contentFiles = counted?.files,
      )
    }
    if (planned.isEmpty()) reject(emptyRejection(skipped))
    return PlanOutcome.Ready(build(OpKind.TRASH, planned, skipped, null, null))
  }

  /** R2: il contenuto vero di una cartella da eliminare, o il rifiuto del piano. */
  private suspend fun countForPlan(share: String, path: SharePath, fileId: Long?): FolderInventory.Result.Counted =
    when (val r = inventory.take(share, path, fileId, purpose, checkCatalog = false)) {
      is FolderInventory.Result.Counted -> r
      FolderInventory.Result.TooBig -> reject(PlanRejection.TOO_MANY_ITEMS)
      is FolderInventory.Result.Failed -> reject(
        when (r.error) {
          NasError.PermissionDenied -> PlanRejection.READ_ONLY
          NasError.NotFound -> PlanRejection.SOURCE_MISSING
          else -> PlanRejection.NAS_UNREACHABLE
        },
      )
    }

  // --- ripristina ------------------------------------------------------------------------------------------------------------

  private suspend fun restore(request: OpRequest.Restore): PlanOutcome {
    if (request.entries.isEmpty()) reject(PlanRejection.EMPTY)
    if (request.entries.any { blocked(it.share) }) reject(PlanRejection.WRITES_BLOCKED)
    val planned = ArrayList<PlannedItem>()
    val skipped = ArrayList<SkippedItem>()
    val seen = HashSet<String>()
    for (ref in request.entries) {
      // Il percorso d'origine viene da un record (anche altrui) mostrato all'utente: solo un percorso di media, mai l'area dell'app.
      requireMedia(ref.originalPath)
      if (!seen.add(ref.share.lowercase(Locale.ROOT) + "/" + ref.ownerDeviceId + "/" + ref.trashId)) continue
      if (access.readOnly(ref.share)) {
        skipped += SkippedItem(ref.share, ref.originalPath, SkipReason.READ_ONLY)
        continue
      }
      // Il file da spostare e' quello che sta davvero nel cestino del proprietario: il posto lo deriva l'app da (proprietario, id).
      val trashed = NasLayout.trashItemFile(ref.ownerDeviceId, ref.trashId)
      val entry = stat(ref.share, trashed)
      if (entry == null || entry.isReparsePoint) {
        skipped += SkippedItem(ref.share, ref.originalPath, SkipReason.SOURCE_MISSING)
        continue
      }
      planned += PlannedItem(
        planned.size, ItemFlow.RESTORE, ref.share, trashed, ref.share, ref.originalPath, entry.isDirectory,
        if (entry.isDirectory) ref.size else entry.size, null, trashId = ref.trashId,
      )
    }
    if (planned.isEmpty()) reject(emptyRejection(skipped))
    return PlanOutcome.Ready(build(OpKind.RESTORE, planned, skipped, null, null))
  }

  // --- elimina subito (Q8) ----------------------------------------------------------------------------------------------

  private suspend fun purgeNow(request: OpRequest.PurgeNow): PlanOutcome {
    if (request.entries.isEmpty()) reject(PlanRejection.EMPTY)
    val planned = ArrayList<PlannedItem>()
    val seen = HashSet<String>()
    val busy = busyTrashIds()
    for (ref in request.entries) {
      if (!seen.add(ref.trashId)) continue
      // Solo il diario locale dice cosa e' mio: percorso, dimensione e share vengono da li', mai da un record remoto.
      val entry = store.trashEntry(ref.trashId)
      if (entry == null || entry.state != TrashEntryState.TRASHED || entry.accountId != accountId) reject(PlanRejection.NOT_MINE)
      if (entry.trashedPath != NasLayout.trashItemFile(deviceId, ref.trashId)) reject(PlanRejection.NOT_MINE)
      if (blocked(entry.shareName)) reject(PlanRejection.WRITES_BLOCKED)
      // I6: una voce ancora in mano a un'operazione aperta (il file non e' ancora nel cestino, o un ripristino la sta spostando) non si
      // elimina adesso: si riprova a operazione chiusa.
      if (ref.trashId in busy) reject(PlanRejection.WRITES_BLOCKED)
      planned += PlannedItem(
        planned.size, ItemFlow.PURGE, entry.shareName, entry.trashedPath, null, null, entry.isFolder, entry.size, null, trashId = ref.trashId,
      )
    }
    return PlanOutcome.Ready(build(OpKind.PURGE_NOW, planned, emptyList(), null, null))
  }

  /** Le voci del cestino citate da un elemento non chiuso di un'operazione aperta. */
  private suspend fun busyTrashIds(): Set<String> {
    val out = HashSet<String>()
    for (op in store.ops(setOf(OpState.PLANNED, OpState.RUNNING, OpState.PAUSED))) {
      store.items(op.id).filter { !it.state.terminal }.mapNotNullTo(out) { it.trashId }
    }
    return out
  }

  private companion object {
    /** Profondita' massima di una cartella spostata tra share (oltre, qualcosa non va: un ciclo di collegamenti). */
    const val MAX_DEPTH = 64
  }
}
