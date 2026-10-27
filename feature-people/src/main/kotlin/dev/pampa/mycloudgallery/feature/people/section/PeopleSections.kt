package dev.pampa.mycloudgallery.feature.people.section

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PeopleList
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSectionState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleUnavailable
import dev.pampa.mycloudgallery.feature.people.nav.PeopleEntryTarget
import dev.pampa.mycloudgallery.feature.people.ui.PeopleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

// LA SEZIONE PERSONE DELLE RACCOLTE (Fase 8, P9; docs/fase8-progetto.md §1.5 `PeopleSectionState`, §2.6 "Cosa si vede", Q2 (b), D4, D25, D34;
// "Fase 7 P11" in docs/decisioni.md, D33 della Fase 7). Ogni sezione delle Raccolte e' un dato che chi la possiede consegna con un
// `CollectionsSectionProvider` (feature-library); `:app` lo lega con un `@Provides @IntoSet` (P13). Qui nasce il dato.
//
// **Perche' non e' un `CollectionsSectionProvider` qui dentro.** I `feature-*` non si conoscono (Phase4ArchitectureTest) e il fornitore, con
// `CollectionEntryGroup`, sta in feature-library. Queste classi producono quindi la stessa cosa con tipi propri, **della stessa forma campo per
// campo** ([SectionData] = `CollectionEntryGroup`, [SectionEntry] = `CollectionEntry`), come fa feature-albums: il legame in `:app` e' un `map` di
// poche righe, e `PeopleSectionBindingTest` lo prova su un fornitore vero di feature-library (il test vede feature-library, il codice di produzione no):
//
//     object : CollectionsSectionProvider {
//       override val code = PeopleSectionSources.CODE                 // "people"
//       override val group = sources.section.map { it?.toGroup() }.stateIn(...)   // toGroup() e' nel test, 6 righe
//     }
//
// Da spenta la sezione ha la sola riga "Attiva riconoscimento dei volti". I codici delle voci sono stabili e non portano mai un nome
// ([PeopleEntryTarget]); `:app` li riconosce con [PeopleEntryTarget.parse].

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
)

/** Una sezione: la stessa forma di `CollectionEntryGroup`. Persone si puo' nascondere dalle Raccolte (non e' "Album"). */
@Immutable
data class SectionData(
  val code: String,
  val title: String,
  val entries: List<SectionEntry>,
  val hideable: Boolean = true,
  val hint: String? = null,
)

/**
 * I dati della sezione Persone: [section] cambia da sola con lo stato di Persone, le persone e il calcolo. Le voci: da spenta una riga sola;
 * accesa fino a [PeopleSectionStates.TOP] persone, "Tutte le persone" e (Q2 (b)) "Altri volti (N)".
 */
