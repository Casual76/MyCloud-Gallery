package dev.pampa.mycloudgallery.feature.backup.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.backup.R
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Come le schermate del backup scrivono numeri, pesi, tempi e nomi: **sempre in italiano**, qualunque lingua abbia il telefono (come
 * `GalleryNumbers` e `GalleryDates`, di cui usa le funzioni), da funzioni pure che si provano su JVM. Solo numeri e codici (§4.3): il
 * nome di una cartella del telefono e' un dato dell'utente, si mostra a schermo ma non va in nessun log.
 */
internal object BackupText {

  private val units = listOf("B", "kB", "MB", "GB", "TB")

  /**
   * Il peso come lo scrive il telefono: "312 kB", "4,1 GB", "1,8 TB". Unita' da 1.000, una cifra decimale sotto il 10 e nessuna sopra,
   * con la virgola, e uno spazio che non va a capo fra il numero e l'unita'. Uguale a quello di Spazio NAS (`NasText.size`): il
   * riepilogo del primo backup e la pagina dello spazio dicono lo stesso numero con la stessa mano.
   */
  fun size(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    if (value < THOUSAND) return "$value${NBSP}B"
    var scaled = value.toDouble()
    var unit = 0
    while (scaled >= THOUSAND && unit < units.lastIndex) {
      scaled /= THOUSAND
      unit++
    }
    // 999,6 MB arrotondato fa 1000 MB: si passa all'unita' sopra.
    if (scaled.roundToLong() >= THOUSAND && unit < units.lastIndex) {
      scaled /= THOUSAND
      unit++
    }
    val tenths = (scaled * 10).roundToLong() / 10.0
    val text = if (tenths < TEN) String.format(Locale.ROOT, "%.1f", tenths).replace('.', ',') else scaled.roundToLong().toString()
    return "$text$NBSP${units[unit]}"
  }

  /** Un conteggio con il punto delle migliaia ("6.658"). */
  fun count(value: Int): String = GalleryNumbers.format(value)

  /** Il percento intero di [part] su [total], 0 se non c'e' un totale. */
  fun percent(part: Long, total: Long): Int = if (total <= 0) 0 else (part.coerceIn(0, total) * PERCENT / total).toInt()

  /** Il nome per le persone di una cartella del telefono ("Pictures/Telegram/" -> "Telegram"); vuoto se il percorso non dice niente. */
  fun folderName(relativePath: String): String = relativePath.trim('/').substringAfterLast('/')

  /** Lo spazio che non va a capo: "4,8" e "MB" restano insieme. */
  const val NBSP: Char = ' '

  private const val THOUSAND = 1_000.0
  private const val TEN = 10.0
  private const val PERCENT = 100L
}

/** "circa 2 h 36 min", "meno di un minuto", "circa 4 min": il tempo che manca, in ore e minuti. */
@Composable
internal fun etaText(etaMillis: Long): String {
  val minutes = ((etaMillis + MinuteMs - 1) / MinuteMs).toInt()
  return when {
    etaMillis < MinuteMs -> stringResource(R.string.backup_eta_under_minute)
    minutes < MinutesPerHour -> stringResource(R.string.backup_eta_minutes, GalleryNumbers.format(minutes))
    minutes % MinutesPerHour == 0 -> stringResource(R.string.backup_eta_hours_only, GalleryNumbers.format(minutes / MinutesPerHour))
    else -> stringResource(R.string.backup_eta_hours, GalleryNumbers.format(minutes / MinutesPerHour), GalleryNumbers.format(minutes % MinutesPerHour))
  }
}

/**
 * "12 ottobre 2026 alle 03:12": un istante in ora del NAS, mostrato come ora di casa. Sempre con la data: "Oggi" vorrebbe l'ora del
 * telefono, che nessuna schermata del backup legge (D31, `NoWallClockSourcesTest`). Il piede della Libreria, che ha la sua regola, dice
 * "Oggi alle 03:12".
 */
@Composable
internal fun atText(atNasMillis: Long): String {
  val at = Instant.ofEpochMilli(atNasMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
  return stringResource(R.string.backup_at_day, GalleryDates.date(at), GalleryDates.time(at))
}

/** Il nome di una sorgente come lo vede l'utente: "Fotocamera", "Screenshot", o la cartella ("Telegram"). */
@Composable
internal fun sourceName(source: BackupSource): String = when (source) {
  BackupSource.Camera -> stringResource(R.string.backup_source_camera)
  BackupSource.Screenshots -> stringResource(R.string.backup_source_screenshots)
  is BackupSource.Folder -> BackupText.folderName(source.relativePath).ifBlank { stringResource(R.string.backup_source_folder_other) }
}

/**
 * Il nome di una sorgente dal suo [BackupSource.id] (le chiavi di `BackupSummary.bySource`): `camera`, `screenshots` o
 * `folder:<volume>:<percorso>`.
 */
@Composable
internal fun sourceNameById(id: String): String = when {
  id == BackupSource.Camera.id -> stringResource(R.string.backup_source_camera)
  id == BackupSource.Screenshots.id -> stringResource(R.string.backup_source_screenshots)
  id.startsWith("folder:") -> BackupText.folderName(id.removePrefix("folder:").substringAfter(':', "")).ifBlank { stringResource(R.string.backup_source_folder_other) }
  else -> stringResource(R.string.backup_source_folder_other)
}

private const val MinuteMs = 60_000L
private const val MinutesPerHour = 60
