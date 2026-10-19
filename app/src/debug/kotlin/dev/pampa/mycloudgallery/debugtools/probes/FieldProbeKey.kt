package dev.pampa.mycloudgallery.debugtools.probes

/**
 * SOLO build debug (Fase 5B, P0-B; docs/fase5-progetto.md §4.4 riga P0-B, docs/android.md "Da verificare con l'app di prova"): le **chiavi del
 * resoconto** delle prove guidate T-09, T-14, T-15 e T-16. Un insieme chiuso: il resoconto e' fatto **solo di numeri** (`chiave=valore`), niente
 * nomi di file, percorsi, share, account, id di media ne' impronte. Una chiave nuova e' una riga qui; un testo libero non entra mai nel resoconto.
 * Gli indici (`chiave.2=...`) distinguono le ripetizioni della stessa misura (la seconda "Confronta" di T-09).
 *
 * I codici sono `[a-z0-9_.]`: lo controlla [FieldProbeKey.isValidCode] e il test `FieldProbeReportTest`.
 */
internal enum class FieldProbeKey(val code: String) {
  // --- Ambiente ---------------------------------------------------------------------------------------------------------------
  ENV_SDK("env.sdk"),
  ENV_ROWS("env.rows"),
  ENV_ROWS_IMAGES("env.rows_images"),
  ENV_ROWS_VIDEOS("env.rows_videos"),

  // --- T-09: cestino Samsung Gallery e cestino MediaStore --------------------------------------------------------------------
  /** Righe di `mcg-prova` nella fotografia di riferimento. */
  T09_BASE_ROWS("t09.base_rows"),

  /** Confronto con il riferimento (indicizzato): righe del riferimento che non ci sono piu' (ne' visibili ne' nel cestino di sistema). */
  T09_DIFF_MISSING("t09.diff_missing"),

  /** Righe con un `_ID` che il riferimento non aveva. */
  T09_DIFF_NEW("t09.diff_new"),

  /** Righe del riferimento nel cestino di sistema (`IS_TRASHED`). */
  T09_DIFF_TRASHED("t09.diff_trashed"),

  /** Righe del riferimento visibili adesso con lo stesso `_ID` e la stessa impronta (dimensione e data di modifica), anche quelle mai toccate. */
  T09_DIFF_SAME_ID_PRESENT("t09.diff_same_id_present"),

  /** Righe nuove che hanno l'impronta di una riga del riferimento sparita: il file e' tornato (ripristino dal cestino) con un `_ID` nuovo. */
  T09_DIFF_OTHER_ID_BACK("t09.diff_other_id_back"),

  /** Quanto e' salita la generazione del volume dal riferimento. */
  T09_DIFF_GENERATION_DELTA("t09.diff_generation_delta"),

  /** Voci di `deleted_files` dopo la generazione del riferimento (quelle che l'app puo' vedere). */
  T09_DIFF_DELETED_FILES("t09.diff_deleted_files"),

  T09_TRASH_REQUESTED("t09.trash_requested"),
  T09_TRASH_RESULT("t09.trash_result"),
  T09_TRASH_IS_TRASHED("t09.trash_is_trashed"),
  T09_TRASH_VISIBLE_DEFAULT("t09.trash_visible_default"),
  T09_TRASH_EXPIRES_DAYS_MIN("t09.trash_expires_days_min"),
  T09_TRASH_EXPIRES_DAYS_MAX("t09.trash_expires_days_max"),

  /** La risposta dell'utente: 1 = il cestino di Samsung Gallery mostra le foto cestinate dall'app, 0 = no. */
  T09_TRASH_IN_SAMSUNG("t09.trash_in_samsung"),
  T09_UNTRASH_REQUESTED("t09.untrash_requested"),
  T09_UNTRASH_RESULT("t09.untrash_result"),
  T09_UNTRASH_IS_TRASHED("t09.untrash_is_trashed"),
  T09_UNTRASH_SAME_IDS("t09.untrash_same_ids"),
  T09_MANY_REQUESTED("t09.many_requested"),
  T09_MANY_RESULT("t09.many_result"),
  T09_MANY_IS_TRASHED("t09.many_is_trashed"),
  T09_WRITE_DIALOG("t09.write_dialog"),

