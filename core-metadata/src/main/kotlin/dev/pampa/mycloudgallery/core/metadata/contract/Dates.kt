package dev.pampa.mycloudgallery.core.metadata.contract

import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.contract.TimeZoneLookup
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// La data di scatto (docs/fase3-progetto.md §2.2, tabella "Fonte della data"). Le firme sono il contratto che
// `core-mediastore` e le schermate usano gia' da P0; il corpo di CaptureTimeResolver e FileNameDates e' di P3.

/**
 * Tutto cio' che il file e il MediaStore dicono sulla data, **non ancora interpretato**. Il resolver sceglie la
 * fonte migliore e il fuso. Ogni campo puo' mancare.
 *
 * @property exifLocal `DateTimeOriginal`: l'ora locale che segnava la fotocamera, senza fuso.
 * @property exifOffsetMinutes `OffsetTimeOriginal`, se c'e' (in minuti).
 * @property exifSubSecondMs `SubSecTimeOriginal`, per l'ordine delle raffiche.
 * @property gpsUtc `GPSDateStamp` + `GPSTimeStamp`: un istante UTC affidabile, da cui si ricava il fuso.
 * @property gps il punto GPS (sensibile), per il fuso del luogo.
 * @property videoUtcMs la data UTC del contenitore di un video (E-M14).
 * @property videoGps il punto ISO 6709 del video, se c'e'.
 * @property fileName la data letta dal nome del file.
 * @property mediaStoreDateTakenMs `DATE_TAKEN` di MediaStore.
 * @property fileModifiedMs la data di modifica del file: l'ultimo ripiego (E-M8).
 * @property scanZone il fuso del telefono alla lettura (E-P5).
 * @property nowMs l'orologio di riferimento: le date oltre "ora + 48 h" sono assurde (E-M9).
 */
class CaptureTimeInputs(
  val exifLocal: LocalDateTime? = null,
  val exifOffsetMinutes: Int? = null,
  val exifSubSecondMs: Int? = null,
  val gpsUtc: Instant? = null,
  val gps: GeoPoint? = null,
  val videoUtcMs: Long? = null,
  val videoGps: GeoPoint? = null,
  val fileName: FileNameDate? = null,
  val mediaStoreDateTakenMs: Long? = null,
  val fileModifiedMs: Long? = null,
  val scanZone: ZoneId,
  val nowMs: Long,
) {
  // Contiene punti GPS: niente nel toString.
  override fun toString(): String = "CaptureTimeInputs([dati nascosti])"
}

/**
 * Il fuso dei file "vicini" (stessa cartella e stesso modello, entro 6 ore, con fuso esatto): per un'immagine senza
 * fuso e senza GPS e' la miglior ipotesi, meglio di quella del telefono di oggi (E-P5).
 */
data class NeighborOffsets(val offsetMinutes: Int) {
  init {
    require(offsetMinutes in -18 * 60..18 * 60) { "Fuso orario fuori dall'intervallo ±18 h." }
  }
}

/** Da quale schema di nome viene una data (tabella dei nomi dei file, §2.2). */
enum class FileNamePattern {
  WHATSAPP,
  SCREENSHOT,
  SCREEN_RECORDING,
  SAMSUNG_CAMERA,
  AOSP_CAMERA,
  PIXEL,
  SIGNAL,
  TELEGRAM,
}

/**
 * La data letta da un nome di file. [time] manca per WhatsApp (il nome porta il giorno della **ricezione**, quindi
 * la data e' `ESTIMATED`); [isUtc] vale per i Pixel (`PXL_...` e' in UTC) e per nessun altro.
 */
data class FileNameDate(
  val date: LocalDate,
  val time: LocalTime?,
  val isUtc: Boolean,
  val pattern: FileNamePattern,
)

/**
 * I nomi dei file che portano una data (WhatsApp, Screenshot, Pixel, Signal, Telegram, fotocamere Samsung e AOSP).
 *
 * **Segnaposto di P0.** Non riconosce nessun nome: l'esito "non riconosciuto" e' sempre sicuro, perche' il resolver
 * passa alla fonte successiva. P3 lo sostituisce con la tabella completa del §2.2 senza cambiare la firma.
 */
object FileNameDates {
  fun parse(name: String): FileNameDate? = null
}

/**
 * Sceglie la data di scatto: l'ordine delle fonti, il fuso, le date assurde. E' la tabella del §2.2.
 *
 * **Segnaposto di P0.** Usa `DATE_TAKEN` e poi la data del file, con il fuso del telefono alla lettura: quanto basta
 * perche' il resto del sistema abbia sempre un [CaptureTime] valido (`OFFSET_ASSUMED` o `ESTIMATED`, mai `EXACT`).
 * P3 lo sostituisce con la tabella completa (EXIF con fuso, GPS, vicini, video UTC, nomi dei file, date assurde)
 * senza cambiare la firma.
 */
object CaptureTimeResolver {

  fun resolve(c: CaptureTimeInputs, zones: TimeZoneLookup, neighbors: NeighborOffsets?): CaptureTime {
    val taken = c.mediaStoreDateTakenMs
    if (taken != null && isPlausible(taken, c.nowMs)) {
      return CaptureTime(taken, offsetMinutesAt(c.scanZone, taken), CaptureTimeConfidence.OFFSET_ASSUMED, CaptureTimeSource.MEDIASTORE)
    }
    val modified = c.fileModifiedMs?.takeIf { isPlausible(it, c.nowMs) } ?: c.nowMs
    return CaptureTime(modified, offsetMinutesAt(c.scanZone, modified), CaptureTimeConfidence.ESTIMATED, CaptureTimeSource.FILE_MODIFIED)
  }

  /** Vuota, a zero o a meno di un giorno dall'epoca, oppure oltre "ora + 48 h": assurda (E-M9). */
  private fun isPlausible(ms: Long, nowMs: Long): Boolean = ms > ONE_DAY_MS && ms <= nowMs + 2 * ONE_DAY_MS

  private fun offsetMinutesAt(zone: ZoneId, ms: Long): Int = zone.rules.getOffset(Instant.ofEpochMilli(ms)).totalSeconds / 60

  private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
}
