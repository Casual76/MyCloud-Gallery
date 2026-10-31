package dev.pampa.mycloudgallery.core.designsystem.adaptive

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** La tabella dei tasti (Fase 10, P4, D15, E-T10): ogni comando ha il suo tasto, e nessun tasto che cambia qualcosa si ripete tenendolo premuto. */
@RunWith(RobolectricTestRunner::class)
class GalleryKeysTest {

  private fun down(code: Int, meta: Int = 0, repeat: Int = 0): KeyEvent =
    KeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, repeat, meta))

  private fun up(code: Int): KeyEvent = KeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0, 0))

  @Test
  fun ognuniComandoHaIlSuoTasto() {
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_ENTER))).isEqualTo(GalleryKey.OPEN)
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_NUMPAD_ENTER))).isEqualTo(GalleryKey.OPEN)
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_A, AndroidKeyEvent.META_CTRL_ON))).isEqualTo(GalleryKey.SELECT_ALL)
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_A, AndroidKeyEvent.META_META_ON))).isEqualTo(GalleryKey.SELECT_ALL)
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_FORWARD_DEL))).isEqualTo(GalleryKey.DELETE)
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_DPAD_LEFT))).isEqualTo(GalleryKey.PREVIOUS)
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_DPAD_RIGHT))).isEqualTo(GalleryKey.NEXT)
  }

  @Test
  fun unTastoNonNostroNonSiConsuma() {
    // Lettere senza Ctrl, Esc (lo fa `MainActivity`), Backspace (non e' "Canc"), numeri, spazio: un campo di testo li riceve com'e'.
    for (code in listOf(
      AndroidKeyEvent.KEYCODE_A, AndroidKeyEvent.KEYCODE_ESCAPE, AndroidKeyEvent.KEYCODE_DEL, AndroidKeyEvent.KEYCODE_1,
      AndroidKeyEvent.KEYCODE_SPACE, AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_TAB,
    )) {
      assertThat(GalleryKeys.of(down(code))).isNull()
    }
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_C, AndroidKeyEvent.META_CTRL_ON))).isNull()
  }

  @Test
  fun contaSoloLaPressione_nonIlRilascio() {
    for (code in listOf(AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_FORWARD_DEL, AndroidKeyEvent.KEYCODE_DPAD_LEFT)) {
      assertThat(GalleryKeys.of(up(code))).isNull()
    }
  }

  @Test
  fun iComandiCheCambianoQualcosaNonSiRipetonoTenendoPremuto_leFreccePerforza() {
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_ENTER, repeat = 1))).isNull()
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_FORWARD_DEL, repeat = 3))).isNull()
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_A, AndroidKeyEvent.META_CTRL_ON, repeat = 2))).isNull()
    // Tenendo premuta la freccia si scorrono le foto.
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_DPAD_RIGHT, repeat = 5))).isEqualTo(GalleryKey.NEXT)
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_DPAD_LEFT, repeat = 5))).isEqualTo(GalleryKey.PREVIOUS)
  }

  @Test
  fun unModificatoreChePerLoStrumentoNonEUnAltroTastoNonEIlNostro() {
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.META_SHIFT_ON))).isNull()
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.META_ALT_ON))).isNull()
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.META_CTRL_ON))).isNull()
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_FORWARD_DEL, AndroidKeyEvent.META_SHIFT_ON))).isNull()
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_A, AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_SHIFT_ON))).isNull()
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.META_ALT_ON))).isNull()
    assertThat(GalleryKeys.of(down(AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.META_CTRL_ON))).isNull()
  }
}
