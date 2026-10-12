package dev.pampa.mycloudgallery.feature.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.model.AddressUi
import dev.pampa.mycloudgallery.feature.onboarding.model.HomeCandidateUi
import dev.pampa.mycloudgallery.feature.onboarding.model.HomeUi
import dev.pampa.mycloudgallery.feature.onboarding.ui.CardButton
import dev.pampa.mycloudgallery.feature.onboarding.ui.ChoiceRow
import dev.pampa.mycloudgallery.feature.onboarding.ui.DockAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeCard
import dev.pampa.mycloudgallery.feature.onboarding.ui.NoticeTone
import dev.pampa.mycloudgallery.feature.onboarding.ui.OnboardingDimens
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemAction
import dev.pampa.mycloudgallery.feature.onboarding.ui.ProblemPanel
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepChrome
import dev.pampa.mycloudgallery.feature.onboarding.ui.StepScaffold
import dev.pampa.mycloudgallery.feature.onboarding.ui.stepAmbient

/** Cio' che si sta scrivendo al passo 2. Vive nella pagina (e non nel ViewModel): sparisce con lei. */
@Stable
internal class AddressForm(ip: String = "", name: String = "", selected: Int? = null) {
  var ip: String by mutableStateOf(ip)
  var name: String by mutableStateOf(name)
  var selected: Int? by mutableStateOf(selected)

  /** L'indirizzo, se quello scritto e' un IP letterale valido (mai un nome da risolvere, E-R7). */
  fun address(): NasAddress? = NasAddress.parseOrNull(ip, name.trim().ifBlank { null })

  val isValid: Boolean get() = address() != null
}

internal class AddressActions(
  val onConfirmHome: (Boolean) -> Unit = {},
  val onRefreshHome: () -> Unit = {},
  val onCheck: (NasAddress) -> Unit = {},
  val onProblemAction: (ProblemAction) -> Unit = {},
)

/**
 * Passo 2: l'indirizzo del NAS (§2.1).
 *
 * Tre momenti, uno dopo l'altro, nella stessa pagina: (1) "E' la rete di casa?", che serve il Wi-Fi di casa e
 * che con il consenso raccoglie l'impronta della rete; (2) la scelta del NAS, fra quelli trovati in LAN o
 * scrivendo l'indirizzo; (3) il controllo (TCP, NEGOTIATE, identita' del NAS), che non invia nessuna password.
 * Ogni problema ha la sua scheda (Wi-Fi mancante, VPN accesa, rete senza ULA, rete locale negata, NAS spento
 * E-O1, servizio fermo, connessione non cifrata, NAS diverso).
 */
@Composable
internal fun NasAddressScreen(
  chrome: StepChrome,
  ui: AddressUi,
  form: AddressForm,
  actions: AddressActions,
  modifier: Modifier = Modifier,
  title: String = stringResource(R.string.address_title),
  subtitle: String = stringResource(R.string.address_subtitle),
) {
  val confirmed = ui.home is HomeUi.Confirmed
  val address = form.address()
  StepScaffold(
    title = title,
    subtitle = subtitle,
    chrome = chrome,
    ambient = stepAmbient(OnboardingStep.NAS_ADDRESS, urgent = ui.problem.isHardFailure()),
    modifier = modifier,
    primary = DockAction(
      label = stringResource(if (ui.checking) R.string.address_checking else R.string.address_check),
      onClick = { address?.let(actions.onCheck) },
      enabled = confirmed && address != null && !ui.checking,
      loading = ui.checking,
    ),
  ) {
    item(key = "home-header") { FluidSectionHeader(title = stringResource(R.string.address_section_home)) }
    item(key = "home") { HomeSection(home = ui.home, onConfirm = actions.onConfirmHome, onRefresh = actions.onRefreshHome, onProblemAction = actions.onProblemAction) }

    if (confirmed) {
      item(key = "found-header") { FluidSectionHeader(title = stringResource(R.string.address_section_found)) }
      item(key = "found") {
        FoundSection(ui = ui, form = form)
      }
      item(key = "manual-header") {
        FluidSectionHeader(
          title = stringResource(R.string.address_section_manual),
          detail = stringResource(R.string.address_section_manual_detail),
        )
      }
      item(key = "manual") { ManualFields(form = form, enabled = !ui.checking) { address?.let(actions.onCheck) } }
    }

    if (ui.checking) {
      item(key = "checking") {
        NoticeCard(
          tone = NoticeTone.Info,
          icon = Icons.Rounded.Dns,
          title = stringResource(R.string.address_checking_title),
          body = stringResource(R.string.address_checking_detail),
        )
      }
    }
    ui.problem?.let { problem ->
      item(key = "problem") { ProblemPanel(problem = problem, onAction = actions.onProblemAction) }
    }
    item(key = "footnote") { FluidSectionFootnote(text = stringResource(R.string.address_footnote)) }
  }
}

/** Un problema "duro" (identita' o sicurezza) tinge il fondale con la famiglia dell'errore. */
private fun OnboardingProblem?.isHardFailure(): Boolean =
  this == OnboardingProblem.IDENTITY_MISMATCH || this == OnboardingProblem.NAS_INSECURE

