package dev.pampa.mycloudgallery.core.library.contract

import androidx.compose.runtime.Immutable

/**
 * Lo stato dell'**analisi delle foto** (il calcolo AI per la ricerca) ridotto per i posti che lo mostrano (Pacchetto X,
 * 12/11/2026): la testata della pagina "Ricerca e modelli", la pagina "Analisi delle foto" e la riga nel pannello della
 * nuvola della Libreria. **Un solo stato per tutti i posti**, come [LibraryBackupStatus] per il backup: `:app` lo compone
 * da ruolo, stato del motore, lavoro che resta, copertura e verdetto delle regole, e ogni posto lo dice con le sue parole
 * una volta sola.
 *
 * Sta in `core-library` come gli altri stati della Libreria: `feature-library` non vede core-ai e `feature-search` riceve
 * lo stesso riassunto dietro la sua porta. Solo numeri veri e codici: mai nomi di file, cartelle, share o account. Un
 * totale che non si sa resta null (niente numero finto). I volti (Persone) **non** ci sono: hanno il loro lavoro e la loro
 * pagina; qui e' solo cio' che rende una foto "pronta per la ricerca" (vettori, e il testo letto dove attivo).
 *
 * I numeri [ready]/[total] sono le foto che la ricerca conosce gia' sul totale delle foto (la copertura, senza nascosti):
 * gli stessi della testata di "Ricerca e modelli", cosi' i due posti non dicono mai due numeri diversi. [remaining] e'
 * invece il lavoro che resta **a questo dispositivo** (le sue aree e i media del solo telefono): c'e' solo dove il
 * dispositivo analizza.
 */
@Immutable
sealed interface LibraryAnalysisStatus {

  /** Niente da dire (la ricerca non e' legata): nessuna riga, nessun segno. */
  data object Idle : LibraryAnalysisStatus

  /**
   * Questo dispositivo **non analizza** (il ruolo Indicizzatore e' spento qui): le foto le analizza un altro dispositivo e
   * i risultati arrivano dal NAS. [ready] su [total] dice quante sono gia' pronte per la ricerca qui.
   */
  data class NotHere(val ready: Int?, val total: Int?) : LibraryAnalysisStatus

  /** L'analisi e' spenta nelle impostazioni delle attivita': non parte finche' non la si riaccende. */
  data object Disabled : LibraryAnalysisStatus

  /** Il dispositivo analizza ma gli manca il modello di analisi: serve scaricarlo (la pagina dice come). */
  data object ModelMissing : LibraryAnalysisStatus

  /** Sta analizzando **adesso**. [etaMinutes] null = non ancora stimabile. */
  data class Running(val remaining: Int?, val etaMinutes: Int?, val ready: Int?, val total: Int?) : LibraryAnalysisStatus

  /** C'e' lavoro e le regole lo lascerebbero partire, ma in questo momento non sta girando (fra una tappa e l'altra). */
  data class Queued(val remaining: Int?, val ready: Int?, val total: Int?) : LibraryAnalysisStatus

  /** C'e' lavoro ma le regole lo fermano adesso: [reason] e' il motivo vero, mai "attesa delle regole". */
  data class Paused(val reason: LibraryAnalysisWait, val remaining: Int?, val ready: Int?, val total: Int?) : LibraryAnalysisStatus

  /** Tutto pronto: ogni foto e' pronta per la ricerca (e a questo dispositivo non resta niente da analizzare). */
  data object AllReady : LibraryAnalysisStatus

  /**
   * Un problema dell'analisi con la sua azione (poco spazio, il motore non parte): [code] e' il codice stabile della
   * Diagnostica (`AiDiag`, maiuscole e trattini bassi), come lo stato ricordato su file.
   */
  data class Problem(val code: String) : LibraryAnalysisStatus {
    init {
      require(CODE.matches(code)) { "Codice di problema dell'analisi non valido." }
    }

    private companion object {
      val CODE = Regex("^[A-Z0-9_]{1,64}$")
    }
  }
}

/** Perche' l'analisi aspetta, in forma tipizzata: ogni posto lo dice con una riga corta nelle sue risorse. */
enum class LibraryAnalysisWait {
  /** Fuori dalla fascia oraria del calcolo. */
  NIGHT_WINDOW,

  /** Serve il telefono in carica. */
  CHARGING,

  /** Batteria sotto la soglia. */
  BATTERY,

  /** Il risparmio energetico di Android e' acceso. */
  POWER_SAVER,

  /** Il telefono e' troppo caldo: riprende da solo quando si raffredda. */
  THERMAL,

  /** Serve una rete consentita (Wi-Fi). */
  WIFI,

  /** Il calcolo vuole il Wi-Fi di casa. */
  HOME_WIFI,

  /** Il NAS non risponde adesso. */
  NAS,

  /** Il tetto dei dati del mese e' raggiunto. */
  DATA_CAP,

  /** Le anteprime non sono ancora pronte (la preparazione non le ha ancora fatte). */
  PREPARATION,

  /** Un limite di Android (le quote dei lavori): riprende al prossimo giro. */
  ANDROID_LIMIT,

  /** Questo profilo non e' attivo adesso. */
  PROFILE,

  /** Un altro motivo del motore delle regole (la Diagnostica lo dice per esteso). */
  OTHER,
}

/** La parte dell'anello piena (0..1) dai numeri veri; null se il totale non si sa (niente numero finto). */
fun LibraryAnalysisStatus.progressOrNull(): Float? {
  val (ready, total) = when (this) {
    is LibraryAnalysisStatus.NotHere -> ready to total
    is LibraryAnalysisStatus.Running -> ready to total
    is LibraryAnalysisStatus.Queued -> ready to total
    is LibraryAnalysisStatus.Paused -> ready to total
    LibraryAnalysisStatus.AllReady -> return 1f
    else -> return null
  }
  if (ready == null || total == null || total <= 0) return null
  return (ready.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Pacchetto XN (13/11/2026, decisione dell'utente): **a quando risale l'ultima sincronizzazione dell'indice dell'analisi con il NAS**, per la
 * sezione dell'analisi nel pannello della nuvola. Additivo e separato da [LibraryAnalysisStatus] (che resta com'e'): lo compone `:app` dalla testa
 * `ai` propria pubblicata sul NAS (`ai_own_head.published_at`, ora del NAS), l'istante che il motore salva gia' a ogni pubblicazione.
 */
@Immutable
sealed interface LibraryIndexSync {

  /** In questa configurazione l'indice non va sul NAS (l'analisi resta sul telefono): la riga non c'e'. */
  data object NotApplicable : LibraryIndexSync

  /** Potrebbe andare sul NAS, ma non c'e' ancora andato. */
  data object Never : LibraryIndexSync

  /** L'ultima pubblicazione sul NAS, all'istante [atNasMillis] (ora del NAS). */
  data class At(val atNasMillis: Long) : LibraryIndexSync
}
