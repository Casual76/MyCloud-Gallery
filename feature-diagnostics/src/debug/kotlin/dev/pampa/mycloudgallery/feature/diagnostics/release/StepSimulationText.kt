package dev.pampa.mycloudgallery.feature.diagnostics.release

import java.util.Locale

/**
 * I testi in italiano della simulazione (schermata e resoconto da copiare). Solo numeri, nomi delle quattro aree, cartelle di primo livello del
 * formato e frasi fisse: nessun nome di file, di dispositivo o di album (i tipi di [StepReport] non li possono portare).
 */
object StepSimulationText {

  const val TITLE: String = "Simula il passo"
  const val READ_ONLY_TITLE: String = "Sola lettura"
  const val READ_ONLY_MESSAGE: String =
    "Non scrive niente sul NAS né sul telefono: legge i numeri che ha già e lo spazio libero, e li mostra qui. " +
      "Nessun nome di file: solo numeri e le cartelle di primo livello del formato."
  const val RUN: String = "Simula adesso"
  const val RUNNING: String = "Sto leggendo i numeri..."
  const val COPY: String = "Copia il resoconto"
  const val COPIED: String = "Resoconto copiato"
  const val CHECK_TITLE: String = "Da controllare"
  const val INFO_TITLE: String = "Da sapere"
  const val TOTAL_TITLE: String = "Che cosa scriverebbe"
  const val SUM_TITLE: String = "Totale"
  const val SPACE_TITLE: String = "Spazio libero sul NAS"
  const val NOT_RUN_FOOTNOTE: String = "Il passo spegne le quattro recinzioni insieme: i numeri qui sotto sono quelli che comincerebbero a scriversi."

  fun areaName(area: StepArea): String = when (area) {
    StepArea.INDEX -> "Indice e dati personali"
    StepArea.BACKUP -> "Backup"
    StepArea.FACES -> "Dati dei volti"
    StepArea.ALBUMS -> "Album condivisi"
  }

  fun placeName(place: StepPlace): String = when (place) {
    StepPlace.PRIVATE_AREA -> "area privata"
    StepPlace.COMMON_AREA -> "area comune"
  }

  fun certaintyName(certainty: StepCertainty): String = when (certainty) {
    StepCertainty.EXACT -> "numeri contati"
    StepCertainty.ESTIMATE -> "stima"
  }

  fun idleText(why: StepIdle): String = when (why) {
    StepIdle.FEATURE_OFF -> "Non attivo su questo dispositivo: non scrive niente"
    StepIdle.NOTHING_TO_DO -> "Niente da scrivere adesso"
    StepIdle.ON_USER_ACTION -> "Scrive solo quando condividi un album"
  }

  fun unavailableText(why: StepUnavailable): String = when (why) {
    StepUnavailable.NOT_READY -> "Numeri non disponibili: l'app non è ancora pronta (famiglia o NAS)"
    StepUnavailable.FAILED -> "Numeri non disponibili: la lettura è fallita"
  }

  fun attentionText(attention: StepAttention): String = when (attention) {
    StepAttention.AREA_UNAVAILABLE -> "Un'area non ha dato i suoi numeri: il passo non parte"
    StepAttention.SPACE_UNKNOWN -> "Lo spazio libero del NAS non si è letto: il passo non parte"
    StepAttention.SPACE_BELOW_MARGIN -> "Dopo il passo resterebbe meno di 1 GB libero sul NAS: il passo non parte"
    StepAttention.ESTIMATES_PRESENT -> "Alcuni numeri sono stime: la prima scrittura vera dirà i numeri esatti"
  }

  /** Il titolo e il dettaglio di una riga della schermata per [forecast]. */
  fun title(forecast: StepForecast): String = areaName(forecast.area)

  fun detail(forecast: StepForecast): String = when (forecast) {
    is StepForecast.Idle -> idleText(forecast.why)
    is StepForecast.Unavailable -> unavailableText(forecast.why)
    is StepForecast.Writes -> {
      val places = forecast.writes.joinToString("; ") { w -> "${placeName(w.place)}, ${w.folder.label}: ${files(w.files)}, ${bytes(w.bytes)}" }
      "${files(forecast.files)}, ${bytes(forecast.bytes)} (${certaintyName(forecast.certainty)}). $places"
    }
  }

  fun files(count: Long): String = if (count == 1L) "1 file" else "${number(count)} file"

  fun number(value: Long): String = String.format(Locale.ITALIAN, "%,d", value)

  /** Byte in forma leggibile (MB e GB con una cifra dopo la virgola, base 1024 come il NAS). */
  fun bytes(value: Long): String {
    val v = value.toDouble()
    return when {
      value < 1024L -> "$value B"
      value < 1024L * 1024 -> String.format(Locale.ITALIAN, "%.0f KB", v / 1024)
      value < 1024L * 1024 * 1024 -> String.format(Locale.ITALIAN, "%.1f MB", v / (1024 * 1024))
      else -> String.format(Locale.ITALIAN, "%.1f GB", v / (1024.0 * 1024 * 1024))
    }
  }

  /** Il resoconto da copiare e da mandare all'orchestratore: una riga per area, i totali, lo spazio e le cose da guardare. Niente nomi di file. */
  fun export(report: StepReport): String = buildString {
    appendLine("Simulazione del passo (sola lettura)")
    for (forecast in report.forecasts) appendLine("${forecast.area.fence} ${title(forecast)}: ${detail(forecast)}")
    appendLine("Totale: ${files(report.totalFiles)}, ${bytes(report.totalBytes)}")
    val space = report.space
    if (space == null) appendLine("Spazio libero sul NAS: non letto")
    else appendLine("Spazio libero sul NAS: ${bytes(space.freeBytes)}; dopo il passo: ${bytes(report.freeAfterBytes ?: 0L)}")
    for (a in report.attention) appendLine("- ${attentionText(a)}")
    if (report.attention.none { it.blocking }) appendLine("Nessuna cosa che ferma il passo: decide l'utente con questi numeri davanti.")
  }.trimEnd()
}
