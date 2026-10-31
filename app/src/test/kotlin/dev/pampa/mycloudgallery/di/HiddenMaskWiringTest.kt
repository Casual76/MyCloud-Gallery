package dev.pampa.mycloudgallery.di

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import java.io.File
import org.junit.Test

/**
 * R3 della revisione di sicurezza della Fase 9 (31/10): **il filtro finale dei Nascosti non puo' tornare muto in silenzio**. Prima le schermate dei ricordi e della mappa
 * chiedevano la maschera a Hilt dietro un `@BindsOptionalOf`, che compila anche senza nessun legame (e il filtro, senza maschera, non faceva niente: era lo stato di prima di
 * P13, e P13-4 diceva che "la compilazione di `:app` lo prova", cosa non vera). Ora `MemoriesDependencies` e `MapDependencies` la chiedono **senza `Optional`**: Hilt la deve
 * avere, e se il legame sparisce `:app` non compila. Queste prove guardano il **codice generato** da Dagger (i `*_Factory` delle due classi e quella del legame) e i sorgenti,
 * perche' nessuno dei due ritorni muti sia possibile senza che un test cada. (Non c'e' un avvio di Hilt nei test di `:app`: il grafo vero lo costruisce la compilazione.)
 */
class HiddenMaskWiringTest {

  private fun maskParameters(factory: String): Int {
    val type = Class.forName(factory)
    val newInstance = type.methods.single { it.name == "newInstance" }
    return newInstance.parameterTypes.count { it == HiddenMask::class.java }
  }

  /** Le due schermate della fase ricevono `HiddenMask` come tipo vero, mai dentro un `Optional`: il costruttore generato ha un parametro `HiddenMask`. */
  @Test
  fun leSchermateDellaFaseRicevonoLaMascheraSenzaOptional() {
    assertWithMessage("MemoriesDependencies").that(maskParameters("dev.pampa.mycloudgallery.feature.memories.nav.MemoriesDependencies_Factory")).isEqualTo(1)
    assertWithMessage("MapDependencies").that(maskParameters("dev.pampa.mycloudgallery.feature.map.nav.MapDependencies_Factory")).isEqualTo(1)
  }

  /** Il legame c'e' una volta sola, in un modulo della galleria, e non piu' in quello degli album. */
  @Test
  fun ilLegameStaNelModuloDellaGalleria() {
    assertThat(Class.forName("dev.pampa.mycloudgallery.di.GalleryModule_HiddenMaskFactory")).isNotNull()
    val moved = runCatching { Class.forName("dev.pampa.mycloudgallery.di.AlbumsModule_HiddenMaskFactory") }.isSuccess
    assertThat(moved).isFalse()
    val binds = File("src/main/kotlin/dev/pampa/mycloudgallery/di").listFiles { f -> f.extension == "kt" }.orEmpty()
      .filter { Regex("""fun\s+\w+\([^)]*\)\s*:\s*HiddenMask\b""").containsMatchIn(it.readText()) }
    assertThat(binds.map { it.name }).containsExactly("GalleryModule.kt")
  }

  /** Nei moduli della fase la maschera non e' piu' opzionale: ne' nei costruttori, ne' nei `@BindsOptionalOf`, e `FinalFilter` non ne ammette l'assenza. */
  @Test
  fun neiModuliDellaFaseLaMascheraNonEPiuOpzionale() {
    for (module in listOf("feature-memories", "feature-map")) {
      val root = File("../$module/src/main/kotlin")
      assertWithMessage("$module: cartella dei sorgenti").that(root.isDirectory).isTrue()
      val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
      assertThat(sources).isNotEmpty()
      for (file in sources) {
        val text = file.readText()
        assertWithMessage("${file.name}: Optional<HiddenMask>").that(text).doesNotContain("Optional<HiddenMask>")
        assertWithMessage("${file.name}: un legame opzionale della maschera").that(Regex("""@BindsOptionalOf\s+abstract\s+fun\s+\w+\(\)\s*:\s*HiddenMask""").containsMatchIn(text)).isFalse()
      }
      val filter = sources.single { it.name == "FinalFilter.kt" }.readText()
      assertWithMessage("$module: FinalFilter con la maschera non nulla").that(filter).contains("class FinalFilter(private val mask: HiddenMask)")
      assertThat(filter).doesNotContain("HiddenMask?")
    }
  }
}
