package dev.pampa.mycloudgallery.feature.people.list

import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.KeepInstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.instantSkeleton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIcons
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListUi
import dev.pampa.mycloudgallery.feature.people.sheets.MergeHandlers
import dev.pampa.mycloudgallery.feature.people.sheets.MergeSheet
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens
import dev.pampa.mycloudgallery.feature.people.ui.PeopleMessageBlock
import dev.pampa.mycloudgallery.feature.people.ui.PeopleText
import dev.pampa.mycloudgallery.feature.people.ui.PersonAvatar
import dev.pampa.mycloudgallery.feature.people.ui.PersonTile
import dev.pampa.mycloudgallery.feature.people.ui.TileSelection
import dev.pampa.mycloudgallery.feature.people.ui.PeopleFigureState
import androidx.compose.ui.unit.Dp
import dev.pampa.mycloudgallery.feature.people.ui.personCardHeight
import dev.pampa.mycloudgallery.feature.people.ui.personFadeHeight

// "TUTTE LE PERSONE" (Fase 8, P9; docs/fase8-progetto.md §1.5 `PeopleListState`, §2.6 "Cosa si vede", Q2 (b) del 27/10, D25). I1 (fase I, 11/11):
// "come Samsung", una griglia di **schede alte con il volto a tutta scheda** e il nome in basso (senza nome: solo il volto), tre per riga sul
// telefono; niente conti sotto (li dice TalkBack). In cima le proposte "E'
// la stessa persona?" (righe di un gruppo: un tocco apre il foglio con i due volti e "Si'" / "No"), poi la riga "Altri volti (N)" con Q2 (b).
// "Seleziona" sta nella barra in alto (un tasto da 48 dp, come "Unisci" e "Fine" in selezione): niente galleggia sopra i volti. Solo
// componenti G2.

/** Cosa l'elenco chiede fuori da se': la navigazione e i passi della selezione. L'unione la fa [MergeHandlers]. */
internal interface PeopleListHandlers : MergeHandlers {
  fun onBack()

  fun onOpenPerson(id: PersonId)

  fun onOpenOthers()

  fun onOpenHidden()

  fun onSetSelecting(on: Boolean)

  fun onStartWith(id: PersonId)

  fun onToggle(id: PersonId)

  fun onMergeSelected()

  fun onOpenProposal(proposal: MergeProposal)

  fun onDismissMessage()
}

/** Quante proposte si mostrano in cima (le altre arrivano dopo aver risposto a queste). */
private const val MAX_PROPOSALS_SHOWN = 3

private const val MAX_COLUMNS = 8

