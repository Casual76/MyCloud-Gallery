package dev.pampa.mycloudgallery.feature.people.page

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidLoadingBlock
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.fluid.rememberFluidScreenMetrics
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryWall
import dev.pampa.mycloudgallery.core.designsystem.grid.rememberGalleryGridCellStyle
import dev.pampa.mycloudgallery.core.faces.contract.MergeProposal
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.contract.PeopleThumbnails
import dev.pampa.mycloudgallery.feature.people.contract.PersonAction
import dev.pampa.mycloudgallery.feature.people.contract.PersonHeader
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageState
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageUi
import dev.pampa.mycloudgallery.feature.people.sheets.CoverSheet
import dev.pampa.mycloudgallery.feature.people.sheets.ForgetSheet
import dev.pampa.mycloudgallery.feature.people.sheets.MergeHandlers
import dev.pampa.mycloudgallery.feature.people.sheets.MergeSheet
import dev.pampa.mycloudgallery.feature.people.sheets.NameSheet
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens
import dev.pampa.mycloudgallery.feature.people.ui.PeopleMessageBlock
import dev.pampa.mycloudgallery.feature.people.ui.PeopleText
import dev.pampa.mycloudgallery.feature.people.ui.PersonAvatar

// LA PAGINA DI UNA PERSONA (Fase 8, P9; docs/fase8-progetto.md §1.5 `PersonPageState`, §2.6 "Pagina persona", D25). In stile Foto: in testa il volto
// grande e rotondo con il nome (o l'invito "Aggiungi un nome") e il conto **sotto**; due bottoni da 50 dp ("Rinomina" e "Controlla le foto"),
// "Forse e' anche…" come righe di un gruppo, poi la parete di foto della persona (nell'ordine della Libreria, stesso viewer). Il resto delle azioni sta
// nel menu ⋮ (un solo tasto da 48 dp accanto alla freccia): niente galleggia sulle foto. Una persona nascosta o dimenticata ha la sua pagina, senza
// foto. Solo componenti G2.

/** Cosa la pagina chiede fuori da se': la navigazione, le azioni (ognuna passa dal controllore) e i passi dei fogli. */
internal interface PersonPageHandlers : MergeHandlers {
  fun onBack()

  fun onAction(action: PersonAction)

  fun onOpenPhoto(media: MediaId)

  fun onOpenProposal(proposal: MergeProposal)

  fun onSaveName(text: String)

  fun onRemoveName()

  fun onDismissName()

  fun onConfirmForget()

  fun onDismissForget()

  fun onPickCover(face: FaceLocator?)

  fun onDismissCovers()

  fun onDismissMessage()
}

private const val MAX_COLUMNS = 12

