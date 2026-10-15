package dev.pampa.mycloudgallery.feature.nas

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nasops.contract.DoneSummary
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.LargestFile
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceDetails
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashItem
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanSummary
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.PlannedItem
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.contract.SkippedItem
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * I dati di prova delle schermate: fatti per sembrare un NAS di famiglia (viaggi, foto, video, documenti), con dimensioni e date
 * plausibili, e per toccare i casi che le schermate devono reggere (nomi lunghi, cartelle grandi, voci senza record). Le date
 * sono ora del NAS fissa, cosi' gli screenshot non cambiano da un giro all'altro.
 */
object NasFixtures {

  /** 2026-10-15T12:32:00Z: l'ora del NAS dei dati di prova. */
  const val NOW: Long = 1_792_067_520_000L
  private const val DAY = 24L * 60 * 60 * 1000

  fun opId(n: Int): OpId = OpId(Base32.encode(ByteArray(16) { i -> if (i == 15) n.toByte() else 0x30 }))

  fun trashId(n: Int): String = Base32.encode(ByteArray(16) { i -> if (i == 15) n.toByte() else 0x50 })

  val DEVICE_ME: String = NasOpsTestKit.DEVICE_A
  val DEVICE_OTHER: String = NasOpsTestKit.DEVICE_B

  // --- Cartelle --------------------------------------------------------------------------------------------------------------

  private fun folder(name: String, parent: String? = null, files: Int? = null) = NasFolderEntry(
    path = parent?.let { SharePath.parse("$it/$name") } ?: SharePath.of(name),
    isFolder = true,
    size = 0,
    modifiedAtNas = NOW - 3 * DAY,
    fileId = null,
    fileCount = files,
  )

  private fun file(name: String, size: Long, parent: String? = null, daysAgo: Int = 1, id: Long = name.hashCode().toLong()) = NasFolderEntry(
    path = parent?.let { SharePath.parse("$it/$name") } ?: SharePath.of(name),
    isFolder = false,
    size = size,
    modifiedAtNas = NOW - daysAgo * DAY,
    fileId = id,
  )

  /** La radice di un'area privata: cartelle con il numero dei file e qualche file sciolto. */
  fun rootEntries(): List<NasFolderEntry> = listOf(
    folder("Documenti", files = 214),
    folder("Famiglia", files = 1_804),
    folder("Scuola", files = 62),
    folder("Viaggi", files = 3_412),
    file("IMG_20260921_184233.jpg", 4_812_345, daysAgo = 24),
    file("Compleanno nonna.mp4", 812_345_678, daysAgo = 9),
    file("Bollette 2026.pdf", 1_204_800, daysAgo = 2),
    file("Appunti.txt", 3_210, daysAgo = 40),
  )

  fun tripEntries(): List<NasFolderEntry> = listOf(
    folder("Oslo 2026", "Viaggi", files = 412),
    folder("Roma 2025", "Viaggi", files = 1_104),
    file("Mappa del percorso.png", 2_431_221, "Viaggi", 30),
    file("Biglietti aerei.pdf", 301_445, "Viaggi", 75),
    file("DJI_0142.mp4", 2_302_112_980, "Viaggi", 12),
    file("Una fotografia con un nome davvero molto lungo che non sta in una riga sola del telefono.jpg", 6_100_432, "Viaggi", 5),
  )

  // --- Cestino ---------------------------------------------------------------------------------------------------------------

