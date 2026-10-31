package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.ShelfState
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryClips
import dev.pampa.mycloudgallery.core.memories.noop.NoMusicLibrary
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMusicSettings
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesListState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.NoMusicPlayback
import dev.pampa.mycloudgallery.feature.memories.contract.NoPlayerImages
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerState
import dev.pampa.mycloudgallery.feature.memories.list.AllMemoriesController
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageController
import dev.pampa.mycloudgallery.feature.memories.player.PlayerController
import dev.pampa.mycloudgallery.feature.memories.player.PlayerPace
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionModel
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionStates
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// Fase 9 (P9, D31, E-MEM5, E-H8, E-H10; 30/10): le SUPERFICI VERE dei ricordi (sezione, pagina, "Tutti i ricordi", presentazione) sopra il motore vero, con il
// **filtro finale dei Nascosti** acceso o spento (lo spegne solo la prova al contrario: la proprieta' deve diventare rossa).
//
// La storia: il motore ha pubblicato i ricordi; **dopo**, il catalogo nasconde alcuni dei loro elementi (la copertina e uno in mezzo, di qualche ricordo) con la
// sola scrittura in `media_personal`, senza che l'istantanea e lo scaffale lo sappiano ancora (nascosto un attimo fa, E-MEM5). Ogni superficie deve togliere
// i nascosti e i membri dei loro gruppi, e dire i numeri giusti: l'oracolo viene dalla **descrizione** del mondo, mai dal codice che si prova.

/** Cosa le superfici hanno mostrato e le violazioni trovate (frasi senza id). */
internal class SurfaceReport(val shown: MutableSet<MediaId> = LinkedHashSet(), val problems: MutableList<String> = ArrayList())

internal class MemoriesReport(val section: SurfaceReport, val page: SurfaceReport, val player: SurfaceReport, val people: SurfaceReport)

internal object RealMemoriesSurfaces {

  private val pace = PlayerPace(intro = 1, photo = 2, maskWait = 30_000, clipWait = 1_000)

  /** Quanti ricordi, nascosti dopo e persone la prova ha davvero guardato (un controllo che non vede mai niente non controlla niente). */
  object Stats {
    var worlds = 0
    var memories = 0
    val kinds = HashSet<MemoryKind>()
    var victims = 0
    var pagesReady = 0
    var pagesGone = 0
    var presentations = 0
    var personMemories = 0

    fun reset() {
      worlds = 0
      memories = 0
      kinds.clear()
      victims = 0
      pagesReady = 0
      pagesGone = 0
      presentations = 0
      personMemories = 0
    }
  }

  /** Gli elementi di un ricordo come li ha il servizio adesso (la vista sull'istantanea), nell'ordine. */
  private suspend fun membersOf(stack: RealMemoriesStack, card: MemoryCard): List<MediaId> {
    val view = stack.engine.memory(card.id).first() ?: return emptyList()
    return (0 until view.view.size).map { view.view.snapshot.idAt(view.view.snapshotIndex(it)) }
  }

  /** I nascosti dell'ultimo momento: la copertina e uno in mezzo di qualche ricordo, scritti **solo** nel catalogo (nessun giro, nessuna pulizia). */
  private suspend fun hideAfterPublishing(stack: RealMemoriesStack, cards: List<MemoryCard>): Set<MediaId> {
    val victims = LinkedHashSet<MediaId>()
    for (card in cards.take(MAX_VICTIM_CARDS)) {
      val members = membersOf(stack, card)
      if (members.size < Memory.MIN_MEMBERS) continue
      victims += members.first()
      victims += members[members.size / 2]
    }
    var at = HIDE_TIME
    for (id in victims) stack.w.db.personalDao().upsert(MediaPersonalEntity(id.value, favorite = false, hidden = true, updatedAt = at++))
    return victims
  }

  suspend fun evaluate(stack: RealMemoriesStack, finalFilter: Boolean): MemoriesReport {
    val report = MemoriesReport(SurfaceReport(), SurfaceReport(), SurfaceReport(), SurfaceReport())
    stack.refresh()
    // Lo scaffale che le superfici vedono si rifa' da solo sul suo scope, un istante dopo il giro: si aspetta lo stato preciso (le voci del file appena scritto),
    // mai una lettura subito dopo l'azione asincrona.
    val published = stack.engine.current()?.memories?.map { it.id }.orEmpty()
    val shelf = withTimeout(WAIT) { stack.engine.shelf.first { it.items.map { card -> card.id } == published && (published.isNotEmpty() || it.state !is ShelfState.Working) } }
    val cards = shelf.items
    Stats.worlds++
    Stats.memories += cards.size
    cards.forEach { Stats.kinds += it.kind }

    // E-H10: una persona nascosta non ha ricordi, e i ricordi di una persona non portano nascosti.
    val persons = cards.filter { it.kind == MemoryKind.PERSON }
    Stats.personMemories += persons.size
    if (persons.any { it.title.person == stack.hiddenPerson }) report.people.problems += "c'e' un ricordo di una persona nascosta"

    val victims = hideAfterPublishing(stack, cards)
    Stats.victims += victims.size
    // Cio' che non deve piu' uscire: i nascosti e tutti i membri dei loro gruppi (E-L11), dall'oracolo della descrizione.
    val never = foreverHidden(stack, victims)
    val scope = singleThreaded()
    val names = MemoryNames(stack.people, NoMemoryPlaceNames)
    // Senza il filtro finale (la mutazione che la prova deve vedere): la maschera che non nasconde niente, perche' `FinalFilter` non ne ammette l'assenza (R3).
    val filter = FinalFilter(if (finalFilter) stack.mask else HiddenMask { emptySet() })
    try {
      val expected = cards.associate { card -> card.id to membersOf(stack, card).filter { it !in never } }

      section(stack, names, filter, scope, cards, expected, never, report.section)
      pages(stack, names, filter, scope, cards, expected, never, report.page, report.people)
      list(stack, names, filter, scope, cards, expected, never, report.page)
      presentations(stack, names, filter, scope, cards, expected, never, report.player)
    } finally {
      scope.cancel()
    }
    return report
  }

