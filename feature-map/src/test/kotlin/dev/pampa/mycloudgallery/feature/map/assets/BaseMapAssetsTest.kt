package dev.pampa.mycloudgallery.feature.map.assets

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.feature.map.assets.MapAssets.list
import dev.pampa.mycloudgallery.feature.map.assets.MapAssets.obj
import java.io.File
import java.util.zip.Deflater
import org.junit.Test

/**
 * La carta di base e i suoi file (Fase 9, P2; docs/fase9-progetto.md §4.4 riga P2, D25): peso, forma, nomi, nessuna "Casa", le impronte che lo strumento
 * (`tools/maps/base/build_base.py`) ha scritto e i glifi che coprono ogni nome. Un file toccato a mano, o un file nuovo, fa fallire la prova.
 */
class BaseMapAssetsTest {
  private fun features(name: String): List<Map<String, Any?>> {
    val root = obj(MapAssets.json("base/$name"))
    assertThat(root["type"]).isEqualTo("FeatureCollection")
    return list(root["features"]).map { obj(it) }
  }

  private fun props(f: Map<String, Any?>) = obj(f["properties"])
  private fun geometry(f: Map<String, Any?>) = obj(f["geometry"])

  /** I caratteri che i glifi dentro l'app coprono: 0-255, 256-511, 768-1023, 8192-8447. */
  private fun covered(c: Char): Boolean = c.code in 0..511 || c.code in 768..1023 || c.code in 8192..8447

  @Test
  fun laCartaDiBasePesaAlPiu4MBSiaScrittaChePerLAPK() {
    var raw = 0L
    var deflated = 0L
    for (name in MapAssets.baseFiles) {
      val bytes = MapAssets.file("base/$name").readBytes()
      raw += bytes.size
      val d = Deflater(6)
      d.setInput(bytes)
      d.finish()
      val out = ByteArray(8192)
      while (!d.finished()) deflated += d.deflate(out)
      d.end()
    }
    assertThat(File(MapAssets.dir, "base").list()!!.toList()).containsExactlyElementsIn(MapAssets.baseFiles)
    assertWithMessage("scritta: $raw B").that(raw).isAtMost(4_000_000L)
    assertWithMessage("compressa: $deflated B").that(deflated).isAtMost(2_000_000L)
  }

  @Test
  fun leImpronteDeiFileSonoQuelleScritteDagliStrumenti() {
    val base = obj(obj(MapAssets.jsonOf(MapAssets.baseRegistry))["file"])
    assertThat(base.keys).containsExactlyElementsIn(MapAssets.baseFiles)
    for ((name, raw) in base) {
      val bytes = MapAssets.file("base/$name").readBytes()
      assertWithMessage(name).that(MapAssets.sha256(bytes)).isEqualTo(obj(raw)["sha256"])
      assertWithMessage(name).that(bytes.size.toDouble()).isEqualTo(obj(raw)["byte"])
    }
    val style = obj(obj(MapAssets.jsonOf(MapAssets.styleRegistry))["file"])
    for ((name, raw) in style) {
      val bytes = MapAssets.file(name).readBytes()
      assertWithMessage(name).that(MapAssets.sha256(bytes)).isEqualTo(obj(raw)["sha256"])
    }
    // Stili, sprite, licenze e NOTICE: tutti nel registro (i glifi vuoti sono riassunti, i quattro intervalli veri sono elencati).
    assertThat(style.keys).containsAtLeast("style-chiaro.json", "style-scuro.json", "sprites/mcg.png", "NOTICE.txt", "LICENSE-NaturalEarth.txt")
    assertThat(style.keys.count { it.startsWith("glyphs/") }).isEqualTo(8)
  }

  @Test
  fun ogniGeoJsonHaLaFormaAttesaECoordinateAlPiuACinqueDecimali() {
    val allowedKeys = mapOf(
      "land.geojson" to emptySet(), "coast.geojson" to emptySet(), "lakes.geojson" to emptySet(),
      "borders.geojson" to setOf("d"), "regions.geojson" to setOf("lvl"),
      "country-labels.geojson" to setOf("name", "rank"), "region-labels.geojson" to setOf("name"),
      "cities.geojson" to setOf("name", "rank", "cap"),
    )
    val geometryTypes = mapOf(
      "land.geojson" to "MultiPolygon", "coast.geojson" to "MultiLineString", "lakes.geojson" to "MultiPolygon",
      "borders.geojson" to "MultiLineString", "regions.geojson" to "MultiLineString",
    )
    for (name in MapAssets.baseFiles) {
      val text = MapAssets.file("base/$name").readText()
      assertWithMessage("$name: coordinate oltre i 5 decimali").that(Regex("""\d\.\d{6,}""").containsMatchIn(text)).isFalse()
      assertWithMessage("$name: `-0`").that(text.contains("-0,") || text.contains("-0]")).isFalse()
      val all = features(name)
      assertThat(all).isNotEmpty()
      for (f in all) {
        assertThat(f["type"]).isEqualTo("Feature")
        assertWithMessage("$name: proprieta' non previste").that(allowedKeys.getValue(name)).containsAtLeastElementsIn(props(f).keys)
        val g = geometry(f)
        geometryTypes[name]?.let { assertThat(g["type"]).isEqualTo(it) } ?: assertThat(g["type"]).isEqualTo("Point")
      }
    }
    // Nessuna coordinata fuori dal mondo: le terre non attraversano l'antimeridiano (Natural Earth le taglia a 180).
    for (name in listOf("land.geojson", "coast.geojson", "lakes.geojson", "borders.geojson", "regions.geojson")) {
      val numbers = Regex("""\[(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)]""").findAll(MapAssets.file("base/$name").readText())
      var count = 0
      for (m in numbers) {
        count++
        assertThat(m.groupValues[1].toDouble()).isIn(com.google.common.collect.Range.closed(-180.0, 180.0))
        assertThat(m.groupValues[2].toDouble()).isIn(com.google.common.collect.Range.closed(-90.0, 90.0))
      }
      assertWithMessage(name).that(count).isGreaterThan(100)
    }
  }

