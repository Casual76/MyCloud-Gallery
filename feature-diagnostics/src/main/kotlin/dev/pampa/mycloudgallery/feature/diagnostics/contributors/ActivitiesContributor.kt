package dev.pampa.mycloudgallery.feature.diagnostics.contributors

import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagText
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.feature.diagnostics.R
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.ActivityReason
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes

/** Il verdetto di una voce, come costante di un enum (per l'esportazione). */
internal enum class DecisionKind { RUN, WAIT, STOP, NEEDS_CONFIRMATION }

/**
 * «Attivita'» (docs/fase2-progetto.md §2.6): perche' ognuna delle sei attivita' in background gira o aspetta, dal
 * motore delle regole (`ActivityRulesEngine.evaluateAll`, docs/regole-attivita.md §4).
 *
 * Il testo del motivo lo scrive `core-rules` in italiano ([WaitReason.message], con le cifre dentro: «Batteria al
 * 19%: in pausa sotto il 20%»): sta a schermo, mai nell'esportazione, che porta solo il tipo del motivo e i
 * numeri. I motivi di WorkManager (`getStopReason`) li aggiunge `:app` con un proprio contributore
 * (`ACT_JOB_STOPPED`, P9).
 *
 * @param decisions normalmente `engine::evaluateAll`; un'astrazione perche' il motore ha tre dipendenze e qui basta
 *   il suo verdetto.
 */
class ActivitiesContributor(
  private val decisions: suspend () -> Map<BackgroundActivity, RuleDecision>,
) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.ACTIVITIES

  override suspend fun collect(): List<DiagItem> = decisions().map { (activity, decision) -> itemFor(activity, decision) }

  private fun itemFor(activity: BackgroundActivity, decision: RuleDecision): DiagItem = when (decision) {
    is RuleDecision.Run -> run(activity, decision)
    is RuleDecision.Wait -> blocked(activity, DecisionKind.WAIT, decision.reason, decision.alsoBlockedBy)
    is RuleDecision.Stop -> blocked(activity, DecisionKind.STOP, decision.reason, decision.alsoBlockedBy)
    is RuleDecision.NeedsConfirmation -> DiagItem(
      code = DiagCodes.ACT_CONFIRMATION_REQUIRED,
      section = DiagSection.ACTIVITIES,
      severity = DiagSeverity.INFO,
      titleRes = titleOf(activity),
      detail = DiagText(R.string.diagnostics_passthrough, listOf(decision.message)),
      export = mapOf(
        "activity" to ExportValue.Enum.of(activity),
        "decision" to ExportValue.Enum.of(DecisionKind.NEEDS_CONFIRMATION),
        "confirmMobileData" to ExportValue.Flag(ConfirmationKind.MOBILE_DATA in decision.kinds),
        "confirmRoaming" to ExportValue.Flag(ConfirmationKind.ROAMING in decision.kinds),
        "confirmOverCap" to ExportValue.Flag(ConfirmationKind.OVER_CAP in decision.kinds),
      ),
    )
  }

  private fun run(activity: BackgroundActivity, decision: RuleDecision.Run): DiagItem {
    // Gira, ma con un'avvertenza: solo sul buffer locale (E-A6) o a ritmo ridotto per il caldo (E-A5).
    val (code, severity) = when {
      decision.scope == RunScope.LOCAL_BUFFER_ONLY -> DiagCodes.ACT_LOCAL_BUFFER_ONLY to DiagSeverity.INFO
      decision.pace == Pace.REDUCED -> DiagCodes.ACT_REDUCED_PACE to DiagSeverity.INFO
      else -> DiagCodes.ACT_RUNNING to DiagSeverity.OK
    }
    val values = LinkedHashMap<String, ExportValue>()
    values["activity"] = ExportValue.Enum.of(activity)
    values["decision"] = ExportValue.Enum.of(DecisionKind.RUN)
    values["network"] = ExportValue.Enum.of(decision.network)
    values["scope"] = ExportValue.Enum.of(decision.scope)
    values["pace"] = ExportValue.Enum.of(decision.pace)
    decision.remainingBytes?.let { values["remainingBytes"] = ExportValue.Num(it) }
    return DiagItem(
      code = code,
      section = DiagSection.ACTIVITIES,
      severity = severity,
      titleRes = titleOf(activity),
      export = values,
    )
  }

  private fun blocked(
    activity: BackgroundActivity,
    kind: DecisionKind,
    reason: WaitReason,
    also: List<WaitReason>,
  ): DiagItem {
    val why = ActivityReason.of(reason)
    val values = LinkedHashMap<String, ExportValue>()
    values["activity"] = ExportValue.Enum.of(activity)
    values["decision"] = ExportValue.Enum.of(kind)
    values["reason"] = ExportValue.Enum.of(why)
    values["alsoBlockedBy"] = ExportValue.Num(also.size.toLong())
    // I numeri del motivo (soglie, tetti): servono a leggere l'esportazione senza il testo.
    when (reason) {
      is WaitReason.BatteryLow -> {
        values["batteryLevel"] = ExportValue.Num(reason.levelPercent.toLong())
        values["batteryThreshold"] = ExportValue.Num(reason.thresholdPercent.toLong())
      }
      is WaitReason.CapReached -> {
        values["capKind"] = ExportValue.Enum.of(reason.kind)
        values["usedBytes"] = ExportValue.Num(reason.usedBytes)
        values["capBytes"] = ExportValue.Num(reason.capBytes)
      }
      is WaitReason.Thermal -> values["thermalStatus"] = ExportValue.Enum.of(reason.status)
      is WaitReason.NetworkNotAllowed -> values["network"] = ExportValue.Enum.of(reason.current)
      is WaitReason.RoleDisabled -> values["role"] = ExportValue.Enum.of(reason.role)
      is WaitReason.AndroidLimit -> reason.budget?.let { values["budget"] = ExportValue.Enum.of(it) }
      else -> Unit
    }
    return DiagItem(
      code = why.code,
      section = DiagSection.ACTIVITIES,
      severity = DiagCatalog.entry(why.code)?.severity ?: DiagSeverity.INFO,
      titleRes = titleOf(activity),
      detail = DiagText(R.string.diagnostics_passthrough, listOf(reason.message)),
      export = values,
    )
  }

  companion object {
    /** Il nome dell'attivita' per lo schermo. */
    @StringRes
    fun titleOf(activity: BackgroundActivity): Int = when (activity) {
      BackgroundActivity.PHOTO_BACKUP -> R.string.diagnostics_activity_photo_backup
      BackgroundActivity.VIDEO_BACKUP -> R.string.diagnostics_activity_video_backup
      BackgroundActivity.INDEX_SYNC -> R.string.diagnostics_activity_index_sync
      BackgroundActivity.PREPARATION -> R.string.diagnostics_activity_preparation
      BackgroundActivity.AI_COMPUTE -> R.string.diagnostics_activity_ai_compute
      BackgroundActivity.HQ_PREVIEWS -> R.string.diagnostics_activity_hq_previews
    }
  }
}
