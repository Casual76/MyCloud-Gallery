package dev.pampa.mycloudgallery.feature.viewer

import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.SurfaceView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.up
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedKind
import dev.pampa.mycloudgallery.core.imaging.contract.AnimatedSpec
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.testing.PlayerCall
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import org.junit.Test

/**
 * I contenuti che non sono una foto ferma: il video (un solo lettore, parte quando la pagina si ferma, scorrimento
 * `CLOSEST_SYNC` e poi esatto, codec non supportato spiegato), la motion photo di formato sconosciuto (E-M16: il resto, badge e animazione,
 * sta in `ViewerMotionTest`) e le immagini animate (E-M11).
 */
class ViewerMediaTest : ViewerTestBase() {

  /** Una Libreria piu' grande: ci sono video e anche qualche motion photo. */
  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private val videos get() = fixture.indicesOf(MediaKind.VIDEO)
  private val motions get() = fixture.indicesOf(MediaKind.MOTION_PHOTO)

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  private fun uriOf(index: Int) = Uri.parse("content://media/external_primary/${1000L + index}")

  @Test
  fun laLibreriaDiProvaHaVideoEMotionPhoto() {
    assertThat(videos).isNotEmpty()
    assertThat(motions).isNotEmpty()
  }

  @Test
  fun unVideoPartePerSoloQuandoLaPaginaSiFermaConLAudio() {
    val index = videos.first()
    show(request = fixture.request(startIndex = index))
    assertThat(player.loads).hasSize(1)
    val load = player.loads.single()
    assertThat(load.source).isEqualTo(PlaybackSource.Whole(uriOf(index)))
    assertThat(load.autoplay).isTrue()
    assertThat(load.muted).isFalse()
    // HDR su SurfaceView: la superficie del lettore e' una SurfaceView.
    assertThat(player.boundSurface).isInstanceOf(SurfaceView::class.java)
  }

  @Test
  fun scorrendoViaDalVideoSiFermaEIlLettoreResteUno() {
    val first = videos.first()
    val second = videos.first { it > first }
    show(request = fixture.request(startIndex = first))
    repeat(second - first) {
      pager().performTouchInput { swipeLeft() }
      composeRule.waitForIdle()
    }
    // Il primo video si e' fermato e il secondo e' partito, con lo stesso lettore.
    assertThat(player.calls).contains(PlayerCall.Stop)
    assertThat(player.loads.map { it.source }).containsExactly(
      PlaybackSource.Whole(uriOf(first)),
      PlaybackSource.Whole(uriOf(second)),
    ).inOrder()
    assertThat(playersCreated).isEqualTo(1)
  }

  @Test
  fun unaFotoNonCreaIlLettore() {
    val photo = (0 until fixture.library.count).first { fixture.library.snapshot.kindAt(it) == MediaKind.PHOTO }
    show(request = fixture.request(startIndex = photo))
    assertThat(playersCreated).isEqualTo(0)
    assertThat(player.loads).isEmpty()
  }

  @Test
  fun laBarraDelVideoScorreConIFotogrammiChiaveEAlRilascioEsatto() {
    val index = videos.first()
    show(request = fixture.request(startIndex = index))
    composeRule.onNodeWithContentDescription("Pausa").assertExists()
    val slider = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
    composeRule.waitForIdle()
    val target = (0.5f * 134_000).toLong()
    assertThat(player.seeks.map { it.exact }).containsExactly(false, true).inOrder()
    assertThat(player.seeks.map { it.positionMs }.distinct()).containsExactly(target)
    // Durante lo scorrimento il video si ferma e, finito, riparte: come in Foto.
    val relevant = player.calls.dropWhile { it !is PlayerCall.Pause }
    assertThat(relevant.map { it::class.simpleName }).containsAtLeast("Pause", "Seek", "Seek", "Play").inOrder()
  }

  @Test
  fun unCodecCheIlDispositivoNonRegge_ViendeSpiegato() {
    val index = videos.first()
    show(request = fixture.request(startIndex = index))
    player.failWith(PlaybackProblem.UNSUPPORTED)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Questo dispositivo non riesce a riprodurre il video").assertExists()
  }

  @Test
  fun unVideoRovinatoDiceCheNonSiRiproduce() {
    show(request = fixture.request(startIndex = videos.first()))
    player.failWith(PlaybackProblem.FAILED)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Non riesco a riprodurre il video").assertExists()
  }

  @Test
  fun unTocco_BrevenonAvviaLaMotionPhoto() {
    val index = motions.first()
    show(request = fixture.request(startIndex = index))
    pager().performTouchInput {
      down(center)
      up()
    }
    composeRule.waitForIdle()
    assertThat(player.loads).isEmpty()
  }

  @Test
  fun e_M16_unaMotionPhotoDiFormatoSconosciutoRestaFermaSenzaBadgeELoDiceNelleInfo() {
    val index = motions.first()
    // Il localizzatore non riconosce il formato (default del fake): niente animazione ne' badge (D50, E-V16).
    show(request = fixture.request(startIndex = index))
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(player.loads).isEmpty()
    composeRule.onAllNodesWithContentDescription("Riproduci il movimento").assertCountEquals(0)
    // E nelle informazioni resta scritto.
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Il formato di questa foto in movimento non è riconosciuto: si vede la foto ferma.").assertExists()
  }

  @Test
  fun e_M11_unaImmagineAnimataParteQuandoLaPaginaECorrenteESiFermaQuandoSiEsce() {
    val index = (0 until fixture.library.count).first { fixture.library.snapshot.kindAt(it) == MediaKind.PHOTO }
    val ref = fixture.items.ref(fixture.idAt(index))
    images.animated[ref] = AnimatedSpec(AnimatedKind.GIF, 320, 240)
    val drawable = RunningDrawable()
    show(
      request = fixture.request(startIndex = index),
      services = services(animatedLoader = { drawable }),
    )
    composeRule.waitForIdle()
    assertThat(drawable.running).isTrue()
    pager().performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    assertThat(drawable.running).isFalse()
  }

  /** Un'immagine animata finta: dice se sta girando. */
  private class RunningDrawable : ColorDrawable(0xFF336699.toInt()), Animatable {
    var running = false
    override fun start() {
      running = true
    }

    override fun stop() {
      running = false
    }

    override fun isRunning(): Boolean = running
  }
}
