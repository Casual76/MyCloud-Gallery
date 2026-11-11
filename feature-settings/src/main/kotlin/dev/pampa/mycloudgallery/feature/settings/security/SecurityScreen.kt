package dev.pampa.mycloudgallery.feature.settings.security

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.security.AppLockMode
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.core.security.lock.AppLockTimeout
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.port.AppLockPreferences
import dev.pampa.mycloudgallery.feature.settings.port.ScreenLockProbe
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback
import dev.pampa.mycloudgallery.feature.settings.ui.FeedbackNote
import dev.pampa.mycloudgallery.feature.settings.ui.Note
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph
import dev.pampa.mycloudgallery.feature.settings.ui.StatusPill
import dev.pampa.mycloudgallery.feature.settings.ui.rememberSystemAuthenticator
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue

internal data class SecurityUiState(
  val settings: AppLockSettings,
  val screenLock: ScreenLockStatus,
  val feedback: Feedback? = null,
) {
  val lockEnabled: Boolean get() = settings.isEnabled
}

/**
 * Sicurezza → blocco dell'app (piano 1.5, C14, E-S3, E-S4).
 *
 *  - Il blocco usa **sempre la credenziale di sistema** del telefono: nessun PIN dell'app, nessun contatore
 *    di tentativi (li limita il sistema; l'app non cancella niente).
 *  - **Accendere** richiede che il telefono abbia un blocco schermo: altrimenti l'app resterebbe chiusa per
 *    sempre (fail closed) e quindi non si accende.
 *  - **Spegnere** chiede prima la credenziale del telefono: chi trova il telefono sbloccato non toglie il
 *    blocco in due tocchi.
 */
