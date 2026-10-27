package dev.pampa.mycloudgallery.feature.albums.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.PersonChoice
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumDimens
import dev.pampa.mycloudgallery.feature.albums.ui.SectionNote
import java.text.Normalizer

// IL FOGLIO DI SCELTA DELLE PERSONE (Fase 8, P10; docs/fase8-progetto.md §2.7 "Creazione": "Aggiungi persone"). Un elenco di persone con un nome, le piu' frequenti prima,
// ognuna una riga da 48 dp che e' un interruttore per il lettore di schermo ("Marco, 124 foto, attivo"); il pomello e' solo il segno. Ogni tocco cambia subito la regola
// dietro il foglio (e l'anteprima), cosi' si vede l'effetto; "Fatto" chiude. Con piu' di otto persone c'e' un campo "Cerca per nome" (senza accenti ne' maiuscole) e l'elenco
// scorre dentro il foglio. Mai una persona nascosta: la porta non la da'.

/** Da quante persone in su il foglio offre il campo per cercare. */
private const val FILTER_FROM = 8

@Composable
internal fun PeoplePickerSheet(
  visible: Boolean,
  choices: List<PersonChoice>,
  chosen: Set<PersonId>,
  onToggle: (PersonId) -> Unit,
  onDismiss: () -> Unit,
) {
  GalleryConfirmSheet(visible = visible, onDismissRequest = onDismiss, paneTitle = stringResource(R.string.editor_people_pick_title)) {
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = if (filter.isBlank()) choices else choices.filter { plain(it.label).contains(plain(filter)) }
    GallerySheetScaffold(
      icon = Icons.Rounded.PersonAdd,
      tone = GallerySheetTone.Brand,
      title = stringResource(R.string.editor_people_pick_title),
      message = stringResource(R.string.editor_people_pick_message),
      body = {
        if (choices.size > FILTER_FROM) {
          FluidTextField(
            value = filter, onValueChange = { filter = it }, placeholder = stringResource(R.string.editor_people_pick_filter),
            modifier = Modifier.fillMaxWidth(),
          )
        }
        if (shown.isEmpty()) {
          SectionNote(stringResource(R.string.editor_people_pick_empty))
        } else {
          Column(modifier = Modifier.fillMaxWidth().heightIn(max = AlbumDimens.PickerMaxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Top) {
            FluidListGroup {
              shown.forEachIndexed { index, choice ->
                if (index > 0) FluidListDivider()
                PickRow(choice, choice.id in chosen) { onToggle(choice.id) }
              }
            }
          }
        }
      },
      actions = { FluidButton(text = stringResource(R.string.editor_people_pick_done), onClick = onDismiss, size = FluidButtonSize.Large, fillWidth = true) },
    )
  }
}

/** Una riga: il nome, quante foto ha, l'interruttore. Tutta la riga e' l'interruttore. */
@Composable
private fun PickRow(choice: PersonChoice, checked: Boolean, onToggle: () -> Unit) {
  val resources = LocalContext.current.resources
  FluidListRow(
    title = choice.label, subtitle = resources.getQuantityString(R.plurals.editor_people_photos, choice.photos, choice.photos), disclosure = false,
    leading = { Icon(Icons.Rounded.Person, contentDescription = null) },
    badge = { FluidSwitch(checked = checked, onCheckedChange = null) },
    modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }),
  )
}

/** Il testo senza accenti e in minuscolo, per cercare "elena" e trovare "Éléna". */
private fun plain(text: String): String =
  Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