@Composable
private fun HomeSection(
  home: HomeUi,
  onConfirm: (Boolean) -> Unit,
  onRefresh: () -> Unit,
  onProblemAction: (ProblemAction) -> Unit,
) {
  when (home) {
    HomeUi.Loading -> FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.address_home_looking),
        subtitle = stringResource(R.string.address_home_looking_detail),
        tone = FluidTone.Primary,
        leading = { Icon(Icons.Rounded.WifiFind, contentDescription = null) },
        badge = { FluidSpinner() },
        disclosure = false,
      )
    }
    HomeUi.NoWifi -> Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap)) {
      ProblemPanel(problem = OnboardingProblem.NOT_ON_WIFI, onAction = { action ->
        if (action == ProblemAction.RETRY) onRefresh() else onProblemAction(action)
      })
    }
    is HomeUi.Ask -> AskHome(candidate = home.candidate, onConfirm = onConfirm, onProblemAction = onProblemAction, onRefresh = onRefresh)
    is HomeUi.Confirmed -> FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.address_home_confirmed),
        subtitle = home.candidate.describe(),
        tone = FluidTone.Success,
        leading = { Icon(Icons.Rounded.Home, contentDescription = null) },
        badge = { Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        disclosure = false,
      )
    }
    HomeUi.NotHome -> ProblemPanel(problem = OnboardingProblem.NOT_ON_WIFI, onAction = { action ->
      if (action == ProblemAction.RETRY) onRefresh() else onProblemAction(action)
    })
  }
}

@Composable
private fun AskHome(
  candidate: HomeCandidateUi,
  onConfirm: (Boolean) -> Unit,
  onProblemAction: (ProblemAction) -> Unit,
  onRefresh: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap)) {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.address_home_ask),
        subtitle = candidate.describe(),
        tone = FluidTone.Primary,
        leading = { Icon(Icons.Rounded.Wifi, contentDescription = null) },
        disclosure = false,
      )
    }
    if (candidate.canConfirm) {
      Text(
        text = stringResource(R.string.address_home_consent),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(OnboardingDimens.DockGap), modifier = Modifier.fillMaxWidth()) {
        CardButton(
          text = stringResource(R.string.address_home_yes),
          onClick = { onConfirm(true) },
          primary = true,
          modifier = Modifier.weight(1f),
        )
        CardButton(
          text = stringResource(R.string.address_home_no),
          onClick = { onConfirm(false) },
          primary = false,
          modifier = Modifier.weight(1f),
        )
      }
    } else {
      ProblemPanel(problem = OnboardingProblem.NO_ULA, onAction = { action ->
        if (action == ProblemAction.RETRY) onRefresh() else onProblemAction(action)
      })
    }
  }
}

@Composable
private fun HomeCandidateUi.describe(): String {
  val parts = buildList {
    ipv4Subnet?.let { add(stringResource(R.string.address_home_subnet, it)) }
    gatewayV4?.let { add(stringResource(R.string.address_home_gateway, it)) }
    dnsDomain?.let { add(it) }
  }
  return if (parts.isEmpty()) stringResource(R.string.address_home_no_details) else parts.joinToString(" · ")
}

@Composable
private fun FoundSection(ui: AddressUi, form: AddressForm) {
  FluidListGroup {
    if (ui.found.isEmpty()) {
      FluidListRow(
        title = stringResource(if (ui.discovering) R.string.address_found_looking else R.string.address_found_none),
        subtitle = stringResource(if (ui.discovering) R.string.address_found_looking_detail else R.string.address_found_none_detail),
        tone = FluidTone.Neutral,
        leading = { Icon(Icons.Rounded.Lan, contentDescription = null) },
        badge = if (ui.discovering) ({ FluidSpinner() }) else null,
        disclosure = false,
      )
    } else {
      ui.found.forEachIndexed { index, nas ->
        if (index > 0) FluidListDivider()
        ChoiceRow(
          title = nas.name,
          subtitle = nas.ip,
          selected = form.selected == index,
          onSelect = {
            form.selected = index
            form.ip = nas.ip
            form.name = nas.name
          },
          icon = Icons.Rounded.Dns,
        )
      }
    }
  }
}

@Composable
private fun ManualFields(form: AddressForm, enabled: Boolean, onDone: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap)) {
    val ipInvalid = form.ip.isNotBlank() && !form.isValid
    FluidTextField(
      value = form.ip,
      onValueChange = {
        form.ip = it
        form.selected = null
      },
      label = stringResource(R.string.address_ip_label),
      placeholder = stringResource(R.string.address_ip_placeholder),
      enabled = enabled,
      isError = ipInvalid,
      supportingText = if (ipInvalid) stringResource(R.string.address_ip_invalid) else null,
      keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Uri,
        imeAction = ImeAction.Done,
      ),
      keyboardActions = KeyboardActions(onDone = { if (form.isValid) onDone() }),
    )
  }
}
