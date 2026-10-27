package dev.pampa.mycloudgallery.core.albums.upkeep

import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.smart.SmartRecompute
import dev.pampa.mycloudgallery.core.albums.smart.SmartRows
import dev.pampa.mycloudgallery.core.albums.smart.searchesContent
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// TENERE AL PASSO GLI ALBUM SMART (Fase 7, revisione di fine fase; docs/fase7-progetto.md D5, D16; AS-1, AS-2, AS-3, AS-4, AS-5, AS-6). Il motore di P6
// (`RulesSmartEngine`, `SmartRecompute`) sa calcolare; nessuno, in `:app`, faceva le tre cose che servono dopo un calcolo:
//  - **dirlo a chi mostra** (AS-1): il motore scrive le righe solo su Room e `CatalogAlbums` si rilegge solo quando cambia il catalogo o lo stato dei
//    dati personali, quindi un album appena riempito restava "vuoto" nell'elenco e nella pagina finche' non si usciva e si rientrava;
//  - **dire che sta cercando** (AS-2): uno smart appena creato non ha righe e la pagina diceva "Nessuna foto corrisponde alle regole";
//  - **non lasciare niente a meta'** (AS-5): un calcolo fermo dal budget (`Partial`), un errore di lettura (R17) o i giorni saltati dal budget non si
//    riprendevano, e gli smart arrivati prima del montaggio non si valutavano mai.
// Le chiamate arrivano da piu' parti insieme (il giro del catalogo, i dati personali, la notte, il montaggio): ogni album e' protetto dal lucchetto del
// motore, qui si tiene solo l'elenco di cio' che resta da fare. Mai titoli, luoghi o id nei log (D37): questa classe non scrive log.
//
// IL BUDGET DELLE RICERCHE PER CONTENUTO (correzioni F, R19, L146, L250). Nessun tetto a quanti smart con una descrizione o delle foto d'esempio si creano e
// nessun messaggio all'utente; ma ognuno costa una ricerca sull'intera Libreria a ogni rivalutazione, e un dispositivo guasto dello stesso account ne potrebbe
// far arrivare centinaia. Per questo **ogni valutazione di uno smart con ricerca per contenuto passa da [ContentBudget]**: al piu' [ContentBudget.PER_ROUND]
// per giro (30 s), gli altri in fila, a rotazione, al giro dopo. Gli smart senza ricerca per contenuto non aspettano mai.

/** Chi mostra gli smart (`CatalogAlbums`): sa quando il motore lavora e quando ha scritto. */
interface SmartWorkSink {
  /** Questi smart non hanno ancora righe e il motore le sta per calcolare: le loro pagine dicono "cerco le foto", non "nessuna foto". */
  fun smartWorkStarted(albums: Collection<AlbumId>)

  /** Il motore ha finito (o ha rinunciato) per [albums]: non sono piu' "in calcolo" e l'elenco e le pagine si rileggono. */
  suspend fun smartWorkFinished(albums: Collection<AlbumId>)

  /** Il motore ha scritto righe di album che erano gia' calcolati: l'elenco e le pagine si rileggono. */
  suspend fun smartRowsChanged()
}

