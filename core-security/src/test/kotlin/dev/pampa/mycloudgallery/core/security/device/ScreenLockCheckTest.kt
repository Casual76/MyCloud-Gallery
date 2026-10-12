package dev.pampa.mycloudgallery.core.security.device

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** E-S3: telefono senza blocco schermo -> avviso. */
@RunWith(RobolectricTestRunner::class)
class ScreenLockCheckTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val keyguard get() = context.getSystemService(KeyguardManager::class.java)

  @Test
  fun conBloccoSchermo_nessunAvviso() {
    shadowOf(keyguard).setIsDeviceSecure(true)
    val status = ScreenLockCheck.status(context)
    assertThat(status).isEqualTo(ScreenLockStatus.SECURE)
    assertThat(status.needsWarning).isFalse()
  }

  @Test
  fun senzaBloccoSchermo_siAvvisa() {
    shadowOf(keyguard).setIsDeviceSecure(false)
    val status = ScreenLockCheck.status(context)
    assertThat(status).isEqualTo(ScreenLockStatus.NOT_SECURE)
    assertThat(status.needsWarning).isTrue()
  }

  @Test
  fun siRilegge_ogniVolta() {
    shadowOf(keyguard).setIsDeviceSecure(false)
    assertThat(ScreenLockCheck.status(context).needsWarning).isTrue()
    shadowOf(keyguard).setIsDeviceSecure(true)
    assertThat(ScreenLockCheck.status(context).needsWarning).isFalse()
  }

  @Test
  fun quandoIlSistemaNonRisponde_nonSiAvvisaPerSbaglio() {
    assertThat(ScreenLockStatus.UNKNOWN.needsWarning).isFalse()
    assertThat(ScreenLockStatus.SECURE.needsWarning).isFalse()
  }
}
