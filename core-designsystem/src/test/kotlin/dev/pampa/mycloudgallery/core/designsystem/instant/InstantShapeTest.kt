package dev.pampa.mycloudgallery.core.designsystem.instant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Avvio istantaneo (28/10): la forma di una pagina per lo scheletro del primo ingresso. Solo numeri (tipo del blocco e due conteggi), tagliati ai
 * massimi; una forma che non si capisce vale "quella di serie" ([InstantShapeMemory.shapeOf]).
 */
class InstantShapeTest {

  @Test
  fun unaFormaSiScriveESiRilegge() {
    val shape = InstantShape.of(InstantBlock.Header, InstantBlock.Rows(4), InstantBlock.Header, InstantBlock.Tiles(9, 3))
    assertThat(shape.encode()).containsExactly(1, 0, 0, 2, 4, 0, 1, 0, 0, 3, 9, 3).inOrder()
    assertThat(InstantShape.decode(shape.encode())).isEqualTo(shape)
  }

  @Test
  fun iConteggiSiTaglianoAiMassimi() {
    val shape = InstantShape.of(InstantBlock.Rows(10_000), InstantBlock.Tiles(0, 0))
    assertThat(shape.blocks).containsExactly(InstantBlock.Rows(InstantShape.MAX_ITEMS), InstantBlock.Tiles(1, 1)).inOrder()
  }

  @Test
  fun numeriCheNonSonoUnaFormaNonValgono() {
    assertThat(InstantShape.decode(listOf(1, 0))).isNull()
    assertThat(InstantShape.decode(listOf(9, 0, 0))).isNull()
    assertThat(InstantShape.decode(listOf(2, 0, 0))).isNull()
    assertThat(InstantShape.decode(listOf(2, -1, 0))).isNull()
    assertThat(InstantShape.decode(listOf(3, 4, 0))).isNull()
    assertThat(InstantShape.decode(List(3 * (InstantShape.MAX_BLOCKS + 1)) { if (it % 3 == 0) 1 else 0 })).isNull()
  }

  @Test
  fun senzaMemoriaLaFormaDiSerie() {
    val default = InstantShape.of(InstantBlock.Rows(3))
    InstantShapeMemory.install({ null }, { _, _ -> })
    assertThat(InstantShapeMemory.shapeOf("raccolte", default)).isEqualTo(default)
    val remembered = HashMap<String, List<Int>>()
    InstantShapeMemory.install(remembered::get) { code, values -> remembered[code] = values }
    InstantShapeMemory.remember("raccolte", InstantShape.of(InstantBlock.Tiles(6, 3)))
    InstantShapeMemory.remember("vuota", InstantShape.EMPTY)
    assertThat(InstantShapeMemory.shapeOf("raccolte", default)).isEqualTo(InstantShape.of(InstantBlock.Tiles(6, 3)))
    assertThat(remembered).doesNotContainKey("vuota")
    // Numeri rovinati: la forma di serie.
    remembered["raccolte"] = listOf(7, 7)
    assertThat(InstantShapeMemory.shapeOf("raccolte", default)).isEqualTo(default)
  }
}
