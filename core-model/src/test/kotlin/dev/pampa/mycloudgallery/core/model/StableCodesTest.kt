package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/** I codici salvati nel database sono un contratto: unici, stabili, nel formato ammesso. */
class StableCodesTest {

  private val all: Map<String, List<StableCode>> = mapOf(
    "MediaKind" to MediaKind.entries,
    "MediaGroupKind" to MediaGroupKind.entries,
    "MediaSource" to MediaSource.entries,
    "LocationType" to LocationType.entries,
    "LocationState" to LocationState.entries,
    "NasArea" to NasArea.entries,
    "VersionKind" to VersionKind.entries,
    "CorrectionField" to CorrectionField.entries,
    "CaptureTimeSource" to CaptureTimeSource.entries,
    "CaptureTimeConfidence" to CaptureTimeConfidence.entries,
    "WorkStage" to WorkStage.entries,
    "WorkStatus" to WorkStatus.entries,
    "VectorPrecision" to VectorPrecision.entries,
    "Presence" to Presence.entries,
    "SafetyStatus" to SafetyStatus.entries,
  )

  @Test
  fun ogniCodiceEUnicoNelSuoEnumENelFormatoAmmesso() {
    all.forEach { (name, values) ->
      val codes = values.map { it.code }
      assertWithMessage(name).that(codes.toSet().size).isEqualTo(codes.size)
      codes.forEach { assertWithMessage(name).that(it).matches("^[a-z0-9_]{1,32}$") }
    }
  }

  /**
   * Fotografia dei codici: chi rinomina o cambia un codice gia' salvato rompe i database esistenti.
   * Aggiungere valori va bene (si aggiorna questa lista); cambiarli no.
   */
  @Test
  fun iCodiciGiaSalvatiNonCambiano() {
    assertThat(MediaKind.entries.map { it.code }).containsAtLeast("photo", "video", "motion_photo", "raw_photo", "animated_image")
    assertThat(LocationState.entries.map { it.code }).containsAtLeast("present", "absent", "unavailable")
    assertThat(VersionKind.entries.map { it.code }).containsAtLeast("original", "edited", "corrected")
    assertThat(WorkStage.entries.map { it.code }).containsAtLeast("meta", "thumb", "analysis_copy", "embed", "ocr", "faces")
    assertThat(SafetyStatus.entries.map { it.code })
      .containsAtLeast("safe", "unverified", "only_common_copy", "no_nas_copy", "not_mine", "lost")
  }

  @Test
  fun decodificaRigorosaETollerante() {
    assertThat(enumByCode<WorkStatus>("gave_up")).isEqualTo(WorkStatus.GAVE_UP)
    assertThrows(IllegalArgumentException::class.java) { enumByCode<WorkStatus>("GAVE_UP") }
    assertThat(enumByCodeOrNull<MediaSource>("telegram")).isNull()
    assertThat(enumByCodeOrNull<MediaSource>(null)).isNull()
  }
}
