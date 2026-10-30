package dev.pampa.mycloudgallery.core.faces.contract

// I CODICI DELLA DIAGNOSTICA DELLE PERSONE (docs/fase8-progetto.md §2.10, D37). Solo numeri e codici: quanti volti, persone, non assegnati,
// unita' fatte, ultimo giro; **mai** nomi, id di persone o di volti, chiavi di contenuto, riquadri, vettori, conteggi per persona,
// generazione. Il catalogo di feature-diagnostics li raccoglie in P13 (sezione `DiagSection.PEOPLE`, voce di stato `PEOPLE_STATE`), con le
// chiavi nell'elenco chiuso dell'esportazione (`ExportKeys`). Un codice nuovo si aggiunge qui e nel catalogo, mai altrove.
object FacesDiag {
  /** Un modello dei volti non torna con l'impronta: si riestrae dall'APK. */
  const val FACES_MODEL_MISMATCH: String = "FACES_MODEL_MISMATCH"

  /** Il motore non parte o fallisce due volte di fila. */
  const val FACES_ENGINE_FAILED: String = "FACES_ENGINE_FAILED"

  /** N volti senza vettore (valori non finiti o norma lontana da 1). */
  const val FACES_VECTOR_INVALID: String = "FACES_VECTOR_INVALID"

  /** Nessun indicizzatore dell'account con Persone attiva ("Fallo su questo dispositivo"). */
  const val FACES_NO_INDEXER: String = "FACES_NO_INDEXER"

  /** Area privata assente o uguale alla comune. */
  const val FACES_NO_PRIVATE_AREA: String = "FACES_NO_PRIVATE_AREA"

  /** N media aspettano un'anteprima (D17). */
  const val FACES_WAITING_PREVIEWS: String = "FACES_WAITING_PREVIEWS"

  /** Dati dei volti non validi o di un altro account, per motivo (conteggio). */
  const val FACES_SEGMENT_REJECTED: String = "FACES_SEGMENT_REJECTED"
  const val FACES_HEAD_REJECTED: String = "FACES_HEAD_REJECTED"

  /** Cartelle di generazioni non correnti trovate: si tolgono (D31). */
  const val FACES_FOREIGN_GENERATION: String = "FACES_FOREIGN_GENERATION"

  /** Il custode si e' riallineato (E-F26). */
  const val FACES_HOLDER_REBASED: String = "FACES_HOLDER_REBASED"

  /** Chiave dei volti inutilizzabile (E-F19): dati riscaricati o ricalcolati. */
  const val FACES_KEY_LOST: String = "FACES_KEY_LOST"

  /** Lo stato di "Disattiva e cancella": la pagina dice cosa manca. */
  const val FACES_ERASE_LOCAL_DONE: String = "FACES_ERASE_LOCAL_DONE"
  const val FACES_ERASE_NAS_PENDING: String = "FACES_ERASE_NAS_PENDING"
  const val FACES_ERASE_NAS_DONE: String = "FACES_ERASE_NAS_DONE"
  const val FACES_ERASE_WAITING_DEVICES: String = "FACES_ERASE_WAITING_DEVICES"

  /** Il cestino di rete del NAS tiene copie (E-F34): "Svuotalo dal pannello del NAS". */
  const val FACES_RECYCLE_BIN: String = "FACES_RECYCLE_BIN"

  /** Voce `faces` sconosciuta (E-F12): Aggiorna. */
  const val FACES_NEEDS_UPDATE: String = "FACES_NEEDS_UPDATE"

  /** P7, P13: dati dei volti di una generazione cancellata ancora da togliere da questo dispositivo: il giro dei dati personali non pubblica (D30). */
  const val FACES_ERASE_PENDING: String = "FACES_ERASE_PENDING"

  /**
   * I7 (correzioni della Fase 8): le persone non si sono potute montare in questo processo. I dati personali escono **senza** la voce `faces`
   * (la testa non vale come conferma di una cancellazione e non dice che si tratti una generazione); Persone resta ferma fino al riavvio.
   */
  const val FACES_MOUNT_FAILED: String = "FACES_MOUNT_FAILED"

  /** Correzioni D (R2, L190 esteso): la cronologia delle ricerche di questo dispositivo potrebbe tenere ancora un nome (i nomi non si sono letti, o la pulizia non e' riuscita). */
  const val FACES_HISTORY_KEPT: String = "FACES_HISTORY_KEPT"

  /** E-A16: Android ha chiuso il processo per memoria durante una tappa del calcolo dei volti (come `AI_KILLED_MEMORY`). */
  const val FACES_KILLED_MEMORY: String = "FACES_KILLED_MEMORY"

  /**
   * Correzioni E (L320, L343, C232, C234): quattro conteggi che prima non avevano un codice proprio. Solo numeri: mai nomi, id, generazioni o percorsi.
   * `FACES_ERASE_FILES_HELD`: N file dei volti che il NAS tiene (aperti da un altro) durante "Disattiva e cancella".
   */
  const val FACES_ERASE_FILES_HELD: String = "FACES_ERASE_FILES_HELD"

  /** N passi del raggruppamento saltati perche' i volti superano il tetto duro (300.000). */
  const val FACES_GROUPING_SKIPPED: String = "FACES_GROUPING_SKIPPED"

  /** N segmenti dei volti che aspettano (assenti, illeggibili, ora nel futuro) e si riprovano con attese crescenti. */
  const val FACES_SEGMENT_WAITING: String = "FACES_SEGMENT_WAITING"

  /** N segmenti o voci che non entrano perche' l'archivio dei volti e' pieno: aspettano una revoca o un contenuto tolto. */
  const val FACES_ARCHIVE_FULL: String = "FACES_ARCHIVE_FULL"

  /**
   * Correzioni C (CA-7, C280): l'archivio dei volti di questo dispositivo non si apre adesso (la chiave del Keystore non risponde, i dati sono di una versione
   * piu' nuova dell'app, o il file non si apre). N tappe del calcolo non sono partite per questo; niente e' stato cancellato. Solo il numero, non il motivo.
   */
  const val FACES_STORE_UNAVAILABLE: String = "FACES_STORE_UNAVAILABLE"

  /** La voce di stato della sezione (`DiagSection.PEOPLE`). */
  const val PEOPLE_STATE: String = "PEOPLE_STATE"

  /** Tutti i codici, nell'ordine del §2.10: il catalogo di feature-diagnostics deve averli tutti (prova di P13). */
  val ALL: List<String> = listOf(
    FACES_MODEL_MISMATCH, FACES_ENGINE_FAILED, FACES_VECTOR_INVALID, FACES_NO_INDEXER, FACES_NO_PRIVATE_AREA, FACES_WAITING_PREVIEWS,
    FACES_SEGMENT_REJECTED, FACES_HEAD_REJECTED, FACES_FOREIGN_GENERATION, FACES_HOLDER_REBASED, FACES_KEY_LOST, FACES_ERASE_LOCAL_DONE,
    FACES_ERASE_NAS_PENDING, FACES_ERASE_NAS_DONE, FACES_ERASE_WAITING_DEVICES, FACES_RECYCLE_BIN, FACES_NEEDS_UPDATE, FACES_ERASE_PENDING, FACES_HISTORY_KEPT, FACES_KILLED_MEMORY, PEOPLE_STATE,
    FACES_MOUNT_FAILED, FACES_ERASE_FILES_HELD, FACES_GROUPING_SKIPPED, FACES_SEGMENT_WAITING, FACES_ARCHIVE_FULL, FACES_STORE_UNAVAILABLE,
  )
}
