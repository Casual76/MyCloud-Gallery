package dev.pampa.mycloudgallery.debugtools.eval

/**
 * SOLO debug (Fase 6, P9): i testi dello strumento "Valutazione della ricerca", in un file proprio. Sono costanti e non risorse perche' P9 non
 * tocca le risorse di `:app`: lo strumento sta tutto in `src/debug/.../eval/` e non entra mai nella release.
 */
internal object EvalStrings {
  const val ENTRY_TITLE = "Valutazione della ricerca"
  const val ENTRY_DETAIL = "Etichetta le tue 50 query e calcola recall, MRR e soglie (solo debug)"
  const val SCREEN_SUBTITLE = "Solo debug: le etichette restano sul telefono, mai nel repository"

  const val NOT_BOUND = "La ricerca non e' ancora collegata allo strumento (lo fa P11). Niente da valutare."
  const val LOAD_ERROR = "Il file delle etichette e' rovinato: ripartiamo da uno vuoto. "

  const val SECTION_QUERIES = "Le tue query"
  const val SECTION_HOW = "Come si fa"
  const val HOW_1 = "1. Scrivi una query e scegli la categoria (otto categorie, circa 50 query in tutto)."
  const val HOW_2 = "2. Prima segna le foto attese sfogliando la Libreria per anno e luogo, senza guardare la ricerca."
  const val HOW_3 = "3. Poi aggiungi dai primi 100 risultati del motore e del coseno grezzo (il metodo a pozzo): cio' che non segni non e' atteso."
  const val HOW_4 = "4. Segna un campione di documenti e no. Poi lancia la corsa: il resoconto e' fatto di soli numeri."

  const val ADD_TITLE = "Nuova query"
  const val QUERY_FIELD = "Testo della query"
  const val ADD_BUTTON = "Aggiungi la query"
  const val REMOVE_BUTTON = "Togli la query e le sue etichette"

  const val BROWSE_TITLE = "1. Sfoglia la Libreria"
  const val BROWSE_DETAIL = "Per anno e luogo, prima di guardare i risultati"
  const val POOL_ENGINE_TITLE = "2a. Dal motore (primi 100)"
  const val POOL_RAW_TITLE = "2b. Dal coseno grezzo (primi 100)"
  const val POOL_DETAIL = "Tocca le foto che sono davvero quello che cercavi"
  const val FROM_YEAR = "Dal"
  const val TO_YEAR = "Al"
  const val PLACE = "Luogo (facoltativo)"
  const val SEARCH_BUTTON = "Mostra"
  const val NO_CELLS = "Nessuna foto con una chiave di contenuto per questo filtro."

  const val DOCS_TITLE = "Documenti: campione"
  const val DOCS_DETAIL = "Per ogni foto: e' un documento (scontrino, ricevuta, foglio) o no"
  const val DOCS_YES = "Documento"
  const val DOCS_NO = "Non e' un documento"
  const val DOCS_NEXT = "Altre 30 foto"

  const val RUN_TITLE = "Lancia la corsa"
  const val RUN_DETAIL = "Metriche, calibrazione di Zc, Zl e Smin, ordine e Documenti"
  const val RUNNING = "Calcolo in corso..."
  const val RUN_NEEDS_LABELS = "Serve almeno una query con delle foto attese."
  const val REPORT_SAVED = "Resoconto salvato come "

  const val OCR_TITLE = "Lettura di prova (set OCR)"
  const val OCR_DETAIL = "Legge il set sintetico copiato con adb e scrive l'errore per carattere"
  const val OCR_OFF = "La lettura del testo e' spenta: attivala in Ricerca e modelli per misurare ML Kit."

  const val WIPE_TITLE = "Cancella etichette e resoconti"
  const val WIPE_DETAIL = "A fine misura: toglie tutto da noBackupFilesDir/eval/"

  const val BACK = "Indietro"

  fun queryRow(id: String, expected: Int): String = "$id: $expected foto attese"

  fun marked(count: Int): String = "$count segnate"
}
