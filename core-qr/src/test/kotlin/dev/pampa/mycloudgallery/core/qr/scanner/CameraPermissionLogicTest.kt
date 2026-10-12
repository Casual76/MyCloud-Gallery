package dev.pampa.mycloudgallery.core.qr.scanner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Le due decisioni del permesso della fotocamera, a tabella: cosa ricordare dopo il "no", cosa mostrare. */
class CameraPermissionLogicTest {

  @Test
  fun dopoLaRisposta_siRicordaSeIlSistemaChiederaAncora() {
    assertThat(denialAfter(granted = true, canAskAgain = true)).isEqualTo(Denial.None)
    assertThat(denialAfter(granted = true, canAskAgain = false)).isEqualTo(Denial.None)
    assertThat(denialAfter(granted = false, canAskAgain = true)).isEqualTo(Denial.Once)
    assertThat(denialAfter(granted = false, canAskAgain = false)).isEqualTo(Denial.Permanent)
  }

  @Test
  fun lAccesso_senzaFotocamera_vincePerTutto() {
    for (granted in listOf(true, false)) for (denial in Denial.entries) {
      assertThat(accessOf(hasCamera = false, granted = granted, denial = denial)).isEqualTo(CameraAccess.NoCamera)
    }
  }

  @Test
  fun lAccesso_concessoVinceSuiVecchiNo() {
    for (denial in Denial.entries) {
      assertThat(accessOf(hasCamera = true, granted = true, denial = denial)).isEqualTo(CameraAccess.Granted)
    }
  }

  @Test
  fun lAccesso_senzaPermesso() {
    assertThat(accessOf(true, false, Denial.None)).isEqualTo(CameraAccess.NotAsked)
    assertThat(accessOf(true, false, Denial.Once)).isEqualTo(CameraAccess.Denied)
    assertThat(accessOf(true, false, Denial.Permanent)).isEqualTo(CameraAccess.DeniedPermanently)
  }

  @Test
  fun lePagine_perOgniAccesso() {
    fun page(access: CameraAccess, manualOpen: Boolean = false) = ScannerUi(access = access, manualOpen = manualOpen).page
    assertThat(page(CameraAccess.Granted)).isEqualTo(ScannerPage.Viewfinder)
    assertThat(page(CameraAccess.Granted, manualOpen = true)).isEqualTo(ScannerPage.Manual)
    assertThat(page(CameraAccess.NotAsked)).isEqualTo(ScannerPage.Ask)
    assertThat(page(CameraAccess.NotAsked, manualOpen = true)).isEqualTo(ScannerPage.Manual)
    // dopo un no o senza fotocamera il campo e' sempre li', senza bisogno di sceglierlo
    for (access in listOf(CameraAccess.Denied, CameraAccess.DeniedPermanently, CameraAccess.NoCamera)) {
      assertThat(page(access)).isEqualTo(ScannerPage.Manual)
    }
  }
}
