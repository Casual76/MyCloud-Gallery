package dev.pampa.mycloudgallery.feature.search.models

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EnergySavingsLeaf
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.search.R

// IL VOCABOLARIO DELL'ANALISI (Pacchetto X; grafica XU, 12/11/2026). Come si **dice** lo stato ridotto (`LibraryAnalysisStatus`, uno solo per tutti i
// posti) con le parole, le icone e i segni della galleria. Un solo vocabolario di segni, lo stesso della testata, del nastro, della nuvola e del
// visore: **in corso** = le frecce che girano (`Running`); **in coda** = la clessidra (`Waiting`); **in pausa** = le due barre (`Paused`) con l'icona del
// perche'; **pronto** = la spunta (`Done`); **tocca a te** = il punto esclamativo (`Attention`); **non si fa da qui / spento** = il trattino
// (`Stopped`); **un problema** = la croce (`Problem`). L'analisi stessa ha un'icona sola, la foto con la lente (`ImageSearch`), sulla piastrella della
// famiglia della ricerca. Mai «indice», «vettore», «embedding».

/** Il segno di uno stato: [mark] sull'anello (null = nessun segno), [glyph] l'icona del perche' e [family] la sua tinta. */
@Immutable
internal class AnalysisSign(val mark: GalleryIconStatus?, val glyph: ImageVector, val family: GalleryIconFamily)

/** L'icona dell'analisi delle foto: la foto con la lente. */
internal val AnalysisGlyph: ImageVector get() = Icons.Rounded.ImageSearch

internal fun analysisSign(status: LibraryAnalysisStatus): AnalysisSign = when (status) {
  is LibraryAnalysisStatus.Running -> AnalysisSign(GalleryIconStatus.Running, AnalysisGlyph, GalleryIconFamily.Search)
  is LibraryAnalysisStatus.Queued -> AnalysisSign(GalleryIconStatus.Waiting, Icons.Rounded.HourglassTop, GalleryIconFamily.System)
  is LibraryAnalysisStatus.Paused -> AnalysisSign(
    // Le foto non ancora preparate non sono una pausa: si aspetta (la clessidra), e riparte da sola.
    if (status.reason == LibraryAnalysisWait.PREPARATION) GalleryIconStatus.Waiting else GalleryIconStatus.Paused,
    waitGlyph(status.reason),
    waitFamily(status.reason),
  )
  LibraryAnalysisStatus.AllReady -> AnalysisSign(GalleryIconStatus.Done, AnalysisGlyph, GalleryIconFamily.Search)
  LibraryAnalysisStatus.ModelMissing -> AnalysisSign(GalleryIconStatus.Attention, Icons.Rounded.Download, GalleryIconFamily.Search)
  LibraryAnalysisStatus.Disabled -> AnalysisSign(GalleryIconStatus.Stopped, Icons.Rounded.PowerSettingsNew, GalleryIconFamily.System)
  is LibraryAnalysisStatus.NotHere -> AnalysisSign(GalleryIconStatus.Stopped, Icons.Rounded.Devices, GalleryIconFamily.System)
  is LibraryAnalysisStatus.Problem -> AnalysisSign(GalleryIconStatus.Problem, Icons.Rounded.Warning, GalleryIconFamily.Danger)
  LibraryAnalysisStatus.Idle -> AnalysisSign(null, AnalysisGlyph, GalleryIconFamily.Search)
}

/** L'icona del perche' di una pausa: il termometro, la batteria, la luna, il Wi-Fi spento... */
internal fun waitGlyph(reason: LibraryAnalysisWait): ImageVector = when (reason) {
  LibraryAnalysisWait.THERMAL -> Icons.Rounded.Thermostat
  LibraryAnalysisWait.CHARGING -> Icons.Rounded.BatteryChargingFull
  LibraryAnalysisWait.BATTERY -> Icons.Rounded.BatteryAlert
  LibraryAnalysisWait.POWER_SAVER -> Icons.Rounded.EnergySavingsLeaf
  LibraryAnalysisWait.NIGHT_WINDOW -> Icons.Rounded.Bedtime
  LibraryAnalysisWait.WIFI, LibraryAnalysisWait.HOME_WIFI -> Icons.Rounded.WifiOff
  LibraryAnalysisWait.NAS -> Icons.Rounded.CloudOff
  LibraryAnalysisWait.DATA_CAP -> Icons.Rounded.DataUsage
  LibraryAnalysisWait.PREPARATION -> Icons.Rounded.HourglassTop
  LibraryAnalysisWait.ANDROID_LIMIT, LibraryAnalysisWait.PROFILE, LibraryAnalysisWait.OTHER -> Icons.AutoMirrored.Rounded.Rule
}

