package dev.pampa.mycloudgallery.core.family.support

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.p3.SoftwareKeys
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import org.junit.Test

/**
 * I due Keystore finti di core-family (quello dei test dell'onboarding, `SoftwareKeyProvider`, e quello dei test di P3,
 * `p3.SoftwareKeys`) si comportano come la classe vera, `AndroidKeystoreKeyProvider` di core-trust, e quindi fra loro (R7 della
 * revisione 5B). Prima solo il primo sapeva fare il Keystore che non risponde: i test del motore della fiducia non potevano provarlo.
 *
 * Il comportamento vero che si rispecchia: `hasDeviceKey` risponde senza creare la chiave; `deviceSigner` la crea se manca e poi da'
 * sempre la stessa; distruggere una chiave che non c'e' non fa niente; un Keystore che non risponde lancia a ogni domanda (lo apre
 * `KeyStore.getInstance(...).load(null)` per ognuna) e non cambia niente, e quando torna la chiave e' quella di prima.
 */
class SoftwareKeysParityTest {

  /** Un finto, con il suo interruttore del guasto. */
  private class Subject(val name: String, val keys: KeyProvider, val setBroken: (Boolean) -> Unit)

  private fun subjects(): List<Subject> = listOf(
    SoftwareKeyProvider().let { keys -> Subject("support.SoftwareKeyProvider", keys) { keys.broken = it } },
    SoftwareKeys().let { keys -> Subject("p3.SoftwareKeys", keys) { keys.broken = it } },
  )

  @Test
  fun comeIlVero_chiedereNonCreaLaChiaveLaFirmaSiEPoiEDSempreLaStessa() {
    for (subject in subjects()) {
      val keys = subject.keys
      assertWithMessage(subject.name).that(keys.hasDeviceKey()).isFalse()
      assertWithMessage("${subject.name}: chiedere non crea").that(keys.hasDeviceKey()).isFalse()
      val first = keys.deviceSigner().publicKey
      assertWithMessage(subject.name).that(keys.hasDeviceKey()).isTrue()
      assertWithMessage(subject.name).that(keys.deviceSigner().publicKey).isEqualTo(first)

      keys.destroyDeviceKey()
      assertWithMessage(subject.name).that(keys.hasDeviceKey()).isFalse()
      keys.destroyDeviceKey() // una chiave che non c'e': niente
      assertWithMessage("${subject.name}: dopo la distruzione, una chiave nuova").that(keys.deviceSigner().publicKey).isNotEqualTo(first)
    }
  }

  @Test
  fun comeIlVero_unKeystoreCheNonRispondeLanciaAOgniDomandaENonCambiaNiente() {
    for (subject in subjects()) {
      val keys = subject.keys
      val key = keys.deviceSigner().publicKey
      subject.setBroken(true)
      assertWithMessage("${subject.name}: hasDeviceKey").that(runCatching { keys.hasDeviceKey() }.isFailure).isTrue()
      assertWithMessage("${subject.name}: deviceSigner").that(runCatching { keys.deviceSigner() }.isFailure).isTrue()
      assertWithMessage("${subject.name}: destroyDeviceKey").that(runCatching { keys.destroyDeviceKey() }.isFailure).isTrue()

      // Tornato a rispondere: la chiave c'e' ed e' la stessa (il guasto non la toglie e non la cambia).
      subject.setBroken(false)
      assertWithMessage(subject.name).that(keys.hasDeviceKey()).isTrue()
      assertWithMessage(subject.name).that(keys.deviceSigner().publicKey).isEqualTo(key)
    }
  }
}
