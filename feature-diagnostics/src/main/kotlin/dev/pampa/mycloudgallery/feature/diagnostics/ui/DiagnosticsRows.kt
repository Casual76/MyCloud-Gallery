package dev.pampa.mycloudgallery.feature.diagnostics.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.RecentEventsContributor

/** La chiave con cui una fase del test porta il suo tempo (`ConnectionTestRunner.toItems`). */
internal const val ELAPSED_KEY = "elapsed"

/**
 * Quali azioni si possono proporre in questa schermata: quelle che il modulo esegue da solo, piu' quelle che `:app`
 * ha dichiarato di eseguire. «Apri WireGuard» solo se WireGuard c'e': un tasto che non fa niente e' peggio di
 * nessun tasto.
 */
class DiagActionPolicy(
  private val appActions: Set<DiagAction> = emptySet(),
  private val wireGuardAvailable: Boolean = false,
) {
  fun canShow(action: DiagAction): Boolean =
    DiagnosticsActions.isAvailable(action, appActions) && (action != DiagAction.OPEN_WIREGUARD || wireGuardAvailable)
}

internal fun DiagSeverity.tone(): FluidTone = when (this) {
  DiagSeverity.OK -> FluidTone.Success
  DiagSeverity.INFO -> FluidTone.Info
  DiagSeverity.WARNING -> FluidTone.Warning
  DiagSeverity.ERROR -> FluidTone.Danger
}

internal fun DiagSeverity.icon(): ImageVector = when (this) {
  DiagSeverity.OK -> Icons.Rounded.CheckCircle
  DiagSeverity.INFO -> Icons.Rounded.Info
  DiagSeverity.WARNING -> Icons.Rounded.Warning
  DiagSeverity.ERROR -> Icons.Rounded.ErrorOutline
}

internal fun DiagSection.icon(): ImageVector = when (this) {
  DiagSection.NETWORK_AND_NAS -> Icons.Rounded.Wifi
  DiagSection.CONNECTION_TEST -> Icons.Rounded.NetworkCheck
  DiagSection.ACTIVITIES -> Icons.Rounded.Schedule
  DiagSection.RECENT_EVENTS -> Icons.Rounded.History
  DiagSection.PHONE_LIBRARY -> Icons.Rounded.PhotoLibrary
  DiagSection.NAS_LIBRARY -> Icons.Rounded.Dns
  DiagSection.BACKUP -> Icons.Rounded.Backup
  DiagSection.STORAGE -> Icons.Rounded.Storage
  DiagSection.MONTHLY_TRAFFIC -> Icons.Rounded.DataUsage
  DiagSection.ANDROID_WARNINGS -> Icons.Rounded.PhoneAndroid
  DiagSection.TRUST -> Icons.Rounded.VerifiedUser
  DiagSection.PROFILES -> Icons.Rounded.Group
}

internal fun DiagSection.titleRes(): Int = when (this) {
  DiagSection.NETWORK_AND_NAS -> R.string.diagnostics_section_network
  DiagSection.CONNECTION_TEST -> R.string.diagnostics_section_connection_test
  DiagSection.ACTIVITIES -> R.string.diagnostics_section_activities
  DiagSection.RECENT_EVENTS -> R.string.diagnostics_section_recent
  DiagSection.PHONE_LIBRARY -> R.string.diagnostics_section_phone_library
  DiagSection.NAS_LIBRARY -> R.string.diagnostics_section_nas_library
  DiagSection.BACKUP -> R.string.diagnostics_section_backup
  DiagSection.STORAGE -> R.string.diagnostics_section_storage
  DiagSection.MONTHLY_TRAFFIC -> R.string.diagnostics_section_traffic
  DiagSection.ANDROID_WARNINGS -> R.string.diagnostics_section_android
  DiagSection.TRUST -> R.string.diagnostics_section_trust
  DiagSection.PROFILES -> R.string.diagnostics_section_profiles
}

internal fun DiagSection.hintRes(): Int = when (this) {
  DiagSection.NETWORK_AND_NAS -> R.string.diagnostics_hint_network
  DiagSection.CONNECTION_TEST -> R.string.diagnostics_hint_connection_test
  DiagSection.ACTIVITIES -> R.string.diagnostics_hint_activities
  DiagSection.RECENT_EVENTS -> R.string.diagnostics_hint_recent
  DiagSection.PHONE_LIBRARY -> R.string.diagnostics_hint_phone_library
  DiagSection.NAS_LIBRARY -> R.string.diagnostics_hint_nas_library
  DiagSection.BACKUP -> R.string.diagnostics_hint_backup
  DiagSection.STORAGE -> R.string.diagnostics_hint_storage
  DiagSection.MONTHLY_TRAFFIC -> R.string.diagnostics_hint_traffic
  DiagSection.ANDROID_WARNINGS -> R.string.diagnostics_hint_android
  DiagSection.TRUST -> R.string.diagnostics_hint_trust
  DiagSection.PROFILES -> R.string.diagnostics_hint_profiles
}

