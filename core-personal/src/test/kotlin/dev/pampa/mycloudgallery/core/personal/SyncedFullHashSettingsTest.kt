package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.personal.contract.FullHashPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.noop.DefaultFullHashPersonalSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedFullHashSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMotionSettings
import dev.pampa.mycloudgallery.core.personal.testing.FakeFullHashPersonalSettings
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Controlla di notte i video del NAS" (Fase 7, P8, D27, Q10 della Fase 4): impostazione personale sincronizzata, spenta di default (voce assente
 * = spenta), con il fake e l'implementazione vera che passano la stessa suite di contratto (§5.2).
 */
class SyncedFullHashSettingsTest {

  private val world = PersonalWorld()
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  private fun settingsOf(device: PersonalDevice) = SyncedFullHashSettings(device.store, scope)

  private suspend fun syncAll(vararg devices: PersonalDevice) {
    repeat(2) {
      for (device in devices) device.syncOnce()
      world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    }
  }

  private val key = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_FULL_HASH_NIGHTLY)

  /** La suite di contratto di `FullHashPersonalSettings`: la stessa su ogni implementazione. Si accende e si spegne. */
  private suspend fun contract(settings: FullHashPersonalSettings) {
    assertThat(settings.fullHashNightly.value).isFalse()
    settings.setFullHashNightly(true)
    assertThat(settings.fullHashNightly.value).isTrue()
    // Scrivere due volte lo stesso valore non cambia niente.
    settings.setFullHashNightly(true)
    assertThat(settings.fullHashNightly.value).isTrue()
    settings.setFullHashNightly(false)
    assertThat(settings.fullHashNightly.value).isFalse()
  }

  @Test
  fun laStessaSuiteDiContrattoPassaSuFakeEImplementazioneVera() = runTest {
    contract(FakeFullHashPersonalSettings())
    contract(settingsOf(world.device()))
    // Il valore di serie e' lo stesso dell'oggetto nullo: spento.
    assertThat(DefaultFullHashPersonalSettings.fullHashNightly.value).isFalse()
    scope.cancel()
  }

  @Test
  fun laVoceAssenteESpenta_ELaSolaLetturaNonScriveNiente() = runTest {
    val phone = world.device()
    val settings = settingsOf(phone)
    assertThat(settings.fullHashNightly.value).isFalse()
    assertThat(phone.store.entries.value).doesNotContainKey(key)
    scope.cancel()
  }

  @Test
  fun lInterruttoreAccesoSuUnDispositivoLoVedonoGliAltri() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = settingsOf(phone)
    val onTablet = settingsOf(tablet)

    onPhone.setFullHashNightly(true)
    assertThat(onPhone.fullHashNightly.value).isTrue()
    assertThat(onTablet.fullHashNightly.value).isFalse()
    syncAll(phone, tablet)
    // Un secondo dispositivo dello stesso account la vede.
    assertThat(onTablet.fullHashNightly.value).isTrue()

    // L'ultima scelta vince, su qualunque dispositivo: spegnerlo dal tablet lo spegne anche sul telefono.
    onTablet.setFullHashNightly(false)
    syncAll(tablet, phone)
    assertThat(onPhone.fullHashNightly.value).isFalse()
    scope.cancel()
  }

  @Test
  fun unaVoceCheNonEUnFlagVaComeAssente_SpentaENonSiAccendeUnaAttivitaPesante() = runTest {
    val phone = world.device()
    val settings = settingsOf(phone)
    for (value in listOf(PersonalValue.Number(1), PersonalValue.Text("true"), PersonalValue.Removed)) {
      phone.store.applyRemote(listOf(PersonalEntry(key, NAS_NOW, phone.id, value)))
      assertThat(settings.fullHashNightly.value).isFalse()
    }
    // Un flag vero si legge com'e'.
    phone.store.applyRemote(listOf(PersonalEntry(key, NAS_NOW + 1, phone.id, PersonalValue.Flag(true))))
    assertThat(settings.fullHashNightly.value).isTrue()
    scope.cancel()
  }

  @Test
  fun scrivereLInterruttoreNonToccaLeAltreImpostazioni() = runTest {
    val phone = world.device()
    val hash = settingsOf(phone)
    val motion = SyncedMotionSettings(phone.store, scope)
    motion.setMotionAnimation(false)
    hash.setFullHashNightly(true)
    assertThat(motion.motionAnimation.value).isFalse()
    assertThat(phone.store.entries.value.keys.map { it.field })
      .containsExactly(PersonalFields.SETTING_MOTION_ANIMATION, PersonalFields.SETTING_FULL_HASH_NIGHTLY)
    scope.cancel()
  }
}
