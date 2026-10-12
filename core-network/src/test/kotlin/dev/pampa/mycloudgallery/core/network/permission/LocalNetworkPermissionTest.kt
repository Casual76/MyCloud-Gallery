package dev.pampa.mycloudgallery.core.network.permission

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Il ramo per il target 37 e' pronto (docs/android.md 7.3); con il target 36 di oggi il permesso e' implicito. */
@RunWith(RobolectricTestRunner::class)
class LocalNetworkPermissionTest {

  @Test
  fun tabellaDelleRegole() {
    // (SDK del dispositivo, target dell'app, permesso concesso) -> stato
    val rows = listOf(
      Triple(29, 36, false) to LocalNetworkAccess.GRANTED_IMPLICIT,
      Triple(35, 36, false) to LocalNetworkAccess.GRANTED_IMPLICIT,
      Triple(36, 36, false) to LocalNetworkAccess.GRANTED_IMPLICIT, // Android 16: il permesso non esiste
      Triple(36, 37, false) to LocalNetworkAccess.GRANTED_IMPLICIT,
      Triple(37, 36, true) to LocalNetworkAccess.GRANTED_IMPLICIT, // Android 17, target 36: concessione implicita
      Triple(37, 36, false) to LocalNetworkAccess.DENIED, // revocata
      Triple(37, 37, true) to LocalNetworkAccess.GRANTED, // target 37, richiesto a runtime
      Triple(37, 37, false) to LocalNetworkAccess.DENIED,
    )

    for ((input, expected) in rows) {
      val (sdk, target, granted) = input
      assertThat(LocalNetworkPermissions.access(sdk, target, granted)).isEqualTo(expected)
    }
  }

  @Test
  fun ilNomeEQuelloDiAndroid17() {
    assertThat(LocalNetworkPermissions.PERMISSION).isEqualTo("android.permission.ACCESS_LOCAL_NETWORK")
  }

  @Test
  fun suAndroid16EPrima_ilPermessoNonSiLegge() {
    val application = ApplicationProvider.getApplicationContext<Application>()

    assertThat(AndroidLocalNetworkPermission(application, sdkInt = 35).status()).isEqualTo(LocalNetworkAccess.GRANTED_IMPLICIT)
  }

  @Test
  fun suAndroid17_siLeggeIlPermessoVero() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val permission = AndroidLocalNetworkPermission(application, sdkInt = 37)

    // Nessuna concessione: negato.
    assertThat(permission.status()).isEqualTo(LocalNetworkAccess.DENIED)

    shadowOf(application).grantPermissions(LocalNetworkPermissions.PERMISSION)
    val target = application.applicationInfo.targetSdkVersion
    assertThat(permission.status()).isEqualTo(if (target >= 37) LocalNetworkAccess.GRANTED else LocalNetworkAccess.GRANTED_IMPLICIT)
  }

  @Test
  fun conTarget37ConcessoEGranted() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    application.applicationInfo.targetSdkVersion = 37
    shadowOf(application).grantPermissions(LocalNetworkPermissions.PERMISSION)

    assertThat(AndroidLocalNetworkPermission(application, sdkInt = 37).status()).isEqualTo(LocalNetworkAccess.GRANTED)
  }
}
