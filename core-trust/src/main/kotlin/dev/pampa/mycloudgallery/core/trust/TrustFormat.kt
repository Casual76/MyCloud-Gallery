package dev.pampa.mycloudgallery.core.trust

/**
 * Versione e limiti del formato dei dati condivisi sul NAS. La specifica e' `docs/formato-nas.md`:
 * ogni costante qui ha la sua riga nella tabella dei limiti (§7) e nessuna si cambia senza cambiarla.
 */
object TrustFormat {

  /**
   * La versione del formato che questa build sa scrivere e leggere. Sta nel quinto byte di ogni file
   * firmato e del kit di recupero. Un dato con una versione piu' nuova NON si interpreta: si chiede di
   * aggiornare l'app ([RejectReason.NEWER_FORMAT]), senza toccarlo (E-I3).
   */
  const val CURRENT_VERSION: Int = 1

  /** La versione piu' vecchia che questa build sa ancora leggere. */
  const val OLDEST_READABLE_VERSION: Int = 1

  /**
   * Fase 6 (A7, D6): la versione dei **soli** file `ai` (teste di tipo `ai` e segmenti `vec` e `ocr`). Tutto il resto resta alla
   * [CURRENT_VERSION] e si legge come prima: un'app senza la Fase 6 non apre `ai/`, e se per caso aprisse un file `ai` lo
   * troverebbe "piu' nuovo" ([RejectReason.NEWER_FORMAT]), mai un errore del dato (E-I3, E-I22).
   */
  const val AI_VERSION: Int = 2

  /** La versione piu' nuova che questa build sa leggere (1..2): la 2 vale solo per i file `ai`. */
  const val NEWEST_READABLE_VERSION: Int = AI_VERSION

  /** Le versioni dei file che non sono `ai` (registro, richieste, teste `index` e `personal`, segmenti, kit...). */
  fun isReadable(version: Int): Boolean = version in OLDEST_READABLE_VERSION..CURRENT_VERSION

  /** Nome della cartella dell'app in ogni share: nascosta (punto iniziale) e senza estensione. */
  const val ROOT_DIR: String = ".mycloudgallery"
}

/** I tetti della lettura difensiva (`docs/formato-nas.md` §7). */
object TrustLimits {

  // --- File firmati: payload massimo per tipo (il file intero aggiunge intestazione e firma) -------
  const val MAX_REGISTRY_PAYLOAD: Int = 256 * 1024
  const val MAX_REQUEST_PAYLOAD: Int = 4 * 1024
  const val MAX_HEAD_PAYLOAD: Int = 1024 * 1024
  const val MAX_LEASE_PAYLOAD: Int = 2 * 1024
  const val MAX_TRASH_RECORD_PAYLOAD: Int = 8 * 1024
  const val MAX_ALBUM_HEAD_PAYLOAD: Int = 256 * 1024
  const val MAX_LOST_REPORT_PAYLOAD: Int = 2 * 1024
  const val MAX_DEVICE_STATUS_PAYLOAD: Int = 2 * 1024

  /** Un segmento dell'indice (contenitore `MCGB`): il resto si legge a pezzi o non si legge. */
  const val MAX_SEGMENT_BYTES: Int = 16 * 1024 * 1024

  /** Il kit di recupero cifrato: poche centinaia di byte. */
  const val MAX_RECOVERY_KIT_BYTES: Int = 1024

  // --- JSON canonico ----------------------------------------------------------------------------
  const val MAX_JSON_DEPTH: Int = 8
  const val MAX_JSON_STRING_CHARS: Int = 4096
  const val MAX_JSON_KEY_CHARS: Int = 32
  const val MAX_JSON_OBJECT_MEMBERS: Int = 32
  const val MAX_JSON_ARRAY_ITEMS: Int = 8192

