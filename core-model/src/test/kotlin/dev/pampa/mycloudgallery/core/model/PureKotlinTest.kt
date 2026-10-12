package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * `core-model` e' Kotlin puro: e' una libreria Android solo perche' il build della galleria e' fatto
 * cosi', ma il suo codice non deve vedere Android (README, "Moduli"). Le regole di dominio si provano
 * sulla JVM e un giorno potrebbero girare altrove (strumenti per il PC, test di massa).
 */
class PureKotlinTest {

  // Gradle esegue i test con la cartella del modulo come cartella di lavoro.
  private val moduleDir = File(".").absoluteFile

  @Test
  fun ilCodiceDelModelloNonImportaAndroid() {
    val sources = File(moduleDir, "src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
    assertThat(sources).isNotEmpty()

    val offenders = sources.filter { file ->
      file.readLines().any { line ->
        val l = line.trim()
        l.startsWith("import android.") || l.startsWith("import androidx.") ||
          l.startsWith("import com.google.android.") || l.startsWith("import kotlinx.coroutines.android")
      }
    }
    assertThat(offenders.map { it.name }).isEmpty()
  }

  @Test
  fun ilModuloNonDichiaraDipendenzeDiProduzione() {
    val build = File(moduleDir, "build.gradle").readText()
    val dependencies = build.substringAfter("dependencies {", missingDelimiterValue = "").substringBefore("}")
    val production = dependencies.lines().map { it.trim() }.filter {
      it.startsWith("implementation") || it.startsWith("api") || it.startsWith("compileOnly")
    }
    assertThat(production).isEmpty()
  }
}
