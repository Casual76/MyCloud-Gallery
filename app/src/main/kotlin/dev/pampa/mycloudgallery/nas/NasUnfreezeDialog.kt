package dev.pampa.mycloudgallery.nas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.nasmedia.contract.headlineCount
import dev.pampa.mycloudgallery.main.GalleryHostViewModel

/**
 * "Ho controllato, continua" (D10, §1.4): la doppia conferma dello scongelamento. Mostra quanti file sono cambiati nella
 * situazione anomala e chiede di **riscrivere quel numero**: solo cosi' le scritture automatiche ripartono e le assenze si
 * possono di nuovo segnare. Composable funzionale (componenti di Material 3, nessuno stile nuovo): l'aspetto e' del
 * filone grafico (§1.5). Nessun nome di share o cartella: solo il numero.
 */
@Composable
fun NasUnfreezeDialog(host: GalleryHostViewModel, onDismiss: () -> Unit) {
  val frozen by host.frozenAreas.collectAsStateWithLifecycle()
  val count = frozen.values.minByOrNull { it.sinceNasMillis }?.headlineCount
  var typed by remember { mutableStateOf("") }
  var wrong by remember { mutableStateOf(false) }
  if (count == null) {
    onDismiss()
    return
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.nas_unfreeze_title)) },
    text = {
      Column {
        Text(pluralStringResource(R.plurals.nas_unfreeze_text, count, count))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          value = typed,
          onValueChange = { value ->
            typed = value.filter(Char::isDigit).take(MAX_DIGITS)
            wrong = false
          },
          label = { Text(stringResource(R.string.nas_unfreeze_field)) },
          isError = wrong,
          supportingText = if (wrong) ({ Text(stringResource(R.string.nas_unfreeze_wrong)) }) else null,
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val number = typed.toIntOrNull()
          if (number != count) {
            wrong = true
          } else {
            host.releaseFreeze(number) { released -> if (released) onDismiss() else wrong = true }
          }
        },
      ) { Text(stringResource(R.string.nas_unfreeze_confirm)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.nas_unfreeze_cancel)) } },
  )
}

private const val MAX_DIGITS = 9
