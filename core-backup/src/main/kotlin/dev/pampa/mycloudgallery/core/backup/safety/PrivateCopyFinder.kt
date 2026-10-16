package dev.pampa.mycloudgallery.core.backup.safety

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyFinder
import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopyResult
import dev.pampa.mycloudgallery.core.backup.contract.NasCopyRef
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.Purpose

/**
 * La **deduplica byte per byte** (Fase 5A, P3; `docs/fase5-progetto.md` D8, §2.3, §2.11 punti 1-3; E-B5, E-B16, E-B23, E-L3).
 *
 * C'e' gia' nell'area privata del proprietario una copia con gli stessi byte del media del telefono? I candidati escono **solo** dal
 * catalogo locale ([BackupCatalog.privateCopies]): l'area privata del proprietario, in qualunque cartella (anche i vecchi backup fatti
 * con altri strumenti), stessa dimensione e stessa impronta rapida. Ognuno si **rilegge per intero** qui:
 * - prima testa e coda (`qh1` letto qui, se il telefono lo conosce): diverso = altri byte, senza leggere il resto;
 * - poi tutto il file, con lo SHA-256 calcolato adesso e il timbro uguale prima e dopo la lettura;
 * - SHA-256 uguale a quello dei byte del telefono = [ExistingCopyResult.Found] con la copia riletta qui (il motore la collega,
 *   `LINKED_EXISTING`); diverso, assente, occupato o cambiato durante la lettura = il candidato non vale e si passa al prossimo;
 *   nessun candidato buono = [ExistingCopyResult.None] e si carica ("nel dubbio si carica").
 *
 * **Mai** un'altra area privata, **mai** l'area comune: un candidato che il catalogo desse fuori da [privateShare] non si apre nemmeno.
 * Un hash dell'indice non basta mai (F4-D8): la decisione e' solo dei byte letti qui. Un errore del NAS che ferma il giro (rete,
 * sessione, password) e' [ExistingCopyResult.Interrupted]: il motore lo tratta come ogni errore del NAS. Non scrive niente, ne' sul NAS
 * ne' nel catalogo: collegare e' del motore (P2), che ha il diario.
 */
class PrivateCopyFinder(
  reader: NasMediaReader,
  private val catalog: BackupCatalog,
  private val clock: BackupClock,
  purpose: Purpose = Purpose.BACKGROUND,
  /** Quanti candidati rileggere al massimo per un media (copie della stessa dimensione in tanti vecchi backup). */
  private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
) : ExistingCopyFinder {

  init {
    require(maxCandidates > 0) { "Almeno un candidato." }
  }

  private val rereader = NasRereader(reader, purpose)

  /** Byte riletti dalla deduplica da quando esiste l'oggetto (traffico; soli numeri). */
  @Volatile var bytesRead: Long = 0
    private set

  override suspend fun find(candidate: BackupCandidate, phoneSha256: Sha256, privateShare: String): ExistingCopyResult {
    return when (
      val found = findIdentical(
        catalog, rereader, privateShare, candidate.size, candidate.quickHash, phoneSha256, maxCandidates,
      ) { bytesRead += it }
    ) {
      Identical.None -> ExistingCopyResult.None
      is Identical.Stop -> ExistingCopyResult.Interrupted(SafetyCommon.problemFor(found.error))
      is Identical.Copy -> {
        // L'istante della verifica e' in ora del NAS: senza, una copia non si segna "verificata adesso" e il giro si ferma (meglio
        // che caricare un doppione di byte gia' presenti, invariante 2 del §5.4).
        val at = clock.nasNowMs() ?: return ExistingCopyResult.Interrupted(null)
        ExistingCopyResult.Found(
          VerifiedCopy(candidate.mediaId, candidate.versionId, found.copy.share, found.copy.path, found.stamp, at),
        )
      }
    }
  }

  /** L'esito della ricerca di una copia identica. */
  internal sealed interface Identical {
    data object None : Identical

    class Copy(val copy: NasCopyRef, val stamp: NasStamp) : Identical {
      override fun toString(): String = "Identical.Copy"
    }

    class Stop(val error: NasError) : Identical {
      override fun toString(): String = "Identical.Stop"
    }
  }

  companion object {
    const val DEFAULT_MAX_CANDIDATES: Int = 20

    /**
     * Il cuore di D8, comune alla deduplica del caricamento e a quella del guardiano: le copie di [privateShare] (e solo di quella) con
     * [size] e [quick], rilette per intero; la prima con lo SHA-256 [sha] vince.
     */
    internal suspend fun findIdentical(
      catalog: BackupCatalog,
      rereader: NasRereader,
      privateShare: String,
      size: Long,
      quick: QuickHash?,
      sha: Sha256,
      limit: Int,
      onBytes: (Long) -> Unit,
    ): Identical {
      val copies = catalog.privateCopies(privateShare, size, quick, limit)
      for (copy in copies.take(limit)) {
        // Mai un'altra area privata, mai l'area comune: anche se il catalogo sbagliasse, il file non si apre.
        if (!SafetyCommon.sameShare(copy.share, privateShare)) continue
        val known = copy.stamp
        if (known != null && known.size != size) continue
        when (val read = rereader.reread(copy.share, copy.path, expectSize = size, expectQuick = quick, onBytes = onBytes)) {
          is Reread.Fail -> return Identical.Stop(read.error)
          Reread.Missing, Reread.Busy, Reread.Changed, Reread.Different -> continue
          is Reread.Ok -> if (read.sha == sha && read.stamp.size == size) return Identical.Copy(copy, read.stamp)
        }
      }
      return Identical.None
    }
  }
}
