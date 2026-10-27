package dev.pampa.mycloudgallery.feature.people.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.MergeSheetState
import dev.pampa.mycloudgallery.feature.people.contract.MergeUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.ui.CancelButton
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens
import dev.pampa.mycloudgallery.feature.people.ui.PeopleText
import dev.pampa.mycloudgallery.feature.people.ui.PersonAvatar
import dev.pampa.mycloudgallery.feature.people.ui.RadioMark

// IL FOGLIO DELL'UNIONE (Fase 8, P9; docs/fase8-progetto.md §1.5 `MergeSheetState`, §2.6 "Unisci" e "Proposte", E-F23, D22, D24). Un foglio di
// vetro del design system che cambia contenuto con lo stato: si sceglie con chi unire, si sceglie quale nome tenere (con due nomi), si
// conferma. Con una proposta ("E' la stessa persona?") la conferma mostra i due volti grandi e i bottoni "Si'" e "No". Ogni riga e' da 48 dp
// almeno, i bottoni da 50: mai due controlli uno sull'altro. L'unione parte solo dal tocco su "Unisci" o "Si'".

/** Cosa il foglio chiede fuori da se': i passi di [MergeFlow] (la rotta li lega al controllore). */
internal interface MergeHandlers {
  fun onToggleCandidate(id: PersonId)

  fun onContinueFromChoose()

  fun onPick(id: PersonId)

  fun onContinueFromName()

  fun onConfirmMerge()

  /** "No, sono diverse" (solo per una proposta). */
  fun onRejectProposal()

  fun onDismissMerge()
}

/** I candidati si offrono al piu' tanti alla volta (sono righe di un foglio, non una pagina): i piu' fotografati per primi. */
private const val MAX_CHOICES = 80

@Composable
internal fun MergeSheet(ui: MergeUi?, crops: PeopleCrops, handlers: MergeHandlers) {
  val sheet = ui?.sheet
  val title = stringResource(
    when (sheet) {
      is MergeSheetState.Choose -> R.string.persone_merge_choose_title
      is MergeSheetState.PickName -> R.string.persone_merge_pick_title
      is MergeSheetState.Confirm -> if (sheet.proposal != null) R.string.persone_merge_proposal_title else R.string.persone_merge_confirm_title
      null -> R.string.persone_merge_confirm_title
    },
  )
  GalleryConfirmSheet(visible = ui != null, onDismissRequest = handlers::onDismissMerge, paneTitle = title) {
    when (sheet) {
      is MergeSheetState.Choose -> ChooseContent(title, sheet, crops, handlers)
      is MergeSheetState.PickName -> PickNameContent(title, sheet, crops, handlers)
      is MergeSheetState.Confirm -> ConfirmContent(title, sheet, ui?.busy == true, crops, handlers)
      null -> Unit
    }
  }
}

@Composable
private fun ChooseContent(title: String, sheet: MergeSheetState.Choose, crops: PeopleCrops, handlers: MergeHandlers) {
  val resources = LocalContext.current.resources
  GallerySheetScaffold(
    icon = Icons.Rounded.CallMerge,
    tone = GallerySheetTone.Brand,
    title = title,
    message = stringResource(R.string.persone_merge_choose_message),
    body = {
      FluidListGroup {
        sheet.candidates.sortedByDescending { it.photos }.take(MAX_CHOICES).forEachIndexed { index, card ->
          if (index > 0) FluidListDivider()
          val chosen = card.id in sheet.selected
          FluidListRow(
            title = PeopleText.title(resources, card),
            subtitle = PeopleText.photos(resources, card.photos),
            disclosure = false,
            selected = chosen,
            onClick = { handlers.onToggleCandidate(card.id) },
            leading = { PersonAvatar(card.cover, crops, PeopleDimens.AvatarRow, Modifier.size(PeopleDimens.AvatarRow)) },
            badge = { RadioMark(selected = chosen) },
            modifier = Modifier.semantics(mergeDescendants = true) {
              role = Role.Checkbox
              selected = chosen
            },
          )
        }
      }
    },
    actions = {
      FluidButton(
        text = stringResource(R.string.persone_merge_continue), onClick = handlers::onContinueFromChoose, enabled = sheet.selected.isNotEmpty(),
        size = FluidButtonSize.Large, fillWidth = true,
      )
      CancelButton(handlers::onDismissMerge)
    },
  )
}

