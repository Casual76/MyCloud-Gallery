package dev.pampa.mycloudgallery.core.library.duplicates

import dev.pampa.mycloudgallery.core.model.LocationId

// Fase 7, P8 (docs/fase7-progetto.md D25, §2.1.1; E-U1, E-U2): le REGOLE della pulizia dei doppioni identici, pure (nessun catalogo,
// nessun Android). Provate su JVM con gruppi scritti a mano e con una proprieta' su gruppi casuali.

/**
 * La proposta di pulizia per un gruppo di doppioni identici.
 *
 * **Quale copia si tiene** (D25), una per parte, nell'ordine:
 *  1. la copia privata **verificata** ("al sicuro");
 *  2. la copia in un **album condiviso**;
 *  3. la copia nella **cartella di backup** del dispositivo (`Backup/<dispositivo>/`);
 *  4. la **piu' vecchia** (poi l'identita' della posizione, per avere una sola risposta).
 * Il primo criterio decide per primo: E-U2, se l'unica copia verificata sta nella cartella che l'ordine avrebbe proposto per l'eliminazione, si
 * tiene quella e si propone di eliminare l'altra.
 *
 * **I "mai"** (che nessuna scelta dell'utente aggira, perche' la proposta non li offre):
 *  - **mai una copia nell'area di un altro account** (E-U1): non c'e' il permesso; si conta e si dice;
 *  - **mai l'ultima copia privata**: se tutte le copie private fossero da togliere, la migliore resta;
 *  - **mai una copia in un album condiviso**: toglierla toglie l'elemento a tutti i familiari (D9, E-SH17); resta dov'e';
 *  - **mai senza hash completo**: un gruppo senza non e' provato identico (D27), si mostra "da verificare" e non si pulisce.
 * La copia del telefono non passa da qui: va nel cestino di sistema, con il dialogo di sistema, e mai senza.
 */
object IdenticalCleanup {

  /** L'ordine di D25: la copia da tenere e' la prima. */
  val keepOrder: Comparator<IdentCopy> = compareBy<IdentCopy>(
    { if (it.isSafeCopy) 0 else 1 },
    { if (it.inSharedAlbum) 0 else 1 },
    { if (it.backupFolder != null) 0 else 1 },
    { it.modifiedAt },
    { it.location.value },
  )

  /**
   * La proposta per [group]. Con [keepChoice] l'utente ha scelto lui quale copia tenere ("Rivedi"): vale se e' una copia con il permesso del
   * gruppo, e tutti i "mai" valgono lo stesso. Una scelta che non e' del gruppo (o e' nell'area di un altro) si ignora.
   */
  fun propose(group: IdenticalGroup, keepChoice: LocationId? = null): GroupProposal {
    val others = group.copies.filter { it.area == CopyArea.OTHERS }
    val blocked = ArrayList<BlockedCopy>(others.map { BlockedCopy(it, BlockReason.OTHER_AREA) })
    val permitted = group.copies.filter { it.area != CopyArea.OTHERS }
    val best = permitted.minWithOrNull(keepOrder)

    if (!group.verified) {
      // Non provato identico: niente da togliere. La copia "da tenere" e' comunque quella che l'ordine sceglierebbe.
      return GroupProposal(group, IdenticalStatus.TO_VERIFY, best, emptyList(), blocked)
    }
    if (permitted.size < 2) return GroupProposal(group, IdenticalStatus.NOTHING_TO_REMOVE, best, emptyList(), blocked)

    val keep = keepChoice?.let { choice -> permitted.firstOrNull { it.location == choice } } ?: best!!
    val remove = permitted.filter { it !== keep }.sortedWith(keepOrder).toMutableList()

    // Mai una copia che e' un elemento di un album condiviso.
    val inAlbum = remove.filter { it.inSharedAlbum }
    if (inAlbum.isNotEmpty()) {
      remove.removeAll(inAlbum)
      blocked += inAlbum.map { BlockedCopy(it, BlockReason.SHARED_ALBUM) }
    }

    // Mai l'ultima copia privata: se la copia da tenere non e' privata, tutte le copie private (che non sono in un album: quelle stanno
    // nell'area comune) sarebbero da togliere, e la migliore resta. Una copia da tenere privata basta.
    val privates = remove.filter { it.area == CopyArea.MY_PRIVATE }
    if (privates.isNotEmpty() && keep.area != CopyArea.MY_PRIVATE) {
      val rescue = privates.minWith(keepOrder)
      remove.remove(rescue)
      blocked += BlockedCopy(rescue, BlockReason.LAST_PRIVATE)
    }

    val status = if (remove.isEmpty()) IdenticalStatus.NOTHING_TO_REMOVE else IdenticalStatus.CLEANABLE
    return GroupProposal(group, status, keep, remove, blocked)
  }

  /** La cartella `Backup/<dispositivo>/` di un percorso nella share, in forma di confronto; null se il file non sta in una cartella di un dispositivo. */
  fun backupFolderOf(path: String): String? {
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.size < MIN_BACKUP_SEGMENTS || !segments[0].equals(BACKUP_ROOT, ignoreCase = true)) return null
    val folder = fold(segments[1])
    return folder.takeIf { it.isNotEmpty() && it != COMMON_AREA_FOLDER }
  }

  /** Il file sta dentro la cartella di un album condiviso dell'area comune: `Album condivisi/<album>/...` (Q1). */
  fun isInSharedAlbumFolder(path: String, sharedRoot: String = SHARED_ALBUMS_ROOT): Boolean {
    val segments = path.split('/').filter { it.isNotEmpty() }
    return segments.size >= 3 && fold(segments[0]) == fold(sharedRoot)
  }

  private fun fold(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)

  /** La radice dei media dei backup nell'area privata, come `OriginDevice`. */
  private const val BACKUP_ROOT = "Backup"

  /** `BackupPaths.COMMON_AREA_FOLDER` in forma di confronto: le copie del guardiano non sono di nessun dispositivo. */
  private const val COMMON_AREA_FOLDER = "area comune"

  /** `Backup/<dispositivo>/<nome>`: almeno tre pezzi. */
  private const val MIN_BACKUP_SEGMENTS = 3

  /** Q1 del 22/10: la cartella degli album condivisi nell'area comune (`SharedAlbumLimits.FOLDER_ROOT` di core-albums, che questo modulo non vede). */
  const val SHARED_ALBUMS_ROOT: String = "Album condivisi"
}
