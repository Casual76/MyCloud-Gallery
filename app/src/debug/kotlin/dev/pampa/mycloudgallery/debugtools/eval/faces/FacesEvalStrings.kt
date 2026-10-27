package dev.pampa.mycloudgallery.debugtools.eval.faces

/**
 * SOLO debug (Fase 8, P11): i testi dello strumento "Valutazione delle persone", in un file proprio. Sono costanti e non risorse perche' lo strumento sta tutto in
 * `src/debug/.../eval/faces/` e non entra mai nella release (come `EvalStrings` della ricerca e `AlbumsEvalStrings` degli album). Mai un nome o un id qui dentro:
 * le righe dei gruppi dicono "Gruppo 3", non chi e'.
 */
internal object FacesEvalStrings {
  const val ENTRY_TITLE = "Valutazione delle persone"
  const val ENTRY_DETAIL = "Purezza dei gruppi, soglie e copertura sui tuoi volti veri (solo debug)"
  const val SCREEN_SUBTITLE = "Solo debug: le etichette restano sul telefono, mai nel repository"

  const val NOT_BOUND = "Le persone non sono ancora collegate allo strumento: lo fa l'integrazione, oppure Persone non è attiva su questo dispositivo. Niente da valutare."
  const val LOAD_ERROR = "Il file delle etichette è rovinato: ripartiamo da uno vuoto. "
  const val NO_SESSION = "La sessione non c'è."
  const val NO_GROUP = "Il gruppo non è fra quelli scelti."

  const val SECTION_HOW = "Come si fa (circa 40 minuti, in due momenti)"
  const val HOW_1 = "1. Sessione 1, calibrazione (circa 20 minuti): ti mostro 40 gruppi scelti a caso fra quelli che Persone mostra, i più grandi più spesso. Per ognuno vedi i volti (fino a 60, più i 10 meno simili al gruppo): tocca quelli che NON sono di quella persona, oppure \"Gruppo misto\"."
  const val HOW_2 = "2. Se due gruppi sono la stessa persona, dillo con \"È la stessa persona di un altro gruppo\": serve a contare i gruppi divisi."
  const val HOW_3 = "3. \"Soglie proposte\" sceglie le soglie del raggruppamento con la regola del progetto (2.5). Scrivile in GroupingParams e chiedi un ricalcolo completo."
  const val HOW_4 = "4. Sessione 2, misura (circa 20 minuti, dopo il ricalcolo): tutti i gruppi se sono al più 80, altrimenti quelli con un nome più un campione. Stessa griglia. Obiettivo: purezza almeno 97%."
  const val HOW_5 = "5. I resoconti sono di soli numeri e si copiano in docs/decisioni.md. Le etichette sono posti di volti: restano sul telefono e se ne vanno con l'azzeramento."

  const val SESSION_1 = "Sessione 1: calibrazione"
  const val SESSION_2 = "Sessione 2: misura"
  const val SESSION_1_DETAIL = "40 gruppi a caso, con peso alla dimensione"
  const val SESSION_2_DETAIL = "Da fare dopo il ricalcolo con le soglie nuove"
  const val NOT_STARTED = "Non iniziata"
  const val START = "Inizia"
  const val CONTINUE = "Continua"
  const val STARTING = "Scelgo i gruppi..."
  const val NO_GROUPS = "Persone non ha ancora formato gruppi da valutare."
  const val START_FAILED = "Non riesco a leggere i gruppi: "

  const val SECTION_NUMBERS = "Numeri e soglie"
  const val NUMBERS_1_TITLE = "Numeri della sessione 1"
  const val NUMBERS_2_TITLE = "Numeri della sessione 2"
  const val NUMBERS_DETAIL = "Purezza, errori per volto, gruppi divisi e copertura (solo numeri)"
  const val PROPOSAL_TITLE = "Soglie proposte dalla sessione 1"
  const val PROPOSAL_DETAIL = "La regola del 2.5: T_join, T_join_weak, M, T_edge e T_birth"
  const val PROPOSAL_NEEDS = "Servono gruppi della sessione 1 guardati e non misti."
  const val NUMBERS_NEED_SESSION = "La sessione non è ancora iniziata."
  const val RUNNING = "Calcolo in corso..."
  const val RUN_FAILED = "Il calcolo si è fermato: "

