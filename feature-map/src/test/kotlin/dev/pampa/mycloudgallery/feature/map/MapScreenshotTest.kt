package dev.pampa.mycloudgallery.feature.map

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryRows
import dev.pampa.mycloudgallery.feature.map.contract.MapBubble
import dev.pampa.mycloudgallery.feature.map.contract.MapNotice
import dev.pampa.mycloudgallery.feature.map.contract.MapPrivacyState
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.PlaceRow
import dev.pampa.mycloudgallery.feature.map.contract.PlacesSectionState
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.grid.NoZoneGridHandlers
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridScreen
import dev.pampa.mycloudgallery.feature.map.map.MapInitial
import dev.pampa.mycloudgallery.feature.map.map.MapScreen
import dev.pampa.mycloudgallery.feature.map.map.MapStyleChoice
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import dev.pampa.mycloudgallery.feature.map.map.MapUi
import dev.pampa.mycloudgallery.feature.map.map.NoMapHandlers
import dev.pampa.mycloudgallery.feature.map.map.PlacedBubble
import dev.pampa.mycloudgallery.feature.map.places.PlacesSectionSources
import dev.pampa.mycloudgallery.feature.map.privacy.MapPrivacyScreen
import dev.pampa.mycloudgallery.feature.map.privacy.NoMapPrivacyHandlers
import dev.pampa.mycloudgallery.feature.map.support.MapRig
import dev.pampa.mycloudgallery.feature.map.testing.FakeMapSurface
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della mappa (Fase 9, P4): **ogni stato del §1.5** -- la pagina della mappa (primo fotogramma, in caricamento, pronta con le bolle, carta semplice, solo sul Wi-Fi, senza rete, nessuna foto con
 * la posizione, con la selezione di "Mostra sulla mappa"), la griglia di una zona (scheletro, con e senza nome, vuota), la sezione Luoghi delle Raccolte (pronta, senza posizione, primo fotogramma) e Impostazioni
 * -> Mappa (accesa, spenta, non disponibile). Chiaro e scuro, telefono (412 dp) e tablet (1280 dp), piu' il testo a 1,3x sul telefono. **MapLibre vero non si disegna in Robolectric**: la carta e' quella *finta*
 * (`FakeMapSurface`: una carta piatta con le linee di una griglia), quindi gli screenshot dicono come stanno controlli, bolle, righe e credito, **non** com'e' disegnata la carta. Il disegno vero si guarda sul
 * telefono (F119, F122). **Nessuna foto vera**: gradienti; i luoghi sono citta' pubbliche.
 *
 * `gradlew :feature-map:recordRoborazziDebug --tests "*ScreenshotTest*"` scrive i PNG in `docs/screenshots/9-P4/`. Nei normali `testDebugUnitTest` si verifica solo che le schermate compongano e dicano le cose giuste.
 */
internal abstract class MapShots(private val dark: Boolean, private val device: String, private val widthDp: Int, private val heightDp: Int) : MapScreenTest() {

  private val rig = MapRig()
  private val thumbs get() = images.thumbnails
  private val look: String get() = if (dark) "scuro" else "chiaro"
  private val center = GeoE6(43_000_000, 12_000_000)

  /** [expect]: le frasi che la scena deve dire. */
  private fun shoot(name: String, vararg expect: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    showAll(dark = dark, fontScale = fontScale, content = content)
    for (text in expect) assertShows(name, text)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    if (ScreenshotPolicy.capture(name, device, fontScale)) composeRule.onRoot().captureRoboImage("../docs/screenshots/9-P4/$name-$look-$device$suffix.png")
  }

  private fun surface() = FakeMapSurface(center, zoom = 6.0, widthDp = widthDp, heightDp = heightDp, density = 2f)

  private fun at(dLat: Double, dLon: Double) = GeoE6(center.latE6 + (dLat * 1_000_000).toInt(), center.lonE6 + (dLon * 1_000_000).toInt())

