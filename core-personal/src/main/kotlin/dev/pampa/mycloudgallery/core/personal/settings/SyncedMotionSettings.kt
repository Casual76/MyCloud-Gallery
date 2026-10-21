package dev.pampa.mycloudgallery.core.personal.settings

import dev.pampa.mycloudgallery.core.personal.contract.MotionPersonalSettings
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
 * "Animazione delle foto in movimento" (Fase 6, P14, D50, risposta Q16 (b)): l'interruttore nelle Impostazioni della Libreria, come
 * impostazione personale **sincronizzata** fra i dispositivi dello stesso account. E' la voce [PersonalFields.SETTING_MOTION_ANIMATION],
 * un [PersonalValue.Flag] dello stesso stato unito dei preferiti: l'ultima modifica vince (ora del NAS) e vale su tutti i dispositivi.
 *
 * **Voce assente = accesa** (default): un account che non ha mai toccato l'interruttore anima le foto in movimento. Un valore che non e'
 * un [PersonalValue.Flag] (una voce di una versione piu' nuova o ostile) vale come assente, cioe' acceso: l'animazione non si spegne per
 * un valore che non si capisce. Il viewer non legge questo modulo: `:app` lo lega a `MotionAnimationSetting` del viewer (P11).
 *
 * File a parte da [SyncedPersonalSettings] (`text_reading` e `search_order`, di P8): le due implementazioni non si toccano.
 */
class SyncedMotionSettings(
  private val store: PersonalStore,
  scope: CoroutineScope,
) : MotionPersonalSettings {

  override val motionAnimation: StateFlow<Boolean> = store.entries
    .map { animationOf(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, animationOf(store.entries.value))

  override suspend fun setMotionAnimation(on: Boolean) {
    store.set(KEY, PersonalValue.Flag(on))
  }

  private fun animationOf(entries: Map<PersonalKey, PersonalEntry>): Boolean =
    (entries[KEY]?.value as? PersonalValue.Flag)?.on ?: true

  private companion object {
    val KEY = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_MOTION_ANIMATION)
  }
}
