package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.centerX
import androidx.compose.ui.test.centerY
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.fluid.FluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionAnimationSetting
import dev.pampa.mycloudgallery.feature.viewer.motion.PowerSaving
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeMotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.testing.PlayerCall
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideo
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackClip
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import org.junit.Test

/**
 * Le foto in movimento nel viewer vero (Fase 6, P14; D45, D50, D51; E-V14, E-V16): la pressione lunga non chiama mai il lettore, il badge
 * "Movimento" e le sue due strade (⋮ e azione di TalkBack) riproducono il video intero con l'audio, l'animazione d'apertura parte quando
 * la pagina si ferma ed e' muta e tagliata, un tocco la ferma. Il resto della logica (scorrimento veloce, condizioni, NAS) sta in
 * `MotionDirectorTest` e `MotionNasDirectorTest`.
 */
class ViewerMotionTest : ViewerTestBase() {

  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private val motions get() = fixture.indicesOf(MediaKind.MOTION_PHOTO)
  private val found = MotionVideo.Found(ByteRange(1_000L, 5_000L), stillUs = 2_000_000L, durationUs = 4_000_000L)

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  private fun uriOf(index: Int) = android.net.Uri.parse("content://media/external_primary/${1000L + index}")

  private fun motionServices(index: Int, animation: Boolean = true, power: Boolean = false): ViewerServices {
    val ref = fixture.items.ref(fixture.idAt(index))
    return services(
      motion = FakeMotionVideoLocator(mapOf(ref to found)),
      motionAnimation = MotionAnimationSetting { animation },
      powerSaving = PowerSaving { power },
    )
  }

  private fun showMotion(animation: Boolean = true, power: Boolean = false, reducedMotion: Boolean = false): Int {
    val index = motions.first()
    val services = motionServices(index, animation, power)
    val request = fixture.request(startIndex = index)
    composeRule.setContent {
      ViewerTestHost {
        CompositionLocalProvider(LocalFluidMotionPolicy provides FluidMotionPolicy(durationScale = if (reducedMotion) 0f else 1f)) {
          ViewerOverlay(request = request, onClosed = { closed++ }, services = services)
        }
      }
    }
    composeRule.waitForIdle()
    return index
  }

