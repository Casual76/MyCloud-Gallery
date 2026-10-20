package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.AreaCandidate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.AreasUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.ChoiceRow
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.LoadingBlock
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/** Cio' che si sta scegliendo al passo 4. */
@Stable
internal class AreasForm(commonShare: String? = null, privateShare: String? = null, extra: String = "") {
  var commonShare: String? by mutableStateOf(commonShare)
  var privateShare: String? by mutableStateOf(privateShare)
  var extra: String by mutableStateOf(extra)
}

internal class AreasActions(
  val onSubmit: (commonShare: String, privateShare: String) -> Unit = { _, _ -> },
  val onAddShare: (String) -> Unit = {},
  val onRefresh: () -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

/**
 * Cosa si puo' fare in una share, in una parola (con l'errore di rete distinto da "non esiste"). [FENCED]: la esclude la recinzione delle
 * prove (D33) con un account di prova, non il NAS (punto 8 delle prove sul campo: prima diceva "Accesso negato").
 */
internal enum class AreaState { READ_WRITE, READ_ONLY, DENIED, FENCED, MISSING, UNREACHABLE }

internal fun AreaCandidate.state(): AreaState = if (fenced) AreaState.FENCED else when (val result = access) {
  is NasResult.Ok -> when (result.value) {
    ShareAccess.READ_WRITE -> AreaState.READ_WRITE
    ShareAccess.READ_ONLY -> AreaState.READ_ONLY
    ShareAccess.DENIED -> AreaState.DENIED
    ShareAccess.MISSING -> AreaState.MISSING
  }
  is NasResult.Err -> AreaState.UNREACHABLE
}

/**
 * Passo 4: le aree (§2.1).
 *
 * Si sceglie l'area comune (dove scrive tutta la famiglia) e quella privata (dove vanno i propri backup) fra le
 * share provate. Solo una share in cui si puo' scrivere si puo' scegliere: l'app non crea cartelle sul NAS
 * (E-O3). Senza nessuna share accessibile (E-O2) la pagina spiega cosa chiedere a chi gestisce il NAS. Se
 * nell'area comune c'e' gia' una famiglia, una pagina dedicata propone di entrare o di recuperare l'admin.
 */
@Composable
internal fun AreasScreen(
  chrome: StepChrome,
  ui: AreasUi,
  form: AreasForm,
  actions: AreasActions,
  modifier: Modifier = Modifier,
) {
  val accessible = ui.candidates.count { it.state() == AreaState.READ_WRITE || it.state() == AreaState.READ_ONLY }
  val writable = ui.candidates.count { it.state() == AreaState.READ_WRITE }
  val noAccess = !ui.loading && (ui.problem == OnboardingProblem.NO_ACCESSIBLE_SHARE || accessible == 0)
  val familyExists = ui.problem == OnboardingProblem.FAMILY_ALREADY_EXISTS
  // Punto 10 delle prove sul campo: "Recupera l'admin" o "Secondo admin" su un'area comune senza famiglia.
  val noFamily = ui.problem == OnboardingProblem.NO_FAMILY_IN_AREA
  val common = form.commonShare
  val privateShare = form.privateShare
  StepScaffold(
    title = stringResource(R.string.areas_title),
    subtitle = stringResource(R.string.areas_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.AREAS, urgent = noAccess),
    modifier = modifier,
    primary = if (noAccess || familyExists || noFamily) {
      null
    } else {
      DockAction(
        label = stringResource(R.string.action_continue),
        onClick = { if (common != null && privateShare != null) actions.onSubmit(common, privateShare) },
        enabled = !ui.loading && !ui.submitting && common != null && privateShare != null,
        loading = ui.submitting,
      )
    },
  ) {
    when {
      ui.loading -> item(key = "loading") { LoadingBlock(label = stringResource(R.string.areas_loading)) }
      noAccess -> item(key = "no-access") {
        ProblemPanel(problem = OnboardingProblem.NO_ACCESSIBLE_SHARE, onAction = { action ->
          if (action == ProblemAction.RETRY) actions.onRefresh() else actions.onProblemAction(action)
        })
      }
      familyExists -> item(key = "family") {
        ProblemPanel(problem = OnboardingProblem.FAMILY_ALREADY_EXISTS, onAction = actions.onProblemAction)
      }
      noFamily -> item(key = "no-family") {
        ProblemPanel(
          problem = OnboardingProblem.NO_FAMILY_IN_AREA,
          onAction = actions.onProblemAction,
          title = common?.let { stringResource(R.string.problem_no_family_title_named, it) },
          detail = common?.let { stringResource(R.string.problem_no_family_detail_named, it) },
        )
      }
      else -> {
        if (writable < 2 || ui.problem == OnboardingProblem.COMMON_AREA_NOT_WRITABLE) {
          item(key = "not-writable") {
            ProblemPanel(problem = OnboardingProblem.COMMON_AREA_NOT_WRITABLE, onAction = { action ->
              if (action == ProblemAction.RETRY) actions.onRefresh() else actions.onProblemAction(action)
            })
          }
        }
        item(key = "common-header") {
          FluidSectionHeader(
            title = stringResource(R.string.areas_section_common),
            detail = stringResource(R.string.areas_section_common_detail),
          )
        }
        item(key = "common") {
          AreaGroup(
            candidates = ui.candidates,
            selected = common,
            disabledShare = privateShare,
            disabledReason = R.string.areas_taken_private,
            icon = Icons.Rounded.FolderShared,
            onSelect = { form.commonShare = it },
          )
        }
        item(key = "private-header") {
          FluidSectionHeader(
            title = stringResource(R.string.areas_section_private),
            detail = stringResource(R.string.areas_section_private_detail),
          )
        }
        item(key = "private") {
          AreaGroup(
            candidates = ui.candidates,
            selected = privateShare,
            disabledShare = common,
            disabledReason = R.string.areas_taken_common,
            icon = Icons.Rounded.FolderSpecial,
            onSelect = { form.privateShare = it },
          )
        }
        item(key = "backup-note") {
          FluidSectionFootnote(
            text = stringResource(
              R.string.areas_backup_note,
              privateShare ?: stringResource(R.string.areas_backup_note_private),
            ),
          )
        }
        item(key = "extra") {
          ExtraShare(form = form, onAdd = actions.onAddShare)
        }
      }
    }
    if (ui.problem != null && ui.problem != OnboardingProblem.NO_ACCESSIBLE_SHARE &&
      ui.problem != OnboardingProblem.FAMILY_ALREADY_EXISTS && ui.problem != OnboardingProblem.COMMON_AREA_NOT_WRITABLE &&
      ui.problem != OnboardingProblem.NO_FAMILY_IN_AREA
    ) {
      item(key = "problem") { ProblemPanel(problem = ui.problem, onAction = actions.onProblemAction) }
    }
  }
}

@Composable
private fun AreaGroup(
  candidates: List<AreaCandidate>,
  selected: String?,
  disabledShare: String?,
  disabledReason: Int,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  onSelect: (String) -> Unit,
) {
  FluidListGroup {
    candidates.forEachIndexed { index, candidate ->
      if (index > 0) FluidListDivider()
      val state = candidate.state()
      val takenByOther = candidate.share == disabledShare
      val enabled = state == AreaState.READ_WRITE && !takenByOther
      ChoiceRow(
        title = candidate.share,
        subtitle = stringResource(
          when {
            takenByOther -> disabledReason
            else -> state.label()
          },
        ),
        selected = selected == candidate.share,
        onSelect = { onSelect(candidate.share) },
        icon = if (state == AreaState.READ_WRITE) icon else Icons.Rounded.Folder,
        enabled = enabled,
        labels = if (candidate.isPublic == true || candidate.hasFamily) {
          {
            Row(horizontalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap), verticalAlignment = Alignment.CenterVertically) {
              if (candidate.isPublic == true) {
                FluidStatusBadge(label = stringResource(R.string.areas_badge_public), tone = FluidTone.Warning)
              }
              if (candidate.hasFamily) {
                FluidStatusBadge(label = stringResource(R.string.areas_badge_family), tone = FluidTone.Info)
              }
            }
          }
        } else {
          null
        },
      )
    }
  }
}

