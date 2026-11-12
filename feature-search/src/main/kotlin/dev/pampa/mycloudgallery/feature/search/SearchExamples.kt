package dev.pampa.mycloudgallery.feature.search

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.asImageBitmap
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.ChipReadings
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchNotice
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.engine.LibrarySuggestions
import dev.pampa.mycloudgallery.feature.search.contract.SearchFaces
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// GLI ESEMPI CON LA FOTO (I2 della fase I, 11/11 sera). L'utente: «prima della ricerca i suggerimenti sono overwhelming, sarebbe meglio qualche esempio con
// una foto d'anteprima, sempre un po' ruotati, e con tanti risultati, in modo da essere buoni esempi»; poi: «cambiano a rotazione», «scelti fra quelli con
// tanti risultati», «ognuno con una foto d'anteprima».
//
//  - **Che cosa**: cio' che la sua libreria ha davvero in abbondanza, mescolando i tipi: cose che l'indice trova spesso («mare», «cibo»), persone con molte
//    foto, luoghi con molte foto, mesi pieni, i tipi che ci sono (video, screenshot...). Ognuno con la foto del suo **primo risultato** (per una persona il
//    volto, dal cancello dei volti), come Samsung fa per le ricerche recenti.
//  - **Quando**: si preparano **fuori dal thread principale e prima che servano** ([start]: qualche secondo dopo la prima istantanea della Libreria, mai
//    durante l'avvio), una passata sola sull'istantanea ([LibraryDigest]) e poche ricerche di prova sul motore ([probe]); si rifanno solo quando la
//    Libreria cambia davvero (non a ogni lotto del backup). Aprire la ricerca non interroga niente: prende la terna successiva ([strip]).
//  - **Rotazione**: a ogni apertura una terna diversa, senza ripetere quella di prima finche' ce ne sono altre ([ExampleRotation]). In testa la ricerca
//    recente piu' nuova, se c'e' (poi due esempi): le recenti restano a portata (anche mentre si scrive e quando non si trova niente).
//  - **Nascosti mai** (D29): le foto vengono dall'istantanea della Libreria (che non li contiene), passano dal filtro finale ([SearchData.hidden]) e i
//    risultati delle prove sono quelli del motore (che li esclude). La riga le disegna solo se sono ancora nell'istantanea di adesso.
//  - **I volti** (CG-1, giro 1): con il contratto delle Raccolte, in un posto solo ([SuggestionFaces]): gli esempi tengono la persona, mai il ritaglio; a
//    ogni revoca si buttano tutti i volti e anche le persone degli esempi, che si rifanno con il cancello di adesso ([onRevocation]).

/** L'anteprima di un suggerimento (I2): una foto della Libreria visibile o un volto. Senza, la riga disegna il segno del suo tipo. */
@Immutable
sealed interface SuggestionArt {
  /** La foto [media]: si disegna solo se e' nell'istantanea di adesso (un media nascosto o eliminato non c'e'). */
  data class Photo(val media: MediaId) : SuggestionArt

  /**
   * Il volto di [person] (CG-1): **solo chi e'**, mai il ritaglio. Il ritaglio sta in [SuggestionFaces], con il segno delle revoche e le foto d'origine:
   * si disegna solo se il cancello l'ha dato con il segno di adesso e se almeno una foto d'origine e' nella Libreria visibile. Senza, la foto [fallback]
   * (il primo risultato, con le regole delle foto), o il segno della persona.
   */
  class Face(val person: PersonId, val fallback: MediaId? = null) : SuggestionArt {
    override fun equals(other: Any?): Boolean = other is Face && other.person == person && other.fallback == fallback
    override fun hashCode(): Int = person.hashCode() * 31 + (fallback?.hashCode() ?: 0)

    /** Mai l'id (D37). */
    override fun toString(): String = "SuggestionArt.Face"
  }
}

/** Una voce della barra dei suggerimenti: il suggerimento e la sua anteprima. */
@Immutable
data class SuggestionItem(val suggestion: Suggestion, val art: SuggestionArt? = null)

