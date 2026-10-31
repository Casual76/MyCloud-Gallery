package dev.pampa.mycloudgallery.feature.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import dev.pampa.mycloudgallery.feature.map.contract.MapBubble
import dev.pampa.mycloudgallery.feature.map.contract.MapNotice
import dev.pampa.mycloudgallery.feature.map.contract.MapPrivacyState
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.grid.NoZoneGridHandlers
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridScreen
import dev.pampa.mycloudgallery.feature.map.map.BubbleEngine
import dev.pampa.mycloudgallery.feature.map.map.LayoutProbe
import dev.pampa.mycloudgallery.feature.map.map.MapHandlers
import dev.pampa.mycloudgallery.feature.map.map.MapInitial
import dev.pampa.mycloudgallery.feature.map.map.MapScreen
import dev.pampa.mycloudgallery.feature.map.map.MapStyleChoice
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import dev.pampa.mycloudgallery.feature.map.map.MapSurface
import dev.pampa.mycloudgallery.feature.map.map.MapSurfaceContent
import dev.pampa.mycloudgallery.feature.map.map.MapSurfaceRequest
import dev.pampa.mycloudgallery.feature.map.map.MapUi
import dev.pampa.mycloudgallery.feature.map.map.NoMapHandlers
import dev.pampa.mycloudgallery.feature.map.map.PlacedBubble
import dev.pampa.mycloudgallery.feature.map.privacy.MapPrivacyHandlers
import dev.pampa.mycloudgallery.feature.map.privacy.MapPrivacyScreen
import dev.pampa.mycloudgallery.feature.map.privacy.NoMapPrivacyHandlers
import dev.pampa.mycloudgallery.feature.map.support.Cities
import dev.pampa.mycloudgallery.feature.map.support.MapRig
import dev.pampa.mycloudgallery.feature.map.testing.FakeMapSurface
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Le schermate della mappa composte (Fase 9, P4; docs/fase9-progetto.md §1.5, §2.10, D24, D28, L430): **le bolle si riposizionano senza nessuna nuova misura del layout** (test di composizione), TalkBack dice
 * "12 foto, Firenze", i bersagli sono da 48 dp anche a testo 1,3x, la carta nasce solo quando la camera e' decisa, il credito della carta c'e' sempre (quello di MapLibre e' spento) e la pagina Impostazioni -> Mappa
 * dice la nota di Q1 (c). Sopra la mappa **finta**: MapLibre non si disegna in Robolectric (si prova sul telefono, F119 e F122).
 */
internal class MapScreensTest : MapScreenTest() {

  private val rig = MapRig()
  private val engine = BubbleEngine({ id -> if (id == 0) null else "Firenze" }, FinalFilter(rig.visibility))
  private val phoneCenter = GeoE6(43_000_000, 11_800_000)

  private fun surface() = FakeMapSurface(phoneCenter, zoom = 5.0, widthDp = 412, heightDp = 915, density = 2f)

  private fun readyUi(
    bubbles: List<PlacedBubble> = emptyList(),
    notice: MapNotice? = null,
    online: MapOnline = MapOnline.On(byDefault = true),
    withoutPosition: Int = 1_240,
    style: MapStyleChoice = MapStyles.choose(online, hasRegions = false, dark = false),
  ) = MapUi(MapScreenState.Ready(online, notice, withoutPosition), style, MapInitial.Saved(CameraSpot(phoneCenter, 5.0)), bubbles)

  private fun bubbles(surface: FakeMapSurface): List<PlacedBubble> = runBlocking { engine.compute(rig.index, surface.viewport) }

  @Composable
  private fun Page(ui: MapUi, surface: MapSurface, content: MapSurfaceContent, handlers: MapHandlers = NoMapHandlers, probe: LayoutProbe? = null) {
    MapScreen(ui, surface, content, images.thumbnails, rig.snapshot, handlers, Modifier, probe)
  }

  // --- Le bolle (D28, §4.4) ----------------------------------------------------------------------------------------------------------

