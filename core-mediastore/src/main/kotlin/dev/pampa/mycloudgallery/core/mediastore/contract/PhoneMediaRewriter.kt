package dev.pampa.mycloudgallery.core.mediastore.contract

import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File

// "RISCRIVERE UN FILE DEL TELEFONO" (Fase 5B, docs/fase5-progetto.md §1.2 "core-mediastore", D19, E-E10; riga P0-B del §4.4). L'unico modo in
// cui l'app cambia i byte di un media che c'e' gia' sul telefono: le correzioni di data, luogo e rotazione e "Ripristina originale".
// Contratto, null-object, fake e adattatore vero (`write/MediaStorePhoneMediaRewriter`) sono di P0-B; chi lo chiama (la sequenza della
// correzione) e' di P8.
//
// **Quando e come si puo' chiamare.** Mai da solo: (1) l'originale e' gia' **al sicuro** sul NAS (lo verifica `core-backup` prima, D19);
// (2) l'utente ha dato il permesso di sistema con [PhoneMediaIntents.writeRequest] per **questa** riga; (3) il file nuovo e' gia' stato scritto
// altrove, riletto e decodificato, e il suo SHA-256 e' quello atteso. Nessun `MANAGE_MEDIA`, nessun accesso a tutti i file.

/** Perche' una riscrittura non e' riuscita, con codici stabili (mai un messaggio con dati dell'utente). */
object RewriteCodes {
  /** Il telefono non ha spazio per la copia di sicurezza o per la scrittura. */
  const val PHONE_FULL: String = "PHONE_FULL"

  /** Errore di I/O scrivendo o rileggendo. */
  const val WRITE_ERROR: String = "WRITE_ERROR"

  /** Riletta, la riga non ha lo SHA-256 atteso. */
  const val HASH_MISMATCH: String = "HASH_MISMATCH"

  /**
   * R10 della revisione 5B: i byte **originali** della riga non si leggono (senza `ACCESS_MEDIA_LOCATION` `setRequireOriginal` fa lanciare
   * l'apertura): una copia di sicurezza o una rilettura oscurate sarebbero un file diverso, quindi niente si tocca.
   */
  const val ORIGINAL_UNREADABLE: String = "ORIGINAL_UNREADABLE"
}

/** L'esito di [PhoneMediaRewriter.replace]. Solo numeri e codici. */
sealed interface RewriteResult {
  /** Riscritta e **riletta**: la riga ha [size] byte e lo SHA-256 atteso. */
  data class Replaced(val sha256: Sha256, val size: Long) : RewriteResult

  /** Nessun permesso di scrittura per questa riga (dialogo rifiutato, non fatto, o permesso decaduto): niente e' stato toccato. */
  data object NotGranted : RewriteResult

  /** La riga non c'e' piu' (cancellata, cestinata): niente da riscrivere. */
  data object Gone : RewriteResult

  /** Il file passato non ha lo SHA-256 atteso: non si scrive niente. */
  data object SourceMismatch : RewriteResult

  /**
   * R3 della revisione 5B: la riga **adesso** non ha l'impronta attesa, cioe' quella della versione che si sostituisce e che e' al sicuro sul
   * NAS (una modifica fatta sul telefono e non ancora salvata, il catalogo in ritardo, un `_ID` riciclato). Niente e' stato scritto: quei byte
   * sarebbero andati persi per sempre.
   */
  data object RowChanged : RewriteResult

  /**
   * La scrittura non e' riuscita ([code] di [RewriteCodes]). [restored] dice se la riga e' tornata com'era (riletta con lo SHA-256 di prima):
   * falso e' il caso grave, in cui il file del telefono e' rimasto a meta' e la sola copia buona e' quella sul NAS (l'originale era al
   * sicuro, D19): il chiamante lo dice all'utente e non tocca altro.
   */
  data class Failed(val code: String, val restored: Boolean) : RewriteResult
}

/** R9 della revisione 5B: l'esito del recupero delle riscritture interrotte da una morte del processo ([PhoneMediaRewriter.recover]). */
data class RewriteRecovery(
  /** Righe rimesse com'erano dalla copia di sicurezza (la scrittura era a meta'). */
  val restored: Int,
  /** Righe gia' a posto (scrittura finita, o mai cominciata, o riga sparita): solo il diario si e' tolto. */
  val settled: Int,
  /** Righe che non si sono potute rimettere adesso (permesso decaduto, telefono pieno): diario e copia restano, si riprova. */
  val pending: Int,
) {
  companion object {
    val NONE: RewriteRecovery = RewriteRecovery(0, 0, 0)
  }
}

/**
 * Sostituisce i byte di una riga del telefono con quelli di [file], con rete di salvataggio: copia dei byte attuali, **controllo che siano
 * quelli attesi**, diario della scrittura, scrittura, **rilettura con `setRequireOriginal`** e confronto con lo SHA-256 atteso; se qualcosa
 * non torna rimette i byte di prima e li rilegge.
 */
interface PhoneMediaRewriter {

  /**
   * Riscrive [ref] con il contenuto di [file], che deve avere SHA-256 [expectedSha256] (altrimenti [RewriteResult.SourceMismatch]).
   *
   * **R3 della revisione 5B, la regola del riscrittore:** prima di scrivere, i byte che la riga ha **adesso** devono avere l'impronta
   * [currentSha256], cioe' quella della versione che si sostituisce e che e' al sicuro sul NAS; altrimenti [RewriteResult.RowChanged] e niente
   * si scrive (la riga ha una modifica che non e' da nessun'altra parte). Il chiamante la mette prima al sicuro, o si ferma e lo dice.
   *
   * Solo con il permesso di [PhoneMediaIntents.writeRequest] per [ref] (lo stesso URI: la collezione la decide il tipo della riga). Sospende per
   * I/O; l'annullamento della coroutine non lascia mai la riga a meta' (la rete di salvataggio gira comunque fino in fondo).
   */
  suspend fun replace(ref: MediaRef.Phone, file: File, expectedSha256: Sha256, currentSha256: Sha256): RewriteResult

  /**
   * R9 della revisione 5B: le riscritture lasciate a meta' da una morte del processo (diario e copia di sicurezza in `noBackupFilesDir`): una
   * riga che non ha ne' l'impronta di prima ne' quella nuova si rimette com'era, poi diario e copia si tolgono. Si chiama all'avvio (e prima
   * di ogni riscrittura). Di default niente da recuperare.
   */
  suspend fun recover(): RewriteRecovery = RewriteRecovery.NONE
}
