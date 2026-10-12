package dev.pampa.mycloudgallery.core.security.screen

import android.app.Application
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** FLAG_SECURE e protezione dalle sovrapposizioni a contatore, e il ripiego dei recenti sulle versioni vecchie. */
@RunWith(RobolectricTestRunner::class)
class WindowProtectionTest {

  private fun grantOverlayPermission() {
    shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(HIDE_OVERLAY_WINDOWS)
  }

  /** Di default il permesso normale e' concesso, come su un telefono vero dopo l'installazione. */
  private fun newWindow(withPermission: Boolean = true): Window {
    if (withPermission) grantOverlayPermission()
    return Robolectric.buildActivity(ComponentActivity::class.java).setup().get().window
  }

  private fun Window.secure() = (attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

  /** `privateFlags` e' un campo nascosto della finestra: in Robolectric si legge per riflessione. */
  private fun Window.hidesOverlays(): Boolean {
    val privateFlags = WindowManager.LayoutParams::class.java.getField("privateFlags").getInt(attributes)
    return (privateFlags and PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0
  }

  // --- FLAG_SECURE -----------------------------------------------------------------------------------------

  @Test
  fun flagSecure_siAccende_esiSpegneAlRilascio() {
    val window = newWindow()
    assertThat(window.secure()).isFalse()

    val handle = WindowProtection.of(window).acquireSecureContent()
    assertThat(window.secure()).isTrue()
    assertThat(WindowProtection.of(window).isSecure).isTrue()

    handle.close()
    assertThat(window.secure()).isFalse()
  }

  @Test
  fun flagSecure_aContatore_restaFinoAlPrimoRilascioDiTutti() {
    val window = newWindow()
    val first = WindowProtection.of(window).acquireSecureContent()
    val second = WindowProtection.of(window).acquireSecureContent()

    first.close()
    assertThat(window.secure()).isTrue()
    second.close()
    assertThat(window.secure()).isFalse()
  }

  @Test
  fun rilasciareDueVolteLoStessoPermesso_nonTogliLaProtezioneAgliAltri() {
    val window = newWindow()
    val first = WindowProtection.of(window).acquireSecureContent()
    val second = WindowProtection.of(window).acquireSecureContent()

    first.close()
    first.close()
    first.close()
    assertThat(window.secure()).isTrue()
    second.close()
    assertThat(window.secure()).isFalse()
  }

  @Test
  fun seLaFinestraAvevaGiaFlagSecure_alRilascioNonLoTogliamo() {
    val window = newWindow()
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

    WindowProtection.of(window).acquireSecureContent().close()

    assertThat(window.secure()).isTrue()
  }

  @Test
  fun unaFinestraNonInfluenzaUnAltra() {
    val a = newWindow()
    val b = newWindow()
    WindowProtection.of(a).acquireSecureContent()
    assertThat(b.secure()).isFalse()
  }

  @Test
  fun ofRestituisceLaStessaIstanzaPerLaStessaFinestra() {
    val window = newWindow()
    assertThat(WindowProtection.of(window)).isSameInstanceAs(WindowProtection.of(window))
  }

  // --- sovrapposizioni -------------------------------------------------------------------------------------

  @Test
  fun anti_sovrapposizioni_nascondeGliOverlay_escartaITocchiOscurati() {
    val window = newWindow()
    assertThat(window.decorView.filterTouchesWhenObscured).isFalse()
    assertThat(window.hidesOverlays()).isFalse()

    val handle = WindowProtection.of(window).acquireOverlayShield()
    assertThat(window.decorView.filterTouchesWhenObscured).isTrue()
    assertThat(window.hidesOverlays()).isTrue()

    handle.close()
    assertThat(window.decorView.filterTouchesWhenObscured).isFalse()
    assertThat(window.hidesOverlays()).isFalse()
  }

  @Test
  fun senzaIlPermesso_ilSistemaLanciaSecurityException_eLaSchermataNonCade() {
    val window = newWindow(withPermission = false)

    val handle = WindowProtection.of(window).acquireOverlayShield() // non deve lanciare

    assertThat(window.hidesOverlays()).isFalse()
    assertThat(window.decorView.filterTouchesWhenObscured).isTrue() // resta almeno il filtro dei tocchi
    handle.close()
    assertThat(window.decorView.filterTouchesWhenObscured).isFalse()
  }

  @Test
  fun anti_sovrapposizioni_aContatore() {
    val window = newWindow()
    val first = WindowProtection.of(window).acquireOverlayShield()
    val second = WindowProtection.of(window).acquireOverlayShield()
    first.close()
    first.close()
    assertThat(window.decorView.filterTouchesWhenObscured).isTrue()
    assertThat(window.hidesOverlays()).isTrue()
    second.close()
    assertThat(window.decorView.filterTouchesWhenObscured).isFalse()
  }

  @Test
  fun seLaVistaFiltravaGiaITocchi_alRilascioContinuaAFarlo() {
    val window = newWindow()
    window.decorView.filterTouchesWhenObscured = true
    WindowProtection.of(window).acquireOverlayShield().close()
    assertThat(window.decorView.filterTouchesWhenObscured).isTrue()
  }

  @Test
  fun primaDiAndroid12_restaIlFiltroDeiTocchi_manonSiChiamaSetHideOverlayWindows() {
    val window = newWindow()
    val protection = WindowProtection(window, sdkInt = 30)

    val handle = protection.acquireOverlayShield()
    assertThat(window.decorView.filterTouchesWhenObscured).isTrue()
    assertThat(window.hidesOverlays()).isFalse()
    handle.close()
    assertThat(window.decorView.filterTouchesWhenObscured).isFalse()
  }

  @Test
  fun schermataDellaPassword_haTutteEDueLeProtezioni() {
    val window = newWindow()
    val secure = WindowProtection.of(window).acquireSecureContent()
    val shield = WindowProtection.of(window).acquireOverlayShield()
    assertThat(window.secure()).isTrue()
    assertThat(window.decorView.filterTouchesWhenObscured).isTrue()
    secure.close()
    shield.close()
    assertThat(window.secure()).isFalse()
    assertThat(window.decorView.filterTouchesWhenObscured).isFalse()
  }

  private companion object {
    /** `WindowManager.LayoutParams.PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (campo nascosto). */
    const val PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS = 0x00080000
    const val HIDE_OVERLAY_WINDOWS = "android.permission.HIDE_OVERLAY_WINDOWS"
  }
}
