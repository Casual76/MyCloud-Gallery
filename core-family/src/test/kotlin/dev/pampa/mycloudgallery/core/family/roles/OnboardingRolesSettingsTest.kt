package dev.pampa.mycloudgallery.core.family.roles

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.RolesChange
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingChoices
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingSnapshot
import dev.pampa.mycloudgallery.core.family.onboarding.StoreState
import dev.pampa.mycloudgallery.core.family.support.InMemoryOnboardingStateStore
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

// Fase 6 (P8, D15, risposta Q3): i ruoli di questo dispositivo cambiano dopo l'onboarding e si ripubblicano subito.
class OnboardingRolesSettingsTest {

  private class CountingRepublisher(var result: () -> Boolean = { true }) : RolesRepublisher {
    var calls = 0

    override suspend fun republish(): Boolean {
      calls++
      return result()
    }
  }

  private fun finished(indexer: Boolean, preparer: Boolean): OnboardingSnapshot =
    OnboardingSnapshot.EMPTY.copy(finished = true, choices = OnboardingChoices(indexer = indexer, preparer = preparer))

  private fun TestScope.settings(store: InMemoryOnboardingStateStore, publisher: RolesRepublisher): OnboardingRolesSettings {
    val settings = OnboardingRolesSettings(store, publisher, backgroundScope)
    runCurrent()
    return settings
  }

  @Test
  fun primaDellaFineDellOnboardingNienteRuoliENienteSalvataggio() = runTest {
    val store = InMemoryOnboardingStateStore()
    val publisher = CountingRepublisher()
    val settings = settings(store, publisher)
    assertThat(settings.roles.value).isEmpty()
    assertThat(settings.setRoles(indexer = true, preparer = true)).isEqualTo(RolesChange.NotConfigured)
    assertThat(store.writes).isEqualTo(0)
    assertThat(publisher.calls).isEqualTo(0)
  }

  @Test
  fun unArchivioNonLeggibileNonSalvaNiente() = runTest {
    val store = InMemoryOnboardingStateStore(finished(indexer = true, preparer = true))
    val publisher = CountingRepublisher()
    val settings = settings(store, publisher)
    store.setState(StoreState.Unavailable)
    runCurrent()
    assertThat(settings.roles.value).isEmpty()
    assertThat(settings.setRoles(indexer = false, preparer = true)).isEqualTo(RolesChange.NotConfigured)
    assertThat(publisher.calls).isEqualTo(0)
  }

  @Test
  fun iRuoliSiLeggonoDalleScelteDelPasso7() = runTest {
    val settings = settings(InMemoryOnboardingStateStore(finished(indexer = true, preparer = false)), CountingRepublisher())
    assertThat(settings.roles.value).containsExactly(DeviceRole.INDEXER)
  }

  @Test
  fun unCambioSiSalvaESiRipubblicaSubito() = runTest {
    val store = InMemoryOnboardingStateStore(finished(indexer = false, preparer = true))
    val publisher = CountingRepublisher()
    val settings = settings(store, publisher)
    val change = settings.setRoles(indexer = true, preparer = true)
    runCurrent()
    assertThat(change).isEqualTo(RolesChange.Saved(setOf(DeviceRole.INDEXER, DeviceRole.PREPARER), republished = true))
    assertThat(store.snapshot.choices.indexer).isTrue()
    assertThat(store.snapshot.choices.preparer).isTrue()
    assertThat(settings.roles.value).containsExactly(DeviceRole.INDEXER, DeviceRole.PREPARER)
    assertThat(publisher.calls).isEqualTo(1)
  }

  @Test
  fun glAltriCampiDelleScelteNonSiToccano() = runTest {
    val before = finished(indexer = true, preparer = true).let { it.copy(choices = it.choices.copy(camera = false, remoteControl = true)) }
    val store = InMemoryOnboardingStateStore(before)
    settings(store, CountingRepublisher()).setRoles(indexer = false, preparer = true)
    assertThat(store.snapshot.choices).isEqualTo(before.choices.copy(indexer = false))
    assertThat(store.snapshot.finished).isTrue()
  }

  @Test
  fun glistessiRuoliNonScrivonoNePubblicano() = runTest {
    val store = InMemoryOnboardingStateStore(finished(indexer = true, preparer = false))
    val publisher = CountingRepublisher()
    val change = settings(store, publisher).setRoles(indexer = true, preparer = false)
    assertThat(change).isEqualTo(RolesChange.Unchanged)
    assertThat(store.writes).isEqualTo(0)
    assertThat(publisher.calls).isEqualTo(0)
  }

  @Test
  fun senzaRuoliIlDispositivoNonFaPiuNiente() = runTest {
    val store = InMemoryOnboardingStateStore(finished(indexer = true, preparer = true))
    val settings = settings(store, CountingRepublisher())
    val change = settings.setRoles(indexer = false, preparer = false)
    runCurrent()
    assertThat(change).isEqualTo(RolesChange.Saved(emptySet(), republished = true))
    assertThat(settings.roles.value).isEmpty()
  }

  @Test
  fun seLaPubblicazioneFallisceIlCambioRestaSalvato() = runTest {
    val store = InMemoryOnboardingStateStore(finished(indexer = false, preparer = true))
    val publisher = CountingRepublisher { false }
    val change = settings(store, publisher).setRoles(indexer = true, preparer = true)
    assertThat(change).isEqualTo(RolesChange.Saved(setOf(DeviceRole.INDEXER, DeviceRole.PREPARER), republished = false))
    assertThat(store.snapshot.choices.indexer).isTrue()
  }

  @Test
  fun unaPubblicazioneCheLanciaNonPerdeIlCambio() = runTest {
    val store = InMemoryOnboardingStateStore(finished(indexer = false, preparer = true))
    val publisher = CountingRepublisher { throw IllegalStateException("NAS in errore") }
    val change = settings(store, publisher).setRoles(indexer = true, preparer = true)
    assertThat(change).isEqualTo(RolesChange.Saved(setOf(DeviceRole.INDEXER, DeviceRole.PREPARER), republished = false))
    assertThat(store.snapshot.choices.indexer).isTrue()
  }

  @Test
  fun lAnnullamentoNonVieneInghiottito() = runTest {
    val store = InMemoryOnboardingStateStore(finished(indexer = false, preparer = true))
    val publisher = CountingRepublisher { throw CancellationException("annullato") }
    val settings = settings(store, publisher)
    var cancelled = false
    try {
      settings.setRoles(indexer = true, preparer = true)
    } catch (e: CancellationException) {
      cancelled = true
    }
    assertThat(cancelled).isTrue()
  }
}
