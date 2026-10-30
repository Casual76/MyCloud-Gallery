package dev.pampa.mycloudgallery.feature.map.assets

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import dev.pampa.mycloudgallery.feature.map.assets.MapAssets.obj
import java.io.File
import org.junit.Test

/**
 * Le attribuzioni della mappa (Fase 9, P2; docs/fase9-progetto.md §2.7): le righe brevi sulla mappa ("© OpenStreetMap contributors · OpenMapTiles ·
 * OpenFreeMap" online, "Natural Earth · GeoNames" sulla carta di base) sono quelle degli stili, di `PlacesAttribution` e del NOTICE; il testo completo
 * di Informazioni e licenze (risorse di feature-settings, che non vede core-places) dice le stesse parole delle costanti.
 */
class MapAttributionTest {
  private val aboutStrings = File("../feature-settings/src/main/res/values/strings_about.xml")

  private fun aboutString(name: String): String {
    val xml = aboutStrings.readText(Charsets.UTF_8)
    val match = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)
    assertWithMessage("manca la stringa $name in strings_about.xml").that(match).isNotNull()
    return match!!.groupValues[1].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
  }

  @Test
  fun leRigheBreviSonoQuelleDegliStiliEDelNotice() {
    assertThat(PlacesAttribution.MAP_ONLINE_SHORT).isEqualTo("© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap")
    assertThat(PlacesAttribution.MAP_BASE_SHORT).isEqualTo("Natural Earth · GeoNames")
    val notice = MapAssets.file("NOTICE.txt").readText()
    assertThat(notice).contains(PlacesAttribution.MAP_ONLINE_SHORT)
    assertThat(notice).contains(PlacesAttribution.MAP_BASE_SHORT)
    for (name in MapAssets.styles) {
      val sources = obj(obj(MapAssets.json(name))["sources"])
      if (!name.endsWith("-offline.json")) {
        assertThat(obj(sources["openmaptiles"])["attribution"]).isEqualTo(PlacesAttribution.MAP_ONLINE_SHORT)
      }
      // La carta di base: un'attribuzione per sorgente che la porta, e messe insieme dicono "Natural Earth · GeoNames".
      val base = sources.filterKeys { it.startsWith("base-") }.values.mapNotNull { obj(it)["attribution"] as String? }
      assertThat(base.joinToString(" · ")).isEqualTo(PlacesAttribution.MAP_BASE_SHORT)
    }
  }

  @Test
  fun ilTestoCompletoDiInformazioniELicenzeDiceLeStesseParoleDelleCostanti() {
    assertThat(aboutString("about_map_online_attribution")).isEqualTo(PlacesAttribution.MAP_ONLINE_TEXT)
    assertThat(aboutString("about_map_base_attribution")).isEqualTo(PlacesAttribution.MAP_BASE_TEXT)
    assertThat(aboutString("about_map_style_attribution")).isEqualTo(PlacesAttribution.MAP_STYLE_TEXT)
    assertThat(aboutString("about_license_odbl_url")).isEqualTo(PlacesAttribution.MAP_ODBL_URL)
    // L'attribuzione di GeoNames della Fase 3 non cambia (la verifica `GalleryIntegrationTest` di :app).
    assertThat(PlacesAttribution.TEXT).contains("GeoNames")
  }

  @Test
  fun ognunoDeiPezziHaLaSuaLicenzaNelTestoCompleto() {
    val all = listOf(PlacesAttribution.MAP_ONLINE_TEXT, PlacesAttribution.MAP_BASE_TEXT, PlacesAttribution.MAP_STYLE_TEXT).joinToString(" ")
    for (piece in listOf(
      "OpenStreetMap", "ODbL", "OpenMapTiles", "OpenFreeMap", "Natural Earth", "pubblico dominio", "GeoNames", "Creative Commons Attribuzione 4.0",
      "Positron", "BSD 3-Clause", "CC BY 4.0", "CC BY 3.0", "Noto Sans", "SIL Open Font License", "Maki", "CC0",
    )) assertWithMessage(piece).that(all).contains(piece)
    // Dice che i dati di GeoNames sono stati modificati (lo chiede la CC BY 4.0) e che lo stile lo e' stato.
    assertThat(PlacesAttribution.MAP_BASE_TEXT).contains("ridotti")
    assertThat(PlacesAttribution.MAP_STYLE_TEXT).contains("modificato")
  }
}
