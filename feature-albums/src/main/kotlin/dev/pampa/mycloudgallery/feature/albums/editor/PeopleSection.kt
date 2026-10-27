package dev.pampa.mycloudgallery.feature.albums.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.OptionRow
import dev.pampa.mycloudgallery.feature.albums.ui.SectionNote

// LA SEZIONE "PERSONE" DELL'EDITOR (Fase 8, P10; docs/fase8-progetto.md §2.7 "Album smart", D27, E-F23, E-F27, E-F28, E-F37). Come le altre sezioni e' un gruppo lista con la
// sua intestazione e dice con le parole cosa vale: le persone della regola per nome (una riga ciascuna, che si tocca per toglierla), "tutte" o "una qualunque" quando sono
// due o piu', e in fondo "Aggiungi persone" che apre il foglio di scelta ([PeoplePickerSheet]). Dove i volti non ci sono la sezione lo dice e la regola resta com'e'.
// Una persona nascosta non mostra il nome (E-F28); una perduta lo dice (E-F27). Nessun controllo sta sopra un altro.

/** Se la sezione ha qualcosa da dire: con i volti accesi sempre; spenti, solo se la regola ha gia' delle persone (altrimenti non c'e' niente da scegliere ne' da perdere). */
internal fun showsPeopleSection(ui: SmartEditorUi, rule: PeopleRule?): Boolean = ui.peopleOn || rule != null

@Composable
internal fun PeopleSection(ui: SmartEditorUi, rule: PeopleRule?, handlers: SmartEditorHandlers, onPick: () -> Unit) {
  val resources = LocalContext.current.resources
  Column(verticalArrangement = Arrangement.spacedBy(AlbumDimens.TightGap)) {
    FluidSectionHeader(title = stringResource(R.string.editor_people))
    FluidListGroup {
      if (rule == null) {
        FluidListRow(
          title = stringResource(R.string.editor_people_none), subtitle = stringResource(R.string.editor_people_none_detail), disclosure = false,
          leading = { Icon(Icons.Rounded.Person, contentDescription = null) },
        )
      } else {
        rule.ids.forEachIndexed { index, id ->
          if (index > 0) FluidListDivider()
          PersonRow(id, ui, handlers)
        }
        if (rule.ids.size > 1) {
          FluidListDivider()
          Text(
            stringResource(R.string.editor_people_mode_header), modifier = Modifier.padding(AlbumDimens.Inset),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
          )
          OptionRow(
            title = stringResource(R.string.editor_people_mode_all), detail = stringResource(R.string.editor_people_mode_all_detail),
            selected = rule.mode == PeopleMode.ALL, onSelect = { handlers.onPeopleMode(PeopleMode.ALL) },
          )
          FluidListDivider()
          OptionRow(
            title = stringResource(R.string.editor_people_mode_any), detail = stringResource(R.string.editor_people_mode_any_detail),
            selected = rule.mode == PeopleMode.ANY, onSelect = { handlers.onPeopleMode(PeopleMode.ANY) },
          )
        }
        val lost = ui.peopleInfo.lost.count { it in rule.ids }
        if (lost > 0) {
          FluidListDivider()
          SectionNote(resources.getQuantityString(R.plurals.editor_people_lost_note, lost, lost))
        }
        FluidListDivider()
        SectionNote(stringResource(R.string.editor_people_classes_note))
      }
      if (!ui.peopleOn) {
        if (rule != null) FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.editor_people_off), subtitle = stringResource(R.string.editor_people_off_detail), disclosure = false, tone = FluidTone.Warning,
          leading = { Icon(Icons.Rounded.PersonOff, contentDescription = null) },
        )
      } else {
        FluidListDivider()
        if (ui.peopleChoices.isEmpty()) {
          SectionNote(stringResource(R.string.editor_people_none_to_choose))
        } else {
          FluidListRow(
            title = stringResource(R.string.editor_people_add), subtitle = "", tone = FluidTone.Primary, onClick = onPick,
            leading = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
          )
        }
      }
      if (rule != null) {
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.editor_people_remove_all), subtitle = "", tone = FluidTone.Danger, disclosure = false, onClick = handlers::onRemovePeople,
          leading = { Icon(Icons.Rounded.Close, contentDescription = null) },
        )
      }
    }
  }
}

/** Una persona della regola: il nome se la porta lo sa, "Persona non piu' riconosciuta" se e' perduta, "Una persona nascosta" altrimenti; si tocca per toglierla. */
@Composable
private fun PersonRow(id: PersonId, ui: SmartEditorUi, handlers: SmartEditorHandlers) {
  val name = ui.peopleInfo.names[id]
  val lost = id in ui.peopleInfo.lost
  val title = when {
    name != null -> name
    lost -> stringResource(R.string.editor_people_lost_name)
    else -> stringResource(R.string.editor_people_hidden_name)
  }
  val detail = when {
    name != null -> ""
    lost -> stringResource(R.string.editor_people_lost_detail)
    else -> stringResource(R.string.editor_people_hidden_detail)
  }
  FluidListRow(
    title = title, subtitle = detail, disclosure = false, tone = if (lost) FluidTone.Warning else FluidTone.Neutral,
    leading = { Icon(Icons.Rounded.Person, contentDescription = null) },
    badge = { Icon(Icons.Rounded.Close, contentDescription = null) },
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClickLabel = stringResource(R.string.editor_people_remove, title), role = Role.Button) { handlers.onRemovePerson(id) },
  )
}
