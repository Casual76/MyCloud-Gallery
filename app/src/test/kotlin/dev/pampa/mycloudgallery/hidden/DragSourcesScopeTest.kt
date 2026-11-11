package dev.pampa.mycloudgallery.hidden

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import org.junit.Test

/**
 * Fase 10 (P6, D20, D41, E-T18, C436): **dai Nascosti non si trascina**, e il trascinamento verso un'altra app parte solo dalle superfici che il progetto elenca (Libreria, album, ricerca,
 * ricordo, griglia della zona, viewer). La prova e' sui sorgenti, come il registro: il modificatore `mediaDragSource` sta solo nei file della lista, **nessuno dei quali** e' una pagina dei Nascosti,
 * degli Eliminati di recente, dei Doppioni o del Cestino; la griglia condivisa (`MediaGrid`) riceve un'origine **solo** dalla Libreria; il viewer non trascina se e' aperto dai Nascosti
 * (`ViewerRequest.secure`); e nessuna origine nomina i Nascosti.
 */
class DragSourcesScopeTest {

  private val allowed = setOf(
    "feature-library/src/main/kotlin/dev/pampa/mycloudgallery/feature/library/grid/MediaGrid.kt",
    "feature-viewer/src/main/kotlin/dev/pampa/mycloudgallery/feature/viewer/page/ViewerPage.kt",
    "feature-albums/src/main/kotlin/dev/pampa/mycloudgallery/feature/albums/page/AlbumGrid.kt",
    "feature-search/src/main/kotlin/dev/pampa/mycloudgallery/feature/search/SearchResultsGrid.kt",
    "feature-memories/src/main/kotlin/dev/pampa/mycloudgallery/feature/memories/page/MemoryGrid.kt",
    "feature-map/src/main/kotlin/dev/pampa/mycloudgallery/feature/map/grid/ZoneGrid.kt",
    // Il design system definisce il modificatore.
    "core-designsystem/src/main/kotlin/dev/pampa/mycloudgallery/core/designsystem/adaptive/MediaDragSource.kt",
  )

  private val users = Regex("""\bmediaDragSource\s*\(""")

  @Test
  fun ilModificatoreStaSoloNelleSuperficiDelProgetto() {
    val found = RepoFiles.production().filter { users.containsMatchIn(RepoFiles.code(it)) }.map { RepoFiles.rel(it) }.toSet()
    assertThat(found).isEqualTo(allowed)
  }

  @Test
  fun nessunaPaginaDeiNascostiDegliEliminatiDeiDoppioniODelCestinoTrascina() {
    val forbiddenHomes = listOf("/hidden/", "/trash/", "/utility/", "/duplicates/", "Hidden", "RecentlyDeleted", "Trash", "Duplicates")
    val offenders = RepoFiles.production().filter { file ->
      val rel = "/" + RepoFiles.rel(file)
      forbiddenHomes.any { it in rel } && (users.containsMatchIn(RepoFiles.code(file)) || Regex("""\bdragOrigin\s*=""").containsMatchIn(RepoFiles.code(file)))
    }.map { RepoFiles.rel(it) }.toList()
    assertThat(offenders).isEmpty()
  }

  @Test
  fun laGrigliaCondivisaTrascinaSoloSeLeSiDaUnOrigine_EVieneDallaSolaLibreria() {
    val callers = RepoFiles.production().filter { file ->
      val code = RepoFiles.code(file)
      Regex("""\bMediaGrid\s*\(""").containsMatchIn(code) && !RepoFiles.rel(file).endsWith("/grid/MediaGrid.kt")
    }.toList()
    assertThat(callers.map { RepoFiles.rel(it).substringAfterLast('/') }).containsExactly("CollectionGridPage.kt", "LibraryScreen.kt")
    for (file in callers) {
      val passesOrigin = Regex("""\bdragOrigin\s*=""").containsMatchIn(RepoFiles.code(file))
      assertThat(passesOrigin).isEqualTo(file.name == "LibraryScreen.kt")
    }
    // Il viewer non trascina se e' aperto dai Nascosti.
    val overlay = RepoFiles.code(RepoFiles.root.resolve("feature-viewer/src/main/kotlin/dev/pampa/mycloudgallery/feature/viewer/ViewerOverlay.kt"))
    assertThat(overlay).contains("dragEnabled = !request.secure")
  }

  @Test
  fun nessunaOrigineNominaINascosti() {
    assertThat(DragOrigin.entries.map { it.code.lowercase() }.filter { "hidden" in it || "nascost" in it }).isEmpty()
  }
}
