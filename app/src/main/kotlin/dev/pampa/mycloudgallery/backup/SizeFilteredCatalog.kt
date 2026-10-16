package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.CopyToVerify
import dev.pampa.mycloudgallery.core.backup.contract.VerifyScope

/**
 * Le copie da rileggere divise per **peso** (Fase 5A, P7; docs/fase5-progetto.md §2.8): le verifiche, `FULL_HASH` e la rotazione girano
 * con la regola dell'attivita' del tipo di media (`PHOTO_BACKUP`, leggera; `VIDEO_BACKUP`, pesante, solo sul Wi-Fi di casa), ma le query
 * del catalogo non portano il tipo del media (e toccarle rovinerebbe i piani delle query di P1): la divisione si fa per dimensione del file.
 * Una copia di almeno [minBytes] e' "pesante" (un video), sotto [maxBytes] e' "leggera" (una foto, anche una RAW); tutto il resto passa
 * com'e' (`BackupCatalog by base`).
 *
 * La paginazione resta quella che il verificatore si aspetta: una pagina piu' corta del limite vuol dire "non ce n'e' altre". Per questo,
 * se il filtro toglie troppe copie, si rilegge dal catalogo con un limite doppio finche' non ce ne sono abbastanza o il catalogo non ne ha
 * piu' (fino a [MAX_READ]).
 */
class SizeFilteredCatalog(
  private val base: BackupCatalog,
  private val minBytes: Long = 0,
  private val maxBytes: Long = Long.MAX_VALUE,
) : BackupCatalog by base {

  init {
    require(minBytes >= 0 && maxBytes >= minBytes) { "Intervallo di dimensioni non valido." }
  }

  override suspend fun verifyCandidates(scope: VerifyScope, limit: Int): List<CopyToVerify> {
    var take = limit
    while (true) {
      val raw = base.verifyCandidates(scope, take)
      val kept = raw.filter { it.size in minBytes..maxBytes }
      if (kept.size >= limit || raw.size < take || take >= MAX_READ) return kept.take(limit)
      take = (take.toLong() * 2).coerceAtMost(MAX_READ.toLong()).toInt()
    }
  }

  companion object {
    /** Il massimo di copie lette dal catalogo per una pagina filtrata. */
    const val MAX_READ: Int = 100_000

    /** Da qui in su una copia e' "pesante": un video, letto solo con la regola dei video (64 MiB: una foto non ci arriva, un video quasi sempre si'). */
    const val HEAVY_BYTES: Long = 64L shl 20
  }
}
