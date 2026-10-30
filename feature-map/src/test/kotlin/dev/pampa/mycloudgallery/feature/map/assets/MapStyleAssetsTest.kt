package dev.pampa.mycloudgallery.feature.map.assets

import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryAccent
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapRequestFacts
import dev.pampa.mycloudgallery.core.map.contract.MapVerdict
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.feature.map.assets.MapAssets.list
import dev.pampa.mycloudgallery.feature.map.assets.MapAssets.obj
import java.io.File
import org.junit.Test

/**
 * Gli stili della mappa (Fase 9, P2; docs/fase9-progetto.md §4.4 riga P2, §2.7, D22): il "Fatto quando" scritto come prove sul JSON dei quattro stili
 * dell'app (chiaro e scuro, ognuno con la sorgente online e senza). Stesse regole dei controlli dello strumento (`tools/maps/style/check_style.py`), ma
 * rifatte qui per conto nostro: se qualcuno cambia uno stile a mano, o ne mette uno scaricato, falliscono.
 */
class MapStyleAssetsTest {
  private val stableTiles = "https://${MapHost.TILES}${MapHost.STABLE_TILE_PREFIX}{z}/{x}/{y}.pbf"
  private val fonts = listOf("NotoSans-Regular", "NotoSans-Bold")
  private val realRanges = listOf("0-255", "256-511", "768-1023", "8192-8447")
  private val allowedSourceLayers = setOf(
    "water", "landcover", "landuse", "park", "waterway", "aeroway", "transportation", "transportation_name", "boundary", "place",
    "water_name", "aerodrome_label",
  )

  private fun style(name: String): Map<String, Any?> = obj(MapAssets.json(name))
  private fun layers(style: Map<String, Any?>): List<Map<String, Any?>> = list(style["layers"]).map { obj(it) }
  private fun isOffline(name: String) = name.endsWith("-offline.json")
  private fun isDark(name: String) = "scuro" in name

  @Test
  fun ogniStringaDelloStileEAssetOLUrlStabileDelleTessere() {
    for (name in MapAssets.styles) {
      for (s in MapAssets.strings(MapAssets.json(name))) {
        if ("://" in s) assertWithMessage("$name: $s").that(s == stableTiles || s.startsWith("asset://map/")).isTrue()
      }
    }
  }

  @Test
  fun loStileOnlineHaUnaSolaSorgenteVettorialeConLUrlStabileENessunTileJson() {
    for (name in MapAssets.styles) {
      val sources = obj(style(name)["sources"])
      for ((id, raw) in sources) {
        val source = obj(raw)
        assertWithMessage("$name: $id ha `url` (il TileJSON lo chiede il cancello)").that(source.containsKey("url")).isFalse()
        assertWithMessage("$name: $id").that(source["type"]).isAnyOf("vector", "geojson")
        if (source["type"] == "geojson") assertThat(source["data"] as String).startsWith("asset://map/base/")
      }
      val vector = sources.filterValues { obj(it)["type"] == "vector" }
      if (isOffline(name)) {
        // La mini-mappa: nessuna sorgente online, nessuna tessera.
        assertWithMessage(name).that(vector).isEmpty()
        assertWithMessage(name).that(MapAssets.strings(style(name)).none { it.contains("{z}") }).isTrue()
        assertThat(style(name).containsKey("sprite")).isFalse()
      } else {
        assertWithMessage(name).that(vector.keys).containsExactly("openmaptiles")
        val omt = obj(vector.getValue("openmaptiles"))
        assertThat(list(omt["tiles"])).containsExactly(stableTiles)
        assertThat(omt["minzoom"]).isEqualTo(0.0)
        assertThat(omt["maxzoom"]).isEqualTo(MapHost.MAX_ZOOM.toDouble())
        assertThat(style(name)["sprite"]).isEqualTo("asset://map/sprites/mcg")
      }
      assertThat(style(name)["glyphs"]).isEqualTo("asset://map/glyphs/{fontstack}/{range}.pbf")
    }
  }

  @Test
  fun lUrlStabileDelloStilePassaIlCancelloEVieneRiscrittoSullaVersione() {
    val path = stableTiles.removePrefix("https://${MapHost.TILES}").replace("{z}", "9").replace("{x}", "272").replace("{y}", "187")
    val facts = MapRequestFacts("GET", MapHost.SCHEME, MapHost.TILES, MapHost.PORT, path, null)
    val state = MapNetState(online = true, network = MapNetwork.WIFI, mobileAllowed = false, covered = { false }, currentVersion = "20261020_001001_pt")
    val verdict = TilePolicy.decide(facts, state)
    assertThat(verdict).isInstanceOf(MapVerdict.Allow::class.java)
    assertThat((verdict as MapVerdict.Allow).path).isEqualTo("/planet/20261020_001001_pt/9/272/187.pbf")
  }

