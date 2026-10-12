package dev.pampa.mycloudgallery.core.security.credentials

import android.security.keystore.StrongBoxUnavailableException
import com.google.common.truth.Truth.assertThat
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * La scelta StrongBox -> TEE, senza telefono: il generatore e' finto e registra come viene chiamato. La
 * generazione vera (parametri della chiave, livello reale) si prova sull'S25 (test instrumented).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSecretKeyStoreTest {

  private class RecordingGenerator(private val strongBoxFails: Throwable? = null, private val teeFails: Throwable? = null) : KeyMaterialGenerator {
    val calls = mutableListOf<Boolean>()
    override fun generate(alias: String, strongBox: Boolean): SecretKey {
      calls += strongBox
      (if (strongBox) strongBoxFails else teeFails)?.let { throw it }
      return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
  }

  private fun keyStore() = AndroidSecretKeyStore(
    hasStrongBox = true,
    generator = RecordingGenerator(),
    keyStore = { KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) } },
  )

  private fun store(hasStrongBox: Boolean, generator: KeyMaterialGenerator) = AndroidSecretKeyStore(
    hasStrongBox = hasStrongBox,
    generator = generator,
    keyStore = { KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) } },
  )

  @Test
  fun conStrongBox_siProvaPrimaStrongBox() {
    val generator = RecordingGenerator()
    store(hasStrongBox = true, generator).create("a")
    assertThat(generator.calls).containsExactly(true)
  }

  @Test
  fun senzaStrongBox_siVaDirettiSulTee() {
    val generator = RecordingGenerator()
    store(hasStrongBox = false, generator).create("a")
    assertThat(generator.calls).containsExactly(false)
  }

  @Test
  fun strongBoxNonDisponibile_ripiegaSulTee() {
    val generator = RecordingGenerator(strongBoxFails = StrongBoxUnavailableException("niente chip"))
    val created = store(hasStrongBox = true, generator).create("a")
    assertThat(generator.calls).containsExactly(true, false).inOrder()
    assertThat(created.key.algorithm).isEqualTo("AES")
  }

  @Test
  fun strongBoxCheDaUnErroreDiProvider_ripiegaSulTee() {
    val generator = RecordingGenerator(strongBoxFails = ProviderException("daemon strongbox"))
    store(hasStrongBox = true, generator).create("a")
    assertThat(generator.calls).containsExactly(true, false).inOrder()
  }

  @Test
  fun seAncheIlTeeFallisce_l_erroreArrivaAlChiamante() {
    val generator = RecordingGenerator(strongBoxFails = StrongBoxUnavailableException(), teeFails = ProviderException("tee guasto"))
    assertThrows(ProviderException::class.java) { store(hasStrongBox = true, generator).create("a") }
    assertThat(generator.calls).containsExactly(true, false).inOrder()
  }

  @Test
  fun unErroreNonDiProvider_nonSiNascondeConIlRipiego() {
    // Se StrongBox fallisce per un motivo che non e' "non disponibile" (es. parametri sbagliati) non lo mascheriamo.
    val generator = RecordingGenerator(strongBoxFails = IllegalStateException("bug"))
    assertThrows(IllegalStateException::class.java) { store(hasStrongBox = true, generator).create("a") }
    assertThat(generator.calls).containsExactly(true)
  }

  @Test
  fun inJvm_ilLivelloNonSiLegge_eVieneDichiaratoSconosciuto() {
    // Fuori da un telefono non esiste l'AndroidKeyStore: nessuna eccezione, solo "sconosciuto".
    val created = keyStore().create("a")
    assertThat(created.level).isEqualTo(KeyProtectionLevel.UNKNOWN)
  }
}
