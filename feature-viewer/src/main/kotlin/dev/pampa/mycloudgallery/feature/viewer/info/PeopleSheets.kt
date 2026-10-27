package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceChip
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceRef
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPeople

// I FOGLI DELLA RIGA "PERSONE" (Fase 8, P9; docs/fase8-progetto.md §2.6, D24, E-F3, E-F21): il menu di un chip ("Chi e'?", "Non e' …", "Apri la
// pagina", "Ignora questo volto"), "Chi e'?" (le persone con un nome, o un nome nuovo) e la conferma di "Ignora questo volto". Fogli di vetro del
// design system, con la difesa dal tapjacking di tutti gli altri; bottoni da 50 dp, righe da 48 dp. Le azioni valgono subito e viaggiano con la
// pubblicazione dei volti.

/** Quante persone al massimo si offrono in "Chi e'?" (le piu' fotografate per prime, le altre si cercano dalla pagina delle persone). */
private const val MAX_CHOICES = 40

@Composable
internal fun PeopleSheets(source: ViewerPeople, media: MediaId, chips: List<ViewerFaceChip>, sheet: PeopleSheet?, controller: PeopleInfoController) {
  MenuSheet(sheet as? PeopleSheet.Menu, media, controller)
  WhoSheet(sheet as? PeopleSheet.Who, media, controller)
  IgnoreSheet(sheet as? PeopleSheet.ConfirmIgnore, media, controller)
}

@Composable
private fun MenuSheet(sheet: PeopleSheet.Menu?, media: MediaId, controller: PeopleInfoController) {
  val title = stringResource(R.string.viewer_people_menu)
  GalleryConfirmSheet(visible = sheet != null, onDismissRequest = controller::dismiss, paneTitle = title) {
    val chip = sheet?.chip
    val face = when (chip) {
      is ViewerFaceChip.Named -> chip.face
      is ViewerFaceChip.Unnamed -> chip.face
      else -> null
    }
    val named = chip as? ViewerFaceChip.Named
    val person = when (chip) {
      is ViewerFaceChip.Named -> chip.person
      is ViewerFaceChip.Unnamed -> chip.person
      else -> null
    }
    val notLabel = if (named != null) stringResource(R.string.viewer_people_not, named.name) else stringResource(R.string.viewer_people_not_this)
    GallerySheetScaffold(
      icon = Icons.Rounded.Face,
      tone = GallerySheetTone.Brand,
      title = title,
      message = named?.name,
      body = {
        FluidListGroup {
          if (face != null) {
            FluidListRow(
              title = stringResource(R.string.viewer_people_who), subtitle = stringResource(R.string.viewer_people_who_detail), tone = FluidTone.Primary,
              disclosure = false, onClick = { controller.who(media, face) },
              leading = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
            )
          }
          if (named != null) {
            FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.viewer_people_open_page), subtitle = stringResource(R.string.viewer_people_open_page_detail), tone = FluidTone.Primary,
              disclosure = false, onClick = { controller.openPage(named.person) },
              leading = { Icon(Icons.Rounded.Face, contentDescription = null) },
            )
          }
          if (face != null && person != null) {
            FluidListDivider()
            FluidListRow(
              title = notLabel,
              subtitle = stringResource(R.string.viewer_people_not_detail), tone = FluidTone.Neutral,
              disclosure = false, onClick = { controller.notThisPerson(media, face, person) },
              leading = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null) },
            )
          }
          if (face != null) {
            FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.viewer_people_ignore), subtitle = stringResource(R.string.viewer_people_ignore_detail), tone = FluidTone.Danger,
              disclosure = false, onClick = { controller.askIgnore(face) },
              leading = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null) },
            )
          }
        }
      },
      actions = { CancelRow(controller::dismiss) },
    )
  }
}

@Composable
private fun WhoSheet(sheet: PeopleSheet.Who?, media: MediaId, controller: PeopleInfoController) {
  val title = stringResource(R.string.viewer_people_who)
  GalleryConfirmSheet(visible = sheet != null, onDismissRequest = controller::dismiss, paneTitle = title) {
    var name by rememberSaveable(sheet?.face?.token) { mutableStateOf("") }
    GallerySheetScaffold(
      icon = Icons.Rounded.PersonAdd,
      tone = GallerySheetTone.Brand,
      title = title,
      message = stringResource(R.string.viewer_people_who_message),
      body = {
        val choices = sheet?.choices.orEmpty().take(MAX_CHOICES)
        if (choices.isNotEmpty()) {
          FluidListGroup {
            choices.forEachIndexed { index, choice ->
              if (index > 0) FluidListDivider()
              FluidListRow(
                title = choice.name, subtitle = "", disclosure = false,
                onClick = { sheet?.let { controller.choose(media, it.face, choice.person) } },
                leading = { Icon(Icons.Rounded.Face, contentDescription = null) },
              )
            }
          }
        }
        Text(
          text = stringResource(R.string.viewer_people_new_person),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FluidTextField(
          value = name,
          onValueChange = { name = it },
          placeholder = stringResource(R.string.viewer_people_new_hint),
          modifier = Modifier.fillMaxWidth(),
          isError = sheet?.invalid == true,
          supportingText = if (sheet?.invalid == true) stringResource(R.string.viewer_people_name_invalid) else null,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) sheet?.let { controller.createPerson(media, it.face, name) } }),
        )
      },
      actions = {
        FluidButton(
          text = stringResource(R.string.viewer_people_new_save), onClick = { sheet?.let { controller.createPerson(media, it.face, name) } },
          enabled = name.isNotBlank(), size = FluidButtonSize.Large, fillWidth = true,
        )
        CancelRow(controller::dismiss)
      },
    )
  }
}

@Composable
private fun IgnoreSheet(sheet: PeopleSheet.ConfirmIgnore?, media: MediaId, controller: PeopleInfoController) {
  val title = stringResource(R.string.viewer_people_ignore_title)
  GalleryConfirmSheet(visible = sheet != null, onDismissRequest = controller::dismiss, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.VisibilityOff,
      tone = GallerySheetTone.Destructive,
      title = title,
      message = stringResource(R.string.viewer_people_ignore_message),
      actions = {
        FluidButton(
          text = stringResource(R.string.viewer_people_ignore_confirm), onClick = { sheet?.let { controller.confirmIgnore(media, it.face) } },
          style = FluidButtonStyle.Destructive, size = FluidButtonSize.Large, fillWidth = true,
        )
        CancelRow(controller::dismiss)
      },
    )
  }
}

@Composable
private fun CancelRow(onClick: () -> Unit) {
  FluidButton(text = stringResource(R.string.viewer_people_cancel), onClick = onClick, style = FluidButtonStyle.Plain, size = FluidButtonSize.Large, fillWidth = true)
}
