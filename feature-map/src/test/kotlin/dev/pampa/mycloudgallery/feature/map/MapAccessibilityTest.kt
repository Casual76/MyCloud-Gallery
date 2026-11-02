package dev.pampa.mycloudgallery.feature.map

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.feature.map.contract.MapBubble
import dev.pampa.mycloudgallery.feature.map.contract.MapNotice
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.grid.NoZoneGridHandlers
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridScreen
import dev.pampa.mycloudgallery.feature.map.map.MapHandlers
import dev.pampa.mycloudgallery.feature.map.map.MapInitial
import dev.pampa.mycloudgallery.feature.map.map.MapScreen
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import dev.pampa.mycloudgallery.feature.map.map.MapUi
import dev.pampa.mycloudgallery.feature.map.map.NoMapHandlers
import dev.pampa.mycloudgallery.feature.map.map.PlacedBubble
import dev.pampa.mycloudgallery.feature.map.support.MapRig
import dev.pampa.mycloudgallery.feature.map.testing.FakeMapSurface
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10, P8 (D23, E-AC8, E-AC9, §2.7 "Mappa", D28): la mappa letta come la legge TalkBack. Gli stati del catalogo (pronta con le bolle, carta
 * semplice, solo Wi-Fi, senza rete, vuota, griglia della zona), a testo 1,0 e 2,0: nome e ruolo, bersagli da 48 dp, niente tagli. In piu':
 * **"Elenco dei luoghi in vista"** (le bolle come raccolta, lette per numero), ogni bolla "12 foto, Firenze" **senza la casa**, e le sue due
 * azioni al posto del tocco, **Avvicina** e **Apri la zona**.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
internal class MapAccessibilityTest : MapScreenTest() {

  private val rig = MapRig()
  private val thumbs get() = images.thumbnails
  private val center = GeoE6(43_000_000, 12_000_000)
  private val on = MapOnline.On(byDefault = true)
  private val start = MapInitial.Saved(CameraSpot(center, 6.0))

  private fun surface() = FakeMapSurface(center, zoom = 6.0, widthDp = 412, heightDp = 915, density = 2f)

  private fun at(dLat: Double, dLon: Double) = GeoE6(center.latE6 + (dLat * 1_000_000).toInt(), center.lonE6 + (dLon * 1_000_000).toInt())

  private fun bubbles(): List<PlacedBubble> = listOf(
    PlacedBubble(MapBubble.Cluster(rig.cellOf(0, 20), 128, rig.ids[0], "Firenze"), at(0.0, 0.0)),
    PlacedBubble(MapBubble.Cluster(rig.cellOf(1, 20), 12, rig.ids[1], null), at(0.9, 1.0)),
    PlacedBubble(MapBubble.Cluster(rig.cellOf(2, 20), 1_240, rig.ids[2], "Roma"), at(-1.2, 0.9)),
    PlacedBubble(MapBubble.Single(rig.cellOf(3, 20), rig.ids[3]), at(1.2, -0.9)),
    PlacedBubble(MapBubble.Cluster(rig.cellOf(4, 20), 45, rig.ids[4], "Siena"), at(-0.4, -1.1)),
    PlacedBubble(MapBubble.Cluster(rig.cellOf(5, 20), 3, rig.ids[5], null), at(0.4, 0.6)),
  )

  private val zoomed = mutableListOf<PlacedBubble>()
  private val zoned = mutableListOf<PlacedBubble>()

  private val handlers = object : MapHandlers by NoMapHandlers {
    override fun onBubbleZoom(bubble: PlacedBubble) {
      zoomed += bubble
    }

    override fun onBubbleZone(bubble: PlacedBubble) {
      zoned += bubble
    }
  }

  /** La scala del testo di adesso: si cambia senza rimostrare la scena (`setContent` si puo' chiamare una volta per prova). */
  private val scale = mutableStateOf(1f)

