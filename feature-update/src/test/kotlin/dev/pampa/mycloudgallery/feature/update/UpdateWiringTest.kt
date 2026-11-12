package dev.pampa.mycloudgallery.feature.update

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Pacchetto U (critica di sicurezza, punto 7): il montaggio. Il client dell'engine (`EngineHttp`) segue i rimandi verso qualunque host; l'app lo usa
 * SOLO avvolto (`GuardedUpdateHttp`, attraverso `UpdateHttp.create`). Questa prova legge i sorgenti di `:feature-update` e di `:app` e cade se qualcuno:
 *  - costruisce un `EngineHttp(...)` fuori dalla sottoclasse che lo avvolge;
 *  - costruisce `AndroidAppUpdateInstaller` con un argomento solo (allora prende il suo `EngineHttp()` di serie, non avvolto);
 *  - costruisce `EngineAppUpdater` o `AndroidAppUpdateInstaller` fuori dalla fabbrica (`AppUpdates.create`), o la fabbrica non passa loro il client avvolto.
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
}
