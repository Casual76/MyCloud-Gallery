package dev.pampa.mycloudgallery.core.model.domain

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.VersionKind
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** I campi derivati dell'indice della timeline: giorno locale, proporzione, flag, copertina dei gruppi. */
class TimelineRulesTest {

  private fun utc(zoned: String): Long = ZonedDateTime.parse(zoned).toInstant().toEpochMilli()

  // --- Giorno locale (E-P5, E-M14) ----------------------------------------------------------------

  /** Lo stesso istante e' in giorni diversi a New York e a Roma: conta l'ora del posto dello scatto. */
  @Test
  fun ilGiornoLocaleUsaIlFusoDelloScatto() {
    val instant = utc("2026-03-12T23:30-05:00") // 13 marzo: 05:30 a Roma, 13:30 a Tokyo
    val newYork = CaptureTime(instant, -5 * 60, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
    val rome = CaptureTime(instant, 60, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
    val tokyo = CaptureTime(instant, 9 * 60, CaptureTimeConfidence.OFFSET_ASSUMED, CaptureTimeSource.VIDEO_UTC)
    val kathmandu = CaptureTime(utc("2026-03-12T18:20Z"), 5 * 60 + 45, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)

    assertThat(TimelineRules.localDay(newYork)).isEqualTo(20260312)
    assertThat(TimelineRules.localDay(rome)).isEqualTo(20260313)
    assertThat(TimelineRules.localDay(tokyo)).isEqualTo(20260313)
    assertThat(TimelineRules.localDay(kathmandu)).isEqualTo(20260313) // 00:05 locali
  }

  @Test
  fun ilGiornoProvvisorioUsaIlFusoDelTelefonoAllaLetturaAncheConLOraLegale() {
    val summer = utc("2026-07-01T22:30Z") // 00:30 a Roma (UTC+2), 23:30 a Londra (UTC+1)
    assertThat(TimelineRules.localDay(summer, ZoneId.of("Europe/Rome"))).isEqualTo(20260702)
    assertThat(TimelineRules.localDay(summer, ZoneId.of("Europe/London"))).isEqualTo(20260701)
    val winter = utc("2026-01-01T23:30Z") // 00:30 a Roma (UTC+1)
    assertThat(TimelineRules.localDay(winter, ZoneId.of("Europe/Rome"))).isEqualTo(20260102)
    assertThat(TimelineRules.localDay(winter, ZoneId.of("UTC"))).isEqualTo(20260101)
  }

  @Test
  fun iGiorniPrimaDel1970EFuoriScalaNonRompono() {
    assertThat(TimelineRules.localDay(utc("1969-12-31T23:59Z"), 0)).isEqualTo(19691231)
    assertThat(TimelineRules.localDay(Long.MAX_VALUE / 2, 0)).isEqualTo(99991231)
    assertThat(TimelineRules.localDay(Long.MIN_VALUE / 2, 0)).isEqualTo(10101)
  }

  // --- Proporzione (E-V3, E-M15) -----------------------------------------------------------------

  @Test
  fun laProporzioneEGiaOrientata() {
    fun aspect(w: Int, h: Int, exif: Int): Int {
      val o = MediaMetadata.orientationOf(exif)
      return TimelineRules.aspectMilli(MediaMetadata(width = w, height = h, orientationDegrees = o.degrees, mirrored = o.mirrored))
    }
    // 4000x3000 memorizzata: dritta e' 4:3, ruotata di 90 o 270 (o specchiata 5/7) e' 3:4.
    assertThat(aspect(4000, 3000, 1)).isEqualTo(1333)
    assertThat(aspect(4000, 3000, 2)).isEqualTo(1333)
    assertThat(aspect(4000, 3000, 3)).isEqualTo(1333)
    assertThat(aspect(4000, 3000, 6)).isEqualTo(750)
    assertThat(aspect(4000, 3000, 8)).isEqualTo(750)
    assertThat(aspect(4000, 3000, 5)).isEqualTo(750)
    assertThat(aspect(4000, 3000, 7)).isEqualTo(750)
    assertThat(aspect(1920, 1080, 1)).isEqualTo(1778)
    assertThat(aspect(1920, 1080, 6)).isEqualTo(563)
    assertThat(aspect(9000, 3000, 1)).isEqualTo(3000)
    // Senza dimensioni (prima di META): un quadrato.
    assertThat(TimelineRules.aspectMilli(null)).isEqualTo(1000)
    assertThat(TimelineRules.aspectMilli(MediaMetadata(width = 100))).isEqualTo(1000)
    // Valori assurdi restano nei limiti.
    assertThat(TimelineRules.aspectMilli(MediaMetadata(width = 1, height = 100_000))).isEqualTo(TimelineRules.MIN_ASPECT_MILLI)
    assertThat(TimelineRules.aspectMilli(MediaMetadata(width = Int.MAX_VALUE, height = 1))).isEqualTo(TimelineRules.MAX_ASPECT_MILLI)
  }

  // --- Flag ---------------------------------------------------------------------------------------

  @Test
  fun iFlagDellaCellaVengonoDaVersioneMetadatiStatiEDatiPersonali() {
    val estimated = CaptureTime(0, 0, CaptureTimeConfidence.ESTIMATED, CaptureTimeSource.FILE_NAME)
    val rich = MediaMetadata(
      captureTime = estimated,
      place = PlaceRef(1, 1),
      traits = setOf(MediaTrait.PANORAMA, MediaTrait.ULTRA_HDR, MediaTrait.HDR_VIDEO),
    )

    assertThat(TimelineRules.cellFlags(MediaKind.PHOTO, VersionKind.ORIGINAL, null, favorite = false, unreadable = false)).isEqualTo(0)
    val all = TimelineRules.cellFlags(MediaKind.MOTION_PHOTO, VersionKind.EDITED, rich, favorite = true, unreadable = true)
    assertThat(all).isEqualTo(
      CellFlags.EDITED or CellFlags.DATE_ESTIMATED or CellFlags.UNREADABLE or CellFlags.HAS_PLACE or CellFlags.MOTION or
        CellFlags.PANORAMA or CellFlags.ULTRA_HDR or CellFlags.FAVORITE,
    )
    assertThat(TimelineRules.cellFlags(MediaKind.PHOTO, VersionKind.CORRECTED, null, false, false)).isEqualTo(CellFlags.EDITED)
  }

  // --- Gruppi (D20) -------------------------------------------------------------------------------

  @Test
  fun unGruppoERappresentatoDallaCopertinaODalMembroVisibilePiuRecente() {
    val jpeg = MediaId("00000000-0000-4000-8000-000000000001")
    val dng = MediaId("00000000-0000-4000-8000-000000000002")
    val third = MediaId("00000000-0000-4000-8000-000000000003")

    val both = listOf(GroupMember(jpeg, 100, true), GroupMember(dng, 100, true))
    assertThat(TimelineRules.groupCover(both, jpeg)).isEqualTo(jpeg)
    // Il JPEG cancellato dal telefono (o nascosto): il DNG resta nella Libreria.
    assertThat(TimelineRules.groupCover(listOf(GroupMember(jpeg, 100, false), GroupMember(dng, 100, true)), jpeg)).isEqualTo(dng)
    // Senza copertina scelta: il piu' recente, a parita' l'identita' maggiore (deterministico).
    assertThat(TimelineRules.groupCover(both + GroupMember(third, 99, true), null)).isEqualTo(dng)
    assertThat(TimelineRules.groupCover(listOf(GroupMember(jpeg, 100, false)), jpeg)).isNull()

    val flags = CellFlags.FAVORITE or CellFlags.GROUP_MEMBER
    assertThat(TimelineRules.withGroupRole(flags, inGroup = true, isCover = true)).isEqualTo(CellFlags.FAVORITE or CellFlags.GROUP_COVER)
    assertThat(TimelineRules.withGroupRole(flags, inGroup = true, isCover = false)).isEqualTo(CellFlags.FAVORITE or CellFlags.GROUP_MEMBER)
    assertThat(TimelineRules.withGroupRole(flags, inGroup = false, isCover = false)).isEqualTo(CellFlags.FAVORITE)
  }
}
