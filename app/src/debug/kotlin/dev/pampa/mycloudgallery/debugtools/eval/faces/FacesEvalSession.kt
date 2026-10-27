package dev.pampa.mycloudgallery.debugtools.eval.faces

import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SOLO debug (Fase 8, P11): lo stato dello strumento di etichettatura, senza interfaccia (si prova sulla JVM). Le due sessioni del §5.3:
 *  1. **calibrazione** ([EvalSessionKind.CALIBRATION], ~20 minuti): 40 gruppi scelti a caso fra quelli mostrati, con peso alla dimensione; per ciascuno la
 *     griglia dei volti (fino a 60, piu' i 10 meno simili al gruppo): si toccano i volti che non sono di quella persona, oppure "Gruppo misto". Da qui
 *     [proposal] sceglie le soglie con la regola del §2.5 ([FacesEvalThresholds]);
 *  2. **misura** ([EvalSessionKind.MEASURE], ~20 minuti, dopo il ricalcolo con le soglie nuove): tutti i gruppi se sono al piu' 80, altrimenti quelli con un nome
 *     piu' un campione fino a 80; stessa griglia. Purezza, errori per volto, gruppi della stessa persona separati ("e' la stessa di ...") e volti assegnati
 *     ([report]).
 *
 * La scelta dei gruppi e dei volti dipende da un seme che sta nelle etichette: riaprendo lo strumento si ritrova la stessa scelta. Le etichette sono
 * localizzatori di volti (D23), salvate a ogni modifica ([FacesEvalStore]); lo strumento non scrive mai nell'archivio dei volti ne' nelle correzioni: e' di
 * sola lettura sulla [FacesEvalEnvironment].
 */
class FacesEvalSession(
  private val env: FacesEvalEnvironment,
  private val store: FacesEvalStore,
  private val newSeed: () -> Long = { Random.nextLong() },
) {

  private val state = MutableStateFlow(FacesLabelSet())
  val labels: StateFlow<FacesLabelSet> get() = state

  /** Se il file delle etichette era rovinato: il motivo (con il numero di riga, mai il contenuto) e un insieme vuoto al suo posto. */
  var loadError: String? = null
    private set

  fun load() {
    state.value = try {
      store.loadLabels()
    } catch (e: IllegalArgumentException) {
      loadError = e.message
      FacesLabelSet()
    }
  }

  fun session(kind: EvalSessionKind): SessionLabels? = state.value.session(kind)

  private fun change(block: (FacesLabelSet) -> FacesLabelSet) {
    val next = block(state.value)
    store.saveLabels(next)
    state.value = next
  }

  private fun changeGroup(kind: EvalSessionKind, person: String, block: (GroupLabel) -> GroupLabel) = change { set ->
    val s = set.session(kind) ?: throw IllegalStateException(FacesEvalStrings.NO_SESSION)
    val g = s.group(person) ?: throw IllegalStateException(FacesEvalStrings.NO_GROUP)
    set.with(s.withGroup(block(g)))
  }

  // --- Cominciare ---------------------------------------------------------------------------------------------------------------

  /**
   * Comincia la sessione: sceglie i gruppi con la regola del §5.3 e ne fotografa i conteggi. Se c'e' gia' non fa niente e la ridà (la scelta non si rifa'
   * finche' non si cancella la sessione: nessuno puo' ripetere il sorteggio finche' gli piace).
   */
  suspend fun start(kind: EvalSessionKind): SessionLabels {
    session(kind)?.let { return it }
    env.prepare()
    val groups = env.groups()
    val seed = newSeed()
    val candidates = groups.map { FacesEvalSampling.Candidate(it.id.value, it.faces, it.named) }
    val chosen = if (kind == EvalSessionKind.CALIBRATION) FacesEvalSampling.calibration(candidates, seed) else FacesEvalSampling.measure(candidates, seed)
    val created = SessionLabels(kind, seed, env.totals(), chosen.map { GroupLabel(it.person, it.size, it.named) })
    change { it.with(created) }
    return created
  }

  /** Toglie la sessione e le sue etichette (si ricomincia con un'altra scelta). */
  fun restart(kind: EvalSessionKind) = change { it.without(kind) }

  /** Chiude la sessione: le etichette non cambiano piu' e i numeri sono quelli. */
  fun close(kind: EvalSessionKind) = change { set ->
    val s = set.session(kind) ?: throw IllegalStateException(FacesEvalStrings.NO_SESSION)
    set.with(s.closed())
  }

  // --- Un gruppo ----------------------------------------------------------------------------------------------------------------

  /** Le informazioni di un gruppo scelto: il gruppo dell'ambiente (per i conteggi e la copertina), o null se non c'e' piu'. */
  suspend fun groupInfo(person: String): EvalGroupInfo? = env.groups().firstOrNull { it.id.value == person }

  /** Tutti i gruppi che Persone mostra adesso, per "e' la stessa di ...". */
  suspend fun allGroups(): List<EvalGroupInfo> = env.groups()

  /** I volti da mostrare di un gruppo: la scelta e' quella del seme della sessione. Vuota se il gruppo non c'e' piu'. */
  suspend fun choice(kind: EvalSessionKind, person: String): FacesEvalSampling.FaceChoice {
    val s = session(kind) ?: throw IllegalStateException(FacesEvalStrings.NO_SESSION)
    val faces = env.faces(PersonId(person))
    return FacesEvalSampling.faces(faces.map { FacesEvalSampling.FaceCandidate(it.locator, it.score) }, person, s.seed)
  }

  /** Il volto e' segnato "non e' lui" (un posto che corrisponde, anche se il volto e' stato ricalcolato, D23). */
  fun isWrong(kind: EvalSessionKind, person: String, face: FaceLocator): Boolean =
    session(kind)?.group(person)?.wrong?.any { it.matches(face) } == true

  /**
   * Tocca un volto: segnato "non e' lui" o, se lo era, di nuovo giusto. Chi tocca un volto sta guardando il gruppo: da ora conta nei numeri ([shown] =
   * quanti volti la griglia mostra). Su un gruppo dato per misto, il tocco ritira il "misto".
   */
  fun toggleWrong(kind: EvalSessionKind, person: String, face: FaceLocator, shown: Int) = changeGroup(kind, person) { g ->
    val wasWrong = g.wrong.any { it.matches(face) }
    val wrong = if (wasWrong) g.wrong.filterNot { it.matches(face) }.toSet() else g.wrong + face
    g.copy(wrong = wrong, mixed = false, reviewed = true, shown = maxOf(shown, g.shown))
  }

  /** "Gruppo misto": il gruppo e' sbagliato, e non si dice quali volti (si toglie ogni volto segnato). */
  fun setMixed(kind: EvalSessionKind, person: String, mixed: Boolean, shown: Int = 0) = changeGroup(kind, person) { g ->
    if (mixed) g.copy(mixed = true, wrong = emptySet(), reviewed = true, shown = maxOf(shown, g.shown)) else g.copy(mixed = false, reviewed = false)
  }

  /** "Ho finito con questo gruppo": da ora conta nei numeri. [shown] = quanti volti la griglia ha mostrato. */
  fun setReviewed(kind: EvalSessionKind, person: String, shown: Int, reviewed: Boolean = true) =
    changeGroup(kind, person) { g -> g.copy(reviewed = reviewed, shown = if (reviewed) shown else g.shown) }

  /** "E' la stessa persona di [other]" (o non piu'): un collegamento fra due gruppi; si salva sul gruppo che si sta guardando. */
  fun toggleSame(kind: EvalSessionKind, person: String, other: String) = changeGroup(kind, person) { g ->
    g.copy(sameAs = if (other in g.sameAs) g.sameAs - other else g.sameAs + other)
  }

  /** Il prossimo gruppo da guardare dopo [person] (o il primo non guardato), o null se sono tutti guardati. */
  fun nextToReview(kind: EvalSessionKind, person: String?): String? {
    val groups = session(kind)?.groups ?: return null
    val from = groups.indexOfFirst { it.person == person } + 1
    return (groups.drop(from) + groups.take(from)).firstOrNull { !it.reviewed }?.person
  }

  // --- I numeri -----------------------------------------------------------------------------------------------------------------

  fun report(kind: EvalSessionKind): SessionReport? = session(kind)?.let { FacesEvalMetrics.report(it) }

  /** Il testo dei numeri di una sessione (solo numeri); null se la sessione non c'e'. */
  fun reportText(kind: EvalSessionKind): String? = report(kind)?.let { FacesEvalMetrics.toNumbersText(it) }

  /** Salva i numeri di una sessione in `report-NN.txt`; il nome del file e il testo. */
  fun saveReport(kind: EvalSessionKind): Pair<String, String>? {
    val text = reportText(kind) ?: return null
    return store.saveReport(text) to text
  }

  /**
   * Le soglie che la regola del §2.5 sceglie dalla sessione 1 ([FacesEvalThresholds]): un volto guardato per volta, con la somiglianza al suo gruppo e alla
   * seconda persona; per le nascite i coseni a due a due dei volti CORE. I gruppi misti non servono (non si sa quali volti sbagliano) e si contano a parte.
   * Null se nella sessione 1 non c'e' nemmeno un gruppo guardato e non misto.
   */
  suspend fun proposal(): ThresholdProposal? {
    val s = session(EvalSessionKind.CALIBRATION) ?: return null
    if (s.reviewedGroups.none { !it.mixed }) return null
    val join = ArrayList<JoinObservation>()
    val births = ArrayList<BirthGroup>()
    var index = 0
    for (g in s.reviewedGroups) {
      val faces = env.faces(PersonId(g.person))
      if (faces.isEmpty()) continue
      val choice = FacesEvalSampling.faces(faces.map { FacesEvalSampling.FaceCandidate(it.locator, it.score) }, g.person, s.seed)
      val shown = choice.all.map { it.locator }.toSet()
      val seen = faces.filter { it.locator in shown }
      fun right(f: EvalFaceInfo): Boolean = g.wrong.none { it.matches(f.locator) }
      // Un gruppo misto non dice quali volti sbagliano: non da' osservazioni di ingresso, ma conta per le nascite.
      if (!g.mixed) {
        for (f in seen) join += JoinObservation(index, f.score, f.second, !f.core, right(f))
        index++
      }
      val core = seen.filter { it.core }
      if (core.size >= MIN_BIRTH_FACES) {
        env.cosines(core.map { it.locator })?.let { cosine ->
          val photos = core.map { it.locator.contentKey.hex + ":" + it.locator.frame }
          val ids = photos.distinct()
          births += BirthGroup(if (g.mixed) null else BooleanArray(core.size) { right(core[it]) }, IntArray(core.size) { ids.indexOf(photos[it]) }, cosine)
        }
      }
    }
    return FacesEvalThresholds.choose(join, births, s.reviewedGroups.count { it.mixed }, env.thresholds, s.totals)
  }

  /** Salva il resoconto delle soglie proposte (solo numeri) in `report-NN.txt`; il nome del file e il testo. Null se non c'e' niente da proporre. */
  suspend fun saveProposal(): Pair<String, String>? {
    val p = proposal() ?: return null
    val text = FacesEvalThresholds.toNumbersText(p)
    return store.saveReport(text) to text
  }

  /** Toglie le etichette e i resoconti (a fine misura). */
  fun wipe() {
    store.wipe()
    state.value = FacesLabelSet()
  }

  companion object {
    private const val MIN_BIRTH_FACES = 3
  }
}