private fun waitFamily(reason: LibraryAnalysisWait): GalleryIconFamily = when (reason) {
  LibraryAnalysisWait.THERMAL, LibraryAnalysisWait.BATTERY -> GalleryIconFamily.People
  LibraryAnalysisWait.WIFI, LibraryAnalysisWait.HOME_WIFI, LibraryAnalysisWait.NAS, LibraryAnalysisWait.DATA_CAP -> GalleryIconFamily.Network
  LibraryAnalysisWait.CHARGING, LibraryAnalysisWait.POWER_SAVER -> GalleryIconFamily.Places
  LibraryAnalysisWait.NIGHT_WINDOW -> GalleryIconFamily.Search
  else -> GalleryIconFamily.System
}

internal fun analysisWaitRes(reason: LibraryAnalysisWait): Int = when (reason) {
  LibraryAnalysisWait.NIGHT_WINDOW -> R.string.analysis_wait_window
  LibraryAnalysisWait.CHARGING -> R.string.analysis_wait_charging
  LibraryAnalysisWait.BATTERY -> R.string.analysis_wait_battery
  LibraryAnalysisWait.POWER_SAVER -> R.string.analysis_wait_power_saver
  LibraryAnalysisWait.THERMAL -> R.string.analysis_wait_thermal
  LibraryAnalysisWait.WIFI -> R.string.analysis_wait_wifi
  LibraryAnalysisWait.HOME_WIFI -> R.string.analysis_wait_home_wifi
  LibraryAnalysisWait.NAS -> R.string.analysis_wait_nas
  LibraryAnalysisWait.DATA_CAP -> R.string.analysis_wait_data_cap
  LibraryAnalysisWait.PREPARATION -> R.string.analysis_wait_preparation
  LibraryAnalysisWait.ANDROID_LIMIT -> R.string.analysis_wait_android
  LibraryAnalysisWait.PROFILE -> R.string.analysis_wait_profile
  LibraryAnalysisWait.OTHER -> R.string.analysis_wait_other
}

/** L'etichetta della testata: due parole. Null = niente da dire. */
@Composable
internal fun analysisLabel(status: LibraryAnalysisStatus): String? = when (status) {
  LibraryAnalysisStatus.Idle -> null
  is LibraryAnalysisStatus.Running -> stringResource(R.string.analysis_hero_running)
  is LibraryAnalysisStatus.Queued -> stringResource(R.string.analysis_hero_queued)
  is LibraryAnalysisStatus.Paused -> stringResource(R.string.analysis_hero_paused)
  LibraryAnalysisStatus.AllReady -> stringResource(R.string.analysis_hero_ready)
  LibraryAnalysisStatus.ModelMissing -> stringResource(R.string.analysis_hero_model)
  LibraryAnalysisStatus.Disabled -> stringResource(R.string.analysis_hero_disabled)
  is LibraryAnalysisStatus.NotHere -> stringResource(R.string.analysis_hero_not_here)
  is LibraryAnalysisStatus.Problem -> SearchCodes.message(LocalResources.current, DiagCode(status.code))
}

/** La riga sotto l'etichetta (al piu' una) e la sua icona davanti (il perche' di una pausa). */
@Immutable
internal class AnalysisLine(val text: String, val glyph: ImageVector? = null)

