package dev.pampa.mycloudgallery.core.security.input

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Impostazioni dei campi che contengono segreti (password del NAS, codice di recupero), da passare al
 * campo di testo dell'app (piano 1.5, docs/sicurezza.md C17).
 *
 * ```
 * OutlinedTextField(
 *   value = password, onValueChange = ...,
 *   keyboardOptions = SecureInput.keyboardOptions(),
 *   visualTransformation = SecureInput.hidden,
 * )
 * ```
 *
 * **Tastiera senza apprendimento.** `KeyboardType.Password` fa dichiarare al campo `TYPE_TEXT_VARIATION_PASSWORD`:
 * le tastiere (Gboard, Samsung Keyboard) non suggeriscono, non memorizzano e non imparano quel che si digita
 * in un campo cosi', e il correttore e' spento. Il flag esplicito `IME_FLAG_NO_PERSONALIZED_LEARNING`
 * (la modalita' "incognito" delle tastiere) Compose NON lo espone: verificato nel bytecode di foundation 1.11,
 * `EditorInfo.update` non lo imposta mai e `PlatformImeOptions` porta solo `privateImeOptions`. Per un campo
 * `EditText` classico esiste [editTextImeOptions].
 */
object SecureInput {

  /** Maiuscole spente, niente correttore, tastiera "password", azione finale "Fatto". */
  fun keyboardOptions(imeAction: ImeAction = ImeAction.Done): KeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
    imeAction = imeAction,
  )

  /** Punti al posto dei caratteri. */
  val hidden: VisualTransformation = PasswordVisualTransformation()

  /**
   * Per un `EditText` classico (non Compose): `editText.imeOptions = SecureInput.editTextImeOptions(editText.imeOptions)`.
   * Aggiunge `IME_FLAG_NO_PERSONALIZED_LEARNING` alle opzioni gia' presenti.
   */
  fun editTextImeOptions(current: Int = 0): Int = current or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}