class PeopleSectionSources(
  private val resources: Resources,
  people: People,
  state: StateFlow<FacesState>,
  status: StateFlow<FacesStatus>,
  scope: CoroutineScope,
) {
  /** Lo stato del §1.5 di adesso (la scheda delle Raccolte e gli screenshot lo leggono). */
  val sectionState: StateFlow<PeopleSectionState> = combine(state, people.list, status) { facesState, list, calc -> PeopleSectionStates.of(facesState, list, calc) }
    .stateIn(scope, SharingStarted.Eagerly, PeopleSectionState.Off)

  /** La sezione come dato. Sempre una riga almeno: persino spenta la sezione c'e' (con "Attiva riconoscimento dei volti"). */
  val section: StateFlow<SectionData?> = combine(sectionState, people.list) { s, list -> sectionOf(resources, s, list) }
    .stateIn(scope, SharingStarted.Eagerly, null)

  companion object {
    /** Il codice stabile della sezione: lo stesso di `CollectionSections.PEOPLE` (lo prova `PeopleSectionBindingTest`). */
    const val CODE: String = "people"

    /** La sezione per uno stato (funzione pura, provata su ogni caso). */
    fun sectionOf(resources: Resources, state: PeopleSectionState, list: PeopleList): SectionData {
      val title = resources.getString(R.string.persone_section)
      val entries = when (state) {
        PeopleSectionState.Off -> listOf(
          row(PeopleEntryTarget.ENABLE, resources.getString(R.string.persone_row_enable), resources.getString(R.string.persone_row_enable_detail), Icons.Rounded.Face, FluidTone.Primary),
        )
        PeopleSectionState.AwaitingNotice -> listOf(
          row(PeopleEntryTarget.NOTICE, resources.getString(R.string.persone_row_notice), resources.getString(R.string.persone_row_notice_detail), Icons.Rounded.Face, FluidTone.Info),
        )
        PeopleSectionState.Excluded -> listOf(
          row(PeopleEntryTarget.EXCLUDED, resources.getString(R.string.persone_row_excluded), resources.getString(R.string.persone_row_excluded_detail), Icons.Rounded.PhoneAndroid, FluidTone.Neutral),
        )
        is PeopleSectionState.FirstRun -> listOf(
          row(
            PeopleEntryTarget.PROGRESS,
            state.percent?.let { resources.getString(R.string.persone_row_progress, it) } ?: resources.getString(R.string.persone_row_progress_waiting),
            resources.getString(R.string.persone_row_progress_detail), Icons.Rounded.Schedule, FluidTone.Info,
          ),
        )
        is PeopleSectionState.Ready -> people(resources, state.top, state.total, state.othersCount, list.hiddenCount)
        is PeopleSectionState.LocalOnly -> people(resources, state.top, state.total, state.othersCount, list.hiddenCount) + row(
          PeopleEntryTarget.LOCAL, resources.getString(R.string.persone_row_local), resources.getString(R.string.persone_row_local_detail), Icons.Rounded.PhoneAndroid, FluidTone.Neutral,
        )
        is PeopleSectionState.Unavailable -> listOf(
          when (state.reason) {
            PeopleUnavailable.NEEDS_UPDATE ->
              row(PeopleEntryTarget.UPDATE, resources.getString(R.string.persone_row_update), resources.getString(R.string.persone_row_update_detail), Icons.Rounded.SystemUpdate, FluidTone.Warning)
            PeopleUnavailable.ERASING ->
              row(PeopleEntryTarget.ERASING, resources.getString(R.string.persone_row_erasing), resources.getString(R.string.persone_row_erasing_detail), Icons.Rounded.Warning, FluidTone.Warning)
            PeopleUnavailable.MODELS ->
              row(PeopleEntryTarget.MODELS, resources.getString(R.string.persone_row_models), resources.getString(R.string.persone_row_models_detail), Icons.Rounded.Warning, FluidTone.Warning)
          },
        )
      }
      return SectionData(code = CODE, title = title, entries = entries, hideable = true)
    }

    private fun row(code: String, title: String, subtitle: String, icon: ImageVector, tone: FluidTone) = SectionEntry(code, title, subtitle, icon, tone)

    /** Le persone in primo piano, "Tutte le persone" e "Altri volti (N)": mai un nome nei codici, solo l'id della persona. */
    private fun people(resources: Resources, top: List<PersonCard>, total: Int, others: Int, hidden: Int): List<SectionEntry> {
      val rows = ArrayList<SectionEntry>()
      for (card in top) {
        rows += SectionEntry(
          code = PeopleEntryTarget.PERSON_PREFIX + card.id.value,
          title = PeopleText.title(resources, card),
          subtitle = PeopleText.photos(resources, card.photos),
          icon = Icons.Rounded.Person,
          tone = if (card.name == null) FluidTone.Neutral else FluidTone.Primary,
        )
      }
      if (total > 0) {
        rows += SectionEntry(
          PeopleEntryTarget.ALL, resources.getString(R.string.persone_row_all),
          resources.getQuantityString(R.plurals.persone_row_all_detail, total, GalleryNumbers.format(total)),
          Icons.Rounded.Group, FluidTone.Neutral,
        )
      }
      if (others > 0) {
        rows += SectionEntry(
          PeopleEntryTarget.OTHERS, resources.getString(R.string.persone_others_row, others), resources.getString(R.string.persone_others_row_detail),
          Icons.Rounded.Face, FluidTone.Info, badge = 0,
        )
      }
      if (rows.isEmpty() && hidden > 0) {
        rows += SectionEntry(PeopleEntryTarget.ALL, resources.getString(R.string.persone_row_all), resources.getString(R.string.persone_row_hidden_only), Icons.Rounded.Group, FluidTone.Neutral)
      }
      return rows
    }
  }
}