  /**
   * Come [showMotion] ma **senza** il `CompositionLocalProvider` del test: la politica di movimento arriva dal tema vero (`MyCloudGalleryTheme`, dentro `ViewerTestHost`), che la
   * legge dalla scala delle animazioni del sistema ([systemAnimatorScale]). E' la strada di un telefono con "Rimuovi animazioni" acceso (SOL-2 della review generale).
   */
  private fun showMotionUnderRealTheme(systemScale: Float): Int {
    systemAnimatorScale = systemScale
    val index = motions.first()
    val services = motionServices(index, animation = true, power = false)
    val request = fixture.request(startIndex = index)
    composeRule.setContent {
      ViewerTestHost { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) }
    }
    composeRule.waitForIdle()
    return index
  }

  /** Fa passare il tempo del pager fermo e della ricerca del video. */
  private fun settle() {
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
  }

  // --- La pressione lunga non avvia piu' il video (D45) -----------------------------------------------------------------

  @Test
  fun laPressioneLungaNonChiamaMaiIlLettore() {
    showMotion(animation = false)
    settle()
    pager().performTouchInput { down(center) }
    composeRule.mainClock.advanceTimeBy(1_500)
    composeRule.waitForIdle()
    pager().performTouchInput { up() }
    composeRule.waitForIdle()
    assertThat(player.calls).isEmpty()
    assertThat(playersCreated).isEqualTo(0)
  }

  // --- Il badge "Movimento" (D51) -----------------------------------------------------------------------------------------

  @Test
  fun ilBadgeCeSoloSuUnaFotoInMovimentoChePuoRiprodursi() {
    val photo = fixture.indicesOf(MediaKind.PHOTO).first()
    show(request = fixture.request(startIndex = photo))
    composeRule.onAllNodesWithContentDescription("Riproduci il movimento").assertCountEquals(0)
  }

  @Test
  fun e_M16_unaFotoInMovimentoDiFormatoSconosciutoNonHaIlBadge() {
    // Il localizzatore di serie non riconosce niente.
    show(request = fixture.request(startIndex = motions.first()))
    settle()
    composeRule.onAllNodesWithContentDescription("Riproduci il movimento").assertCountEquals(0)
  }

  @Test
  fun ilBadgeStaNellaBarraEHaIlBersaglioDa48dp_ETalkBackLoLegge() {
    showMotion(animation = false)
    settle()
    val badge = composeRule.onNodeWithContentDescription("Riproduci il movimento")
    badge.assertExists()
    badge.assertHeightIsAtLeast(48.dp)
    badge.assertWidthIsAtLeast(48.dp)
    // E la pagina si legge come "Foto in movimento" per TalkBack.
    composeRule.onNode(SemanticsMatcher("descrizione della pagina") { node ->
      node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains("Foto in movimento") } == true
    }).assertExists()
  }

  @Test
  fun ilBadgeRiproduceIlVideoInteroConLAudio_UnaVolta_EUnAltroTocco_LoFerma() {
    val index = showMotion(animation = false)
    settle()
    composeRule.onNodeWithContentDescription("Riproduci il movimento").performClick()
    composeRule.waitForIdle()
    val load = player.loads.single()
    assertThat(load.source).isEqualTo(PlaybackSource.Range(uriOf(index), start = 1_000L, length = 4_000L))
    assertThat(load.muted).isFalse()
    assertThat(load.clip).isNull()
    assertThat(load.autoplay).isTrue()
    // Mentre gira il badge lo dice e un altro tocco lo ferma.
    composeRule.onNodeWithContentDescription("Ferma il movimento").performClick()
    composeRule.waitForIdle()
    assertThat(player.calls.last()).isEqualTo(PlayerCall.Stop)
    composeRule.onNodeWithContentDescription("Riproduci il movimento").assertExists()
    assertThat(playersCreated).isEqualTo(1)
  }

  @Test
  fun ilBadgeFunzionaConLInterruttoreSpento_ConRimuoviAnimazioniEConIlRisparmioEnergetico() {
    showMotion(animation = false, power = true, reducedMotion = true)
    settle()
    assertThat(player.calls).isEmpty()
    composeRule.onNodeWithContentDescription("Riproduci il movimento").performClick()
    composeRule.waitForIdle()
    assertThat(player.loads.single().muted).isFalse()
  }

  @Test
  fun laVoceDelMenu_RiproduciIlMovimento_FaLoStesso() {
    val index = showMotion(animation = false)
    settle()
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitForIdle()
    // Il menu ha la voce (e il badge ha la stessa etichetta): si sceglie quella del menu.
    composeRule.onAllNodesWithContentDescription("Riproduci il movimento").assertCountEquals(1)
    composeRule.onNodeWithText("Riproduci il movimento").performClick()
    composeRule.waitForIdle()
    assertThat(player.loads.single().source).isEqualTo(PlaybackSource.Range(uriOf(index), 1_000L, 4_000L))
    assertThat(player.loads.single().muted).isFalse()
  }

  @Test
  fun lAzioneDiTalkBackSullaPagina_RiproduceIlMovimento() {
    val index = showMotion(animation = false)
    settle()
    val actions = SemanticsMatcher("azione di TalkBack") { node ->
      node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Riproduci il movimento" } == true
    }
    composeRule.onNode(actions).assertExists()
    // L'azione si trova nell'albero della pagina e si esegue come fa TalkBack.
    val action = composeRule.onNode(actions).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Riproduci il movimento" }
    composeRule.runOnUiThread { action.action() }
    composeRule.waitForIdle()
    assertThat(player.loads.single().source).isEqualTo(PlaybackSource.Range(uriOf(index), 1_000L, 4_000L))
    assertThat(player.loads.single().muted).isFalse()
  }

  // --- L'animazione d'apertura (D50) --------------------------------------------------------------------------------------

  @Test
  fun quandoLaPaginaSiFerma_ParteIlTrattoTagliatoEMuto() {
    val index = showMotion()
    settle()
    val load = player.loads.single()
    assertThat(load.clip).isEqualTo(PlaybackClip(startMs = 800L, endMs = 2_000L))
    assertThat(load.muted).isTrue()
    assertThat(load.source).isEqualTo(PlaybackSource.Range(uriOf(index), 1_000L, 4_000L))
    assertThat(playersCreated).isEqualTo(1)
  }

  @Test
  fun unTocco_FermaSubitoLAnimazione_EIlTocco_ValeComeSempre() {
    showMotion()
    settle()
    assertThat(player.loads).hasSize(1)
    pager().performTouchInput { down(center) }
    composeRule.waitForIdle()
    // Il primo dito ferma l'animazione; poi il gesto continua come sempre.
    assertThat(player.calls.last()).isEqualTo(PlayerCall.Stop)
    pager().performTouchInput { up() }
    composeRule.waitForIdle()
    assertThat(player.loads).hasSize(1)
  }

  @Test
  fun unDoppioTocco_FermaSubitoLAnimazione() {
    showMotion()
    settle()
    pager().performTouchInput { doubleClick() }
    composeRule.waitForIdle()
    assertThat(player.calls).contains(PlayerCall.Stop)
    assertThat(player.loads).hasSize(1)
  }

  @Test
  fun unPizzico_FermaSubitoLAnimazione() {
    showMotion()
    settle()
    assertThat(player.loads).hasSize(1)
    pager().performTouchInput {
      pinch(
        start0 = androidx.compose.ui.geometry.Offset(centerX - 40f, centerY),
        end0 = androidx.compose.ui.geometry.Offset(centerX - 160f, centerY),
        start1 = androidx.compose.ui.geometry.Offset(centerX + 40f, centerY),
        end1 = androidx.compose.ui.geometry.Offset(centerX + 160f, centerY),
      )
    }
    composeRule.waitForIdle()
    assertThat(player.calls).contains(PlayerCall.Stop)
    assertThat(player.loads).hasSize(1)
  }

  @Test
  fun unoScorrimentoVeloceFermaLAnimazione_ENonNeFaPartireAltreSulleCartellePagine() {
    showMotion()
    settle()
    assertThat(player.loads).hasSize(1)
    repeat(3) {
      pager().performTouchInput { swipeLeft() }
      composeRule.waitForIdle()
    }
    assertThat(player.calls).contains(PlayerCall.Stop)
    // Nessuna animazione e' partita mentre si scorreva: ne' per le pagine attraversate ne' per la prima dopo i 150 ms (qui la pagina e' una foto).
    assertThat(player.loads.size).isAtMost(2)
    assertThat(playersCreated).isEqualTo(1)
  }

  @Test
  fun chiudereLeInfoNonRifaLAnimazione_E_V14() {
    showMotion()
    settle()
    assertThat(player.loads).hasSize(1)
    // Le informazioni si aprono (swipe verso l'alto) e si richiudono (verso il basso): la pagina e' sempre la stessa, l'animazione non si rifa.
    pager().performTouchInput { swipeUp() }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    pager().performTouchInput { swipeDown() }
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.waitForIdle()
    assertThat(player.loads).hasSize(1)
  }

  @Test
  fun conRimuoviAnimazioni_NonCEUnaChiamataAlLettore() {
    showMotion(reducedMotion = true)
    settle()
    assertThat(player.calls).isEmpty()
    assertThat(playersCreated).isEqualTo(0)
  }

  /**
   * SOL-2 della review generale: `GalleryTheme` non installava `FluidMotionPolicyProvider`, quindi sotto il tema vero `reducedMotion` non era mai vero e le foto in movimento
   * partivano anche con le animazioni di sistema spente. Il test di sopra (`conRimuoviAnimazioni_...`) non lo vedeva: dava la politica a mano.
   */
  @Test
  fun conLeAnimazioniDiSistemaSpente_SottoIlTemaVero_NonCEUnaChiamataAlLettore() {
    showMotionUnderRealTheme(systemScale = 0f)
    settle()
    assertThat(player.calls).isEmpty()
    assertThat(playersCreated).isEqualTo(0)
  }

  /** Il controllo: stessa strada, animazioni di sistema accese, e l'animazione d'apertura parte (se no il test di sopra non proverebbe niente). */
  @Test
  fun conLeAnimazioniDiSistemaAccese_SottoIlTemaVero_LAnimazioneParte() {
    showMotionUnderRealTheme(systemScale = 1f)
    settle()
    assertThat(player.loads).hasSize(1)
    assertThat(playersCreated).isEqualTo(1)
  }

  @Test
  fun conIlRisparmioEnergetico_NonCEUnaChiamataAlLettore() {
    showMotion(power = true)
    settle()
    assertThat(player.calls).isEmpty()
    assertThat(playersCreated).isEqualTo(0)
  }

  @Test
  fun conLInterruttoreSpento_NonCEUnaChiamataAlLettore() {
    showMotion(animation = false)
    settle()
    assertThat(player.calls).isEmpty()
    assertThat(playersCreated).isEqualTo(0)
  }
}
