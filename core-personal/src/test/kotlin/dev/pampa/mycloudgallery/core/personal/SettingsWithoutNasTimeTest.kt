package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMotionSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedSearchSettings
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le impostazioni personali **senza l'ora del NAS** (R6 della revisione di sicurezza della Fase 6; LT-1, CE-3 e VM-2 della verifica
 * funzionale): fuori casa, senza VPN e senza un accesso SMB nel processo, una modifica non ha l'ora e resta "da datare", fuori dallo stato
 * che si pubblica. Prima `SyncedSearchSettings` e `SyncedMotionSettings` leggevano solo lo stato unito, quindi "Spegni" la lettura del testo,
 * l'ordine dei risultati e l'animazione delle foto in movimento non cambiavano finche' il telefono non tornava a casa. Adesso valgono
 * subito, e alla prima sincronizzazione la modifica prende l'ora e viaggia, nell'ordine in cui e' stata fatta.
 */
class SettingsWithoutNasTimeTest {

  private val world = PersonalWorld()
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  private fun offlineDevice() = world.device().also { it.anchored = false }

  @Test
  fun spegnereSenzaLOraDelNasSpegneSubito_ESiRicordaDopoUnRiavvio() = runTest {
    val phone = offlineDevice()
    val settings = SyncedSearchSettings(phone.store, scope)
    settings.setTextReading(TextReadingChoice.OnForAccount(1))
    assertThat(settings.textReading.value).isEqualTo(TextReadingChoice.OnForAccount(1))
    // Lo stato che si pubblica non cambia (non ha l'ora), ma la scelta dell'utente si': e' quello che "Spegni" non faceva.
    assertThat(phone.store.entries.value).isEmpty()

    settings.setTextReading(TextReadingChoice.Off)
    assertThat(settings.textReading.value).isEqualTo(TextReadingChoice.Off)
    assertThat(phone.store.undated()).hasSize(1)

    // Un nuovo processo (stesso registro su disco, ancora senza ora del NAS): la scelta c'e' ancora.
    val restarted = world.device(ledger = phone.ledger, signer = phone.signer).also { it.anchored = false }
    val afterRestart = SyncedSearchSettings(restarted.store, scope)
    restarted.store.open()
    assertThat(afterRestart.textReading.value).isEqualTo(TextReadingChoice.Off)
    scope.cancel()
  }

  @Test
  fun spegnereUnaLetturaAccesaAltroveSenzaOraPrevaleSullaVoceVecchia() = runTest {
    val tablet = world.device()
    val phone = offlineDevice()
    SyncedSearchSettings(tablet.store, scope).setTextReading(TextReadingChoice.OnForAccount(1))
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    tablet.syncOnce()
    phone.anchored = true
    phone.syncOnce()
    phone.anchored = false
    val settings = SyncedSearchSettings(phone.store, scope)
    assertThat(settings.textReading.value).isEqualTo(TextReadingChoice.OnForAccount(1))

    settings.setTextReading(TextReadingChoice.Off)
    // La voce sincronizzata dice ancora "accesa", ma l'utente ha appena spento: vale la sua scelta.
    assertThat(phone.store.entries.value.values.map { it.value }).isNotEmpty()
    assertThat(settings.textReading.value).isEqualTo(TextReadingChoice.Off)
    scope.cancel()
  }

  @Test
  fun lOrdineEL_animazioneSceltiSenzaOraValgonoSubito() = runTest {
    val phone = offlineDevice()
    val search = SyncedSearchSettings(phone.store, scope)
    val motion = SyncedMotionSettings(phone.store, scope)
    assertThat(search.searchOrder.value).isEqualTo(SearchOrderSetting.RECENT)
    assertThat(motion.motionAnimation.value).isTrue()
    search.setSearchOrder(SearchOrderSetting.RELEVANT)
    motion.setMotionAnimation(false)
    assertThat(search.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    assertThat(motion.motionAnimation.value).isFalse()
    scope.cancel()
  }

  @Test
  fun allaPrimaSincronizzazioneLeModificheSiDatanoESiPubblicano_NellOrdineGiusto() = runTest {
    val phone = offlineDevice()
    val tablet = world.device()
    val settings = SyncedSearchSettings(phone.store, scope)
    // Tre modifiche della stessa voce senza ora: conta l'ultima (l'utente ha acceso, spento, riacceso).
    settings.setTextReading(TextReadingChoice.OnForAccount(1))
    settings.setTextReading(TextReadingChoice.Off)
    settings.setTextReading(TextReadingChoice.OnForAccount(1))
    settings.setSearchOrder(SearchOrderSetting.RELEVANT)
    assertThat(phone.store.undated()).hasSize(2)

    phone.anchored = true
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    phone.syncOnce()
    assertThat(phone.store.undated()).isEmpty()
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    tablet.syncOnce()
    val onTablet = SyncedSearchSettings(tablet.store, scope)
    assertThat(onTablet.textReading.value).isEqualTo(TextReadingChoice.OnForAccount(1))
    assertThat(onTablet.searchOrder.value).isEqualTo(SearchOrderSetting.RELEVANT)
    // Il telefono vede ancora lo stesso, ora dal suo stato unito.
    assertThat(settings.textReading.value).isEqualTo(TextReadingChoice.OnForAccount(1))
    scope.cancel()
  }
}
