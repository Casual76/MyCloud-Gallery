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
) {
  private val content = MutableStateFlow<SearchCoverage>(SearchCoverage.Complete)

  val state: StateFlow<SearchCoverage> get() = content

  @Volatile var report: CoverageReport = CoverageReport(SearchCoverage.Complete, 0, 0)
    private set

  suspend fun update(counts: CoverageCounts): SearchCoverage {
    val value = when {
      modelMissing() -> SearchCoverage.ModelMissing(queryModelBytes)
      counts.withVector < counts.total ->
        SearchCoverage.Partial(counts.withVector, counts.total, if (counts.missing.isEmpty()) emptyMap() else reasons.reasons(counts.missing))
      counts.currentRecipe < counts.total -> SearchCoverage.Updating(if (counts.total == 0) 100 else counts.currentRecipe * 100 / counts.total)
      else -> SearchCoverage.Complete
    }
    content.value = value
    report = CoverageReport(value, counts.withText, counts.total)
    return value
  }
}