@HiltViewModel
internal class SecurityViewModel @Inject constructor(
  private val preferences: AppLockPreferences,
  private val probe: ScreenLockProbe,
  private val controller: AppLockController,
) : ViewModel() {

  private val screenLock = MutableStateFlow(probe.status())
  private val feedback = MutableStateFlow<Feedback?>(null)

  val state: StateFlow<SecurityUiState> = combine(preferences.settings, screenLock, feedback) { settings, lock, note ->
    SecurityUiState(settings = settings, screenLock = lock, feedback = note)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
    initialValue = SecurityUiState(AppLockSettings(), probe.status()),
  )

  /** Rilegge se il telefono ha un blocco schermo (l'utente potrebbe averlo appena impostato). */
  fun refresh() {
    screenLock.value = probe.status()
  }

  /** Accende il blocco. Falso (con messaggio) se il telefono non ha un blocco schermo. */
  fun enable() {
    refresh()
    if (screenLock.value.needsWarning) {
      feedback.value = Feedback(R.string.security_need_lock_title, R.string.security_need_lock, FluidTone.Warning)
      return
    }
    feedback.value = null
    viewModelScope.launch { save(preferences.settings.first().copy(mode = AppLockMode.SYSTEM_CREDENTIAL)) }
  }

  /** Spegne il blocco, **solo dopo** la credenziale del telefono ([authenticate] la chiede la pagina). */
  suspend fun disable(authenticate: suspend () -> AuthResult) {
    when (authenticate()) {
      AuthResult.Success -> {
        feedback.value = null
        save(preferences.settings.first().copy(mode = AppLockMode.OFF))
      }
      AuthResult.Cancelled, AuthResult.Interrupted -> Unit
      else -> feedback.value = Feedback(R.string.security_auth_failed_title, R.string.security_auth_failed, FluidTone.Warning)
    }
  }

  fun setTimeout(timeout: AppLockTimeout) {
    viewModelScope.launch { save(preferences.settings.first().copy(timeout = timeout)) }
  }

  /**
   * OB-4 (review generale): con la memoria piena salvare non riesce. Prima l'eccezione di DataStore chiudeva l'app; ora il blocco resta com'era (l'interruttore torna indietro) e
   * la pagina lo dice con lo stesso avviso degli altri esiti, con il perche' e cosa fare.
   */
  private suspend fun save(settings: AppLockSettings) {
    if (!preferences.update(settings)) {
      feedback.value = Feedback(R.string.security_not_saved_title, R.string.security_not_saved, FluidTone.Warning)
    }
  }

  /** "Blocca ora": richiude subito (senza effetto se il blocco e' spento). */
  fun lockNow() {
    controller.lockNow()
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}

internal class SecurityActions(
  val onBack: () -> Unit,
  val onEnable: () -> Unit,
  val onDisable: () -> Unit,
  val onTimeout: (AppLockTimeout) -> Unit,
  val onLockNow: () -> Unit,
  val onOpenSystemSecurity: () -> Unit,
  val onOpenBackupNotice: () -> Unit,
) {
  companion object {
    val None = SecurityActions({}, {}, {}, {}, {}, {}, {})
  }
}

@Composable
internal fun SecurityRoute(onBack: () -> Unit, openBackupNotice: () -> Unit) {
  val viewModel: SecurityViewModel = hiltViewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val authenticator = rememberSystemAuthenticator()
  val scope = rememberCoroutineScope()
  val prompt = AuthPromptText(
    title = stringResource(R.string.security_disable_prompt_title),
    subtitle = stringResource(R.string.security_disable_prompt_subtitle),
  )
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
  SecurityScreen(
    state = state,
    actions = SecurityActions(
      onBack = onBack,
      onEnable = viewModel::enable,
      onDisable = {
        scope.launch {
          viewModel.disable {
            authenticator?.authenticate(prompt)
              ?: AuthResult.NotAvailable(dev.pampa.mycloudgallery.core.security.lock.AuthAvailability.UNSUPPORTED)
          }
        }
      },
      onTimeout = viewModel::setTimeout,
      onLockNow = viewModel::lockNow,
      onOpenSystemSecurity = {
        try {
          context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
          // Nessuna schermata di impostazioni di sicurezza: non c'e' altro da fare.
        }
      },
      onOpenBackupNotice = openBackupNotice,
    ),
  )
}

@Composable
internal fun SecurityScreen(state: SecurityUiState, actions: SecurityActions) {
  val enabled = state.lockEnabled
  val noScreenLock = state.screenLock.needsWarning
  FluidScreen(
    title = stringResource(R.string.security_title),
    onBack = actions.onBack,
    ambient = FluidAmbient(tone = if (noScreenLock) FluidHeroTone.Alert else FluidHeroTone.Secondary, motif = FluidHeroMotif.Glow),
  ) {
    item {
      FluidHero(
        tone = if (enabled) FluidHeroTone.PrimaryToSecondary else if (noScreenLock) FluidHeroTone.Alert else FluidHeroTone.Tertiary,
        motif = FluidHeroMotif.Dots,
        eyebrow = stringResource(R.string.security_eyebrow),
        value = stringResource(if (enabled) R.string.security_value_on else R.string.security_value_off),
        title = stringResource(if (enabled) R.string.security_hero_on_title else R.string.security_hero_off_title),
        description = if (enabled) {
          // CU-14: il tempo entra a meta' frase, con la minuscola (le scelte hanno la maiuscola perche' sono righe a se').
          stringResource(R.string.security_hero_on_detail, stringResource(state.settings.timeout.labelRes()).replaceFirstChar { it.lowercase() })
        } else {
          stringResource(R.string.security_hero_off_detail)
        },
        icon = if (enabled) Icons.Rounded.Lock else Icons.Rounded.PhonelinkLock,
      )
    }

    if (noScreenLock) {
      item {
        Note(
          title = stringResource(R.string.security_no_lock_title),
          message = stringResource(R.string.security_no_lock),
          tone = FluidTone.Warning,
        )
      }
      item {
        FluidButton(
          text = stringResource(R.string.security_open_system),
          onClick = actions.onOpenSystemSecurity,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          fillWidth = true,
        )
      }
    }
    state.feedback?.let { item { FeedbackNote(it) } }

    item { GallerySectionHeader(title = stringResource(R.string.security_section_lock)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.security_lock_row),
          subtitle = stringResource(R.string.security_lock_row_detail),
          tone = if (enabled) FluidTone.Success else FluidTone.Neutral,
          leading = { RowGlyph(Icons.Rounded.Fingerprint) },
          disclosure = false,
          onClick = if (enabled) actions.onDisable else actions.onEnable,
          badge = { FluidSwitch(checked = enabled, onCheckedChange = null) },
          modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Switch
            toggleableState = ToggleableState(enabled)
          },
        )
        if (enabled) {
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.security_lock_now),
            subtitle = stringResource(R.string.security_lock_now_detail),
            tone = FluidTone.Primary,
            leading = { RowGlyph(Icons.Rounded.LockClock) },
            onClick = actions.onLockNow,
          )
        }
      }
    }

    if (enabled) {
      item {
        GallerySectionHeader(
          title = stringResource(R.string.security_section_timeout),
          detail = stringResource(R.string.security_section_timeout_detail),
        )
      }
      item {
        FluidListGroup {
          AppLockTimeout.entries.forEachIndexed { index, timeout ->
            if (index > 0) FluidListDivider()
            val selected = timeout == state.settings.timeout
            FluidListRow(
              title = stringResource(timeout.labelRes()),
              subtitle = if (timeout == AppLockTimeout.DEFAULT) stringResource(R.string.security_timeout_default) else "",
              selected = selected,
              disclosure = false,
              onClick = { actions.onTimeout(timeout) },
              badge = {
                if (selected) {
                  Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.selected), tint = MaterialTheme.colorScheme.primary)
                }
              },
            )
          }
        }
      }
    }

    item { GallerySectionHeader(title = stringResource(R.string.security_section_protects)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.security_protects_keys),
          subtitle = stringResource(R.string.security_protects_keys_detail),
          tone = FluidTone.Info,
          leading = { RowGlyph(Icons.Rounded.Key) },
          disclosure = false,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.security_protects_screens),
          subtitle = stringResource(R.string.security_protects_screens_detail),
          tone = FluidTone.Info,
          leading = { RowGlyph(Icons.Rounded.VisibilityOff) },
          disclosure = false,
        )
      }
    }

    item { GallerySectionHeader(title = stringResource(R.string.security_section_backup)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_backup_notice),
          subtitle = stringResource(R.string.security_backup_detail),
          tone = FluidTone.Warning,
          leading = { RowGlyph(Icons.Rounded.Backup) },
          labels = { StatusPill(stringResource(R.string.security_backup_pill), FluidTone.Warning) },
          onClick = actions.onOpenBackupNotice,
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.security_footnote)) }
  }
}

private fun AppLockTimeout.labelRes(): Int = when (this) {
  AppLockTimeout.IMMEDIATELY -> R.string.timeout_immediately
  AppLockTimeout.AFTER_30_SECONDS -> R.string.timeout_30s
  AppLockTimeout.AFTER_1_MINUTE -> R.string.timeout_1m
  AppLockTimeout.AFTER_5_MINUTES -> R.string.timeout_5m
  AppLockTimeout.AFTER_15_MINUTES -> R.string.timeout_15m
}
