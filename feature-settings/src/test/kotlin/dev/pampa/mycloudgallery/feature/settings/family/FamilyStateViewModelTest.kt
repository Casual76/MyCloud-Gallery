package dev.pampa.mycloudgallery.feature.settings.family

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.testing.FakeQrPayloadCodec
import dev.pampa.mycloudgallery.core.family.testing.FakeTrustStateStore
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class FamilyStateViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private val codec = FakeQrPayloadCodec<FamilyStateQr>()

  @Test
  fun senzaUnRegistroAccettatoNonMostraNessunQr() = runTest {
    val vm = FamilyStateViewModel(FakeTrustStateStore(snapshot = null), codec)
    assertThat(vm.state.value).isEqualTo(FamilyStateUi.Unavailable)
  }

  @Test
  fun unRegistroLocaleIllegibileNonProduceUnQrFinto() = runTest {
    val broken = TrustSnapshot(
      registryBytes = ByteArray(16) { it.toByte() },
      signerFingerprint = "impronta",
      familyId = "famiglia",
      familyKeyFingerprint = "impronta",
      minRegistryVersion = 1,
      ownHeads = emptyMap(),
    )
    val vm = FamilyStateViewModel(FakeTrustStateStore(broken), codec)
    assertThat(vm.state.value).isEqualTo(FamilyStateUi.Unavailable)
    assertThat(codec.known).isEmpty()
  }
}