  /** 0 = riscritta e riletta, 1 = nessun permesso, 2 = sparita, 3 = file diverso, 4 = fallita e ripristinata, 5 = fallita e NON ripristinata, 6 = foto non leggibile. */
  T09_WRITE_CODE("t09.write_code"),
  T09_WRITE_SIZE_BEFORE("t09.write_size_before"),
  T09_WRITE_SIZE_AFTER("t09.write_size_after"),
  T09_WRITE_GENERATION_DELTA("t09.write_generation_delta"),
  T09_WRITE_MS("t09.write_ms"),

  // --- T-14: handler GET_CONTENT/PICK e DocumentsProvider ----------------------------------------------------------------------
  T14_HANDLERS_GET_CONTENT("t14.handlers_get_content"),
  T14_HANDLERS_PICK("t14.handlers_pick"),
  T14_OWN_HANDLER("t14.own_handler"),

  /** Quante volte e' stato aperto il selettore di prova da un'altra app. */
  T14_PICKER_LAUNCHED("t14.picker_launched"),
  T14_PICKER_MULTIPLE("t14.picker_multiple"),

  /** 1 = immagini, 2 = video, 3 = qualunque tipo, 9 = altro, 0 = nessuno. */
  T14_PICKER_MIME("t14.picker_mime"),

  /** Chi ha chiesto: il codice di [FieldProbeCaller]. */
  T14_PICKER_CALLER("t14.picker_caller"),

  /** 1 = `GET_CONTENT`, 2 = `PICK`. */
  T14_PICKER_ACTION("t14.picker_action"),

  /** La forma scelta per consegnare il media: 1 = descrittore diretto del MediaStore, 2 = a richiesta (streaming), 3 = copia in cache prima. */
  T14_FORM("t14.form"),

  /** La latenza simulata per ogni lettura del descrittore a richiesta, in ms. */
  T14_LATENCY_MS("t14.latency_ms"),

  /** Quanti file consegna il selettore di prova (1 o 3). */
  T14_COUNT("t14.count"),
  T14_SERVE_OPENS("t14.serve_opens"),
  T14_SERVE_READS("t14.serve_reads"),
  T14_SERVE_BYTES("t14.serve_bytes"),
  T14_SERVE_FIRST_READ_MS("t14.serve_first_read_ms"),
  T14_SERVE_DURATION_MS("t14.serve_duration_ms"),
  T14_SERVE_BACKWARD_SEEKS("t14.serve_backward_seeks"),
  T14_SERVE_REACHED_EOF("t14.serve_reached_eof"),
  T14_SERVE_COPY_MS("t14.serve_copy_ms"),
  T14_SERVE_QUERIES("t14.serve_queries"),
  T14_SERVE_TYPE_QUERIES("t14.serve_type_queries"),
  T14_DOCS_ROOT_QUERIES("t14.docs_root_queries"),
  T14_DOCS_CHILD_QUERIES("t14.docs_child_queries"),
  T14_DOCS_DOC_QUERIES("t14.docs_doc_queries"),
  T14_DOCS_OPENS("t14.docs_opens"),
  T14_DOCS_CALLER("t14.docs_caller"),

  // --- T-15: ricezione di condivisioni -----------------------------------------------------------------------------------------
  T15_HANDLERS_SEND("t15.handlers_send"),
  T15_HANDLERS_SEND_MULTIPLE("t15.handlers_send_multiple"),
  T15_OWN_HANDLER("t15.own_handler"),
  T15_RECEIVED("t15.received"),

  /** 1 = `SEND`, 2 = `SEND_MULTIPLE`. */
  T15_ACTION("t15.action"),
  T15_STREAMS("t15.streams"),
  T15_CLIP_ITEMS("t15.clip_items"),
  T15_GRANT_FLAG("t15.grant_flag"),
  T15_SCHEME_CONTENT("t15.scheme_content"),
  T15_SCHEME_FILE("t15.scheme_file"),
  T15_SCHEME_OTHER("t15.scheme_other"),
  T15_PERM_GRANTED("t15.perm_granted"),
  T15_OPEN_OK("t15.open_ok"),
  T15_OPEN_FAIL("t15.open_fail"),
  T15_BYTES("t15.bytes"),
  T15_OPEN_MS_AVG("t15.open_ms_avg"),
  T15_READ_MS_TOTAL("t15.read_ms_total"),
  T15_CALLER("t15.caller"),

  /** Se la prova ha chiuso l'activity subito, senza leggere (1) o ha letto prima (0). */
  T15_CLOSED_FIRST("t15.closed_first"),
  T15_REOPEN_OK("t15.reopen_ok"),
  T15_REOPEN_DENIED("t15.reopen_denied"),
  T15_REOPEN_AGE_S("t15.reopen_age_s"),

