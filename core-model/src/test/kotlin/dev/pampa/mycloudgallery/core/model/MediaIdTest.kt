package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaIdTest {

  @Test
  fun dueIdentitaConLoStessoValoreSonoUguali() {
    assertThat(MediaId("abc")).isEqualTo(MediaId("abc"))
    assertThat(MediaId("abc")).isNotEqualTo(MediaId("abd"))
  }

  @Test
  fun unaIdentitaVuotaOBiancaESrifiutata() {
    assertThrows(IllegalArgumentException::class.java) { MediaId("") }
    assertThrows(IllegalArgumentException::class.java) { MediaId("   ") }
  }
}