@Composable
internal fun analysisLine(status: LibraryAnalysisStatus): AnalysisLine? = when (status) {
  is LibraryAnalysisStatus.Running -> {
    val rest = status.remaining?.takeIf { it > 0 }?.let { GalleryNumbers.format(it) }
    val eta = status.etaMinutes
    when {
      rest != null && eta != null -> AnalysisLine(stringResource(R.string.analysis_line_remaining_eta, rest, eta))
      rest != null -> AnalysisLine(stringResource(R.string.analysis_line_remaining, rest))
      else -> null
    }
  }
  is LibraryAnalysisStatus.Queued -> AnalysisLine(stringResource(R.string.analysis_line_queued))
  is LibraryAnalysisStatus.Paused -> AnalysisLine(stringResource(analysisWaitRes(status.reason)), waitGlyph(status.reason))
  LibraryAnalysisStatus.AllReady -> AnalysisLine(stringResource(R.string.analysis_line_ready))
  LibraryAnalysisStatus.Disabled -> AnalysisLine(stringResource(R.string.analysis_line_disabled))
  is LibraryAnalysisStatus.NotHere -> AnalysisLine(stringResource(R.string.analysis_line_not_here))
  LibraryAnalysisStatus.ModelMissing, is LibraryAnalysisStatus.Problem, LibraryAnalysisStatus.Idle -> null
}

/** La frase intera per TalkBack: lo stato e, dove c'e', quante foto restano (un numero vero o niente). */
internal fun analysisSentence(resources: Resources, status: LibraryAnalysisStatus): String {
  val rest = remainingOf(status)?.let { resources.getString(R.string.analysis_remaining, GalleryNumbers.format(it)) } ?: ""
  return when (status) {
    LibraryAnalysisStatus.Idle -> ""
    is LibraryAnalysisStatus.Running -> resources.getString(R.string.analysis_sentence_running, rest).trim()
    is LibraryAnalysisStatus.Queued -> resources.getString(R.string.analysis_sentence_queued, rest).trim()
    is LibraryAnalysisStatus.Paused ->
      resources.getString(R.string.analysis_sentence_paused, resources.getString(analysisWaitRes(status.reason)).lowercase(resources.configuration.locales[0]))
    LibraryAnalysisStatus.AllReady -> resources.getString(R.string.analysis_sentence_ready)
    LibraryAnalysisStatus.ModelMissing -> resources.getString(R.string.analysis_sentence_model_missing)
    LibraryAnalysisStatus.Disabled -> resources.getString(R.string.analysis_sentence_disabled)
    is LibraryAnalysisStatus.NotHere -> resources.getString(R.string.analysis_sentence_not_here)
    is LibraryAnalysisStatus.Problem -> SearchCodes.message(resources, DiagCode(status.code))
  }
}

internal fun remainingOf(status: LibraryAnalysisStatus): Int? = when (status) {
  is LibraryAnalysisStatus.Running -> status.remaining
  is LibraryAnalysisStatus.Queued -> status.remaining
  is LibraryAnalysisStatus.Paused -> status.remaining
  else -> null
}

/** Le foto pronte sul totale, quando si sanno tutti e due (niente numero finto). */
internal fun readyOf(status: LibraryAnalysisStatus): Pair<Int, Int>? {
  val (ready, total) = when (status) {
    is LibraryAnalysisStatus.Running -> status.ready to status.total
    is LibraryAnalysisStatus.Queued -> status.ready to status.total
    is LibraryAnalysisStatus.Paused -> status.ready to status.total
    is LibraryAnalysisStatus.NotHere -> status.ready to status.total
    else -> null to null
  }
  if (ready == null || total == null || total <= 0) return null
  return ready.coerceIn(0, total) to total
}

/** Il percento che si scrive: 100 solo quando sono pronte tutte (mai «100%» con una foto che manca), come nel Backup. */
internal fun analysisPercent(ready: Int, total: Int): Int = when {
  total <= 0 -> 0
  ready >= total -> FULL_PERCENT
  else -> (ready.toLong() * FULL_PERCENT / total).toInt().coerceAtMost(FULL_PERCENT - 1)
}

private const val FULL_PERCENT = 100
