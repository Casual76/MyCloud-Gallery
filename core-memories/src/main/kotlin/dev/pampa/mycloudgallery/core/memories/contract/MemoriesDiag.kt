package dev.pampa.mycloudgallery.core.memories.contract

// I CODICI DELLA DIAGNOSTICA DEI RICORDI E DELLE NOTIFICHE (docs/fase9-progetto.md §2.12, D33). Solo numeri e codici: ricordi sullo scaffale,
// nuovi, ultimo giro (giorni fa), durata, esclusi per motivo, notifiche saltate per motivo; **mai** titoli, luoghi, date o id dei ricordi, nomi
// o id di persone, l'ora scelta. Il catalogo di feature-diagnostics li raccoglie in P13 (sezione `DiagSection.MEMORIES`, voce di stato
// `MEMORY_STATE`, gia' nel catalogo da P0), con le chiavi nell'elenco chiuso dell'esportazione (`ExportKeys`). Un codice nuovo si aggiunge qui e
// nel catalogo, mai altrove.
object MemoriesDiag {
  /** La voce di stato della sezione: ricordi sullo scaffale, nuovi, ultimo giro (giorni fa), durata. */
  const val MEMORY_STATE: String = "MEMORY_STATE"

  /** Lo scaffale era rovinato e si e' rigenerato. */
  const val MEMORY_SHELF_RESET: String = "MEMORY_SHELF_RESET"

  /** Doppioni cercati senza vettori (firma o finestra di 3 minuti, E-MEM16). */
  const val MEMORY_NO_VECTORS: String = "MEMORY_NO_VECTORS"

  /** Esclusi per motivo ([DropReason]): solo conteggi. */
  const val MEMORY_DROPPED: String = "MEMORY_DROPPED"

  /** Notifica non mandata, per motivo ([NotifySkip]): "Apri le impostazioni" per permesso e canale. */
  const val NOTIFY_SKIPPED: String = "NOTIFY_SKIPPED"

  /** Consegne oltre H+1 h negli ultimi 14 giorni (conteggio): decide il piano B di D20. */
  const val NOTIFY_LATE: String = "NOTIFY_LATE"

  /** Notifiche tolte per un nascosto o uno scarto (D19). */
  const val NOTIFY_REVOKED: String = "NOTIFY_REVOKED"

  /** Tutti i codici, nell'ordine del §2.12: il catalogo di feature-diagnostics deve averli tutti (prova di P13). */
  val ALL: List<String> = listOf(MEMORY_STATE, MEMORY_SHELF_RESET, MEMORY_NO_VECTORS, MEMORY_DROPPED, NOTIFY_SKIPPED, NOTIFY_LATE, NOTIFY_REVOKED)
}

/** Perche' una notifica non e' partita (`NOTIFY_SKIPPED`): solo il codice va nella Diagnostica. */
enum class NotifySkip(override val code: String) : dev.pampa.mycloudgallery.core.model.StableCode {
  DISABLED("disabled"),
  PERMISSION("permission"),
  CHANNEL("channel"),
  FREQUENCY("frequency"),
  NOTHING("nothing"),
  OUTSIDE_WINDOW("outside_window"),
  ALREADY_OPENED("already_opened"),
  ALREADY_TODAY("already_today"),
}
