package dev.pampa.mycloudgallery.feature.memories.section

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCard
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.ShelfEmptyReason
import dev.pampa.mycloudgallery.core.memories.contract.ShelfView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.memories.R
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoriesText
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.HourglassTop

// LA SEZIONE RICORDI DELLE RACCOLTE (Fase 9, P9; docs/fase9-progetto.md §1.5 `MemoriesSectionState`, §2.5, D14, D31, E-MEM1; "Avvio e pagine istantanei"
// in docs/decisioni.md). Ogni sezione delle Raccolte e' un dato che chi la possiede consegna con un `CollectionsSectionProvider` (feature-library);
// `:app` lo lega con un `@Provides @IntoSet` (P13). Qui nasce il dato, **della stessa forma campo per campo** di `CollectionEntryGroup` (come fa
// feature-people, perche' i feature non si conoscono): il legame in `:app` e' un `map` di poche righe, e `MemoriesSectionBindingTest` lo prova su un
// fornitore vero di feature-library.
//
// **Istantanea**: la sezione si disegna **dall'ultimo scaffale** (`Memories.shelf`, che `MemoryEngine.restore()` ha letto da file) senza aspettare la
// Libreria ne' il database; subito dopo il filtro finale dei Nascosti ([FinalFilter]) rifa' copertine e conti. Il segno "nuovo" e' sulla voce; "visto"
// si segna solo aprendo la pagina o la presentazione (D10), mai guardando la sezione.

/** Una voce di una sezione: la stessa forma di `CollectionEntry` (feature-library). */
@Immutable
data class SectionEntry(
  val code: String,
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val tone: FluidTone,
  val badge: Int = 0,
  val trailing: String? = null,
  /** H5: la copertina del ricordo, che le Raccolte mostrano al posto dell'icona (dalla Libreria visibile: mai un nascosto). */
  val cover: MediaId? = null,
  /** I1 (fase I): la riga piccola della scheda grande delle Raccolte (il mese del ricordo); null quando il titolo lo dice gia' ("Il meglio di ottobre"). */
  val caption: String? = null,
) {
  /** D33: mai il titolo o il sottotitolo (possono portare un luogo, una data o un nome): solo il codice, che non porta mai un dato dell'utente. */
  override fun toString(): String = "SectionEntry($code)"
}

/** Una sezione: la stessa forma di `CollectionEntryGroup`. I Ricordi si possono nascondere dalle Raccolte (D14). */
@Immutable
data class SectionData(
  val code: String,
  val title: String,
  val entries: List<SectionEntry>,
  val hideable: Boolean = true,
  val hint: String? = null,
) {
  /** D33: mai il titolo ne' le voci per esteso: il codice e quante voci. */
  override fun toString(): String = "SectionData($code, ${entries.size} voci)"
}

/** Cio' che serve a disegnare la sezione, nello stesso istante: lo stato, quante voci ha lo scaffale e i nomi delle persone gia' letti (per ricomporre i titoli quando arrivano). */
data class SectionSnapshot(val state: MemoriesSectionState, val total: Int, val named: Map<PersonId, String>)

/**
 * Lo stato della sezione Ricordi **senza risorse** (nessun testo, nessuna `R`): dall'ultimo scaffale subito, poi rifatto dal filtro finale dei Nascosti
 * ([FinalFilter]). E' la parte che si prova sul motore vero (`HiddenSurfaces`, riga `MEMORIES`) e che [MemoriesSectionSources] veste con i testi.
 */
class MemoriesSectionModel(
  private val memories: Memories,
  private val names: MemoryNames,
  private val filter: FinalFilter,
  scope: CoroutineScope,
) {

  /** Lo stato, le voci (tutte, non solo le sei della sezione) e i nomi gia' letti. */
  private data class Resolved(val state: MemoriesSectionState, val cards: List<MemoryCard>, val named: Map<PersonId, String>)

  private fun resolve(view: ShelfView, cards: List<MemoryCard>): Resolved {
    val named = cards.mapNotNull { it.title.person }.distinct().mapNotNull { id -> names.cachedPerson(id)?.let { id to it } }.toMap()
    return Resolved(MemoriesSectionStates.section(view, cards), cards, named)
  }

  private val resolved: StateFlow<Resolved> = memories.shelf.transformLatest { view ->
    emit(resolve(view, view.items))
    val refined = filter.refine(memories, view.items)
    names.learn(refined)
    emit(resolve(view, refined))
  }.stateIn(scope, SharingStarted.Eagerly, memories.shelf.value.let { resolve(it, it.items) })

  /** Lo stato del §1.5 di adesso. */
  val sectionState: StateFlow<MemoriesSectionState> = resolved.map { it.state }.stateIn(scope, SharingStarted.Eagerly, resolved.value.state)

  /** Lo stato con quanto serve a comporre le righe. */
  val snapshot: StateFlow<SectionSnapshot> = resolved.map { SectionSnapshot(it.state, it.cards.size, it.named) }
    .stateIn(scope, SharingStarted.Eagerly, resolved.value.let { SectionSnapshot(it.state, it.cards.size, it.named) })

  /** Il ricordo di una voce (`m_<16 caratteri>`), o null se non c'e' piu' (uscito dallo scaffale mentre si toccava). */
  fun resolve(code: String): MemoryId? = resolved.value.cards.firstOrNull { MemoriesSectionStates.codeOf(it.id) == code }?.id
}