  const val SECTION_REPORTS = "Resoconti salvati"
  const val WIPE_TITLE = "Cancella etichette e resoconti"
  const val WIPE_CONFIRM = "Tocca ancora per cancellare le etichette e i resoconti: non si torna indietro."
  const val WIPE_DETAIL = "A fine misura: toglie le etichette e i resoconti di noBackupFilesDir/eval/faces/. L'azzeramento dell'app toglie tutta eval/."
  const val BACK = "Indietro"

  const val GROUPS_HINT = "Tocca un gruppo per guardarne i volti."
  const val CLOSE_TITLE = "Chiudi la sessione"
  const val CLOSE_CONFIRM = "Dopo la chiusura le etichette non cambiano più. Tocca ancora per chiudere."
  const val CLOSED = "Sessione chiusa: le etichette non cambiano più."
  const val RESTART_TITLE = "Ricomincia da capo"
  const val RESTART_CONFIRM = "Toglie questa sessione e sceglie altri gruppi. Tocca ancora per ricominciare."
  const val SHOW_NUMBERS = "Numeri di questa sessione"

  const val GROUP_HINT = "Tocca i volti che NON sono di questa persona. I dieci in fondo sono i meno simili al gruppo: è lì che gli errori sono più probabili."
  const val LEAST_SIMILAR = "I 10 meno simili al gruppo"
  const val ALL_FACES = "Volti del gruppo"
  const val GROUP_GONE = "Questo gruppo non c'è più (unito, dimenticato o senza foto mostrabili)."
  const val LOADING = "Carico i volti..."
  const val ALL_RIGHT = "Sono tutti giusti, ho finito"
  const val NEXT_GROUP = "Prossimo gruppo da guardare"
  const val MIXED = "Gruppo misto"
  const val MIXED_OFF = "Non è un gruppo misto"
  const val MIXED_HINT = "Un gruppo misto conta come sbagliato, ma non si sa quali volti: i suoi volti non entrano negli errori per volto."
  const val SAME_TITLE = "È la stessa persona di un altro gruppo"
  const val SAME_DETAIL = "Per contare i gruppi della stessa persona separati"
  const val UNREVIEW = "Rimetti da guardare"
  const val ALL_DONE = "Hai guardato tutti i gruppi di questa sessione."
  const val SAME_HINT = "Tocca i gruppi che sono la stessa persona di quello che stai guardando."
  const val SAME_NONE = "Non ci sono altri gruppi."

  const val STATE_RIGHT = "giusto"
  const val STATE_WRONG = "segnato come non suo"

  fun sessionProgress(reviewed: Int, total: Int, closed: Boolean): String =
    "$reviewed gruppi su $total guardati" + if (closed) " · chiusa" else ""

  fun groupRow(index: Int, faces: Int): String = "Gruppo $index · $faces volti"

  fun groupStatus(reviewed: Boolean, mixed: Boolean, wrong: Int, linked: Int): String = when {
    mixed -> "misto"
    !reviewed -> "da guardare"
    wrong == 0 -> "guardato, giusto"
    wrong == 1 -> "guardato, 1 volto sbagliato"
    else -> "guardato, $wrong volti sbagliati"
  } + when (linked) {
    0 -> ""
    1 -> " · stessa persona di un altro gruppo"
    else -> " · stessa persona di altri $linked gruppi"
  }

  fun faceDescription(position: Int, total: Int, wrong: Boolean): String =
    "Volto $position di $total, " + if (wrong) STATE_WRONG else STATE_RIGHT

  fun groupTitle(index: Int, total: Int): String = "Gruppo $index di $total"

  fun groupSubtitle(faces: Int, shown: Int, wrong: Int): String = "$faces volti nel gruppo, $shown mostrati, ${marked(wrong)}"

  fun marked(count: Int): String = if (count == 1) "1 segnato" else "$count segnati"
}
