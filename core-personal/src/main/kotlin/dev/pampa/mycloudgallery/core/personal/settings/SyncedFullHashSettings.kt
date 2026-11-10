package dev.pampa.mycloudgallery.core.personal.settings

import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.personal.contract.FullHashPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * "Controlla di notte i video del NAS" (Fase 7, P8, D27, Q10 della Fase 4): l'interruttore di Utility -> Doppioni, come impostazione personale
 * **sincronizzata** fra i dispositivi dello stesso account. E' la voce [PersonalFields.SETTING_FULL_HASH_NIGHTLY], un [PersonalValue.Flag] dello
 * stesso stato unito dei preferiti: l'ultima modifica vince (ora del NAS) e vale su tutti i dispositivi.
 *
 * **Voce assente = spento**, e un valore che non e' un [PersonalValue.Flag] (una voce di una versione piu' nuova o ostile) vale come assente,
 * cioe' spento: un'attivita' pesante (otto ore di letture dal NAS) non si accende per un valore che non si capisce. Legge
 * [PersonalStore.effectiveEntries]: la scelta vale subito anche senza l'ora del NAS, come le altre impostazioni (VM-2 della Fase 6).
 *
 * File a parte da [SyncedMotionSettings] e [SyncedPersonalSettings]: le implementazioni non si toccano.
 */
class SyncedFullHashSettings(
  private val store: PersonalStore,
  scope: CoroutineScope,
) : FullHashPersonalSettings {

  override val fullHashNightly: StateFlow<Boolean> = store.effectiveEntries
    .map { nightlyOf(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, nightlyOf(store.effectiveEntries.value))

  override suspend fun setFullHashNightly(on: Boolean) {
    QuietWrites.attempt("impronta completa di notte") { store.set(KEY, PersonalValue.Flag(on)) }
  }

  private fun nightlyOf(entries: Map<PersonalKey, PersonalEntry>): Boolean =
    (entries[KEY]?.value as? PersonalValue.Flag)?.on ?: false

  private companion object {
    val KEY = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_FULL_HASH_NIGHTLY)
  }
}
