package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.mycloudgallery.core.security.input.SecureInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * Il campo di un codice a gruppi (codice di approvazione, codice di recupero).
 *
 * Il testo che il chiamante tiene e' gia' formattato ([CodeFormat.format]): il campo lo riformatta a ogni
 * tasto e **riporta il cursore in fondo**, perche' un trattino inserito a meta' scritta fa saltare il
 * cursore (e la persona riscrive un carattere nel posto sbagliato). Maiuscole automatiche, niente correttore.
 *
 * Con [secret] la tastiera e' quella dei campi riservati ([SecureInput]): nessun suggerimento e nessun
 * apprendimento. Il testo resta visibile mentre si scrive (un codice di 28 caratteri nascosto da puntini non
 * si controlla), e la finestra e' protetta da `FLAG_SECURE` dalla pagina che lo ospita.
 */
@Composable
internal fun CodeField(
  value: String,
  onValueChange: (String) -> Unit,
  maxChars: Int,
  label: String,
  modifier: Modifier = Modifier,
  placeholder: String? = null,
  secret: Boolean = false,
  isError: Boolean = false,
  supportingText: String? = null,
  enabled: Boolean = true,
  imeAction: ImeAction = ImeAction.Done,
) {
  var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
  LaunchedEffect(value) {
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
  }
  FluidTextField(
    value = field,
    onValueChange = { typed ->
      val formatted = CodeFormat.format(typed.text, maxChars)
      field = TextFieldValue(formatted, TextRange(formatted.length))
      onValueChange(formatted)
    },
    modifier = modifier,
    label = label,
    placeholder = placeholder,
    enabled = enabled,
    isError = isError,
    supportingText = supportingText,
    showClearButton = true,
    keyboardOptions = if (secret) {
      SecureInput.keyboardOptions(imeAction).copy(capitalization = KeyboardCapitalization.Characters)
    } else {
      KeyboardOptions(
        capitalization = KeyboardCapitalization.Characters,
        autoCorrectEnabled = false,
        imeAction = imeAction,
      )
    },
  )
}