  private fun mapScene(ui: MapUi) =
    showAll {
      val d = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) {
        val s = surface()
        MapScreen(ui, s, s.content(), thumbs, rig.snapshot, handlers, Modifier.fillMaxSize())
      }
    }

  private fun ready(notice: MapNotice? = null, online: MapOnline = on) =
    MapUi(MapScreenState.Ready(online, notice, 1_240), MapStyles.choose(online, false, false), start, bubbles())

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun config() = A11yConfig(density = composeRule.density)

  private fun bubbleNodes(): List<SemanticsNode> = A11yAssertions.nodes(root()).filter { n ->
    A11yAssertions.name(n).let { it.contains("foto") && !it.startsWith("©") } && A11yAssertions.customActionLabels(n).isNotEmpty()
  }

  /** Le regole a testo 1,0 e poi a 2,0, sulla stessa scena (la scala e' uno stato: niente secondo `setContent`). */
  private fun cleanAtBothScales(name: String) {
    for (value in listOf(1f, 2f)) {
      scale.value = value
      composeRule.waitForIdle()
      val found = A11yAssertions.check(root(), config())
      assertWithMessage("$name a testo $value: " + found.joinToString(" | ")).that(found).isEmpty()
    }
  }

  private val style get() = MapStyles.choose(on, false, false)

  @Test fun mappaPronta() { mapScene(ready()); cleanAtBothScales("mappa pronta") }

  @Test fun mappaSoloWifi() { mapScene(ready(MapNotice.WIFI_ONLY)); cleanAtBothScales("mappa solo Wi-Fi") }

  @Test fun mappaSenzaRete() { mapScene(ready(MapNotice.OFFLINE)); cleanAtBothScales("mappa senza rete") }

  @Test fun mappaCartaSemplice() { mapScene(ready(MapNotice.BASE_ONLY, MapOnline.Off)); cleanAtBothScales("mappa carta semplice") }

  @Test fun mappaInCaricamento() { mapScene(MapUi(MapScreenState.Loading, style, start)); cleanAtBothScales("mappa in caricamento") }

  @Test fun mappaVuota() { mapScene(MapUi(MapScreenState.Empty(320), style, start)); cleanAtBothScales("mappa vuota") }

  private fun zone(state: ZoneGridState?) {
    showAll {
      val d = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) {
        ZoneGridScreen(state, thumbs, NoZoneGridHandlers, Modifier.fillMaxSize())
      }
    }
  }

  private val zoneView get() = IndexOrderedTimelineView(rig.snapshot, IntArray(40) { it })

  @Test fun zonaPronta() { zone(ZoneGridState.Ready("Firenze", zoneView)); cleanAtBothScales("zona pronta") }

  @Test fun zonaVuota() { zone(ZoneGridState.Empty); cleanAtBothScales("zona vuota") }

  @Test fun zonaScheletro() { zone(null); cleanAtBothScales("zona scheletro") }

  @Test
  fun screenshotTesto2x() {
    scale.value = 2f
    mapScene(ready(MapNotice.WIFI_ONLY))
    composeRule.onRoot().captureRoboImage("../docs/screenshots/10-P8/mappa-testo-2x-chiaro-telefono.png")
  }

  @Test
  fun screenshotVuotaTesto2x() {
    scale.value = 2f
    mapScene(MapUi(MapScreenState.Empty(320), style, start))
    composeRule.onRoot().captureRoboImage("../docs/screenshots/10-P8/mappa-vuota-testo-2x-chiaro-telefono.png")
  }

  @Test
  fun leBolleSonoUnElencoLettoPerNumero() {
    mapScene(ready())
    val list = A11yAssertions.nodes(root()).first { A11yAssertions.name(it) == "Elenco dei luoghi in vista" }
    assertThat(list.config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount).isEqualTo(6)
    // L'ordine di lettura: dalla piu' grande ("1.240 foto, Roma") alla piu' piccola ("1 foto").
    val order = bubbleNodes().sortedBy { it.config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f }.map(A11yAssertions::name)
    assertThat(order.first()).startsWith("1.240 foto")
    assertThat(order.last()).isEqualTo("1 foto")
  }

  @Test
  fun ognunaDiceIlNumeroEIlLuogoMaiLaCasa() {
    mapScene(ready())
    val names = bubbleNodes().map(A11yAssertions::name)
    assertThat(names).contains("128 foto, Firenze")
    assertThat(names).contains("12 foto")
    names.forEach { assertThat(it.lowercase()).doesNotContain("casa") }
    names.forEach { assertThat(A11yAssertions.leakIn(it)).isNull() }
  }

  @Test
  fun leBolleHannoAvvicinaEApriLaZona() {
    mapScene(ready())
    val node = bubbleNodes().first { A11yAssertions.name(it) == "128 foto, Firenze" }
    assertThat(A11yAssertions.customActionLabels(node)).containsExactly("Avvicina", "Apri la zona").inOrder()
    val actions = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions)!!
    composeRule.runOnUiThread {
      actions.first { it.label == "Avvicina" }.action()
      actions.first { it.label == "Apri la zona" }.action()
    }
    assertThat(zoomed.map { it.count }).containsExactly(128)
    assertThat(zoned.map { it.count }).containsExactly(128)
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfMappaPronta() {
    mapScene(ready())
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfZonaPronta() {
    zone(ZoneGridState.Ready("Firenze", zoneView))
    composeRule.onRoot().checkRoboAccessibility()
  }
}

