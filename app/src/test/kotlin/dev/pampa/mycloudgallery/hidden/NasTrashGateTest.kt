package dev.pampa.mycloudgallery.hidden

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * ED-1 e R4 della revisione di Fase 7: **il Cestino di Cartelle NAS non e' una seconda porta del cestino.** Nome e cartella di quello che e' stato
 * eliminato possono essere di un media nascosto (il cestino ha anche i nomi), quindi la pagina sta dietro il cancello dei Nascosti: la credenziale di
 * sistema a ogni ingresso. La vecchia pagina di `feature-nas` li elencava senza cancello e senza distinguere i nascosti.
 *
 * Il controllo guarda i sorgenti (come i test d'architettura del progetto): ogni ingresso di `NasScreen.TRASH` passa da `GatedNasTrashPage`, cioe' da
 * `RecentlyDeletedRoute`, che sta dietro `HiddenAccessGate` (provato da `RecentlyDeletedControllerTest` e `RecentlyDeletedNasTest`: chiusa non legge
 * niente, ne' del telefono ne' del NAS). Una rotta che richiamasse la vecchia pagina, o una chiamata a `NasScreenContent` senza la pagina
 * protetta, fa fallire questo test.
 */
class NasTrashGateTest {

  private val hub = "feature-nas/src/main/kotlin/dev/pampa/mycloudgallery/feature/nas/hub/NasHub.kt"
  private val oldPage = "feature-nas/src/main/kotlin/dev/pampa/mycloudgallery/feature/nas/trash/NasTrashScreen.kt"

  private fun code(path: String): String = RepoFiles.code(File(RepoFiles.root, path))

  @Test
  fun laRottaDelCestinoNonChiamaLaVecchiaPaginaSenzaCancello() {
    val hubCode = code(hub)
    assertThat(hubCode).doesNotContain("NasTrashRoute")
    assertThat(hubCode).doesNotContain("NasTrashViewModel")
    // Il Cestino e' la pagina che da' `:app` (dietro il cancello); senza, una pagina che non elenca niente.
    assertThat(Regex("""NasScreen\.TRASH\s*->\s*if\s*\(\s*trashPage\s*!=\s*null\s*\)""").containsMatchIn(hubCode)).isTrue()
    assertThat(hubCode).contains("NasTrashElsewhere(")
  }

  @Test
  fun nessunFileDiProduzioneRichiamaLaVecchiaPagina() {
    val callers = RepoFiles.production().map { RepoFiles.rel(it) }
      .filter { it != oldPage && it != "feature-nas/src/main/kotlin/dev/pampa/mycloudgallery/feature/nas/trash/NasTrashViewModel.kt" }
      .filter { path -> Regex("""\b(NasTrashRoute|NasTrashScreen|NasTrashViewModel)\b""").containsMatchIn(code(path)) }
      .toList()
    assertWithMessage("file che richiamano la pagina del Cestino senza cancello: $callers").that(callers).isEmpty()
  }

  @Test
  fun laVecchiaPaginaNonSiPuRichiamareDaFuoriDalModulo() {
    // `internal`: `:app` non puo' nemmeno nominarla.
    assertThat(Regex("""internal\s+fun\s+NasTrashRoute\s*\(""").containsMatchIn(code(oldPage))).isTrue()
    assertThat(Regex("""(?<!internal )fun\s+NasTrashRoute\s*\(""").containsMatchIn(code(oldPage))).isFalse()
  }

  @Test
  fun ogniIngressoDiCartelleNasPassaDallaPaginaProtetta() {
    val users = RepoFiles.production().map { RepoFiles.rel(it) }
      .filter { it.startsWith("app/") }
      .filter { path -> Regex("""\b(NasScreenContent|nasGraph)\s*\(""").containsMatchIn(code(path)) }
      .toList()
    assertThat(users).isNotEmpty()
    for (path in users) {
      val calls = Regex("""\b(NasScreenContent|nasGraph)\s*\([^)]*\)""").findAll(code(path)).map { it.value }.toList()
      // `fun NavGraphBuilder.nasGraph(` e simili: solo le chiamate (hanno `screen =` o `nav =`).
      val real = calls.filter { "nav = " in it || "screen = " in it }
      for (call in real) assertWithMessage("$path: una chiamata a Cartelle NAS senza la pagina protetta: $call").that(call).contains("trashPage = GatedNasTrashPage")
    }
  }

  @Test
  fun laPaginaProtettaSonoGliEliminatiDiRecenteDietroIlCancello() {
    val gated = code("app/src/main/kotlin/dev/pampa/mycloudgallery/navigation/NasNavigation.kt")
    assertThat(Regex("""val\s+GatedNasTrashPage\s*:\s*NasTrashPage\s*=\s*\{[^}]*RecentlyDeletedRoute\(""").containsMatchIn(gated)).isTrue()
    // E gli Eliminati di recente leggono solo a cancello aperto: il controllore e' costruito sul cancello e rifiuta di leggere da chiuso.
    val controller = code("feature-library/src/main/kotlin/dev/pampa/mycloudgallery/feature/library/trash/RecentlyDeletedController.kt")
    assertThat(controller).contains("private val gate: HiddenAccessGate")
    assertThat(controller).contains("!gate.isUnlocked")
  }
}