/** Di che tipo e' un esempio: la rotazione li mescola. */
internal enum class ExampleKind { THING, PERSON, PLACE, MONTH, TYPE }

/** Un esempio con quanti risultati ha (sicuri, senza nascosti). */
internal data class Example(val item: SuggestionItem, val count: Int, val kind: ExampleKind) {
  val key: String get() = SearchText.normalize(item.suggestion.label)
}

/**
 * IL RIASSUNTO DELLA LIBRERIA per gli esempi (I2): in **una passata** sull'istantanea, per ogni luogo, mese e tipo quante celle ha e le piu' recenti (le
 * candidate per la foto). Puro e senza Compose: si prova su JVM. Gira fuori dal thread principale.
 */
internal class LibraryDigest private constructor(
  val snapshot: TimelineSnapshot,
  private val places: Map<Int, Tally>,
  private val months: Map<Int, Tally>,
  private val monthsOfYear: Map<Int, Tally>,
  private val types: Map<SearchMediaType, Tally>,
) {
  /** Quante celle e la piu' recente non nascosta (null se le candidate erano tutte nascoste). */
  class Tally(val count: Int, val newest: MediaId?, val newestTime: Long)

  val size: Int get() = snapshot.size

  /** Il luogo (o i luoghi, per una regione): la foto piu' recente fra tutti. */
  fun place(ids: Collection<Int>): Tally? {
    val found = ids.mapNotNull { places[it] }
    if (found.isEmpty()) return null
    val newest = found.filter { it.newest != null }.maxByOrNull { it.newestTime }
    return Tally(found.sumOf { it.count }, newest?.newest, newest?.newestTime ?: Long.MIN_VALUE)
  }

  /** Il mese `aaaamm`. */
  fun month(yyyymm: Int): Tally? = months[yyyymm]

  /** Lo stesso mese di ogni anno (1..12): «Agosto». */
  fun monthOfYear(month: Int): Tally? = monthsOfYear[month]

  fun type(type: SearchMediaType): Tally? = types[type]

  /** I mesi con piu' celle, dal piu' pieno. */
  fun fullestMonths(limit: Int): List<Pair<Int, Tally>> = months.entries.sortedByDescending { it.value.count }.take(limit).map { it.key to it.value }

  /** I tipi che ci sono, dal piu' frequente. */
  fun presentTypes(): List<Pair<SearchMediaType, Tally>> = types.entries.filter { it.value.count > 0 }.sortedByDescending { it.value.count }.map { it.key to it.value }

  /** Un accumulatore: il conteggio e le [CANDIDATES] celle piu' recenti (indici e istanti). */
  private class Acc {
    var count = 0
    val index = IntArray(CANDIDATES) { -1 }
    val time = LongArray(CANDIDATES) { Long.MIN_VALUE }

    fun add(i: Int, t: Long) {
      count++
      var slot = -1
      for (k in 0 until CANDIDATES) if (index[k] < 0 || t > time[k]) { slot = k; break }
      if (slot < 0) return
      for (k in CANDIDATES - 1 downTo slot + 1) {
        index[k] = index[k - 1]
        time[k] = time[k - 1]
      }
      index[slot] = i
      time[slot] = t
    }
  }

  companion object {
    /** Quante candidate si tengono per la foto: se la piu' recente e' appena stata nascosta c'e' la seguente. */
    const val CANDIDATES: Int = 3

    /** I tipi che fanno da esempio (le foto no: sono quasi tutto). */
    private val TYPES: List<SearchChip.MediaType> = LibrarySuggestions.TYPES.filter { it.first != SearchMediaType.PHOTO }
      .map { (type, label) -> SearchChip.MediaType(type, label) }

    /**
     * Il riassunto di [snapshot]. [hidden] e' il filtro finale dei nascosti (D29): l'istantanea non li contiene, ma un media nascosto altrove un attimo
     * fa puo' esserci ancora. Una sola chiamata, con tutte le candidate.
     */
    suspend fun of(snapshot: TimelineSnapshot, hidden: suspend (Collection<MediaId>) -> Set<MediaId>): LibraryDigest {
      val places = HashMap<Int, Acc>()
      val months = HashMap<Int, Acc>()
      val monthsOfYear = HashMap<Int, Acc>()
      val types = HashMap<SearchMediaType, Acc>()
      for (i in 0 until snapshot.size) {
        val t = snapshot.sortTimeAt(i)
        val place = snapshot.placeAt(i)
        if (place != 0) places.getOrPut(place) { Acc() }.add(i, t)
        val day = snapshot.localDayAt(i)
        if (day > 0) {
          val yyyymm = day / 100
          if (yyyymm % 100 in 1..12) {
            months.getOrPut(yyyymm) { Acc() }.add(i, t)
            monthsOfYear.getOrPut(yyyymm % 100) { Acc() }.add(i, t)
          }
        }
        for (chip in TYPES) if (ChipMatching.accepts(chip, snapshot, i)) types.getOrPut(chip.type) { Acc() }.add(i, t)
      }
      val all = listOf(places.values, months.values, monthsOfYear.values, types.values).flatten()
      val candidates = all.flatMap { acc -> acc.index.filter { it >= 0 } }.distinct().map { snapshot.idAt(it) }
      val secret = if (candidates.isEmpty()) emptySet() else hidden(candidates)
      fun Acc.tally(): Tally {
        for (k in 0 until CANDIDATES) {
          val i = index[k]
          if (i < 0) break
          val id = snapshot.idAt(i)
          if (id !in secret) return Tally(count, id, time[k])
        }
        return Tally(count, null, Long.MIN_VALUE)
      }
      return LibraryDigest(
        snapshot,
        places.mapValues { it.value.tally() },
        months.mapValues { it.value.tally() },
        monthsOfYear.mapValues { it.value.tally() },
        types.mapValues { it.value.tally() },
      )
    }
  }
}

