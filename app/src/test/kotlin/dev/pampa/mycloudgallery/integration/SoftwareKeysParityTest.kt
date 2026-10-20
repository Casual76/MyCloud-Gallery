package dev.pampa.mycloudgallery.integration

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Il Keystore finto del banco dell'app ([SoftwareKeys]) si comporta come la classe vera, `AndroidKeystoreKeyProvider` di core-trust,
 * e come i finti di core-family (lo stesso controllo e' `SoftwareKeysParityTest` di core-family). R7 della revisione 5B: prima qui
 * mancava il Keystore che non risponde, e il grafo vero non si provava con un guasto.
 *
 * Il comportamento vero che si rispecchia: `hasDeviceKey` risponde senza creare la chiave; `deviceSigner` la crea se manca e poi da'
 * sempre la stessa; distruggere una chiave che non c'e' non fa niente; un Keystore che non risponde lancia a ogni domanda e non
 * cambia niente, e quando torna la chiave e' quella di prima.
 */
class SoftwareKeysParityTest {

  @Test
  fun comeIlVero_chiedereNonCreaLaChiaveLaFirmaSiEPoiEDSempreLaStessa() {
    val keys = SoftwareKeys()
    assertThat(keys.hasDeviceKey()).isFalse()
    assertThat(keys.hasDeviceKey()).isFalse()
    val first = keys.deviceSigner().publicKey
    assertThat(keys.hasDeviceKey()).isTrue()
    assertThat(keys.deviceSigner().publicKey).isEqualTo(first)

    keys.destroyDeviceKey()
    assertThat(keys.hasDeviceKey()).isFalse()
    keys.destroyDeviceKey() // una chiave che non c'e': niente
    assertThat(keys.deviceSigner().publicKey).isNotEqualTo(first)
  }

  @Test
  fun comeIlVero_unKeystoreCheNonRispondeLanciaAOgniDomandaENonCambiaNiente() {
    val keys = SoftwareKeys()
    val key = keys.deviceSigner().publicKey
    keys.broken = true
    assertThat(runCatching { keys.hasDeviceKey() }.isFailure).isTrue()
    assertThat(runCatching { keys.deviceSigner() }.isFailure).isTrue()
    assertThat(runCatching { keys.destroyDeviceKey() }.isFailure).isTrue()

    keys.broken = false
    assertThat(keys.hasDeviceKey()).isTrue()
    assertThat(keys.deviceSigner().publicKey).isEqualTo(key)
  }
}