  // --- Contenuti -------------------------------------------------------------------------------------
  /** Le voci del registro non si tolgono mai (anche revocate): 256 bastano per anni di telefoni e reinstallazioni. */
  const val MAX_DEVICES: Int = 256
  const val MAX_FROZEN_HEADS: Int = 64
  const val MAX_HEAD_SEGMENTS: Int = 8192
  const val MAX_DISPLAY_TEXT_CHARS: Int = 64
  const val MAX_RESOURCE_KEY_CHARS: Int = 256
  const val MAX_NAS_ACCOUNT_CHARS: Int = 64
  const val MAX_HOST_CHARS: Int = 253
  const val MAX_ENUM_CHARS: Int = 16
  const val MAX_PKCS8_BYTES: Int = 512

  // --- Configurazione di famiglia (Fase 2, §5.3) -------------------------------------------------------
  /** Prefissi ULA /48 di casa: un FRITZ!Box ne annuncia uno; quattro bastano anche dopo un cambio (E-R16). */
  const val MAX_HOME_ULA: Int = 4

  /** Cartelle dell'area comune escluse dall'indice (passo 5: musica, film, copertine). */
  const val MAX_COMMON_EXCLUSIONS: Int = 256

  /** Ogni esclusione e' un percorso relativo alla share: tetto stretto, cosi' 256 esclusioni stanno nel registro. */
  const val MAX_EXCLUSION_UTF8_BYTES: Int = 512

  /** La versione dell'app nello stato di un dispositivo (`2.0.0-debug`). */
  const val MAX_APP_VERSION_CHARS: Int = 32

  /** Tetto dei numeri di versione e di generazione: oltre, il campo e' malformato. */
  const val MAX_VERSION: Long = 1_000_000_000L

  // --- Percorsi relativi alla share --------------------------------------------------------------------
  const val MAX_PATH_UTF8_BYTES: Int = 4096
  const val MAX_PATH_COMPONENTS: Int = 64
  const val MAX_COMPONENT_UTF8_BYTES: Int = 255
  const val MAX_SHARE_NAME_CHARS: Int = 80

  // --- Elenchi di cartelle: oltre il tetto l'elenco e' "anomalo" e si lavora con cio' che si ha ----------
  /** L'elenco del registro e' solo un indizio (versioni oltre un buco): le versioni si leggono per nome. */
  const val MAX_LIST_REGISTRY: Int = 1024
  const val MAX_LIST_REQUESTS: Int = 64
  const val MAX_LIST_HEADS: Int = 256

  /** Prenotazioni: generazioni raggruppate in cartelle da 256, cosi' nessuna cartella cresce senza limite. */
  const val LEASE_SHARD_SIZE: Int = 256
  const val MAX_LIST_LEASE_SHARDS: Int = 1024
  const val MAX_LIST_LEASE_GENERATIONS: Int = 512

  // --- Letture dirette delle serie numerate (registro) -----------------------------------------------------
  /** Versioni lette per nome in una sincronizzazione (recupero di un ritardo lungo: si continua alla successiva). */
  const val MAX_SERIES_READS: Int = 1024

  /**
   * Nomi consecutivi occupati da file non validi che si scavalcano prima di fermarsi e dare l'allarme:
   * limita il lavoro (e il traffico) che uno scrittore ostile puo' imporre a ogni sincronizzazione.
   */
  const val MAX_JUNK_RUN: Int = 32

  // --- Tempo (sempre ora del NAS, in millisecondi) ------------------------------------------------------
  /** Prenotazione piu' lunga ammessa: oltre, la prenotazione e' ignorata (E-I16), non accorciata. */
  const val MAX_LEASE_MILLIS: Long = 60L * 60 * 1000

  /** Quanto un istante firmato puo' stare nel futuro rispetto all'ora del NAS (scarto di rete). */
  const val MAX_FUTURE_SKEW_MILLIS: Long = 2L * 60 * 1000

  /** Permanenza nel cestino del NAS prima che chi ha cestinato possa eliminare (E-D4). */
  const val TRASH_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