/** Le regole pure degli esempi (I2): quali tenere e in che ordine. Senza coroutine ne' Compose. */
internal object ExamplePool {
  /** «Con tanti risultati»: un esempio con meno di cosi' non e' un buon esempio. */
  const val MIN_RESULTS: Int = 12

  /** In una libreria piccola (le prime foto) si accontenta di meno, purche' l'esempio trovi qualcosa. */
  const val MIN_RESULTS_SMALL: Int = 3

  /** Sotto quanti esempi forti si passa alla soglia piccola. */
  private const val MIN_POOL = 4

  /** Quanti esempi per tipo, al piu'. */
  private fun cap(kind: ExampleKind): Int = when (kind) {
    ExampleKind.THING -> 6
    ExampleKind.PERSON -> 4
    ExampleKind.PLACE -> 4
    ExampleKind.MONTH -> 3
    ExampleKind.TYPE -> 2
  }

  /** L'ordine in cui i tipi si alternano: una cosa, una persona, un luogo, un mese, un tipo, e di nuovo. */
  private val ORDER = listOf(ExampleKind.THING, ExampleKind.PERSON, ExampleKind.PLACE, ExampleKind.MONTH, ExampleKind.TYPE)

  /**
   * Gli esempi da far ruotare: quelli con almeno [MIN_RESULTS] risultati (in una libreria piccola [MIN_RESULTS_SMALL]), i piu' ricchi per tipo, **mescolati**
   * (un tipo dopo l'altro), senza due con la stessa parola.
   */
  fun of(candidates: List<Example>): List<Example> {
    val strong = candidates.filter { it.count >= MIN_RESULTS }
    val chosen = if (strong.size >= MIN_POOL) strong else candidates.filter { it.count >= MIN_RESULTS_SMALL }
    val byKind = chosen.groupBy { it.kind }.mapValues { (kind, list) -> list.sortedByDescending { it.count }.distinctBy { it.key }.take(cap(kind)) }
    val out = ArrayList<Example>()
    var round = 0
    while (true) {
      var added = false
      for (kind in ORDER) {
        byKind[kind]?.getOrNull(round)?.let {
          out += it
          added = true
        }
      }
      if (!added) break
      round++
    }
    return out.distinctBy { it.key }
  }
}

