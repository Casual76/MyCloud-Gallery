package dev.pampa.mycloudgallery

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.testing.NoNetworkSources
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * Revisione di sicurezza della Fase 3, I7 (D24): nessuna rete nei file della **galleria del telefono** dentro `:app`. L'app
 * ha il permesso `INTERNET` (parla con il NAS, Fase 2: `graph/`, `nas/`, `work/Trust*`), quindi il manifest non puo'
 * vietare niente; qui si controllano i sorgenti che sono della galleria: le cartelle `gallery` e `main` (la Libreria nell'app),
 * i lavori `work/GalleryWork.kt` e il montaggio `di/GalleryModule.kt` e `di/MeasurementHooks.kt`, piu' la variante di
 * misura (`src/benchmarkRelease`, che usa solo il MediaStore e il database locale).
 */
class NoNetworkSourcesTest {

  private val base = "src/main/kotlin/dev/pampa/mycloudgallery"

  @Test
  fun iFileDellaGalleriaNonUsanoLaRete() {
    assertThat(NoNetworkSources.violations(File("$base/gallery"))).isEmpty()
    assertThat(NoNetworkSources.violations(File("$base/main"))).isEmpty()
    assertThat(NoNetworkSources.violations(File("src/benchmarkRelease/kotlin"))).isEmpty()
  }

  @Test
  fun neiFileSingoliDellaGalleriaNonCeRete() {
    val files = listOf("$base/work/GalleryWork.kt", "$base/di/GalleryModule.kt", "$base/di/MeasurementHooks.kt")
    val copy = Files.createTempDirectory("galleria-senza-rete").toFile()
    try {
      files.forEach { path ->
        val source = File(path)
        assertThat(source.isFile).isTrue()
        source.copyTo(File(copy, source.name))
      }
      assertThat(NoNetworkSources.violations(copy)).isEmpty()
    } finally {
      copy.deleteRecursively()
    }
  }

  @Test
  fun laBarrieraVedeLaReteNeiFileDellaGalleria() {
    val dir = Files.createTempDirectory("galleria-con-rete").toFile()
    try {
      File(dir, "Cattivo.kt").writeText("package x\n\nimport java.net.URL\n\nclass Cattivo\n")
      assertThat(NoNetworkSources.violations(dir)).hasSize(1)
    } finally {
      dir.deleteRecursively()
    }
  }
}
