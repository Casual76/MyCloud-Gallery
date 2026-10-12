package dev.pampa.mycloudgallery.core.security.input

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SensitiveClipboardTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun laCostanteCoincideConQuellaDiAndroid() {
    assertThat(SensitiveClipboard.EXTRA_IS_SENSITIVE).isEqualTo(ClipDescription.EXTRA_IS_SENSITIVE)
  }

  @Test
  fun copiaIlTesto_marcandoloComeSensibile() {
    assertThat(SensitiveClipboard.copy(context, "Codice", "ABCD-1234")).isTrue()

    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
    assertThat(clip.getItemAt(0).text.toString()).isEqualTo("ABCD-1234")
    assertThat(clip.description.label.toString()).isEqualTo("Codice")
    assertThat(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)).isTrue()
  }

  @Test
  fun unaCopiaSuccessiva_sostituisceLaPrecedente() {
    SensitiveClipboard.copy(context, "a", "uno")
    SensitiveClipboard.copy(context, "b", "due")
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
    assertThat(clip.getItemAt(0).text.toString()).isEqualTo("due")
  }
}