  fun trashItems(): List<NasTrashItem> = listOf(
    NasTrashItem(
      share = "test", ownerDeviceId = DEVICE_ME, trashId = trashId(1), name = "IMG_20260921_184233.jpg",
      originalFolder = SharePath.parse("Viaggi/Oslo 2026"), originalPath = SharePath.parse("Viaggi/Oslo 2026/IMG_20260921_184233.jpg"),
      size = 4_812_345, daysLeft = 29, author = null, reason = TrashReason.DELETE, isFolder = false, fileCount = null,
      withoutValidRecord = false, restorable = true, deletableNow = true,
    ),
    NasTrashItem(
      share = "test", ownerDeviceId = DEVICE_ME, trashId = trashId(2), name = "Vecchie bozze",
      originalFolder = SharePath.parse("Documenti"), originalPath = SharePath.parse("Documenti/Vecchie bozze"),
      size = 182_000_000, daysLeft = 11, author = null, reason = TrashReason.DELETE, isFolder = true, fileCount = 74,
      withoutValidRecord = false, restorable = true, deletableNow = true,
    ),
    NasTrashItem(
      share = "test-comune", ownerDeviceId = DEVICE_OTHER, trashId = trashId(3), name = "Compleanno nonna.mp4",
      originalFolder = SharePath.parse("Famiglia"), originalPath = SharePath.parse("Famiglia/Compleanno nonna.mp4"),
      size = 812_345_678, daysLeft = 3, author = "Tablet di Mamma", reason = TrashReason.DELETE, isFolder = false, fileCount = null,
      withoutValidRecord = false, restorable = true, deletableNow = false,
    ),
    NasTrashItem(
      share = "test", ownerDeviceId = DEVICE_ME, trashId = trashId(4), name = "DSC_0042.jpg",
      originalFolder = null, originalPath = SharePath.of("DSC_0042.jpg"),
      size = 3_204_100, daysLeft = 0, author = null, reason = TrashReason.MOVE, isFolder = false, fileCount = null,
      withoutValidRecord = false, restorable = true, deletableNow = true,
    ),
    NasTrashItem(
      share = "test-comune", ownerDeviceId = DEVICE_OTHER, trashId = trashId(5), name = null,
      originalFolder = null, originalPath = null,
      size = 52_411_000, daysLeft = 17, author = null, reason = null, isFolder = false, fileCount = null,
      withoutValidRecord = true, restorable = false, deletableNow = false,
    ),
  )

  // --- Spazio ----------------------------------------------------------------------------------------------------------------

  fun largest(count: Int = 14): List<LargestFile> {
    val names = listOf(
      "Compleanno nonna.mp4" to 812_345_678L, "DJI_0142.mp4" to 2_302_112_980L, "Matrimonio Giulia.mov" to 4_100_000_000L,
      "Backup telefono.zip" to 1_840_000_000L, "Vacanze 2024.mp4" to 1_320_000_000L, "Tesi.pdf" to 98_000_000L,
      "Scansioni archivio.tif" to 640_000_000L, "Natale 2025.mp4" to 560_000_000L, "Lezioni registrate.mp4" to 480_000_000L,
      "Festa di classe.mp4" to 410_000_000L, "Musica.zip" to 380_000_000L, "Foto panoramica.jpg" to 120_000_000L,
      "Ricette scannerizzate.pdf" to 95_000_000L, "Pagella.pdf" to 4_000_000L,
    ).sortedByDescending { it.second }
    return names.take(count).mapIndexed { index, (name, size) ->
      val parent = listOf("Viaggi", "Famiglia", "Documenti", "Scuola")[index % 4]
      LargestFile("test", SharePath.parse("$parent/$name"), size, NasStamp(size, NOW - index * DAY, index.toLong()))
    }
  }

  fun space(quota: Long? = null, wd: Boolean = false, share: String = "test"): NasSpaceDetails = NasSpaceDetails(
    share = share,
    freeBytes = 1_840_000_000_000L,
    totalBytes = 3_600_000_000_000L,
    quotaBytes = quota,
    largest = largest(),
    trashBytes = 1_240_000_000L,
    indexBytes = 386_000_000L,
    versionsBytes = 0,
    wdTrashMayHoldSpace = wd,
    measuredAtNas = NOW,
  )

  // --- Operazioni ------------------------------------------------------------------------------------------------------------

  fun summary(kind: OpKind, count: Int, bytes: Long, destShare: String? = null, destFolder: String? = null, folders: Int = 0) = PlanSummary(
    kind = kind,
    itemCount = count,
    folderCount = folders,
    totalBytes = bytes,
    destShare = destShare,
    destFolder = destFolder?.let { SharePath.parse(it) },
  )

  fun operations(): List<NasOperationState> = listOf(
    NasOperationState.Running(opId(1), OpKind.MOVE, done = 128, total = 312, bytesDone = 1_640_000_000, bytesTotal = 4_100_000_000),
    NasOperationState.Paused(opId(2), OpKind.TRASH, PauseReason.NAS_UNREACHABLE, done = 12, total = 40),
    NasOperationState.WaitingLocked(opId(3), OpKind.MOVE, lockedCount = 2, done = 20, total = 22),
    NasOperationState.Planned(opId(4), OpKind.RESTORE, summary(OpKind.RESTORE, 3, 190_000_000)),
    NasOperationState.Done(opId(5), OpKind.MOVE, DoneSummary(done = 310, failed = 2, skipped = 0, bytes = 4_100_000_000)),
    NasOperationState.Done(opId(6), OpKind.TRASH, DoneSummary(done = 18, failed = 0, skipped = 0, bytes = 96_000_000)),
    NasOperationState.Failed(opId(7), OpKind.MOVE, OpErrorCode.NO_SPACE, done = 41, total = 120),
    NasOperationState.Cancelled(opId(8), OpKind.PURGE_NOW, done = 3, total = 9),
  )

