package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaIdTest {

  @Test
  fun dueIdentitaConLoStessoValoreSonoUguali() {
    val raw = "3f2b6c1e-8d4a-4f7b-9c2e-1a2b3c4d5e6f"
    assertThat(MediaId(raw)).isEqualTo(MediaId(raw))
    assertThat(MediaId(raw)).isNotEqualTo(MediaId("3f2b6c1e-8d4a-4f7b-9c2e-1a2b3c4d5e70"))
  }

  @Test
  fun unaIdentitaNonCanonicaERifiutata() {
    listOf("", "   ", "abc", "3F2B6C1E-8D4A-4F7B-9C2E-1A2B3C4D5E6F", "3f2b6c1e8d4a4f7b9c2e1a2b3c4d5e6f").forEach {
      assertThrows(IllegalArgumentException::class.java) { MediaId(it) }
    }
  }

  @Test
  fun dalTestoEsternoSiOttieneLaFormaCanonica() {
    assertThat(MediaId.parse(" 3F2B6C1E-8D4A-4F7B-9C2E-1A2B3C4D5E6F ").value).isEqualTo("3f2b6c1e-8d4a-4f7b-9c2e-1a2b3c4d5e6f")
  }

  @Test
  fun leIdentitaNuoveSonoUnicheEOrdinabili() {
    val ids = List(1_000) { MediaId.random() }
    assertThat(ids.toSet()).hasSize(1_000)
    assertThat(ids.sorted().first()).isEqualTo(ids.minOf { it })
  }

  @Test
  fun gliAccountSonoNormalizzati() {
    assertThat(AccountId.of(" Alessio ")).isEqualTo(AccountId("alessio"))
    assertThrows(IllegalArgumentException::class.java) { AccountId.of("../admin") }
    assertThrows(IllegalArgumentException::class.java) { AccountId.of("") }
  }
}
