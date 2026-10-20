package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File

// Fase 5B (docs/fase5-progetto.md §1.2 "Correzioni (5B)", D19, E-E10; riga P0-B del §4.4): cio' che `core-backup` sa del telefono quando
// deve **scriverci**: il permesso che l'utente ha dato nel dialogo di sistema e la porta che riscrive una riga. `core-backup` non vede
// `core-mediastore` (le due cose le lega `:app`, P14), quindi qui ci sono i tipi equivalenti a quelli di `PhoneMediaRewriter`. Nella 5A il
// telefono non si scrive: `PhoneBytes` e' di sola lettura.

/**
 * Il permesso di scrittura che l'utente ha dato nel dialogo di sistema (`createWriteRequest`) per alcune righe del telefono. Lo costruisce la
 * UI (o `:app`) quando il dialogo torna con "accetto"; vale per **quelle** righe e per questo avvio del processo. Mai nei log (le righe sono
 * dati dell'utente).
 *
 * **Il significato di un permesso che non copre le righe** (P14, contratto per la UI, per i servizi veri e per i fake):
 * - `null` = il dialogo **non e' ancora stato chiesto**: il servizio risponde che serve (`NeedsPhoneWrite`, `NeedsPhoneWrite` delle
 *   correzioni) e **non tocca niente**;
 * - un permesso che **non copre** tutte le righe che servono (anche `PhoneWriteGrant(emptySet())`) = **l'utente ha detto no** al dialogo: il
 *   servizio non scrive sul telefono, non lo tratta come un guasto e fa solo cio' che si puo' senza riscrivere il telefono ("Ripristina
 *   originale": lo scambio sul NAS si fa e il telefono tiene la modifica, `RestoreOutcome.PhoneNotRewritten(PERMISSION_DENIED)`; una
 *   correzione di un media del telefono: niente, `CorrectionOutcome` di rifiuto).
 */
class PhoneWriteGrant(val rows: Set<PhoneRef>) {

  fun covers(ref: PhoneRef): Boolean = ref in rows

  fun coversAll(refs: Collection<PhoneRef>): Boolean = refs.all(::covers)

  override fun toString(): String = "PhoneWriteGrant(${rows.size} righe)"
}

/** L'esito di una riscrittura di una riga del telefono: rispecchia `RewriteResult` di core-mediastore, senza vederlo. Solo numeri e codici. */
sealed interface PhoneRewrite {
  /** Riscritta e riletta: la riga ha [size] byte e lo SHA-256 atteso. */
  data class Replaced(val sha256: Sha256, val size: Long) : PhoneRewrite

  /** Nessun permesso di scrittura per questa riga: niente e' stato toccato. */
  data object NotGranted : PhoneRewrite

  /** La riga non c'e' piu': niente da riscrivere. */
  data object Gone : PhoneRewrite

  /** Il file passato non ha lo SHA-256 atteso: non si scrive niente. */
  data object SourceMismatch : PhoneRewrite

  /**
   * La scrittura non e' riuscita ([code] stabile: `PHONE_FULL`, `WRITE_ERROR`, `HASH_MISMATCH`). [restored] dice se la riga e' tornata com'era;
   * falso e' il caso grave: la sola copia buona e' quella sul NAS (l'originale era al sicuro, D19) e l'utente va avvisato.
   */
  data class Failed(val code: String, val restored: Boolean) : PhoneRewrite
}

/**
 * La riscrittura di un file del telefono (D19, E-E10). `:app` la lega a `PhoneMediaRewriter` di core-mediastore (con D13: in debug con gli
 * account di prova solo `mcg-prova`). Si chiama solo dopo un [PhoneConfirmation] valida, con il permesso del dialogo di sistema per quella
 * riga e con l'originale **al sicuro** sul NAS: lo garantisce la sequenza che la usa, non questa porta.
 */
interface PhoneRewriter {

  /** Riscrive [ref] (di tipo [mime], se noto) con il contenuto di [file], che deve avere SHA-256 [expectedSha256]. */
  suspend fun replace(ref: PhoneRef, mime: String?, file: File, expectedSha256: Sha256): PhoneRewrite
}
