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
  const val MAX_DEVICES: Int = 64
  const val MAX_FROZEN_HEADS: Int = 64
  const val MAX_HEAD_SEGMENTS: Int = 8192
  const val MAX_DISPLAY_TEXT_CHARS: Int = 64
  const val MAX_RESOURCE_KEY_CHARS: Int = 256

  // --- Percorsi relativi alla share --------------------------------------------------------------------
  const val MAX_PATH_UTF8_BYTES: Int = 4096
  const val MAX_PATH_COMPONENTS: Int = 64
  const val MAX_COMPONENT_UTF8_BYTES: Int = 255
  const val MAX_SHARE_NAME_CHARS: Int = 80

  // --- Elenchi di cartelle: oltre il tetto l'elenco e' "anomalo" e si lavora con cio' che si ha ----------
  const val MAX_LIST_REGISTRY: Int = 1024
  const val MAX_LIST_REQUESTS: Int = 64
  const val MAX_LIST_HEADS: Int = 256
  const val MAX_LIST_LEASE_GENERATIONS: Int = 256

  // --- Tempo (sempre ora del NAS, in millisecondi) ------------------------------------------------------
  /** Prenotazione piu' lunga ammessa: oltre, la prenotazione e' ignorata (E-I16), non accorciata. */
  const val MAX_LEASE_MILLIS: Long = 60L * 60 * 1000

  /** Quanto un istante firmato puo' stare nel futuro rispetto all'ora del NAS (scarto di rete). */
  const val MAX_FUTURE_SKEW_MILLIS: Long = 2L * 60 * 1000

  /** Permanenza nel cestino del NAS prima che chi ha cestinato possa eliminare (E-D4). */
  const val TRASH_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

  /** Tetti per giro di pulizia del cestino: oltre, ci si ferma e si chiede (E-D4, piano §7.4). */
  const val MAX_PURGE_ITEMS_PER_ROUND: Int = 200
  const val MAX_PURGE_BYTES_PER_ROUND: Long = 20L * 1024 * 1024 * 1024

  /** Istanti plausibili: dal 2020 al 2100. Fuori, il campo e' malformato. */
  const val MIN_PLAUSIBLE_TIME_MILLIS: Long = 1_577_836_800_000L
  const val MAX_PLAUSIBLE_TIME_MILLIS: Long = 4_102_444_800_000L

  /** Tetto di pixel per decodificare un'immagine che arriva dall'indice (miniature e anteprime). */
  const val MAX_INDEX_IMAGE_PIXELS: Long = 4096L * 4096L
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