/** L'elenco per lo stato dato: nessun effetto, la usano la rotta, i test e gli screenshot. */
@Composable
internal fun PeopleListScreen(
  ui: PeopleListUi,
  crops: PeopleCrops,
  handlers: PeopleListHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
  /** Avvio istantaneo: lo scheletro da disegnare al posto dello stato (le persone non ancora lette); null = lo stato com'e'. */
  skeleton: InstantShape? = null,
) {
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = PeopleDimens.TileMin, spacing = PeopleDimens.BlockGap, maxColumns = MAX_COLUMNS)
  // Giro 1 (CU-2): la sfumatura sopra il nome si sceglie dall'altezza vera delle schede (corta sotto i 140 dp: la bocca resta libera).
  val fade = personFadeHeight(personCardHeight(metrics.contentWidth, columns, PeopleDimens.BlockGap))
  val ready = ui.state as? PeopleListState.Ready
  if (ready != null && !ui.selecting) KeepInstantShape(PeopleInstant.CODE, PeopleInstant.shapeOf(ready, columns))
  FluidScreen(
    title = if (ui.selecting) LocalContext.current.resources.getQuantityString(R.plurals.persone_selected, ui.selected.size, ui.selected.size) else stringResource(R.string.persone_title),
    modifier = modifier,
    onBack = handlers::onBack,
    listState = listState,
    metrics = metrics,
    contentMaxWidth = PeopleDimens.PageWidth,
    actions = {
      if (ui.selecting) {
        GalleryBarButton(
          icon = Icons.Rounded.CallMerge, contentDescription = stringResource(R.string.persone_merge_action), onClick = handlers::onMergeSelected,
          enabled = ui.selected.size >= 2,
        )
        GalleryBarButton(icon = Icons.Rounded.Close, contentDescription = stringResource(R.string.persone_select_done), onClick = { handlers.onSetSelecting(false) })
      } else if (ready != null) {
        GalleryBarButton(icon = Icons.Rounded.Check, contentDescription = stringResource(R.string.persone_select), onClick = { handlers.onSetSelecting(true) })
      }
    },
  ) {
    ui.message?.let { message -> item(key = "message") { PeopleMessageBlock(message, handlers::onDismissMessage) } }
    if (skeleton != null) {
      instantSkeleton(skeleton, keyPrefix = "instant-people")
      return@FluidScreen
    }
    when (val state = ui.state) {
      is PeopleListState.Working -> working(state)
      PeopleListState.Empty -> item(key = "empty") {
        PeopleFigureState(icon = Icons.Rounded.Face, title = stringResource(R.string.persone_empty_title))
      }
      is PeopleListState.Ready -> {
        if (!ui.selecting) proposals(ui.proposals, crops, handlers)
        if (!ui.selecting && state.othersCount > 0) othersRow(state.othersCount, handlers)
        if (state.named.isNotEmpty()) grid("named", state.named, columns, fade, ui, crops, handlers)
        if (state.unnamed.isNotEmpty()) {
          item(key = "header-unnamed") { GallerySectionHeader(title = stringResource(R.string.persone_header_unnamed)) }
          grid("unnamed", state.unnamed, columns, fade, ui, crops, handlers)
        }
        if (state.named.isEmpty() && state.unnamed.isEmpty()) {
          item(key = "none-named") {
            PeopleFigureState(icon = Icons.Rounded.Face, title = stringResource(R.string.persone_none_named_title), why = stringResource(R.string.persone_none_named_detail))
          }
        }
        if (!ui.selecting && state.hiddenCount > 0) hiddenRow(state.hiddenCount, handlers)
      }
    }
  }
  MergeSheet(ui.merge, crops, handlers)
}

private fun LazyListScope.working(state: PeopleListState.Working) {
  item(key = "working") {
    // Giro 1 (CU-23): la figura, il titolo e la barra; quanto manca e quando va avanti stanno dietro la «i».
    PeopleFigureState(
      icon = Icons.Rounded.Face,
      title = stringResource(R.string.persone_working_title),
      why = state.percent?.let { stringResource(R.string.persone_working_percent, it) } ?: stringResource(R.string.persone_working_waiting),
      action = state.percent?.let { percent -> { FluidProgressBar(progress = { percent / PERCENT }) } },
    )
  }
}

private const val PERCENT = 100f

/** "E' la stessa persona?": una riga per proposta, con i due volti affiancati (mai uno sull'altro) e i nomi. */
private fun LazyListScope.proposals(list: List<MergeProposal>, crops: PeopleCrops, handlers: PeopleListHandlers) {
  if (list.isEmpty()) return
  // I1 (fase I): niente intestazione sopra le proposte: la riga stessa chiede "E' la stessa persona?" e i due volti dicono chi; la regola ("non unisco
  // mai niente da solo") la dice il foglio che si apre.
  item(key = "proposals") {
    FluidListGroup {
      list.take(MAX_PROPOSALS_SHOWN).forEachIndexed { index, proposal ->
        if (index > 0) FluidListDivider()
        val resources = LocalContext.current.resources
        FluidListRow(
          title = stringResource(R.string.persone_proposal_title),
          subtitle = stringResource(R.string.persone_proposal_names, PeopleText.nameInSentence(resources, proposal.first), PeopleText.nameInSentence(resources, proposal.second)),
          onClick = { handlers.onOpenProposal(proposal) },
          // I due volti stanno a destra, prima della freccia: la piastrella a sinistra e' una sola e ne taglierebbe uno.
          badge = { PairOfFaces(proposal.first, proposal.second, crops) },
        )
      }
    }
  }
}

