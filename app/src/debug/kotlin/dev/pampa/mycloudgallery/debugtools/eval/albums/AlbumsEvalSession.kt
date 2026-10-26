package dev.pampa.mycloudgallery.debugtools.eval.albums

import dev.pampa.mycloudgallery.core.albums.contract.TripRules
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Cosa l'utente dice di una foto che l'album ha messo: del viaggio, non del viaggio, o ancora niente. */
enum class Verdict { NONE, RIGHT, WRONG }

/**
 * SOLO debug (Fase 7, P12): lo stato dello strumento di etichettatura e di corsa, senza interfaccia (si prova sulla JVM). Il **metodo "a pozzo"** (§5.3) per
 * ogni viaggio, per non premiare il motore che si sta misurando ([AlbumsEvalReview]):
 *  1. **alla cieca**: si segnano le foto del viaggio scorrendo i giorni del viaggio nella Libreria ([blindCells]), senza guardare l'album;
 *  2. **la corsa**: si crea l'album smart dal titolo, in memoria ([runTrip]); si giudicano i sicuri e i suggeriti su cui l'utente e il motore non sono
 *     d'accordo ([pending], [setVerdict]);
 *  3. **i mancanti**: si cercano fra le foto dei giorni attorno al viaggio che l'album non ha preso ([missingCells]).
 * Poi i numeri: precisione dei sicuri e copertura per viaggio e in totale ([metrics], [saveF88Report]); e per F89 l'elenco dei viaggi principali scritto
 * **prima** contro le proposte di `CatalogTrips` ([compareTrips]).
 *
 * Le etichette sono id locali, salvate a ogni modifica ([AlbumsEvalStore]). Lo strumento non scrive mai nella Libreria: l'album della corsa sta in memoria.
 */
class AlbumsEvalSession(private val env: AlbumsEvalEnvironment, private val store: AlbumsEvalStore) {

  private val state = MutableStateFlow(AlbumsLabelSet())
  val labels: StateFlow<AlbumsLabelSet> get() = state

  /** Se il file delle etichette era rovinato: il motivo (con il numero di riga, mai il contenuto) e un insieme vuoto al suo posto. */
  var loadError: String? = null
    private set

  /** Le corse di questa sessione (in memoria: si rifanno quando serve). */
  private val runs = LinkedHashMap<String, TripRun>()

  fun load() {
    state.value = try {
      store.loadLabels()
    } catch (e: IllegalArgumentException) {
      loadError = e.message
      AlbumsLabelSet()
    }
    runs.clear()
  }

  // --- I viaggi ------------------------------------------------------------------------------------------------------------------

  /** Aggiunge un viaggio dell'elenco; con un [title] si puo' valutare anche come album (F88). Errori di dati: `IllegalArgumentException` con il motivo. */
  fun addTrip(title: String?, from: LocalDate, to: LocalDate): EvalTrip {
    val trip = EvalTrip(state.value.nextId(), from, to, title?.trim()?.takeIf { it.isNotEmpty() })
    change { it.withTrip(trip) }
    return trip
  }

  fun setTitle(id: String, title: String?) {
    val trip = state.value.trip(id) ?: return
    change { it.withTrip(EvalTrip(id, trip.from, trip.to, title?.trim()?.takeIf { t -> t.isNotEmpty() })) }
    runs.remove(id)
  }

  fun removeTrip(id: String) {
    change { it.withoutTrip(id) }
    runs.remove(id)
  }

  private fun change(block: (AlbumsLabelSet) -> AlbumsLabelSet) {
    val next = block(state.value)
    store.saveLabels(next)
    state.value = next
  }

  // --- Prima fase: alla cieca ----------------------------------------------------------------------------------------------------

  /** Le celle visibili della Libreria nei giorni del viaggio, senza nascosti e senza guardare l'album. */
  suspend fun blindCells(id: String): List<AlbumsEvalCell> {
    val trip = state.value.trip(id) ?: return emptyList()
    val (from, to) = AlbumsEvalReview.blindDays(trip)
    return env.cellsOfDays(from, to)
  }

  /** Segna (o toglie il segno a) delle foto come del viaggio. */
  fun setInTrip(id: String, media: Collection<String>, on: Boolean) = change { it.withInTrip(id, media.toSet(), on) }

  /** Segna delle foto come "non del viaggio" (quelle che l'album ha messo e non vanno). */
  fun setNotInTrip(id: String, media: Collection<String>) = change { it.withNotInTrip(id, media.toSet(), true) }

  /** Il giudizio dell'utente su una foto che l'album ha messo. */
  fun verdictOf(id: String, media: String): Verdict = when (media) {
    in state.value.inTripOf(id) -> Verdict.RIGHT
    in state.value.notInTripOf(id) -> Verdict.WRONG
    else -> Verdict.NONE
  }

  fun setVerdict(id: String, media: String, verdict: Verdict) = change {
    when (verdict) {
      Verdict.RIGHT -> it.withInTrip(id, media, true)
      Verdict.WRONG -> it.withNotInTrip(id, media, true)
      Verdict.NONE -> it.withInTrip(id, media, false).withNotInTrip(id, media, false)
    }
  }

