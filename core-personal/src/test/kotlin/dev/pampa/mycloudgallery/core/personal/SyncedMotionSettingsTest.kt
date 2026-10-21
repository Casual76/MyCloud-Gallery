package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.personal.contract.MotionPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.noop.DefaultMotionPersonalSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMotionSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.FakeMotionPersonalSettings
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Animazione delle foto in movimento" (Fase 6, P14, D50, Q16 (b)): impostazione personale sincronizzata, accesa di default (voce assente =
 * accesa), con il fake e l'implementazione vera che passano la stessa suite di contratto (§5.2).
 */
class SyncedMotionSettingsTest {

  private val world = PersonalWorld()
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  private fun motionOf(device: PersonalDevice) = SyncedMotionSettings(device.store, scope)

  private suspend fun syncAll(vararg devices: PersonalDevice) {
    repeat(2) {
      for (device in devices) device.syncOnce()
      world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    }
  }

  private val key = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_MOTION_ANIMATION)

  /** La suite di contratto di `MotionPersonalSettings`: la stessa su ogni implementazione. */
  private suspend fun contract(settings: MotionPersonalSettings) {
    assertThat(settings.motionAnimation.value).isTrue()
    settings.setMotionAnimation(false)
    assertThat(settings.motionAnimation.value).isFalse()
    // Scrivere due volte lo stesso valore non cambia niente.
    settings.setMotionAnimation(false)
    assertThat(settings.motionAnimation.value).isFalse()
    settings.setMotionAnimation(true)
    assertThat(settings.motionAnimation.value).isTrue()
  }

  @Test
  fun laStessaSuiteDiContrattoPassaSuFakeEImplementazioneVera() = runTest {
    contract(FakeMotionPersonalSettings())
    contract(motionOf(world.device()))
    // Il valore di serie e' lo stesso dell'oggetto nullo.
    assertThat(DefaultMotionPersonalSettings.motionAnimation.value).isTrue()
    scope.cancel()
  }

  @Test
  fun laVoceAssenteEAccesa_ELaSolaLetturaNonScriveNiente() = runTest {
    val phone = world.device()
    val settings = motionOf(phone)
    assertThat(settings.motionAnimation.value).isTrue()
    assertThat(phone.store.entries.value).doesNotContainKey(key)
    scope.cancel()
  }

  @Test
  fun lInterruttoreSpentoSuUnDispositivoLoVedonoGliAltri_Q16() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = motionOf(phone)
    val onTablet = motionOf(tablet)

    onPhone.setMotionAnimation(false)
    assertThat(onPhone.motionAnimation.value).isFalse()
    assertThat(onTablet.motionAnimation.value).isTrue()
    syncAll(phone, tablet)
    // Un secondo dispositivo dello stesso account la vede.
    assertThat(onTablet.motionAnimation.value).isFalse()

    // L'ultima scelta vince, su qualunque dispositivo.
    onTablet.setMotionAnimation(true)
    syncAll(tablet, phone)
    assertThat(onPhone.motionAnimation.value).isTrue()
    scope.cancel()
  }

  @Test
  fun unaVoceCheNonEUnFlagVaFcomeAssente_AccesaESenzaSpegnereLAnimazione() = runTest {
    val phone = world.device()
    val settings = motionOf(phone)
    for (value in listOf(PersonalValue.Number(0), PersonalValue.Text("false"), PersonalValue.Removed)) {
      phone.store.applyRemote(listOf(PersonalEntry(key, NAS_NOW, phone.id, value)))
      assertThat(settings.motionAnimation.value).isTrue()
    }
    // Un flag vero si legge com'e'.
    phone.store.applyRemote(listOf(PersonalEntry(key, NAS_NOW + 1, phone.id, PersonalValue.Flag(false))))
    assertThat(settings.motionAnimation.value).isFalse()
    scope.cancel()
  }

  @Test
  fun scrivereLInterruttoreNonToccaLeAltreImpostazioni() = runTest {
    val phone = world.device()
    val motion = motionOf(phone)
    val others = SyncedPersonalSettings(phone.store, scope)
    others.setShowAlbumCovers(true)
    motion.setMotionAnimation(false)
    assertThat(others.showAlbumCovers.value).isTrue()
    assertThat(phone.store.entries.value.keys.map { it.field })
      .containsExactly(PersonalFields.SETTING_SHOW_ALBUM_COVERS, PersonalFields.SETTING_MOTION_ANIMATION)
    scope.cancel()
  }
}
