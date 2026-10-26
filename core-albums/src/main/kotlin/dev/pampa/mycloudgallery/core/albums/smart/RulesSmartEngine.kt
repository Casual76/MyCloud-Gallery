package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.SmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.personal.SmartRulesText
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

// IL MOTORE DEGLI ALBUM SMART (docs/fase7-progetto.md §2.2, D14-D16; E-AL1-E-AL17). Valuta uno smart: legge le regole e le scelte dell'utente, carica i
// candidati (solo **visibili**: `VisibleMedia` e il filtro finale `HiddenMask`, mai un nascosto, E-AL8), li classifica ([SmartClassifier]: A, B, C
// sicuri; S1-S5 suggeriti) e scrive **solo** le sue righe ([SmartRows]): gli elementi `auto` e i suggeriti. Mai un elemento scelto dall'utente
// (`manual`, `accepted`), mai un escluso (E-AL5), mai un congelato (E-AL14).
//
//  - [evaluate]: la valutazione intera, a lotti di pagine con un budget (D16: "riprende dal punto a cui era arrivato");
//  - [reevaluate]: l'incrementale (D16): i giorni toccati da un cambiamento del catalogo piu' o meno uno; **uguale** alla valutazione intera
//    (il verdetto di un candidato dipende solo dai tre giorni attorno al suo: [SmartClassifier]), provata come proprieta';
//  - [preview]: l'anteprima di regole non ancora salvate, senza scrivere niente.
//
// Senza il motore di ricerca (non pronto) le regole C e S4 **tacciono** (D15): l'album non perde le righe che il motore di ricerca gli aveva dato (un
// modello che si sta ancora caricando non deve svuotare gli album); un album fatto di sola descrizione resta com'e'. Mai titoli, luoghi, date o id
// nei log (D37): questa classe non scrive log.