  /** Una richiesta di registrazione si approva solo entro 15 minuti da quando l'admin l'ha vista (revisione M2). */
  const val MAX_REQUEST_AGE_MILLIS: Long = 15L * 60 * 1000

  /** Un dispositivo pubblica il proprio stato al massimo una volta in questo intervallo (§5.10). */
  const val DEVICE_STATUS_INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1000

  /** Tetti per giro di pulizia del cestino: oltre, ci si ferma e si chiede (E-D4, piano §7.4). */
  const val MAX_PURGE_ITEMS_PER_ROUND: Int = 200
  const val MAX_PURGE_BYTES_PER_ROUND: Long = 20L * 1024 * 1024 * 1024

  /** Istanti plausibili: dal 2020 al 2100. Fuori, il campo e' malformato. */
  const val MIN_PLAUSIBLE_TIME_MILLIS: Long = 1_577_836_800_000L
  const val MAX_PLAUSIBLE_TIME_MILLIS: Long = 4_102_444_800_000L

  /** Tetto di pixel per decodificare un'immagine che arriva dall'indice (miniature e anteprime). */
  const val MAX_INDEX_IMAGE_PIXELS: Long = 4096L * 4096L

  // --- Fase 4: contenuto dei segmenti dell'indice (§5.5-bis, M2; §7, M9). Costanti fissate da P0, usate da P3/P4 ------

  /** Voci per segmento (`N` nella tabella del contenuto, da 1 a questo). */
  const val MAX_SEGMENT_ENTRIES: Int = 4096

  /** Lapidi (`gone`) per segmento `meta`: tutte le voci possono esserlo (una cartella cancellata dal PC). */
  const val MAX_SEGMENT_TOMBSTONES: Int = MAX_SEGMENT_ENTRIES

  /** Un record `meta` (JSON canonico, al massimo [MAX_JSON_OBJECT_MEMBERS] campi). */
  const val MAX_META_RECORD_BYTES: Int = 4 * 1024

  /** Un blob d'immagine (`thumbS`, `thumbM`, `analysis`, `hq`): JPEG o WebP. */
  const val MAX_BLOB_BYTES: Int = 1024 * 1024

  /** Il corpo `frames` di un media (conteggio + fotogrammi) e quante immagini al massimo (copertina + 8). */
  const val MAX_FRAMES_BODY_BYTES: Int = 2 * 1024 * 1024
  const val MAX_FRAMES_PER_MEDIA: Int = 9

  /** `seq` di un record: cresce per (dispositivo, share), stesso tetto delle versioni. */
  const val MAX_RECORD_SEQ: Long = MAX_VERSION

  /** Record vivi per dispositivo e share (oltre: la pubblicazione si ferma con un avviso, non si tronca). */
  const val MAX_RECORDS_PER_DEVICE_SHARE: Int = 262_144

  /** Il segnaposto nel record (`th`): un ThumbHash di al massimo 36 byte in base32. */
  const val MAX_THUMBHASH_BASE32_CHARS: Int = 58

  /** Un segmento si chiude quando arriva a questa dimensione (o a fine unita'): resta sotto [MAX_SEGMENT_BYTES]. */
  const val SEGMENT_TARGET_BYTES: Int = 12 * 1024 * 1024

  // --- Fase 4: lettura e pubblicazione delle teste dell'indice (§5.5, M3; §7, M9) -------------------------------------

  /** Dispositivi letti per share e per giro (cartelle in `devices/`, tenute solo se approvati o dismessi). */
  const val MAX_INDEX_DEVICES_PER_SHARE: Int = MAX_DEVICES

  /** Letture per nome delle teste per share e per giro (salti doppi e bisezione, come `DeviceStatusSeries`). */
  const val MAX_HEAD_READS_PER_SHARE: Int = 1024

