package dev.pampa.mycloudgallery.feature.nas.ui

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Come le schermate della 4B scrivono i numeri: **sempre in italiano**, qualunque lingua abbia il telefono (come
 * `GalleryNumbers` e `GalleryDates`, di cui usa le funzioni), e sempre da funzioni pure che si provano su JVM.
 */
internal object NasText {

  private val units = listOf("B", "kB", "MB", "GB", "TB")

  /**
   * Il peso come lo scrive il telefono: "312 kB", "4,1 GB", "1,8 TB". Unita' da 1.000 (quelle del telefono e dei dischi),
   * una cifra decimale sotto il 10 e nessuna sopra, con la virgola, e uno spazio che non va a capo fra il numero e l'unita'. Il riepilogo dei fogli ("Spostare 312 elementi, 4,1 GB")
   * e le pagine dello spazio dicono lo stesso numero con la stessa mano.
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

  /** Il peso con i separatori delle migliaia dove serve ("12.480 elementi" usa [GalleryNumbers]): per i numeri interi di conteggio. */
  fun count(value: Int): String = GalleryNumbers.format(value)

  /** "12 ottobre 2026" da un istante in millisecondi (l'ora del NAS dell'elenco), nel fuso del telefono. */
  fun date(epochMillis: Long): String = GalleryDates.date(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate())

  /** "14:32", 24 ore. */
  fun time(epochMillis: Long): String = GalleryDates.time(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime())

  /** Il percorso scritto per le persone: "Famiglia / Viaggi". Vuoto per la radice. */
  fun path(components: List<String>): String = components.joinToString(" / ")

  /** Come si presenta un file: serve a scegliere l'icona. Dal nome, mai dal contenuto (la cartella non e' stata letta). */
  fun kindOf(name: String): FileKind {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (extension) {
      "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "avif", "bmp", "dng", "arw", "cr2", "cr3", "nef", "orf", "raf", "rw2" -> FileKind.IMAGE
      "mp4", "mov", "m4v", "mkv", "avi", "3gp", "webm", "mts", "m2ts", "wmv" -> FileKind.VIDEO
      else -> FileKind.OTHER
    }
  }

  /** Lo spazio che non va a capo: "4,8" e "MB" restano insieme. */
  const val NBSP: Char = '\u00A0'

  private const val THOUSAND = 1_000.0
  private const val TEN = 10.0
}

/** Il genere di un file, per l'icona della riga. */
internal enum class FileKind { IMAGE, VIDEO, OTHER }

/** Un conteggio scritto come in tutta la galleria: il numero con il punto delle migliaia, il plurale scelto dal numero vero. */
@Composable
internal fun countText(@PluralsRes plural: Int, count: Int): String = pluralStringResource(plural, count, GalleryNumbers.format(count))
