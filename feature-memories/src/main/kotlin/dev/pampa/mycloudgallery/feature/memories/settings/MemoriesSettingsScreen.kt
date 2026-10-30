package dev.pampa.mycloudgallery.feature.memories.settings

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackState
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.feature.memories.R
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsUi
import dev.pampa.mycloudgallery.feature.memories.contract.MusicTrackRow
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.memories.contract.PeopleMemoriesRow
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesDimens
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesMessageBlock
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesText
import dev.pampa.mycloudgallery.feature.memories.ui.RadioMark

// IMPOSTAZIONI -> RICORDI E NOTIFICHE (Fase 9, P9; docs/fase9-progetto.md §1.5 `MemoriesSettingsState`, §2.3, §2.6, D15, D16, D18, Q3, Q4, Q5 (a), Q9 (a)).
// Righe raggruppate di Fluid (regola 2 del design system: il tono sulla piastrella, mai sulla riga); nessuna riga del filtro automatico (Q6 (a)). Ogni
// controllo e' da almeno 48 dp e nessuno ne copre un altro: i bottoni stanno nella riga o sotto il gruppo, mai sopra altro.

/** Cosa la pagina chiede fuori da se': ognuna passa dal controllore, tranne le uscite verso Android (permesso, canale, selettore della canzone). */
internal interface MemoriesSettingsHandlers {
  fun onBack()

  fun onSetPeople(on: Boolean)

  fun onSetNotify(on: Boolean)

  fun onShiftHour(quarters: Int)

  fun onFrequency(frequency: NotifyFrequency)

  fun onLockScreen(mode: LockScreenMode)

  fun onAllow()

  fun onOpenChannel()

  fun onRemoveLess(choice: LessChoice)

  fun onNoMusic()

  fun onPickSong()

  fun onInstallTrack(trackId: String)

  fun onUseTrack(trackId: String)

  fun onDismissMessage()
}

@Composable
internal fun MemoriesSettingsScreen(ui: MemoriesSettingsUi, handlers: MemoriesSettingsHandlers, modifier: Modifier = Modifier) {
  val state = ui.state
  FluidScreen(title = stringResource(R.string.memorie_settings_title), modifier = modifier, onBack = handlers::onBack, contentMaxWidth = MemoriesDimens.SheetWidth) {
    ui.message?.let { message -> item(key = "message") { MemoriesMessageBlock(message, handlers::onDismissMessage) } }
    if (state.people != PeopleMemoriesRow.HIDDEN) peopleSection(state.people == PeopleMemoriesRow.ON, handlers)
    notifySection(ui, handlers)
    if (state.notify.enabled) {
      frequencySection(state.notify.frequency, handlers)
      lockSection(state.notify.lockScreen, handlers)
    }
    hiddenSection(state.lessChoices, handlers)
    musicSection(ui, handlers)
  }
}

/** Una riga con un interruttore: tutta la riga si tocca; TalkBack legge "acceso" o "spento". */
@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  FluidListRow(
    title = title,
    subtitle = subtitle,
    modifier = Modifier.semantics {
      role = Role.Switch
      toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
    },
    badge = { FluidSwitch(checked = checked, onCheckedChange = null) },
    onClick = { onChange(!checked) },
    disclosure = false,
  )
}

/** Una riga a scelta singola: il pallino a destra, per TalkBack "selezionata". */
@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  FluidListRow(
    title = title,
    subtitle = subtitle,
    modifier = Modifier.semantics { role = Role.RadioButton },
    badge = { RadioMark(selected) },
    selected = selected,
    onClick = onClick,
    disclosure = false,
  )
}

private fun LazyListScope.peopleSection(on: Boolean, handlers: MemoriesSettingsHandlers) {
  item(key = "header-memories") { FluidSectionHeader(title = stringResource(R.string.memorie_settings_memories_header)) }
  item(key = "people") {
    FluidListGroup {
      SwitchRow(stringResource(R.string.memorie_settings_people), stringResource(R.string.memorie_settings_people_detail), on, handlers::onSetPeople)
    }
  }
}

