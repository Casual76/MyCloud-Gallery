package dev.pampa.mycloudgallery.core.common.testing

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertThrows
import org.junit.Test

/** Il controllo "nessuna rete" (D24) deve vedere davvero cio' che vieta, e ignorare i commenti. */
class NoNetworkSourcesTest {

  private fun withSource(name: String, content: String, block: (File) -> Unit) {
    val root = Files.createTempDirectory("nonet").toFile()
    try {
      File(root, name).apply { parentFile?.mkdirs() }.writeText(content)
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun trovaOgniNomeVietatoConLaRigaGiusta() {
    val source = """
      package x

      import java.net.URL
      import androidx.media3.datasource.DefaultDataSource
      import okhttp3.OkHttpClient

      class A
    """.trimIndent()
    withSource("a/A.kt", source) { root ->
      val violations = NoNetworkSources.violations(root)
      assertThat(violations).hasSize(3)
      assertThat(violations[0]).startsWith("a/A.kt:3:")
      assertThat(violations[1]).contains("DefaultDataSource")
      assertThat(violations[2]).contains("OkHttp")
    }
  }

  @Test
  fun icommentiNonContanoMaLeStringheSi() {
    val source = """
      /** Mai java.net, mai DefaultDataSource: sorgenti solo locali. */
      class B {
        // okhttp3 e' vietato
        /* DefaultHttpDataSource /* annidato */ ancora commento */
        val url = "https://example.org/licenza"
        val bad = "java.net.Socket"
      }
    """.trimIndent()
    withSource("B.kt", source) { root ->
      val violations = NoNetworkSources.violations(root)
      // "https://" dentro una stringa non e' un commento (non fa sparire il resto della riga) e non e' vietato;
      // la stringa con java.net si.
      assertThat(violations).hasSize(1)
      assertThat(violations.single()).startsWith("B.kt:6:")
    }
  }

  @Test
  fun unaCartellaCheNonEsisteEUnErroreENonUnViaLibera() {
    assertThrows(IllegalArgumentException::class.java) {
      NoNetworkSources.violations(File("questa/cartella/non/esiste"))
    }
  }

  /** Fase 6 (D33): l'eccezione vale solo per i file sotto il pacchetto ammesso, mai per i vicini ne' per un nome che gli somiglia. */
  @Test
  fun ilPacchettoAmmessoSiSaltaIlRestoNo() {
    val root = Files.createTempDirectory("nonet").toFile()
    try {
      File(root, "kotlin/x/download/https/Fetcher.kt").apply { parentFile?.mkdirs() }.writeText("import javax.net.ssl.HttpsURLConnection\n")
      File(root, "kotlin/x/download/Other.kt").apply { parentFile?.mkdirs() }.writeText("import java.net.URL\n")
      File(root, "kotlin/x/download/https2/Near.kt").apply { parentFile?.mkdirs() }.writeText("import java.net.URL\n")
      val violations = NoNetworkSources.violationsExcept(root, allowedPackages = listOf("kotlin/x/download/https/"))
      assertThat(violations).hasSize(2)
      assertThat(violations.joinToString()).doesNotContain("Fetcher.kt")
      // Senza eccezione anche il pacchetto HTTPS conta (due nomi vietati sulla stessa riga: javax.net e HttpsURLConnection).
      assertThat(NoNetworkSources.violations(root)).hasSize(4)
      assertThrows(IllegalArgumentException::class.java) { NoNetworkSources.violationsExcept(root, listOf("kotlin/x/download/https")) }
      assertThrows(IllegalArgumentException::class.java) { NoNetworkSources.violationsExcept(root, listOf("../x/")) }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun inumeriDiRigaRestanoQuelliDelFileDopoIcommentiDiPiuRighe() {
    val source = "/*\n  uno\n  due\n*/\nimport java.net.URI\n"
    withSource("C.kt", source) { root ->
      assertThat(NoNetworkSources.violations(root).single()).startsWith("C.kt:5:")
    }
  }
}
