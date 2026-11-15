package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class MonthGroupingTest {
  @Test
  fun fastMonthKeysKeepTheExistingGrouping() {
    for (day in listOf(null, -1, 0, 101, 9990101, 20261115, Int.MAX_VALUE)) {
      val expected = if (day == null || day <= 0) "0000-00" else "%04d-%02d".format(Locale.ROOT, day / 10_000, day / 100 % 100)
      assertThat(monthOf(day)).isEqualTo(expected)
    }
    for (year in 1990..2035) for (month in 1..12) {
      assertThat(monthOf(year * 10_000 + month * 100 + 15)).isEqualTo("%04d-%02d".format(Locale.ROOT, year, month))
    }
  }
}
