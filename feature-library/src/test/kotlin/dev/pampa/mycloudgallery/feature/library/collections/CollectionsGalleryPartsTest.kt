package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Map
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import org.junit.Test

/**
 * I1 (fase I): come una sezione si divide nella galleria delle Raccolte. Le forme dichiarate vincono; senza, si ricavano dai dati (la mappa, una foto o
 * dei volti, altrimenti una piastrella). Nascosti ed Eliminati di recente sono sempre piastrelle: mai una foto, mai un conto.
 */
class CollectionsGalleryPartsTest {

  private fun entry(code: String, shape: EntryShape? = null, cover: MediaId? = null, faces: List<FaceLocator> = emptyList(), coverPlace: Int = 0, coverPlaces: List<Int> = emptyList()) =
    CollectionEntry(code, code, "", Icons.Rounded.Face, FluidTone.Neutral, cover = cover, faces = faces, coverPlace = coverPlace, coverPlaces = coverPlaces, shape = shape)

  private val face = FaceLocator(ContentKey("%064x".format(1)), 0, FaceBox(10, 10, 90, 90))

  private fun section(vararg rows: SectionRow) = CollectionsSection("people", SectionText.Raw("Persone"), rows.toList())

  @Test
  fun leFormeSiRicavanoDaiDati() {
    assertThat(entry("a", cover = MediaId.random()).resolvedShape).isEqualTo(EntryShape.Card)
    assertThat(entry("b", faces = listOf(face)).resolvedShape).isEqualTo(EntryShape.Card)
    assertThat(entry("c", coverPlace = 7).resolvedShape).isEqualTo(EntryShape.Card)
    assertThat(entry("d", coverPlaces = listOf(7, 8)).resolvedShape).isEqualTo(EntryShape.Map)
    assertThat(entry("e").resolvedShape).isEqualTo(EntryShape.Tile)
    // Dichiarata vince sui dati: "Tutte le persone" ha tre volti ma e' il "tutti" della sezione.
    assertThat(entry("f", shape = EntryShape.More, faces = listOf(face)).resolvedShape).isEqualTo(EntryShape.More)
  }

  @Test
  fun unaSezioneSiDivideInMappaSchedeTuttiStatiEPiastrelle() {
    val parts = section(
      SectionRow.Entry(entry("map", shape = EntryShape.Map, coverPlaces = listOf(1))),
      SectionRow.Entry(entry("p1", faces = listOf(face))),
      SectionRow.Entry(entry("p2", faces = listOf(face))),
      SectionRow.Entry(entry("all", shape = EntryShape.More)),
      SectionRow.Entry(entry("others", shape = EntryShape.Notice)),
      SectionRow.Entry(entry("tile")),
      SectionRow.Kind(CollectionRow(CollectionKind.HIDDEN, null)),
    ).parts()
    assertThat(parts.map?.code).isEqualTo("map")
    assertThat(parts.cards.map { it.code }).containsExactly("p1", "p2").inOrder()
    assertThat(parts.more?.code).isEqualTo("all")
    assertThat(parts.notices.map { it.code }).containsExactly("others")
    assertThat(parts.tiles.map { it.key }).containsExactly("entry:tile", "kind:hidden").inOrder()
  }

  @Test
  fun unaSecondaMappaOUnSecondoTuttiNonSiPerdono() {
    val parts = section(
      SectionRow.Entry(entry("m1", shape = EntryShape.Map)),
      SectionRow.Entry(entry("m2", shape = EntryShape.Map)),
      SectionRow.Entry(entry("a1", shape = EntryShape.More)),
      SectionRow.Entry(entry("a2", shape = EntryShape.More)),
    ).parts()
    assertThat(parts.map?.code).isEqualTo("m1")
    assertThat(parts.tiles.map { it.key }).containsExactly("entry:m2")
    assertThat(parts.more?.code).isEqualTo("a1")
    assertThat(parts.notices.map { it.code }).containsExactly("a2")
  }

  @Test
  fun lePiastrelleDeiPostiProtettiNonHannoMaiUnaFoto() {
    val parts = CollectionsSection("hidden", SectionText.Raw("Nascosti"), listOf(SectionRow.Kind(CollectionRow(CollectionKind.HIDDEN, null)))).parts()
    assertThat(parts.cards).isEmpty()
    assertThat(parts.map).isNull()
    assertThat(parts.tiles).hasSize(1)
  }

  @Test
  fun leCopertineDaPreparareSonoSoloQuelleConUnaFoto() {
    val s = section(
      SectionRow.Entry(entry("p", faces = listOf(face))),
      SectionRow.Entry(entry("m", cover = MediaId.random())),
      SectionRow.Entry(entry("map", coverPlaces = listOf(3), shape = EntryShape.Map)),
      SectionRow.Entry(entry("nothing")),
      SectionRow.Kind(CollectionRow(CollectionKind.HIDDEN, null)),
    )
    assertThat(s.coverEntries().map { it.code }).containsExactly("p", "m", "map").inOrder()
  }

  @Test
  fun talkBackDiceTuttoQuelloCheLoSchermoNonScrive() {
    val e = CollectionEntry("x", "Toscana, agosto 2025", "Viaggio · 640 elementi", Icons.Rounded.Map, FluidTone.Neutral, trailing = "Nuovo", caption = "Viaggio")
    assertThat(e.spoken()).isEqualTo("Toscana, agosto 2025, Viaggio · 640 elementi, Nuovo")
    val ops = CollectionEntry("ops", "Operazioni in corso", "2 in corso", Icons.Rounded.Map, FluidTone.Success, badge = 2)
    assertThat(ops.spoken()).isEqualTo("Operazioni in corso, 2 in corso, 2")
  }
}