@Composable
internal fun severityLabel(severity: DiagSeverity): String = stringResource(
  when (severity) {
    DiagSeverity.OK -> R.string.diagnostics_severity_ok
    DiagSeverity.INFO -> R.string.diagnostics_severity_info
    DiagSeverity.WARNING -> R.string.diagnostics_severity_warning
    DiagSeverity.ERROR -> R.string.diagnostics_severity_error
  },
)

/** Il testo di uno [DiagText] (risorsa piu' argomenti), vuoto se la risorsa e' 0. Solo per lo schermo. */
@Composable
internal fun DiagText.resolve(): String =
  if (resId == 0) "" else stringResource(resId, *args.toTypedArray())

/** «adesso», «5 min fa», «3 h fa», «2 g fa». */
@Composable
internal fun ageText(millis: Long): String {
  val minutes = millis / MINUTE_MS
  return when {
    minutes < 1 -> stringResource(R.string.diagnostics_age_now)
    minutes < MINUTES_PER_HOUR -> stringResource(R.string.diagnostics_age_minutes, minutes)
    minutes < MINUTES_PER_DAY -> stringResource(R.string.diagnostics_age_hours, minutes / MINUTES_PER_HOUR)
    else -> stringResource(R.string.diagnostics_age_days, minutes / MINUTES_PER_DAY)
  }
}

/** «41 ms», «1,2 s»: la durata di una fase del test. */
@Composable
internal fun durationText(millis: Long): String =
  if (millis < SECOND_MS) {
    stringResource(R.string.diagnostics_duration_ms, millis)
  } else {
    stringResource(R.string.diagnostics_duration_seconds, String.format(java.util.Locale.ITALIAN, "%.1f", millis / SECOND_MS.toDouble()))
  }

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 1_440L

/**
 * Le righe delle voci di una sezione dentro un gruppo (`FluidListGroup`): per ogni voce una riga con il tono sulla
 * piastrella dell'icona (mai sullo sfondo della riga: regole Fluid) e, per un avviso o un errore, il «cosa fare»
 * del catalogo; se la voce ha un'azione disponibile, subito sotto la sua riga-tasto.
 *
 * Il codice della voce sta sempre in piccolo sotto il testo: aiuta a riconoscere una voce in un'esportazione o a
 * dirla a chi ti aiuta. E' un identificativo scritto nel sorgente, non un dato.
 */
@Composable
internal fun ColumnScope.DiagItemRows(
  items: List<DiagItem>,
  actions: DiagActionPolicy,
  onAction: (DiagAction) -> Unit,
) {
  items.forEachIndexed { index, item ->
    if (index > 0) FluidListDivider()
    DiagItemRow(item, actions, onAction)
  }
}

@Composable
private fun ColumnScope.DiagItemRow(item: DiagItem, actions: DiagActionPolicy, onAction: (DiagAction) -> Unit) {
  val titleRes = item.titleRes.takeIf { it != 0 } ?: DiagCatalog.titleOf(item.code)
  val title = if (titleRes != 0) stringResource(titleRes) else stringResource(R.string.diagnostics_item_unknown)
  val detail = item.detail?.resolve()?.takeIf { it.isNotBlank() }
  val guideRes = if (item.severity >= DiagSeverity.WARNING) DiagCatalog.guideOf(item.code) else 0
  val guide = if (guideRes != 0) stringResource(guideRes) else null
  val age = (item.export[RecentEventsContributor.AGE_KEY] as? ExportValue.Dur)?.let { ageText(it.millis) }
  // Una fase saltata o non eseguita ha un tempo di zero: non si mostra.
  val elapsed = (item.export[ELAPSED_KEY] as? ExportValue.Dur)?.takeIf { it.millis > 0 }?.let { durationText(it.millis) }
  val label = severityLabel(item.severity)

  // Il dettaglio (numeri, motivo) viene prima; il «cosa fare» lo segue. Se il dettaglio manca, il «cosa fare»
  // e' la riga di sotto. Il codice e l'eta' chiudono la voce.
  // Il codice generico di una fase del test («TEST_STAGE») non dice niente: non si mostra.
  val code = item.code.id.takeUnless { item.code == DiagCodes.TEST_STAGE }
  val meta = buildList {
    if (detail != null && guide != null) add(guide)
    listOfNotNull(code, age, elapsed).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { add(it) }
  }.joinToString("\n")

  FluidListRow(
    title = title,
    subtitle = detail ?: guide.orEmpty(),
    meta = meta,
    tone = item.severity.tone(),
    leading = { Icon(imageVector = item.severity.icon(), contentDescription = label) },
    badge = if (item.severity >= DiagSeverity.WARNING) {
      { FluidStatusBadge(label = label, tone = item.severity.tone()) }
    } else {
      null
    },
  )

  val action = DiagCatalog.actionFor(item)?.takeIf { actions.canShow(it) }
  if (action != null) {
    FluidListDivider()
    FluidListRow(
      title = stringResource(DiagnosticsActions.labelRes(action)),
      subtitle = "",
      tone = FluidTone.Primary,
      leading = { Icon(imageVector = Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
      onClick = { onAction(action) },
    )
  }
}
