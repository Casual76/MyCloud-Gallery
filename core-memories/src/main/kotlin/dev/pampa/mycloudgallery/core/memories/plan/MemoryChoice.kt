package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.MemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.contract.MemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.MemoryVectors
import dev.pampa.mycloudgallery.core.memories.contract.PhotoPicker
import dev.pampa.mycloudgallery.core.memories.contract.PickResult
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickSpec
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import kotlin.time.Duration

/** Il risultato del passo 5 per un ricordo: la scelta e se i doppioni sono stati cercati senza vettori (`MEMORY_NO_VECTORS`, E-MEM16). */
class Chosen(val result: PickResult, val withoutVectors: Boolean) {
  override fun toString(): String = "Chosen($result, senzaVettori=$withoutVectors)"
}

/**
 * Il passo 5 del giro per **un ricordo** (docs/fase9-progetto.md §2.1, D5-D7, D31): raccogliere i segnali dalle porte (vettori, firma visiva,
 * pixel, album, persone) **una volta** per i candidati di quel ricordo e scegliere le foto, con il filtro finale dei Nascosti. E' il punto
 * d'innesto fra il motore (P6) e la scelta delle foto (P7): il motore non sa come si raccolgono i segnali ne' quale scelta si usa, sa solo che da
 * [choose] esce una [PickResult] (che poi ricontrolla contro i nascosti prima di pubblicare).
 *
 * In produzione: la scelta di P7 (`PickSignalsLoader`, con le misure dei pixel per i soli 200 finalisti, e `VisiblePhotoPicker` sopra
 * `MemoryPhotoPicker`, con la maschera dei Nascosti prima e dopo), legate da :app (P13). Di serie, e nelle prove, [PortsMemoryChoice].
 * [pixelBudget] e' il tempo che resta al giro: la porta dei pixel lo misura con l'orologio monotono.
 */
fun interface MemoryChoice {
  suspend fun choose(cells: List<CandidateCell>, spec: PickSpec, choices: MemoriesChoices, pixelBudget: Duration): Chosen
}

/**
 * La scelta di serie: chiede **a ogni porta una volta sola** i segnali di tutti i candidati del ricordo e scegli con un [PhotoPicker] puro
 * (di norma il riferimento a forza bruta nelle prove). Nessun tetto sui pixel, nessuna maschera dei Nascosti (la applica il motore dopo): e' il
 * comportamento del riferimento (`InMemoryMemories`), su cui si prova la parita' del motore. Un guasto di una porta si propaga: nessun ricordo
 * sulla base di segnali a meta'.
 */
class PortsMemoryChoice(
  private val picker: PhotoPicker,
  private val vectors: MemoryVectors,
  private val lookAlikes: MemoryLookAlikes,
  private val pixels: MemoryPixels,
  private val albums: MemoryAlbumSignals,
  private val people: MemoryPeople,
) : MemoryChoice {

  override suspend fun choose(cells: List<CandidateCell>, spec: PickSpec, choices: MemoriesChoices, pixelBudget: Duration): Chosen {
    val ids = cells.map { it.id }.distinct()
    val vectorMap = vectors.vectors(ids)
    val signals = PickSignals(
      vectors = vectorMap,
      lookAlike = lookAlikes.among(ids),
      pixels = pixels.probe(ids, pixelBudget),
      albums = albums.inManualAlbums(ids),
      people = if (people.available.value && ids.isNotEmpty()) people.peopleIn(ids) else emptyMap(),
      choices = choices,
    )
    return Chosen(picker.pick(cells, spec, signals), withoutVectors = ids.isNotEmpty() && vectorMap.isEmpty())
  }

  override fun toString(): String = "PortsMemoryChoice"
}
