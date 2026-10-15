package dev.pampa.mycloudgallery.core.nasops

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.PlanSummary
import dev.pampa.mycloudgallery.core.nasops.contract.PlannedItem
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/** Piani costruiti a mano per i test delle regole pure (senza NAS). */
object PlanFixtures {
  val me: String = Base32.encode(ByteArray(16) { 0x11 })
  val other: String = Base32.encode(ByteArray(16) { 0x22 })

  fun id(n: Int): String = Base32.encode(ByteArray(16) { n.toByte() })

  private fun plan(kind: OpKind, items: List<PlannedItem>) =
    OpPlan(kind, items, emptyList(), PlanSummary(kind, items.size, items.count { it.isFolder }, items.sumOf { it.size }, null, null), emptySet())

  fun trash(count: Int = 1, share: String = "test"): OpPlan = plan(
    OpKind.TRASH,
    (0 until count).map { PlannedItem(it, ItemFlow.TRASH, share, SharePath.of("Foto", "f$it.jpg"), null, null, false, 10, NasStamp(10, 1_000, it.toLong()), trashId = id(it + 1)) },
  )

  fun rename(from: String, to: String, share: String = "test"): OpPlan = plan(
    OpKind.RENAME,
    listOf(PlannedItem(0, ItemFlow.RENAME, share, SharePath.parse(from), share, SharePath.parse(to), false, 10, null)),
  )

  fun move(from: String, to: String, fromShare: String = "test", toShare: String = "test-comune"): OpPlan = plan(
    OpKind.MOVE,
    listOf(
      PlannedItem(
        0, ItemFlow.MOVE_ACROSS_SHARES, fromShare, SharePath.parse(from), toShare, SharePath.parse(to), false, 10, NasStamp(10, 1_000, 5), trashId = id(9),
      ),
    ),
  )

  fun purge(owner: String = me, trashId: String = id(3)): OpPlan = plan(
    OpKind.PURGE_NOW,
    listOf(PlannedItem(0, ItemFlow.PURGE, "test", NasLayout.trashItemFile(owner, trashId), null, null, false, 10, null, trashId = trashId)),
  )

  fun restore(owner: String = me, trashId: String = id(3), to: String = "Foto/x.jpg"): OpPlan = plan(
    OpKind.RESTORE,
    listOf(PlannedItem(0, ItemFlow.RESTORE, "test", NasLayout.trashItemFile(owner, trashId), "test", SharePath.parse(to), false, 10, null, trashId = trashId)),
  )

  fun create(path: String): OpPlan = plan(
    OpKind.CREATE_FOLDER,
    listOf(PlannedItem(0, ItemFlow.CREATE_FOLDER, "test", SharePath.parse(path), null, null, true, 0, null)),
  )
}
