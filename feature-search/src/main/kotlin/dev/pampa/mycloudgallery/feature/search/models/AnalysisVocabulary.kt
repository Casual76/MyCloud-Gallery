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
import androidx.compose.material.icons.rounded.SdStorage
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
  // XU2-5 (giro 4): la clessidra una volta, sull'anello; nel mirino l'analisi stessa (non una seconda clessidra grande).
  is LibraryAnalysisStatus.Queued -> AnalysisSign(GalleryIconStatus.Waiting, AnalysisGlyph, GalleryIconFamily.Search)
  // Le foto non ancora preparate non sono una pausa: si aspetta (la clessidra sull'anello, una volta: XU2-5) e nel mirino l'analisi, come in coda.
  is LibraryAnalysisStatus.Paused -> if (status.reason == LibraryAnalysisWait.PREPARATION) {
    AnalysisSign(GalleryIconStatus.Waiting, AnalysisGlyph, GalleryIconFamily.Search)
  } else {
    AnalysisSign(GalleryIconStatus.Paused, waitGlyph(status.reason), waitFamily(status.reason))
  }
  LibraryAnalysisStatus.AllReady -> AnalysisSign(GalleryIconStatus.Done, AnalysisGlyph, GalleryIconFamily.Search)
  LibraryAnalysisStatus.ModelMissing -> AnalysisSign(GalleryIconStatus.Attention, Icons.Rounded.Download, GalleryIconFamily.Search)
  LibraryAnalysisStatus.Disabled -> AnalysisSign(GalleryIconStatus.Stopped, Icons.Rounded.PowerSettingsNew, GalleryIconFamily.System)
  is LibraryAnalysisStatus.NotHere -> AnalysisSign(GalleryIconStatus.Stopped, Icons.Rounded.Devices, GalleryIconFamily.System)
  // XU-6: nel mirino l'icona del perche' (la memoria, il modello), non un triangolo generico.
  is LibraryAnalysisStatus.Problem -> AnalysisSign(GalleryIconStatus.Problem, problemGlyph(status.code), GalleryIconFamily.Danger)
  LibraryAnalysisStatus.Idle -> AnalysisSign(null, AnalysisGlyph, GalleryIconFamily.Search)
}

/** I codici dei problemi che hanno un perche' corto e un'icona loro; gli altri dicono la frase del catalogo e il triangolo. */
private val StorageCodes = setOf("AI_STORAGE_LOW", "AI_MODEL_NO_SPACE", "AI_KILLED_MEMORY")
private val ModelCodes = setOf("AI_MODEL_FETCH_FAILED", "AI_MODEL_MISMATCH", "AI_MODEL_MISSING")

private fun problemGlyph(code: String): ImageVector = when (code) {
  in StorageCodes -> Icons.Rounded.SdStorage
  in ModelCodes -> Icons.Rounded.Download
  else -> Icons.Rounded.Warning
}

