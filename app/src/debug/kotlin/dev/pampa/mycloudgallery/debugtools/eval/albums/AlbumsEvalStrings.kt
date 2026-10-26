package dev.pampa.mycloudgallery.debugtools.eval.albums

/**
 * SOLO debug (Fase 7, P12): i testi dello strumento "Valutazione degli album", in un file proprio. Sono costanti e non risorse perche' lo strumento sta tutto in
 * `src/debug/.../eval/albums/` e non entra mai nella release (come `EvalStrings` della ricerca).
 */
internal object AlbumsEvalStrings {
  const val ENTRY_TITLE = "Valutazione degli album"
  const val ENTRY_DETAIL = "Precisione, copertura e viaggi trovati sui tuoi viaggi veri (solo debug)"
  const val SCREEN_SUBTITLE = "Solo debug: le etichette restano sul telefono, mai nel repository"

  const val NOT_BOUND = "Gli album non sono ancora collegati allo strumento (la Libreria non e' pronta o l'app non ha ancora legato la porta). Niente da valutare."
  const val LOAD_ERROR = "Il file delle etichette e' rovinato: ripartiamo da uno vuoto. "

  const val SECTION_HOW = "Come si fa (circa un'ora, anche in piu' volte)"
  const val HOW_1 = "1. Scrivi i viaggi principali degli ultimi 2 anni PRIMA di guardare le proposte: solo i giorni, e il titolo (come lo scriveresti, \"Oslo 2026\") per 3 viaggi da valutare come album."
  const val HOW_2 = "2. Per ogni viaggio con titolo, segna alla cieca le foto del viaggio scorrendo i suoi giorni nella Libreria: non guardare ancora l'album."
  const val HOW_3 = "3. \"Crea l'album\" lo fa in memoria (non scrive nella Libreria). Giudica solo i sicuri e i suggeriti su cui non sei d'accordo, poi cerca i mancanti nei giorni attorno."
  const val HOW_4 = "4. \"Numeri di F88\": precisione dei sicuri (almeno 98%) e copertura (almeno 90%). Per ultimo \"Confronta con le proposte\" (F89): dopo, l'elenco non si cambia piu'."
  const val HOW_5 = "5. I resoconti sono di soli numeri e si copiano in docs/decisioni.md."

  const val SECTION_TRIPS = "I tuoi viaggi"
  const val NO_TRIPS = "Ancora nessun viaggio."
  const val ADD_TITLE = "Nuovo viaggio"
  const val TITLE_FIELD = "Titolo, come lo scriveresti (facoltativo)"
  const val FROM_FIELD = "Primo giorno (aaaa-mm-gg)"
  const val TO_FIELD = "Ultimo giorno (aaaa-mm-gg)"
  const val ADD_BUTTON = "Aggiungi il viaggio"
  const val BAD_DAY = "I giorni vanno scritti come aaaa-mm-gg, il primo non dopo l'ultimo."
  const val LIST_CLOSED = "L'elenco e' chiuso: hai gia' confrontato i viaggi con le proposte. Per rifarlo da capo, cancella le etichette."

  const val SECTION_TOOLS = "Numeri e confronti"
  const val F88_TITLE = "Numeri di F88"
  const val F88_DETAIL = "Precisione dei sicuri e copertura dei viaggi con titolo"
  const val F88_NEEDS_RUN = "Serve almeno un viaggio con titolo su cui hai fatto \"Crea l'album\"."
  const val F89_TITLE = "Confronta con le proposte (F89)"
  const val F89_DETAIL = "Chiude l'elenco dei viaggi e lo confronta con quelli che l'app propone"
  const val F89_CONFIRM = "Dopo il confronto l'elenco dei viaggi non si cambia piu'. Tocca ancora per confrontare."
  const val F89_NOT_AVAILABLE = "I viaggi non sono ancora collegati allo strumento (lo fa l'integrazione degli album): niente da confrontare, l'elenco resta aperto."
  const val REHEARSAL_TITLE = "Prova generale (viaggi sintetici)"
  const val REHEARSAL_DETAIL = "Prova lo strumento su un file di viaggi inventati, con i numeri attesi dalla JVM"
  const val RUNNING = "Calcolo in corso..."

  const val SECTION_REPORTS = "Resoconti salvati"
  const val WIPE_TITLE = "Cancella etichette e resoconti"
  const val WIPE_DETAIL = "A fine misura: toglie le etichette e i resoconti di noBackupFilesDir/eval/albums/. Il pulsante di \"Valutazione della ricerca\" toglie tutta eval/."
  const val BACK = "Indietro"

  const val TRIP_BLIND_TITLE = "1. Segna alla cieca le foto del viaggio"
  const val TRIP_BLIND_DETAIL = "Scorri i giorni del viaggio nella Libreria, senza guardare l'album"
  const val TRIP_RUN_TITLE = "2. Crea l'album e guarda i numeri"
  const val TRIP_RUN_DETAIL = "L'album resta in memoria: la Libreria non cambia"
  const val TRIP_SURE_TITLE = "3. Giudica i sicuri"
  const val TRIP_SUGGESTED_TITLE = "4. Giudica i suggeriti"
  const val TRIP_MISSING_TITLE = "5. Cerca i mancanti nei giorni attorno"
  const val TRIP_MISSING_DETAIL = "Le foto vicine che l'album non ha preso: segna quelle che sono del viaggio"
  const val TRIP_REMOVE = "Togli il viaggio e le sue etichette"
  const val TRIP_SET_TITLE = "Dai o cambia il titolo"
  const val NEEDS_TITLE = "Per creare l'album serve un titolo."
  const val BLIND_FIRST = "Prima segna alla cieca almeno una foto del viaggio: guardare l'album prima di aver detto cosa c'e' premierebbe il motore."
  const val LIST_FIRST = "Scrivi prima almeno un viaggio nell'elenco."
  const val RUN_FAILED = "La corsa si e' fermata: "

  const val BLIND_HINT = "Tocca le foto che sono del viaggio. \"Tutto il giorno\" segna le foto di quel giorno."
  const val ALL_DAY = "Tutto il giorno"
  const val NONE_OF_DAY = "Togli il giorno"
  const val NO_CELLS = "Nessuna foto in questi giorni."
  const val PENDING_HINT = "Tocca per cambiare: da giudicare, del viaggio, non del viaggio."
  const val ALL_RIGHT = "Tutte del viaggio"
  const val ALL_WRONG = "Tutte non del viaggio"
  const val SHOW_ALL = "Mostra tutti"
  const val SHOW_PENDING = "Solo da giudicare"
  const val NOTHING_PENDING = "Niente da giudicare."
  const val MISSING_HINT = "Tocca le foto che sono del viaggio e che l'album non ha preso."

  const val MISSING_NEEDS_RUN = "Prima crea l'album (passo 2): i mancanti sono le foto vicine che l'album non ha preso."
  const val REHEARSAL_NO_FILE = "Nessun file di viaggi sintetici: portalo dentro con adb (vedi LEGGIMI.txt del generatore, docs/decisioni.md \"Fase 7 P12\") e riapri la pagina."
  const val REHEARSAL_RUN = "Esegui la prova generale"

  const val STATE_NONE = "da giudicare"
  const val STATE_RIGHT = "del viaggio"
  const val STATE_WRONG = "non del viaggio"

  fun tripRow(days: Long, marked: Int): String = "$days giorni, $marked foto segnate"

  fun marked(count: Int): String = "$count segnate"
}
