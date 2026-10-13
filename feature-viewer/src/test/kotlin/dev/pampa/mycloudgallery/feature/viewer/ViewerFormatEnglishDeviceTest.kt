package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P11: **con il telefono in inglese** il viewer parla italiano. Prima `ViewerFormat` prendeva la lingua del dispositivo
 * (`Locale.getDefault()`) e il titolo diventava "March 12, 2024" accanto a "Oggi" della griglia.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class ViewerFormatEnglishDeviceTest {

  /** Il costruttore di default: quello che usa il viewer. */
  private val format = ViewerFormat(ApplicationProvider.getApplicationContext())

  private val capture = CaptureTime(
    utcMillis = 1_710_243_660_000L,
    offsetMinutes = 60,
    confidence = CaptureTimeConfidence.EXACT,
    source = CaptureTimeSource.EXIF_WITH_OFFSET,
  )

  @Test
  fun dateEOreInItalianoAncheConIlTelefonoInInglese() {
    assertThat(format.dateTitle(capture)).isEqualTo("12 marzo 2024")
    assertThat(format.time(capture)).isEqualTo("12:41")
    assertThat(format.dateTimeLine(capture)).isEqualTo("martedì 12 marzo 2024, 12:41")
    assertThat(format.dateTitle(20240312)).isEqualTo("12 marzo 2024")
  }

  @Test
  fun iNumeriUsanoLaVirgolaItaliana() {
    val details = MediaDetails(id = MediaId.random(), kind = MediaKind.PHOTO, width = 2_000, height = 1_500)
    // 3 MP: sotto i 10 si scrive con un decimale, con la virgola.
    assertThat(format.dimensions(details)).contains("3,0")
    assertThat(format.fileSize(3_400_000L)).contains(",")
  }
}
