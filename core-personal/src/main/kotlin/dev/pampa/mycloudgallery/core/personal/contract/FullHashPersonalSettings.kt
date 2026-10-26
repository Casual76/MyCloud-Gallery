package dev.pampa.mycloudgallery.core.personal.contract

import kotlinx.coroutines.flow.StateFlow

// Fase 7, P8 (docs/fase7-progetto.md D27): l'interruttore dell'hash completo dei video del NAS "per tutti, di notte" (Q10 della Fase 4).

/**
 * "Controlla di notte i video del NAS" (Utility -> Doppioni): spento di serie. Acceso, l'attivita' `FULL_HASH` lavora anche fuori dai gruppi
 * "da verificare", con le regole dei video (attivita' pesante, Wi-Fi di casa). E' la voce [PersonalFields.SETTING_FULL_HASH_NIGHTLY], un
 * [PersonalValue.Flag] **sincronizzato** fra i dispositivi dello stesso account: l'ultima scelta vince. Implementazione: `SyncedFullHashSettings`.
 */
interface FullHashPersonalSettings {
  /** Spento di serie (voce assente). */
  val fullHashNightly: StateFlow<Boolean>

  suspend fun setFullHashNightly(on: Boolean)
}
