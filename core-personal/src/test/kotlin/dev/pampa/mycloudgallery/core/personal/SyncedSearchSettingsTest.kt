package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting
import dev.pampa.mycloudgallery.core.personal.contract.SearchPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import dev.pampa.mycloudgallery.core.personal.noop.NoSearchPersonalSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMotionSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedSearchSettings
import dev.pampa.mycloudgallery.core.personal.testing.FakeSearchPersonalSettings
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `text_reading` e `search_order` (Fase 6, P8, D41, D48; Q4, Q10, Q13): impostazioni personali sincronizzate; spenta e "Recenti" di
 * default. Fake e implementazione vera passano la stessa suite di contratto (§5.2).
 */
class SyncedSearchSettingsTest {

  private val world = PersonalWorld()
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  private fun searchOf(device: PersonalDevice) = SyncedSearchSettings(device.store, scope)

  private suspend fun syncAll(vararg devices: PersonalDevice) {
    repeat(2) {
      for (device in devices) device.syncOnce()
      world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    }
  }

  private val reading = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_TEXT_READING)
  private val order = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_SEARCH_ORDER)

  private suspend fun contract(settings: SearchPersonalSettings) {
    assertThat(settings.textReading.value).isEqualTo(TextReadingChoice.Off)
    assertThat(settings.searchOrder.value).isEqualTo(SearchOrderSetting.RECENT)
    settings.setTextReading(TextReadingChoice.OnForAccount(2))
    assertThat(settings.textReading.value).isEqualTo(TextReadingChoice.OnForAccount(2))
    settings.setTextReading(TextReadingChoice.Off)
    assertThat(settings.textReading.value).isEqualTo(TextReadingChoice.Off)
    settings.setSearchOrder(SearchOrderSetting.RELEVANT)
    assertThat(settings.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    settings.setSearchOrder(SearchOrderSetting.RELEVANT)
    assertThat(settings.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    settings.setSearchOrder(SearchOrderSetting.RECENT)
    assertThat(settings.searchOrder.value).isEqualTo(SearchOrderSetting.RECENT)
  }

  @Test
  fun laStessaSuiteDiContrattoPassaSuFakeEImplementazioneVera() = runTest {
    contract(FakeSearchPersonalSettings())
    contract(searchOf(world.device()))
    assertThat(NoSearchPersonalSettings.textReading.value).isEqualTo(TextReadingChoice.Off)
    scope.cancel()
  }

  @Test
  fun leVociAssentiSonoSpentaERecenti_ELaSolaLetturaNonScriveNiente() = runTest {
    val phone = world.device()
    val settings = searchOf(phone)
    assertThat(settings.textReading.value).isEqualTo(TextReadingChoice.Off)
    assertThat(settings.searchOrder.value).isEqualTo(SearchOrderSetting.RECENT)
    assertThat(phone.store.entries.value).doesNotContainKey(reading)
    assertThat(phone.store.entries.value).doesNotContainKey(order)
    scope.cancel()
  }

  @Test
  fun laVersioneDelTestoAccettatoSiSalvaNellaVoce() = runTest {
    val phone = world.device()
    searchOf(phone).setTextReading(TextReadingChoice.OnForAccount(3))
    assertThat(phone.store.entries.value[reading]?.value).isEqualTo(PersonalValue.Number(3))
    searchOf(phone).setTextReading(TextReadingChoice.Off)
    assertThat(phone.store.entries.value[reading]?.value).isEqualTo(PersonalValue.Number(0))
    scope.cancel()
  }

  @Test
  fun laLetturaAttivaSuUnDispositivoVaSuTuttiGliAltriDelloStessoAccount_Q13() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = searchOf(phone)
    val onTablet = searchOf(tablet)
    onPhone.setTextReading(TextReadingChoice.OnForAccount(1))
    assertThat(onTablet.textReading.value).isEqualTo(TextReadingChoice.Off)
    syncAll(phone, tablet)
    assertThat(onTablet.textReading.value).isEqualTo(TextReadingChoice.OnForAccount(1))
    // Lo spegnimento vale ovunque (ultima modifica vince).
    onTablet.setTextReading(TextReadingChoice.Off)
    syncAll(tablet, phone)
    assertThat(onPhone.textReading.value).isEqualTo(TextReadingChoice.Off)
    scope.cancel()
  }

  @Test
  fun lOrdineScelto_RicordatoESincronizzato_Q10() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = searchOf(phone)
    val onTablet = searchOf(tablet)
    onPhone.setSearchOrder(SearchOrderSetting.RELEVANT)
    syncAll(phone, tablet)
    assertThat(onTablet.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    // Un dispositivo che si riapre (stesso archivio) ricorda la scelta.
    assertThat(searchOf(tablet).searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    scope.cancel()
  }

  @Test
  fun valoriStraniOOstiliValgonoComeIDefault() = runTest {
    val phone = world.device()
    val settings = searchOf(phone)
    for (value in listOf(PersonalValue.Number(-4), PersonalValue.Number(Long.MAX_VALUE), PersonalValue.Flag(true), PersonalValue.Text("si"), PersonalValue.Removed)) {
      phone.store.applyRemote(listOf(PersonalEntry(reading, NAS_NOW, phone.id, value)))
      assertThat(settings.textReading.value).isEqualTo(TextReadingChoice.Off)
    }
    for (value in listOf(PersonalValue.Text("domani"), PersonalValue.Number(1), PersonalValue.Flag(true), PersonalValue.Removed)) {
      phone.store.applyRemote(listOf(PersonalEntry(order, NAS_NOW, phone.id, value)))
      assertThat(settings.searchOrder.value).isEqualTo(SearchOrderSetting.RECENT)
    }
    scope.cancel()
  }

  @Test
  fun leTreImplementazioniNonSiCalpestano() = runTest {
    val phone = world.device()
    val search = searchOf(phone)
    val motion = SyncedMotionSettings(phone.store, scope)
    search.setTextReading(TextReadingChoice.OnForAccount(1))
    search.setSearchOrder(SearchOrderSetting.RELEVANT)
    motion.setMotionAnimation(false)
    assertThat(search.textReading.value).isEqualTo(TextReadingChoice.OnForAccount(1))
    assertThat(search.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    assertThat(motion.motionAnimation.value).isFalse()
    assertThat(phone.store.entries.value.keys.map { it.field }).containsExactly(
      PersonalFields.SETTING_TEXT_READING,
      PersonalFields.SETTING_SEARCH_ORDER,
      PersonalFields.SETTING_MOTION_ANIMATION,
    )
    scope.cancel()
  }
}
