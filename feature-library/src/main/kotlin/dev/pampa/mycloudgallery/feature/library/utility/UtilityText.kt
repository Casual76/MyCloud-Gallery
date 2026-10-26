package dev.pampa.mycloudgallery.feature.library.utility

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Come le pagine dell'Utility scrivono un peso: **sempre in italiano**, qualunque lingua abbia il telefono (come `GalleryNumbers`), da una
 * funzione pura che si prova su JVM. Lo stesso modo delle pagine del NAS: "312 kB", "4,1 GB", "1,8 TB" (unita' da 1.000, una cifra decimale
 * sotto il 10 e nessuna sopra, la virgola, e uno spazio che non va a capo fra il numero e l'unita').
 */
internal object UtilityText {

  private val units = listOf("B", "kB", "MB", "GB", "TB")
  private const val THOUSAND = 1_000.0
  private const val TEN = 10.0
  private const val NBSP = '\u00A0'

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
}