  // --- Piani per i fogli di conferma -----------------------------------------------------------------------------------------

  private fun item(index: Int, flow: ItemFlow, srcShare: String, src: String, dstShare: String?, dst: String?, folder: Boolean, size: Long, trash: String? = null) =
    PlannedItem(index, flow, srcShare, SharePath.parse(src), dstShare, dst?.let { SharePath.parse(it) }, folder, size, null, trash)

  /** Spostare 312 elementi (2 cartelle), 4,1 GB, nell'area comune: "le vedranno tutti i familiari". */
  fun moveToCommonPlan(skipped: Boolean = false): OpPlan = OpPlan(
    kind = OpKind.MOVE,
    items = listOf(
      item(0, ItemFlow.MOVE_ACROSS_SHARES, "test", "Viaggi/Oslo 2026", "test-comune", "Famiglia/Viaggi/Oslo 2026", true, 3_900_000_000, trashId(11)),
      item(1, ItemFlow.MOVE_ACROSS_SHARES, "test", "Viaggi/DJI_0142.mp4", "test-comune", "Famiglia/Viaggi/DJI_0142.mp4", false, 200_000_000, trashId(12)),
    ),
    skipped = if (skipped) listOf(SkippedItem("test", SharePath.parse("Viaggi/Bloccata"), SkipReason.SAME_LOCATION)) else emptyList(),
    summary = summary(OpKind.MOVE, 312, 4_100_000_000, "test-comune", "Famiglia/Viaggi", folders = 2),
    warnings = setOf(PlanWarning.CROSS_SHARE_COPY, PlanWarning.FOLDER_INCLUDED) + if (skipped) setOf(PlanWarning.SOME_SKIPPED) else emptySet(),
  )

  /** Spostare dentro la stessa area (una rinomina atomica). */
  fun moveSameAreaPlan(): OpPlan = OpPlan(
    kind = OpKind.MOVE,
    items = listOf(item(0, ItemFlow.RENAME, "test", "IMG_1.jpg", "test", "Viaggi/IMG_1.jpg", false, 4_812_345)),
    skipped = emptyList(),
    summary = summary(OpKind.MOVE, 3, 14_400_000, "test", "Viaggi"),
    warnings = emptySet(),
  )

  fun trashPlan(folders: Int = 1): OpPlan = OpPlan(
    kind = OpKind.TRASH,
    items = listOf(item(0, ItemFlow.TRASH, "test", "Documenti/Vecchie bozze", null, null, true, 182_000_000, trashId(21))),
    skipped = emptyList(),
    summary = summary(OpKind.TRASH, 3, 190_000_000, folders = folders),
    warnings = if (folders > 0) setOf(PlanWarning.FOLDER_INCLUDED) else emptySet(),
  )

  fun restorePlan(): OpPlan = OpPlan(
    kind = OpKind.RESTORE,
    items = listOf(
      item(
        0, ItemFlow.RESTORE, "test", "${TRASH_ROOT}/$DEVICE_ME/files/${trashId(2)}", "test", "Documenti/Vecchie bozze", true, 182_000_000, trashId(2),
      ),
    ),
    skipped = emptyList(),
    summary = summary(OpKind.RESTORE, 2, 186_800_000, folders = 1),
    warnings = setOf(PlanWarning.FOLDER_INCLUDED, PlanWarning.NAME_ADJUSTED),
  )

  fun purgePlan(): OpPlan = OpPlan(
    kind = OpKind.PURGE_NOW,
    items = listOf(
      item(0, ItemFlow.PURGE, "test", "${TRASH_ROOT}/$DEVICE_ME/files/${trashId(1)}", null, null, false, 4_812_345, trashId(1)),
    ),
    skipped = emptyList(),
    summary = summary(OpKind.PURGE_NOW, 2, 187_000_000, folders = 1),
    warnings = setOf(PlanWarning.PERMANENT_DELETE, PlanWarning.FOLDER_INCLUDED),
  )

  private const val TRASH_ROOT = ".mycloudgallery/trash"

  @Suppress("unused")
  fun newTrashId(): String = TrustIds.random()
}
