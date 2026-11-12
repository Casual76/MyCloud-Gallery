package dev.pampa.mycloudgallery.feature.update

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Pacchetto U (critica di sicurezza, punto 7): il montaggio. Il client dell'engine (`EngineHttp`) segue i rimandi verso qualunque host; l'app lo usa
 * SOLO avvolto (`GuardedUpdateHttp`, attraverso `UpdateHttp.create`). Questa prova legge i sorgenti di `:feature-update` e di `:app` e cade se qualcuno:
 *  - costruisce un `EngineHttp(...)` fuori dalla sottoclasse che lo avvolge;
 *  - costruisce `AndroidAppUpdateInstaller` con un argomento solo (allora prende il suo `EngineHttp()` di serie, non avvolto);
 *  - costruisce `EngineAppUpdater` o `AndroidAppUpdateInstaller` fuori dalla fabbrica (`AppUpdates.create`), o la fabbrica non passa loro il client avvolto;
 *  - (giro 2) separa il cancello dell'installatore da quello che il ciclo di vita dell'activity apre e chiude.
 */
class UpdateWiringTest {

  private val roots = listOf(File("src/main/kotlin"), File("../app/src/main/kotlin"))

  private val sources: List<File> = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

  /** Le righe di codice (senza commenti) che contengono [pattern], con il file. */
  private fun uses(pattern: Regex): List<String> = sources.flatMap { file ->
    file.readLines().mapIndexedNotNull { index, line ->
      val code = line.substringBefore("//")
      val trimmed = code.trimStart()
      if (!trimmed.startsWith("*") && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
    }
  }

  @Test
  fun ilClientDellEngineSiUsaSoloAvvolto() {
    assertWithMessage("sorgenti di feature-update e di app").that(sources.map { it.name }).containsAtLeast("AppUpdates.kt", "UpdateModule.kt")
    assertWithMessage("EngineHttp costruito fuori da GuardedUpdateHttp")
      .that(uses(Regex("""\bEngineHttp\s*\(""")).filterNot { it.startsWith("GuardedUpdateHttp.kt:") })
      .isEmpty()
    assertWithMessage("AndroidAppUpdateInstaller con il solo contesto (prenderebbe l'EngineHttp di serie)")
      .that(uses(Regex("""\bAndroidAppUpdateInstaller\s*\(\s*[^,()]+\s*\)""")))
      .isEmpty()
    assertWithMessage("installatore e controllore dell'engine costruiti fuori da AppUpdates.create")
      .that(uses(Regex("""\b(AndroidAppUpdateInstaller|EngineAppUpdater)\s*\(""")).filterNot { it.startsWith("AppUpdates.kt:") })
      .isEmpty()
    val factory = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/update/AppUpdates.kt").readText()
    assertWithMessage("la fabbrica crea il client avvolto").that(factory).contains("val http = UpdateHttp.create(versionName)")
    assertWithMessage("e lo passa allo scaricamento").that(factory).contains("AndroidAppUpdateInstaller(app, http)")
    assertWithMessage("e al controllo").that(factory).contains("EngineAppUpdater(http, ")
  }

  @Test
  fun ilCancelloEUnoSolo_lInstallatoreLoGuarda_ilCicloDiVitaDellActivityLoMuove() {
    // Giro 2, US2-1: lo stesso ForegroundGate all'installatore (che lo guarda prima di installFile) e ad AppUpdates (che lo apre e chiude).
    val factory = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/update/AppUpdates.kt").readText()
    assertWithMessage("un cancello nella fabbrica").that(factory).contains("val gate = ForegroundGate()")
    assertWithMessage("guardato dall'installatore").that(factory).contains("inFront = gate::inFront")
    assertWithMessage("mosso da AppUpdates").that(factory).contains("gate = gate,")
    assertWithMessage("l'installatore protetto costruito fuori da AppUpdates.create")
      .that(uses(Regex("""\bGuardedUpdateInstaller\s*\(""")).filterNot { it.startsWith("AppUpdates.kt:") || it.startsWith("GuardedUpdateInstaller.kt:") })
      .isEmpty()
    // Il ciclo di vita dell'activity, non l'importanza del processo.
    val module = File("../app/src/main/kotlin/dev/pampa/mycloudgallery/di/UpdateModule.kt").readText()
    assertWithMessage("l'importanza del processo nel modulo dell'aggiornamento").that(module).doesNotContain("getMyMemoryState")
    val effect = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/update/ui/UpdatePrompt.kt").readText()
    assertWithMessage("ON_PAUSE fa uscire dal cancello il proprietario di questa activity").that(effect).contains("onPauseOrDispose { viewModel.onPaused(owner) }")
    assertWithMessage("ON_RESUME fa entrare lo stesso proprietario").that(effect).contains("viewModel.onResumed(owner)")
    val root = File("../app/src/main/kotlin/dev/pampa/mycloudgallery/AppRoot.kt").readText()
    assertWithMessage("l'effetto alla radice dell'activity").that(root).contains("UpdateLifecycleEffect(updatesViewModel)")
  }
}