private fun LazyListScope.notifySection(ui: MemoriesSettingsUi, handlers: MemoriesSettingsHandlers) {
  val prefs = ui.state.notify
  item(key = "header-notify") { FluidSectionHeader(title = stringResource(R.string.memorie_settings_notify_header)) }
  item(key = "notify") {
    FluidListGroup {
      SwitchRow(stringResource(R.string.memorie_settings_notify_enabled), stringResource(R.string.memorie_settings_notify_enabled_detail), prefs.enabled, handlers::onSetNotify)
      if (prefs.enabled) {
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.memorie_settings_hour), subtitle = MemoriesText.time(prefs.hour), disclosure = false)
      }
    }
  }
  if (prefs.enabled) {
    item(key = "hour-steps") {
      Row(horizontalArrangement = Arrangement.spacedBy(MemoriesDimens.TightGap), modifier = Modifier.fillMaxWidth()) {
        FluidButton(
          text = stringResource(R.string.memorie_settings_hour_earlier), onClick = { handlers.onShiftHour(-1) },
          style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, modifier = Modifier.weight(1f),
        )
        FluidButton(
          text = stringResource(R.string.memorie_settings_hour_later), onClick = { handlers.onShiftHour(1) },
          style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, modifier = Modifier.weight(1f),
        )
      }
    }
    item(key = "permission") { PermissionBlock(ui.state.permission, handlers) }
  }
}

/** Il permesso e il canale (D18, E-NT1, E-NT2): dice come stanno le cose e, se serve, offre **un** tocco (mai una richiesta che l'utente non ha chiesto). */
@Composable
private fun PermissionBlock(permission: NotifyPermission, handlers: MemoriesSettingsHandlers) {
  val message = when (permission) {
    NotifyPermission.GRANTED -> R.string.memorie_settings_permission_granted
    NotifyPermission.ASKABLE -> R.string.memorie_settings_permission_askable
    NotifyPermission.DENIED -> R.string.memorie_settings_permission_denied
    NotifyPermission.CHANNEL_OFF -> R.string.memorie_settings_channel_off
  }
  FluidListGroup {
    FluidListRow(
      title = stringResource(message),
      subtitle = if (permission == NotifyPermission.CHANNEL_OFF) stringResource(R.string.memorie_settings_channel_off_detail) else "",
      tone = if (permission == NotifyPermission.GRANTED) FluidTone.Success else FluidTone.Warning,
      disclosure = false,
    )
    val action = when (permission) {
      NotifyPermission.GRANTED -> null
      NotifyPermission.ASKABLE -> R.string.memorie_settings_permission_allow to handlers::onAllow
      NotifyPermission.DENIED -> R.string.memorie_settings_permission_open to handlers::onAllow
      NotifyPermission.CHANNEL_OFF -> R.string.memorie_settings_channel_open to handlers::onOpenChannel
    }
    if (action != null) {
      FluidListDivider()
      FluidButton(text = stringResource(action.first), onClick = action.second, style = FluidButtonStyle.Plain, size = FluidButtonSize.Large, fillWidth = true)
    }
  }
}

private fun LazyListScope.frequencySection(current: NotifyFrequency, handlers: MemoriesSettingsHandlers) {
  item(key = "header-frequency") { FluidSectionHeader(title = stringResource(R.string.memorie_settings_frequency_header)) }
  item(key = "frequency") {
    FluidListGroup {
      RadioRow(stringResource(R.string.memorie_settings_freq_few), stringResource(R.string.memorie_settings_freq_few_detail), current == NotifyFrequency.FEW_PER_WEEK) {
        handlers.onFrequency(NotifyFrequency.FEW_PER_WEEK)
      }
      FluidListDivider()
      RadioRow(stringResource(R.string.memorie_settings_freq_daily), stringResource(R.string.memorie_settings_freq_daily_detail), current == NotifyFrequency.DAILY) {
        handlers.onFrequency(NotifyFrequency.DAILY)
      }
      FluidListDivider()
      RadioRow(
        stringResource(R.string.memorie_settings_freq_anniversaries), stringResource(R.string.memorie_settings_freq_anniversaries_detail), current == NotifyFrequency.ANNIVERSARIES,
      ) { handlers.onFrequency(NotifyFrequency.ANNIVERSARIES) }
    }
  }
}

