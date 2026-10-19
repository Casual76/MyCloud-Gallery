package dev.pampa.mycloudgallery.feature.integrations.picker.testing

import androidx.fragment.app.FragmentActivity
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.picker.EntryUnlock
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import kotlinx.coroutines.awaitCancellation

// Fase 5B (P10): lo sblocco finto del selettore, per i test di P10, P12 e P14. Mai nella release: package `testing`.

/**
 * Uno sblocco scelto dal test: riesce o no, oppure resta aperto finche' l'activity non si chiude (la schermata di sistema delle credenziali).
 * Quando riesce il cancello [gate] passa a `ALLOWED`, come farebbe `:app` dopo un `AppLockController.endAuthentication(true)`.
 */
class FakeEntryUnlock(
  private val gate: FakeEntryGate,
  var succeeds: Boolean = true,
  var hangs: Boolean = false,
) : EntryUnlock {

  var calls: Int = 0
    private set

  override suspend fun unlock(activity: FragmentActivity): Boolean {
    calls++
    if (hangs) awaitCancellation()
    if (succeeds) gate.decision = EntryDecision.ALLOWED
    return succeeds
  }
}
