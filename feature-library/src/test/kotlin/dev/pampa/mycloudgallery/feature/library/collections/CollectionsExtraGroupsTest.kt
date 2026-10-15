package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import org.junit.Test
import org.robolectric.annotation.Config

// Fase 4B (P16): le voci in piu' delle Raccolte che arrivano da `:app` ("Sul NAS"), come dati. Sul telefono una voce apre la sua rotta,
// sul tablet la sua pagina sta nel pannello di dettaglio; senza voci il gruppo non c'e' nemmeno come intestazione.

private val nasGroup = CollectionEntryGroup(
  title = "Sul NAS",
  entries = listOf(
    CollectionEntry("nas/folders", "Cartelle NAS", "Sfoglia, crea, rinomina", Icons.Rounded.Folder, FluidTone.Primary),
    CollectionEntry("nas/operations", "Operazioni in corso", "1 operazione attiva", Icons.Rounded.Sync, FluidTone.Success, badge = 1),
  ),
)

class CollectionsExtraGroupsPhoneTest : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()

  @Test
  fun leVociDiAppStannoDopoIGruppiDiSempre_eUnToccoApreLaRotta() {
    val opened = mutableListOf<String>()
    show {
      CollectionsScreen(
        state = collectionsUiState(world.source.countsNow(), canTrash = true),
        contentPadding = PaddingValues(),
        backdropOut = {},
        onOpenCollection = {},
        onOpenSettings = {},
        statusHeader = {},
        detail = { kind -> Text("dettaglio ${kind.code}") },
        extraGroups = listOf(nasGroup),
        onOpenEntry = { opened += it.code },
        entryDetail = { entry -> Text("pagina ${entry.code}") },
      )
    }

    // L'elenco e' una lista pigra: si scorre fino al gruppo, che sta dopo quelli di sempre.
    // L'intestazione del gruppo (in maiuscolo, come le altre), poi le voci.
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Sul NAS", ignoreCase = true))
    composeRule.onNodeWithText("Sul NAS", ignoreCase = true).assertExists()
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Operazioni in corso"))
    composeRule.onNodeWithText("Operazioni in corso").performClick()
    composeRule.onNodeWithText("Cartelle NAS").performClick()
    assertThat(opened).containsExactly("nas/operations", "nas/folders").inOrder()
    // Sul telefono non c'e' il pannello di dettaglio.
    composeRule.onAllNodesWithText("pagina", substring = true).assertCountEquals(0)
  }

  @Test
  fun senzaVociIlGruppoNonCe() {
    show {
      CollectionsScreen(
        state = collectionsUiState(world.source.countsNow(), canTrash = true),
        contentPadding = PaddingValues(),
        backdropOut = {},
        onOpenCollection = {},
        onOpenSettings = {},
        statusHeader = {},
        detail = {},
        extraGroups = listOf(CollectionEntryGroup("Sul NAS", emptyList())),
      )
    }

    composeRule.onAllNodesWithText("Sul NAS", ignoreCase = true).assertCountEquals(0)
  }
}

@Config(qualifiers = TABLET_QUALIFIERS)
class CollectionsExtraGroupsTabletTest : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()

  @Test
  fun sulTabletLaVoceSceglieLaPaginaAccanto_eUnaRaccoltaTornaAlSuoDettaglio() {
    val opened = mutableListOf<String>()
    show {
      CollectionsScreen(
        state = collectionsUiState(world.source.countsNow(), canTrash = true),
        contentPadding = PaddingValues(),
        backdropOut = {},
        onOpenCollection = {},
        onOpenSettings = {},
        statusHeader = {},
        detail = { kind -> Text("dettaglio ${kind.code}") },
        extraGroups = listOf(nasGroup),
        onOpenEntry = { opened += it.code },
        entryDetail = { entry -> Text("pagina ${entry.code}") },
      )
    }

    composeRule.onNodeWithText("dettaglio favorites").assertExists()
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Cartelle NAS"))
    composeRule.onNodeWithText("Cartelle NAS").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("pagina nas/folders").assertExists()
    assertThat(opened).isEmpty()

    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Video"))
    composeRule.onNodeWithText("Video").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("dettaglio videos").assertExists()
  }
}
