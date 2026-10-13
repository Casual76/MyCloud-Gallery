package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.TypedFingerprint
import dev.pampa.mycloudgallery.core.security.input.SecureInput
import dev.pampa.mycloudgallery.core.security.screen.SecureScreen
import dev.pampa.mycloudgallery.core.security.screen.SensitiveScreen
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.KeyUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.CodeGroups
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.GlyphTile
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.SecretFieldLabel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.ToggleRow
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

// Il passo 12: la chiave di famiglia e il kit di recupero. Quattro pagine vere (la transizione fra una e l'altra
// e' laterale, come fra due passi), nell'ordine fisso del §2.1: genera -> mostra il codice -> riscrivilo per
// intero -> salva il kit sul NAS, aprilo con il codice, fonda la famiglia.

/** Il codice di recupero e' fatto di caratteri maiuscoli e cifre, in gruppi: il resto si ignora quando si scrive. */
internal object RecoveryCodeFormat {

  /** Solo lettere e cifre, maiuscole: gli spazi e i trattini fra i gruppi non contano. */
  fun normalize(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase()

  /** Il codice come lo scrive l'utente, confrontato con quello mostrato (gruppi uniti). */
  fun matches(typed: String, groups: List<String>): Boolean = normalize(typed) == groups.joinToString("")
}

/** Mostra il codice a gruppi mentre lo si scrive (uno spazio ogni [groupSize] caratteri): i caratteri non cambiano. */
internal class GroupedCodeTransformation(private val groupSize: Int) : VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    val grouped = buildString {
      text.text.forEachIndexed { index, char ->
        if (index > 0 && index % groupSize == 0) append(' ')
        append(char)
      }
    }
    val mapping = object : OffsetMapping {
      override fun originalToTransformed(offset: Int): Int = offset + (offset - 1).coerceAtLeast(0) / groupSize

      override fun transformedToOriginal(offset: Int): Int = offset - offset / (groupSize + 1)
    }
    return TransformedText(AnnotatedString(grouped), mapping)
  }
}

@Stable
internal class KeyForm(acknowledged: Boolean = false, typed: String = "", reveal: Boolean = true) {
  var acknowledged: Boolean by mutableStateOf(acknowledged)
  var typed: String by mutableStateOf(typed)
  var reveal: Boolean by mutableStateOf(reveal)
}

internal class KeyActions(
  val onGenerate: () -> Unit = {},
  val onCodeWritten: () -> Unit = {},
  val onBackToCode: () -> Unit = {},
  val onSubmitCode: (CharArray) -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

/** Pagina 1: cosa sono la chiave e il codice, e il bottone che li genera. */
@Composable
internal fun KeyIntroPage(chrome: StepChrome, ui: KeyUi, actions: KeyActions, modifier: Modifier = Modifier) {
  val familyExists = ui.problem == OnboardingProblem.FAMILY_ALREADY_EXISTS
  StepScaffold(
    title = stringResource(R.string.key_title),
    subtitle = stringResource(R.string.key_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.FAMILY_KEY),
    modifier = modifier,
    primary = if (familyExists) {
      null
    } else {
      DockAction(
        label = stringResource(if (ui.busy) R.string.key_generating else R.string.key_generate),
        onClick = actions.onGenerate,
        enabled = !ui.busy,
        loading = ui.busy,
      )
    },
  ) {
    if (familyExists) {
      item(key = "family") { ProblemPanel(problem = OnboardingProblem.FAMILY_ALREADY_EXISTS, onAction = actions.onProblemAction) }
    } else {
      item(key = "two") {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.key_what_key),
            subtitle = stringResource(R.string.key_what_key_detail),
            tone = FluidTone.Primary,
            leading = { Icon(Icons.Rounded.Key, contentDescription = null) },
            disclosure = false,
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.key_what_code),
            subtitle = stringResource(R.string.key_what_code_detail),
            tone = FluidTone.Warning,
            leading = { Icon(Icons.Rounded.Shield, contentDescription = null) },
            disclosure = false,
          )
        }
      }
      item(key = "plan-header") { FluidSectionHeader(title = stringResource(R.string.key_plan_section)) }
      item(key = "plan") {
        FluidListGroup {
          PlanRow(Icons.Rounded.Key, R.string.key_plan_generate)
          FluidListDivider()
          PlanRow(Icons.Rounded.Create, R.string.key_plan_write)
          FluidListDivider()
          PlanRow(Icons.Rounded.Save, R.string.key_plan_save)
          FluidListDivider()
          PlanRow(Icons.Rounded.Groups, R.string.key_plan_found)
        }
      }
    }
    ui.problem?.takeIf { !familyExists }?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
  }
}

@Composable
private fun PlanRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: Int) {
  FluidListRow(
    title = stringResource(text),
    subtitle = "",
    tone = FluidTone.Info,
    leading = { Icon(icon, contentDescription = null) },
    disclosure = false,
  )
}