/** Due volti piccoli affiancati con uno spazio in mezzo: la riga di una proposta, di una coppia. */
@Composable
private fun PairOfFaces(first: PersonCard, second: PersonCard, crops: PeopleCrops) {
  Row(horizontalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap / 2)) {
    PersonAvatar(first.cover, crops, PeopleDimens.AvatarRow, Modifier.size(PeopleDimens.AvatarPair))
    PersonAvatar(second.cover, crops, PeopleDimens.AvatarRow, Modifier.size(PeopleDimens.AvatarPair))
  }
}

/** "Altri volti (N)": il gruppo senza nome, a un tocco (Q2 (b)). */
private fun LazyListScope.othersRow(count: Int, handlers: PeopleListHandlers) {
  item(key = "others") {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.persone_others_row, count),
        // I1: un'etichetta sola; che cosa si fa li' lo dice la pagina.
        subtitle = "",
        tone = FluidTone.Primary,
        // Giro 1 (CU-8): la piastrella della famiglia Persone, come le altre dell'app (mai la grigia di sistema).
        leading = { GalleryIconTile(icon = Icons.Rounded.Group, family = GalleryIconFamily.People) },
        leadingTile = false,
        onClick = handlers::onOpenOthers,
      )
    }
  }
}

private fun LazyListScope.hiddenRow(count: Int, handlers: PeopleListHandlers) {
  item(key = "hidden") {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.persone_hidden_row, count),
        subtitle = "",
        tone = FluidTone.Neutral,
        // Giro 1 (CU-8): lo stesso segno dei Nascosti nelle Raccolte e nelle Impostazioni (famiglia Sicurezza).
        leading = { GalleryIconTile(spec = GalleryIcons.Hidden) },
        leadingTile = false,
        onClick = handlers::onOpenHidden,
      )
    }
  }
}

/** La griglia: [columns] tessere per riga, l'ultima riga lascia i posti vuoti (le tessere non si allargano); [fade] la sfumatura sopra i nomi. */
private fun LazyListScope.grid(
  key: String,
  cards: List<PersonCard>,
  columns: Int,
  fade: Dp,
  ui: PeopleListUi,
  crops: PeopleCrops,
  handlers: PeopleListHandlers,
) {
  val rows = cards.chunked(columns)
  items(rows.size, key = { "$key-row-$it" }) { rowIndex ->
    Row(horizontalArrangement = Arrangement.spacedBy(PeopleDimens.BlockGap), modifier = Modifier.fillMaxWidth()) {
      val row = rows[rowIndex]
      for (card in row) {
        val selection = if (!ui.selecting) TileSelection.NONE else if (card.id in ui.selected) TileSelection.ON else TileSelection.OFF
        PersonTile(
          card = card,
          crops = crops,
          selection = selection,
          onClick = { if (ui.selecting) handlers.onToggle(card.id) else handlers.onOpenPerson(card.id) },
          onLongClick = if (ui.selecting) null else ({ handlers.onStartWith(card.id) }),
          modifier = Modifier.weight(1f),
          fadeHeight = fade,
        )
      }
      repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
  }
}

/** Nessuna azione: per le anteprime e gli screenshot. */
internal object NoPeopleListHandlers : PeopleListHandlers {
  override fun onBack() = Unit

  override fun onOpenPerson(id: PersonId) = Unit

  override fun onOpenOthers() = Unit

  override fun onOpenHidden() = Unit

  override fun onSetSelecting(on: Boolean) = Unit

  override fun onStartWith(id: PersonId) = Unit

  override fun onToggle(id: PersonId) = Unit

  override fun onMergeSelected() = Unit

  override fun onOpenProposal(proposal: MergeProposal) = Unit

  override fun onDismissMessage() = Unit

  override fun onToggleCandidate(id: PersonId) = Unit

  override fun onContinueFromChoose() = Unit

  override fun onPick(id: PersonId) = Unit

  override fun onContinueFromName() = Unit

  override fun onConfirmMerge() = Unit

  override fun onRejectProposal() = Unit

  override fun onDismissMerge() = Unit
}
