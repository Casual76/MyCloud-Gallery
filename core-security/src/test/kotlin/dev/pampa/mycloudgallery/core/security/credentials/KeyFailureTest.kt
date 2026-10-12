package dev.pampa.mycloudgallery.core.security.credentials

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Cosa vale come "chiave invalidata" (serve di nuovo la password) e cosa come "guasto passeggero" (E-S9). */
@RunWith(RobolectricTestRunner::class)
class KeyFailureTest {

  @Test
  fun chiaveInvalidata_ancheSeAvvoltaDaAltreEccezioni() {
    assertThat(KeyFailure.of(KeyPermanentlyInvalidatedException())).isEqualTo(KeyFailure.INVALIDATED)
    assertThat(KeyFailure.of(InvalidKeyException("Keystore operation failed", KeyPermanentlyInvalidatedException())))
      .isEqualTo(KeyFailure.INVALIDATED)
    assertThat(KeyFailure.of(RuntimeException("x", ProviderException("y", KeyPermanentlyInvalidatedException()))))
      .isEqualTo(KeyFailure.INVALIDATED)
  }

  @Test
  fun chiaveNonRecuperabile_eInvalidata() {
    assertThat(KeyFailure.of(UnrecoverableKeyException("Failed to obtain information about key"))).isEqualTo(KeyFailure.INVALIDATED)
  }

  @Test
  fun bloccoDanneggiato_eDatiErrati_nonChiavePersa() {
    assertThat(KeyFailure.of(AEADBadTagException())).isEqualTo(KeyFailure.BAD_DATA)
    assertThat(KeyFailure.of(IllegalBlockSizeException())).isEqualTo(KeyFailure.BAD_DATA)
    assertThat(KeyFailure.of(SealedFormatException("corto"))).isEqualTo(KeyFailure.BAD_DATA)
    assertThat(KeyFailure.of(CredentialFormatException("troncato"))).isEqualTo(KeyFailure.BAD_DATA)
  }

  @Test
  fun tuttoIlResto_eUnGuastoPasseggero_enonCancellaNiente() {
    assertThat(KeyFailure.of(IOException("disco"))).isEqualTo(KeyFailure.TRANSIENT)
    assertThat(KeyFailure.of(KeyStoreException("occupato"))).isEqualTo(KeyFailure.TRANSIENT)
    assertThat(KeyFailure.of(ProviderException("daemon"))).isEqualTo(KeyFailure.TRANSIENT)
    assertThat(KeyFailure.of(InvalidKeyException("generica"))).isEqualTo(KeyFailure.TRANSIENT)
    assertThat(KeyFailure.of(IllegalStateException("boh"))).isEqualTo(KeyFailure.TRANSIENT)
  }

  @Test
  fun unaCatenaCircolare_nonFaLoopare() {
    val a = RuntimeException("a")
    val b = RuntimeException("b", a)
    a.initCause(b)
    assertThat(KeyFailure.of(a)).isEqualTo(KeyFailure.TRANSIENT)
  }
}