  /** I nodi delle bolle: i controlli con una frase come "12 foto" o "12 foto, Firenze". */
  private fun bubbleNodes() = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.let { Regex("""^\d[\d.]* foto(, .+)?$""").matches(it) } == true
  }

  @Test
  fun leBolleSiRiposizionanoSenzaUnaNuovaMisuraDelLayout() {
    val surface = surface()
    val list = bubbles(surface)
    assertThat(list).isNotEmpty()
    val probe = LayoutProbe()
    showAll { Page(readyUi(list), surface, surface.content(), probe = probe) }
    val nodes = bubbleNodes()
    assertThat(nodes.size).isEqualTo(list.size)
    val measuredAtRest = probe.measures
    assertThat(measuredAtRest).isAtLeast(list.size)
    val before = bubbleNodes().map { it.positionInRoot }
    // Cinque fotogrammi di movimento della camera (un gesto): le bolle si spostano, nessuna e' stata misurata di nuovo e nessuna ricomposta.
    repeat(5) {
      composeRule.runOnUiThread { surface.pan(dxDp = 30.0, dyDp = 10.0) }
      composeRule.waitForIdle()
    }
    assertThat(probe.measures).isEqualTo(measuredAtRest)
    val after = bubbleNodes().map { it.positionInRoot }
    // 150 dp a destra e 50 in basso: a 2 pixel per dp, 300 e 100 pixel (a meno dell'arrotondamento).
    for ((a, b) in before.zip(after)) {
      assertThat(b.x - a.x).isWithin(2f).of(300f)
      assertThat(b.y - a.y).isWithin(2f).of(100f)
    }
  }

  @Test
  fun TalkBackDiceQuanteFotoEDove_EIlBersaglioEDa48Dp() {
    val surface = surface()
    val cluster = PlacedBubble(MapBubble.Cluster(rig.cellOf(0, 20), 12, rig.ids[0], "Firenze"), phoneCenter)
    val noName = PlacedBubble(MapBubble.Cluster(rig.cellOf(1, 20), 1_240, rig.ids[1], null), GeoE6(43_000_000, 11_900_000))
    val single = PlacedBubble(MapBubble.Single(rig.cellOf(2, 20), rig.ids[2]), GeoE6(43_050_000, 11_700_000))
    showAll { Page(readyUi(listOf(cluster, noName, single)), surface, surface.content()) }
    assertThat(spokenControls()).containsAtLeast("12 foto, Firenze", "1.240 foto", "1 foto")
    val density = composeRule.density.density
    for (node in bubbleNodes()) {
      val b = node.touchBoundsInRoot
      assertWithMessage(label(node)).that(minOf(b.width, b.height) / density).isAtLeast(48f)
    }
  }

  @Test
  fun unaBollaSiTocca_EilControllorePuoAvvicinarsiOAprireLaGriglia() {
    val surface = surface()
    val tapped = ArrayList<PlacedBubble>()
    val handlers = object : MapHandlers by NoMapHandlers {
      override fun onBubble(bubble: PlacedBubble) {
        tapped += bubble
      }
    }
    val big = PlacedBubble(MapBubble.Cluster(rig.cellOf(0, 20), 500, rig.ids[0], null), phoneCenter)
    showAll { Page(readyUi(listOf(big)), surface, surface.content(), handlers) }
    clickText("500")
    assertThat(tapped).containsExactly(big)
  }

  // --- Pagina ------------------------------------------------------------------------------------------------------------------------

  @Test
  fun laCartaNasceSoloQuandoLaCameraEDecisa_ELaPaginaCeSubito() {
    val surface = surface()
    var created = 0
    val counting = object : MapSurfaceContent {
      override fun createSurface(): MapSurface = surface

      @Composable
      override fun Content(surface: MapSurface, request: MapSurfaceRequest, modifier: Modifier) {
        created++
      }
    }
    val state = mutableStateOf(MapUi(MapScreenState.Loading, MapStyles.choose(MapOnline.On(true), false, false)))
    showAll { Page(state.value, surface, counting) }
    // Primo fotogramma: i controlli, il titolo, il credito e "Cerco le foto..." ci sono; la carta no (la camera non e' decisa).
    assertShows("primo fotogramma", "Mappa", "Cerco le foto con la posizione…", PlacesAttribution.MAP_ONLINE_SHORT)
    assertThat(created).isEqualTo(0)
    composeRule.runOnUiThread { state.value = readyUi() }
    composeRule.waitForIdle()
    assertThat(created).isAtLeast(1)
  }

  @Test
  fun gliStatiDellaPaginaEIAvvisi() {
    val surface = surface()
    val state = mutableStateOf(readyUi())
    showAll { Page(state.value, surface, surface.content()) }
    fun scene(ui: MapUi, vararg shown: String, notShown: List<String> = emptyList()) {
      composeRule.runOnUiThread { state.value = ui }
      composeRule.waitForIdle()
      assertShows(ui.screen.toString(), *shown)
      assertDoesNotShow(ui.screen.toString(), *notShown.toTypedArray())
    }
    scene(readyUi(notice = MapNotice.BASE_ONLY, online = MapOnline.Off), "Carta semplice · Mappa dettagliata", PlacesAttribution.MAP_BASE_SHORT)
    scene(readyUi(notice = MapNotice.WIFI_ONLY), "Mappa dettagliata solo sul Wi-Fi", PlacesAttribution.MAP_ONLINE_SHORT)
    scene(readyUi(notice = MapNotice.OFFLINE), "Nessuna rete: la carta semplice e le zone scaricate")
    scene(readyUi(notice = null), PlacesAttribution.MAP_ONLINE_SHORT, notShown = listOf("Carta semplice", "solo sul Wi-Fi", "Nessuna rete"))
    scene(MapUi(MapScreenState.Empty(320), MapStyles.choose(MapOnline.On(true), false, false), readyUi().initial), "Nessuna foto con la posizione", "320 foto senza posizione")
    scene(MapUi(MapScreenState.Empty(0), MapStyles.choose(MapOnline.Off, false, false), readyUi().initial), "Nessuna foto con la posizione", "Le foto con la posizione compaiono qui")
    // Il credito di MapLibre apre un collegamento nel browser e non c'e' (L430): mai la scritta del suo pulsante.
    assertDoesNotShow("credito", "MapLibre", "Mapbox", "http")
  }

  private fun checkPageScenes(fontScale: Float?) {
    val surface = surface()
    checkScenes(
      fontScale,
      "mappa" to { Page(readyUi(notice = MapNotice.BASE_ONLY, online = MapOnline.Off), surface, surface.content()) },
      "solo wifi" to { Page(readyUi(notice = MapNotice.WIFI_ONLY), surface, surface.content()) },
      "vuota" to { Page(MapUi(MapScreenState.Empty(1_240), MapStyles.choose(MapOnline.On(true), false, false), readyUi().initial), surface, surface.content()) },
    )
  }

  @Test
  fun iControlliSonoDa48DpENonSiSovrappongono() = checkPageScenes(null)

  @Test
  fun iControlliSonoDa48DpENonSiSovrappongono_ATestoGrande() = checkPageScenes(LARGE_TEXT)

  @Test
  fun iTastiDellaPaginaFannoLeLoroCose() {
    val surface = surface()
    val calls = ArrayList<String>()
    val handlers = object : MapHandlers {
      override fun onBack() { calls += "indietro" }

      override fun onBubble(bubble: PlacedBubble) { calls += "bolla" }

      override fun onNotice() { calls += "avviso" }

      override fun onSettings() { calls += "impostazioni" }

      override fun onRetry() { calls += "riprova" }
    }
    showAll { Page(readyUi(notice = MapNotice.BASE_ONLY, online = MapOnline.Off), surface, surface.content(), handlers) }
    clickText("Carta semplice")
    assertThat(calls).containsExactly("avviso")
    assertThat(spokenControls()).containsAtLeast("Indietro", "Impostazioni della mappa")
  }

  // --- Impostazioni -> Mappa (Q1 (c)) ---------------------------------------------------------------------------------------------------

  @Test
  fun laPaginaDelleImpostazioniDiceLaNotaEL_InterruttorePerSpegnere() {
    val calls = ArrayList<String>()
    val handlers = object : MapPrivacyHandlers {
      override fun onBack() { calls += "indietro" }

      override fun onOnline(on: Boolean) { calls += "online=$on" }

      override fun onWifiOnly(on: Boolean) { calls += "wifi=$on" }

      override fun onOpenRegions() { calls += "zone" }
    }
    val state = mutableStateOf<MapPrivacyState>(MapPrivacyState.Shown(MapOnline.On(byDefault = true), wifiOnly = true, noteVersion = 1, regions = 0))
    showAll { MapPrivacyScreen(state.value, handlers) }
    assertShows(
      "accesa", "Accesa su questo dispositivo", "Solo sul Wi-Fi", "Mai con i dati mobili",
      "OpenFreeMap riceve l’indirizzo della tua connessione e la zona che stai guardando", "Non riceve mai le tue foto, le coordinate esatte né quante sono", "La scelta vale solo su questo dispositivo",
      "Zone senza Internet", "Nessuna zona scaricata", "Per non far sapere una zona, scaricala prima di aprirla, oppure spegni la mappa dettagliata",
    )
    clickText("Accesa su questo dispositivo")
    clickText("Mai con i dati mobili")
    clickText("Nessuna zona scaricata")
    assertThat(calls).containsExactly("online=false", "wifi=false", "zone").inOrder()
    // Spenta: la carta semplice resta, e "Solo sul Wi-Fi" non ha senso (nessuna richiesta): la riga non c'e'.
    composeRule.runOnUiThread { state.value = MapPrivacyState.Shown(MapOnline.Off, wifiOnly = true, noteVersion = 1, regions = 2) }
    composeRule.waitForIdle()
    assertShows("spenta", "Spenta: resta la carta semplice dentro l’app", "2 zone scaricate")
    assertDoesNotShow("spenta", "Solo sul Wi-Fi")
    clickText("Spenta: resta la carta semplice dentro l’app")
    assertThat(calls.last()).isEqualTo("online=true")
    // Senza l'interruttore di build: la pagina dice che la mappa dettagliata non c'e', senza interruttori.
    composeRule.runOnUiThread { state.value = MapPrivacyState.Unavailable }
    composeRule.waitForIdle()
    assertShows("non disponibile", "La mappa dettagliata non c’è in questa versione", "non esce niente dal telefono")
    assertDoesNotShow("non disponibile", "Solo sul Wi-Fi", "Accesa su questo dispositivo")
  }

  private fun checkSettingsScenes(fontScale: Float?) = checkScenes(
    fontScale,
    "accesa" to { MapPrivacyScreen(MapPrivacyState.Shown(MapOnline.On(byDefault = false), wifiOnly = false, noteVersion = 1, regions = 3), NoMapPrivacyHandlers) },
    "spenta" to { MapPrivacyScreen(MapPrivacyState.Shown(MapOnline.Off, true, 1, 0), NoMapPrivacyHandlers) },
    "non disponibile" to { MapPrivacyScreen(MapPrivacyState.Unavailable, NoMapPrivacyHandlers) },
  )

  @Test
  fun laPaginaDelleImpostazioniHaBersagliDa48DpENessunaSovrapposizione() = checkSettingsScenes(null)

  @Test
  fun laPaginaDelleImpostazioniHaBersagliDa48DpENessunaSovrapposizione_ATestoGrande() = checkSettingsScenes(LARGE_TEXT)

  // --- Griglia di una zona -----------------------------------------------------------------------------------------------------------

  @Test
  fun laGrigliaDiUnaZonaDiceIlNomeEQuanteFoto_OlScheletroEIlVuoto() {
    val view = IndexOrderedTimelineView(rig.snapshot, IntArray(7) { it })
    val state = mutableStateOf<ZoneGridState?>(null)
    showAll { ZoneGridScreen(state.value, images.thumbnails, NoZoneGridHandlers) }
    assertShows("scheletro", "Questa zona")
    composeRule.runOnUiThread { state.value = ZoneGridState.Ready("Firenze", view) }
    composeRule.waitForIdle()
    assertShows("pronta", "Firenze", "7 foto")
    composeRule.runOnUiThread { state.value = ZoneGridState.Ready(null, view) }
    composeRule.waitForIdle()
    assertShows("senza nome", "Questa zona", "7 foto")
    assertThat(countDescribed("Foto del")).isAtLeast(1)
    composeRule.runOnUiThread { state.value = ZoneGridState.Empty }
    composeRule.waitForIdle()
    assertShows("vuota", "Nessuna foto qui", "Torna alla mappa")
  }
}
