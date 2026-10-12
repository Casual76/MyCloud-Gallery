package dev.pampa.mycloudgallery.core.rules.traffic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.GB
import dev.pampa.mycloudgallery.core.rules.MB
import dev.pampa.mycloudgallery.core.rules.MutableClock
import dev.pampa.mycloudgallery.core.rules.at
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrafficLedgerTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun TestScope.store(): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "ledger.preferences_pb") }

  private val clock = MutableClock(at(12))

  @Test
  fun registraPerAttivitaEPerRete_eSommaNelCicloInCorso() = runTest {
    val ledger = DataStoreTrafficLedger(store(), clock)

    ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.HOME_WIFI, 300 * MB)
    ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.HOME_WIFI, 200 * MB)
    ledger.record(TrafficSource.VIDEO_BACKUP, TrafficNetwork.HOME_WIFI, 2 * GB)
    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.MOBILE, 40 * MB)
    ledger.record(TrafficSource.VISIBLE_CONTENT, TrafficNetwork.MOBILE, 10 * MB)
    ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.EXTERNAL_WIFI, 5 * MB)
    ledger.record(TrafficSource.AI_COMPUTE, TrafficNetwork.ROAMING, 1 * MB)

    val usage = ledger.currentUsage()
    assertThat(usage.bytesFor(TrafficSource.PHOTO_BACKUP, TrafficNetwork.HOME_WIFI)).isEqualTo(500 * MB)
    assertThat(usage.bytesFor(BackgroundActivity.PHOTO_BACKUP)).isEqualTo(505 * MB)
    assertThat(usage.bytesFor(BackgroundActivity.VIDEO_BACKUP)).isEqualTo(2 * GB)
    assertThat(usage.bytesFor(TrafficSource.VISIBLE_CONTENT)).isEqualTo(10 * MB)
    assertThat(usage.bytesFor(TrafficNetwork.HOME_WIFI)).isEqualTo(2 * GB + 500 * MB)
    // Mobile e roaming contano nel tetto dei dati mobili, il resto nel tetto Wi-Fi.
    assertThat(usage.mobileBytes).isEqualTo(51 * MB)
    assertThat(usage.wifiBytes).isEqualTo(2 * GB + 505 * MB)
    assertThat(usage.totalBytes).isEqualTo(usage.mobileBytes + usage.wifiBytes)
    assertThat(usage.cycle.id).isEqualTo("2026-10")
  }

  @Test
  fun valoriZeroONegativi_nonFannoNiente() = runTest {
    val ledger = DataStoreTrafficLedger(store(), clock)

    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.HOME_WIFI, 0)
    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.HOME_WIFI, -500)

    assertThat(ledger.currentUsage().totalBytes).isEqualTo(0)
  }

  @Test
  fun cambioMese_icontatoriRipartonoDaZero_eLoStoricoResta() = runTest {
    val ledger = DataStoreTrafficLedger(store(), clock)
    clock.set(at(23, 59, 59, day = 31))
    ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.MOBILE, 900 * MB)
    assertThat(ledger.currentUsage().mobileBytes).isEqualTo(900 * MB)

    // Scatta la mezzanotte del primo novembre.
    clock.set(LocalDateTime.of(2026, 11, 1, 0, 0, 0))

    val november = ledger.currentUsage()
    assertThat(november.cycle.id).isEqualTo("2026-11")
    assertThat(november.mobileBytes).isEqualTo(0)

    ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.MOBILE, 50 * MB)
    assertThat(ledger.currentUsage().mobileBytes).isEqualTo(50 * MB)

    // Lo storico del mese passato e' intatto.
    val history = ledger.history()
    assertThat(history.map { it.cycle.id }).containsExactly("2026-11", "2026-10").inOrder()
    assertThat(history[0].mobileBytes).isEqualTo(50 * MB)
    assertThat(history[1].mobileBytes).isEqualTo(900 * MB)
  }

  @Test
  fun unSecondoPrimaDellaMezzanotte_siContaAncoraNelMesePrecedente() = runTest {
    val ledger = DataStoreTrafficLedger(store(), clock)

    clock.set(at(23, 59, 59, day = 31))
    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.HOME_WIFI, 1 * MB)
    clock.set(LocalDateTime.of(2026, 11, 1, 0, 0, 0))
    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.HOME_WIFI, 2 * MB)

    val history = ledger.history()
    assertThat(history.map { it.wifiBytes }).containsExactly(2 * MB, 1 * MB).inOrder()
  }

  @Test
  fun giornoDiInizioCiclo_ilMeseDelPianoDati() = runTest {
    // Il piano dati si rinnova il 15: dal 15/10 al 14/11 e' lo stesso ciclo.
    val ledger = DataStoreTrafficLedger(store(), clock, cycleStartDay = { 15 })

    clock.set(at(12, day = 14)) // 14/10: ancora il ciclo cominciato il 15/09
    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.MOBILE, 100 * MB)
    assertThat(ledger.currentUsage().cycle.id).isEqualTo("2026-09")

    clock.set(at(0, 0, 0, day = 15)) // 15/10: ciclo nuovo, contatori a zero
    assertThat(ledger.currentUsage().cycle.id).isEqualTo("2026-10")
    assertThat(ledger.currentUsage().mobileBytes).isEqualTo(0)
    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.MOBILE, 7 * MB)

    clock.set(at(12, day = 31)) // fine ottobre: sempre lo stesso ciclo
    assertThat(ledger.currentUsage().mobileBytes).isEqualTo(7 * MB)
  }

  @Test
  fun billingCycle_calcoli() {
    val cycle = BillingCycle.containing(LocalDate.of(2026, 1, 10), startDay = 15)
    assertThat(cycle.month).isEqualTo(YearMonth.of(2025, 12))
    assertThat(cycle.start).isEqualTo(LocalDate.of(2025, 12, 15))
    assertThat(cycle.endExclusive).isEqualTo(LocalDate.of(2026, 1, 15))

    val calendar = BillingCycle.containing(LocalDate.of(2026, 2, 28))
    assertThat(calendar.start).isEqualTo(LocalDate.of(2026, 2, 1))
    assertThat(calendar.endExclusive).isEqualTo(LocalDate.of(2026, 3, 1))

    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { BillingCycle.containing(LocalDate.now(), 29) }
    assertThat(BillingCycle.parseId("2026-13")).isNull()
    assertThat(BillingCycle.parseId("boh")).isNull()
  }

  @Test
  fun losStoricoSiPotaDopoIlNumeroDiCicliDaTenere() = runTest {
    val ledger = DataStoreTrafficLedger(store(), clock, keepCycles = 3)

    for (month in 1..6) {
      clock.set(at(12).withMonth(month))
      ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.HOME_WIFI, month * MB)
    }

    assertThat(ledger.history().map { it.cycle.id }).containsExactly("2026-06", "2026-05", "2026-04").inOrder()
  }

  @Test
  fun lo_storico_includeSempreIlCicloInCorso_ancheSeVuoto() = runTest {
    val ledger = DataStoreTrafficLedger(store(), clock)

    val history = ledger.history()

    assertThat(history).hasSize(1)
    assertThat(history[0].totalBytes).isEqualTo(0)
  }

  @Test
  fun registrazioniInParallelo_nonPerdonoByte() = runTest {
    val ledger = DataStoreTrafficLedger(store(), clock)

    (1..50).map { async { ledger.record(TrafficSource.PREPARATION, TrafficNetwork.HOME_WIFI, 10 * MB) } }.awaitAll()

    assertThat(ledger.currentUsage().bytesFor(BackgroundActivity.PREPARATION)).isEqualTo(500 * MB)
  }

  @Test
  fun saturaInvecediFareOverflow() = runTest {
    val ledger = DataStoreTrafficLedger(store(), clock)

    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.HOME_WIFI, Long.MAX_VALUE - 5)
    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.HOME_WIFI, 100)

    assertThat(ledger.currentUsage().bytesFor(TrafficSource.INDEX_SYNC)).isEqualTo(Long.MAX_VALUE)
  }

  @Test
  fun chiaviIllegibiliOEstranee_siIgnorano() = runTest {
    val dataStore = store()
    val ledger = DataStoreTrafficLedger(dataStore, clock)
    dataStore.edit {
      it[longPreferencesKey("traffic.2026-10.NOPE.HOME_WIFI")] = 999
      it[longPreferencesKey("traffic.2026-10.INDEX_SYNC")] = 999
      it[longPreferencesKey("altro.2026-10.INDEX_SYNC.HOME_WIFI")] = 999
    }
    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.HOME_WIFI, 1 * MB)

    assertThat(ledger.currentUsage().totalBytes).isEqualTo(1 * MB)
  }

  @Test
  fun osservare_ilConsumoSiAggiornaAOgniRegistrazione() = runTest {
    val ledger = DataStoreTrafficLedger(store(), clock)

    ledger.observeCurrentUsage().test {
      assertThat(awaitItem().totalBytes).isEqualTo(0)
      ledger.record(TrafficSource.HQ_PREVIEWS, TrafficNetwork.HOME_WIFI, 3 * MB)
      assertThat(awaitItem().totalBytes).isEqualTo(3 * MB)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun ognunaDelleAttivitaHaLaSuaFonteDiTraffico() {
    for (activity in BackgroundActivity.entries) {
      assertThat(TrafficSource.of(activity).name).isEqualTo(activity.name)
    }
    assertThat(TrafficNetwork.of(dev.pampa.mycloudgallery.core.rules.NetworkClass.OFFLINE)).isNull()
    assertThat(TrafficNetwork.of(dev.pampa.mycloudgallery.core.rules.NetworkClass.MOBILE)).isEqualTo(TrafficNetwork.MOBILE)
  }
}
