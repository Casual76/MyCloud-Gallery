package dev.pampa.mycloudgallery.core.search.coverage

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.CoverageReason
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Perche' le celle senza vettore aspettano (D31: preparazione, regole dell'indicizzatore, nessun indicizzatore per l'area, modello
 * assente, solo sul telefono di un non indicizzatore, formato non supportato). Lo sa :app (stati della preparazione, ruoli, aree);
 * senza, tutto e' "in preparazione".
 */
fun interface CoverageReasons {
  suspend fun reasons(missing: List<MediaId>): Map<CoverageReason, Int>

  companion object {
    val PREPARATION: CoverageReasons = CoverageReasons { mapOf(CoverageReason.PREPARATION to it.size) }
  }
}

/**
 * Revisione A (PR-1; D14, D31, D42, E-I12): cio' che :app sa dire, oltre ai conteggi, sopra i risultati di Cerca e nella pagina Ricerca e
 * modelli. Solo il tipo dell'area, mai il nome di una share.
 */
interface CoverageNotices {
  /** Le aree senza un indicizzatore vivo adesso: `true` = l'area comune, `false` = l'area privata di questo account. Vuoto = coperte, o non si sa. */
  suspend fun areasWithoutIndexer(): List<Boolean>

  /** Nessun dispositivo che conta ha la lettura del testo attiva (D42): le parole non si cercano dentro le foto. */
  suspend fun textReadingOff(): Boolean

  companion object {
    /** Nessun avviso (i montaggi senza :app, i test). */
    val NONE: CoverageNotices = object : CoverageNotices {
      override suspend fun areasWithoutIndexer(): List<Boolean> = emptyList()

      override suspend fun textReadingOff(): Boolean = false
    }
  }
}

/**
 * I conteggi di una copertura: celle candidate (l'istantanea della Libreria: **senza nascosti**), con un vettore, con un vettore della
 * ricetta corrente, con il testo letto, e le celle senza vettore.
 */
class CoverageCounts(val total: Int, val withVector: Int, val currentRecipe: Int, val withText: Int, val missing: List<MediaId>)

/** La copertura dichiarata (D31, E-Q6, E-A10) per la pagina Ricerca e modelli: il contenuto e, a parte, il testo letto (D42). */
class CoverageReport(val content: SearchCoverage, val withText: Int, val total: Int)

/**
 * LA COPERTURA (D31): ricalcolata a ogni cambio dell'indice o della Libreria, non a ogni query (il motore chiama [update] solo quando
 * la mappa delle celle e' nuova).
 * - modello delle query assente -> [SearchCoverage.ModelMissing] (si cerca con filtri e testo, D35);
 * - celle senza nessun vettore -> [SearchCoverage.Partial] con i motivi (l'avviso solo sotto il 98%, `SearchCoverage.warns`);
 * - tutte con un vettore ma non tutte della ricetta corrente -> [SearchCoverage.Updating] ("Aggiornamento della ricerca: 40%", D21);
 * - altrimenti [SearchCoverage.Complete].
 */
class CoverageTracker(
  private val reasons: CoverageReasons = CoverageReasons.PREPARATION,
  private val queryModelBytes: Long = 0,
  private val modelMissing: () -> Boolean = { false },
  /** PR-1: la lettura del testo non attiva (D42) diventa un motivo della copertura, con le celle senza testo letto. */
  private val notices: CoverageNotices = CoverageNotices.NONE,
) {
  private val content = MutableStateFlow<SearchCoverage>(SearchCoverage.Complete)

  val state: StateFlow<SearchCoverage> get() = content

  @Volatile var report: CoverageReport = CoverageReport(SearchCoverage.Complete, 0, 0)
    private set

  suspend fun update(counts: CoverageCounts): SearchCoverage {
    val value = when {
      modelMissing() -> SearchCoverage.ModelMissing(queryModelBytes)
      counts.withVector < counts.total -> SearchCoverage.Partial(counts.withVector, counts.total, reasonsOf(counts))
      counts.currentRecipe < counts.total -> SearchCoverage.Updating(if (counts.total == 0) 100 else counts.currentRecipe * 100 / counts.total)
      else -> SearchCoverage.Complete
    }
    content.value = value
    report = CoverageReport(value, counts.withText, counts.total)
    return value
  }

  private suspend fun reasonsOf(counts: CoverageCounts): Map<CoverageReason, Int> {
    val out = LinkedHashMap<CoverageReason, Int>()
    if (counts.missing.isNotEmpty()) out += reasons.reasons(counts.missing)
    if (counts.withText < counts.total && notices.textReadingOff()) out[CoverageReason.TEXT_READING_OFF] = counts.total - counts.withText
    return out
  }
}