/** Il perche' corto di un problema per la riga della testata (XU-6): due o tre parole, o la frase del catalogo. */
@Composable
private fun problemLine(code: String): String = when (code) {
  in StorageCodes -> stringResource(R.string.analysis_problem_storage)
  in ModelCodes -> stringResource(R.string.analysis_problem_model)
  else -> SearchCodes.message(LocalResources.current, DiagCode(code))
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

/** Il motivo per cui l'analisi aspetta, a parole: con la batteria, col livello scelto («Batteria sotto il 60%»), mai «scarica» con la batteria al 55%. */
@Composable
internal fun analysisWaitText(reason: LibraryAnalysisWait): String =
  if (reason == LibraryAnalysisWait.BATTERY) dev.pampa.mycloudgallery.core.designsystem.state.batteryWaitText() else stringResource(analysisWaitRes(reason))

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

/**
 * XU2-4 e XU-12 (giro 4): **il tasto «Analizza ora» c'e'** solo dove una richiesta dell'utente fa davvero partire l'analisi, e vale per le due
 * pagine (la testata di "Analisi delle foto" e quella di Ricerca e modelli). Una richiesta supera la fascia oraria, la carica, la batteria, il
 * risparmio energetico, il caldo leggero (oltre la soglia dura il motore non parte comunque, e la testata lo dice), il limite di Android e la coda
 * (`RuleEvaluator.userInitiated`); **non** supera il NAS che non risponde, il Wi-Fi (di casa o no), i dati del mese finiti (chiede una conferma
 * che questo tasto non da'), il profilo non attivo, l'attesa generica e le foto ancora in preparazione (non c'e' niente da far partire). Mai in corso,
 * con tutto pronto, su un altro dispositivo, spenta, senza modello, con un problema (che ha il suo tasto) o senza stato.
 */
internal fun analyzeNowShown(status: LibraryAnalysisStatus): Boolean = when (status) {
  is LibraryAnalysisStatus.Queued -> true
  is LibraryAnalysisStatus.Paused -> when (status.reason) {
    LibraryAnalysisWait.NIGHT_WINDOW,
    LibraryAnalysisWait.CHARGING,
    LibraryAnalysisWait.BATTERY,
    LibraryAnalysisWait.POWER_SAVER,
    LibraryAnalysisWait.THERMAL,
    LibraryAnalysisWait.ANDROID_LIMIT,
    -> true
    LibraryAnalysisWait.WIFI,
    LibraryAnalysisWait.HOME_WIFI,
    LibraryAnalysisWait.NAS,
    LibraryAnalysisWait.DATA_CAP,
    LibraryAnalysisWait.PREPARATION,
    LibraryAnalysisWait.PROFILE,
    LibraryAnalysisWait.OTHER,
    -> false
  }
  LibraryAnalysisStatus.Idle,
  is LibraryAnalysisStatus.Running,
  LibraryAnalysisStatus.AllReady,
  is LibraryAnalysisStatus.NotHere,
  LibraryAnalysisStatus.Disabled,
  LibraryAnalysisStatus.ModelMissing,
  is LibraryAnalysisStatus.Problem,
  -> false
}

/** L'etichetta della testata: due parole. Null = niente da dire. */
@Composable
internal fun analysisLabel(status: LibraryAnalysisStatus): String? = when (status) {
  LibraryAnalysisStatus.Idle -> null
  is LibraryAnalysisStatus.Running -> stringResource(R.string.analysis_hero_running)
  is LibraryAnalysisStatus.Queued -> stringResource(R.string.analysis_hero_queued)
  // XU2-5: le foto in preparazione non sono una pausa (il segno e' la clessidra): si aspetta.
  is LibraryAnalysisStatus.Paused ->
    stringResource(if (status.reason == LibraryAnalysisWait.PREPARATION) R.string.analysis_hero_waiting else R.string.analysis_hero_paused)
  LibraryAnalysisStatus.AllReady -> stringResource(R.string.analysis_hero_ready)
  LibraryAnalysisStatus.ModelMissing -> stringResource(R.string.analysis_hero_model)
  LibraryAnalysisStatus.Disabled -> stringResource(R.string.analysis_hero_disabled)
  is LibraryAnalysisStatus.NotHere -> stringResource(R.string.analysis_hero_not_here)
  // XU-6: due parole fisse; il perche' sta nella riga, come nel backup.
  is LibraryAnalysisStatus.Problem -> stringResource(R.string.analysis_hero_problem)
}

/** La riga sotto l'etichetta (al piu' una) e la sua icona davanti (il perche' di una pausa). */
@Immutable
internal class AnalysisLine(val text: String, val glyph: ImageVector? = null)

@Composable
internal fun analysisLine(status: LibraryAnalysisStatus): AnalysisLine? = when (status) {
  // XU-1: niente minuti (il motore stima solo il mese in lavorazione, non tutto il lavoro) e niente «Restano N» quando l'anello dice gia' pronte su
  // totale: due misure diverse dello stesso lavoro nello stesso riquadro non tornano. Il conto resta solo se l'anello non ha numeri.
  is LibraryAnalysisStatus.Running -> status.remaining?.takeIf { it > 0 && readyOf(status) == null }?.let {
    AnalysisLine(stringResource(R.string.analysis_line_remaining, GalleryNumbers.format(it)))
  }
  is LibraryAnalysisStatus.Queued -> AnalysisLine(stringResource(R.string.analysis_line_queued))
  is LibraryAnalysisStatus.Paused -> AnalysisLine(analysisWaitText(status.reason), waitGlyph(status.reason))
  LibraryAnalysisStatus.AllReady -> AnalysisLine(stringResource(R.string.analysis_line_ready))
  LibraryAnalysisStatus.Disabled -> AnalysisLine(stringResource(R.string.analysis_line_disabled))
  is LibraryAnalysisStatus.NotHere -> AnalysisLine(stringResource(R.string.analysis_line_not_here))
  is LibraryAnalysisStatus.Problem -> AnalysisLine(problemLine(status.code))
  LibraryAnalysisStatus.ModelMissing -> AnalysisLine(stringResource(R.string.analysis_line_model))
  LibraryAnalysisStatus.Idle -> null
}

/**
 * La frase intera per TalkBack: lo stato e, dove l'anello non ha gia' i numeri, quante foto restano (un numero vero o niente). [withCount] falso per
 * la regione viva (XU-17): si annuncia il cambio di stato, non il conto che cambia a ogni foto.
 */
internal fun analysisSentence(resources: Resources, status: LibraryAnalysisStatus, withCount: Boolean = true): String {
  val rest = remainingOf(status)?.takeIf { withCount && readyOf(status) == null }?.let { resources.getString(R.string.analysis_remaining, GalleryNumbers.format(it)) } ?: ""
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
    is LibraryAnalysisStatus.Problem -> resources.getString(R.string.analysis_sentence_problem, SearchCodes.message(resources, DiagCode(status.code)))
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
