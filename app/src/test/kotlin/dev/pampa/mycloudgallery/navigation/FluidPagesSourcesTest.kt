package dev.pampa.mycloudgallery.navigation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Adozione del Fluid Engine 3.0: le pagine sono dell'engine (`FluidNavHost`). Una destinazione di `androidx.navigation.compose.composable` in un grafo di
 * `FluidNavHost` **non si vedrebbe** (in debug l'engine ferma l'app, in release scrive solo un log), e un `NavHost` di navigation-compose non saprebbe
 * disegnare le pagine Fluid dei grafi condivisi (`settingsPages`, `diagnosticsGraph`). Questa prova guarda i sorgenti di tutti i moduli: nessuno dei due
 * import nel codice dell'app (main e debug). E le pagine con gesti orizzontali propri chiedono il back col dito dal bordo o mai.
 */
class FluidPagesSourcesTest {

  private val modules: List<File> = File("..").listFiles { file -> file.isDirectory && (file.name == "app" || file.name.startsWith("feature-") || file.name.startsWith("core-")) }
    .orEmpty().sortedBy { it.name }

  private fun sources(): List<File> = modules.flatMap { module ->
    listOf("src/main/kotlin", "src/debug/kotlin").map { File(module, it) }.filter { it.isDirectory }
      .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
  }

  @Test
  fun nessunaDestinazioneNeHostDiNavigationCompose() {
    val all = sources()
    assertThat(all.size).isGreaterThan(500)
    val forbidden = listOf("import androidx.navigation.compose.composable", "import androidx.navigation.compose.NavHost")
    val found = all.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line -> if (forbidden.any { line.trim() == it }) "${file.path}:${index + 1}" else null }
    }
    assertWithMessage("usare FluidNavHost e dev.antigravity.fluidengine.ui.navigation.composable").that(found).isEmpty()
  }

  @Test
  fun lePagineConGestiPropriChiedonoIlBackDalBordoOMai() {
    fun text(path: String): String = File("../$path").readText()
    // La presentazione dei ricordi: di lato si cambia foto, mai il back col dito (il tasto e il back di sistema restano).
    assertThat(text("feature-memories/src/main/kotlin/dev/pampa/mycloudgallery/feature/memories/nav/MemoriesNavigation.kt"))
      .contains("composable<MemoryPlayerDestination>(backSwipe = FluidBackSwipe.Off)")
    // La mappa: il dito sposta la carta.
    assertThat(text("feature-map/src/main/kotlin/dev/pampa/mycloudgallery/feature/map/nav/MapNavigation.kt"))
      .contains("composable<MapDestination>(backSwipe = FluidBackSwipe.EdgeOnly)")
    // Le griglie con la selezione a trascinamento.
    val collections = text("feature-library/src/main/kotlin/dev/pampa/mycloudgallery/feature/library/collections/CollectionsNavigation.kt")
    for (route in listOf("CollectionGridDestination", "HiddenDestination", "RecentlyDeletedDestination")) {
      assertThat(collections).contains("composable<$route>(backSwipe = FluidBackSwipe.EdgeOnly)")
    }
    assertThat(text("feature-library/src/main/kotlin/dev/pampa/mycloudgallery/feature/library/utility/UtilityNavigation.kt"))
      .contains("composable<UtilityFilterDestination>(backSwipe = FluidBackSwipe.EdgeOnly)")
    assertThat(text("app/src/main/kotlin/dev/pampa/mycloudgallery/navigation/AlbumsNavigation.kt"))
      .contains("composable<MediaPickerDestination>(backSwipe = FluidBackSwipe.EdgeOnly)")
    assertThat(text("feature-nas/src/main/kotlin/dev/pampa/mycloudgallery/feature/nas/hub/NasHub.kt"))
      .contains("backSwipe = if (screen == NasScreen.FOLDERS || screen == NasScreen.TRASH) FluidBackSwipe.EdgeOnly else FluidBackSwipe.Anywhere")
    // Il pannello delle Impostazioni: solo dal bordo del pannello.
    assertThat(text("feature-settings/src/main/kotlin/dev/pampa/mycloudgallery/feature/settings/SettingsPanes.kt"))
      .contains("backSwipe = FluidBackSwipe.EdgeOnly")
  }
}
