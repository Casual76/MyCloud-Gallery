package dev.pampa.mycloudgallery.core.trust

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import org.junit.Test

class TrustFormatTest {

  @Test
  fun laVersioneCorrenteSiLegge_quellaFuturaOSenzaSensoNo() {
    assertThat(TrustFormat.isReadable(TrustFormat.CURRENT_VERSION)).isTrue()
    assertThat(TrustFormat.isReadable(TrustFormat.CURRENT_VERSION + 1)).isFalse()
    assertThat(TrustFormat.isReadable(0)).isFalse()
    assertThat(TrustFormat.isReadable(-1)).isFalse()
  }

  /**
   * "Nessuna strada per cui un dato letto dal NAS provochi da solo una cancellazione o uno spostamento":
   * l'interfaccia del NAS vista da core-trust non ha operazioni distruttive. Se qualcuno ne aggiunge una,
   * questo test lo costringe a passare da docs/formato-nas.md §9.
   */
  @Test
  fun ilFileSystemDelNasNonHaOperazioniDistruttive() {
    val methods = NasFileSystem::class.java.declaredMethods.map { it.name }.toSet()

    assertThat(methods).containsExactly("getShareName", "list", "read", "publishExclusive")
  }

  /** Ora di riferimento = ora del NAS (E-P9): nel codice del modulo l'orologio del dispositivo non compare. */
  @Test
  fun ilCodiceNonLeggeLOrologioDelDispositivo() {
    val sources = java.io.File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    val forbidden = listOf("currentTimeMillis", "nanoTime", "Instant.now", "LocalDateTime.now", "Date()", "Clock.system", "elapsedRealtime")

    assertThat(sources).isNotEmpty()
    for (file in sources) {
      val text = file.readText()
      for (word in forbidden) {
        assertWithMessage("${file.name} usa $word").that(text.contains(word)).isFalse()
      }
    }
  }

  @Test
  fun leEccezioniInattesePerdonoLaFormaDiRifiuto() {
    val verdict = verdictOf<Int> { throw IllegalStateException("boom") }

    assertThat(verdict).isEqualTo(Verdict.Rejected(RejectReason.MALFORMED))
  }
}
