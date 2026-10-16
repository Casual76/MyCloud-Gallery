package dev.pampa.mycloudgallery.feature.backup.contract

import dev.pampa.mycloudgallery.core.backup.contract.BackupAction

// Fase 5A (docs/fase5-progetto.md §1.1, §1.5, §2.6; righe P5 e P12 del §4.4): le schermate di feature-backup e le loro rotte. Il
// contratto fra questo modulo e `:app` (che le inserisce nella navigazione, P7): `:app` conosce le rotte, non le composable. I feature
// non si conoscono fra loro: Impostazioni apre la voce "Backup" con una callback scritta da `:app`.

/**
 * Le schermate del backup. [route] e' stabile (nessun dato dell'utente dentro: mai un percorso o un nome di cartella); [phase5B] le
 * schermate della seconda meta' (P12), dichiarate perche' le rotte non cambino dopo.
 */
enum class BackupScreen(val route: String, val phase5B: Boolean = false) {
  /** La pagina Backup (§2.6): una riga per sorgente, il problema piu' grave con la sua azione, "Fai ora", "Ferma". */
  PAGE("backup/page"),

  /** Impostazioni → Backup: sorgenti, cartella del dispositivo (D7), "Proteggi subito le foto nuove" (D34). */
  SETTINGS("backup/settings"),

  /** Il riepilogo del primo backup (§2.11): i tre numeri, la stima, "Stanotte" / "Inizia ora". */
  FIRST_BACKUP("backup/first"),

  /** La scelta della cartella del dispositivo (E-B6, E-B16: "Continua li'" o "Nuova cartella"). */
  DEVICE_FOLDER("backup/device-folder"),

  /** 5B: "Libera spazio" (D21). */
  FREE_SPACE("backup/free-space", phase5B = true),

  /** 5B: le attivita' in sospeso, "Da decidere" (D20). */
  PENDING_TASKS("backup/pending", phase5B = true),
}

/**
 * Le azioni dei problemi del backup (§2.6, colonna "Azione") e dei comandi della pagina, eseguite da `:app` (P7): navigazione verso la
 * Diagnostica e Spazio NAS, richiesta dei permessi, password del NAS, "Fai ora" (UIDT) e "Ferma". Le schermate non le eseguono da
 * sole: chiamano questa porta dopo un tocco.
 */
interface BackupActions {
  /** Esegue [action] (un'azione senza tasto, come [BackupAction.ASK_ADMIN], non arriva mai qui). */
  fun perform(action: BackupAction)

  /** "Fai ora" (proposta 10): sui dati mobili `:app` chiede prima la conferma con la stima. */
  fun backupNow()

  /** "Ferma": prima del file successivo, ogni temporaneo resta con il suo punto di ripresa. */
  fun stop()

  /**
   * La scelta del foglio del primo backup (§2.11, P5): [tonight] = "Stanotte" (la predefinita: il lavoro segue il preset, di notte),
   * altrimenti "Inizia ora" (un lavoro avviato dall'utente, UIDT, con l'avviso "meglio in carica e sul Wi-Fi di casa"; sui dati
   * mobili `:app` chiede prima la conferma con la stima). Aggiunta di P5 al contratto di P0: `backupNow` e' "Fai ora" (`USER_NOW`), questo
   * e' il primo backup (`FIRST_BACKUP`).
   */
  fun startFirstBackup(tonight: Boolean)

  /** Apre una schermata del backup. */
  fun open(screen: BackupScreen)
}
