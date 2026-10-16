package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.Sha256

// Fase 5A (docs/fase5-progetto.md D8, §2.3, §2.11): il punto d'incontro fra il motore del caricamento (P2) e la deduplica (P3).
// Scritto prima che i due pacchetti partano in parallelo: P3 lo implementa in `safety/`, P2 lo chiama; nessuno dei due lo cambia
// senza dirlo nel report.

/**
 * D8 (E-B5, E-B23): c'e' gia' nell'area privata del proprietario una copia con gli **stessi byte** di [candidate]?
 *
 * L'implementazione prende i candidati da [BackupCatalog.privateCopies] (solo l'area privata del proprietario, in qualunque
 * cartella, stessa dimensione, impronta rapida letta qui) e **rilegge per intero** ciascuno: SHA-256 uguale a [phoneSha256] =
 * [ExistingCopyResult.Found] con la copia riletta qui; diverso, illeggibile o nessun candidato = [ExistingCopyResult.None] e si
 * carica ("nel dubbio si carica"). Un hash dell'indice non basta mai (F4-D8). Il motore la chiama dopo aver letto i byte del
 * telefono e prima di aprire il temporaneo.
 */
fun interface ExistingCopyFinder {
  suspend fun find(candidate: BackupCandidate, phoneSha256: Sha256, privateShare: String): ExistingCopyResult
}

sealed interface ExistingCopyResult {
  /** Copia identica riletta qui: il motore la collega (`LINKED_EXISTING`), niente caricamento. */
  class Found(val copy: VerifiedCopy) : ExistingCopyResult {
    override fun toString(): String = "Found"
  }

  /** Nessuna copia identica: si carica. */
  data object None : ExistingCopyResult

  /** NAS irraggiungibile o giro da fermare durante la rilettura: il motore lo tratta come ogni errore del NAS (§2.2 "Rete"). */
  class Interrupted(val problem: BackupProblemCode?) : ExistingCopyResult {
    override fun toString(): String = "Interrupted(${problem?.code})"
  }
}
