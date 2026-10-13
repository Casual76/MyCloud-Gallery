package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.viewer.video.Media3ViewerPlayer
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import dev.pampa.mycloudgallery.feature.viewer.video.playbackProblemOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il lettore vero, su Media3, per quanto si puo' provare senza un decoder: si costruisce solo con sorgenti locali, non
 * crolla se gli si chiede di tutto in qualunque ordine, e dopo `release` non fa piu' niente. I codici d'errore che
 * vogliono dire "questo dispositivo non riesce a riprodurre" si distinguono da un file rovinato (E-M7, E-M13).
 * Il video vero, con HDR e scorrimento, si prova sul campo (F12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Media3PlayerTest {

  @Test
  fun siCostruisceELiberaSenzaEccezioni() {
    val player = Media3ViewerPlayer(ApplicationProvider.getApplicationContext())
    assertThat(player.state.value.source).isNull()
    player.bind(null)
    player.setMuted(true)
    assertThat(player.state.value.muted).isTrue()
    player.pause()
    player.seekTo(1_000L, exact = false)
    player.seekTo(2_000L, exact = true)
    player.stop()
    player.release()
    // Dopo release non fa piu' niente e non lancia.
    player.play()
    player.seekTo(0L, exact = true)
    player.setMuted(false)
    player.release()
  }

  @Test
  fun iCodiciDelDecoderDiconoCheIlDispositivoNonRiesce() {
    for (code in listOf(
      PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
      PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
      PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
      PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
      PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    )) {
      assertThat(playbackProblemOf(code)).isEqualTo(PlaybackProblem.UNSUPPORTED)
    }
  }

  @Test
  fun unFileIllegibileONonPiuDisponibileEUnAltroProblema() {
    for (code in listOf(
      PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
      PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
      PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
      PlaybackException.ERROR_CODE_UNSPECIFIED,
    )) {
      assertThat(playbackProblemOf(code)).isEqualTo(PlaybackProblem.FAILED)
    }
  }
}
