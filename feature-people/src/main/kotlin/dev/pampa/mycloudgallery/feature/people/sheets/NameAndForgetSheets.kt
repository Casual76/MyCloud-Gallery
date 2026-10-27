package dev.pampa.mycloudgallery.feature.people.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetScaffold
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.faces.contract.PersonNames
import dev.pampa.mycloudgallery.feature.people.R
import dev.pampa.mycloudgallery.feature.people.contract.NamingUi
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.ui.CancelButton
import dev.pampa.mycloudgallery.feature.people.ui.PeopleDimens
import dev.pampa.mycloudgallery.feature.people.ui.PeopleText
import dev.pampa.mycloudgallery.feature.people.ui.PersonAvatar

// I FOGLI DEL NOME E DI "DIMENTICA" (Fase 8, P9; docs/fase8-progetto.md §2.6, D24, E-F27). Fogli di vetro del design system (`GalleryConfirmSheet`,
// con la difesa dal tapjacking di tutti gli altri); bottoni da 50 dp, mai due controlli sovrapposti. Il foglio del nome tiene il testo
// in uno stato suo (si azzera a ogni apertura); il nome lo valida `People` (1-64 caratteri, nessun carattere di controllo o invisibile).

/** "Aggiungi un nome" / "Rinomina": il campo del nome, "Salva", e se la persona ha gia' un nome "Togli il nome". */
@Composable
internal fun NameSheet(
  naming: NamingUi?,
  onSave: (String) -> Unit,
  onRemoveName: () -> Unit,
  onDismiss: () -> Unit,
) {
  val title = stringResource(if (naming?.current == null) R.string.persone_name_add_title else R.string.persone_name_rename_title)
  GalleryConfirmSheet(visible = naming != null, onDismissRequest = onDismiss, paneTitle = title) {
    var text by rememberSaveable(naming?.person?.value) { mutableStateOf(naming?.current.orEmpty()) }
    GallerySheetScaffold(
      icon = Icons.Rounded.Face,
      tone = GallerySheetTone.Brand,
      title = title,
      message = stringResource(R.string.persone_name_message),
      body = {
        FluidTextField(
          value = text,
          onValueChange = { text = it.take(PersonNames.MAX_CHARS) },
          placeholder = stringResource(R.string.persone_name_hint),
          modifier = Modifier.fillMaxWidth(),
          isError = naming?.invalid == true,
          supportingText = if (naming?.invalid == true) stringResource(R.string.persone_name_invalid) else null,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onSave(text) }),
        )
      },
      actions = {
        FluidButton(text = stringResource(R.string.persone_name_save), onClick = { onSave(text) }, enabled = text.isNotBlank(), size = FluidButtonSize.Large, fillWidth = true)
        if (naming?.current != null) {
          FluidButton(text = stringResource(R.string.persone_name_remove), onClick = onRemoveName, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Large, fillWidth = true)
        }
        CancelButton(onDismiss)
      },
    )
  }
}

/**
 * "Dimentica": la conferma che il piano chiede (§2.6): "I suoi volti non verranno piu' raggruppati; se arrivano foto nuove potrebbe
 * ricomparire senza nome". Non lascia nessuna impronta per riconoscerla di nuovo (D24, E-F27). [persons] sono le persone da dimenticare
 * (una dalla pagina, piu' d'una da "Altri volti"): con piu' d'una il testo dice quante.
 */
@Composable
internal fun ForgetSheet(
  persons: List<PersonCard>,
  crops: PeopleCrops,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  val resources = LocalContext.current.resources
  val single = persons.singleOrNull()
  val title = stringResource(if (persons.size > 1) R.string.persone_forget_many_title else R.string.persone_forget_title)
  GalleryConfirmSheet(visible = persons.isNotEmpty(), onDismissRequest = onDismiss, paneTitle = title) {
    GallerySheetScaffold(
      icon = Icons.Rounded.Delete,
      tone = GallerySheetTone.Destructive,
      title = title,
      message = if (persons.size > 1) resources.getQuantityString(R.plurals.persone_forget_many_message, persons.size, persons.size) else stringResource(R.string.persone_forget_message),
      body = {
        if (single != null) {
          Row(horizontalArrangement = Arrangement.spacedBy(PeopleDimens.BlockGap), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(single.cover, crops, PeopleDimens.AvatarSheet, Modifier.size(PeopleDimens.AvatarSheet))
            Column(verticalArrangement = Arrangement.spacedBy(PeopleDimens.TightGap / 2)) {
              Text(PeopleText.nameInSentence(resources, single), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
              Text(PeopleText.photos(resources, single.photos), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
        Text(stringResource(R.string.persone_forget_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      },
      actions = {
        FluidButton(text = stringResource(R.string.persone_forget_confirm), onClick = onConfirm, style = FluidButtonStyle.Destructive, size = FluidButtonSize.Large, fillWidth = true)
        CancelButton(onDismiss)
      },
    )
  }
}
