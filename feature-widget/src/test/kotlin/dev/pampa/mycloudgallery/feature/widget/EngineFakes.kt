package dev.pampa.mycloudgallery.feature.widget

import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCellRef
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlanner
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPrefs
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetClock
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetPrefs
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetFrame
import dev.pampa.mycloudgallery.feature.widget.glance.WidgetComposer
import dev.pampa.mycloudgallery.feature.widget.host.WidgetAppearance
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServices
import dev.pampa.mycloudgallery.feature.widget.host.WidgetThumbnails
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetTargets
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import java.time.YearMonth
import java.util.UUID

/** I widget sulla Home finta: [dims] i dp di ognuno (larghezza, altezza); ogni consegna finisce in [events] (`push:<id>`) insieme al resto, in ordine. */
internal class RecordingTargets(val dims: MutableMap<WidgetSlot, Pair<Int, Int>>, val events: MutableList<String>) : WidgetTargets {
  val pushed = mutableListOf<WidgetSlot>()

  override fun slots(): List<WidgetSlot> = dims.keys.toList()

  override fun sizeOf(slot: WidgetSlot): Pair<Int, Int> = dims[slot] ?: (0 to 0)

  override fun push(slot: WidgetSlot, views: RemoteViews) {
    pushed += slot
    events += "push:${slot.appWidgetId}"
  }
}

/** La composizione finta: tiene i fotogrammi che riceve (nessun Glance) e restituisce dei `RemoteViews` qualunque. */
internal class RecordingComposer(private val context: Context, val events: MutableList<String>) : WidgetComposer {
  val frames = mutableListOf<WidgetFrame>()

  override suspend fun compose(frame: WidgetFrame, size: DpSize, settings: EngineSettings): RemoteViews {
    frames += frame
    events += "compose:${frame.slot.appWidgetId}:${frame.state.javaClass.simpleName}"
    return RemoteViews(context.packageName, R.layout.widget_initial)
  }
}

/** Un registro che scrive negli [events] cio' che gli si chiede, e che puo' fallire come un disco pieno ([failWrites]). */
internal class OrderedLedger(private val inner: WidgetLedger, private val events: MutableList<String>) : WidgetLedger by inner {
  var failWrites = false
  var failReads = false

  override suspend fun record(plan: WidgetPlan) {
    if (failWrites) throw IOException("disco pieno")
    events += "record:${plan.slot.appWidgetId}:${plan.mode.javaClass.simpleName}:${plan.cells.size}"
    inner.record(plan)
  }

  override suspend fun clear() {
    events += "clear"
    inner.clear()
  }

  override suspend fun cell(slot: WidgetSlot, index: Int): WidgetCellRef? = inner.cell(slot, index)

  override suspend fun showing(ids: Set<MediaId>): Set<WidgetSlot> {
    if (failReads) throw IOException("registro illeggibile")
    return inner.showing(ids)
  }

  override suspend fun showingMemory(id: MemoryId): Set<WidgetSlot> {
    if (failReads) throw IOException("registro illeggibile")
    return inner.showingMemory(id)
  }
}

/**
 * Il pianificatore finto: [next] decide il piano per (slot, misura); [events] riceve `plan:<id>` a ogni chiamata; [gate] (se c'e') fa aspettare la **prima** chiamata,
 * come una lettura lenta: serve a far arrivare un nascosto **mentre** il giro e' in corso.
 */
internal class ScriptedPlanner(
  private val events: MutableList<String>,
  var gate: CompletableDeferred<Unit>? = null,
  private val next: suspend (WidgetSlot, WidgetSize, Int) -> WidgetPlan,
) : WidgetPlanner {
  var calls = 0
    private set

  override suspend fun plan(slot: WidgetSlot, size: WidgetSize, budgetMs: Long): WidgetPlan {
    calls++
    events += "plan:${slot.appWidgetId}"
    val waiting = gate
    if (waiting != null && calls == 1) waiting.await()
    return next(slot, size, calls)
  }
}

/** Le porte di `:app` finte, con gli arnesi che servono ai test. */
internal class FakeServices(
  override val planner: WidgetPlanner,
  override val ledger: WidgetLedger,
  override val thumbnails: WidgetThumbnails,
  override val prefs: WidgetPrefs = FakeWidgetPrefs(),
  override val clock: WidgetClock = FakeWidgetClock(),
  override val places: WidgetPlaceNames = WidgetPlaceNames { if (it == WidgetTestKit.OSLO) "Oslo" else null },
  override val appearance: WidgetAppearance = WidgetAppearance { EngineSettings() },
) : WidgetServices

/** Un id di media riconoscibile dall'indice, uguale a ogni chiamata. */
internal fun mediaId(n: Int): MediaId = MediaId(UUID(11L, n.toLong()).toString())

/** Un piano con [count] foto (id `mediaId(0..count-1)`), tutte `PHOTO`, nella misura [size]. */
internal fun photoPlan(slot: WidgetSlot, size: WidgetSize, count: Int, epoch: Long = 1, from: Int = 0, mode: WidgetMode = WidgetMode.Photos): WidgetPlan =
  WidgetPlan(slot, size, mode, List(count) { WidgetCell(it, mediaId(from + it), MediaKind.PHOTO, 20251031 - it) }, epoch)

/** Una miniatura sintetica [side] x [side]. */
internal fun thumb(side: Int = 64): Bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)

internal fun memoryId(n: Int): MemoryId = MemoryIds.of(MemoryKey.Month(YearMonth.of(2025, 1 + n % 12)))