  /** I media che non devono comparire: i nascosti del mondo, i loro gruppi e i nascosti dell'ultimo momento con i loro gruppi. */
  private fun foreverHidden(stack: RealMemoriesStack, victims: Set<MediaId>): Set<MediaId> {
    val spec = stack.w.spec
    val extended = WorldSpec(
      spec.seed, spec.media, spec.groups, spec.hiddenByUser + victims.map { stack.w.indexOf(it) }, spec.hiddenPartial, spec.favorites, spec.lateHidden, spec.sameSideCopies,
    )
    return stack.w.idsOf(HiddenOracle(extended).liveMustNeverShow)
  }

  private suspend fun section(
    stack: RealMemoriesStack,
    names: MemoryNames,
    filter: FinalFilter,
    scope: CoroutineScope,
    cards: List<MemoryCard>,
    expected: Map<MemoryId, List<MediaId>>,
    never: Set<MediaId>,
    out: SurfaceReport,
  ) {
    val sources = MemoriesSectionModel(stack.engine, names, filter, scope)
    // Lo stato preciso che la sezione deve raggiungere: lo scaffale com'e' ripulito dal filtro finale (arriva un istante dopo la prima immagine, che e' lo scaffale
    // com'e'). Poi i conti si confrontano con l'oracolo, che non dipende dal codice che si prova.
    val shelf = stack.engine.shelf.value
    val target = MemoriesSectionStates.section(shelf, filter.refine(stack.engine, shelf.items))
    if (!awaitState(out, "la sezione non si e' assestata", sources.sectionState) { it == target }) return
    val state = sources.sectionState.value
    if (state is MemoriesSectionState.Ready) {
      for (card in state.cards) {
        val members = expected[card.id].orEmpty()
        out.shown += card.cover
        if (card.cover in never) out.problems += "la copertina di una voce della sezione e' un media nascosto"
        if (card.count != members.size) out.problems += "una voce della sezione conta ${card.count} elementi, ne attendeva ${members.size}"
        if (members.size < Memory.MIN_MEMBERS) out.problems += "una voce della sezione ha meno di 3 elementi visibili"
      }
    }
    // Il codice di una voce si risolve in un ricordo che c'e'.
    (state as? MemoriesSectionState.Ready)?.cards?.forEach { card ->
      if (sources.resolve(MemoriesSectionStates.codeOf(card.id)) != card.id) out.problems += "una voce della sezione non si risolve nel suo ricordo"
    }
  }

  private suspend fun pages(
    stack: RealMemoriesStack,
    names: MemoryNames,
    filter: FinalFilter,
    scope: CoroutineScope,
    cards: List<MemoryCard>,
    expected: Map<MemoryId, List<MediaId>>,
    never: Set<MediaId>,
    out: SurfaceReport,
    people: SurfaceReport,
  ) {
    for (card in cards) {
      val page = MemoryPageController(card.id, stack.engine, names, filter, scope)
      val want = expected[card.id].orEmpty()
      if (!awaitState(out, "una pagina non si e' assestata", page.ui) { it.page != MemoryPageState.Loading }) continue
      when (val state = page.ui.value.page) {
        is MemoryPageState.Ready -> {
          Stats.pagesReady++
          val shown = (0 until state.view.size).map { state.view.snapshot.idAt(state.view.snapshotIndex(it)) }
          out.shown += shown
          out.shown += state.cover
          // I ricordi di una persona (E-H10) sono anche una superficie a se': cio' che la pagina di una persona mostra.
          if (card.kind == MemoryKind.PERSON) {
            people.shown += shown
            people.shown += state.cover
          }
          if (shown.any { it in never }) out.problems += "la pagina di un ricordo mostra un media nascosto"
          if (state.cover in never) out.problems += "la copertina della pagina di un ricordo e' un media nascosto"
          if (shown.toSet() != want.toSet()) out.problems += "la pagina di un ricordo ha ${shown.size} elementi, ne attendeva ${want.size}"
          if (want.size < Memory.MIN_MEMBERS) out.problems += "una pagina con meno di 3 elementi visibili e' ancora aperta"
        }
        MemoryPageState.Gone -> {
          Stats.pagesGone++
          if (want.size >= Memory.MIN_MEMBERS) out.problems += "una pagina dice che il ricordo non c'e' piu' ma ha ${want.size} elementi visibili"
        }
        else -> out.problems += "una pagina e' in uno stato inatteso"
      }
    }
  }

