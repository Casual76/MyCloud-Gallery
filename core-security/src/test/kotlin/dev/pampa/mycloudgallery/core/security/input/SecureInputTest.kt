package dev.pampa.mycloudgallery.core.security.input

import android.view.inputmethod.EditorInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecureInputTest {

  @Test
  fun tastieraPassword_senzaCorrettore_senzaMaiuscole() {
    val options = SecureInput.keyboardOptions()
    assertThat(options.keyboardType).isEqualTo(KeyboardType.Password)
    assertThat(options.autoCorrectEnabled).isFalse()
    assertThat(options.capitalization).isEqualTo(KeyboardCapitalization.None)
    assertThat(options.imeAction).isEqualTo(ImeAction.Done)
  }

  @Test
  fun lAzioneFinaleSiPuoCambiare_ilResto_no() {
    val options = SecureInput.keyboardOptions(ImeAction.Next)
    assertThat(options.imeAction).isEqualTo(ImeAction.Next)
    assertThat(options.keyboardType).isEqualTo(KeyboardType.Password)
    assertThat(options.autoCorrectEnabled).isFalse()
  }

  @Test
  fun iCaratteriSonoNascosti() {
    val shown = SecureInput.hidden.filter(AnnotatedString("segreto")).text.text
    assertThat(shown).doesNotContain("segreto")
    assertThat(shown).hasLength("segreto".length)
  }

  @Test
  fun perGliEditTextClassici_siAggiungeIlFlagIncognito_senzaPerdereGliAltri() {
    val withFlag = SecureInput.editTextImeOptions(EditorInfo.IME_ACTION_DONE)
    assertThat(withFlag and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).isNotEqualTo(0)
    assertThat(withFlag and EditorInfo.IME_MASK_ACTION).isEqualTo(EditorInfo.IME_ACTION_DONE)
    assertThat(SecureInput.editTextImeOptions()).isEqualTo(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
  }
}