/** Pagina 2: il codice di recupero, in 7 gruppi, su una pagina protetta (`FLAG_SECURE`). */
@Composable
internal fun KeyShowCodePage(
  chrome: StepChrome,
  ui: KeyUi,
  form: KeyForm,
  actions: KeyActions,
  modifier: Modifier = Modifier,
) {
  SecureScreen()
  StepScaffold(
    title = stringResource(R.string.key_code_title),
    subtitle = stringResource(R.string.key_code_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.FAMILY_KEY),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.key_code_written),
      onClick = actions.onCodeWritten,
      enabled = form.acknowledged,
    ),
  ) {
    item(key = "paper") {
      NoticeCard(
        tone = NoticeTone.Caution,
        icon = Icons.Rounded.Warning,
        title = stringResource(R.string.key_code_paper_title),
        body = stringResource(R.string.key_code_paper_detail),
      )
    }
    item(key = "code") {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = OnboardingDimens.CardGap),
        verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
      ) {
        CodeGroups(groups = ui.groups, cellMinWidth = OnboardingDimens.CodeCellMinWidth)
      }
    }
    // Revisione 2, F3: l'impronta della famiglia va sul foglio con il codice. Non e' segreta, ma nel recupero senza il
    // QR "stato della famiglia" e' l'unica ancora che non viene dal NAS.
    ui.fingerprint?.let { fingerprint ->
      item(key = "fingerprint") {
        FluidListGroup {
          FluidListRow(
            title = TypedFingerprint.grouped(fingerprint),
            subtitle = stringResource(R.string.key_code_fingerprint_detail),
            eyebrow = stringResource(R.string.key_code_fingerprint_title),
            tone = FluidTone.Primary,
            leading = { Icon(Icons.Rounded.Fingerprint, contentDescription = null) },
            disclosure = false,
          )
        }
      }
    }
    item(key = "ack") {
      FluidListGroup {
        ToggleRow(
          title = stringResource(R.string.key_code_ack),
          subtitle = stringResource(R.string.key_code_ack_detail),
          icon = Icons.Rounded.Create,
          checked = form.acknowledged,
          onCheckedChange = { form.acknowledged = it },
        )
      }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.key_code_footnote)) }
  }
}

/**
 * Pagina 3: il codice riscritto per intero. Il campo usa `SecureInput` (tastiera "password", niente correttore)
 * e la pagina e' protetta; i gruppi si formano da soli mentre si scrive. Un codice sbagliato non costa niente:
 * e' un confronto locale, si puo' rivedere il codice e riprovare quante volte si vuole.
 */
@Composable
internal fun KeyRetypePage(
  chrome: StepChrome,
  ui: KeyUi,
  form: KeyForm,
  actions: KeyActions,
  modifier: Modifier = Modifier,
) {
  SensitiveScreen()
  val expected = ui.groups.sumOf { it.length }
  val groupSize = ui.groups.firstOrNull()?.length ?: DEFAULT_GROUP_SIZE
  val complete = expected > 0 && form.typed.length == expected
  val submit = { if (complete) actions.onSubmitCode(form.typed.toCharArray()) }
  StepScaffold(
    title = stringResource(R.string.key_retype_title),
    subtitle = stringResource(R.string.key_retype_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.FAMILY_KEY, urgent = ui.problem == OnboardingProblem.RECOVERY_CODE_MISMATCH),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(R.string.key_retype_submit),
      onClick = submit,
      enabled = complete,
    ),
    secondary = DockAction(
      label = stringResource(R.string.action_back_to_code),
      onClick = actions.onBackToCode,
      style = FluidButtonStyle.Plain,
    ),
  ) {
    item(key = "field") {
      Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap)) {
      SecretFieldLabel(
        label = stringResource(R.string.key_retype_label),
        revealed = form.reveal,
        onToggle = { form.reveal = !form.reveal },
      )
      FluidTextField(
        value = form.typed,
        onValueChange = { form.typed = RecoveryCodeFormat.normalize(it).take(expected) },
        supportingText = stringResource(R.string.key_retype_count, form.typed.length, expected),
        keyboardOptions = SecureInput.keyboardOptions(ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        visualTransformation = if (form.reveal) GroupedCodeTransformation(groupSize) else SecureInput.hidden,
        showClearButton = false,
      )
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") {
        ProblemPanel(problem = problem, onAction = { action ->
          if (action == ProblemAction.BACK_TO_CODE) actions.onBackToCode() else actions.onProblemAction(action)
        })
      }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.key_retype_footnote)) }
  }
}

private const val DEFAULT_GROUP_SIZE = 4

/** Pagina 4: il kit si salva sul NAS, si rilegge, si apre con il codice digitato; poi si fonda la famiglia. */
@Composable
internal fun KeyVerifyingPage(chrome: StepChrome, modifier: Modifier = Modifier) {
  StepScaffold(
    title = stringResource(R.string.key_verify_title),
    subtitle = stringResource(R.string.key_verify_subtitle),
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.FAMILY_KEY),
    modifier = modifier,
  ) {
    item(key = "spinner") {
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = OnboardingDimens.CardPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
      ) {
        GlyphTile(
          icon = Icons.Rounded.Shield,
          tone = NoticeTone.Success,
          size = OnboardingDimens.HeroTile,
          glyph = OnboardingDimens.HeroGlyph,
        )
        FluidSpinner(size = OnboardingDimens.TileGlyph)
        Text(
          text = stringResource(R.string.key_verify_wait),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
    item(key = "lines") {
      FluidListGroup {
        PlanRow(Icons.Rounded.Save, R.string.key_verify_save)
        FluidListDivider()
        PlanRow(Icons.Rounded.FindInPage, R.string.key_verify_open)
        FluidListDivider()
        PlanRow(Icons.Rounded.Groups, R.string.key_verify_found)
      }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.key_verify_footnote)) }
  }
}
