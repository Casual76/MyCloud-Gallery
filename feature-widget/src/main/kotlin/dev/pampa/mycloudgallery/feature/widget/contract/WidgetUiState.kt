package dev.pampa.mycloudgallery.feature.widget.contract

import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle

// LO STATO DEL WIDGET (docs/fase10-progetto.md §1.5 "WidgetUiState", D3, D8, D38). Contratto di P0: la Fase 10 decide **cosa** si mostra, la
// fase grafica **come** (dentro il kit di `engine-widget`, P3). Lo stato non porta id di media: le immagini le carica P3 dalle miniature locali
// della cella del piano, e il tocco porta solo i due numeri (D9). Nessun `toString()` mostra titoli, luoghi o date.

/**
 * Una cella da disegnare: [index] la posizione (0 = la copertina in un ricordo), il tipo (il segno "video", E-W17), il giorno locale
 * (`aaaammgg`) per la descrizione della superficie `WIDGET` ("Foto, 12 marzo 2024": solo tipo e data, E-AC11).
 */
data class WidgetUiCell(val index: Int, val kind: MediaKind, val localDay: Int) {
  override fun toString(): String = "WidgetUiCell($index, ${kind.code})"
}

/** Che cosa disegna il widget. Sigillato: la fase grafica decide l'aspetto di ogni caso, non ne aggiunge. */
sealed interface WidgetUiState {
  val size: WidgetSize

  /** Il ricordo di oggi: [cover] la cella 0, [cells] le altre, [title] come dati (il testo lo compone P3; mai un nome, D4). */
  data class Memory(override val size: WidgetSize, val cover: WidgetUiCell, val cells: List<WidgetUiCell>, val title: WidgetTitle) : WidgetUiState {
    override fun toString(): String = "WidgetUiState.Memory(${size.code}, ${cells.size + 1})"
  }

  /** Foto a caso. */
  data class Photos(override val size: WidgetSize, val cells: List<WidgetUiCell>) : WidgetUiState {
    override fun toString(): String = "WidgetUiState.Photos(${size.code}, ${cells.size})"
  }

  /** Blocco dell'app attivo (Q3 (c)): solo media anche sul telefono, **nessun titolo**. */
  data class Locked(override val size: WidgetSize, val cells: List<WidgetUiCell>) : WidgetUiState {
    override fun toString(): String = "WidgetUiState.Locked(${size.code}, ${cells.size})"
  }

  /** Nessuna foto: "Apri MyCloud Gallery" o, con `NOT_CONFIGURED`, "Configura MyCloud Gallery" (una riga, non un errore: E-W1). */
  data class Message(override val size: WidgetSize, val reason: WidgetEmpty) : WidgetUiState

  companion object {
    /**
     * Lo stato di [plan]. Un piano `NOT_READY` non si disegna (resta quello che c'e', E-W4): null. Un ricordo senza celle non esiste (lo
     * impedisce `WidgetPlan`).
     */
    fun of(plan: WidgetPlan): WidgetUiState? {
      val cells = plan.cells.map { WidgetUiCell(it.index, it.kind, it.localDay) }
      return when (val mode = plan.mode) {
        is WidgetMode.Memory -> Memory(plan.size, cells.first(), cells.drop(1), mode.title)
        WidgetMode.Photos -> Photos(plan.size, cells)
        WidgetMode.Locked -> Locked(plan.size, cells)
        is WidgetMode.Message -> if (mode.reason == WidgetEmpty.NOT_READY) null else Message(plan.size, mode.reason)
      }
    }

    /** Il disegno **senza immagini** dello svuotamento (D5, D42): nessuna decodifica, nessuna cella. */
    fun blank(size: WidgetSize, reason: WidgetEmpty): WidgetUiState = Message(size, reason)
  }
}
