package dev.pampa.mycloudgallery.core.ai

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Fase 6 (P0, D2; docs/decisioni.md "Fase 6 P0: kotlin-reflect e coroutines"): litertlm-android 0.18.1 porterebbe kotlin-reflect
 * 2.4.0 (e con lui kotlin-stdlib 2.4.0 per tutta l'app, che il compilatore 2.2.20 non legge) e coroutines 1.11.0. La scelta di P0:
 * kotlin-reflect TOLTO, coroutines FERMATE alla versione dell'app. Questo test di non regressione guarda il classpath vero dei test di
 * core-ai (le stesse dipendenze, esclusioni comprese, dell'APK): se un aggiornamento le rimette, fallisce.
 */
class TransitiveVersionsTest {

  @Test
  fun kotlinStdlibEQuellaDelCompilatoreDellEngine() {
    assertThat(KotlinVersion.CURRENT.toString()).isEqualTo("2.2.20")
  }

  @Test
  fun kotlinReflectNonCe() {
    val reflect = runCatching { Class.forName("kotlin.reflect.full.KClasses") }
    assertThat(reflect.isFailure).isTrue()
    assertThat(reflect.exceptionOrNull()).isInstanceOf(ClassNotFoundException::class.java)
  }

  @Test
  fun leCoroutinesSonoQuelleDellApp() {
    val version = javaClass.classLoader!!.getResourceAsStream("META-INF/kotlinx_coroutines_core.version")?.use { it.readBytes().decodeToString().trim() }
    assertThat(version).isEqualTo("1.10.2")
  }

  /**
   * Le librerie ci sono davvero sul classpath (altrimenti i controlli sopra non direbbero niente). Si cercano i file delle classi senza
   * caricarle: litertlm-android 0.18.1 e' compilata in bytecode Java 21 (versione 65) e la JVM 17 dei test non la carica (sul
   * dispositivo la converte D8: lo prova il test strumentato). Per questo i motori veri si provano solo strumentati (P4).
   */
  @Test
  fun leLibrerieDeiMotoriSonoSulClasspath() {
    val loader = javaClass.classLoader!!
    for (name in listOf("com.google.ai.edge.litertlm.EmbeddingEngine", "com.google.ai.edge.litert.CompiledModel", "org.tensorflow.lite.Interpreter")) {
      val resource = loader.getResource(name.replace('.', '/') + ".class")
      assertWithMessage("$name non e' sul classpath dei test").that(resource).isNotNull()
    }
    val litertlm = loader.getResourceAsStream("com/google/ai/edge/litertlm/EmbeddingEngine.class")!!.use { it.readBytes() }
    // byte 6-7 = versione principale del formato delle classi: 65 = Java 21.
    assertWithMessage("versione del bytecode di litertlm-android").that(((litertlm[6].toInt() and 0xff) shl 8) or (litertlm[7].toInt() and 0xff)).isAtLeast(61)
  }
}