class SmartUpkeep(
  private val scope: CoroutineScope,
  private val rows: SmartRows,
  private val recompute: SmartRecompute,
  private val sink: SmartWorkSink,
  /** Un errore di un giro: chi monta lo conta (`roundErrors`). Mai il messaggio nei log. */
  private val onError: (Exception) -> Unit = {},
  private val budget: Duration = BUDGET,
  /** AS-6: per quanto il catalogo deve restare fermo prima di rifare gli smart con una descrizione e senza periodo. */
  contentQuiet: Duration = CONTENT_QUIET,
  /** Correzioni F (R19): quante ricerche per contenuto per giro, e quanto dura un giro. Con pochi album non cambia niente. */
  contentPerRound: Int = ContentBudget.PER_ROUND,
  contentRound: Duration = ContentBudget.ROUND,
) {
  /** Gli smart il cui calcolo e' rimasto a meta' (budget finito, errore): si riprendono da [drain]. */
  private val unfinished = ConcurrentHashMap.newKeySet<AlbumId>()

  /** I media dei cambiamenti del catalogo che il budget ha lasciato senza ricalcolo (giorni saltati). */
  private val skipped = ConcurrentHashMap.newKeySet<MediaId>()

  /** Gli smart con una descrizione e senza periodo che aspettano la fine della scansione (AS-6). */
  private val heavy = ConcurrentHashMap.newKeySet<AlbumId>()
  private val heavyTimer = Debouncer(scope, contentQuiet) { evaluateAll(takeHeavy()) }
  private val draining = AtomicBoolean(false)

  /** Il budget delle ricerche per contenuto: gli smart che non ci stanno aspettano il giro dopo e li valuta lui, senza passare da [drain]. */
  private val turns = ContentBudget(scope, contentPerRound, contentRound) { granted -> evaluateGranted(granted) }

  /** Resta qualcosa da riprendere. */
  val hasUnfinished: Boolean get() = unfinished.isNotEmpty() || skipped.isNotEmpty()

  /** Quanti smart con ricerca per contenuto aspettano il loro giro (Diagnostica: solo il numero). Si svuota da solo, un giro dopo l'altro. */
  val waitingForBudget: Int get() = turns.waiting

  private enum class Result { DONE, PARTIAL, FAILED }

  /** Le regole o le scelte di [album] sono cambiate (anche da un altro dispositivo): la valutazione intera. */
  suspend fun rulesChanged(album: AlbumId) = evaluateAll(listOf(album))

  /** Al montaggio (AS-5): ogni smart si valuta una volta. Quelli arrivati prima del montaggio, o con novita' mai viste dal motore, non aspettano la notte. */
  suspend fun resumeAll() = evaluateAll(rows.smartAlbums())

  /** La notte: ricalcolo di sicurezza di tutti. */
  suspend fun nightly() = evaluateAll(rows.smartAlbums())

  /** AS-4: la ricerca per contenuto e' diventata pronta: gli smart con una descrizione o delle foto d'esempio, che fin qui hanno taciuto, si rivalutano. */
  suspend fun searchReady() {
    val described = rows.smartAlbums().filter { id ->
      val state = rows.state(id)
      state != null && !state.frozen && state.rules.searchesContent()
    }
    evaluateAll(described)
  }

  /**
   * Fase 8 (D27, E-AL7): le persone sono cambiate (un'istantanea del raggruppamento nuova, un nome, un'unione, una correzione, una persona dimenticata, Persone attivata o
   * spenta qui): gli smart **con la regola delle persone** si rivalutano interi, uno alla volta (a lotti con il budget, come ogni valutazione: `drain` riprende quelli
   * rimasti a meta'). Dove i volti non ci sono il motore li lascia come sono (E-F37). Lo chiama `:app` (P13) quando `People.list` o lo stato di Persone cambiano.
   */
  suspend fun peopleChanged() {
    val withPeople = rows.smartAlbums().filter { id ->
      val state = rows.state(id)
      state != null && !state.frozen && state.rules.people != null
    }
    evaluateAll(withPeople)
  }

  /**
   * Il catalogo e' cambiato per [media] (un gruppo di novita' accorpato): gli smart che hanno righe su quei media si rivalutano interi (un media che
   * cambia giorno puo' uscirne), poi l'incrementale per gli altri. **I giorni che il budget ha lasciato** e i calcoli falliti si riprendono con [drain].
   */
  suspend fun catalogChanged(media: Collection<MediaId>) {
    if (media.isEmpty()) return
    // Gli smart con una ricerca per contenuto che questa chiamata ha gia' valutato (o messo in fila) non si rifanno nell'incrementale: una valutazione intera
    // vale piu' di una incrementale, e senza questo conto ogni smart userebbe due posti del giro.
    val handled = HashSet<AlbumId>()
    evaluateAll(smartAlbumsWith(media), handled)
    try {
      // Anche l'incrementale degli smart con periodo e ricerca per contenuto e' una ricerca sull'intera Libreria: passa dal budget (R19).
      val report = recompute.onCatalogChanged(media, emptyList(), budget, deferHeavy = true) { id -> id !in handled && turns.admit(listOf(id)).isNotEmpty() }
      if (report.skipped > 0) skipped += media
      if (report.deferred.isNotEmpty()) {
        heavy += report.deferred
        heavyTimer.poke()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      skipped += media
      onError(e)
    }
    sink.smartRowsChanged()
  }

  /**
   * Riprende cio' che e' rimasto a meta' (AS-5): ogni [gap] un altro passo, al piu' [maxPasses]. Uno alla volta (una seconda chiamata mentre la prima
   * lavora non fa niente). Alla fine gli smart che non si sono finiti non restano "in calcolo" per sempre: tornano a dire la verita' sulle righe che hanno.
   */
  suspend fun drain(gap: Duration = DRAIN_GAP, maxPasses: Int = MAX_PASSES) {
    if (!draining.compareAndSet(false, true)) return
    try {
      var pass = 0
      while (pass++ < maxPasses && hasUnfinished) {
        delay(gap)
        evaluateAll(unfinished.toList())
        val again = skipped.toList().also { skipped.clear() }
        if (again.isNotEmpty()) catalogChanged(again)
      }
    } finally {
      withContext(NonCancellable) {
        val leftover = unfinished.toList()
        unfinished.clear()
        skipped.clear()
        if (leftover.isNotEmpty()) sink.smartWorkFinished(leftover)
        draining.set(false)
      }
    }
  }

  // --- Interni ------------------------------------------------------------------------------------------------------------

  private fun takeHeavy(): List<AlbumId> = heavy.toList().also { heavy.removeAll(it.toSet()) }

  private suspend fun smartAlbumsWith(media: Collection<MediaId>): List<AlbumId> {
    val wanted = media.toHashSet()
    return rows.smartAlbums().filter { id ->
      val state = rows.state(id) ?: return@filter false
      !state.frozen && (state.auto.keys.any { it in wanted } || state.suggestions.keys.any { it in wanted })
    }
  }

  private suspend fun evaluateAll(albums: Collection<AlbumId>, handled: MutableSet<AlbumId>? = null) {
    val list = albums.distinct()
    if (list.isEmpty()) return
    handled?.addAll(list)
    val content = announce(list)
    // R19: gli smart con una ricerca per contenuto passano dal budget; quelli che non ci stanno aspettano il giro dopo (li valuta `turns`), senza passare da `drain`.
    val granted = turns.admit(content)
    val waiting = content.filter { it !in granted }.toSet()
    if (waiting.isNotEmpty()) unfinished.removeAll(waiting)
    evaluateGranted(if (waiting.isEmpty()) list else list.filter { it !in waiting })
  }

  /** Valuta [list]: tutti hanno gia' il loro posto nel budget (o non ne hanno bisogno). */
  private suspend fun evaluateGranted(list: List<AlbumId>) {
    if (list.isEmpty()) return
    val finished = ArrayList<AlbumId>()
    try {
      for (id in list) {
        when (evaluateOne(id)) {
          Result.DONE -> finished += id
          // Un errore non lascia la pagina a "cerco" per sempre: si riprova con drain, ma intanto dice cio' che ha.
          Result.FAILED -> finished += id
          // Un calcolo a meta' lavora ancora: resta "in calcolo" finche' drain non lo finisce.
          Result.PARTIAL -> Unit
        }
      }
    } finally {
      // Anche annullati: lo stato "in calcolo" di un album non deve restare appeso.
      withContext(NonCancellable) { if (finished.isNotEmpty()) sink.smartWorkFinished(finished) }
    }
  }

  /**
   * Gli smart ancora senza righe (nuovi, o con un risultato che non si e' mai visto): da adesso le loro pagine dicono che si sta cercando (AS-2), anche se
   * poi aspettano il loro giro (R19: cercano davvero, solo piu' tardi). Restituisce quelli che hanno una ricerca per contenuto, nell'ordine di [albums].
   */
  private suspend fun announce(albums: List<AlbumId>): List<AlbumId> {
    val empty = ArrayList<AlbumId>()
    val content = ArrayList<AlbumId>()
    for (id in albums) {
      val state = rows.state(id)
      if (state == null || state.frozen) {
        turns.forget(id)
        continue
      }
      if (state.auto.isEmpty() && state.suggestions.isEmpty()) empty += id
      if (state.rules.searchesContent()) content += id else turns.forget(id)
    }
    if (empty.isNotEmpty()) sink.smartWorkStarted(empty)
    return content
  }

  private suspend fun evaluateOne(id: AlbumId): Result = try {
    when (recompute.onRulesChanged(id, budget)) {
      is SmartOutcome.Partial -> {
        unfinished += id
        Result.PARTIAL
      }
      else -> {
        unfinished -= id
        Result.DONE
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    unfinished += id
    onError(e)
    Result.FAILED
  }

  companion object {
    /** Il tempo di un calcolo intero prima di fermarsi e riprendere (D16: a lotti). */
    val BUDGET: Duration = 10.seconds

    /** AS-6: gli smart con una descrizione e senza periodo si rifanno quando il catalogo e' stato fermo per tanto. */
    val CONTENT_QUIET: Duration = 2.minutes

    /** AS-5: ogni quanto riprende un calcolo lasciato a meta', e quante volte ci prova. */
    val DRAIN_GAP: Duration = 30.seconds
    const val MAX_PASSES: Int = 40
  }
}