  @Test
  fun ogniTextFontHaISuoiGlifiEIlNomeNonHaSpazi() {
    var seen = 0
    for (name in MapAssets.styles) {
      for (layer in layers(style(name))) {
        val layout = obj(layer["layout"] ?: emptyMap<String, Any?>())
        val stack = layout["text-font"] ?: continue
        for (font in list(stack)) {
          seen++
          assertWithMessage("$name ${layer["id"]}: $font").that(fonts).contains(font)
          assertThat(font as String).doesNotContainMatch("[ %]")
          for (range in realRanges) {
            val glyphs = MapAssets.file("glyphs/$font/$range.pbf")
            assertWithMessage("$font/$range").that(glyphs.length()).isGreaterThan(20_000L)
          }
        }
      }
    }
    assertThat(seen).isGreaterThan(40)
  }

  @Test
  fun ognuIntervalloDiGlifiEsisteEIVuotiSonoMessaggiValidi() {
    for (font in fonts) {
      val dir = File(MapAssets.dir, "glyphs/$font")
      val expected = (0 until 65536 step 256).map { "$it-${it + 255}" }
      assertThat(dir.list()!!.map { it.removeSuffix(".pbf") }).containsExactlyElementsIn(expected)
      for (range in expected - realRanges.toSet()) {
        val bytes = File(dir, "$range.pbf").readBytes()
        // Un messaggio `glyphs` con una `fontstack` (nome e intervallo) e nessun glifo: MapLibre lo legge e va avanti.
        assertWithMessage("$font/$range").that(bytes.size).isLessThan(64)
        assertThat(bytes[0].toInt()).isEqualTo(0x0A)
        assertThat(bytes[1].toInt()).isEqualTo(bytes.size - 2)
        assertThat(String(bytes, Charsets.UTF_8)).contains(font)
        assertThat(String(bytes, Charsets.UTF_8)).contains(range)
      }
    }
  }

