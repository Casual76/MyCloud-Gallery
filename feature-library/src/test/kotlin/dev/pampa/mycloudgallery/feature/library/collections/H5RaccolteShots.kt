package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlot
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlotVisibility
import dev.pampa.mycloudgallery.core.designsystem.chrome.LocalGalleryTabBarSlot
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H5 (fase H, 11/11): la scheda **Raccolte** com'e' dopo H5, per l'utente. Come in produzione: la scheda dentro il posto della pillola (lo stesso di
 * `MainScaffold`), il fondo della pagina, la capsula della rete in testa, le sezioni nell'ordine predefinito e senza quelle passate alla scheda
 * Album, le copertine dalla cache delle miniature (qui quella finta, con le foto sintetiche della Libreria finta).
 *
 * Gli stati: in cima, scorsa fino in fondo, con il menu in alto aperto (Modifica Raccolte, Impostazioni), e la libreria **appena installata**
 * (nessun ricordo, nessuna persona, nessun viaggio, nessuna foto con la posizione). Il telefono dell'utente (360 x 780 dp) e il tablet orizzontale,
 * chiaro e scuro. I dati dei Ricordi, dei Viaggi, delle Persone e dei Luoghi sono inventati (nessun nome o luogo vero).
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests ...H5RaccoltePhoneShots` (e `...H5RaccolteTabletShots`) scrive in
 * `docs/screenshots/beta-grafica/H5/`.
 */
abstract class H5RaccolteShotBase(private val dark: Boolean, private val device: String) : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val covers = CollectionCovers(fakeThumbnailLoader(), world.source.items(LibraryFilter.ALL))
  private val photos = world.visibleIds.toList()

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, after: () -> Unit = {}, content: @Composable () -> Unit) {
    show(dark = dark, content = content)
    after()
    composeRule.waitForIdle()
    composeRule.onRoot().captureRoboImage("$DIR/raccolte-$name-$look-$device.png")
  }

  private fun entry(code: String, title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, cover: Int? = null, trailing: String? = null, tone: FluidTone = FluidTone.Neutral) =
    CollectionEntry(code, title, subtitle, icon, tone, trailing = trailing, cover = cover?.let { photos[it * 37 % photos.size] })

  /** Le sezioni di una libreria usata da un po': ricordi e viaggi con la copertina, persone, luoghi, NAS, Utility, Nascosti. */
  private fun full(): List<CollectionsSection> = listOfNotNull(
    CollectionsLayout.sectionOf(
      CollectionSections.TRIPS,
      CollectionEntryGroup(
        "Viaggi",
        listOf(
          entry("trip/t1", "Toscana, agosto 2025", "Viaggio · 640 elementi", Icons.Rounded.Flight, cover = 3, tone = FluidTone.Info),
          entry("trip/t2", "Sabato 12 aprile 2025", "Evento · 96 elementi", Icons.Rounded.Event, cover = 11, tone = FluidTone.Info),
        ),
        code = CollectionSections.TRIPS,
      ),
    ),
    CollectionsLayout.sectionOf(
      CollectionSections.PEOPLE,
      CollectionEntryGroup(
        "Persone",
        listOf(
          entry("person/p1", "Giulia", "212 foto", Icons.Rounded.Person, tone = FluidTone.Primary),
          entry("person/p2", "Marco", "148 foto", Icons.Rounded.Person, tone = FluidTone.Primary),
          entry("people/all", "Tutte le persone", "5 persone", Icons.Rounded.Group),
        ),
        code = CollectionSections.PEOPLE,
      ),
    ),
    CollectionsLayout.sectionOf(
      CollectionSections.MEMORIES,
      CollectionEntryGroup(
        "Ricordi",
        listOf(
          entry("m_1", "Un anno fa", "18 foto · ottobre 2025", Icons.Rounded.History, cover = 5, trailing = "Nuovo", tone = FluidTone.Primary),
          entry("m_2", "Estate al mare", "42 foto · agosto 2025", Icons.Rounded.Flight, cover = 17),
          entry("m_3", "Il meglio di ottobre 2026", "60 foto", Icons.Rounded.CalendarMonth, cover = 23),
          entry("memories/all", "Tutti i ricordi", "3 ricordi", Icons.Rounded.PhotoLibrary),
        ),
        code = CollectionSections.MEMORIES,
      ),
    ),
    CollectionsLayout.sectionOf(
      CollectionSections.PLACES,
      CollectionEntryGroup(
        "Luoghi",
        listOf(
          entry("places/map", "Mappa", "1.240 foto con la posizione", Icons.Rounded.Map, tone = FluidTone.Info),
          entry("places/p1", "Firenze", "320 foto", Icons.Rounded.Place),
          entry("places/p2", "Oslo", "88 foto", Icons.Rounded.Place),
        ),
        code = CollectionSections.PLACES,
      ),
    ),
  ) + samples(recognitionOn = true).filter { it.code != CollectionSections.PEOPLE && it.code != CollectionSections.TRIPS }

  /** La libreria appena installata: nessun ricordo, nessuna persona, nessun viaggio, nessuna foto con la posizione. */
  private fun fresh(): List<CollectionsSection> = listOfNotNull(
    CollectionsLayout.sectionOf(
      CollectionSections.MEMORIES,
      CollectionEntryGroup(
        "Ricordi",
        listOf(entry("memories/empty", "Ancora nessun ricordo", "Arrivano quando ci sono abbastanza foto", Icons.Rounded.History)),
        code = CollectionSections.MEMORIES,
      ),
    ),
    CollectionsLayout.sectionOf(
      CollectionSections.PLACES,
      CollectionEntryGroup(
        "Luoghi",
        listOf(entry("places/map", "Mappa", "Nessuna foto con la posizione", Icons.Rounded.Map, tone = FluidTone.Info)),
        code = CollectionSections.PLACES,
      ),
    ),
  ) + samples(recognitionOn = false).filter { it.code != CollectionSections.TRIPS }

  private fun samples(recognitionOn: Boolean): List<CollectionsSection> =
    (CollectionsSectionSamples.all().filter { it.code != CollectionSections.PEOPLE } + CollectionsSectionSamples.people(recognitionOn))
      .mapNotNull { CollectionsLayout.sectionOf(it.code, it.group.value) }

  private fun state(providers: List<CollectionsSection>): CollectionsLayoutState {
    val all = layoutOf(world, providers = providers)
    return all.copy(sections = all.sections.filter { it.code !in CollectionSections.inAlbumsTab })
  }

  /** La scheda nel posto della pillola, il fondo della pagina e l'host dei pop-up come in produzione. */
  @Composable
  private fun Tab(sections: List<CollectionsSection>) {
    val slot = remember { GalleryTabBarSlot() }
    var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      ModalTestHost {
        CompositionLocalProvider(LocalGalleryTabBarSlot provides slot, LocalCollectionCovers provides covers) {
          CollectionsScreen(
            state = state(sections),
            contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
            backdropOut = { backdrop = it },
            onOpenCollection = {},
            onOpenSettings = {},
            statusHeader = { ProductionNetworkHeader() },
            detail = { kind -> Text(kind.code) },
            entryDetail = { e -> Text(e.title) },
          )
        }
        GalleryTabBarSlotVisibility(visible = !slot.taken, modifier = Modifier.align(Alignment.BottomCenter)) {
          GalleryTabBar(selected = GalleryTab.Collections, onSelect = {}, backdrop = backdrop, onOpenSearch = {})
        }
      }
    }
  }

  private fun scrollTo(text: String) {
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text))
    composeRule.waitForIdle()
  }

  @Test
  fun inCima() = shoot("in-cima") { Tab(full()) }

  @Test
  fun scorsa() = shoot("scorsa", after = { scrollTo("Nascosti") }) { Tab(full()) }

  @Test
  fun ricordiEViaggi() = shoot("ricordi", after = { scrollTo("Tutti i ricordi") }) { Tab(full()) }

  @Test
  fun appenaInstallata() = shoot("appena-installata") { Tab(fresh()) }

  @Test
  fun appenaInstallataScorsa() = shoot("appena-installata-scorsa", after = { scrollTo("Nascosti") }) { Tab(fresh()) }

  @Test
  fun menuAperto() = shoot("menu", after = {
    composeRule.onNodeWithContentDescription("Altre azioni sulle Raccolte").performClick()
    composeRule.waitForIdle()
  }) { Tab(full()) }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/H5"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class H5RaccoltePhoneShots(dark: Boolean) : H5RaccolteShotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class H5RaccolteTabletShots(dark: Boolean) : H5RaccolteShotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
