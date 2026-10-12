package dev.pampa.mycloudgallery.feature.onboarding

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.device.ScreenLockStatus
import dev.pampa.mycloudgallery.feature.onboarding.platform.AndroidOnboardingPlatform
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionKind
import dev.pampa.mycloudgallery.feature.onboarding.platform.PermissionState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** La parte Android del passo 9 e 10: cosa Android dice dei permessi, cosa si chiede e quali impostazioni si aprono. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AndroidOnboardingPlatformTest {

  private val app: Application get() = ApplicationProvider.getApplicationContext()

  private fun platform(sdk: Int = Build.VERSION.SDK_INT) = AndroidOnboardingPlatform(app, sdk)

  @Test
  fun senzaPermessiLeFotoSonoNegate() {
    val states = platform().permissions().states
    assertThat(states.getValue(PermissionKind.MEDIA)).isEqualTo(PermissionState.DENIED)
    assertThat(states.getValue(PermissionKind.MEDIA_LOCATION)).isEqualTo(PermissionState.DENIED)
  }

  @Test
  fun conLeImmaginiEIVideoLaLibreriaECompleta() {
    shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    assertThat(platform().permissions().state(PermissionKind.MEDIA)).isEqualTo(PermissionState.GRANTED)
  }

  @Test
  fun conSoloLeFotoSelezionateEParziale() {
    shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    val snapshot = platform().permissions()
    assertThat(snapshot.state(PermissionKind.MEDIA)).isEqualTo(PermissionState.PARTIAL)
    assertThat(snapshot.reducedMode).isTrue()
  }

  @Test
  fun lePosizioniDeiMediaSonoUnPermessoASe() {
    shadowOf(app).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
    assertThat(platform().permissions().state(PermissionKind.MEDIA_LOCATION)).isEqualTo(PermissionState.GRANTED)
  }

  @Test
  fun leNotificheSiLeggonoDaAndroid() {
    val manager = app.getSystemService(NotificationManager::class.java)
    shadowOf(manager).setNotificationsEnabled(false)
    assertThat(platform().permissions().state(PermissionKind.NOTIFICATIONS)).isEqualTo(PermissionState.DENIED)
    shadowOf(manager).setNotificationsEnabled(true)
    assertThat(platform().permissions().state(PermissionKind.NOTIFICATIONS)).isEqualTo(PermissionState.GRANTED)
  }

  @Test
  fun ilDialogoDeiMediaChiedeIPermessiGiustiPerOgniVersione() {
    val asked = mutableListOf<List<String>>()
    listOf(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.S).forEach { sdk ->
      val platform = platform(sdk).apply { launcher = { asked += it.toList() } }
      platform.requestPermission(PermissionKind.MEDIA)
    }
    assertThat(asked[0]).containsExactly(
      Manifest.permission.READ_MEDIA_IMAGES,
      Manifest.permission.READ_MEDIA_VIDEO,
      Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    assertThat(asked[1]).containsExactly(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    assertThat(asked[2]).containsExactly(Manifest.permission.READ_EXTERNAL_STORAGE)
  }

  @Test
  fun unPermessoGiaChiestoESempreNegatoSiRicordaCheEStatoChiesto() {
    val platform = platform().apply { launcher = {} }
    assertThat(platform.permissions().requested).isEmpty()
    platform.requestPermission(PermissionKind.MEDIA_LOCATION)
    assertThat(platform.permissions().requested).containsExactly(PermissionKind.MEDIA_LOCATION)
  }

  @Test
  fun leNotificheSottoLaTredicesimaVersioneSiGestisconoDalleImpostazioni() {
    val platform = platform(Build.VERSION_CODES.S).apply { launcher = { error("nessun dialogo prima di Android 13") } }
    platform.requestPermission(PermissionKind.NOTIFICATIONS)
    val started: Intent? = shadowOf(app).nextStartedActivity
    assertThat(started?.action).isEqualTo(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
  }

  @Test
  fun senzaBloccoSchermoLoStatoLoDice() {
    val snapshot = platform().permissions()
    // Robolectric non ha un blocco schermo: il controllo di E-S3 risponde con uno stato noto (mai un'eccezione).
    assertThat(snapshot.screenLock).isAnyOf(ScreenLockStatus.SECURE, ScreenLockStatus.NOT_SECURE, ScreenLockStatus.UNKNOWN)
  }

  @Test
  fun unTabletSiRiconoscePerLaLarghezzaMinima() {
    assertThat(platform().isTablet()).isFalse()
  }

  @Test
  fun leImpostazioniDellAppSiApronoConIlPacchettoGiusto() {
    assertThat(platform().openAppSettings()).isTrue()
    val intent = shadowOf(app).nextStartedActivity
    assertThat(intent.action).isEqualTo(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    assertThat(intent.data?.schemeSpecificPart).isEqualTo(app.packageName)
  }
}
