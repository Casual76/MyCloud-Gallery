package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidListDetailScaffold
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.library.R
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Le voci in piu' di Raccolte (`CollectionEntries`, solo dati) e la loro riga: le funzioni pure, e un'anteprima dell'integrazione
 * (l'elenco di Raccolte con il gruppo "Sul NAS" accanto a quelli di sempre, telefono e due pannelli del tablet, chiaro e scuro) in
 * `docs/screenshots/4-P14/` con il prefisso `raccolte-nas`.
 */
class CollectionEntriesTest {

  private fun entry(code: String, badge: Int = 0) = CollectionEntry(code, "Titolo $code", "Frase", Icons.Rounded.Folder, FluidTone.Primary, badge)

  @Test
  fun unaVoceSiTrovaPerCodiceEUnCodiceSconosciutoNonEUnaVoce() {
    val groups = listOf(CollectionEntryGroup("Sul NAS", listOf(entry("nas/folders"), entry("nas/space"))))
    assertThat(CollectionEntries.find(groups, "nas/space")?.title).isEqualTo("Titolo nas/space")
    assertThat(CollectionEntries.find(groups, "nas/altro")).isNull()
    assertThat(CollectionEntries.find(groups, null)).isNull()
    // Un codice salvato di una voce sparita non lascia un pannello vuoto.
    assertThat(CollectionEntries.selected(groups, "nas/sparita")).isNull()
  }

  @Test
  fun unGruppoVuotoNonHaNeppureLIntestazione() {
    val groups = listOf(CollectionEntryGroup("Sul NAS", emptyList()), CollectionEntryGroup("Altro", listOf(entry("x"))))
    assertThat(CollectionEntries.visible(groups).map { it.title }).containsExactly("Altro")
  }

  @Test
  fun iCodiciDiUnGruppoSonoUniciEIlBadgeNonENegativo() {
    runCatching { CollectionEntryGroup("Doppio", listOf(entry("a"), entry("a"))) }.let { assertThat(it.isFailure).isTrue() }
    runCatching { entry("a", badge = -1) }.let { assertThat(it.isFailure).isTrue() }
    runCatching { entry(" ") }.let { assertThat(it.isFailure).isTrue() }
  }

  @Test
  fun unaVoceNonPortaIlNomeDelNasNelLog() {
    assertThat(entry("nas/folders", badge = 2).toString()).isEqualTo("CollectionEntry(nas/folders, badge=2)")
  }

  @Test
  fun iDatiNonHannoStileNuovo() {
    // Come `CollectionsStyleTest`: nessun colore, raggio, dimensione di testo o dp a mano nei file `CollectionEntries*`.
    val files = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/library/collections").listFiles().orEmpty().filter { it.name.startsWith("CollectionEntries") }
    assertThat(files.map { it.name }).containsAtLeast("CollectionEntries.kt", "CollectionEntriesRows.kt")
    val forbidden = Regex("""Color\(\s*0x|\bColor\.[A-Z]\w+|RoundedCornerShape|CircleShape|\b\d+(\.\d+)?\.(sp|dp)\b|\bfontSize\s*=|\b(tween|spring)\s*[<(]""")
    for (file in files) {
      val bad = file.readLines().mapIndexedNotNull { i, line ->
        val code = line.substringBefore("//")
        val doc = code.trimStart().startsWith("*") || code.trimStart().startsWith("/**")
        if (!doc && forbidden.containsMatchIn(code)) "${file.name}:${i + 1}" else null
      }
      assertWithMessage("stile a mano in ${file.name}").that(bad).isEmpty()
    }
    // I dati non sanno nulla di un altro feature.
    for (file in files) assertThat(file.readText()).doesNotContain("feature.nas")
  }
}

/** L'anteprima dell'integrazione: Raccolte con il gruppo del NAS, sui fake. */
abstract class CollectionEntriesPreviewBase(private val dark: Boolean, private val device: String) : ComposeScreenTest() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun nasGroup(operations: Int) = CollectionEntryGroup(
    "Sul NAS",
    listOf(
      CollectionEntry("nas/folders", "Cartelle NAS", "Sfoglia, crea, rinomina, sposta ed elimina", Icons.Rounded.Folder, FluidTone.Primary),
      CollectionEntry("nas/space", "Spazio NAS", "Spazio libero, quota e file più grandi", Icons.Rounded.Storage, FluidTone.Info),
      CollectionEntry("nas/trash", "Eliminati di recente del NAS", "Il cestino del NAS: 30 giorni per ripensarci", Icons.Rounded.Delete, FluidTone.Danger),
      CollectionEntry(
        "nas/operations", "Operazioni in corso",
        if (operations > 0) "$operations in corso adesso" else "Quello che sta facendo il NAS per te",
        Icons.Rounded.Sync, FluidTone.Success, badge = operations,
      ),
    ),
  )

  /** I gruppi di sempre come righe (stessi titoli, icone e toni delle Raccolte), cosi' l'anteprima mostra l'elenco intero. */
  @Composable
  private fun standardGroups(): List<CollectionEntryGroup> {
    val state = collectionsUiState(mapOf(CollectionKind.FAVORITES to 312, CollectionKind.VIDEOS to 148, CollectionKind.SCREENSHOTS to 1_204, CollectionKind.WHATSAPP to 3_890), canTrash = true)
    @Composable fun CollectionRow.asEntry(subtitle: String) = CollectionEntry(kind.code, stringResource(kind.titleRes), subtitle, kind.icon, kind.tone)
    val phone = state.media.map { it.asEntry("${it.count} elementi") }
    val safe = state.protectedRows.map {
      it.asEntry(stringResource(if (it.kind == CollectionKind.HIDDEN) R.string.collection_hidden_subtitle else R.string.collection_recently_deleted_subtitle))
    }
    return listOf(
      CollectionEntryGroup(stringResource(R.string.collections_section_phone), phone),
      CollectionEntryGroup(stringResource(R.string.collections_section_protected), safe),
    )
  }

  @Composable
  private fun RaccolteWithNas(operations: Int, initial: String?) {
    var selected by rememberSaveable { mutableStateOf(initial) }
    // Il gruppo del NAS dopo quello dei posti protetti: in cima all'anteprima, cosi' si vede intero (l'elenco vero ha anche "Dal telefono").
    val groups = standardGroups().drop(1) + nasGroup(operations)
    FluidListDetailScaffold(
      ambient = null,
      list = { twoPane ->
        FluidScreen(title = stringResource(R.string.collections_title)) {
          collectionEntryItems(groups, selectedCode = if (twoPane) selected else null, twoPane = twoPane, onSelect = { selected = it.code })
        }
      },
      detail = { Text(text = CollectionEntries.find(groups, selected)?.title ?: "") },
    )
  }

  private fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale, content = content)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("../docs/screenshots/4-P14/raccolte-nas-$name-$look-$device$suffix.png")
  }

  @Test
  fun raccolteConIlGruppoDelNas() = shoot("elenco") { RaccolteWithNas(operations = 0, initial = "nas/folders") }

  @Test
  fun raccolteConUnaOperazioneInCorso() = shoot("operazioni") { RaccolteWithNas(operations = 2, initial = "nas/operations") }

  @Test
  fun raccolteTestoGrande() = shoot("elenco", fontScale = LARGE_TEXT) { RaccolteWithNas(operations = 2, initial = "nas/space") }

  @Test
  fun toccareUnaVoceLaScegliePassandoIlCodice() {
    var picked: String? = null
    show {
      FluidScreen(title = "Raccolte") {
        collectionEntryItems(listOf(nasGroup(0)), selectedCode = null, twoPane = false, onSelect = { picked = it.code })
      }
    }
    composeRule.onNodeWithText("Spazio NAS").performClick()
    composeRule.waitForIdle()
    assertThat(picked).isEqualTo("nas/space")
  }

  @Test
  fun unGruppoVuotoNonCompare() {
    show {
      FluidScreen(title = "Raccolte") {
        collectionEntryItems(listOf(CollectionEntryGroup("Sul NAS", emptyList())), selectedCode = null, twoPane = false, onSelect = {})
        item { FluidSectionHeader(title = "Fine") }
      }
    }
    composeRule.onNodeWithText("Sul NAS").assertDoesNotExist()
  }

  companion object {
    const val LARGE_TEXT = 1.3f
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class CollectionEntriesPhonePreviewTest(dark: Boolean) : CollectionEntriesPreviewBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class CollectionEntriesTabletPreviewTest(dark: Boolean) : CollectionEntriesPreviewBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
