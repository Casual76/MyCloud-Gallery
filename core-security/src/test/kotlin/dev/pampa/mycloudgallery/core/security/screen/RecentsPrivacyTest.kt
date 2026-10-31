package dev.pampa.mycloudgallery.core.security.screen

import android.app.Activity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** L'anteprima nei recenti: API vera da Android 13, FLAG_SECURE come ripiego sotto. */
@RunWith(RobolectricTestRunner::class)
class RecentsPrivacyTest {

  private fun newActivity(): Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

  private fun Activity.secure() = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

  @Test
  fun daAndroid13_siUsaLApiDedicata_enonSiBloccanoGliScreenshot() {
    val activity = newActivity()
    val recents = RecentsPrivacy(sdkInt = 33)

    recents.setHidden(activity, true)
    assertThat(activity.secure()).isFalse()
    recents.setHidden(activity, false)
    assertThat(activity.secure()).isFalse()
  }

  @Test
  fun conAndroidVero_chiamarlaNonLanciaEccezioni() {
    val activity = newActivity()
    RecentsPrivacy.instance.setHidden(activity, true)
    RecentsPrivacy.instance.setHidden(activity, false)
  }

  @Test
  fun sottoAndroid13_ilRipiegoEFlagSecure_esiToglieAlRilascio() {
    val activity = newActivity()
    val recents = RecentsPrivacy(sdkInt = 32)

    recents.setHidden(activity, true)
    assertThat(activity.secure()).isTrue()
    recents.setHidden(activity, false)
    assertThat(activity.secure()).isFalse()
  }

  /** Fase 10 (P4, D17): con il blocco attivo ogni finestra oscura i suoi recenti; chiusa una, l'altra resta oscurata. */
  @Test
  fun dueFinestre_ognunaOscuraIPropriRecenti_eIndipendentemente() {
    val a = newActivity()
    val b = newActivity()
    val recents = RecentsPrivacy(sdkInt = 32)

    recents.setHidden(a, true)
    recents.setHidden(b, true)
    assertThat(a.secure()).isTrue()
    assertThat(b.secure()).isTrue()

    recents.setHidden(a, false)
    assertThat(a.secure()).isFalse()
    assertThat(b.secure()).isTrue()
  }

  @Test
  fun sottoAndroid13_nasconderePiuVolte_eUnaSolaVolta() {
    val activity = newActivity()
    val recents = RecentsPrivacy(sdkInt = 29)

    recents.setHidden(activity, true)
    recents.setHidden(activity, true)
    recents.setHidden(activity, false) // un solo rilascio basta: l'anteprima e' una condizione, non un contatore
    assertThat(activity.secure()).isFalse()
    // mostrare quando non e' nascosto non fa niente
    recents.setHidden(activity, false)
    assertThat(activity.secure()).isFalse()
  }

  @Test
  fun ilRipiegoConvivConUnaSchermataRiservata() {
    val activity = newActivity()
    val recents = RecentsPrivacy(sdkInt = 30)
    val screen = WindowProtection.of(activity.window).acquireSecureContent()

    recents.setHidden(activity, true)
    recents.setHidden(activity, false)
    assertThat(activity.secure()).isTrue() // la schermata riservata e' ancora aperta

    screen.close()
    assertThat(activity.secure()).isFalse()
  }
}