  // --- Seconda fase: la corsa ----------------------------------------------------------------------------------------------------

  fun runOf(id: String): TripRun? = runs[id]

  /**
   * Crea l'album smart del titolo **in memoria** e lo valuta sulla Libreria vera. Serve un titolo e almeno una foto segnata alla cieca: guardare
   * l'album prima di aver detto cosa c'e' nel viaggio premierebbe il motore (§5.3).
   */
  suspend fun runTrip(id: String): TripRun {
    val trip = state.value.trip(id) ?: throw IllegalArgumentException("Viaggio sconosciuto.")
    val title = trip.title ?: throw IllegalArgumentException("Il viaggio non ha un titolo: serve per creare l'album.")
    if (state.value.inTripOf(id).isEmpty()) throw IllegalStateException(AlbumsEvalStrings.BLIND_FIRST)
    val run = AlbumsEvalRunner(env.ports()).run(id, title)
    runs[id] = run
    return run
  }

  /** Cio' che resta da giudicare dopo la corsa (vuoto se la corsa non c'e'). */
  fun pending(id: String): AlbumsEvalReview.Pending {
    val run = runs[id] ?: return AlbumsEvalReview.Pending(emptyList(), emptyList())
    return AlbumsEvalReview.pending(run, state.value.inTripOf(id), state.value.notInTripOf(id))
  }

  /** Tutti i sicuri (o tutti i suggeriti) dell'ultima corsa, anche quelli gia' giudicati (vuoto se la corsa non c'e'). */
  fun listed(id: String, suggested: Boolean): List<String> = runs[id]?.let { if (suggested) it.suggested else it.sure }.orEmpty()

  /** Le celle di questi id (sicuri o suggeriti da giudicare), con il giorno, nell'ordine in cui arrivano. */
  suspend fun cellsOf(ids: List<String>): List<AlbumsEvalCell> {
    val byId = env.cellsOf(ids).associateBy { it.id }
    return ids.mapNotNull { byId[it] }
  }

  // --- Terza fase: i mancanti ----------------------------------------------------------------------------------------------------

  /** Le foto dei giorni attorno al viaggio che l'album non ha ne' inserito ne' suggerito (serve la corsa). */
  suspend fun missingCells(id: String): List<AlbumsEvalCell> {
    val trip = state.value.trip(id) ?: return emptyList()
    val run = runs[id] ?: return emptyList()
    val around = AlbumsEvalReview.neighbourDays(trip).flatMap { (from, to) -> env.cellsOfDays(from, to) }
    val keep = AlbumsEvalReview.missingPool(around.map { it.id }, run).toSet()
    return around.filter { it.id in keep }.distinctBy { it.id }
  }

  // --- I numeri ------------------------------------------------------------------------------------------------------------------

  fun metrics(id: String): TripMetrics? {
    val run = runs[id] ?: return null
    return AlbumsEvalMetrics.judge(run, state.value.inTripOf(id), state.value.notInTripOf(id))
  }

  /** I viaggi con titolo che hanno una corsa, giudicati; null se nessuno. */
  fun report(): AlbumsEvalReport? {
    val entries = state.value.titled.mapNotNull { trip ->
      val run = runs[trip.id] ?: return@mapNotNull null
      AlbumsEvalMetrics.Entry(trip, run, AlbumsEvalMetrics.judge(run, state.value.inTripOf(trip.id), state.value.notInTripOf(trip.id)))
    }
    return if (entries.isEmpty()) null else AlbumsEvalMetrics.report(entries)
  }

  /** Il resoconto di F88 (soli numeri) nel file `report-NN.txt`; il nome del file e il testo. */
  fun saveF88Report(): Pair<String, String>? {
    val report = report() ?: return null
    val missing = state.value.titled.size - report.entries.size
    val text = report.toNumbersText() + if (missing > 0) "Viaggi con titolo senza corsa: $missing (non contano nei numeri)\n" else ""
    return store.saveReport(text) to text
  }

  // --- F89 -----------------------------------------------------------------------------------------------------------------------

  /**
   * Confronta l'elenco dei viaggi con le proposte di `CatalogTrips` (ultimi 2 anni) e **chiude l'elenco**: dopo aver visto le proposte non si aggiungono
   * ne' si tolgono viaggi (F89 vale solo se l'elenco e' stato scritto prima di guardare). Salva il resoconto di soli numeri. Se le proposte non sono
   * disponibili non chiude niente.
   */
  suspend fun compareTrips(): Triple<TripsFound, String, String>? {
    val listed = state.value.trips
    if (listed.isEmpty()) throw IllegalStateException(AlbumsEvalStrings.LIST_FIRST)
    val view = env.tripProposals()
    if (!view.available) return null
    val found = TripsMatching.compare(listed, view.proposals, TripRules.firstScreenSince(env.today), view.homeKnown)
    change { it.closed() }
    val text = found.toNumbersText()
    return Triple(found, store.saveReport(text), text)
  }
}