class RulesSmartEngine(
  private val rows: SmartRows,
  candidates: SmartCandidates,
  visible: VisibleMedia,
  mask: HiddenMask,
  private val search: SmartContentSearch,
  private val devices: DeviceNormalizer,
  private val lookup: PlaceLookup?,
  private val clocks: AlbumClocks,
  private val seen: SmartSeenMarks = InMemorySeenMarks(),
) : SmartAlbumEngine {

  private val library = VisibleCandidates(candidates, visible, mask)

  /** Una valutazione ferma per il budget: ricomincia da dove era (chiave: l'album e le regole con cui e' partita). */
  private class Pending(val signature: String, val load: CandidateLoad, val content: ContentMatches)

  private val pending = ConcurrentHashMap<AlbumId, Pending>()

  // --- La valutazione intera ---------------------------------------------------------------------------------------------------

  override suspend fun evaluate(id: AlbumId, budget: Duration): SmartOutcome {
    val state = rows.state(id) ?: return SmartOutcome.NotFound
    if (state.frozen) return SmartOutcome.Frozen
    val rules = state.rules
    val signature = signatureOf(rules)
    val start = clocks.monotonicMs()
    val resumed = pending[id]?.takeIf { it.signature == signature }
    val content = resumed?.content ?: contentFor(rules)
    val classifier = classifierOf(rules, content, state)
    if (!classifier.hasConstraints) return finish(id, state, classifier, emptyList(), content, restricted = null)
    // Solo la descrizione e il motore non c'e': l'album resta com'e' (D15), non si svuota.
    if (rules.place == null && classifier.contentRequested && !content.used) return counts(id, state.auto, state.suggestions)
    val load = resumed?.load ?: CandidateLoad(library, SmartLoading.plan(rules, content))
    while (!load.done) {
      load.step()
      if (!load.done && clocks.monotonicMs() - start >= budget.inWholeMilliseconds) {
        pending[id] = Pending(signature, load, content)
        return SmartOutcome.Partial(load.size, maxOf(load.size, library.visibleTotal()))
      }
    }
    pending.remove(id)
    return finish(id, state, classifier, load.result(), content, restricted = null)
  }

  /**
   * L'incrementale (D16): ricalcola i giorni [days] piu' o meno uno (i candidati dei tre giorni attorno a ognuno) e tocca solo le righe di quei
   * giorni. Un album con una descrizione o foto d'esempio si rivaluta intero (la soglia della ricerca e' relativa all'insieme che cerca: un media
   * nuovo puo' cambiare la classe di altri lontani).
   */
  suspend fun reevaluate(id: AlbumId, days: Collection<LocalDate>): SmartOutcome {
    val state = rows.state(id) ?: return SmartOutcome.NotFound
    if (state.frozen) return SmartOutcome.Frozen
    val rules = state.rules
    if (!rules.text.isNullOrBlank() || rules.examples.isNotEmpty()) return evaluate(id, Duration.INFINITE)
    val classifier = classifierOf(rules, ContentMatches.NONE, state)
    if (!classifier.hasConstraints || days.isEmpty()) return counts(id, state.auto, state.suggestions)
    val affected = days.flatMapTo(sortedSetOf<LocalDate>()) { listOf(it.minusDays(1), it, it.plusDays(1)) }
    val ranges = daysAround(affected)
    val loaded = ArrayList<SmartCandidate>()
    for (range in ranges) {
      loaded += library.all(CandidateFilter(days = range, withGpsOnly = false))
    }
    val dayOf = library.daysOf(state.auto.keys + state.suggestions.keys)
    val restricted = Restricted(affected, dayOf)
    return finish(id, state, classifier, loaded.distinctBy { it.media }, ContentMatches.NONE, restricted)
  }

  /** Le righe che una valutazione incrementale puo' togliere: quelle dei giorni ricalcolati e quelle di un media di cui non si sa il giorno. */
  private class Restricted(val affected: Set<LocalDate>, val dayOf: Map<MediaId, LocalDate>) {
    fun removable(media: MediaId): Boolean = dayOf[media]?.let { it in affected } ?: true
  }

  /** Gli intervalli di giorni da caricare: ogni giorno toccato con un giorno di contesto per parte, quelli che si toccano fusi. */
  private fun daysAround(affected: Set<LocalDate>): List<LocalDayRange> {
    val raw = affected.map { LocalDayRange(it.minusDays(1), it.plusDays(1)) }
    return PeriodChips.merge(raw)
  }

  private suspend fun finish(
    id: AlbumId,
    state: SmartAlbumState,
    classifier: SmartClassifier,
    loaded: List<SmartCandidate>,
    content: ContentMatches,
    restricted: Restricted?,
  ): SmartOutcome {
    val index = DayIndex(loaded)
    val verdicts = classifier.classify(index, restricted?.affected)
    val target = Target.of(verdicts, loaded)
    // Il filtro finale subito prima di scrivere: un media nascosto un attimo fa non diventa un elemento (D22).
    val shown = library.shownIds(target.media())
    val targetAuto = target.auto.filterKeys { it in shown }
    val targetSuggested = target.suggested.filterKeys { it in shown }.toMutableMap()
    // Il motore di ricerca non c'e': i suggeriti S4 di prima restano (tacciono, non si ritirano).
    if (classifier.contentRequested && !content.used) {
      for ((media, row) in state.suggestions) {
        if (row.reason == SmartReason.S4.code && media !in targetAuto && media !in targetSuggested && media !in state.exclusions && media !in state.owned) {
          targetSuggested[media] = SmartReason.S4
        }
      }
    }
    val now = stampFor(state)
    val removable: (MediaId) -> Boolean = { media -> restricted?.removable(media) ?: true }
    val change = SmartDiff.change(state.auto, state.suggestions, targetAuto, targetSuggested, now, removable)
    rows.apply(id, change)
    val (auto, suggested) = SmartDiff.after(state.auto, state.suggestions, change)
    return counts(id, auto, suggested)
  }

  private suspend fun counts(id: AlbumId, auto: Map<MediaId, AutoRow>, suggested: Map<MediaId, SuggestionRow>): SmartOutcome {
    val mark = seen.seenUpTo(id)
    val fresh = if (mark == null) 0 else auto.values.count { it.addedAt > mark } + suggested.values.count { it.addedAt > mark }
    return SmartOutcome.Done(auto.size, suggested.size, fresh)
  }

  // --- L'anteprima ------------------------------------------------------------------------------------------------------------

  override suspend fun preview(rules: SmartRules): SmartPreview {
    val content = contentFor(rules)
    val classifier = SmartClassifier(rules, placeOf(rules), devices, content, emptySet(), emptySet())
    if (!classifier.hasConstraints || (rules.place == null && classifier.contentRequested && !content.used)) {
      return SmartPreview(0, 0, tooBroad = false, sample = emptyList(), searchUsed = content.used)
    }
    val load = CandidateLoad(library, SmartLoading.plan(rules, content))
    while (!load.done) load.step()
    val loaded = load.result()
    val target = Target.of(classifier.classify(DayIndex(loaded)), loaded)
    val sure = target.auto.keys.size
    val suggested = target.suggested.size
    val total = library.visibleTotal()
    val broad = sure + suggested > SmartLimits.TOO_BROAD_ITEMS || (total > 0 && (sure + suggested).toDouble() / total > SmartLimits.TOO_BROAD_FRACTION)
    return SmartPreview(sure, suggested, broad, target.sample(SmartLimits.PREVIEW_SAMPLE), searchUsed = content.used)
  }

  // --- "Nuove: 12" -----------------------------------------------------------------------------------------------------------

  /** Quanti elementi e suggeriti sono arrivati dopo l'ultima volta che l'album e' stato visto (E-AL7). Senza un segno: nessuna novita'. */
  suspend fun newSinceSeen(id: AlbumId): Int {
    val state = rows.state(id) ?: return 0
    val mark = seen.seenUpTo(id) ?: return 0
    return state.auto.values.count { it.addedAt > mark } + state.suggestions.values.count { it.addedAt > mark }
  }

  /** L'utente ha visto l'album: le novita' ripartono da zero. */
  suspend fun markSeen(id: AlbumId) {
    val state = rows.state(id) ?: return
    seen.markSeen(id, stampFor(state))
  }

  // --- Pezzi -------------------------------------------------------------------------------------------------------------------

  private suspend fun contentFor(rules: SmartRules): ContentMatches =
    if (rules.text.isNullOrBlank() && rules.examples.isEmpty()) ContentMatches.NONE else search.match(rules.text, rules.examples, rules.period)

  private fun placeOf(rules: SmartRules): PlaceMatcher? = rules.place?.let { PlaceMatcher(it, lookup) }

  private fun classifierOf(rules: SmartRules, content: ContentMatches, state: SmartAlbumState): SmartClassifier =
    SmartClassifier(rules, placeOf(rules), devices, content, state.exclusions, state.owned)

  /** L'istante delle righe nuove: l'ora del NAS se nota, altrimenti subito dopo l'ultima riga che l'album ha (mai indietro). */
  private fun stampFor(state: SmartAlbumState): Long =
    clocks.nasNowMs() ?: ((state.auto.values.maxOfOrNull { it.addedAt } ?: 0L).coerceAtLeast(state.suggestions.values.maxOfOrNull { it.addedAt } ?: 0L) + 1)

  private fun signatureOf(rules: SmartRules): String =
    SmartRulesText.encode(rules).fields.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" } + "#" + rules.examples.joinToString(",") { it.value }
}

