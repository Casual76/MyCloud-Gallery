package dev.pampa.mycloudgallery.feature.widget.frame

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.feature.widget.contract.WidgetUiState

// IL FOTOGRAMMA DEL WIDGET (Fase 10, P3, docs/fase10-progetto.md §2.1 passo 8, §1.5). Lo stato sigillato (`WidgetUiState`, P0) dice **cosa** si disegna; il fotogramma
// ci aggiunge cio' che serve davvero a disegnare e che lo stato non porta: le bitmap delle miniature locali, la descrizione di ogni cella (superficie `WIDGET` di
// `core-describe`: solo tipo e data), il titolo gia' composto e il testo del messaggio. E' fatto **fuori** dalla composizione (le decodifiche e il registro sono
// sospensioni) e il disegno non sceglie niente: nessun id di media, nessun nome, nessun luogo escono da qui. `toString()` senza testi.

/** Una cella: la bitmap (null = cella vuota, mai un segnaposto fatto di dati) e la descrizione per TalkBack ("Foto, 12 marzo 2024"). */
class WidgetFrameCell(val bitmap: Bitmap?, val description: String) {
  override fun toString(): String = "WidgetFrameCell(bitmap=${bitmap != null})"
}

/**
 * Tutto cio' che il widget [slot] disegna. [cells] stanno **per indice di cella** (la 0 e' la copertina di un ricordo o la prima foto) e sono tante quante
 * quelle dello [state] (`Memory`: la copertina piu' le altre; `Photos`/`Locked`: le sue; `Message`: nessuna). [title] e' il titolo del ricordo gia' composto (solo per
 * `Memory`, mai un nome, mai con il blocco dell'app); [message] il testo di un `Message` ("Apri MyCloud Gallery" o "Configura MyCloud Gallery").
 */
class WidgetFrame(
  val slot: WidgetSlot,
  val state: WidgetUiState,
  val cells: List<WidgetFrameCell>,
  val title: String?,
  val message: String?,
) {
  init {
    require(cells.size == cellCount(state)) { "Le celle del fotogramma non sono quelle dello stato." }
    require(title == null || state is WidgetUiState.Memory) { "Il titolo e' solo del ricordo." }
    require((message != null) == (state is WidgetUiState.Message)) { "Il messaggio e' solo dei messaggi." }
  }

  override fun toString(): String = "WidgetFrame($slot, $state)"

  companion object {
    /** Quante celle ha uno stato (la copertina conta). */
    fun cellCount(state: WidgetUiState): Int = when (state) {
      is WidgetUiState.Memory -> 1 + state.cells.size
      is WidgetUiState.Photos -> state.cells.size
      is WidgetUiState.Locked -> state.cells.size
      is WidgetUiState.Message -> 0
    }
  }
}