/**
 * I dati della sezione Ricordi: [sectionState] e' lo stato del §1.5 di adesso (le Raccolte e gli screenshot lo leggono), [section] la sezione come dato.
 * Montata, **c'e' sempre una riga almeno** (da vuota "I ricordi arrivano quando ci sono abbastanza foto", in generazione "Sto preparando i ricordi"),
 * cosi' la sezione non sparisce dal layout delle Raccolte (E-MEM1: "senza voci non c'e' sezione").
 */
class MemoriesSectionSources(
  private val resources: Resources,
  memories: Memories,
  private val names: MemoryNames,
  filter: FinalFilter,
  scope: CoroutineScope,
) {
  private val model = MemoriesSectionModel(memories, names, filter, scope)

  /** Lo stato del §1.5 di adesso. */
  val sectionState: StateFlow<MemoriesSectionState> get() = model.sectionState

  /** La sezione come dato: mai vuota (almeno una riga che dice a che punto si e'). */
  val section: StateFlow<SectionData> = model.snapshot.map { sectionOf(resources, it.state, names, it.named, it.total) }
    .stateIn(scope, SharingStarted.Eagerly, model.snapshot.value.let { sectionOf(resources, it.state, names, it.named, it.total) })

  /** Il ricordo di una voce (`m_<16 caratteri>`), o null se non c'e' piu' (uscito dallo scaffale mentre si toccava). */
  fun resolve(code: String): MemoryId? = model.resolve(code)

  companion object {
    /** Il codice stabile della sezione: lo stesso di `CollectionSections.MEMORIES` (lo prova `MemoriesSectionBindingTest`). */
    const val CODE: String = "memories"

    /** La sezione per uno stato (funzione pura, provata su ogni caso). [named] i nomi delle persone gia' letti (per i ricordi `PERSON`). */
    fun sectionOf(
      resources: Resources,
      state: MemoriesSectionState,
      names: MemoryNames,
      named: Map<PersonId, String> = emptyMap(),
      /** Quanti ricordi ha lo scaffale in tutto (la riga "Tutti i ricordi" lo dice); di serie quelli della sezione. */
      total: Int = (state as? MemoriesSectionState.Ready)?.cards?.size ?: 0,
    ): SectionData {
      val title = resources.getString(R.string.memorie_section)
      val entries = when (state) {
        MemoriesSectionState.Working -> listOf(
          // Giro 3: "Sto preparando i ricordi" e' in corso (le frecce che girano); "Aspetto la tua libreria" e' un'attesa (la clessidra).
          row(MemoriesEntryTarget.WORKING, resources.getString(R.string.memorie_row_working), resources.getString(R.string.memorie_row_working_detail), Icons.Rounded.Sync, FluidTone.Info),
        )
        is MemoriesSectionState.Empty -> listOf(
          if (state.reason == ShelfEmptyReason.LIBRARY_NOT_READY) {
            row(MemoriesEntryTarget.LIBRARY, resources.getString(R.string.memorie_row_library), resources.getString(R.string.memorie_row_library_detail), Icons.Rounded.HourglassTop, FluidTone.Neutral)
          } else {
            row(MemoriesEntryTarget.EMPTY, resources.getString(R.string.memorie_row_empty), resources.getString(R.string.memorie_row_empty_detail), Icons.Rounded.AutoAwesome, FluidTone.Neutral)
          },
        )
        is MemoriesSectionState.Ready -> state.cards.map { card -> entryOf(resources, card, names, named) } + allRow(resources, total)
      }
      return SectionData(code = CODE, title = title, entries = entries, hideable = true)
    }

    private fun row(code: String, title: String, subtitle: String, icon: ImageVector, tone: FluidTone) = SectionEntry(code, title, subtitle, icon, tone)

    /** La voce di un ricordo: il titolo, "18 foto · periodo · luoghi", l'icona del tipo e il segno "nuovo" a destra (il tono sulla piastrella). */
    fun entryOf(resources: Resources, card: MemoryCard, names: MemoryNames, named: Map<PersonId, String> = emptyMap()): SectionEntry {
      val personName = card.title.person?.let { named[it] ?: names.cachedPerson(it) }
      val places = names.places(card.title.placeIds)
      val title = MemoriesText.title(resources, card.kind, card.title, personName, places)
      return SectionEntry(
        code = MemoriesSectionStates.codeOf(card.id),
        title = title,
        subtitle = resources.getString(R.string.memorie_all_row_detail, MemoriesText.photos(resources, card.count), MemoriesText.subtitle(resources, card.title, places)),
        caption = MemoriesText.caption(card.kind, card.title, title),
        icon = iconOf(card.kind),
        tone = if (card.isNew) FluidTone.Primary else FluidTone.Neutral,
        trailing = if (card.isNew) resources.getString(R.string.memorie_new) else null,
        cover = card.cover,
      )
    }

    private fun allRow(resources: Resources, total: Int): SectionEntry = SectionEntry(
      MemoriesEntryTarget.ALL, resources.getString(R.string.memorie_row_all),
      resources.getQuantityString(R.plurals.memorie_row_all_detail, total.coerceAtLeast(1), GalleryNumbers.format(total)),
      Icons.Rounded.PhotoLibrary, FluidTone.Neutral,
    )

    private fun iconOf(kind: MemoryKind): ImageVector = when (kind) {
      MemoryKind.ON_THIS_DAY -> Icons.Rounded.History
      MemoryKind.TRIP -> Icons.Rounded.Flight
      MemoryKind.EVENT -> Icons.Rounded.Event
      MemoryKind.PERSON -> Icons.Rounded.Person
      MemoryKind.MONTH -> Icons.Rounded.CalendarMonth
    }
  }
}
