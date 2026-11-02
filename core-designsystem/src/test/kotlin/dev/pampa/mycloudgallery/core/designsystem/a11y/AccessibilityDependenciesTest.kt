package dev.pampa.mycloudgallery.core.designsystem.a11y

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Fase 10, P8 (D28, D40, Q9 (a)): l'Accessibility Test Framework e `roborazzi-accessibility-check` sono **solo per i test**. Nessun `build.gradle`
 * li dichiara se non come `testImplementation` (mai `implementation`, `api`, `compileOnly`, `androidTestImplementation`): cosi' non arrivano mai
 * al dex dell'APK. La versione e' in `versions-app.gradle` (quella di Roborazzi, 1.76.0).
 */
class AccessibilityDependenciesTest {

  private val root: File = File("..").canonicalFile

  private val buildFiles: List<File> by lazy {
    root.listFiles { f -> f.isDirectory && (f.name.startsWith("feature-") || f.name.startsWith("core-") || f.name == "app" || f.name == "benchmark") }
      .orEmpty().map { File(it, "build.gradle") }.filter { it.isFile }
  }

  @Test
  fun lAccessoriaDiProvaStaSoloNeiTest() {
    val offenders = buildFiles.flatMap { file ->
      file.readLines().mapIndexedNotNull { i, line ->
        val code = line.substringBefore("//").trim()
        val mentions = code.contains("roborazziAccessibilityCheck") || code.contains("accessibility-test-framework") || code.contains("roborazzi-accessibility-check")
        if (mentions && !code.startsWith("testImplementation")) "${file.parentFile.name}:${i + 1}: $code" else null
      }
    }
    assertWithMessage("ATF fuori da testImplementation:\n" + offenders.joinToString("\n")).that(offenders).isEmpty()
  }

  @Test
  fun laVersioneEFissataInUnPostoSolo() {
    val versions = File(root, "versions-app.gradle").readText()
    assertWithMessage("manca roborazziAccessibilityCheck in versions-app.gradle").that(versions).contains("roborazziAccessibilityCheck")
    assertWithMessage("versione di roborazzi-accessibility-check diversa da quella di Roborazzi").that(versions)
      .contains("roborazzi-accessibility-check:\${appVersions.roborazzi}")
    val others = buildFiles.filter { it.readText().contains("roborazzi-accessibility-check:") }.map { it.parentFile.name }
    assertWithMessage("coordinate scritte a mano fuori da versions-app.gradle: $others").that(others).isEmpty()
  }

  @Test
  fun ilDexDiReleaseNonVedeLAccessoria() {
    // `A11yAssertions` e' in un package `testing` (solo `androidx.compose.ui`, nessuna libreria di prova): il passo 8 di check.ps1 cerca i package
    // `testing` nel dex di release. Qui si controlla che non usi niente di ATF o di Roborazzi.
    val source = File(root, "core-designsystem/src/main/kotlin/dev/pampa/mycloudgallery/core/designsystem/testing/A11yAssertions.kt").readText()
    assertWithMessage("A11yAssertions nomina ATF o Roborazzi").that(source).doesNotContain("com.google.android.apps.common.testing")
    assertWithMessage("A11yAssertions nomina Roborazzi").that(source).doesNotContain("roborazzi")
  }
}
