package dev.pampa.mycloudgallery.core.network.vpn

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** La scelta sul controllo di WireGuard e la sospensione: persistenti, validate, con il lato prudente di default. */
class VpnControlStoresTest {

  @get:Rule val folder = TemporaryFolder()

  private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private fun storeOn(file: File, scope: CoroutineScope) =
    DataStoreVpnControlConfigStore(
      PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, scope = scope, produceFile = { file }),
      scope,
    )

  @Test
  fun difabbrica_ilControlloESpento() = runBlocking {
    val scope = newScope()
    val store = storeOn(File(folder.newFolder(), "vpn.preferences_pb"), scope)

    assertThat(store.config.value).isEqualTo(VpnControlConfig.OFF)
    assertThat(store.suspension.value).isNull()
    scope.coroutineContext[Job]!!.cancelAndJoin()
  }

  @Test
  fun salvaERilegge_ancheDopoIlRiavvio() = runBlocking {
    val file = File(folder.newFolder(), "vpn.preferences_pb")
    val scope1 = newScope()
    val first = storeOn(file, scope1)
    first.save(VpnControlConfig(enabled = true, tunnelName = "casa"))
    first.suspendBecause(VpnSuspendReason.LOCKDOWN_SUSPECTED)
    scope1.coroutineContext[Job]!!.cancelAndJoin()

    val scope2 = newScope()
    val second = storeOn(file, scope2)
    second.suspension.first { it != null }

    assertThat(second.config.value).isEqualTo(VpnControlConfig(enabled = true, tunnelName = "casa"))
    assertThat(second.suspension.value).isEqualTo(VpnSuspendReason.LOCKDOWN_SUSPECTED)
    scope2.coroutineContext[Job]!!.cancelAndJoin()
  }

  @Test
  fun salvareLaConfigurazione_togliLaSospensione() = runBlocking {
    val scope = newScope()
    val store = storeOn(File(folder.newFolder(), "vpn.preferences_pb"), scope)
    store.suspendBecause(VpnSuspendReason.NOT_CONTROLLABLE)
    assertThat(store.suspension.value).isEqualTo(VpnSuspendReason.NOT_CONTROLLABLE)

    store.save(VpnControlConfig(enabled = true, tunnelName = "casa"))

    assertThat(store.suspension.value).isNull()
    scope.coroutineContext[Job]!!.cancelAndJoin()
  }

  @Test
  fun clearSuspension_laTogliSenzaToccareLaConfigurazione() = runBlocking {
    val scope = newScope()
    val store = storeOn(File(folder.newFolder(), "vpn.preferences_pb"), scope)
    store.save(VpnControlConfig(enabled = true, tunnelName = "casa"))
    store.suspendBecause(VpnSuspendReason.LOCKDOWN_SUSPECTED)

    store.clearSuspension()

    assertThat(store.suspension.value).isNull()
    assertThat(store.config.value.tunnelName).isEqualTo("casa")
    scope.coroutineContext[Job]!!.cancelAndJoin()
  }

  @Test
  fun unNomeDiTunnelNonValido_diventaNessunNome() = runBlocking {
    val scope = newScope()
    val store = storeOn(File(folder.newFolder(), "vpn.preferences_pb"), scope)

    store.save(VpnControlConfig(enabled = true, tunnelName = "casa; rm -rf /"))

    assertThat(store.config.value).isEqualTo(VpnControlConfig(enabled = true, tunnelName = null))
    store.save(VpnControlConfig(enabled = true, tunnelName = "  casa  "))
    assertThat(store.config.value.tunnelName).isEqualTo("casa")
    scope.coroutineContext[Job]!!.cancelAndJoin()
  }
}
