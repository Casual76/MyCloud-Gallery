package dev.pampa.mycloudgallery.core.widget.testing

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSchedule
import dev.pampa.mycloudgallery.core.widget.contract.WidgetScheduleRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapRequest
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapUri
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTaps
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTarget
import java.time.LocalDateTime
import java.time.LocalTime

/** Le ore del widget scritte nel modo piu' diretto (§5.2): il confronto per `schedule/` di P2 (suite `WidgetScheduleContract`). */
class ReferenceWidgetSchedule : WidgetSchedule {

  override fun isQuiet(now: LocalTime): Boolean = !now.isBefore(WidgetScheduleRules.QUIET_FROM) || now.isBefore(WidgetScheduleRules.QUIET_UNTIL)

  override fun nextRun(now: LocalDateTime, period: WidgetPeriod): LocalDateTime {
    if (period == WidgetPeriod.DAILY) return nextMorning(now)
    val candidate = now.plusHours(period.hours.toLong())
    return if (isQuiet(candidate.toLocalTime())) nextMorning(candidate.minusNanos(1)) else candidate
  }

  /** Le 7:00 del primo mattino strettamente dopo [after]. */
  private fun nextMorning(after: LocalDateTime): LocalDateTime {
    val today = after.toLocalDate().atTime(WidgetScheduleRules.QUIET_UNTIL)
    return if (today.isAfter(after)) today else today.plusDays(1)
  }
}

/**
 * Il tocco scritto nel modo piu' diretto (§5.2, D9): il confronto per `WidgetTaps` di P2 (suite `WidgetTapContract`). [parse] legge carattere
 * per carattere, senza espressioni regolari ne' conversioni permissive.
 */
class ReferenceWidgetTaps : WidgetTaps {

  override fun parse(uri: String?): WidgetTapRequest? {
    if (uri == null) return null
    val prefix = WidgetTapUri.SCHEME + ":"
    if (!uri.startsWith(prefix)) return null
    val rest = uri.substring(prefix.length)
    val slash = rest.indexOf('/')
    if (slash <= 0 || slash != rest.lastIndexOf('/')) return null
    val id = strictInt(rest.substring(0, slash)) ?: return null
    val cell = strictInt(rest.substring(slash + 1)) ?: return null
    if (id < 1 || cell >= WidgetTapUri.CELLS) return null
    return WidgetTapRequest(id, cell)
  }

  /** Solo cifre ASCII, almeno una, nessuno zero davanti (tranne "0"), dentro un `Int`. */
  private fun strictInt(text: String): Int? {
    if (text.isEmpty() || text.length > 10) return null
    if (text.any { it !in '0'..'9' }) return null
    if (text.length > 1 && text[0] == '0') return null
    return text.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt()
  }

  override suspend fun resolve(
    request: WidgetTapRequest,
    ownSlots: Set<WidgetSlot>,
    ledger: WidgetLedger,
    visible: VisibleMedia,
    mask: HiddenMask,
  ): WidgetTarget {
    val slot = WidgetSlot(request.appWidgetId)
    if (slot !in ownSlots) return WidgetTarget.Library(notice = false)
    val ref = ledger.cell(slot, request.cell) ?: return WidgetTarget.Library(notice = false)
    val stillThere = ref.media in visible.visible(listOf(ref.media)) && ref.media !in mask.hiddenAmong(listOf(ref.media))
    if (!stillThere) return WidgetTarget.Library(notice = true)
    val memory = ref.memory
    return if (memory != null) WidgetTarget.MemoryPage(memory) else WidgetTarget.Viewer(ref.media)
  }
}
