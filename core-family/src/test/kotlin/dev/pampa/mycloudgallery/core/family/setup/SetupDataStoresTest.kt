package dev.pampa.mycloudgallery.core.family.setup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.onboarding.DataStoreOnboardingStateStore
import dev.pampa.mycloudgallery.core.family.onboarding.OnboardingSnapshot
import dev.pampa.mycloudgallery.core.family.onboarding.StoreState
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** I file dello stato locale stanno in `noBackupFilesDir` (fuori da backup e trasferimenti, C16) e un file rotto riparte da zero. */
@RunWith(RobolectricTestRunner::class)
class SetupDataStoresTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun iFileStannoInNoBackupFilesDir() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val store = DataStoreOnboardingStateStore(SetupDataStores.onboarding(context, scope), scope)
      assertThat(store.update { it.copy(deferrable = true) }!!.deferrable).isTrue()
      DataStoreNasAccountStore(SetupDataStores.account(context, scope)).save(NasAccount(NasAddress("192.168.178.58"), "alessio", "nas-primary", "g"))

      val onboarding = SetupDataStores.fileOf(context, SetupDataStores.ONBOARDING_FILE)
      val account = SetupDataStores.fileOf(context, SetupDataStores.ACCOUNT_FILE)
      assertThat(onboarding.exists()).isTrue()
      assertThat(account.exists()).isTrue()
      assertThat(onboarding.canonicalPath).startsWith(context.noBackupFilesDir.canonicalPath)
      assertThat(account.canonicalPath).startsWith(context.noBackupFilesDir.canonicalPath)
    } finally {
      scope.coroutineContext[Job]!!.cancelAndJoin()
    }
  }

  @Test
  fun unFileDanneggiatoDiventaVuoto() = runBlocking {
    val file = SetupDataStores.fileOf(context, SetupDataStores.ONBOARDING_FILE)
    file.parentFile!!.mkdirs()
    file.writeBytes(ByteArray(64) { 0x7f })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val store = DataStoreOnboardingStateStore(SetupDataStores.onboarding(context, scope), scope)
      val state = withTimeout(10_000) { store.state.first { it !is StoreState.Loading } }
      assertThat(state).isEqualTo(StoreState.Ready(OnboardingSnapshot.EMPTY))
      // E si puo' riscrivere.
      assertThat(store.update { it.copy(deferrable = true) }!!.deferrable).isTrue()
    } finally {
      scope.coroutineContext[Job]!!.cancelAndJoin()
    }
  }
}
