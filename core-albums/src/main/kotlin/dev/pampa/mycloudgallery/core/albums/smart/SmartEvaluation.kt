package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.model.MediaId

// I PEZZI INTERNI DELLA VALUTAZIONE DI UNO SMART (D16): cosa caricare per un album, il caricamento a pagine che puo' fermarsi e riprendere, e la
// differenza fra le righe che ci sono e quelle che le regole vogliono. Tutto puro tranne il caricamento, che chiede i candidati a [VisibleCandidates].

/** Cosa caricare: un filtro per periodo e/o i media che la ricerca ha indicato (le celle visibili fra quelli). */
internal class LoadPlan(val filters: List<CandidateFilter>, val media: Set<MediaId>) {
  override fun toString(): String = "LoadPlan(${filters.size} filtri, ${media.size} media)"
}

internal object SmartLoading {

  /**
   * Cosa serve a classificare un album (§2.2).
   *  - **Con un luogo**: i candidati del periodo (le serie, una per volta; oppure tutta la Libreria, solo con GPS se non c'e' un periodo: senza un
   *    periodo una foto senza GPS non si lega al luogo, se non dalla regola B che vuole i sicuri, che hanno il GPS);
   *  - **solo il periodo**: i candidati del periodo;
   *  - **solo la descrizione** (o gli esempi): le celle che la ricerca ha indicato, niente scansione della Libreria.
   * In piu', con un luogo e la ricerca pronta, le celle indicate dalla descrizione (S4 per quelle senza GPS di un album senza periodo).
   */
  fun plan(rules: SmartRules, content: ContentMatches): LoadPlan {
    val contentRequested = !rules.text.isNullOrBlank() || rules.examples.isNotEmpty()
    val hasPlace = rules.place != null
    val indicated = if (content.used) content.sure + content.likely else emptySet()
    if (!hasPlace && contentRequested) return LoadPlan(emptyList(), indicated)
    val gpsOnly = hasPlace && rules.period == null
    val filters = when (val period = rules.period) {
      is SmartPeriod.Ranges -> period.ranges.map { CandidateFilter(days = it, withGpsOnly = gpsOnly) }
      else -> listOf(CandidateFilter(withGpsOnly = gpsOnly))
    }
    return LoadPlan(filters, if (hasPlace) indicated else emptySet())
  }
}

/**
 * Il caricamento a pagine di un [LoadPlan]: ogni [step] legge una pagina (o un gruppo di media indicati), cosi' chi ha un budget puo' fermarsi fra
 * una pagina e l'altra e riprendere (D16: "riprende dal punto a cui era arrivato"). Ogni candidato e' gia' passato dal filtro dei nascosti.
 */
internal class CandidateLoad(private val library: VisibleCandidates, private val plan: LoadPlan, private val pageSize: Int = SmartTuning.PAGE) {
  private val loaded = ArrayList<SmartCandidate>()
  private var filterIndex = 0
  private var cursor: CandidateCursor? = null
  private var mediaDone = plan.media.isEmpty()
  private val pendingMedia = plan.media.toList().sortedBy { it.value }

  val done: Boolean get() = filterIndex >= plan.filters.size && mediaDone

  val size: Int get() = loaded.size

  suspend fun step() {
    when {
      filterIndex < plan.filters.size -> {
        val page = library.page(plan.filters[filterIndex], cursor, pageSize)
        loaded += page.items
        cursor = page.next
        if (cursor == null) filterIndex++
      }
      !mediaDone -> {
        for (chunk in pendingMedia.chunked(MEDIA_CHUNK)) loaded += library.candidatesOf(chunk)
        mediaDone = true
      }
    }
  }

  /** I candidati caricati, ognuno una volta sola (un media indicato dalla ricerca puo' essere anche nel periodo). */
  fun result(): List<SmartCandidate> {
    val seen = HashSet<MediaId>(loaded.size * 2)
    return loaded.filter { seen.add(it.media) }
  }

  private companion object {
    const val MEDIA_CHUNK = 900
  }
}

/** La differenza fra le righe che l'album ha e quelle che le regole vogliono (solo righe del motore). */
internal object SmartDiff {

  /**
   * Cosa scrivere. [targetAuto] sono i sicuri con l'istante di scatto (la posizione), [targetSuggested] i suggeriti con la regola. Una riga che
   * c'e' gia' e non cambia non si riscrive (chi osserva le tabelle non vede un cambiamento che non c'e' stato) e conserva il suo `added_at`
   * ("Nuove: 12" conta da quando un elemento e' arrivato, non da quando e' stato ricalcolato). [removable] dice quali righe che ci sono e non
   * servono piu' si possono togliere: tutte per una valutazione intera, quelle dei giorni ricalcolati per una incrementale.
   */
  fun change(
    existingAuto: Map<MediaId, AutoRow>,
    existingSuggested: Map<MediaId, SuggestionRow>,
    targetAuto: Map<MediaId, Long>,
    targetSuggested: Map<MediaId, SmartReason>,
    now: Long,
    removable: (MediaId) -> Boolean = { true },
  ): SmartRowsChange {
    val putAuto = LinkedHashMap<MediaId, AutoRow>()
    for ((media, position) in targetAuto) {
      val old = existingAuto[media]
      val row = AutoRow(position, old?.addedAt ?: now)
      if (old != row) putAuto[media] = row
    }
    val putSuggestions = LinkedHashMap<MediaId, SuggestionRow>()
    for ((media, reason) in targetSuggested) {
      val old = existingSuggested[media]
      val row = SuggestionRow(reason.code, old?.addedAt ?: now)
      if (old != row) putSuggestions[media] = row
    }
    return SmartRowsChange(
      putAuto = putAuto,
      removeAuto = existingAuto.keys.filterTo(LinkedHashSet()) { it !in targetAuto && removable(it) },
      putSuggestions = putSuggestions,
      removeSuggestions = existingSuggested.keys.filterTo(LinkedHashSet()) { it !in targetSuggested && removable(it) },
    )
  }

  /** Le righe dopo [change]: per i conteggi senza rileggere il database. */
  fun after(
    existingAuto: Map<MediaId, AutoRow>,
    existingSuggested: Map<MediaId, SuggestionRow>,
    change: SmartRowsChange,
  ): Pair<Map<MediaId, AutoRow>, Map<MediaId, SuggestionRow>> {
    val auto = LinkedHashMap(existingAuto)
    change.removeAuto.forEach { auto.remove(it) }
    auto += change.putAuto
    val suggested = LinkedHashMap(existingSuggested)
    change.removeSuggestions.forEach { suggested.remove(it) }
    suggested += change.putSuggestions
    return auto to suggested
  }
}
