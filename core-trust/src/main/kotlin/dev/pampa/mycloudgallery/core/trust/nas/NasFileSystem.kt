package dev.pampa.mycloudgallery.core.trust.nas

/**
 * L'ora di riferimento: l'ora del NAS, in millisecondi dall'epoca (`docs/formato-nas.md` §6). Scadenze,
 * prenotazioni e istanti firmati usano SOLO questa, mai l'orologio del dispositivo (E-P9).
 *
 * L'implementazione reale (Fase 2.1) somma all'orologio monotono del dispositivo lo scarto letto alla
 * NEGOTIATE SMB (`getTimeOffsetMillis`); nei test e' un orologio finto.
 */
fun interface NasClock {
  fun nowMillis(): Long
}

/** Elenco dei nomi (non percorsi) dei figli diretti di una cartella. */
class NasListing(val names: List<String>, val truncated: Boolean) {
  companion object {
    val EMPTY = NasListing(emptyList(), truncated = false)
  }
}

sealed interface NasRead {
  class Data(val bytes: ByteArray) : NasRead

  data object NotFound : NasRead

  /** Il file supera il tetto chiesto: non si e' letto oltre. */
  data object TooLarge : NasRead
}

enum class PublishResult {
  CREATED,

  /** Il nome esisteva gia': nessun byte e' stato sovrascritto. */
  ALREADY_EXISTS,
}

/**
 * Il file system di UNA share del NAS, visto da `core-trust` (`docs/formato-nas.md` §2.3). Implementazione
 * SMB in Fase 2.1; un fake in memoria nei test.
 *
 * Di proposito NON ci sono operazioni distruttive: niente cancellazione, niente spostamento, niente
 * sovrascrittura. Ogni dato si pubblica una volta sola con un nome nuovo ([publishExclusive]); chi legge
 * sceglie la versione piu' alta valida. Cosi' nessun dato letto dal NAS puo' far cancellare o spostare
 * qualcosa passando da questo modulo (E-S11). Le operazioni distruttive dell'app (cestino, pulizie,
 * spostamenti verso l'area comune) vivono altrove e partono solo da azioni locali confermate.
 *
 * Le chiamate sono bloccanti: vanno fatte su un dispatcher di I/O.
 */
interface NasFileSystem {

  /** Il nome della share, come appare nella configurazione di famiglia. */
  val shareName: String

  /**
   * I nomi dei figli di [dir] (al massimo [limit]; `truncated` se ce n'erano di piu'). Cartella assente =
   * elenco vuoto. I nomi arrivano dal server cosi' come sono: chi li usa li valida.
   */
  fun list(dir: SharePath, limit: Int): NasListing

  /**
   * Legge [path] per intero se non supera [maxBytes]. L'implementazione non alloca mai piu' di
   * `maxBytes + 1` byte, qualunque dimensione dichiari il server.
   */
  fun read(path: SharePath, maxBytes: Int): NasRead

  /**
   * Pubblica [bytes] in [path] solo se il nome non esiste. Atomica per chi legge: un file pubblicato e'
   * sempre completo. Implementazione SMB: scrittura di un temporaneo in `.mycloudgallery/tmp/` aperto con
   * `FILE_CREATE`, flush, verifica della lunghezza, `rename(path, replaceIfExist = false)`; le cartelle
   * mancanti si creano trattando "esiste gia'" come successo.
   */
  fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult
}
