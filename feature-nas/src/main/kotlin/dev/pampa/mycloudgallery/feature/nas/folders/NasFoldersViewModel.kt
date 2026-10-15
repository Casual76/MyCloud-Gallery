package dev.pampa.mycloudgallery.feature.nas.folders

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.mycloudgallery.core.nasops.contract.NameRules
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolders
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.UserConfirmation
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.confirm.NameCheck
import dev.pampa.mycloudgallery.feature.nas.confirm.NameDraft
import dev.pampa.mycloudgallery.feature.nas.confirm.NamePurpose
import dev.pampa.mycloudgallery.feature.nas.contract.NasArea
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreas
import dev.pampa.mycloudgallery.feature.nas.ui.UiNotice
import dev.pampa.mycloudgallery.feature.nas.ui.noticeFor
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Cartelle NAS (docs/fase4-progetto.md §1.5 "NasFolderState", §2.7): sfoglia, crea, rinomina, sposta, elimina. Il ViewModel tiene lo
// stato della schermata e parla con i contratti di core-nasops; non fa mai un'operazione da solo: ogni cambiamento passa da un piano
// (`NasOperations.plan`), da un foglio che l'utente conferma con un tocco e da `NasOperations.start` con quella conferma.

/** Dove si e': una share e una cartella (null = la radice). Null per l'elenco delle aree. */
@Immutable
internal data class FolderWhere(val share: String, val dir: SharePath?) {
  override fun toString(): String = "FolderWhere"
}

/** Cosa si sta facendo con la lista. */
internal sealed interface FoldersMode {
  /** Si sfoglia. */
  data object Browse : FoldersMode

  /** Si sceglie la cartella di arrivo per [items]: la stessa lista, con "Sposta qui" in fondo. */
  data class PickDestination(val items: List<NasItem>) : FoldersMode
}

/** Lo stato di Cartelle NAS. */
@Immutable
internal data class FoldersUiState(
  val areas: List<NasArea> = emptyList(),
  val where: FolderWhere? = null,
  val listing: NasFolderState = NasFolderState.Loading,
  val selecting: Boolean = false,
  val selection: Set<SharePath> = emptySet(),
  val mode: FoldersMode = FoldersMode.Browse,
  val activeOperations: Int = 0,
  val confirm: OpPlan? = null,
  /** R11: quanti elementi del piano di [confirm] l'utente ha nascosto (spostamento nell'area comune). */
  val confirmHidden: Int = 0,
  val name: NameDraft? = null,
  val notice: UiNotice? = null,
) {
  /** Le voci da mostrare: l'elenco, o l'ultimo salvato se il NAS non risponde. Le cartelle prima, poi per nome. */
  val entries: List<NasFolderEntry>
    get() = when (listing) {
      is NasFolderState.Listing -> listing.entries
      is NasFolderState.Offline -> listing.cached.orEmpty()
      else -> emptyList()
    }.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))

  /** Sola lettura per questo account (E-N9). */
  val readOnly: Boolean get() = (listing as? NasFolderState.Listing)?.readOnly == true

  /** Il NAS non risponde: le azioni che modificano sono spente. */
  val offline: Boolean get() = listing is NasFolderState.Offline

  /** Si possono cambiare cose qui: c'e' una cartella aperta, il NAS risponde e non e' in sola lettura. */
  val canModify: Boolean get() = where != null && listing is NasFolderState.Listing && !readOnly

  val picking: Boolean get() = mode is FoldersMode.PickDestination

  /** Le voci scelte come elementi per le operazioni (con il timbro visto qui). */
  fun selectedItems(): List<NasItem> {
    val share = where?.share ?: return emptyList()
    return entries.filter { it.path in selection }.map { it.toItem(share) }
  }

  /** "Sposta qui" ha senso: ci si trova in una cartella (non nell'elenco delle aree), il NAS risponde e si puo' scrivere. */
  val canMoveHere: Boolean
    get() = picking && canModify
}

