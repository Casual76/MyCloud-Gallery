package dev.pampa.mycloudgallery.core.network.home

import android.app.Application
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.env.LiveNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.testing.FakeHomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.testing.FakeLocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkObservationSource
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** L'impronta di casa nel DataStore (noBackupFilesDir), validata in lettura e in scrittura. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeStorageTest {

  @get:Rule val folder = TemporaryFolder()

  private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private fun storeOn(file: File, scope: CoroutineScope) =
    DataStoreHomeFingerprintStore(
      PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, scope = scope, produceFile = { file }),
      scope,
    )

  private val saved = HomeFingerprint(setOf(LinkFactsSamples.HOME_ULA), "192.168.178.1", "fritz.box", 123L)

  @Test
  fun salvaERilegge_ancheDopoIlRiavvio() = runBlocking {
    val file = File(folder.newFolder(), "home.preferences_pb")
    val scope1 = newScope()
    storeOn(file, scope1).save(saved)
    scope1.coroutineContext[Job]!!.cancelAndJoin()

    val scope2 = newScope()
    val reopened = storeOn(file, scope2)
    val read = reopened.fingerprint.first { it != null }

    assertThat(read).isEqualTo(saved)
    scope2.coroutineContext[Job]!!.cancelAndJoin()
  }

  @Test
  fun ilValoreInMemoriaSegueSubito_saveEClear() = runBlocking {
    val scope = newScope()
    val store = storeOn(File(folder.newFolder(), "home.preferences_pb"), scope)

    store.save(saved)
    assertThat(store.fingerprint.value).isEqualTo(saved)
    store.clear()
    assertThat(store.fingerprint.value).isNull()
    scope.coroutineContext[Job]!!.cancelAndJoin()
  }

  @Test
  fun siSalvaSoloCioCheEValido() = runBlocking {
    val scope = newScope()
    val store = storeOn(File(folder.newFolder(), "home.preferences_pb"), scope)

    store.save(
      HomeFingerprint(
        ulaPrefixes = setOf("fd12:3456:789a::/48", "fc00::/7", "2001:db8::/48", "non-un-prefisso"),
        gatewayV4 = "fe80::1", // non e' un IPv4
        dnsDomain = "  FRITZ.BOX. ",
        capturedAt = 5L,
      ),
    )

    val read = store.fingerprint.value!!
    assertThat(read.ulaPrefixes).containsExactly("fd12:3456:789a::/48")
    assertThat(read.gatewayV4).isNull()
    assertThat(read.dnsDomain).isEqualTo("fritz.box")
    assertThat(read.capturedAt).isEqualTo(5L)
    scope.coroutineContext[Job]!!.cancelAndJoin()
  }

  @Test
  fun senzaNessunUlaValido_nonCeImpronta() = runBlocking {
    val scope = newScope()
    val store = storeOn(File(folder.newFolder(), "home.preferences_pb"), scope)
    store.save(saved)

    store.save(HomeFingerprint(setOf("fc00::/7", "2001:db8::/48"), "192.168.178.1", "fritz.box", 6L))

    assertThat(store.fingerprint.value).isNull()
    scope.coroutineContext[Job]!!.cancelAndJoin()
  }

  @Test
  fun alMassimoQuattroUla() = runBlocking {
    val scope = newScope()
    val store = storeOn(File(folder.newFolder(), "home.preferences_pb"), scope)

    store.save(HomeFingerprint((1..6).map { "fd0$it::/48" }.toSet(), null, null, 1L))

    assertThat(store.fingerprint.value!!.ulaPrefixes).hasSize(4)
    scope.coroutineContext[Job]!!.cancelAndJoin()
  }

  @Test
  fun unFileDanneggiato_siIgnora_eSiPuoRisalvare() = runBlocking {
    val file = File(folder.newFolder(), "home.preferences_pb")
    file.writeBytes(ByteArray(64) { 0x7F })
    val scope = newScope()
    val store = storeOn(file, scope)

    // Non c'e' nessuna impronta (e nessun crash).
    assertThat(store.fingerprint.value).isNull()
    store.save(saved)
    assertThat(store.fingerprint.value).isEqualTo(saved)
    scope.coroutineContext[Job]!!.cancelAndJoin()
  }

  @Test
  fun create_usaNoBackupFilesDir() = runBlocking {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val scope = newScope()
    val store = DataStoreHomeFingerprintStore.create(application, scope)

    store.save(saved)

    val file = File(application.noBackupFilesDir, "datastore/network_home.preferences_pb")
    assertThat(file.exists()).isTrue()
    assertThat(file.parentFile?.parentFile).isEqualTo(application.noBackupFilesDir)
    scope.coroutineContext[Job]!!.cancelAndJoin()
  }

  // --- La raccolta dal Wi-Fi attuale ---------------------------------------------------------------------

  private fun captureOf(vararg networks: LinkFacts, default: String? = networks.firstOrNull()?.networkId): ObservedHomeCapture {
    val source = FakeNetworkObservationSource(LinkFactsSamples.observation(default, *networks))
    val environment = LiveNetworkEnvironment(source, FakeHomeFingerprintStore(null), FakeLocalNetworkPermission(), CoroutineScope(Job()), clockMillis = { 1L })
    return ObservedHomeCapture(environment)
  }

  @Test
  fun laRaccolta_legge_ilWifiAttuale() = runTest {
    val candidate = captureOf(LinkFactsSamples.homeWifi()).candidateFromCurrentWifi()

    assertThat(candidate!!.ulaPrefixes).containsExactly(LinkFactsSamples.HOME_ULA)
    assertThat(candidate.networkId).isEqualTo("wifi-home")
  }

  @Test
  fun laRaccolta_senzaWifi_oConSoloCellulareOVpn_nonDaNulla() = runTest {
    assertThat(captureOf().candidateFromCurrentWifi()).isNull()
    assertThat(captureOf(LinkFactsSamples.cellular()).candidateFromCurrentWifi()).isNull()
    assertThat(captureOf(LinkFactsSamples.homeVpn(over = NetTransport.CELLULAR)).candidateFromCurrentWifi()).isNull()
  }

  @Test
  fun laRaccolta_unaPaginaDiAccessoNonECasa() = runTest {
    assertThat(captureOf(LinkFactsSamples.cafeWifi(validated = false, captivePortal = true)).candidateFromCurrentWifi()).isNull()
  }

  @Test
  fun laRaccolta_conDueWifi_preferisceLaPredefinita() = runTest {
    val capture = captureOf(LinkFactsSamples.homeWifi(), LinkFactsSamples.friendWifi(), default = "wifi-friend")

    assertThat(capture.candidateFromCurrentWifi()!!.networkId).isEqualTo("wifi-friend")
  }
}