@Composable
private fun PickNameContent(title: String, sheet: MergeSheetState.PickName, crops: PeopleCrops, handlers: MergeHandlers) {
  val resources = LocalContext.current.resources
  GallerySheetScaffold(
    icon = Icons.Rounded.CallMerge,
    tone = GallerySheetTone.Brand,
    title = title,
    message = stringResource(R.string.persone_merge_pick_message),
    body = {
      FluidListGroup {
        listOf(sheet.first, sheet.second).forEachIndexed { index, card ->
          if (index > 0) FluidListDivider()
          val chosen = sheet.chosen == card.id
          FluidListRow(
            title = stringResource(R.string.persone_merge_keep, PeopleText.nameInSentence(resources, card)),
            subtitle = PeopleText.photos(resources, card.photos),
            disclosure = false,
            selected = chosen,
            onClick = { handlers.onPick(card.id) },
            leading = { PersonAvatar(card.cover, crops, PeopleDimens.AvatarRow, Modifier.size(PeopleDimens.AvatarRow)) },
            badge = { RadioMark(selected = chosen) },
            modifier = Modifier.semantics(mergeDescendants = true) {
              role = Role.RadioButton
              selected = chosen
            },
          )
        }
      }
    },
    actions = {
      FluidButton(
        text = stringResource(R.string.persone_merge_continue), onClick = handlers::onContinueFromName, enabled = sheet.canConfirm,
        size = FluidButtonSize.Large, fillWidth = true,
      )
      if (sheet.proposal != null) {
        FluidButton(
          text = stringResource(R.string.persone_merge_no), onClick = handlers::onRejectProposal, style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large, fillWidth = true,
        )
      }
      CancelButton(handlers::onDismissMerge, label = stringResource(if (sheet.proposal != null) R.string.persone_merge_not_now else R.string.persone_cancel))
    },
  )
}

@Composable
private fun ConfirmContent(title: String, sheet: MergeSheetState.Confirm, busy: Boolean, crops: PeopleCrops, handlers: MergeHandlers) {
  val resources = LocalContext.current.resources
  val keptName = sheet.persons.firstOrNull { it.id == sheet.keepNameOf }?.name
  val message = when {
    sheet.proposal != null -> stringResource(R.string.persone_merge_proposal_message)
    keptName != null -> stringResource(R.string.persone_merge_keeps_name, keptName)
    else -> stringResource(R.string.persone_merge_keeps_none)
  }
  GallerySheetScaffold(
    icon = Icons.Rounded.CallMerge,
    tone = GallerySheetTone.Brand,
    title = title,
    message = message,
    body = {
      Row(horizontalArrangement = Arrangement.spacedBy(PeopleDimens.BlockGap), verticalAlignment = Alignment.Top) {
        sheet.persons.take(MAX_FACES).forEach { card -> FaceWithName(card, crops, Modifier.weight(1f)) }
      }
      if (sheet.persons.size > MAX_FACES) {
        Text(
          text = resources.getQuantityString(R.plurals.persone_merge_more, sheet.persons.size - MAX_FACES, sheet.persons.size - MAX_FACES),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    actions = {
      FluidButton(
        text = stringResource(if (sheet.proposal != null) R.string.persone_merge_yes else R.string.persone_merge_confirm),
        onClick = handlers::onConfirmMerge, enabled = !busy, loading = busy, size = FluidButtonSize.Large, fillWidth = true,
      )
      if (sheet.proposal != null) {
        FluidButton(
          text = stringResource(R.string.persone_merge_no), onClick = handlers::onRejectProposal, enabled = !busy, style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large, fillWidth = true,
        )
      }
      CancelButton(handlers::onDismissMerge, label = stringResource(if (sheet.proposal != null) R.string.persone_merge_not_now else R.string.persone_cancel))
    },
  )
}

/** Un volto grande con il nome (o "senza nome") sotto: il nome sta sotto il volto, mai sopra. */
@Composable
private fun FaceWithName(card: PersonCard, crops: PeopleCrops, modifier: Modifier) {
  val resources = LocalContext.current.resources
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap)) {
    PersonAvatar(card.cover, crops, PeopleDimens.AvatarSheet, Modifier.size(PeopleDimens.AvatarSheet))
    Text(
      text = card.name ?: resources.getString(R.string.persone_unnamed_short),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
      maxLines = 2,
    )
    Text(
      text = PeopleText.photos(resources, card.photos),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Quanti volti si mostrano nella conferma (oltre, "e altre N"). */
private const val MAX_FACES = 4
