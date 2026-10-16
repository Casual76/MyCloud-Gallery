package dev.pampa.mycloudgallery.core.backup.plan

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.SafetyStatus

// IL CENSIMENTO DI CIO' CHE C'E' GIA' SUL NAS (Fase 5A, P4; docs/fase5-progetto.md §2.11 punti 2, 3 e 6). Serve al riepilogo del primo
// backup: "N gia' sul NAS (collegati), M con una copia simile (si caricano e si vedranno insieme), K nuovi".
//
// E' una STIMA per il riepilogo, non una decisione: collegare una copia al posto di caricare lo decide la rilettura completa (D8,
// `ExistingCopyFinder` di P3); un hash o un'impronta dell'indice non basta mai (F4-D8). Nel dubbio il candidato conta fra i nuovi:
// "nel dubbio si carica".

/** Che cosa c'e' sul NAS di un candidato (§2.11). */
enum class ExistingCopyClass {
  /** Nessuna copia nota: si carica. */
  FRESH,

  /** Una copia nell'area privata del proprietario con la stessa dimensione e la stessa impronta rapida: si collega dopo la rilettura (D8). */
  IDENTICAL,

  /** Una copia simile ma con byte diversi (D36): si carica sempre e si unisce in una cella. */
  SIMILAR,

  /** Il media c'e' solo nell'area comune o in una share pubblica: non si ricarica e non e' "al sicuro" (§2.11 punto 3, Q4 (b)). */
  COMMON_AREA,
}

/**
 * Il censimento dei candidati. [classify] restituisce una classe per candidato, **nello stesso ordine** (stessa dimensione dell'elenco).
 * Non scrive niente e non legge il NAS: parte dal catalogo locale.
 */
fun interface ExistingCopyCensus {
  suspend fun classify(candidates: List<BackupCandidate>, privateShare: String): List<ExistingCopyClass>
}

/**
 * I candidati che hanno una copia simile sul NAS (D36). `:app` la lega al rilevatore delle copie simili (P15, `core-library`): core-backup
 * non lo vede. Restituisce gli id dei media che hanno una copia simile.
 */
fun interface SimilarCopyLookup {
  suspend fun withSimilarOnNas(candidates: List<BackupCandidate>): Set<MediaId>

  companion object {
    /** Nessun rilevatore: nessuna copia simile nota (i candidati contano fra i nuovi). */
    val NONE: SimilarCopyLookup = SimilarCopyLookup { emptySet() }
  }
}

/**
 * Il censimento sopra il catalogo locale (`BackupCatalog`):
 *
 * 1. **identica**: [BackupCatalog.privateCopies] trova nell'area privata del proprietario, in qualunque cartella (anche i vecchi backup
 *    fatti con altri strumenti), una copia con la stessa dimensione e la stessa impronta rapida. Senza impronta rapida del candidato non si
 *    dice mai "identica": la sola dimensione non basta;
 * 2. **solo area comune**: lo stato di sicurezza del media e' [SafetyStatus.ONLY_COMMON_COPY];
 * 3. **simile**: [similar] (P15);
 * 4. altrimenti **nuovo**.
 */
class CatalogExistingCopyCensus(
  private val catalog: BackupCatalog,
  private val similar: SimilarCopyLookup = SimilarCopyLookup.NONE,
) : ExistingCopyCensus {

  override suspend fun classify(candidates: List<BackupCandidate>, privateShare: String): List<ExistingCopyClass> {
    if (candidates.isEmpty()) return emptyList()
    val similarIds = similar.withSimilarOnNas(candidates)
    return candidates.map { c ->
      when {
        c.quickHash != null && catalog.privateCopies(privateShare, c.size, c.quickHash, 1).isNotEmpty() -> ExistingCopyClass.IDENTICAL
        catalog.mediaSafety(c.mediaId)?.status == SafetyStatus.ONLY_COMMON_COPY -> ExistingCopyClass.COMMON_AREA
        c.mediaId in similarIds -> ExistingCopyClass.SIMILAR
        else -> ExistingCopyClass.FRESH
      }
    }
  }
}
