package dev.pampa.mycloudgallery.feature.library.trash

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.feature.library.utility.DuplicatesController
import java.io.File
import org.junit.Test

/**
 * R18 della revisione di Fase 7: **la prova del tocco non si fabbrica.** Il test di guardia che `NasTrashPort.kt` prometteva e non esisteva.
 *
 * Una `UserTap` e' cio' che una porta distruttiva (cestino del NAS, "Elimina subito", ripristino) chiede per fidarsi che l'utente abbia toccato. Prima la
 * fabbricavano il controllore dei Doppioni dentro `confirm()` e dei falsi pubblici nel set `main` (`fakeSingleTap`, `fakeDoubleTap`): una chiamata qualunque
 * in `:app` metteva in moto un `Trash` o un `PurgeNow` senza un tocco. Ora la prova nasce solo in un composable di questo modulo, nel gesto del pulsante, e il
 * foglio di conferma aspetta un tempo minimo dalla sua comparsa ([SheetTap]). Il controllo guarda i sorgenti, come i test d'architettura del progetto.
 */
class TrashTapGuardTest {

  private val main: List<File> by lazy {
    File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  private fun stripped(file: File): String =
    file.readLines().joinToString("\n") { line ->
      val trimmed = line.trimStart()
      if (trimmed.startsWith("*") || trimmed.startsWith("/**") || trimmed.startsWith("//")) "" else line.substringBefore(" // ")
    }

  private val minters = Regex("""\b(UserTap\s*\(|tapOnButton\s*\(|SheetTap\s*\(|DoubleTapConfirm\s*\()""")

  @Test
  fun nessunFalsoPubblicoDiProveNelSetMain() {
    assertThat(main).isNotEmpty()
    val found = main.filter { Regex("""\b(fakeSingleTap|fakeDoubleTap|singleTap)\b""").containsMatchIn(stripped(it)) }.map { it.name }
    assertWithMessage("Prove del tocco a disposizione di chiunque: $found").that(found).isEmpty()
  }

  @Test
  fun iControlloriNonSiFabbricanoLaProva() {
    val controllers = main.filter { it.name.endsWith("Controller.kt") }
    assertThat(controllers.map { it.name }).containsAtLeast("DuplicatesController.kt", "RecentlyDeletedController.kt")
    val found = controllers.filter { minters.containsMatchIn(stripped(it)) }.map { it.name }
    assertWithMessage("Controllori che fabbricano la prova del tocco: $found").that(found).isEmpty()
  }

  @Test
  fun laProvaNasceSoloInUnComposable() {
    val users = main.filter { it.name != "NasTrashPort.kt" && minters.containsMatchIn(stripped(it)) }
    assertThat(users).isNotEmpty()
    for (file in users) {
      assertWithMessage("${file.name} fabbrica una prova del tocco ma non disegna niente: la prova nasce nel gesto di un pulsante")
        .that(stripped(file)).contains("@Composable")
    }
  }

  @Test
  fun leClassiCheFabbricanoLaProvaSonoInternal() {
    val port = stripped(main.single { it.name == "NasTrashPort.kt" })
    assertThat(Regex("""class\s+UserTap\s+internal\s+constructor""").containsMatchIn(port)).isTrue()
    assertThat(Regex("""internal\s+class\s+DoubleTapConfirm""").containsMatchIn(port)).isTrue()
    assertThat(Regex("""internal\s+class\s+SheetTap""").containsMatchIn(port)).isTrue()
    assertThat(Regex("""internal\s+fun\s+proof\s*\(""").containsMatchIn(port)).isTrue()
    assertThat(Regex("""internal\s+fun\s+tapOnButton\s*\(""").containsMatchIn(port)).isTrue()
  }

  @Test
  fun leAzioniDistruttiveVoglionoLaProvaDaFuori() {
    fun takesTap(owner: Class<*>, name: String) =
      owner.methods.filter { it.name == name }.all { method -> method.parameterTypes.any { it == UserTap::class.java } }
    assertThat(takesTap(DuplicatesController::class.java, "confirm")).isTrue()
    assertThat(takesTap(RecentlyDeletedController::class.java, "restoreNas")).isTrue()
    assertThat(takesTap(RecentlyDeletedController::class.java, "purgeNas")).isTrue()
  }

  @Test
  fun ilFoglioDiConfermaAspettaUnTempoMinimoDallaComparsa() {
    val sheet = SheetTap(shownAtMonotonicMs = 10_000)

    // Un tocco arrivato prima di 600 ms (un doppio tocco sul pulsante di prima, un tocco registrato due volte) non e' una scelta.
    assertThat(sheet.confirm(10_000)).isNull()
    assertThat(sheet.confirm(10_000 + SheetTap.MIN_SHOWN_MS - 1)).isNull()
    assertThat(sheet.confirm(10_000 + SheetTap.MIN_SHOWN_MS)!!.taps).isEqualTo(1)
    // Un orologio che e' andato indietro non da' la prova.
    assertThat(sheet.confirm(5_000)).isNull()
  }
}