  /** Bolle di esempio: grandi con il nome, piccole senza, una foto sola. */
  private fun bubbles(): List<PlacedBubble> = listOf(
    PlacedBubble(MapBubble.Cluster(rig.cellOf(0, 20), 128, rig.ids[0], "Firenze"), at(0.0, 0.0)),
    PlacedBubble(MapBubble.Cluster(rig.cellOf(1, 20), 12, rig.ids[1], null), at(0.9, 1.0)),
    PlacedBubble(MapBubble.Cluster(rig.cellOf(2, 20), 1_240, rig.ids[2], "Roma"), at(-1.2, 0.9)),
    PlacedBubble(MapBubble.Single(rig.cellOf(3, 20), rig.ids[3]), at(1.2, -0.9)),
    PlacedBubble(MapBubble.Cluster(rig.cellOf(4, 20), 45, rig.ids[4], "Siena"), at(-0.4, -1.1)),
    PlacedBubble(MapBubble.Cluster(rig.cellOf(5, 20), 3, rig.ids[5], null), at(0.4, 0.6)),
  )

  private fun style(online: MapOnline, regions: Boolean = false): MapStyleChoice = MapStyles.choose(online, regions, dark)

  private val start = MapInitial.Saved(CameraSpot(center, 6.0))
  private val on = MapOnline.On(byDefault = true)

  private fun mapScene(ui: MapUi, selection: Boolean = false): @Composable () -> Unit = {
    val s = surface()
    MapScreen(ui.copy(selection = if (selection) center else null), s, s.content(), thumbs, rig.snapshot, NoMapHandlers, Modifier.fillMaxSize())
  }

  // --- La pagina della mappa --------------------------------------------------------------------------------------------------------

  @Test fun mappaPrimoFotogramma() = shoot("mappa-primo-fotogramma", "Mappa", "Cerco le foto con la posizione…", content = mapScene(MapUi(MapScreenState.Loading, style(on), null)))

  @Test fun mappaInCaricamento() = shoot("mappa-caricamento", "Cerco le foto con la posizione…", content = mapScene(MapUi(MapScreenState.Loading, style(on), start)))

  @Test fun mappaPronta() = shoot("mappa-pronta", "Firenze", "© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap", content = mapScene(MapUi(MapScreenState.Ready(on, null, 1_240), style(on), start, bubbles())))

  @Test fun mappaProntaTestoGrande() = shoot("mappa-pronta", "Mappa", fontScale = LARGE_TEXT, content = mapScene(MapUi(MapScreenState.Ready(on, MapNotice.WIFI_ONLY, 0), style(on), start, bubbles())))

  @Test fun mappaCartaSemplice() = shoot("mappa-carta-semplice", "Carta semplice · Mappa dettagliata", "Natural Earth · GeoNames", content = mapScene(MapUi(MapScreenState.Ready(MapOnline.Off, MapNotice.BASE_ONLY, 0), style(MapOnline.Off), start, bubbles())))

  @Test fun mappaSoloWifi() = shoot("mappa-solo-wifi", "Mappa dettagliata solo sul Wi-Fi", content = mapScene(MapUi(MapScreenState.Ready(on, MapNotice.WIFI_ONLY, 0), style(on), start, bubbles())))

  @Test fun mappaSenzaRete() = shoot("mappa-senza-rete", "Nessuna rete: la carta semplice e le zone scaricate", content = mapScene(MapUi(MapScreenState.Ready(on, MapNotice.OFFLINE, 0), style(on), start, bubbles())))

  @Test fun mappaVuota() = shoot("mappa-vuota", "Nessuna foto con la posizione", "320 foto senza posizione", content = mapScene(MapUi(MapScreenState.Empty(320), style(on), start)))

  @Test fun mappaVuotaTestoGrande() = shoot("mappa-vuota", "Nessuna foto con la posizione", fontScale = LARGE_TEXT, content = mapScene(MapUi(MapScreenState.Empty(320), style(on), start)))

  @Test fun mappaConLaSelezione() = shoot("mappa-selezione", "Mappa", content = mapScene(MapUi(MapScreenState.Ready(on, null, 12), style(on), start, bubbles()), selection = true))

  // --- La griglia di una zona -------------------------------------------------------------------------------------------------------

  private fun zone(state: ZoneGridState?): @Composable () -> Unit = { ZoneGridScreen(state, thumbs, NoZoneGridHandlers, Modifier.fillMaxSize()) }