/** La pagina per lo stato dato: nessun effetto, la usano la rotta, i test e gli screenshot. */
@Composable
internal fun PersonPageScreen(
  ui: PersonPageUi,
  thumbnails: PeopleThumbnails,
  crops: PeopleCrops,
  handlers: PersonPageHandlers,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  when (val page = ui.page) {
    PersonPageState.Loading -> Plain(stringResource(R.string.persone_page_title_unknown), handlers, modifier, listState) { item(key = "loading") { FluidLoadingBlock() } }
    is PersonPageState.Ready -> ReadyPage(ui, page, thumbnails, crops, handlers, modifier, listState)
    is PersonPageState.Hidden -> Plain(titleOf(page.header), handlers, modifier, listState) { hidden(page.header, crops, handlers, ui) }
    // NV-1: il nome c'e' ancora, sono le foto a essere tutte nascoste (non "persona dimenticata").
    is PersonPageState.AllHidden -> Plain(titleOf(page.header), handlers, modifier, listState) {
      item(key = "all-hidden") {
        Column(verticalArrangement = Arrangement.spacedBy(PeopleDimens.BlockGap)) {
          FluidEmptyState(title = stringResource(R.string.persone_all_hidden_title), detail = stringResource(R.string.persone_all_hidden_detail))
          FluidButton(text = stringResource(R.string.persone_back), onClick = handlers::onBack, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
        }
      }
    }
    PersonPageState.Forgotten -> Plain(stringResource(R.string.persone_page_title_unknown), handlers, modifier, listState) {
      item(key = "forgotten") {
        Column(verticalArrangement = Arrangement.spacedBy(PeopleDimens.BlockGap)) {
          FluidEmptyState(title = stringResource(R.string.persone_forgotten_title), detail = stringResource(R.string.persone_forgotten_detail))
          FluidButton(text = stringResource(R.string.persone_back), onClick = handlers::onBack, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
        }
      }
    }
    PersonPageState.NotFound -> Plain(stringResource(R.string.persone_page_title_unknown), handlers, modifier, listState) {
      item(key = "not-found") {
        Column(verticalArrangement = Arrangement.spacedBy(PeopleDimens.BlockGap)) {
          FluidEmptyState(title = stringResource(R.string.persone_not_found_title), detail = stringResource(R.string.persone_not_found_detail))
          FluidButton(text = stringResource(R.string.persone_back), onClick = handlers::onBack, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
        }
      }
    }
  }
  Sheets(ui, crops, handlers)
}

@Composable
private fun titleOf(header: PersonHeader): String = header.name ?: stringResource(R.string.persone_unnamed_spoken)

@Composable
private fun Plain(title: String, handlers: PersonPageHandlers, modifier: Modifier, listState: LazyListState, content: LazyListScope.() -> Unit) {
  FluidScreen(title = title, modifier = modifier, onBack = handlers::onBack, listState = listState, contentMaxWidth = PeopleDimens.SheetWidth, content = content)
}

/** I fogli aperti sopra la pagina: il nome, l'unione, "Dimentica", la copertina. Ognuno sta chiuso finche' il suo stato non c'e'. */
@Composable
private fun Sheets(ui: PersonPageUi, crops: PeopleCrops, handlers: PersonPageHandlers) {
  NameSheet(ui.naming, onSave = handlers::onSaveName, onRemoveName = handlers::onRemoveName, onDismiss = handlers::onDismissName)
  MergeSheet(ui.merge, crops, handlers)
  val card = when (val page = ui.page) {
    is PersonPageState.Ready -> PersonCard(page.header.id, page.header.name, page.header.count, page.header.cover)
    is PersonPageState.Hidden -> PersonCard(page.header.id, page.header.name, page.header.count, page.header.cover)
    else -> null
  }
  ForgetSheet(
    persons = if (ui.confirmForget && card != null) listOf(card) else emptyList(),
    crops = crops,
    onConfirm = handlers::onConfirmForget,
    onDismiss = handlers::onDismissForget,
  )
  CoverSheet(ui.covers, crops, onPick = handlers::onPickCover, onDismiss = handlers::onDismissCovers)
}

@Composable
private fun ReadyPage(
  ui: PersonPageUi,
  page: PersonPageState.Ready,
  thumbnails: PeopleThumbnails,
  crops: PeopleCrops,
  handlers: PersonPageHandlers,
  modifier: Modifier,
  listState: LazyListState,
) {
  val metrics = rememberFluidScreenMetrics()
  val columns = metrics.columns(minColumn = PeopleDimens.CellMin, spacing = GalleryWall.Gap, maxColumns = MAX_COLUMNS)
  val style = rememberGalleryGridCellStyle(cornerScale = GalleryWall.CornerScale)
  val interaction = remember { GalleryGridInteractionState() }
  val actions = rememberCellActions(page.view, stringResource(R.string.persone_cell_open), handlers::onOpenPhoto)
  FollowGridWindow(listState, thumbnails, "photos", page.view, columns)
  FluidScreen(
    title = titleOf(page.header),
    subtitle = PeopleText.photos(LocalContext.current.resources, page.header.count),
    modifier = modifier,
    onBack = handlers::onBack,
    listState = listState,
    metrics = metrics,
    itemSpacing = GalleryWall.Gap,
    contentMaxWidth = PeopleDimens.PageWidth,
    actions = { GalleryMenuButton(entries = menuEntries(page.actions, handlers), contentDescription = stringResource(R.string.persone_more)) },
  ) {
    ui.message?.let { message -> item(key = "message") { PeopleMessageBlock(message, handlers::onDismissMessage) } }
    header(page, crops, handlers)
    proposals(page.proposals, crops, handlers)
    if (page.view.size == 0) {
      item(key = "empty") { FluidEmptyState(title = stringResource(R.string.persone_page_empty_title), detail = stringResource(R.string.persone_page_empty_detail)) }
    } else {
      peopleGrid("photos", page.view, columns, thumbnails, style, interaction, actions, handlers::onOpenPhoto)
    }
  }
}

/** Il volto grande, il nome (o l'invito) e i due bottoni. Ogni blocco ha il suo spazio: fra le foto la fessura e' una sola. */
private fun LazyListScope.header(page: PersonPageState.Ready, crops: PeopleCrops, handlers: PersonPageHandlers) {
  item(key = "header") {
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = PeopleDimens.BlockGap),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(PeopleDimens.BlockGap),
    ) {
      // Il nome sta gia' nel titolo della schermata (che scorre via con la pagina): qui solo il volto, senza ripeterlo.
      PersonAvatar(page.header.cover, crops, PeopleDimens.AvatarHeader, Modifier.size(PeopleDimens.AvatarHeader))
      Row(horizontalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap), modifier = Modifier.fillMaxWidth()) {
        if (PersonAction.RENAME in page.actions) {
          FluidButton(
            text = stringResource(if (page.header.name == null) R.string.persone_add_name else R.string.persone_rename),
            onClick = { handlers.onAction(PersonAction.RENAME) },
            style = if (page.header.name == null) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
            modifier = Modifier.weight(1f),
          )
        }
        if (PersonAction.REVIEW in page.actions) {
          FluidButton(
            text = stringResource(R.string.persone_review),
            onClick = { handlers.onAction(PersonAction.REVIEW) },
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Large,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

/** "Forse e' anche…": una riga per proposta, con il volto dell'altra persona; un tocco apre il foglio con i due volti e "Si'" / "No". */
private fun LazyListScope.proposals(list: List<MergeProposal>, crops: PeopleCrops, handlers: PersonPageHandlers) {
  if (list.isEmpty()) return
  // I1 (fase I): il titolo basta ("Forse e' anche…"), i volti dicono chi; la regola la dice il foglio che si apre.
  item(key = "header-maybe") { GallerySectionHeader(title = stringResource(R.string.persone_maybe_header)) }
  item(key = "maybe") {
    FluidListGroup {
      list.forEachIndexed { index, proposal ->
        if (index > 0) FluidListDivider()
        val other = proposal.second
        FluidListRow(
          title = PeopleText.title(LocalContext.current.resources, other),
          // I1: il nome e il volto; quante foto ha lo dice la sua pagina.
          subtitle = "",
          onClick = { handlers.onOpenProposal(proposal) },
          leading = { PersonAvatar(other.cover, crops, PeopleDimens.AvatarRow, Modifier.size(PeopleDimens.AvatarRow)) },
        )
      }
    }
  }
  // Fra le proposte e le foto uno spazio vero: le foto fra loro stanno a una fessura sola.
  item(key = "maybe-gap") { Spacer(Modifier.height(PeopleDimens.BlockGap)) }
}

/** Una persona nascosta: la pagina lo dice e offre di mostrarla di nuovo; le sue foto restano in Libreria (E-F28). */
private fun LazyListScope.hidden(header: PersonHeader, crops: PeopleCrops, handlers: PersonPageHandlers, ui: PersonPageUi) {
  ui.message?.let { message -> item(key = "message") { PeopleMessageBlock(message, handlers::onDismissMessage) } }
  item(key = "hidden") {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PeopleDimens.BlockGap), modifier = Modifier.fillMaxWidth()) {
      PersonAvatar(header.cover, crops, PeopleDimens.AvatarHeader, Modifier.size(PeopleDimens.AvatarHeader))
      FluidEmptyState(title = stringResource(R.string.persone_hidden_title), detail = stringResource(R.string.persone_hidden_detail))
      FluidButton(
        text = stringResource(R.string.persone_show_again), onClick = { handlers.onAction(PersonAction.UNHIDE) },
        size = FluidButtonSize.Large, fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.persone_forget), onClick = { handlers.onAction(PersonAction.FORGET) }, style = FluidButtonStyle.Tinted,
        size = FluidButtonSize.Large, fillWidth = true,
      )
    }
  }
}

/** Una voce del menu ⋮ di una persona: l'azione, il testo, l'icona. */
private class MenuSpec(val action: PersonAction, @StringRes val label: Int, val icon: ImageVector, val destructive: Boolean = false)

private val MenuSpecs = listOf(
  MenuSpec(PersonAction.RENAME, R.string.persone_rename, Icons.Rounded.Edit),
  MenuSpec(PersonAction.MERGE, R.string.persone_merge_with, Icons.Rounded.CallMerge),
  MenuSpec(PersonAction.REVIEW, R.string.persone_review, Icons.Rounded.Face),
  MenuSpec(PersonAction.SPLIT, R.string.persone_split, Icons.Rounded.CallSplit),
  MenuSpec(PersonAction.SET_COVER, R.string.persone_set_cover, Icons.Rounded.Face),
  MenuSpec(PersonAction.SMART_ALBUM, R.string.persone_smart_album, Icons.Rounded.AutoAwesome),
  MenuSpec(PersonAction.HIDE, R.string.persone_hide, Icons.Rounded.VisibilityOff),
  MenuSpec(PersonAction.UNHIDE, R.string.persone_show_again, Icons.Rounded.Visibility),
)

private val ForgetSpec = MenuSpec(PersonAction.FORGET, R.string.persone_forget, Icons.Rounded.Delete, destructive = true)

/** Il menu ⋮: le azioni nell'ordine d'uso; "Dimentica" in fondo, dopo un'interruzione, col colore di errore. */
@Composable
private fun menuEntries(actions: Set<PersonAction>, handlers: PersonPageHandlers): List<GalleryMenuEntry> {
  val entries = ArrayList<GalleryMenuEntry>()
  for (spec in MenuSpecs.filter { it.action in actions }) {
    entries += GalleryMenuEntry.Item(label = stringResource(spec.label), onClick = { handlers.onAction(spec.action) }, icon = spec.icon)
  }
  if (ForgetSpec.action in actions) {
    entries += GalleryMenuEntry.GroupBreak
    entries += GalleryMenuEntry.Item(
      label = stringResource(ForgetSpec.label), onClick = { handlers.onAction(ForgetSpec.action) }, icon = ForgetSpec.icon, destructive = true,
    )
  }
  return entries
}

/** Nessuna azione: per le anteprime e gli screenshot. */
internal object NoPersonPageHandlers : PersonPageHandlers {
  override fun onBack() = Unit

  override fun onAction(action: PersonAction) = Unit

  override fun onOpenPhoto(media: MediaId) = Unit

  override fun onOpenProposal(proposal: MergeProposal) = Unit

  override fun onSaveName(text: String) = Unit

  override fun onRemoveName() = Unit

  override fun onDismissName() = Unit

  override fun onConfirmForget() = Unit

  override fun onDismissForget() = Unit

  override fun onPickCover(face: FaceLocator?) = Unit

  override fun onDismissCovers() = Unit

  override fun onDismissMessage() = Unit

  override fun onToggleCandidate(id: PersonId) = Unit

  override fun onContinueFromChoose() = Unit

  override fun onPick(id: PersonId) = Unit

  override fun onContinueFromName() = Unit

  override fun onConfirmMerge() = Unit

  override fun onRejectProposal() = Unit

  override fun onDismissMerge() = Unit
}