private fun LazyListScope.lockSection(current: LockScreenMode, handlers: MemoriesSettingsHandlers) {
  item(key = "header-lock") { FluidSectionHeader(title = stringResource(R.string.memorie_settings_lock_header)) }
  item(key = "lock") {
    FluidListGroup {
      RadioRow(stringResource(R.string.memorie_settings_lock_generic), stringResource(R.string.memorie_settings_lock_generic_detail), current == LockScreenMode.GENERIC) {
        handlers.onLockScreen(LockScreenMode.GENERIC)
      }
      FluidListDivider()
      RadioRow(stringResource(R.string.memorie_settings_lock_title), stringResource(R.string.memorie_settings_lock_title_detail), current == LockScreenMode.TITLE_AND_PHOTO) {
        handlers.onLockScreen(LockScreenMode.TITLE_AND_PHOTO)
      }
    }
  }
}

/** "Persone e periodi nascosti dai ricordi": una riga per scelta con "Togli". Con Persone spenta le persone sono "Una persona (N)": mai un nome inventato. */
private fun LazyListScope.hiddenSection(choices: List<LessChoice>, handlers: MemoriesSettingsHandlers) {
  item(key = "header-hidden") { FluidSectionHeader(title = stringResource(R.string.memorie_settings_hidden_header)) }
  if (choices.isEmpty()) {
    item(key = "hidden-empty") { FluidSectionFootnote(text = stringResource(R.string.memorie_settings_hidden_empty)) }
    return
  }
  item(key = "hidden") {
    val resources = LocalContext.current.resources
    FluidListGroup {
      var anonymous = 0
      choices.forEachIndexed { index, choice ->
        if (index > 0) FluidListDivider()
        if (choice.target is LessTarget.Person && choice.personName == null) anonymous++
        val title = lessTitle(resources, choice, anonymous)
        val remove = resources.getString(R.string.memorie_settings_hidden_remove_spoken, title)
        FluidListRow(
          title = title,
          subtitle = stringResource(if (choice.level == LessLevel.LESS) R.string.memorie_settings_hidden_level_less else R.string.memorie_settings_hidden_level_never),
          badge = {
            FluidButton(
              text = stringResource(R.string.memorie_settings_hidden_remove), onClick = { handlers.onRemoveLess(choice) },
              style = FluidButtonStyle.Plain, size = FluidButtonSize.Large, modifier = Modifier.semantics { contentDescription = remove },
            )
          },
          disclosure = false,
        )
      }
    }
  }
}

/** Il titolo di una scelta: il nome (letto adesso, mai salvato), "Una persona (N)" se Persone non c'e', o il periodo. */
internal fun lessTitle(resources: Resources, choice: LessChoice, anonymousIndex: Int): String = when (val target = choice.target) {
  is LessTarget.Person -> choice.personName ?: resources.getString(R.string.memorie_settings_hidden_person, anonymousIndex.coerceAtLeast(1))
  is LessTarget.Days -> MemoriesText.period(resources, target.range)
}