  private val view get() = IndexOrderedTimelineView(rig.snapshot, IntArray(40) { it })

  @Test fun zonaScheletro() = shoot("zona-scheletro", "Questa zona", content = zone(null))

  @Test fun zonaPronta() = shoot("zona-pronta", "Firenze", "40 foto", content = zone(ZoneGridState.Ready("Firenze", view)))

  @Test fun zonaProntaTestoGrande() = shoot("zona-pronta", "Firenze", fontScale = LARGE_TEXT, content = zone(ZoneGridState.Ready("Firenze", view)))

  @Test fun zonaSenzaNome() = shoot("zona-senza-nome", "Questa zona", "40 foto", content = zone(ZoneGridState.Ready(null, view)))

  @Test fun zonaVuota() = shoot("zona-vuota", "Nessuna foto qui", "Torna alla mappa", content = zone(ZoneGridState.Empty))

  // --- La sezione Luoghi delle Raccolte -------------------------------------------------------------------------------------------------

  @Composable
  private fun SectionScene(state: PlacesSectionState?) {
    val section = PlacesSectionSources.sectionOf(LocalContext.current.resources, state)
    val group = CollectionEntryGroup(
      title = section.title, entries = section.entries.map { CollectionEntry(it.code, it.title, it.subtitle, it.icon, it.tone, it.badge, it.trailing) },
      code = section.code, hideable = section.hideable, hint = section.hint,
    )
    FluidScreen(title = "Raccolte", modifier = Modifier.fillMaxSize()) {
      item { FluidSectionHeader(title = section.title) }
      item { CollectionEntryRows(group, selectedCode = null, twoPane = false, onSelect = {}) }
    }
  }

  private val places = PlacesSectionState.Ready(9_000, List(8) { PlaceRow(3_000_000 + it, "Luogo di prova ${it + 1}", 900 - it * 50) }, 1_240)

  @Test fun luoghiPrimoFotogramma() = shoot("luoghi-primo-fotogramma", "Mappa", "Le tue foto sulla carta") { SectionScene(null) }

  @Test fun luoghiPronta() = shoot("luoghi-pronta", "Mappa", "9.000 foto con la posizione", "Luogo di prova 1", "1.240 foto senza posizione") { SectionScene(places) }

  @Test fun luoghiProntaTestoGrande() = shoot("luoghi-pronta", "Mappa", fontScale = LARGE_TEXT) { SectionScene(places) }

  @Test fun luoghiSenzaPosizione() = shoot("luoghi-senza-posizione", "Nessuna foto con la posizione", "320 foto senza posizione") { SectionScene(PlacesSectionState.NoGps(320)) }

  // --- Impostazioni -> Mappa ------------------------------------------------------------------------------------------------------

  private fun settings(state: MapPrivacyState): @Composable () -> Unit = { MapPrivacyScreen(state, NoMapPrivacyHandlers, Modifier.fillMaxSize()) }

  @Test fun impostazioniAccesa() = shoot("impostazioni-accesa", "Accesa su questo dispositivo", "Solo sul Wi-Fi", "Nessuna zona scaricata", content = settings(MapPrivacyState.Shown(on, true, 1, 0)))

  @Test fun impostazioniAccesaTestoGrande() = shoot("impostazioni-accesa", "Accesa su questo dispositivo", fontScale = LARGE_TEXT, content = settings(MapPrivacyState.Shown(on, false, 1, 2)))

  @Test fun impostazioniSpenta() = shoot("impostazioni-spenta", "Spenta: resta la carta semplice dentro l’app", "2 zone scaricate", content = settings(MapPrivacyState.Shown(MapOnline.Off, true, 1, 2)))

  @Test fun impostazioniNonDisponibile() = shoot("impostazioni-non-disponibile", "La mappa dettagliata non c’è in questa versione", content = settings(MapPrivacyState.Unavailable))
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
internal class MapPhoneScreenshotTest(dark: Boolean) : MapShots(dark, "telefono", 412, 915) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
internal class MapTabletScreenshotTest(dark: Boolean) : MapShots(dark, "tablet", 1280, 800) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