  /** 1 = il processo e' ripartito dalla ricezione. */
  T15_REOPEN_AFTER_RESTART("t15.reopen_after_restart"),

  // --- T-16: "Modifica con..." -------------------------------------------------------------------------------------------------
  T16_HANDLERS_EDIT("t16.handlers_edit"),
  T16_HANDLERS_VIEW("t16.handlers_view"),
  T16_VIEW_LAUNCHED("t16.view_launched"),
  T16_VIEW_ROW_CHANGED("t16.view_row_changed"),
  T16_VIEW_ROWS_NEW("t16.view_rows_new"),
  T16_VIEW_NEW_SAME_TAKEN("t16.view_new_same_taken"),
  T16_VIEW_SIZE_DELTA("t16.view_size_delta"),
  T16_VIEW_GENERATION_DELTA("t16.view_generation_delta"),
  T16_VIEW_SAME_ID("t16.view_same_id"),
  T16_COL_ORIGINAL_DOCUMENT_ID("t16.col_original_document_id"),
  T16_COL_DOCUMENT_ID("t16.col_document_id"),
  T16_COL_INSTANCE_ID("t16.col_instance_id"),
  T16_EDIT_LAUNCHED("t16.edit_launched"),
  T16_EDIT_RESULT_CODE("t16.edit_result_code"),
  T16_EDIT_RESULT_HAS_DATA("t16.edit_result_has_data"),
  T16_EDIT_BYTES_CHANGED("t16.edit_bytes_changed"),
  T16_EDIT_SIZE_BEFORE("t16.edit_size_before"),
  T16_EDIT_SIZE_AFTER("t16.edit_size_after"),
  T16_EDIT_NEW_FILES("t16.edit_new_files"),
  ;

  companion object {
    private val CODE = Regex("^[a-z0-9_.]+$")

    fun isValidCode(code: String): Boolean = CODE.matches(code)

    private val KNOWN: Set<String> = entries.mapTo(HashSet()) { it.code }

    /** Vero se [line] e' la chiave di una misura del resoconto: un codice dell'elenco, con un indice numerico facoltativo (`t09.diff_missing.2`). */
    fun isKnownLine(line: String): Boolean {
      if (line in KNOWN) return true
      val dot = line.lastIndexOf('.')
      return dot > 0 && line.substring(0, dot) in KNOWN && line.substring(dot + 1).toIntOrNull() != null
    }

    /** Il testo della riga del resoconto per [key] con un [index] facoltativo: `t09.diff_missing.2`. */
    fun lineKey(key: FieldProbeKey, index: Int?): String = if (index == null) key.code else "${key.code}.$index"
  }
}

/**
 * Chi ha chiamato un componente di prova, come **numero** (mai il nome del pacchetto nel resoconto): solo le app note delle prove T-14 e T-15.
 * `NONE` = nessun chiamante noto (l'attivita' non e' stata avviata per risultato), `OTHER` = un'app che non e' in elenco.
 */
internal enum class FieldProbeCaller(val code: Long, val packageName: String?) {
  NONE(0, null),
  GMAIL(1, "com.google.android.gm"),
  WHATSAPP(2, "com.whatsapp"),
  TELEGRAM(3, "org.telegram.messenger"),
  GOOGLE_MESSAGES(4, "com.google.android.apps.messaging"),
  SAMSUNG_MESSAGES(5, "com.samsung.android.messaging"),
  ONEDRIVE(6, "com.microsoft.skydrive"),
  GOOGLE_DRIVE(7, "com.google.android.apps.docs"),
  MY_FILES(8, "com.sec.android.app.myfiles"),
  CHROME(9, "com.android.chrome"),
  SAMSUNG_GALLERY(10, "com.sec.android.gallery3d"),
  GOOGLE_PHOTOS(11, "com.google.android.apps.photos"),
  QUICK_SHARE(12, "com.samsung.android.app.sharelive"),
  DOCUMENTS_UI(13, "com.android.documentsui"),
  OTHER(99, null),
  ;

  companion object {
    /** Il codice di [packageName]: noto → il suo, mancante → [NONE], sconosciuto → [OTHER]. */
    fun codeOf(packageName: String?): Long {
      if (packageName.isNullOrEmpty()) return NONE.code
      return entries.firstOrNull { it.packageName == packageName }?.code ?: OTHER.code
    }
  }
}