private fun AreaState.label(): Int = when (this) {
  AreaState.READ_WRITE -> R.string.areas_state_read_write
  AreaState.READ_ONLY -> R.string.areas_state_read_only
  AreaState.DENIED -> R.string.areas_state_denied
  AreaState.FENCED -> R.string.areas_state_fenced
  AreaState.MISSING -> R.string.areas_state_missing
  AreaState.UNREACHABLE -> R.string.areas_state_unreachable
}

@Composable
private fun ExtraShare(form: AreasForm, onAdd: (String) -> Unit) {
  val add = {
    val name = form.extra.trim()
    if (name.isNotEmpty()) {
      onAdd(name)
      form.extra = ""
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap), modifier = Modifier.fillMaxWidth()) {
    FluidTextField(
      value = form.extra,
      onValueChange = { form.extra = it },
      label = stringResource(R.string.areas_extra_label),
      placeholder = stringResource(R.string.areas_extra_placeholder),
      keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        imeAction = ImeAction.Done,
      ),
      keyboardActions = KeyboardActions(onDone = { add() }),
    )
    FluidButton(
      text = stringResource(R.string.areas_extra_add),
      onClick = add,
      style = FluidButtonStyle.Tinted,
      size = FluidButtonSize.Medium,
      enabled = form.extra.isNotBlank(),
    )
  }
}
