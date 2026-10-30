package dev.pampa.mycloudgallery.debugtools.eval.memories

/**
 * SOLO debug (Fase 9, P11): i testi dello strumento "Valutazione dei ricordi", in un file proprio. Sono costanti e non risorse perche' lo strumento sta tutto in
 * `src/debug/.../eval/memories/` e non entra mai nella release (come `AlbumsEvalStrings` e `FacesEvalStrings`).
 */
internal object MemoriesEvalStrings {
  const val ENTRY_TITLE = "Valutazione dei ricordi"
  const val ENTRY_DETAIL = "Ricordi graditi, foto da togliere e doppioni sui tuoi ricordi veri (solo debug)"
  const val SCREEN_SUBTITLE = "Solo debug: i giudizi restano sul telefono, mai nel repository"

  const val NOT_BOUND = "I ricordi non sono ancora collegati allo strumento (la Libreria non e' pronta o l'app non ha ancora legato la porta). Niente da valutare."
  const val LOAD_ERROR = "Il file dei giudizi e' rovinato: ripartiamo da uno vuoto. "
  const val NO_MEMORIES = "Non ci sono ricordi da giudicare: la Libreria non e' pronta o ha troppo poche foto negli ultimi 45 giorni."

  const val SECTION_HOW = "Come si fa (circa 25 minuti, anche in piu' volte)"
  const val HOW_1 = "1. \"Genera i ricordi\" li fa in memoria come se oggi fosse ognuno degli ultimi 45 giorni: non scrive lo scaffale ne' le tue scelte."
  const val HOW_2 = "2. Per ogni ricordo guarda titolo e foto e rispondi con un tocco: Bello, Cosi cosi, Da non mostrare (col motivo, se vuoi)."
  const val HOW_3 = "3. Tocca le foto che NON dovevano esserci e di' perche': doppione, mossa o buia, screenshot o documento, non c'entra, triste."
  const val HOW_4 = "4. \"Numeri\" da' ricordi graditi (almeno 70%), da non mostrare (al piu' 3%), foto da togliere (1 su 12 in media) e doppioni (3% delle foto)."
  const val HOW_5 = "5. I resoconti sono di soli numeri e si copiano in docs/decisioni.md. Con Q6 (a) non c'e' nessun filtro dei ricordi tristi: sono solo una misura."

  const val SECTION_RUN = "La corsa"
  const val RUN_BUTTON = "Genera i ricordi degli ultimi 45 giorni"
  const val RUN_AGAIN_CONFIRM = "Ricominciare perde i giudizi di adesso. Tocca ancora per confermare."
  const val RUNNING = "Calcolo in corso..."
  const val RUN_FAILED = "La corsa si e' fermata: "
  const val RUN_DONE = "Ricordi da giudicare: "
  const val SESSION_HEADER = "Ricordi della sessione"
  const val NO_SESSION = "Nessuna sessione: genera i ricordi."
  const val HIDDEN_DROPPED = "Qualcosa e' stato nascosto o eliminato dopo la corsa: l'ho tolto dallo strumento e dai numeri."
  const val SECTION_TOOLS = "Numeri"
  const val NUMBERS_TITLE = "Numeri di F125"
  const val NUMBERS_DETAIL = "Ricordi graditi, da non mostrare, foto da togliere, doppioni: soli numeri, salvati come resoconto"
  const val NUMBERS_NEEDS_JUDGEMENT = "Giudica almeno un ricordo."
  const val SECTION_REPORTS = "Resoconti salvati"
  const val WIPE_TITLE = "Cancella giudizi e resoconti"
  const val WIPE_DETAIL = "A fine misura: toglie i giudizi e i resoconti di noBackupFilesDir/eval/memories/. L'azzeramento dell'app toglie tutta eval/."
  const val BACK = "Indietro"

  const val PENDING = "da giudicare"
  const val VERDICT_GOOD = "Bello"
  const val VERDICT_SO_SO = "Cosi cosi"
  const val VERDICT_DONT_SHOW = "Da non mostrare"
  const val WHY_SAD = "Triste"
  const val WHY_WRONG = "Sbagliato"
  const val WHY_BORING = "Noioso"
  const val WHY_HINT = "Motivo (facoltativo) di \"Da non mostrare\":"
  const val NO_WHY = "Nessun motivo"

  const val FAULT_DUPLICATE = "Doppione"
  const val FAULT_BLURRY = "Mossa o buia"
  const val FAULT_SCREEN = "Screenshot o documento"
  const val FAULT_UNRELATED = "Non c'entra"
  const val FAULT_SAD = "Triste"
  const val FAULT_REMOVE = "Togli il segno"
  const val ALL_RIGHT = "Sono tutte giuste"
  const val GRID_HINT = "Tocca una foto che non doveva esserci, poi scegli perche'."
  const val SELECTED_HINT = "Foto scelta: perche' non doveva esserci?"
  const val NEXT_PENDING = "Prossimo da giudicare"
  const val NO_MORE = "Hai giudicato tutti i ricordi."
  const val CLEAR_JUDGEMENT = "Togli il giudizio"

  fun kind(code: String): String = MemoriesEvalKinds.label(code)

  fun verdict(verdict: EvalVerdict?): String = when (verdict) {
    null -> PENDING
    EvalVerdict.GOOD -> VERDICT_GOOD
    EvalVerdict.SO_SO -> VERDICT_SO_SO
    EvalVerdict.DONT_SHOW -> VERDICT_DONT_SHOW
  }

  fun why(why: EvalWhy): String = when (why) {
    EvalWhy.SAD -> WHY_SAD
    EvalWhy.WRONG -> WHY_WRONG
    EvalWhy.BORING -> WHY_BORING
  }

  fun fault(fault: EvalFault): String = when (fault) {
    EvalFault.DUPLICATE -> FAULT_DUPLICATE
    EvalFault.BLURRY_OR_DARK -> FAULT_BLURRY
    EvalFault.SCREENSHOT_OR_DOC -> FAULT_SCREEN
    EvalFault.UNRELATED -> FAULT_UNRELATED
    EvalFault.SAD -> FAULT_SAD
  }

  /** La riga di un ricordo nella lista: "anni fa, 12 foto: Bello" (mai il titolo ne' il giorno: sono sulla pagina del ricordo). */
  fun row(index: Int, kind: String, photos: Int, judgement: EvalJudgement?, faults: Int): String =
    "Ricordo ${index + 1} (${kind(kind)}), $photos foto: ${verdict(judgement?.verdict)}" + if (faults > 0) ", $faults da togliere" else ""

  fun cellDescription(index: Int, total: Int, fault: EvalFault?): String = "Foto $index di $total" + (fault?.let { ", " + fault(it) } ?: "")
}