  private suspend fun list(
    stack: RealMemoriesStack,
    names: MemoryNames,
    filter: FinalFilter,
    scope: CoroutineScope,
    cards: List<MemoryCard>,
    expected: Map<MemoryId, List<MediaId>>,
    never: Set<MediaId>,
    out: SurfaceReport,
  ) {
    val all = AllMemoriesController(stack.engine, names, filter, scope)
    val wanted = cards.filter { (expected[it.id]?.size ?: 0) >= Memory.MIN_MEMBERS }
    if (!awaitState(out, "\"Tutti i ricordi\" non si e' assestato", all.ui) { it.snapshot != null || wanted.isEmpty() && it.state is MemoriesListState.Empty }) return
    val shown = (all.ui.value.state as? MemoriesListState.Ready)?.byMonth.orEmpty().flatMap { it.cards }
    for (card in shown) {
      out.shown += card.cover
      if (card.cover in never) out.problems += "la copertina di una voce di \"Tutti i ricordi\" e' un media nascosto"
      if (card.count != (expected[card.id]?.size ?: -1)) out.problems += "una voce di \"Tutti i ricordi\" ha un conto sbagliato"
    }
    if (shown.map { it.id }.toSet() != wanted.map { it.id }.toSet()) out.problems += "\"Tutti i ricordi\" ha ${shown.size} voci, ne attendeva ${wanted.size}"
  }

  private suspend fun presentations(
    stack: RealMemoriesStack,
    names: MemoryNames,
    filter: FinalFilter,
    scope: CoroutineScope,
    cards: List<MemoryCard>,
    expected: Map<MemoryId, List<MediaId>>,
    never: Set<MediaId>,
    out: SurfaceReport,
  ) {
    for (card in cards.take(MAX_PRESENTATIONS)) {
      val want = expected[card.id].orEmpty()
      val playerScope = singleThreaded()
      val player = PlayerController(
        card.id, stack.engine, names, filter, NoMemoryClips, NoPlayerImages, InMemoryMusicSettings(), NoMusicLibrary, NoMusicPlayback, playerScope, pace = pace,
      )
      val seen = LinkedHashSet<MediaId>()
      val watcher = scope.launch {
        player.ui.collect { ui ->
          (ui.state as? PlayerState.Playing)?.let { seen += it.item.media; seen += it.next.map { next -> next.media } }
          (ui.state as? PlayerState.Paused)?.let { seen += it.item.media }
          ui.intro?.let { seen += it.cover.media }
          seen += ui.items.map { it.media }
        }
      }
      val finished = awaitState(out, "una presentazione non e' finita", player.ui) { it.state is PlayerState.Ended || it.gone }
      watcher.cancel()
      player.exit()
      val ended = player.ui.value
      playerScope.cancel()
      if (!finished) continue
      Stats.presentations++
      out.shown += seen
      if (seen.any { it in never }) out.problems += "la presentazione di un ricordo mostra un media nascosto"
      if (want.size < Memory.MIN_MEMBERS && !ended.gone) out.problems += "una presentazione con meno di 3 elementi visibili parte lo stesso"
      val count = (ended.state as? PlayerState.Ended)?.count
      if (want.size >= Memory.MIN_MEMBERS && count != want.size) out.problems += "la presentazione ha $count elementi, ne attendeva ${want.size}"
      if (ended.items.any { it.media in never }) out.problems += "la carta finale di una presentazione ha un media nascosto"
    }
  }

  /**
   * Lo scope dei controllori della prova: **un thread alla volta** (i controllori sono scritti per il thread principale, dove comandi e tempo non si sovrappongono mai;
   * qui il catalogo risponde da un altro thread e `Dispatchers.Unconfined` farebbe correre lo stato).
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private fun singleThreaded(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

  /**
   * Aspetta lo **stato preciso** (`first { ... }` dentro un tetto, mai una lettura subito dopo l'azione asincrona) e, se non arriva, lo registra come violazione:
   * una prova non resta mai appesa e non legge uno stato di mezzo.
   */
  private suspend fun <T> awaitState(out: SurfaceReport, problem: String, flow: Flow<T>, condition: (T) -> Boolean): Boolean = try {
    withTimeout(WAIT) { flow.first(condition) }
    true
  } catch (e: TimeoutCancellationException) {
    out.problems += problem
    false
  }

  /** Il tetto di ogni attesa di uno stato (la prova non resta mai appesa: scaduto, e' una violazione). */
  private val WAIT = 30.seconds

  private const val MAX_VICTIM_CARDS = 3
  private const val MAX_PRESENTATIONS = 3
  private const val HIDE_TIME = 1_780_000_200_000L
}
