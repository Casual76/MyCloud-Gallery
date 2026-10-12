package dev.pampa.mycloudgallery.core.rules

import java.util.Locale

/**
 * Formattazione dei byte per l'utente, in italiano (virgola decimale) e con unita' decimali
 * (1 GB = 10^9 byte, come nei piani dati e nelle misure di Fase 0).
 */
object ByteFormat {

  private const val KB = 1_000.0
  private const val MB = 1_000_000.0
  private const val GB = 1_000_000_000.0

  /** "350 MB", "1,5 GB", "800 KB". */
  fun size(bytes: Long): String {
    val unit = unitFor(bytes)
    return "${number(bytes / unit.factor)} ${unit.label}"
  }

  /** "1,2/1 GB": consumato e tetto nella stessa unita' (quella del tetto). */
  fun usage(usedBytes: Long, capBytes: Long): String {
    val unit = unitFor(capBytes)
    return "${number(usedBytes / unit.factor)}/${number(capBytes / unit.factor)} ${unit.label}"
  }

  private class Unit(val factor: Double, val label: String)

  private fun unitFor(bytes: Long): Unit =
    when {
      bytes >= GB -> Unit(GB, "GB")
      bytes >= MB -> Unit(MB, "MB")
      else -> Unit(KB, "KB")
    }

  /** Una cifra decimale, tolta se e' zero: 1,2 - 1 - 350. */
  private fun number(value: Double): String {
    val text = String.format(Locale.ITALIAN, "%.1f", value)
    return text.removeSuffix(",0")
  }
}