  /** Al massimo una testa nuova per share e tipo in questo intervallo (ora del NAS), salvo la chiusura di un'unita'. */
  const val HEAD_MIN_INTERVAL_MILLIS: Long = 5L * 60 * 1000

  // --- Fase 4: unita' di lavoro e prenotazioni (§5.6, M4; D13) ------------------------------------------------------------

  /** Media per unita' di lavoro (parte di un mese di scatto, in ordine di chiave di contenuto). */
  const val WORK_UNIT_MAX_ITEMS: Int = 200

  /** Rinnovo di una prenotazione di lavoro: una generazione nuova ogni 20 minuti (durata 1 ora). */
  const val LEASE_RENEW_MILLIS: Long = 20L * 60 * 1000

  // --- Fase 4: pulizia dei propri file (§2.3, M6; accesa nella 4B) ---------------------------------------------------------

  /** Le ultime teste proprie che si tengono sempre. */
  const val OWN_FILES_KEEP_HEADS: Int = 3

  /** Un segmento non elencato dalle ultime teste si toglie solo se piu' vecchio di cosi' (ora del NAS). */
  const val OWN_FILES_GRACE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

  /** File propri tolti al massimo per giro. */
  const val MAX_OWN_FILES_PER_ROUND: Int = 500

  // --- Fase 4: ora del NAS (§6.1, M8; D32) -------------------------------------------------------------------------------------

  /**
   * Un salto dell'ora del NAS fra due sessioni oltre questa soglia sospende prenotazioni, pulizia del cestino,
   * compattazione e pulizia dei propri file (uguale a `SessionNasClock.DEFAULT_JUMP_THRESHOLD_MS` di core-nas).
   */
  const val NAS_CLOCK_JUMP_MILLIS: Long = 5L * 60 * 1000

  /** Dopo un salto, le funzioni sospese ripartono da sole dopo 24 ore di ora stabile (o con il gesto dell'utente). */
  const val NAS_CLOCK_STABLE_RESUME_MILLIS: Long = 24L * 60 * 60 * 1000

  // --- Fase 6: corpi dei dati AI (§5.5-bis "Dati AI", §7, A6). Il codec e' `AiSegments` (data/AiSegments.kt) -------------------

  /** Un corpo `vec`: da 1 a [MAX_VEC_VECTORS] vettori da 768 componenti float16; il piu' grande possibile e' di 13.868 byte. */
  const val MAX_VEC_BODY_BYTES: Int = 14 * 1024

  /** Vettori per corpo `vec`: copertina + 8 fotogrammi (D11); esattamente 1 per le sorgenti d'immagine. */
  const val MAX_VEC_VECTORS: Int = 9

  /** Un corpo `ocr` (righe con riquadro, inclinazione, estremi delle parole e testo). */
  const val MAX_OCR_BODY_BYTES: Int = 64 * 1024

  /** Righe di testo per corpo `ocr` (0 = "analizzata, nessun testo"). */
  const val MAX_OCR_LINES: Int = 400

  /** Byte UTF-8 del testo di una riga. */
  const val MAX_OCR_LINE_BYTES: Int = 1024

  /** Parole per riga (gli estremi servono al testo dal vivo, D43). */
  const val MAX_OCR_WORDS_PER_LINE: Int = 64
}

/**
 * Perche' un dato letto dal NAS e' stato rifiutato. Va in Diagnostica (piano 2.7) cosi' com'e': i
 * messaggi non riportano mai il contenuto remoto (niente percorsi, nomi o testo letti dal NAS).
 */