/**
 * La rotazione (I2): a ogni apertura la terna successiva, senza ripetere quella di prima finche' ce ne sono altre. [current] ridà la terna di adesso (il
 * campo svuotato, le recenti aggiornate) senza farla girare.
 */
internal class ExampleRotation {
  private var start = 0
  private var end = 0

  /** La finestra seguente di [count] esempi di [pool], saltando quelli in [skip] (le parole gia' nella barra). */
  fun next(pool: List<Example>, count: Int, skip: Set<String>): List<Example> {
    start = end
    return window(pool, count, skip)
  }

  /** La finestra di adesso. */
  fun current(pool: List<Example>, count: Int, skip: Set<String>): List<Example> = window(pool, count, skip)

  private fun window(pool: List<Example>, count: Int, skip: Set<String>): List<Example> {
    if (pool.isEmpty() || count <= 0) {
      end = start
      return emptyList()
    }
    val out = ArrayList<Example>(count)
    var i = start % pool.size
    var steps = 0
    while (out.size < count && steps < pool.size) {
      val e = pool[i]
      if (e.key !in skip && out.none { it.key == e.key }) out += e
      i = (i + 1) % pool.size
      steps++
    }
    end = i
    return out
  }
}

/**
 * GLI ESEMPI E LE ANTEPRIME DELLA RICERCA (I2). Vive con il [SearchController] (il suo ViewModel); il lavoro pesante gira su [compute]. Le chiamate
 * pubbliche arrivano dal contesto del controller (il thread principale in produzione), e lo stato si tocca solo li'.
 */
