package dev.pampa.mycloudgallery.feature.search.models

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.search.R

// Pacchetto X: come si **dice** lo stato ridotto dell'analisi (`LibraryAnalysisStatus`, un solo stato per tutti i posti): il segno, le due
// parole della riga e la frase intera per TalkBack. La testata di "Ricerca e modelli" e la pagina "Analisi delle foto" usano queste sole
// funzioni, cosi' lo stato si dice allo stesso modo ovunque — e una volta per pagina.

/** Il segno dello stato: in corso (`Running`), fatto (`Done`), in pausa con il segno del perche', in attesa (clessidra, nessun segno). */
internal fun analysisSign(status: LibraryAnalysisStatus): Triple<ImageVector, GalleryIconFamily, GalleryIconStatus?> = when (status) {
  is LibraryAnalysisStatus.Running -> Triple(Icons.Rounded.Timelapse, GalleryIconFamily.Search, GalleryIconStatus.Running)
  is LibraryAnalysisStatus.Queued -> Triple(Icons.Rounded.HourglassTop, GalleryIconFamily.System, null)
  is LibraryAnalysisStatus.Paused -> when (status.reason) {
    LibraryAnalysisWait.PREPARATION -> Triple(Icons.Rounded.HourglassTop, GalleryIconFamily.System, null)
    LibraryAnalysisWait.THERMAL -> Triple(Icons.Rounded.Thermostat, GalleryIconFamily.System, GalleryIconStatus.Paused)
    LibraryAnalysisWait.NAS -> Triple(Icons.Rounded.CloudOff, GalleryIconFamily.Network, GalleryIconStatus.Paused)
    LibraryAnalysisWait.WIFI, LibraryAnalysisWait.HOME_WIFI -> Triple(Icons.Rounded.WifiOff, GalleryIconFamily.Network, GalleryIconStatus.Paused)
    LibraryAnalysisWait.CHARGING, LibraryAnalysisWait.BATTERY, LibraryAnalysisWait.POWER_SAVER ->
      Triple(Icons.Rounded.BatteryAlert, GalleryIconFamily.System, GalleryIconStatus.Paused)
    LibraryAnalysisWait.NIGHT_WINDOW -> Triple(Icons.Rounded.Bedtime, GalleryIconFamily.System, GalleryIconStatus.Paused)
    LibraryAnalysisWait.DATA_CAP -> Triple(Icons.Rounded.SdStorage, GalleryIconFamily.System, GalleryIconStatus.Paused)
    else -> Triple(Icons.AutoMirrored.Rounded.Rule, GalleryIconFamily.Backup, GalleryIconStatus.Paused)
  }
  LibraryAnalysisStatus.AllReady -> Triple(Icons.Rounded.Memory, GalleryIconFamily.Search, GalleryIconStatus.Done)
  LibraryAnalysisStatus.ModelMissing -> Triple(Icons.Rounded.Download, GalleryIconFamily.Search, GalleryIconStatus.Attention)
  LibraryAnalysisStatus.Disabled -> Triple(Icons.Rounded.PowerSettingsNew, GalleryIconFamily.Search, GalleryIconStatus.Stopped)
  is LibraryAnalysisStatus.NotHere -> Triple(Icons.Rounded.PhoneAndroid, GalleryIconFamily.System, null)
  is LibraryAnalysisStatus.Problem -> Triple(Icons.Rounded.Warning, GalleryIconFamily.Danger, GalleryIconStatus.Problem)
  LibraryAnalysisStatus.Idle -> Triple(Icons.Rounded.Memory, GalleryIconFamily.Search, null)
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

/** Le due parole della riga (il perche' di una pausa sta tutto qui, in breve); null = niente riga. */
@Composable
internal fun analysisShortText(status: LibraryAnalysisStatus): String? = when (status) {
  LibraryAnalysisStatus.Idle -> null
  is LibraryAnalysisStatus.Running -> status.etaMinutes?.let { stringResource(R.string.analysis_status_running_eta, it) }
    ?: stringResource(R.string.analysis_status_running)
  is LibraryAnalysisStatus.Queued -> stringResource(R.string.analysis_status_queued)
  is LibraryAnalysisStatus.Paused -> stringResource(R.string.analysis_status_paused, stringResource(analysisWaitRes(status.reason)))
  LibraryAnalysisStatus.AllReady -> stringResource(R.string.analysis_status_ready)
  LibraryAnalysisStatus.ModelMissing -> stringResource(R.string.analysis_status_model_missing)
  LibraryAnalysisStatus.Disabled -> stringResource(R.string.analysis_status_disabled)
  is LibraryAnalysisStatus.NotHere -> stringResource(R.string.analysis_status_not_here)
  is LibraryAnalysisStatus.Problem -> SearchCodes.message(androidx.compose.ui.platform.LocalResources.current, DiagCode(status.code))
}

/** La frase intera per TalkBack: lo stato e, dove c'e', quante foto restano (un numero vero o niente). */
internal fun analysisSentence(resources: Resources, status: LibraryAnalysisStatus): String {
  val rest = remainingOf(status)?.let { resources.getString(R.string.analysis_remaining, GalleryNumbers.format(it)) } ?: ""
  return when (status) {
    LibraryAnalysisStatus.Idle -> ""
    is LibraryAnalysisStatus.Running -> resources.getString(R.string.analysis_sentence_running, rest).trim()
    is LibraryAnalysisStatus.Queued -> resources.getString(R.string.analysis_sentence_queued, rest).trim()
    is LibraryAnalysisStatus.Paused -> resources.getString(R.string.analysis_sentence_paused, resources.getString(analysisWaitRes(status.reason)))
    LibraryAnalysisStatus.AllReady -> resources.getString(R.string.analysis_sentence_ready)
    LibraryAnalysisStatus.ModelMissing -> resources.getString(R.string.analysis_sentence_model_missing)
    LibraryAnalysisStatus.Disabled -> resources.getString(R.string.analysis_sentence_disabled)
    is LibraryAnalysisStatus.NotHere -> resources.getString(R.string.analysis_sentence_not_here)
    is LibraryAnalysisStatus.Problem -> SearchCodes.message(resources, DiagCode(status.code))
  }
}

/**
 * La riga di stato nella testata di "Ricerca e modelli": il segno, due parole e la freccia; il tocco apre la pagina "Analisi delle foto".
 * TalkBack sente la frase intera e che si apre, senza dover toccare.
 */
@Composable
internal fun AnalysisHeroLine(status: LibraryAnalysisStatus, resources: Resources, onOpen: () -> Unit) {
  val short = analysisShortText(status) ?: return
  val (glyph, _, _) = analysisSign(status)
  val openLabel = stringResource(R.string.analysis_open)
  val spoken = analysisSentence(resources, status)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .defaultMinSize(minWidth = GalleryDimens.MinTouchTarget, minHeight = GalleryDimens.MinTouchTarget)
      .fluidPressable(onClick = onOpen, role = Role.Button)
      .semantics(mergeDescendants = true) {
        role = Role.Button
        contentDescription = listOf(spoken, openLabel).filter { it.isNotEmpty() }.joinToString(". ")
      },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap, Alignment.CenterHorizontally),
  ) {
    Icon(imageVector = glyph, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(SearchDimens.InlineGlyph))
    Text(text = short, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    Icon(imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(SearchDimens.InlineGlyph))
  }
}

internal fun remainingOf(status: LibraryAnalysisStatus): Int? = when (status) {
  is LibraryAnalysisStatus.Running -> status.remaining
  is LibraryAnalysisStatus.Queued -> status.remaining
  is LibraryAnalysisStatus.Paused -> status.remaining
  else -> null
}
