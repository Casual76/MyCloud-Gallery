package dev.pampa.mycloudgallery.feature.memories.settings

import dev.pampa.mycloudgallery.core.memories.contract.LessChoice
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicLibrary
import dev.pampa.mycloudgallery.core.memories.contract.MusicPackCatalog
import dev.pampa.mycloudgallery.core.memories.contract.MusicSettings
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackPin
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackState
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.core.memories.contract.NotifySettings
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesMessage
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsUi
import dev.pampa.mycloudgallery.feature.memories.contract.MusicTrackRow
import dev.pampa.mycloudgallery.feature.memories.contract.NotificationAccess
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.memories.contract.PeopleMemoriesRow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * IMPOSTAZIONI -> RICORDI E NOTIFICHE (Fase 9, P9; docs/fase9-progetto.md §1.5 `MemoriesSettingsState`, §2.3, §2.6, D11, D15, D16, D18, Q3, Q4, Q5 (a),
 * Q9 (a)). Non sa niente di Compose. Mette insieme, in un solo stato, cio' che gia' c'e' altrove:
 *
 *  - **Ricordi con le persone** ([Memories.peopleMemories], Q9): la riga c'e' solo con Persone attiva su questo dispositivo ([peopleAvailable]);
 *  - **Notifiche** ([NotifySettings], locali di questo dispositivo): accese, ora (a passi di 15 minuti), frequenza (Q4), schermata di blocco (Q5 (a):
 *    generica di serie); il **permesso** e il **canale** ([NotificationAccess], D18: "Consenti" da un tocco, o le impostazioni di Android);
 *  - **Persone e periodi nascosti dai ricordi** ([Memories.lessChoices]) con "Togli" per ognuno: l'effetto e' immediato (lo scaffale si ripulisce);
 *  - **Musica** (Q3): nessuna di serie, la canzone propria (il selettore di sistema lo apre `:app`, che salva la scelta in [MusicSettings]) o un
 *    brano del pacchetto di musica libera ([catalog], **vuoto** finche' l'utente non approva fonti e brani: la schermata lo dice).
 *
 * Q6 (a): **nessuna riga del filtro automatico** dei ricordi tristi. Le scelte delle notifiche non vanno nei log e non si sincronizzano (D10).
 */
class MemoriesSettingsController(
  private val memories: Memories,
  private val notify: NotifySettings,
  private val access: NotificationAccess,
  private val musicSettings: MusicSettings,
  private val library: MusicLibrary,
  private val peopleAvailable: StateFlow<Boolean>,
  private val scope: CoroutineScope,
  private val catalog: List<MusicTrackPin> = MusicPackCatalog.TRACKS,
) {
  private val message = MutableStateFlow<MemoriesMessage?>(null)

  private class Inputs(
    val people: Boolean,
    val available: Boolean,
    val prefs: NotifyPrefs,
    val permission: NotifyPermission,
    val less: List<LessChoice>,
  )

  private val inputs = combine(
    memories.peopleMemories, peopleAvailable, notify.prefs, access.state, memories.lessChoices,
  ) { people, available, prefs, permission, less -> Inputs(people, available, prefs, permission, less) }

  val ui: StateFlow<MemoriesSettingsUi> = combine(inputs, musicSettings.choice, library.tracks, message) { base, music, tracks, msg ->
    val row = when {
      !base.available -> PeopleMemoriesRow.HIDDEN
      base.people -> PeopleMemoriesRow.ON
      else -> PeopleMemoriesRow.OFF
    }
    MemoriesSettingsUi(
      state = MemoriesSettingsState(row, base.prefs, base.permission, base.less, music, tracks),
      tracks = catalog.map { pin -> MusicTrackRow(pin, tracks[pin.id] ?: MusicTrackState.NotInstalled, (music as? MusicChoice.FreeTrack)?.trackId == pin.id) },
      message = msg,
    )
  }.stateIn(
    scope, SharingStarted.Eagerly,
    MemoriesSettingsUi(
      MemoriesSettingsState(
        if (peopleAvailable.value) (if (memories.peopleMemories.value) PeopleMemoriesRow.ON else PeopleMemoriesRow.OFF) else PeopleMemoriesRow.HIDDEN,
        notify.prefs.value, access.state.value, memories.lessChoices.value, musicSettings.choice.value, library.tracks.value,
      ),
      tracks = catalog.map { pin -> MusicTrackRow(pin, library.tracks.value[pin.id] ?: MusicTrackState.NotInstalled, (musicSettings.choice.value as? MusicChoice.FreeTrack)?.trackId == pin.id) },
    ),
  )

  // --- Ricordi -----------------------------------------------------------------------------------------------------------------------

  fun setPeople(on: Boolean) = launch { memories.setPeopleMemories(on) }

  /** "Togli" per una scelta: torna come prima, subito (E-MEM22). */
  fun removeLess(choice: LessChoice) = launch { memories.setLess(choice.target, null) }

  // --- Notifiche ---------------------------------------------------------------------------------------------------------------------

  fun setNotifyEnabled(on: Boolean) = update { it.copy(enabled = on) }

  /** L'ora si sposta di [quarters] quarti d'ora (anche negativi), oltre la mezzanotte ricomincia. */
  fun shiftHour(quarters: Int) = update { it.copy(hour = it.hour.plusMinutes(quarters * NotifyRules.HOUR_STEP_MINUTES.toLong())) }

  fun setFrequency(frequency: NotifyFrequency) = update { it.copy(frequency = frequency) }

  fun setLockScreen(mode: LockScreenMode) = update { it.copy(lockScreen = mode) }

  /** La pagina torna in primo piano (magari dalle impostazioni di Android): si rilegge il permesso e il canale. */
  fun refreshAccess() = access.refresh()

  private fun update(change: (NotifyPrefs) -> NotifyPrefs) = launch { notify.set(change(notify.prefs.value)) }

  // --- Musica ------------------------------------------------------------------------------------------------------------------------

  fun chooseNoMusic() = launch { musicSettings.set(MusicChoice.None) }

  /** Usa un brano del pacchetto, solo se e' installato (altrimenti non si puo'). */
  fun useTrack(trackId: String) = launch {
    if (library.tracks.value[trackId] == MusicTrackState.Ready && catalog.any { it.id == trackId }) musicSettings.set(MusicChoice.FreeTrack(trackId))
  }

  /** Installa un brano (un tocco dell'utente); se non si puo' adesso lo dice. */
  fun installTrack(trackId: String) = launch {
    if (catalog.none { it.id == trackId } || !library.install(trackId)) message.value = MemoriesMessage.FAILED
  }

  fun dismissMessage() {
    message.value = null
  }

  private fun launch(block: suspend () -> Unit) {
    scope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message.value = MemoriesMessage.FAILED
      }
    }
  }
}