/** I verdetti trasformati in cio' che si scrive: i sicuri con l'istante di scatto (la posizione) e i suggeriti con la regola. */
internal class Target(val auto: Map<MediaId, Long>, val suggested: Map<MediaId, SmartReason>, private val order: Map<MediaId, Long>) {
  fun media(): Set<MediaId> = auto.keys + suggested.keys

  /** Al piu' [n] sicuri (poi suggeriti) a intervalli regolari nell'ordine del tempo: l'anteprima mostra tutto il periodo, non solo l'inizio. */
  fun sample(n: Int): List<MediaId> {
    val byTime = compareBy<MediaId>({ order[it] ?: 0L }, { it.value })
    val sure = auto.keys.sortedWith(byTime)
    val pool = if (sure.size >= n) sure else sure + suggested.keys.sortedWith(byTime)
    if (pool.size <= n) return pool
    return (0 until n).map { pool[(it.toLong() * pool.size / n).toInt()] }
  }

  companion object {
    fun of(verdicts: Map<MediaId, SmartVerdict>, loaded: List<SmartCandidate>): Target {
      val time = loaded.associate { it.media to it.c.takenAtUtcMs }
      val auto = LinkedHashMap<MediaId, Long>()
      val suggested = LinkedHashMap<MediaId, SmartReason>()
      for ((media, verdict) in verdicts) {
        when (verdict) {
          is SmartVerdict.Sure -> auto[media] = (time[media] ?: 0L).coerceAtLeast(0L)
          is SmartVerdict.Suggested -> suggested[media] = verdict.reason
        }
      }
      return Target(auto, suggested, time)
    }
  }
}