  @Test
  fun ogniIconaDelloStileEnelloSprite() {
    val sprites = listOf("sprites/mcg.json" to 1.0, "sprites/mcg@2x.json" to 2.0)
    val names = sprites.map { (file, ratio) ->
      val index = obj(MapAssets.json(file))
      for ((icon, raw) in index) assertWithMessage("$file $icon").that(obj(raw)["pixelRatio"]).isEqualTo(ratio)
      index.keys
    }
    assertThat(names[0]).containsExactlyElementsIn(names[1])
    for (png in listOf("sprites/mcg.png", "sprites/mcg@2x.png")) {
      assertThat(MapAssets.file(png).readBytes().copyOf(8)).isEqualTo(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
    }
    var used = 0
    for (name in MapAssets.styles) {
      for (layer in layers(style(name))) {
        val icon = obj(layer["layout"] ?: emptyMap<String, Any?>())["icon-image"] ?: continue
        used++
        assertWithMessage("$name ${layer["id"]}").that(icon).isInstanceOf(String::class.java)
        assertWithMessage("$name ${layer["id"]}: $icon").that(names[0]).contains(icon)
        assertWithMessage("$name: le icone chiare vanno con lo stile scuro").that((icon as String).endsWith("-white")).isEqualTo(isDark(name))
      }
    }
    assertThat(used).isEqualTo(6) // aeroporto e sensi unici, negli stili online
  }

  @Test
  fun nienteEdifici3DNienteLuoghiCommercialiNienteCasa() {
    for (name in MapAssets.styles) {
      val ids = mutableSetOf<String>()
      for (layer in layers(style(name))) {
        val id = layer["id"] as String
        assertThat(ids.add(id)).isTrue()
        assertWithMessage("$name $id").that(layer["type"]).isNotIn(listOf("fill-extrusion", "raster", "hillshade", "heatmap"))
        layer["source-layer"]?.let { assertWithMessage("$name $id").that(allowedSourceLayers).contains(it) }
        assertWithMessage("$name $id").that(id.lowercase().split(Regex("[^a-z0-9]+")).toSet())
          .containsNoneOf("poi", "building", "3d", "extrusion", "casa", "home")
      }
      for (s in MapAssets.strings(MapAssets.json(name))) {
        assertWithMessage("$name: $s").that(s.trim().lowercase()).isNotIn(listOf("casa", "home"))
      }
    }
  }

  @Test
  fun loStileOfflineELOnlineHannoLaCartaDiBaseELeStesseSorgenti() {
    for (name in MapAssets.styles) {
      val sources = obj(style(name)["sources"]).keys
      assertThat(sources).containsAtLeast(
        "base-land", "base-coast", "base-lakes", "base-borders", "base-regions", "base-country-labels", "base-region-labels", "base-cities",
      )
      val used = layers(style(name)).mapNotNull { it["source"] as String? }.toSet()
      assertWithMessage(name).that(sources - used).isEmpty()
    }
  }

  @Test
  fun ilContrastoDelleEtichetteEAlmeno4Virgola5InChiaroEInScuro() {
    for (name in MapAssets.styles) {
      val backgrounds = mutableSetOf<String>()
      val texts = mutableSetOf<String>()
      for (layer in layers(style(name))) {
        val paint = obj(layer["paint"] ?: emptyMap<String, Any?>())
        when (layer["type"]) {
          "background" -> backgrounds += MapAssets.hexColors(paint["background-color"])
          "fill" -> backgrounds += MapAssets.hexColors(paint["fill-color"])
          "line" -> if (layer["source-layer"] == "transportation") backgrounds += MapAssets.hexColors(paint["line-color"])
          "symbol" -> {
            paint["text-color"]?.let {
              assertWithMessage("$name ${layer["id"]}: il colore del testo e' un solo #rrggbb").that(MapAssets.hexColors(it)).hasSize(1)
              texts += MapAssets.hexColors(it)
            }
          }
        }
      }
      assertThat(texts).isNotEmpty()
      assertThat(backgrounds).isNotEmpty()
      for (t in texts) for (b in backgrounds) {
        assertWithMessage("$name: testo $t su $b").that(MapAssets.contrast(t, b)).isAtLeast(4.5)
      }
    }
  }

  @Test
  fun lAcquaEIConfiniSonoInAmetista() {
    val amethyst = mapOf(
      false to "#%06X".format(GalleryAccent.Amethyst.light.toArgb() and 0xFFFFFF),
      true to "#%06X".format(GalleryAccent.Amethyst.dark.toArgb() and 0xFFFFFF),
    )
    for (name in MapAssets.styles) {
      val dark = isDark(name)
      val byId = layers(style(name)).associateBy { it["id"] as String }
      val water = (if (isOffline(name)) byId["bg-ocean"] else byId["water"])!!
      val waterColor = MapAssets.hexColors(obj(water["paint"]).let { it["background-color"] ?: it["fill-color"] }).single()
      assertWithMessage("$name: l'acqua $waterColor").that(MapAssets.hue(waterColor)).isIn(com.google.common.collect.Range.closed(255.0, 285.0))
      // Il confine sottile fra i paesi e' l'Ametista dell'app, quella di GalleryAccent (non un numero scritto qui).
      val border = byId["base-borders"]!!
      assertThat(MapAssets.hexColors(obj(border["paint"])["line-color"])).containsExactly(amethyst.getValue(dark))
      // Sottile: le larghezze dell'interpolazione (dopo "interpolate", il tipo e lo zoom: coppie livello-valore) non superano 1,5 pixel.
      val widths = list(obj(border["paint"])["line-width"]).drop(3).filterIndexed { i, _ -> i % 2 == 1 }.filterIsInstance<Double>()
      assertWithMessage(name).that(widths).isNotEmpty()
      assertWithMessage(name).that(widths.max()).isAtMost(1.5)
    }
  }

  @Test
  fun ogniLivelloHaSoloChiaviCheLaSpecificaDiStileConosce() {
    val paintKeys = mapOf(
      "background" to setOf("background-color", "background-opacity"),
      "fill" to setOf("fill-antialias", "fill-color", "fill-opacity", "fill-outline-color", "fill-translate"),
      "line" to setOf(
        "line-blur", "line-color", "line-dasharray", "line-gap-width", "line-offset", "line-opacity", "line-translate", "line-width",
      ),
      "circle" to setOf("circle-color", "circle-opacity", "circle-radius", "circle-stroke-color", "circle-stroke-opacity", "circle-stroke-width"),
      "symbol" to setOf(
        "icon-opacity", "text-color", "text-halo-blur", "text-halo-color", "text-halo-width", "text-opacity", "text-translate",
      ),
    )
    val layerKeys = setOf("id", "type", "source", "source-layer", "minzoom", "maxzoom", "filter", "layout", "paint", "metadata")
    for (name in MapAssets.styles) {
      for (layer in layers(style(name))) {
        val id = "$name ${layer["id"]}"
        assertWithMessage(id).that(layerKeys).containsAtLeastElementsIn(layer.keys)
        val type = layer["type"] as String
        val paint = obj(layer["paint"] ?: emptyMap<String, Any?>())
        assertWithMessage(id).that(paintKeys.getValue(type)).containsAtLeastElementsIn(paint.keys)
        val min = layer["minzoom"] as Double?
        val max = layer["maxzoom"] as Double?
        if (min != null && max != null) assertWithMessage(id).that(min).isLessThan(max)
        if (type != "background") assertWithMessage(id).that(layer["source"]).isNotNull()
      }
    }
  }

  @Test
  fun ognuStileStaSottoI100KB() {
    for (name in MapAssets.styles) assertThat(MapAssets.file(name).length()).isLessThan(100L * 1024)
  }
}
