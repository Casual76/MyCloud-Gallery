package dev.pampa.mycloudgallery.core.personal.settings

import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * La voce `faces` di Persone (Fase 8, P7, D3) sopra lo store dei dati personali, sincronizzata fra i dispositivi dello stesso account come
 * le altre impostazioni (l'ultima modifica vince con l'ora del NAS; due attivazioni quasi insieme, E-F9, hanno un solo vincitore uguale
 * ovunque). Stesso modello di [SyncedSearchSettings]:
 *
 * - **Vale subito, anche senza l'ora del NAS.** Si legge [PersonalStore.effectiveEntries]: lo stato unito con sopra la scelta fatta qui ancora
 *   "da datare". Una scelta fatta fuori casa non ha l'ora e non e' nello stato che si pubblica; la sincronizzazione viene dopo e la voce
 *   prende l'ora alla prima volta ([PersonalStore.stamp]) senza perdere l'ordine. [current] legge il valore dello store **adesso**, senza
 *   aspettare che [setting] (un flusso su un altro thread) si sia mosso: chi guarda lo stato prima di ogni passo (come fa
 *   `FacesSwitch.currentState`) non vede una scelta vecchia.
 * - **Voce assente o lapide = spenta; forma sconosciuta = [FacesSetting.Unknown]** (E-F12, [FacesSettingCodec.parse]).
 * - **Non si riscrive cio' che non si capisce**: con una voce sconosciuta [set] non scrive (un'app piu' vecchia non cancella ne' sostituisce
 *   l'attivazione di un'app piu' nuova).
 * - **"Da datare"** ([syncPending]): lo store da' alle voci locali ancora senza ora del NAS l'ora piu' alta possibile
 *   ([PersonalStore.effective]); e' il segno che la scelta non e' ancora arrivata agli altri dispositivi.
 *
 * Mai la generazione nei log: questa classe non scrive niente nei log.
 */
class SyncedFacesSettings(
  private val store: PersonalStore,
  scope: CoroutineScope,
) : FacesPersonalSettings {

  override val setting: StateFlow<FacesSetting?> = store.effectiveEntries
    .map { FacesSettingCodec.read(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, FacesSettingCodec.read(store.effectiveEntries.value))

  override fun current(): FacesSetting? = FacesSettingCodec.read(store.effectiveEntries.value)

  override val syncPending: StateFlow<Boolean> = store.effectiveEntries
    .map { pendingOf(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, pendingOf(store.effectiveEntries.value))

  override suspend fun set(setting: FacesSetting): Boolean {
    // Una voce sconosciuta si legge prima di scrivere: lo stato effettivo di adesso, non quello di un flusso che puo' ritardare.
    if (current() == FacesSetting.Unknown) {
      require(setting != FacesSetting.Unknown) { "Una voce sconosciuta non si scrive." }
      return false
    }
    store.set(FacesSettingCodec.KEY, FacesSettingCodec.valueOf(setting))
    return true
  }

  private fun pendingOf(entries: Map<PersonalKey, PersonalEntry>): Boolean = entries[FacesSettingCodec.KEY]?.atNas == Long.MAX_VALUE
}
