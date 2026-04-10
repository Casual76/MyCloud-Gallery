package com.mycloudgallery.presentation.gallery

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Determina il titolo della sezione temporale per un dato timestamp.
 * Usato per generare gli sticky headers della timeline.
 */
object TimelineGrouper {

    private val monthYearFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("MMMM", Locale.getDefault())

    fun getSectionTitle(timestampMs: Long): String {
        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = timestampMs }

        // Stesso anno
        if (date.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            val dayOfYear = date.get(Calendar.DAY_OF_YEAR)
            val todayDoy = now.get(Calendar.DAY_OF_YEAR)

            return when {
                dayOfYear == todayDoy -> "Oggi"
                dayOfYear == todayDoy - 1 -> "Ieri"
                dayOfYear > todayDoy - 7 && date.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR) ->
                    "Questa settimana"
                date.get(Calendar.MONTH) == now.get(Calendar.MONTH) ->
                    "Questo mese"
                else -> capitalize(monthFormat.format(Date(timestampMs)))
            }
        }

        return capitalize(monthYearFormat.format(Date(timestampMs)))
    }

    /**
     * Genera una chiave di raggruppamento stabile (anno-mese) per le sezioni.
     * Usata per determinare quando inserire un nuovo header.
     */
    fun getSectionKey(timestampMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val now = Calendar.getInstance()

        if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
            val todayDoy = now.get(Calendar.DAY_OF_YEAR)
            return when {
                dayOfYear == todayDoy -> "today"
                dayOfYear == todayDoy - 1 -> "yesterday"
                dayOfYear > todayDoy - 7 && cal.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR) ->
                    "this_week"
                cal.get(Calendar.MONTH) == now.get(Calendar.MONTH) -> "this_month"
                else -> "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            }
        }
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
    }

    private fun capitalize(s: String): String =
        s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}