enum class RejectReason {
  /** Il file o un suo campo supera un tetto di §7. */
  TOO_LARGE,
  /** Struttura binaria o JSON non valida, troncata, con byte in piu'. */
  MALFORMED,
  /** JSON valido ma non nella forma canonica (spazi, chiavi fuori ordine, escape superflui...). */
  NOT_CANONICAL,
  /** Scritto da una versione dell'app piu' nuova: non si interpreta, si chiede di aggiornare (E-I3). */
  NEWER_FORMAT,
  /** Il tipo nell'intestazione non e' quello atteso in quella posizione. */
  WRONG_TYPE,
  /** Firmato da una chiave che non e' quella di famiglia attesa o di un dispositivo approvato (E-S14). */
  SIGNER_NOT_APPROVED,
  /** Firmato da un dispositivo revocato o segnato come perso (E-I15). */
  SIGNER_REVOKED,
  /** Firmato da un dispositivo dismesso, e non e' uno dei dati congelati al momento del ritiro. */
  SIGNER_RETIRED,
  /** La firma non corrisponde al contenuto. */
  BAD_SIGNATURE,
  /** Il dato dichiara un'altra famiglia. */
  WRONG_FAMILY,
  /** Il dato non corrisponde al posto in cui si trova (share, cartella, nome del file). */
  WRONG_LOCATION,
  /** Un campo ha un valore non ammesso (formato, intervallo, testo pericoloso). */
  INVALID_FIELD,
  /** Un percorso ostile: `..`, assoluto, UNC, nome riservato, caratteri di controllo o di confusione. */
  HOSTILE_PATH,
  /** Versione piu' vecchia di una gia' accettata (anti-rollback). */
  ROLLBACK,
  /**
   * Registro piu' nuovo ma che "torna indietro" rispetto a quello gia' fidato: un dispositivo sparito, uno
   * stato che regredisce (revocato → approvato), un account cambiato, teste congelate aggiunte, un'ora di
   * emissione precedente. Allarme: base vecchia di un admin, oppure chiave di famiglia usata male.
   */
  REGRESSION,
  /** Due contenuti diversi con lo stesso numero di versione dallo stesso firmatario. */
  EQUIVOCATION,
  /** Dati personali firmati da un dispositivo di un altro account. */
  NOT_OWNER,
  /** Il contenuto non ha l'impronta (o la dimensione) dichiarata da chi lo elenca. */
  HASH_MISMATCH,
}

/** Esito di una verifica: o il valore fidato, o il motivo del rifiuto. Mai un'eccezione verso fuori. */
sealed interface Verdict<out T> {
  data class Accepted<T>(val value: T) : Verdict<T>
  data class Rejected(val reason: RejectReason) : Verdict<Nothing>
}

/** Il valore accettato, oppure null. */
fun <T> Verdict<T>.acceptedOrNull(): T? = (this as? Verdict.Accepted<T>)?.value

/** Il motivo del rifiuto, oppure null se accettato. */
fun Verdict<*>.rejectionOrNull(): RejectReason? = (this as? Verdict.Rejected)?.reason

/**
 * Eccezione INTERNA dei parser: si lancia nel punto in cui si scopre il problema e si converte in
 * [Verdict.Rejected] al confine dell'API ([verdictOf]). Il messaggio e' fisso, mai costruito con dati
 * remoti.
 */
class TrustViolation(val reason: RejectReason, message: String) : Exception(message)

internal fun violation(reason: RejectReason, message: String): Nothing = throw TrustViolation(reason, message)

internal fun ensure(condition: Boolean, reason: RejectReason, message: String) {
  if (!condition) violation(reason, message)
}

/**
 * Esegue un parser su dati remoti e converte QUALUNQUE eccezione in un rifiuto: un dato ostile non deve
 * mai far cadere l'app (E-I14). Le eccezioni non previste valgono [RejectReason.MALFORMED].
 */
internal inline fun <T> verdictOf(block: () -> T): Verdict<T> =
  try {
    Verdict.Accepted(block())
  } catch (v: TrustViolation) {
    Verdict.Rejected(v.reason)
  } catch (e: RuntimeException) {
    Verdict.Rejected(RejectReason.MALFORMED)
  } catch (e: java.security.GeneralSecurityException) {
    Verdict.Rejected(RejectReason.MALFORMED)
  }
