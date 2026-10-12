package dev.pampa.mycloudgallery.core.model

/**
 * Un valore enumerato che si salva con un **codice stabile**, mai con `name` o `ordinal`.
 *
 * Perche': l'ordinale cambia se qualcuno riordina l'enum, il nome cambia se qualcuno lo rinomina; in
 * entrambi i casi un database gia' scritto verrebbe riletto male, in silenzio, senza che nessuna
 * migrazione se ne accorga. Il codice e' un contratto: si **aggiunge**, non si cambia e non si riusa.
 * Dalla Fase 4 gli stessi codici finiscono anche nei dati firmati sul NAS, letti da versioni diverse
 * dell'app (E-I3): un codice cambiato romperebbe la compatibilita' tra dispositivi.
 *
 * Il test `StableCodesTest` verifica che i codici di ogni enum siano unici e nel formato ammesso.
 */
interface StableCode {
  /** Il codice salvato: minuscolo, `[a-z0-9_]`, al massimo 32 caratteri. */
  val code: String
}

/**
 * Decodifica rigorosa: un codice sconosciuto e' un errore. Va bene per i dati locali, scritti da
 * questa stessa app (Room rifiuta di aprire un database di una versione futura).
 */
inline fun <reified E> enumByCode(code: String): E where E : Enum<E>, E : StableCode =
  enumByCodeOrNull<E>(code)
    ?: throw IllegalArgumentException("Codice sconosciuto per ${E::class.java.simpleName}: '$code'.")

/**
 * Decodifica tollerante: `null` per un codice sconosciuto. Serve a chi legge dati scritti da una
 * versione piu' nuova dell'app (Fase 4) e deve degradare con garbo invece di fermarsi.
 */
inline fun <reified E> enumByCodeOrNull(code: String?): E? where E : Enum<E>, E : StableCode =
  enumValues<E>().firstOrNull { it.code == code }