@HiltViewModel
class NasFoldersViewModel @Inject constructor(
  private val folders: NasFolders,
  private val operations: NasOperations,
  private val areas: NasAreas,
  private val clocks: OpClocks,
) : ViewModel() {

  private val mutable = MutableStateFlow(FoldersUiState())
  internal val state: StateFlow<FoldersUiState> = mutable

  private var listingJob: Job? = null
  private var nameJob: Job? = null
  private var previousActive = 0

  init {
    viewModelScope.launch { areas.areas.collect { list -> mutable.update { it.copy(areas = list) } } }
    viewModelScope.launch {
      operations.operations.collect { ops ->
        val active = ops.count { it.active }
        // Un'operazione che si chiude cambia quello che c'e' nella cartella: si rilegge.
        if (active < previousActive) refresh()
        previousActive = active
        mutable.update { it.copy(activeOperations = active) }
      }
    }
  }

  /** L'orologio monotono con cui si data una conferma: lo stesso con cui `NasOperations.start` la controlla. */
  fun monotonicMs(): Long = clocks.monotonicMs()

  // --- Dove si e' -----------------------------------------------------------------------------------------------------------

  internal fun openArea(area: NasArea) = goTo(FolderWhere(area.share, null))

  internal fun openFolder(entry: NasFolderEntry) {
    val here = mutable.value.where ?: return
    if (!entry.isFolder) return
    goTo(FolderWhere(here.share, entry.path))
  }

  internal fun goTo(where: FolderWhere?) {
    mutable.update { it.copy(where = where, listing = NasFolderState.Loading, selecting = false, selection = emptySet(), notice = null) }
    observe()
  }

  /** Un livello su; vero se la pressione di "indietro" e' stata gestita qui (false = tocca a chi ospita la pagina). */
  internal fun up(): Boolean {
    val current = mutable.value
    when {
      current.selecting || current.selection.isNotEmpty() -> clearSelection()
      current.where?.dir != null -> goTo(FolderWhere(current.where.share, current.where.dir.parent()))
      current.where != null -> goTo(null)
      current.picking -> cancelMove()
      else -> return false
    }
    return true
  }

  internal fun refresh() {
    val where = mutable.value.where ?: return
    viewModelScope.launch {
      folders.refresh(where.share, where.dir)
      observe()
    }
  }

  private fun observe() {
    listingJob?.cancel()
    val where = mutable.value.where ?: return
    listingJob = viewModelScope.launch {
      folders.observe(where.share, where.dir).collect { listing ->
        mutable.update { current ->
          if (current.where != where) {
            current
          } else {
            // La selezione tiene solo cio' che c'e' ancora nell'elenco.
            val present = (listing as? NasFolderState.Listing)?.entries?.map { it.path }?.toSet()
            current.copy(listing = listing, selection = if (present == null) current.selection else current.selection.intersect(present))
          }
        }
      }
    }
  }

  // --- Selezione ------------------------------------------------------------------------------------------------------------

  internal fun startSelecting() = mutable.update { if (it.canModify || it.listing is NasFolderState.Listing) it.copy(selecting = true) else it }

  internal fun toggle(path: SharePath) = mutable.update {
    val next = if (path in it.selection) it.selection - path else it.selection + path
    it.copy(selecting = true, selection = next)
  }

  internal fun selectAll() = mutable.update { it.copy(selecting = true, selection = it.entries.map { e -> e.path }.toSet()) }

  internal fun clearSelection() = mutable.update { it.copy(selecting = false, selection = emptySet()) }

  // --- Cambiare cose: ogni gesto e' un piano da confermare ------------------------------------------------------------------

  /** "Elimina" sulla selezione: il piano per il cestino del NAS, da confermare. */
  internal fun requestTrash() {
    val items = mutable.value.takeIf { it.canModify }?.selectedItems().orEmpty()
    if (items.isEmpty()) return
    plan(OpRequest.Trash(items))
  }

  /** "Sposta": si passa alla scelta della cartella di arrivo. */
  internal fun startMove() {
    val items = mutable.value.takeIf { it.canModify }?.selectedItems().orEmpty()
    if (items.isEmpty()) return
    mutable.update { it.copy(mode = FoldersMode.PickDestination(items), selecting = false, selection = emptySet(), notice = null) }
  }

  internal fun cancelMove() = mutable.update { it.copy(mode = FoldersMode.Browse) }

  /** "Sposta qui": il piano per portare gli elementi scelti nella cartella aperta. */
  internal fun moveHere() {
    val current = mutable.value
    val mode = current.mode as? FoldersMode.PickDestination ?: return
    val where = current.where ?: return
    if (!current.canModify) return
    plan(OpRequest.Move(mode.items, where.share, where.dir))
  }

  private fun plan(request: OpRequest) {
    viewModelScope.launch {
      when (val outcome = operations.plan(request)) {
        is PlanOutcome.Ready -> {
          // R11: per uno spostamento, quanti elementi nascosti ci sono (il foglio lo dice se vanno nell'area comune).
          val hidden = if (outcome.plan.kind == OpKind.MOVE) runCatching { areas.hiddenAmong(outcome.plan) }.getOrDefault(0) else 0
          mutable.update { it.copy(confirm = outcome.plan, confirmHidden = hidden, notice = null) }
        }
        is PlanOutcome.Rejected -> mutable.update { it.copy(notice = noticeFor(outcome)) }
      }
    }
  }

  internal fun dismissConfirm() = mutable.update { it.copy(confirm = null) }

  /** L'utente ha confermato [plan] con un tocco: l'operazione parte (o l'esito dice perche' no). */
  internal fun confirmed(plan: OpPlan, confirmation: UserConfirmation) {
    mutable.update { it.copy(confirm = null, name = null) }
    viewModelScope.launch {
      val outcome = operations.start(plan, confirmation)
      mutable.update {
        it.copy(
          notice = noticeFor(outcome),
          // Fatto lo spostamento (o l'eliminazione) si torna a sfogliare.
          mode = FoldersMode.Browse,
          selecting = false,
          selection = emptySet(),
        )
      }
      refresh()
    }
  }

  internal fun dismissNotice() = mutable.update { it.copy(notice = null) }

  // --- Il nome: "Nuova cartella" e "Rinomina" --------------------------------------------------------------------------------

  internal fun openNewFolder() {
    val current = mutable.value
    val where = current.where ?: return
    if (!current.canModify) return
    mutable.update {
      it.copy(name = NameDraft(NamePurpose.NEW_FOLDER, "", NameCheck.Empty, shareName = where.share, folder = where.dir?.components.orEmpty()))
    }
  }

  internal fun openRename() {
    val current = mutable.value
    val item = current.takeIf { it.canModify }?.selectedItems()?.singleOrNull() ?: return
    mutable.update { it.copy(name = NameDraft(NamePurpose.RENAME, item.path.name, NameCheck.Same)) }
  }

  internal fun onNameChanged(text: String) {
    val current = mutable.value
    val draft = current.name ?: return
    nameJob?.cancel()
    if (text.isEmpty()) {
      mutable.update { it.copy(name = draft.copy(text = text, check = NameCheck.Empty)) }
      return
    }
    // Un nome che il NAS non ammette non si manda neppure a pianificare: si propone la correzione (E-D9).
    val local = NameRules.check(text)
    if (local is NameRules.Check.Invalid) {
      mutable.update { it.copy(name = draft.copy(text = text, check = NameCheck.Invalid(local.suggestion))) }
      return
    }
    val request = requestFor(current, draft, text)
    if (request == null) {
      mutable.update { it.copy(name = draft.copy(text = text, check = NameCheck.Same)) }
      return
    }
    mutable.update { it.copy(name = draft.copy(text = text, check = NameCheck.Checking)) }
    nameJob = viewModelScope.launch {
      delay(NAME_CHECK_DELAY_MS)
      val check = when (val outcome = operations.plan(request)) {
        is PlanOutcome.Ready -> NameCheck.Ready(outcome.plan)
        is PlanOutcome.Rejected -> when (outcome.reason) {
          PlanRejection.NAME_EXISTS -> NameCheck.Exists
          PlanRejection.SAME_LOCATION -> NameCheck.Same
          PlanRejection.INVALID_NAME -> NameCheck.Invalid(NameRules.suggest(text))
          else -> NameCheck.Refused(outcome.reason)
        }
      }
      // Se nel frattempo il testo e' cambiato, questo esito e' vecchio.
      mutable.update { latest -> if (latest.name?.text == text) latest.copy(name = latest.name.copy(check = check)) else latest }
    }
  }

  private fun requestFor(state: FoldersUiState, draft: NameDraft, text: String): OpRequest? = when (draft.purpose) {
    NamePurpose.NEW_FOLDER -> state.where?.let { OpRequest.CreateFolder(it.share, it.dir, text) }
    NamePurpose.RENAME -> state.selectedItems().singleOrNull()?.takeIf { it.path.name != text }?.let { OpRequest.Rename(it, text) }
  }

  internal fun dismissName() {
    nameJob?.cancel()
    mutable.update { it.copy(name = null) }
  }

  private companion object {
    /** Quanto si aspetta dopo l'ultimo carattere prima di chiedere il piano: non si interroga il NAS a ogni lettera. */
    const val NAME_CHECK_DELAY_MS = 250L
  }
}
