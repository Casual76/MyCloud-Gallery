package dev.pampa.mycloudgallery.core.map.contract

// I CODICI DELLA DIAGNOSTICA DELLA MAPPA (docs/fase9-progetto.md §2.12, D33). Solo numeri e codici: mappa online si'/no, richieste lasciate
// passare e byte, rifiutate per motivo (`RefuseReason`), regioni (numero e byte, parziali, spazio), punti e senza posizione, coordinate scartate;
// **mai** coordinate, riquadri, tessere `z/x/y`, nomi delle regioni o dei luoghi, l'ultima camera. Il catalogo di feature-diagnostics li raccoglie
// in P13 (sezione `DiagSection.MAP`, voce di stato `MAP_STATE`, gia' nel catalogo da P0), con le chiavi nell'elenco chiuso dell'esportazione.
object MapDiag {
  const val MAP_STATE: String = "MAP_STATE"

  /** Richieste lasciate passare e byte (oggi, 30 giorni); per livello `z` solo nei contatori di debug, mai `x` e `y`. */
  const val MAP_REQUESTS: String = "MAP_REQUESTS"

  /** Richieste rifiutate per motivo: un `HOST` o un `PATH` diverso da zero e' un difetto ("Segnala"). */
  const val MAP_REFUSED: String = "MAP_REFUSED"

  const val MAP_REGION_PARTIAL: String = "MAP_REGION_PARTIAL"
  const val MAP_REGION_SPACE: String = "MAP_REGION_SPACE"

  /** Coordinate scartate (E-MAP7). */
  const val MAP_INVALID_POINTS: String = "MAP_INVALID_POINTS"

  /** Correzioni R2 (31/10): il file delle impostazioni non si e' letto (errore di I/O): la mappa dettagliata vale spenta per il processo e la lettura si riprova. */
  const val MAP_SETTINGS_UNREADABLE: String = "MAP_SETTINGS_UNREADABLE"

  /** Correzioni R2: il file delle impostazioni era rovinato: la mappa dettagliata resta spenta finche' l'utente non la riaccende. */
  const val MAP_SETTINGS_DAMAGED: String = "MAP_SETTINGS_DAMAGED"

  /** Correzioni R5: MapLibre non ha spostato il suo database in `noBackupFilesDir/map/`: la mappa resta sullo stile offline, senza richieste. */
  const val MAP_CACHE_UNMOVED: String = "MAP_CACHE_UNMOVED"

  /** Correzioni MA-1: l'indice dei punti non si e' potuto leggere (il database non ha risposto): la mappa lo dice e propone di riprovare. */
  const val MAP_INDEX_FAILED: String = "MAP_INDEX_FAILED"

  val ALL: List<String> = listOf(
    MAP_STATE, MAP_REQUESTS, MAP_REFUSED, MAP_REGION_PARTIAL, MAP_REGION_SPACE, MAP_INVALID_POINTS,
    MAP_SETTINGS_UNREADABLE, MAP_SETTINGS_DAMAGED, MAP_CACHE_UNMOVED, MAP_INDEX_FAILED,
  )
}
