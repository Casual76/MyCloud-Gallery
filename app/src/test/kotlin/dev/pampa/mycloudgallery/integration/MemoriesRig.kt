package dev.pampa.mycloudgallery.integration

import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.memories.MemoriesGraph
import dev.pampa.mycloudgallery.memories.MemoriesWiring
import dev.pampa.mycloudgallery.utility.SearchShotVectors
import dev.pampa.mycloudgallery.work.MemoryNotifySlot

// L'ATTREZZATURA DEI RICORDI SUL GRAFO VERO (Fase 9, P13): le stesse righe di `MemoriesModule` (`MemoriesWiring.build`) su un dispositivo di prova delle
// persone (`FacesNode`: galleria, Fase 6, album e persone veri, sul NAS simulato). Cambia solo "oggi" (fisso) e il primo disegno, che nei test non c'e'.

/** I ricordi montabili del dispositivo, come in produzione. [today] di serie quello delle persone. */
internal fun FacesNode.memoriesGraph(today: Today = FACES_TODAY, slot: MemoryNotifySlot = MemoryNotifySlot()): MemoriesGraph = MemoriesWiring.build(
  context = device.context,
  scope = device.graph.scope,
  gallery = mounted.gallery,
  albums = albums,
  faces = faces,
  lookup = bridge,
  vectors = SearchShotVectors(data = { mounted.stack.search.data }),
  today = today,
  cutoutsFolder = mounted.stack.cutoutFence::folder,
  slot = slot,
  afterFirstDraw = {},
)
