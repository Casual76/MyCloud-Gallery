package dev.pampa.mycloudgallery.core.nasops

import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine

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
