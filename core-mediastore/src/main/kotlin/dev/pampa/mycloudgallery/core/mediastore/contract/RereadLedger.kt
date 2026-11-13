package dev.pampa.mycloudgallery.core.mediastore.contract

/**
 * Il collegamento «avviso di Android -> rilettura del telefono finita» per chi deve restare chiuso finche' la rilettura non ha coperto l'avviso
 * (oggi il nastro delle foto in analisi, `AnalysisVisibilityBoundary` di `:app`; poi forse il nastro del backup).
 *
 * Chi riceve gli avvisi di Android li **numera** ([noticeOrder]); la sincronizzazione del telefono, quando comincia un giro, legge il numero **prima di
 * toccare il MediaStore** e, se il giro finisce bene con l'accesso completo, lo **dichiara** ([rereadFinished]) insieme al `change_seq` del catalogo
 * di quel momento: un giro partito dopo l'avviso N ha letto il telefono dopo il cambiamento, uno partito prima no. Il chiamante riapre quando
 * l'istantanea che la Libreria pubblica ha raggiunto quel `change_seq` (la rilettura e' nel catalogo e il catalogo e' nell'istantanea): nessuna attesa a tempo.
 *
 * Le due funzioni sono richiami **leggeri**: una lettura di variabile e qualche campo, mai I/O ne' attese (sono chiamate dal giro di sincronizzazione).
 */
interface RereadLedger {

  /** Il numero d'ordine dell'ultimo avviso di Android arrivato (0 = nessuno finora). */
  fun noticeOrder(): Long

  /**
   * Una rilettura **completa e con accesso completo** e' finita bene. [order] e' il numero d'ordine letto **all'inizio** di quel giro; [catalogSeq] e' il
   * `change_seq` del catalogo alla fine (tutto cio' che il giro ha scritto ha un numero minore o uguale).
   */
  fun rereadFinished(order: Long, catalogSeq: Long)
}
