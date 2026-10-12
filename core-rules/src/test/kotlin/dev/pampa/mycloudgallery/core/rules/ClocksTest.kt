package dev.pampa.mycloudgallery.core.rules

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Test

class ClocksTest {

  @Test
  fun ilFusoOrarioSegueQuelloDelDispositivoAncheDopoIlCambio_E_P5() {
    val original = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")) // UTC+14
      val east = LocalDate.now(SystemClockFollowingZone)
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago")) // UTC-11
      val west = LocalDate.now(SystemClockFollowingZone)

      // 25 ore di differenza: le due date locali non possono essere la stessa.
      assertThat(east).isNotEqualTo(west)
      assertThat(SystemClockFollowingZone.zone.id).isEqualTo("Pacific/Pago_Pago")
    } finally {
      TimeZone.setDefault(original)
    }
  }
}
