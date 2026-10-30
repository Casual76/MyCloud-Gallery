package dev.pampa.mycloudgallery.core.memories.plan

import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.PickSpec
import dev.pampa.mycloudgallery.core.memories.pick.PickSignalsLoader
import dev.pampa.mycloudgallery.core.memories.pick.VisiblePhotoPicker
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import kotlin.time.Duration

/**
 * Il passo 5 del giro con la scelta di P7 (docs/fase9-progetto.md §2.1, §2.2, D5-D7, D31): i segnali li raccoglie [PickSignalsLoader] **una volta per
 * ricordo** (solo cio' che serve: i candidati gia' esclusi da D5 non si chiedono a nessuna porta; le misure dei pixel solo per i 200 finalisti, entro il
 * tempo che resta; le persone falliscono chiuse quando ci sono scelte "Mostra meno" o "Mai"), la scelta la fa [VisiblePhotoPicker] sopra
 * `MemoryPhotoPicker` con la maschera dei Nascosti prima e dopo (fallisce chiuso, al piu' 3 giri). Il motore ricontrolla comunque i nascosti prima di
 * pubblicare.
 *
 * Si lega in :app (P13): `MemoryPhotoChoice(PickSignalsLoader(vettori, firma, pixel, album, persone), VisiblePhotoPicker(MemoryPhotoPicker(), hiddenMask))`.
 * Il motore non nomina `HiddenMask`: lo ricevono solo `VisiblePhotoPicker` (P7) e chi lo lega.
 */
class MemoryPhotoChoice(
  private val loader: PickSignalsLoader,
  private val picker: VisiblePhotoPicker,
) : MemoryChoice {

  override suspend fun choose(cells: List<CandidateCell>, spec: PickSpec, choices: MemoriesChoices, pixelBudget: Duration): Chosen {
    val signals = loader.load(cells, spec, choices, pixelBudget)
    // "Senza vettori" (E-MEM16): ci sono candidati e nessuno ha un vettore.
    return Chosen(picker.pick(cells, spec, signals), withoutVectors = cells.isNotEmpty() && signals.vectors.isEmpty())
  }

  override fun toString(): String = "MemoryPhotoChoice"
}
