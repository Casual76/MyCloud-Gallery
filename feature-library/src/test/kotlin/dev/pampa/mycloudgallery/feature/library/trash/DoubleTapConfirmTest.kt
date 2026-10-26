package dev.pampa.mycloudgallery.feature.library.trash

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * "Elimina subito" a doppio tocco (Fase 7, P8, D26, Q8): la macchina dei tocchi (la stessa regola di `ConfirmationSheet` della 4B, R6) e la guardia
 * sul codice: la prova dei tocchi la costruisce solo questo modulo, e questo modulo non nomina `core-nasops` ne' le sue conferme.
 */
class DoubleTapConfirmTest {

  @Test
  fun servonoDueToccheDistinti() {
    val confirm = DoubleTapConfirm(shownAtMonotonicMs = 10_000)

    assertThat(confirm.tap(11_000)).isFalse()
    assertThat(confirm.taps).isEqualTo(1)
    assertThat(confirm.proof()).isNull()

    assertThat(confirm.tap(12_000)).isTrue()
    assertThat(confirm.taps).isEqualTo(2)
    assertThat(confirm.proof()!!.taps).isEqualTo(2)
  }

  @Test
  fun unDoppioTocco_CheStaSotto600ms_NonConta() {
    val confirm = DoubleTapConfirm(shownAtMonotonicMs = 10_000)

    // Il primo tocco troppo vicino alla comparsa del foglio (il secondo colpo di un doppio tocco sul pulsante che l'ha aperto): non conta.
    assertThat(confirm.tap(10_200)).isFalse()
    assertThat(confirm.taps).isEqualTo(0)

    assertThat(confirm.tap(10_700)).isFalse()
    assertThat(confirm.taps).isEqualTo(1)
    // Un secondo tocco registrato due volte a 300 ms dal primo: ignorato.
    assertThat(confirm.tap(11_000)).isFalse()
    assertThat(confirm.taps).isEqualTo(1)
    assertThat(confirm.proof()).isNull()
    // Un secondo gesto voluto, dopo aver letto il pannello nuovo: conta.
    assertThat(confirm.tap(11_400)).isTrue()
    assertThat(confirm.proof()).isNotNull()
  }

  @Test
  fun ilLimiteEEsattamenteSeicentoMillisecondi() {
    val a = DoubleTapConfirm(shownAtMonotonicMs = 0)
    assertThat(a.tap(DoubleTapConfirm.MIN_GAP_MS - 1)).isFalse()
    assertThat(a.taps).isEqualTo(0)
    assertThat(a.tap(DoubleTapConfirm.MIN_GAP_MS)).isFalse()
    assertThat(a.taps).isEqualTo(1)
  }

  @Test
  fun rinunciareRicominciaDaCapo() {
    val confirm = DoubleTapConfirm(shownAtMonotonicMs = 0)
    confirm.tap(1_000)
    confirm.reset(nowMonotonicMs = 5_000)

    assertThat(confirm.taps).isEqualTo(0)
    assertThat(confirm.proof()).isNull()
    // Dopo il reset il tocco troppo vicino non conta di nuovo.
    assertThat(confirm.tap(5_100)).isFalse()
    assertThat(confirm.taps).isEqualTo(0)
  }

  // --- La guardia sul codice -------------------------------------------------------------------------------------------------

  private val main: List<File> by lazy {
    File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  private fun stripped(file: File): String =
    file.readLines().joinToString("\n") { line ->
      val trimmed = line.trimStart()
      if (trimmed.startsWith("*") || trimmed.startsWith("/**") || trimmed.startsWith("//")) "" else line.substringBefore(" // ")
    }

  @Test
  fun laProvaDeiToccheLaCostruisceSoloIlFileDellaPorta() {
    assertThat(main.map { it.name }).contains("NasTrashPort.kt")
    val found = main.filter { it.name != "NasTrashPort.kt" && Regex("""\bUserTap\s*\(""").containsMatchIn(stripped(it)) }.map { it.name }
    assertThat(found).isEmpty()
  }

  @Test
  fun ilCostruttoreDellaProvaEInternal() {
    val port = stripped(main.single { it.name == "NasTrashPort.kt" })
    assertThat(Regex("""class\s+UserTap\s+internal\s+constructor""").containsMatchIn(port)).isTrue()
  }

  @Test
  fun questoModuloNonNominaLeOperazioniDelNas() {
    val forbidden = Regex("""core\.nasops|ConfirmationIssuer|ConfirmationSheet|UserConfirmation|AcceptedPlan|NasOperations|NasFileOps""")
    val found = main.filter { forbidden.containsMatchIn(stripped(it)) }.map { it.name }
    assertThat(found).isEmpty()
  }

  @Test
  fun inSettingsGradleFeatureLibraryNonDipendeDaCoreNasops() {
    val build = File("build.gradle").readText()
    assertThat(Regex("""project\(\s*['"]:core-nasops['"]\s*\)""").containsMatchIn(build)).isFalse()
  }
}
