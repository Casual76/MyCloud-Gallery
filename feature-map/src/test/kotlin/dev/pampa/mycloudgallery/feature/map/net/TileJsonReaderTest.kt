package dev.pampa.mycloudgallery.feature.map.net

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * La lettura del TileJSON di OpenFreeMap (P1, D22): la forma vera (`linux_host_lib/metadata_to_tilejson.py` di OpenFreeMap, letta il 30/10:
 * TileJSON 3.0.0 minimizzato con `tiles` = `https://tiles.openfreemap.org/planet/<versione>/{z}/{x}/{y}.pbf`) da' la versione; ogni altra no.
 * Robolectric solo per `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class TileJsonReaderTest {

  @Test
  fun laFormaVeraDaLaVersione() {
    val real = """{"tilejson":"3.0.0","tiles":["https://tiles.openfreemap.org/planet/20261022_001001_pt/{z}/{x}/{y}.pbf"],""" +
      """"vector_layers":[{"id":"water","fields":{"class":"String"}}],"attribution":"<a href=\"https://openfreemap.org\">OpenFreeMap</a>",""" +
      """"bounds":[-180,-85.0511,180,85.0511],"center":[0,0,1],"maxzoom":14,"minzoom":0,"name":"OpenMapTiles","version":"3.15.0"}"""
    assertThat(TileJsonReader.version(real)).isEqualTo("20261022_001001_pt")
    // Le barre "escaped" del JSON sono la stessa stringa.
    assertThat(TileJsonReader.version("""{"tiles":["https:\/\/tiles.openfreemap.org\/planet\/20261022_001001_pt\/{z}\/{x}\/{y}.pbf"]}""")).isEqualTo("20261022_001001_pt")
  }

  @Test
  fun ogniAltraFormaNo() {
    for (text in listOf(
      """{"tiles":["http://tiles.openfreemap.org/planet/v1/{z}/{x}/{y}.pbf"]}""",
      """{"tiles":["https://tiles.openfreemap.org.evil/planet/v1/{z}/{x}/{y}.pbf"]}""",
      """{"tiles":["https://tiles.openfreemap.org/planet/../{z}/{x}/{y}.pbf"]}""",
      """{"tiles":["https://tiles.openfreemap.org/planet/../x/{z}/{x}/{y}.pbf"]}""",
      """{"tiles":["https://tiles.openfreemap.org/planet/${"x".repeat(65)}/{z}/{x}/{y}.pbf"]}""",
      """{"tiles":["https://tiles.openfreemap.org/planet/v1/{z}/{y}/{x}.pbf"]}""",
      """{"tiles":["https://tiles.openfreemap.org/other/v1/{z}/{x}/{y}.pbf"]}""",
      """{"tiles":[1]}""", """{"tiles":null}""", """{}""", """{"tiles":["""", "", "null",
    )) {
      assertWithMessage(text).that(TileJsonReader.version(text)).isNull()
    }
  }
}
