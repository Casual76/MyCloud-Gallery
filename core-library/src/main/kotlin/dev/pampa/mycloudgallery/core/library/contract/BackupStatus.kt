package dev.pampa.mycloudgallery.core.library.contract

import androidx.compose.runtime.Immutable

/**
 * Lo stato del backup nel piede della Libreria, terza voce sotto rete e NAS (Fase 5, `docs/fase5-progetto.md` §1.5, §2.6, D16):
 * **cosa** dire e **quando**; **come** dirlo e' del filone grafico (`GalleryFooterStatus`, §12.5 della grafica). Contratto di P0:
 * P5 lo mette in `LibraryTabState.backup` e lo disegna, `:app` (P7) lo compone da `BackupStatus` di core-backup.
 *
 * Sta in `core-library` come [LibraryNasStatus] (scelta di P0 della 4A): la Libreria **non vede** core-backup (§1.1, nessun dato del
 * backup arriva qui se non come questo riassunto). Per questo i problemi viaggiano con il **codice stabile** di `BackupProblemCode`
 * ([Problem.code]) e non con il tipo: il test di P5 confronta i testi del piede con l'elenco dei codici di core-backup.
 *
 * Solo numeri e codici: mai nomi di file, cartelle, share o account. Istanti in **ora del NAS**; durate in ms (`etaMillis` null =
 * non ancora stimabile).
 */
@Immutable
sealed interface LibraryBackupStatus {

  /** Backup non configurato, nessuna sorgente scelta o niente da dire: la voce non si mostra. */
  data object Idle : LibraryBackupStatus

  /** D12: "Backup in prova" · "Si scrive solo nelle share di prova". */
  data object TestOnly : LibraryBackupStatus

  /** §2.11: prima del primo backup si cerca cosa c'e' gia' sul NAS ("Sto cercando…", "Sto confrontando N foto…"). */
  data class Comparing(val pending: Int?) : LibraryBackupStatus

  /** "Tutto al sicuro" · "Oggi alle 03:12": l'ultimo giro finito all'istante [atNasMillis] (null = non noto). */
  data class AllSafe(val atNasMillis: Long?) : LibraryBackupStatus

  /** "312 in coda" · "Attesa carica": [items] media aspettano per [wait]. */
  data class Queued(val items: Int, val wait: LibraryBackupWait) : LibraryBackupStatus

  /** "Primo backup: 1.240 di 6.658" · "circa 1 h 20 min". */
  data class FirstBackup(val done: Int, val total: Int, val etaMillis: Long?) : LibraryBackupStatus

  /** Un giro (non il primo) in corso: "In corso: 12 di 40". */
  data class Running(val done: Int, val total: Int, val etaMillis: Long?) : LibraryBackupStatus

  /**
   * Il backup e' **fermo** e ci sono (o possono esserci) foto ancora da salvare: non e' "niente da dire" e non e' mai "tutto al sicuro" (OA-10,
   * review della beta: con [Idle] la nuvola, guardando solo il NAS, mostrava la spunta di "tutto salvato").
   *
   * Con [byUser] l'utente ha toccato "Ferma" e la pausa dura finche' non tocca "Riprendi": "Backup in pausa" · il tasto porta alla pagina Backup.
   * Senza, il lavoro lo ha fermato Android (termica, memoria, fine della tappa): "Backup interrotto" · "Riprende da solo".
   */
  data class Paused(val byUser: Boolean) : LibraryBackupStatus

  /**
   * Il problema piu' grave ("Quota esaurita" · "Chiedi all'admin del NAS"): [code] e' il codice stabile di `BackupProblemCode`
   * (core-backup, §2.6), [count] quanti media riguarda (0 = tutto il backup, es. NAS irraggiungibile).
   */
  data class Problem(val code: String, val count: Int) : LibraryBackupStatus {
    init {
      require(CODE.matches(code)) { "Codice di problema del backup non valido." }
      require(count >= 0) { "Conteggio negativo." }
    }

    private companion object {
      val CODE = Regex("^[a-z][a-z0-9_]{1,31}$")
    }
  }
}

/** Perche' il backup aspetta, per la frase breve del piede ("Attesa carica", "Attesa Wi-Fi di casa"…). */
enum class LibraryBackupWait {
  /** Il preset vuole il telefono in carica (Notte). */
  CHARGING,

  /** Serve un Wi-Fi (Bilanciato, Wi-Fi sempre). */
  WIFI,

  /** Serve il Wi-Fi di casa (video, proposta 1). */
  HOME_WIFI,

  /** Fuori dalla fascia notturna. */
  NIGHT_WINDOW,

  /** Batteria sotto la soglia. */
  BATTERY,

  /** Il NAS non e' raggiungibile adesso. */
  NAS,

  /** Al prossimo giro programmato. */
  NEXT_RUN,

  /** D-13: il NAS tiene ancora occupato un file del backup; si riprova tra poco, da soli. */
  NAS_BUSY,

  /** FAB-4: il risparmio energetico di Android e' acceso e l'impostazione lo rispetta: riprende quando lo si spegne. */
  POWER_SAVER,

  /** Un altro motivo del motore delle regole (Diagnostica → Attivita' lo dice per esteso). */
  OTHER,
}
