package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.LeaveBehind
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Test

// P12: gli STESSI scenari scritti da P0-B per l'esecutore di riferimento (OperationsTest, FaultMatrixTest, TrashAndHostileDataTest)
// girano sull'esecutore vero, sul cestino vero e sulle cartelle e lo spazio veri (docs/fase4-progetto.md §4.4 riga P12; decisioni.md,
// "Scostamenti" punto 9: "i test valgono per entrambi").

class ProductionOperationsTest : OperationsTest() {
  override fun engine(): KitEngine = KitEngine.PRODUCTION
}

class ProductionFaultMatrixTest : FaultMatrixTest() {
  override fun engine(): KitEngine = KitEngine.PRODUCTION
}

class ProductionTrashAndHostileDataTest : TrashAndHostileDataTest() {
  override fun engine(): KitEngine = KitEngine.PRODUCTION
}

class ProductionLeaveBehindTest : LeaveBehindTest() {
  override fun engine(): KitEngine = KitEngine.PRODUCTION

  // --- Solo l'esecutore vero: le cartelle sono elementi del piano ----------------------------------------------------------

  @Test
  fun leCartelleVuoteSiRicreanoEQuelleConINascostiNonSiTolgono() = runTest {
    populate()
    kit.putDir("test", "V/Vuota")
    val keeping = plan(moveV(secretFile, deepSecretFile))
    assertThat(keeping.items.filter { it.isFolder }.map { it.srcPath.value }).containsExactly("V/Vuota", "V/Gita", "V")
    assertThat(kit.operations.start(keeping, kit.confirm(keeping))).isInstanceOf(StartOutcome.Started::class.java)
    // Una cartella davvero vuota si sposta come sempre (ricreata a destinazione, tolta dall'origine)...
    assertThat(kit.exists("test-comune", "F/V/Vuota")).isTrue()
    assertThat(kit.exists("test", "V/Vuota")).isFalse()
    // ...una che contiene un nascosto resta dov'e', con lui; e il suo nome non arriva nell'area comune da solo.
    assertThat(kit.exists("test", "V/Gita/Segreta")).isTrue()
    assertThat(kit.exists("test-comune", "F/V/Gita/Segreta")).isFalse()
    checkLeft()
  }

  @Test
  fun unaCartellaTuttaDiNascostiSiSpostaVuotaELasciaTuttoDovEra() = runTest {
    kit.putFile("test", "W/uno.jpg", secret)
    kit.putFile("test", "W/Due/due.jpg", deepSecret)
    val leave = setOf(LeaveBehind("test", SharePath.parse("W/uno.jpg")), LeaveBehind("test", SharePath.parse("W/Due/due.jpg")))
    val keeping = plan(OpRequest.Move(listOf(kit.itemOf("test", "W")), "test-comune", null, leave))
    // Resta solo la cartella scelta dall'utente (senza contenuto): una sottocartella di soli nascosti non entra nel piano.
    assertThat(keeping.items.map { it.srcPath.value }).containsExactly("W")
    assertThat(kit.operations.start(keeping, kit.confirm(keeping))).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(kit.filesIn("test-comune").filterNot { it.startsWith(".mycloudgallery") }).isEmpty()
    assertThat(kit.bytes("test", "W/uno.jpg")!!.toList()).isEqualTo(secret.toList())
    assertThat(kit.bytes("test", "W/Due/due.jpg")!!.toList()).isEqualTo(deepSecret.toList())
    assertThat(kit.store.allTrash()).isEmpty()
  }
}
