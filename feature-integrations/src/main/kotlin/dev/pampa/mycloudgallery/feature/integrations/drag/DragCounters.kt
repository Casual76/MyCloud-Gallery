package dev.pampa.mycloudgallery.feature.integrations.drag

import dev.pampa.mycloudgallery.core.library.contract.DragRefusal
import dev.pampa.mycloudgallery.feature.integrations.share.CodeCounters
import dev.pampa.mycloudgallery.feature.integrations.share.CounterCode

// Fase 10 (P6, D39, §2.13 `DRAG_OUT`, `DRAG_IN`, `DRAG_TEMP_CLEANED`): cio' che il trascinamento conta per la Diagnostica. **Solo numeri con un codice fisso**: mai un
// nome, un id, un percorso (C21). Un insieme chiuso, come `ReceiveCounter` e gli altri. **Non** sono dentro `FlowCounters`: le chiavi nuove devono stare nell'elenco chiuso
// di `ExportKeys` e il collegamento alla Diagnostica e' di P13 (`DragCounters.snapshot()` da unire a `FlowCounters.snapshot()` quando le chiavi ci sono).

enum class DragCounter(override val code: String) : CounterCode {
  /** Un trascinamento verso un'altra app e' pronto (gli elementi in [OUT_ITEMS], i solo-NAS in [OUT_NAS_ONLY]). */
  OUT_READY("drag_out_ready"),
  OUT_ITEMS("drag_out_items"),
  OUT_NAS_ONLY("drag_out_nas_only"),

  /** Con "Rimuovi la posizione": le copie senza posizione preparate per un trascinamento. */
  OUT_STRIPPED("drag_out_stripped"),

  /** Non e' partito (il dito e' stato alzato, il sistema non l'ha accettato): copie e id tolti subito. */
  OUT_DISCARDED("drag_out_discarded"),

  /** Il prestito di un trascinamento e' finito (app in secondo piano da 10 minuti, troppi trascinamenti, azzeramento). */
  OUT_RELEASED("drag_out_released"),

  OUT_REFUSED_TOO_MANY("drag_out_refused_too_many"),
  OUT_REFUSED_TOO_LARGE("drag_out_refused_too_large"),
  OUT_REFUSED_HIDDEN("drag_out_refused_hidden"),
  OUT_REFUSED_LOCATION("drag_out_refused_location"),
  OUT_REFUSED_NAS("drag_out_refused_nas"),
  OUT_REFUSED_LOCKED("drag_out_refused_locked"),
  OUT_REFUSED_UNAVAILABLE("drag_out_refused_unavailable"),

  /** Un trascinamento in arrivo preso (il foglio della ricezione si apre) e uno rifiutato prima di aprire niente. */
  IN_DROPPED("drag_in_dropped"),
  IN_REFUSED("drag_in_refused"),

  /** Un trascinamento nostro che passa sopra le nostre destinazioni: ignorato. */
  IN_OWN_IGNORED("drag_in_own_ignored"),

  /** Copie temporanee del trascinamento tolte all'avvio del processo (`ShareCache.sweepAtStart`). */
  TEMP_CLEANED("drag_temp_cleaned"),
  ;

  companion object {
    /** Il contatore di un rifiuto: un solo posto, cosi' ogni [DragRefusal] ha il suo. */
    fun refused(reason: DragRefusal): DragCounter = when (reason) {
      DragRefusal.TOO_MANY -> OUT_REFUSED_TOO_MANY
      DragRefusal.TOO_LARGE -> OUT_REFUSED_TOO_LARGE
      DragRefusal.HIDDEN -> OUT_REFUSED_HIDDEN
      DragRefusal.LOCATION_NOT_REMOVABLE -> OUT_REFUSED_LOCATION
      DragRefusal.NAS_UNREACHABLE -> OUT_REFUSED_NAS
      DragRefusal.LOCKED -> OUT_REFUSED_LOCKED
      DragRefusal.UNAVAILABLE -> OUT_REFUSED_UNAVAILABLE
    }
  }
}

/** I contatori del trascinamento, in memoria. */
object DragCounters {
  private val counters = CodeCounters(DragCounter.entries)

  fun add(counter: DragCounter, by: Long = 1) = counters.add(counter, by)

  fun get(counter: DragCounter): Long = counters.get(counter)

  /** Codice → numero, tutti (anche a zero), per la Diagnostica. */
  fun snapshot(): Map<String, Long> = counters.snapshot()

  fun reset() = counters.reset()
}
