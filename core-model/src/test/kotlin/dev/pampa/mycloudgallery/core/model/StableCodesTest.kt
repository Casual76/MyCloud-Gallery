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
    "MediaTrait" to MediaTrait.entries,
    "FreezeReason" to FreezeReason.entries,
    // Fase 6 (P0): i codici dei dati AI (salvati da P1, pubblicati da P2).
    "SpaceCode" to SpaceCode.entries,
    "VecSource" to VecSource.entries,
    "OcrSource" to OcrSource.entries,
    "OcrEngineCode" to OcrEngineCode.entries,
    // Fase 7 (P0): i codici degli album (salvati da P1 e P3, pubblicati da P2).
    "ShareLevel" to ShareLevel.entries,
    "AlbumKind" to AlbumKind.entries,
    "AlbumSort" to AlbumSort.entries,
    "TripKind" to TripKind.entries,
    // Fase 8 (P0): i codici dei volti (salvati da P6, pubblicati da P3) e il modo delle persone (ricerca e album smart, P10).
    "FaceSpaceCode" to FaceSpaceCode.entries,
    "FaceTier" to FaceTier.entries,
    "FaceSource" to FaceSource.entries,
    "FaceDetectorCode" to FaceDetectorCode.entries,
    "PeopleMode" to PeopleMode.entries,
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
    assertThat(MediaTrait.entries.associate { it.code to it.bit })
      .containsAtLeast("panorama", 1, "sphere", 2, "ultra_hdr", 4, "hdr_video", 8, "screen_recording", 16)
  }

  /** I tratti si salvano come maschera: un bit a testa, mai condiviso. */
  @Test
  fun ogniTrattoHaUnBitSoloENonCondiviso() {
    MediaTrait.entries.forEach { assertWithMessage(it.name).that(Integer.bitCount(it.bit)).isEqualTo(1) }
    assertThat(MediaTrait.entries.map { it.bit }.toSet()).hasSize(MediaTrait.entries.size)
    val some = setOf(MediaTrait.PANORAMA, MediaTrait.HDR_VIDEO)
    assertThat(MediaTrait.fromBits(MediaTrait.toBits(some))).isEqualTo(some)
    // Un bit di una versione futura dell'app si ignora.
    assertThat(MediaTrait.fromBits(MediaTrait.toBits(some) or (1 shl 30))).isEqualTo(some)
    assertThat(MediaTrait.fromBits(0)).isEmpty()
  }

  @Test
  fun decodificaRigorosaETollerante() {
    assertThat(enumByCode<WorkStatus>("gave_up")).isEqualTo(WorkStatus.GAVE_UP)
    assertThrows(IllegalArgumentException::class.java) { enumByCode<WorkStatus>("GAVE_UP") }
    assertThat(enumByCodeOrNull<MediaSource>("telegram")).isNull()
    assertThat(enumByCodeOrNull<MediaSource>(null)).isNull()
  }
}