private fun LazyListScope.musicSection(ui: MemoriesSettingsUi, handlers: MemoriesSettingsHandlers) {
  val music = ui.state.music
  item(key = "header-music") { FluidSectionHeader(title = stringResource(R.string.memorie_settings_music_header)) }
  item(key = "music") {
    FluidListGroup {
      RadioRow(stringResource(R.string.memorie_settings_music_none), stringResource(R.string.memorie_settings_music_none_detail), music == MusicChoice.None, handlers::onNoMusic)
      FluidListDivider()
      RadioRow(
        stringResource(R.string.memorie_settings_music_own),
        stringResource(if (music is MusicChoice.OwnSong) R.string.memorie_settings_music_own_chosen else R.string.memorie_settings_music_own_detail),
        music is MusicChoice.OwnSong, handlers::onPickSong,
      )
    }
  }
  if (music is MusicChoice.OwnSong) {
    item(key = "music-change") {
      FluidButton(
        text = stringResource(R.string.memorie_settings_music_own_change), onClick = handlers::onPickSong,
        style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true,
      )
    }
  } else {
    item(key = "music-pick") {
      FluidButton(
        text = stringResource(R.string.memorie_settings_music_own_pick), onClick = handlers::onPickSong,
        style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true,
      )
    }
  }
  item(key = "header-free") { FluidSectionHeader(title = stringResource(R.string.memorie_settings_music_free)) }
  if (ui.tracks.isEmpty()) {
    // Q3: il pacchetto di musica libera non c'e' finche' l'utente non approva fonti e brani: la pagina lo dice con chiarezza.
    item(key = "free-empty") {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.memorie_settings_music_free_empty), subtitle = stringResource(R.string.memorie_settings_music_free_empty_detail),
          tone = FluidTone.Neutral, disclosure = false,
        )
      }
    }
  } else {
    item(key = "free") {
      FluidListGroup {
        ui.tracks.forEachIndexed { index, row ->
          if (index > 0) FluidListDivider()
          TrackRow(row, handlers)
        }
      }
    }
  }
}

@Composable
private fun TrackRow(row: MusicTrackRow, handlers: MemoriesSettingsHandlers) {
  val credit = stringResource(R.string.memorie_settings_music_track_credit, row.pin.author, row.pin.license.code)
  val status = when (val state = row.state) {
    MusicTrackState.NotInstalled -> stringResource(R.string.memorie_settings_music_track_install)
    is MusicTrackState.Installing -> stringResource(R.string.memorie_settings_music_track_installing, state.percent)
    MusicTrackState.Ready -> stringResource(if (row.chosen) R.string.memorie_settings_music_track_chosen else R.string.memorie_settings_music_track_use)
    is MusicTrackState.Failed -> stringResource(R.string.memorie_settings_music_track_failed)
  }
  FluidListRow(
    title = row.pin.title,
    subtitle = "$credit · $status",
    badge = if (row.state == MusicTrackState.Ready) {
      { RadioMark(row.chosen) }
    } else {
      null
    },
    onClick = when (row.state) {
      MusicTrackState.Ready -> ({ handlers.onUseTrack(row.pin.id) })
      is MusicTrackState.Installing -> null
      MusicTrackState.NotInstalled, is MusicTrackState.Failed -> ({ handlers.onInstallTrack(row.pin.id) })
    },
    disclosure = false,
  )
}

/** Nessuna azione: per le anteprime e gli screenshot. */
internal object NoMemoriesSettingsHandlers : MemoriesSettingsHandlers {
  override fun onBack() = Unit

  override fun onSetPeople(on: Boolean) = Unit

  override fun onSetNotify(on: Boolean) = Unit

  override fun onShiftHour(quarters: Int) = Unit

  override fun onFrequency(frequency: NotifyFrequency) = Unit

  override fun onLockScreen(mode: LockScreenMode) = Unit

  override fun onAllow() = Unit

  override fun onOpenChannel() = Unit

  override fun onRemoveLess(choice: LessChoice) = Unit

  override fun onNoMusic() = Unit

  override fun onPickSong() = Unit

  override fun onInstallTrack(trackId: String) = Unit

  override fun onUseTrack(trackId: String) = Unit

  override fun onDismissMessage() = Unit
}
