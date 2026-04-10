package com.mycloudgallery.presentation.gallery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Calendar

class TimelineGrouperTest {

    @Test
    fun `timestamp di oggi restituisce Oggi`() {
        val today = System.currentTimeMillis()
        assertEquals("Oggi", TimelineGrouper.getSectionTitle(today))
    }

    @Test
    fun `timestamp di ieri restituisce Ieri`() {
        val yesterday = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        assertEquals("Ieri", TimelineGrouper.getSectionTitle(yesterday))
    }

    @Test
    fun `timestamp di due anni fa include anno`() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.YEAR, -2)
        val title = TimelineGrouper.getSectionTitle(cal.timeInMillis)
        // Deve contenere l'anno (es. "Aprile 2024")
        assert(title.contains(cal.get(Calendar.YEAR).toString())) {
            "Titolo '$title' dovrebbe contenere l'anno ${cal.get(Calendar.YEAR)}"
        }
    }

    @Test
    fun `sectionKey di oggi è today`() {
        val today = System.currentTimeMillis()
        assertEquals("today", TimelineGrouper.getSectionKey(today))
    }

    @Test
    fun `sectionKey di ieri è yesterday`() {
        val yesterday = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        assertEquals("yesterday", TimelineGrouper.getSectionKey(yesterday))
    }

    @Test
    fun `sectionKey di due date dello stesso mese è uguale`() {
        val cal1 = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2023)
            set(Calendar.MONTH, Calendar.JUNE)
            set(Calendar.DAY_OF_MONTH, 5)
        }
        val cal2 = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2023)
            set(Calendar.MONTH, Calendar.JUNE)
            set(Calendar.DAY_OF_MONTH, 20)
        }
        assertEquals(
            TimelineGrouper.getSectionKey(cal1.timeInMillis),
            TimelineGrouper.getSectionKey(cal2.timeInMillis),
        )
    }
}