  @Test
  fun iNomiDellaCartaCiSonoSonoItalianiENonSonoMaiCasa() {
    val countries = features("country-labels.geojson").map { props(it)["name"] as String }
    assertThat(countries.size).isAtLeast(200)
    assertThat(countries).containsAtLeast("Italia", "Francia", "Svizzera", "Austria", "Slovenia", "San Marino", "Città del Vaticano")
    val regions = features("region-labels.geojson").map { props(it)["name"] as String }
    assertThat(regions).containsExactly(
      "Piemonte", "Valle d’Aosta", "Lombardia", "Trentino-Alto Adige", "Veneto", "Friuli-Venezia Giulia", "Liguria", "Emilia-Romagna",
      "Toscana", "Umbria", "Marche", "Lazio", "Abruzzo", "Molise", "Campania", "Puglia", "Basilicata", "Calabria", "Sicilia", "Sardegna",
    )
    val cities = features("cities.geojson")
    assertThat(cities.size).isAtLeast(10_000)
    val names = cities.map { props(it)["name"] as String }
    // Nomi italiani (non "Rome", "Florence", "Naples"): arrivano dal dataset dei luoghi dell'app.
    assertThat(names).containsAtLeast("Roma", "Milano", "Firenze", "Napoli", "Torino", "Genova", "Londra", "Parigi", "Monaco di Baviera")
    assertThat(names).containsNoneOf("Rome", "Florence", "Naples", "Milan")
    for (n in countries + regions + names) {
      assertWithMessage(n).that(n.all { covered(it) }).isTrue()
      assertWithMessage(n).that(n.trim().lowercase()).isNotIn(listOf("casa", "home", ""))
    }
    // Le capitali di Stato hanno il livello 3 o meglio (si vedono da z4,6), il livello 5 non e' scritto.
    for (c in cities) {
      val rank = props(c)["rank"]
      assertWithMessage(c.toString()).that(rank == null || rank in listOf(1.0, 2.0, 3.0, 4.0)).isTrue()
      if (props(c)["cap"] != null) assertWithMessage(c.toString()).that(rank != null && (rank as Double) <= 3.0).isTrue()
    }
  }

  @Test
  fun laCartaNonHaUnLivelloUnaEtichettaOUnPuntoCasa() {
    // Nessun file della carta nomina la casa: ne' i nomi (sopra) ne' le proprieta' (chiavi chiuse sopra) ne' il testo. (Il divieto di leggere la casa
    // dai moduli della Fase 9 e' di Phase9ArchitectureTest: questo file non scrive i nomi dei suoi campi.)
    for (name in MapAssets.baseFiles) {
      val text = MapAssets.file("base/$name").readText()
      for (word in listOf("\"Casa\"", "\"casa\"", "\"Home\"", "\"home\"", "\"Casa mia\"")) {
        assertWithMessage("$name: $word").that(text.contains(word)).isFalse()
      }
    }
  }

  @Test
  fun leLicenzeELeAttribuzioniDiOgniPezzoCiSono() {
    val notice = MapAssets.file("NOTICE.txt").readText()
    for (piece in listOf(
      "Natural Earth", "pubblico dominio", "GeoNames", "CC BY 4.0", "OpenFreeMap", "OpenMapTiles", "OpenStreetMap contributors", "ODbL",
      "Positron", "Dark", "BSD 3-Clause", "SIL Open Font License", "Noto Sans", "Maki", "CC0",
    )) assertWithMessage(piece).that(notice).contains(piece)
    for ((file, word) in mapOf(
      "LICENSE-NaturalEarth.txt" to "public domain", "LICENSE-NotoSans.txt" to "SIL OPEN FONT LICENSE", "LICENSE-Positron.txt" to "BSD 3-Clause",
      "LICENSE-Dark.txt" to "BSD 3-Clause", "LICENSE-OpenFreeMapStyles.txt" to "MIT",
    )) assertWithMessage(file).that(MapAssets.file(file).readText()).contains(word)
  }
}