class SearchExamples(
  private val engine: SearchEngine,
  private val data: SearchData?,
  private val faces: SearchFaces?,
  private val scope: CoroutineScope,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
  private val prepareDelayMillis: Long = PREPARE_DELAY_MILLIS,
  private val facePx: Int = FACE_PX,
) {
  private var digest: LibraryDigest? = null
  private var pool: List<Example> = emptyList()
  private val rotation = ExampleRotation()
  private var started = false

  /** Le persone di riposo con quanti risultati hanno (dalle prove sul motore), per riassunto. */
  private var peopleCounts: Map<PersonId, Pair<Int, MediaId?>> = emptyMap()
  private var peopleOf: LibraryDigest? = null

  /** Le cose che l'indice trova (dalle prove sul motore): parola normalizzata -> (la parola, quanti, il primo). */
  private val things = HashMap<String, Triple<String, Int, MediaId?>>()
  private var thingsOf: LibraryDigest? = null

  /** Il primo risultato delle ricerche di questa sessione e delle prove: testo normalizzato -> (quanti, il primo). */
  private val firsts = object : LinkedHashMap<String, Pair<Int, MediaId>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, MediaId>>?): Boolean = size > MAX_FIRSTS
  }
  private val probing = HashSet<String>()

  /**
   * CG-1: i volti delle anteprime, in un posto solo e con il segno delle revoche ([SuggestionFaces]). La barra li legge da qui; niente altro tiene un
   * ritaglio.
   */
  val faceImages: SuggestionFaces = SuggestionFaces()

  /** A chi e' gia' stato chiesto il volto e con quale segno: non si richiede a ogni tasto, si richiede dopo una revoca o a un'apertura. */
  private val asked = HashMap<PersonId, Any?>()

  /** I suggerimenti a riposo (da [start]): servono a rifare le persone dopo una revoca. */
  private var idleSource: (suspend () -> IdleSuggestions)? = null

  private val mutableUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  /** Qualcosa e' cambiato (gli esempi pronti, un'anteprima arrivata): chi mostra la barra rilegge. */
  val updates: SharedFlow<Unit> = mutableUpdates

  /** Vero quando gli esempi sono pronti. */
  val ready: Boolean get() = pool.isNotEmpty()

  /** Comincia a preparare: qualche secondo dopo la prima istantanea, poi solo quando la Libreria cambia davvero. [idle] da' i suggerimenti a riposo. */
  fun start(idle: suspend () -> IdleSuggestions) {
    if (started) return
    started = true
    idleSource = idle
    // CG-1: le revoche dei volti si seguono anche senza Libreria (le recenti con una persona hanno il volto).
    faces?.let { port -> scope.launch { port.revocations.collect { token -> onRevocation(token) } } }
    val source = data ?: return
    scope.launch {
      source.snapshot.filterNotNull().first()
      delay(prepareDelayMillis)
      source.snapshot.filterNotNull().conflate().collect { snapshot ->
        if (!changedEnough(snapshot)) return@collect
        val now = safeIdle(idle)
        prepare(snapshot, now)
        // Il motore del contenuto era gia' pronto (una ricerca prima del riassunto): le cose si provano adesso.
        if (engine.readiness.value == SearchReadiness.Ready) probeThings(now)
        // La dose: mentre il catalogo cresce (il backup, la prima lettura) al piu' un riassunto ogni tanto, e sempre l'ultimo.
        delay(REFRESH_DOSE_MILLIS)
      }
    }
    // Le cose si provano solo con il motore del contenuto pronto (dopo la prima apertura): prima la prova cercherebbe solo i filtri.
    scope.launch {
      engine.readiness.collect { now ->
        if (now == SearchReadiness.Ready) {
          delay(THINGS_DELAY_MILLIS)
          probeThings(safeIdle(idle))
        }
      }
    }
  }

  /**
   * CG-1: un segno nuovo delle revoche (il primo, quando si comincia, e ognuno dopo: cancellazione locale, Persone spenta o rimontata, ritagli svuotati).
   * Nello stesso giro, prima di ogni attesa: via **tutti** i volti ([SuggestionFaces.revoke]) e le domande fatte; con una revoca vera via anche le persone
   * dagli esempi (la terna di adesso e quella della prossima apertura), perche' i loro nomi potrebbero non valere piu' (Persone spenta). Poi la barra
   * rilegge e le persone si rifanno dalle sorgenti di adesso, con il cancello di adesso: un volto torna solo se il cancello lo ripermette.
   */
  private fun onRevocation(token: Any?) {
    val first = !hadToken
    hadToken = true
    faceImages.revoke(token)
    asked.clear()
    if (first) return
    if (pool.any { it.kind == ExampleKind.PERSON }) pool = pool.filter { it.kind != ExampleKind.PERSON }
    peopleCounts = emptyMap()
    peopleOf = null
    mutableUpdates.tryEmit(Unit)
    val idle = idleSource ?: return
    val current = digest ?: return
    scope.launch {
      val now = safeIdle(idle)
      // Il riassunto e' cambiato nel frattempo: le persone le rifa' gia' il suo giro.
      if (digest !== current) return@launch
      probePeople(now.people, current)
      rebuild(now)
    }
  }

  /** Vero dopo il primo segno delle revoche. */
  private var hadToken = false

  private suspend fun safeIdle(idle: suspend () -> IdleSuggestions): IdleSuggestions = try {
    idle()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    IdleSuggestions(emptyList(), emptyList(), emptyList())
  }

  /** La Libreria e' cambiata abbastanza da rifare il riassunto (non a ogni preferito o a ogni lotto del backup che cambia i flag). */
  private fun changedEnough(snapshot: TimelineSnapshot): Boolean {
    val before = digest ?: return true
    if (before.snapshot === snapshot) return false
    val delta = abs(snapshot.size - before.size)
    return delta >= maxOf(MIN_CHANGE, before.size / CHANGE_FRACTION)
  }

  /** Il riassunto, le persone e gli esempi per [snapshot]. Le parti pesanti su [compute]. */
  private suspend fun prepare(snapshot: TimelineSnapshot, idle: IdleSuggestions) {
    val hidden: suspend (Collection<MediaId>) -> Set<MediaId> = { ids -> data?.hidden(ids).orEmpty() }
    val next = try {
      withContext(compute) { LibraryDigest.of(snapshot, hidden) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return
    }
    digest = next
    probePeople(idle.people, next)
    rebuild(idle)
  }

  /** Le persone di riposo: quanti risultati e il primo (la foto se il volto non c'e'). */
  private suspend fun probePeople(people: List<PersonOption>, current: LibraryDigest) {
    if (peopleOf === current && people.all { p -> p.ids.first() in peopleCounts }) return
    val counts = HashMap<PersonId, Pair<Int, MediaId?>>()
    for (option in people) {
      val found = probe(SearchRequest(option.label, listOf(ChipReadings.ofOption(option)), order = ResultOrder.RELEVANT)) ?: continue
      counts[option.ids.first()] = found.confident.size to firstOf(found)
    }
    peopleCounts = counts
    peopleOf = current
  }

  /** Le cose: una prova per parola, solo quando il motore del contenuto risponde davvero. */
  private suspend fun probeThings(idle: IdleSuggestions) {
    val current = digest ?: return
    if (thingsOf === current && things.isNotEmpty()) return
    for (word in THINGS) {
      val found = probe(SearchRequest(word, order = ResultOrder.RELEVANT)) ?: return
      // Il motore non aveva il contenuto (si e' rilasciato nel frattempo): si riprova al prossimo "pronto".
      if (found.notices.any { it == SearchNotice.PreparingContentSearch || it == SearchNotice.OnlyFiltersAndText }) return
      things[SearchText.normalize(word)] = Triple(word, found.confident.size, firstOf(found))
    }
    thingsOf = current
    rebuild(idle)
  }

  private suspend fun probe(request: SearchRequest): SearchResults? = try {
    engine.search(request)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  private fun firstOf(results: SearchResults): MediaId? =
    if (results.confident.size > 0) results.confident.snapshot.idAt(results.confident.snapshotIndex(0)) else null

  /** Gli esempi dai pezzi pronti; la rotazione resta dove era. */
  private suspend fun rebuild(idle: IdleSuggestions) {
    val current = digest ?: return
    val candidates = ArrayList<Example>()
    for ((word, count, first) in things.values) {
      candidates += Example(SuggestionItem(Suggestion.Term(word), first?.let { SuggestionArt.Photo(it) }), count, ExampleKind.THING)
    }
    for (option in idle.people) {
      val (count, first) = peopleCounts[option.ids.first()] ?: continue
      candidates += Example(SuggestionItem(Suggestion.PersonSuggestion(option), personArt(option.ids.first(), first)), count, ExampleKind.PERSON)
    }
    for (place in idle.places) {
      val tally = current.place(place.ids) ?: continue
      candidates += Example(SuggestionItem(Suggestion.PlaceSuggestion(place), tally.newest?.let { SuggestionArt.Photo(it) }), tally.count, ExampleKind.PLACE)
    }
    for ((yyyymm, tally) in current.fullestMonths(FULLEST_MONTHS)) {
      candidates += Example(SuggestionItem(Suggestion.Term(monthLabel(yyyymm)), tally.newest?.let { SuggestionArt.Photo(it) }), tally.count, ExampleKind.MONTH)
    }
    val labels = LibrarySuggestions.TYPES.toMap()
    for ((type, tally) in current.presentTypes()) {
      val label = labels[type] ?: continue
      candidates += Example(SuggestionItem(Suggestion.Type(type, label), tally.newest?.let { SuggestionArt.Photo(it) }), tally.count, ExampleKind.TYPE)
    }
    pool = ExamplePool.of(candidates)
    mutableUpdates.tryEmit(Unit)
  }

  /**
   * La barra a campo vuoto: due esempi e la ricerca recente piu' nuova (con la sua anteprima), fino a [size]. Gli esempi vengono **prima**: a 360 dp, se
   * le tre voci non entrano intere (CU-2), restano le foto d'esempio, che sono cio' che l'utente ha chiesto; la recente si ritrova anche scrivendo e quando
   * non si trova niente. Con [advance] la terna successiva (una nuova apertura), altrimenti quella di adesso. Gli esempi non ancora pronti (i primi
   * secondi dopo l'avvio): i suggerimenti di prima, con le anteprime che ci sono.
   */
  suspend fun strip(idle: IdleSuggestions, advance: Boolean, size: Int = SearchController.STRIP_SIZE): List<SuggestionItem> {
    val recent = idle.recents.firstOrNull()?.let { Suggestion.Recent(it) }
    val tail = listOfNotNull(recent)
    if (pool.isEmpty()) return withArt(SearchStates.idleTop(idle))
    val skip = tail.mapTo(HashSet()) { SearchText.normalize(it.label) }
    val chosen = if (advance) rotation.next(pool, size - tail.size, skip) else rotation.current(pool, size - tail.size, skip)
    val items = chosen.map { it.item } + withArt(tail)
    // Una nuova apertura: il cancello riconferma i volti della terna (una foto d'origine tornata visibile lo ripermette, una sparita lo toglie).
    if (advance) items.forEach { item -> (item.art as? SuggestionArt.Face)?.let { authorize(it.person, recheck = true) } }
    return items
  }

  /** Gli esempi per quando la ricerca non trova niente e non c'e' nessuna ricerca recente: gli stessi della barra, senza farli girare. */
  fun examplesNow(size: Int = SearchController.STRIP_SIZE): List<SuggestionItem> = rotation.current(pool, size, emptySet()).map { it.item }

  /** Le anteprime di [suggestions] (i completamenti mentre si scrive, le recenti): solo cio' che e' gia' pronto, piu' i volti. */
  suspend fun withArt(suggestions: List<Suggestion>): List<SuggestionItem> = suggestions.map { SuggestionItem(it, artOf(it)) }

  private fun artOf(suggestion: Suggestion): SuggestionArt? {
    val current = digest
    return when (suggestion) {
      is Suggestion.PlaceSuggestion -> current?.place(suggestion.option.ids)?.newest?.let { SuggestionArt.Photo(it) }
      is Suggestion.Type -> current?.type(suggestion.type)?.newest?.let { SuggestionArt.Photo(it) }
      is Suggestion.PersonSuggestion -> personArt(suggestion.option.ids.first(), peopleCounts[suggestion.option.ids.first()]?.second)
      is Suggestion.Recent -> recentArt(suggestion.entry)
      is Suggestion.Term -> termArt(suggestion.label, current)
    }
  }

  /** Una ricerca recente: il volto della sua persona, o la foto del suo primo risultato (di questa sessione, o di una prova fatta adesso in sottofondo). */
  private fun recentArt(entry: HistoryEntry): SuggestionArt? {
    val key = SearchText.normalize(entry.text)
    val photo = firsts[key]?.second ?: (termArt(entry.text, digest) as? SuggestionArt.Photo)?.media
    if (photo == null) probeLater(entry.text)
    val person = entry.persons.firstOrNull()
    return if (person != null) personArt(person, photo) else photo?.let { SuggestionArt.Photo(it) }
  }

  /** Una parola: una cosa provata, o un mese («Agosto», «Agosto 2024»). Le feste restano col segno del calendario. */
  private fun termArt(label: String, current: LibraryDigest?): SuggestionArt? {
    val key = SearchText.normalize(label)
    things[key]?.third?.let { return SuggestionArt.Photo(it) }
    firsts[key]?.let { return SuggestionArt.Photo(it.second) }
    current ?: return null
    val words = key.split(' ').filter { it.isNotBlank() }
    val month = words.firstOrNull()?.let { MONTH_KEYS.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: return null
    val year = words.getOrNull(1)?.toIntOrNull()
    val tally = if (year != null) current.month(year * 100 + month) else current.monthOfYear(month)
    return tally?.newest?.let { SuggestionArt.Photo(it) }
  }

  /** La foto del primo risultato di [text] con una prova in sottofondo, una volta per testo per sessione; poi la barra rilegge. */
  private fun probeLater(text: String) {
    val key = SearchText.normalize(text)
    if (key.isEmpty() || !probing.add(key)) return
    scope.launch {
      val found = probe(SearchRequest(text, order = ResultOrder.RELEVANT)) ?: return@launch
      val first = firstOf(found) ?: return@launch
      firsts[key] = found.confident.size to first
      mutableUpdates.tryEmit(Unit)
    }
  }

  /** Una ricerca fatta dall'utente: il suo primo risultato fa da anteprima quando torna fra le recenti. */
  fun remember(text: String, results: SearchResults) {
    val first = firstOf(results) ?: return
    firsts[SearchText.normalize(text)] = results.confident.size to first
  }

  /**
   * L'anteprima di una persona: senza la porta dei volti la foto [fallback]; con la porta la persona (il ritaglio lo tiene [faceImages]) e intanto si chiede
   * il volto al cancello, se non e' gia' stato chiesto con il segno di adesso.
   */
  private fun personArt(person: PersonId, fallback: MediaId?): SuggestionArt? {
    if (faces == null) return fallback?.let { SuggestionArt.Photo(it) }
    authorize(person)
    return SuggestionArt.Face(person, fallback)
  }

  /**
   * CG-1: il volto di [person] dal cancello dei volti, fuori dal thread principale. Il segno delle revoche si prende **prima** della domanda: se cambia
   * mentre il ritaglio e' in volo, la risposta non entra ([SuggestionFaces.admit]). Un rifiuto toglie il volto. Una volta per persona e per segno, o di
   * nuovo con [recheck] (un'apertura).
   */
  private fun authorize(person: PersonId, recheck: Boolean = false) {
    val port = faces ?: return
    val token = faceImages.currentToken
    if (!recheck && asked.containsKey(person) && asked[person] == token) return
    asked[person] = token
    scope.launch {
      val got = try {
        withContext(compute) { port.face(person, facePx)?.let { face -> face.bitmap.asImageBitmap() to face.origins } }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
      if (got == null || got.second.isEmpty()) faceImages.refuse(person, token) else faceImages.admit(person, got.first, got.second, token)
    }
  }

  companion object {
    /** Dopo la prima istantanea si aspetta un po': l'avvio e il primo disegno della Libreria vengono prima. */
    const val PREPARE_DELAY_MILLIS: Long = 4_000

    /** Al piu' un riassunto ogni tanto mentre il catalogo cresce. */
    const val REFRESH_DOSE_MILLIS: Long = 30_000

    /** Il motore del contenuto e' pronto: le prove delle cose aspettano un attimo (la prima ricerca dell'utente viene prima). */
    const val THINGS_DELAY_MILLIS: Long = 2_500

    /** Un riassunto nuovo quando la Libreria cambia di almeno tanti media, o di un cinquantesimo. */
    private const val MIN_CHANGE = 50
    private const val CHANGE_FRACTION = 50

    private const val FULLEST_MONTHS = 6
    private const val MAX_FIRSTS = 64

    /** Il lato dei volti in pixel (il tondo della barra e' 32 dp: a 3x sono 96 px). */
    const val FACE_PX: Int = 128

    /**
     * Le cose da provare: parole che l'analizzatore lascia al contenuto (non mesi, feste, luoghi o tipi), comuni nelle foto di famiglia. Un elenco
     * **fisso** scritto qui: mai parole del testo letto (D29).
     */
    val THINGS: List<String> = listOf(
      "mare", "spiaggia", "montagna", "neve", "tramonto", "cibo", "pizza", "torta", "cane", "gatto", "fiori", "bosco", "lago", "città", "concerto",
      "bicicletta",
    )

    private val MONTH_KEYS: List<String> = CalendarCompletions.MONTHS.map { SearchText.normalize(it) }

    /** L'etichetta di un mese `aaaamm`: «Agosto 2024». */
    fun monthLabel(yyyymm: Int): String = CalendarCompletions.MONTHS[(yyyymm % 100) - 1] + " " + (yyyymm / 100)
  }
}
