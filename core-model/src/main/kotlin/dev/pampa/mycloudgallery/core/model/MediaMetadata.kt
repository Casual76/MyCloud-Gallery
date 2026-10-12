package dev.pampa.mycloudgallery.core.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Da dove viene la data di scatto: serve a spiegarla nel pannello info e a scegliere la migliore. */
enum class CaptureTimeSource(override val code: String) : StableCode {
  /** EXIF `DateTimeOriginal` con `OffsetTimeOriginal`: ora locale e fuso noti. */
  EXIF_WITH_OFFSET("exif_offset"),

  /** EXIF senza fuso: l'ora locale e' certa, il fuso e' stato supposto. */
  EXIF_LOCAL("exif_local"),

  /** Contenitore video (MP4/MOV): l'istante e' in UTC, il fuso e' stato supposto (E-M14). */
  VIDEO_UTC("video_utc"),

  /** Dal nome del file (WhatsApp, Screenshot, fotocamere): spesso solo il giorno, o senza fuso. */
  FILE_NAME("file_name"),

  /** `DATE_TAKEN` di MediaStore quando l'EXIF non e' leggibile. */
  MEDIASTORE("mediastore"),

  /** Data di modifica del file: l'ultimo ripiego, sempre stimato (E-M8). */
  FILE_MODIFIED("file_modified"),

  /** Corretta a mano dall'utente (E-L8). */
  USER_CORRECTION("user_correction"),
}

/** Quanto ci si puo' fidare della data di scatto. */
enum class CaptureTimeConfidence(override val code: String) : StableCode {
  /** Istante e ora locale noti. */
  EXACT("exact"),

  /** Una delle due parti e' certa e il fuso e' supposto (vedi [CaptureTimeSource]). */
  OFFSET_ASSUMED("offset_assumed"),

  /** La data stessa e' approssimata (nome del file, data del file): in app e' marcata "stimata". */
  ESTIMATED("estimated"),
}

/**
 * La data di scatto, nella forma che serve davvero a una galleria.
 *
 * Due esigenze diverse (E-M14, E-P5): **ordinare** foto e video dello stesso viaggio, che richiede un
 * istante assoluto ([utcMillis]), e **mostrare** l'ora che c'era sul posto ("le 10 a Oslo"), che
 * richiede il fuso dello scatto ([offsetMinutes]). Il fuso c'e' sempre, anche quando e' supposto, cosi'
 * l'ora locale si ricostruisce senza dipendere dal fuso del telefono di oggi; [confidence] dice se e'
 * stato supposto e [source] da dove viene.
 */
data class CaptureTime(
  val utcMillis: Long,
  val offsetMinutes: Int,
  val confidence: CaptureTimeConfidence,
  val source: CaptureTimeSource,
) {

  init {
    require(offsetMinutes in -18 * 60..18 * 60) { "Fuso orario fuori dall'intervallo ±18 h." }
    require(source != CaptureTimeSource.FILE_MODIFIED || confidence == CaptureTimeConfidence.ESTIMATED) {
      "La data del file e' sempre una stima."
    }
    require(source != CaptureTimeSource.USER_CORRECTION || confidence == CaptureTimeConfidence.EXACT) {
      "Una data corretta dall'utente e' per definizione quella giusta."
    }
  }

  /** Il flag "stimata" del piano (3.1, E-M8). */
  val isEstimated: Boolean get() = confidence == CaptureTimeConfidence.ESTIMATED

  /** L'ora che segnava l'orologio sul posto. */
  val localDateTime: LocalDateTime
    get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(utcMillis), ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
}

/**
 * Un punto GPS. **Dato sensibile** (piano §7): rivela casa e abitudini. Il `toString()` non lo mostra,
 * cosi' non finisce in un log o in un messaggio d'errore per distrazione.
 */
@Sensitive("La posizione rivela dove si abita e dove si va.")
class GeoPoint(
  val latitude: Double,
  val longitude: Double,
  val altitudeMeters: Double? = null,
) {

  init {
    require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Coordinate fuori intervallo." }
  }

  override fun equals(other: Any?): Boolean =
    other is GeoPoint && other.latitude == latitude && other.longitude == longitude && other.altitudeMeters == altitudeMeters

  override fun hashCode(): Int = (latitude.hashCode() * 31 + longitude.hashCode()) * 31 + altitudeMeters.hashCode()

  override fun toString(): String = "GeoPoint([posizione nascosta])"
}

/**
 * I metadati di **una versione** (non del media): una correzione di data o luogo, o una rotazione,
 * produce una versione nuova con metadati nuovi, e l'originale conserva i suoi. Il media mostra quelli
 * della versione corrente.
 */
data class MediaMetadata(
  val mimeType: String? = null,
  val width: Int? = null,
  val height: Int? = null,
  /** Rotazione da applicare per mostrare il media dritto (EXIF/contenitore), E-V3. */
  val orientationDegrees: Int = 0,
  val durationMillis: Long? = null,
  val captureTime: CaptureTime? = null,
  @Sensitive("Posizione GPS dello scatto.")
  val location: GeoPoint? = null,
) {

  init {
    require(orientationDegrees in setOf(0, 90, 180, 270)) { "Rotazione non valida." }
    require((width == null || width > 0) && (height == null || height > 0)) { "Dimensioni non valide." }
    require(durationMillis == null || durationMillis >= 0) { "Durata negativa." }
  }
}
