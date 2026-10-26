package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.personal.codec.PersonalRecords
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.settings.CollectionsSettingsCodec
import dev.pampa.mycloudgallery.core.personal.settings.SyncedCollectionsSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMotionSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedSearchSettings
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `collections_order` e `collections_hidden:<sezione>` (Fase 7, P11, D33, E-L14): l'ordine e la visibilita' delle sezioni delle Raccolte,
 * sincronizzati fra i dispositivi dello stesso account, che valgono subito anche senza l'ora del NAS (come la revisione B della Fase 6).
 */
class SyncedCollectionsSettingsTest {

  private val world = PersonalWorld()
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  private fun collectionsOf(device: PersonalDevice) = SyncedCollectionsSettings(device.store, scope)

  private fun offlineDevice() = world.device().also { it.anchored = false }

  private suspend fun syncAll(vararg devices: PersonalDevice) {
    repeat(2) {
      for (device in devices) device.syncOnce()
      world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    }
  }

  private val orderKey = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_COLLECTIONS_ORDER)

  private fun hiddenKey(code: String) =
    PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.collectionsHidden(code))

  // --- Valori di partenza -----------------------------------------------------------------------------------------------------------

  @Test
  fun senzaVociNonCeOrdineSalvatoNeSezioniNascoste_ELaSolaLetturaNonScriveNiente() = runTest {
    val phone = world.device()
    val settings = collectionsOf(phone)
    assertThat(settings.order.value).isEmpty()
    assertThat(settings.hidden.value).isEmpty()
    assertThat(phone.store.entries.value).isEmpty()
    scope.cancel()
  }

  // --- Sincronizzazione fra i dispositivi ----------------------------------------------------------------------------------------------

  @Test
  fun l_ordineSceltoSuUnDispositivoViaggiaFinoAgliAltri_EL_ultimaScelteVince() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = collectionsOf(phone)
    val onTablet = collectionsOf(tablet)

    onPhone.setOrder(listOf("phone", "albums", "nas"))
    assertThat(onTablet.order.value).isEmpty()
    syncAll(phone, tablet)
    assertThat(onTablet.order.value).containsExactly("phone", "albums", "nas").inOrder()

    onTablet.setOrder(listOf("nas", "phone", "albums"))
    syncAll(tablet, phone)
    assertThat(onPhone.order.value).containsExactly("nas", "phone", "albums").inOrder()
    scope.cancel()
  }

  @Test
  fun unaSezioneNascostaSuUnDispositivoELoSuTuttiQuelliDellAccount_E_L14() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val onPhone = collectionsOf(phone)
    val onTablet = collectionsOf(tablet)

    onPhone.setHidden("hidden", true)
    onPhone.setHidden("trips", true)
    syncAll(phone, tablet)
    assertThat(onTablet.hidden.value).containsExactly("hidden", "trips")

    // Rimostrarla su un dispositivo la rimostra ovunque: l'ultima scelta vince.
    onTablet.setHidden("hidden", false)
    syncAll(tablet, phone)
    assertThat(onPhone.hidden.value).containsExactly("trips")
    scope.cancel()
  }

  @Test
  fun ilDispositivoCheSiRiapreRicordaLeScelte() = runTest {
    val tablet = world.device()
    collectionsOf(tablet).setOrder(listOf("smart", "albums"))
    collectionsOf(tablet).setHidden("utility", true)
    val reopened = collectionsOf(tablet)
    assertThat(reopened.order.value).containsExactly("smart", "albums").inOrder()
    assertThat(reopened.hidden.value).containsExactly("utility")
    scope.cancel()
  }

  // --- Senza l'ora del NAS (revisione B della Fase 6) ----------------------------------------------------------------------------------

  @Test
  fun lOrdineELaVisibilitaSenzaLOraDelNasValgonoSubito_ESiRicordanoDopoUnRiavvio() = runTest {
    val phone = offlineDevice()
    val settings = collectionsOf(phone)
    settings.setOrder(listOf("nas", "albums"))
    settings.setHidden("hidden", true)

    assertThat(settings.order.value).containsExactly("nas", "albums").inOrder()
    assertThat(settings.hidden.value).containsExactly("hidden")
    // Lo stato che si pubblica non cambia (le modifiche non hanno l'ora), ma la scelta dell'utente si': e' il punto della correzione.
    assertThat(phone.store.entries.value).isEmpty()
    assertThat(phone.store.undated()).hasSize(2)

    val restarted = world.device(ledger = phone.ledger, signer = phone.signer).also { it.anchored = false }
    val afterRestart = collectionsOf(restarted)
    restarted.store.open()
    assertThat(afterRestart.order.value).containsExactly("nas", "albums").inOrder()
    assertThat(afterRestart.hidden.value).containsExactly("hidden")
    scope.cancel()
  }

  @Test
  fun rimostrareUnaSezioneSenzaOraPrevaleSullaVoceVecchiaSincronizzata() = runTest {
    val tablet = world.device()
    val phone = offlineDevice()
    collectionsOf(tablet).setHidden("trips", true)
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    tablet.syncOnce()
    phone.anchored = true
    phone.syncOnce()
    phone.anchored = false
    val settings = collectionsOf(phone)
    assertThat(settings.hidden.value).containsExactly("trips")

    settings.setHidden("trips", false)
    // La voce sincronizzata dice ancora "nascosta", ma l'utente ha appena rimostrato la sezione: vale la sua scelta.
    assertThat(phone.store.entries.value.keys).contains(hiddenKey("trips"))
    assertThat(settings.hidden.value).isEmpty()
    scope.cancel()
  }

  @Test
  fun allaPrimaSincronizzazioneLeModificheSiDatanoESiPubblicano_NellOrdineGiusto() = runTest {
    val phone = offlineDevice()
    val tablet = world.device()
    val settings = collectionsOf(phone)
    // Tre modifiche dell'ordine senza ora: conta l'ultima.
    settings.setOrder(listOf("albums", "nas"))
    settings.setOrder(listOf("nas", "albums"))
    settings.setOrder(listOf("phone", "nas", "albums"))
    settings.setHidden("smart", true)
    assertThat(phone.store.undated()).hasSize(2)

    phone.anchored = true
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    phone.syncOnce()
    assertThat(phone.store.undated()).isEmpty()
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    tablet.syncOnce()
    val onTablet = collectionsOf(tablet)
    assertThat(onTablet.order.value).containsExactly("phone", "nas", "albums").inOrder()
    assertThat(onTablet.hidden.value).containsExactly("smart")
    scope.cancel()
  }

  // --- Album non si nasconde (D33, §2.7) ------------------------------------------------------------------------------------------------

  @Test
  fun albumNonSiNascondeMai_NeSiScriveNeSiLegge() = runTest {
    val phone = world.device()
    val settings = collectionsOf(phone)
    settings.setHidden("albums", true)
    assertThat(phone.store.entries.value).isEmpty()
    assertThat(settings.hidden.value).isEmpty()

    // Un'altra versione o un altro dispositivo che l'ha scritta comunque: si ignora.
    phone.store.applyRemote(listOf(PersonalEntry(hiddenKey("albums"), NAS_NOW, phone.id, PersonalValue.Flag(true))))
    phone.store.applyRemote(listOf(PersonalEntry(hiddenKey("trips"), NAS_NOW, phone.id, PersonalValue.Flag(true))))
    assertThat(settings.hidden.value).containsExactly("trips")
    scope.cancel()
  }

  // --- Codici e valori ostili -------------------------------------------------------------------------------------------------------------

  @Test
  fun unCodiceCheNonSiPuoScrivereNonScriveNiente() = runTest {
    val phone = world.device()
    val settings = collectionsOf(phone)
    for (bad in listOf("", "Albums", "a,b", "a:b", "1abc", "a b", "a\u0000b", "x".repeat(25), "né", "a-b")) {
      settings.setHidden(bad, true)
    }
    assertThat(phone.store.entries.value).isEmpty()
    assertThat(settings.hidden.value).isEmpty()
    scope.cancel()
  }

  @Test
  fun l_ordineScartaICodiciNonValidiEIDoppioni_ESenzaCodiciValidiTornaAlPredefinito() = runTest {
    val phone = world.device()
    val settings = collectionsOf(phone)
    settings.setOrder(listOf("nas", "Bad", "phone", "nas", "", "a,b", "albums"))
    assertThat(settings.order.value).containsExactly("nas", "phone", "albums").inOrder()

    settings.setOrder(listOf("Bad", ""))
    assertThat(settings.order.value).isEmpty()

    settings.setOrder(listOf("nas"))
    settings.resetOrder()
    assertThat(settings.order.value).isEmpty()
    scope.cancel()
  }

  @Test
  fun valoriStraniOOstiliDiUnaVersionePiuNuovaValgonoComeNessunaScelta() = runTest {
    val phone = world.device()
    val settings = collectionsOf(phone)
    for (value in listOf(PersonalValue.Flag(true), PersonalValue.Number(3), PersonalValue.Removed, PersonalValue.Text(""))) {
      phone.store.applyRemote(listOf(PersonalEntry(orderKey, NAS_NOW, phone.id, value)))
      assertWithMessage(value.toString()).that(settings.order.value).isEmpty()
    }
    for (value in listOf(PersonalValue.Flag(false), PersonalValue.Number(1), PersonalValue.Text("si"), PersonalValue.Removed)) {
      phone.store.applyRemote(listOf(PersonalEntry(hiddenKey("nas"), NAS_NOW, phone.id, value)))
      assertWithMessage(value.toString()).that(settings.hidden.value).isEmpty()
    }
    // Un testo con codici buoni e cattivi tiene i buoni, nell'ordine.
    phone.store.applyRemote(listOf(PersonalEntry(orderKey, NAS_NOW + 1, phone.id, PersonalValue.Text(",nas,,X Y,phone,nas,smart_2,"))))
    assertThat(settings.order.value).containsExactly("nas", "phone", "smart_2").inOrder()
    // Un campo che sembra una visibilita' ma ha un codice impossibile non nasconde niente.
    phone.store.applyRemote(listOf(PersonalEntry(hiddenKey("Bad Code"), NAS_NOW, phone.id, PersonalValue.Flag(true))))
    assertThat(settings.hidden.value).isEmpty()
    scope.cancel()
  }

  @Test
  fun unOrdineLunghissimoStaSempreNelTettoDiUnaVoceEIlCodecDiOggiLoRilegge() {
    val many = (1..200).map { "s" + "x".repeat(23 - it.toString().length) + it }
    assertThat(many.all(CollectionsSettingsCodec::isValidCode)).isTrue()
    val value = CollectionsSettingsCodec.encodeOrder(many) as PersonalValue.Text
    assertThat(value.text.length).isAtMost(PersonalValue.MAX_TEXT)
    assertThat(CollectionsSettingsCodec.decodeOrder(value)).hasSize(SyncedCollectionsSettings.MAX_SECTIONS)

    val author = dev.pampa.mycloudgallery.core.trust.crypto.TrustIds.random()
    val entry = PersonalEntry(orderKey, NAS_NOW, author, value)
    val bytes = PersonalRecords.encodeOrNull(entry)
    assertThat(bytes).isNotNull()
    assertThat(PersonalRecords.decode(bytes!!, orderKey.digest()).acceptedOrNull()).isEqualTo(entry)
  }

  @Test
  fun leVociDelleRaccolteNonSiCalpestanoConLeAltreImpostazioni() = runTest {
    val phone = world.device()
    val collections = collectionsOf(phone)
    val search = SyncedSearchSettings(phone.store, scope)
    val motion = SyncedMotionSettings(phone.store, scope)
    collections.setOrder(listOf("nas", "albums"))
    collections.setHidden("hidden", true)
    search.setSearchOrder(dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting.RELEVANT)
    motion.setMotionAnimation(false)

    assertThat(collections.order.value).containsExactly("nas", "albums").inOrder()
    assertThat(collections.hidden.value).containsExactly("hidden")
    assertThat(search.searchOrder.value).isEqualTo(dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting.RELEVANT)
    assertThat(motion.motionAnimation.value).isFalse()
    assertThat(phone.store.entries.value.keys.map { it.field }).containsExactly(
      PersonalFields.SETTING_COLLECTIONS_ORDER,
      PersonalFields.collectionsHidden("hidden"),
      PersonalFields.SETTING_SEARCH_ORDER,
      PersonalFields.SETTING_MOTION_ANIMATION,
    )
    scope.cancel()
  }
}
