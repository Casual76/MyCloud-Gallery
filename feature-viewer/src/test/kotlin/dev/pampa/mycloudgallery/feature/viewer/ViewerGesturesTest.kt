package dev.pampa.mycloudgallery.feature.viewer

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.info.ViewerMediaAlbums
import dev.pampa.mycloudgallery.feature.viewer.info.ViewerAlbumEntry
import androidx.compose.ui.test.onNodeWithText
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.up
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerAnchor
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.testing.PlayerCall
import org.junit.Test

/**
 * H1 sul viewer vero (Robolectric, sui fake): i gesti della chiusura e lo zoom dei video.
 *
 *  - il **pizzico che stringe** a zoom minimo chiude come il trascinamento (D7); quello che allarga ingrandisce; uno appena accennato torna;
 *  - un **annullamento di sistema** (il gesto Indietro dal bordo che il sistema si prende) non chiude e non e' un tocco: le barre restano;
 *  - l'**Indietro predittivo** guida la stessa chiusura: annullato torna, completato chiude;
 *  - i **video si ingrandiscono** come le foto (D8): doppio tocco e pizzico; la barra del video resta toccabile e lo zoom resta mentre il video va,
 *    in pausa e durante lo scorrimento della barra del tempo; cambiando elemento lo zoom si azzera; un video si chiude col trascinamento, verso
 *    la cella o, senza cella, sfumando.
 */
class ViewerGesturesTest : ViewerTestBase() {

  /** Una Libreria con dei video. */
  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private val videoIndex get() = fixture.indicesOf(MediaKind.VIDEO).first { it > 2 }

  private val zoomed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ingrandita")

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  private fun isZoomed(): Boolean = composeRule.onAllNodes(zoomed).fetchSemanticsNodes().isNotEmpty()

  private fun chromeShown(): Boolean = composeRule.onAllNodesWithContentDescription("Chiudi").fetchSemanticsNodes().isNotEmpty()

  // --- Il pizzico in chiusura (D7) ------------------------------------------------------------------------------------

  @Test
  fun unPizzicoCheStringeAZoomMinimoChiude() {
    show()
    pager().performTouchInput {
      pinch(
        start0 = center - Offset(300f, 0f),
        end0 = center - Offset(70f, 0f),
        start1 = center + Offset(300f, 0f),
        end1 = center + Offset(70f, 0f),
        durationMillis = 300,
      )
    }
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
  }

  @Test
  fun unPizzicoCheAllargaIngrandisceENonChiude() {
    show()
    pager().performTouchInput {
      pinch(
        start0 = center - Offset(70f, 0f),
        end0 = center - Offset(300f, 0f),
        start1 = center + Offset(70f, 0f),
        end1 = center + Offset(300f, 0f),
        durationMillis = 300,
      )
    }
    composeRule.waitForIdle()
    assertThat(isZoomed()).isTrue()
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun unPizzicoAppenaAccennatoTornaAlSuoPosto() {
    show()
    pager().performTouchInput {
      pinch(
        start0 = center - Offset(300f, 0f),
        end0 = center - Offset(275f, 0f),
        start1 = center + Offset(300f, 0f),
        end1 = center + Offset(275f, 0f),
        durationMillis = 1_000,
      )
    }
    composeRule.waitForIdle()
    assertThat(closed).isEqualTo(0)
    // Tornato a riposo: le barre sono di nuovo li', la foto non e' ingrandita.
    assertThat(chromeShown()).isTrue()
    assertThat(isZoomed()).isFalse()
  }

  // --- Annullamento di sistema e Indietro predittivo -----------------------------------------------------------------

  @Test
  fun unAnnullamentoDiSistemaNonChiudeENonEUnTocco() {
    show()
    pager().performTouchInput {
      down(center)
      moveBy(Offset(0f, 300f), delayMillis = 60)
      moveBy(Offset(0f, 300f), delayMillis = 60)
      cancel()
    }
    composeRule.waitForIdle()
    assertThat(closed).isEqualTo(0)
    // Un tocco avrebbe nascosto le barre: sono ancora li'.
    assertThat(chromeShown()).isTrue()
  }

  @Test
  fun lIndietroPredittivoAnnullatoTorna_completatoChiude() {
    show()
    val dispatcher = composeRule.activity.onBackPressedDispatcher
    composeRule.runOnUiThread {
      dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 900f, 0f, BackEventCompat.EDGE_LEFT))
      dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 900f, 0.6f, BackEventCompat.EDGE_LEFT))
    }
    composeRule.waitForIdle()
    composeRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
    composeRule.waitForIdle()
    assertThat(closed).isEqualTo(0)
    assertThat(chromeShown()).isTrue()

    composeRule.runOnUiThread {
      dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 900f, 0f, BackEventCompat.EDGE_LEFT))
      dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 900f, 0.8f, BackEventCompat.EDGE_LEFT))
      dispatcher.onBackPressed()
    }
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
  }

  // --- Lo zoom dei video (D8) ----------------------------------------------------------------------------------------

  @Test
  fun aPannelloInformazioniApertoIlDoppioToccoNonIngrandisceIlVideo() {
    // CG-3: come per le foto. Prima il ramo del video non riceveva il blocco e il video si ingrandiva sotto il pannello.
    show(request = fixture.request(startIndex = videoIndex))
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(800)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag(ViewerTags.InfoPanel).assertExists()
    pager().performTouchInput { doubleClick(Offset(centerX, height * 0.2f)) }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(800)
    composeRule.waitForIdle()
    assertThat(isZoomed()).isFalse()
  }

  @Test
  fun unVideoSiIngrandisceColDoppioTocco_laBarraRestaToccabile_eLoZoomResta() {
    show(request = fixture.request(startIndex = videoIndex))
    composeRule.onNodeWithContentDescription("Pausa").assertExists()

    pager().performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    assertThat(isZoomed()).isTrue()

    // La barra del video sta fuori dallo zoom: si tocca, e lo zoom resta in pausa...
    composeRule.onNodeWithContentDescription("Pausa").performClick()
    composeRule.waitForIdle()
    assertThat(player.calls).contains(PlayerCall.Pause)
    assertThat(isZoomed()).isTrue()

    // ... e mentre si scorre la barra del tempo.
    val slider = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }
    composeRule.waitForIdle()
    assertThat(player.seeks).isNotEmpty()
    assertThat(isZoomed()).isTrue()
  }

  @Test
  fun unVideoSiIngrandisceColPizzico() {
    show(request = fixture.request(startIndex = videoIndex))
    pager().performTouchInput {
      pinch(
        start0 = center - Offset(60f, 0f),
        end0 = center - Offset(280f, 0f),
        start1 = center + Offset(60f, 0f),
        end1 = center + Offset(280f, 0f),
        durationMillis = 300,
      )
    }
    composeRule.waitForIdle()
    assertThat(isZoomed()).isTrue()
    assertThat(closed).isEqualTo(0)
  }

  @Test
  fun cambiandoElementoLoZoomDelVideoSiAzzera() {
    show(request = fixture.request(startIndex = videoIndex))
    pager().performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    assertThat(isZoomed()).isTrue()

    pageAction("Foto successiva")
    composeRule.waitForIdle()
    pageAction("Foto precedente")
    composeRule.waitForIdle()

    assertThat(isZoomed()).isFalse()
  }

  @Test
  fun unVideoSiChiudeColTrascinamentoVersoLaCella() {
    show(request = fixture.request(startIndex = videoIndex))
    pager().performTouchInput { swipeDown() }
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
  }

  @Test
  fun unVideoSenzaCellaSiChiudeSfumando() {
    // Aperto da un album: niente griglia sotto, nessuna cella.
    val request = ViewerRequest(items = fixture.items, start = fixture.idAt(videoIndex), anchor = FakeViewerAnchor(), secure = false)
    show(request = request)
    pager().performTouchInput { swipeDown() }
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
    // Il lettore si e' fermato prima del volo: la miniatura sfuma al posto della superficie, che non sa sfumare.
    assertThat(player.calls).contains(PlayerCall.Stop)
  }

  /** Un'azione di TalkBack del pager ("Foto successiva", "Foto precedente"): come le userebbe chi non vede le pagine. */
  private fun pageAction(label: String) {
    val withAction = SemanticsMatcher("ha l'azione $label") { node ->
      node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
    }
    val node = composeRule.onAllNodes(withAction).fetchSemanticsNodes().first()
    val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
    composeRule.runOnUiThread { action.action() }
  }

  // --- La cella d'origine resta vuota (CU-1) ------------------------------------------------------------------------------

  @Test
  fun laCellaDOrigineEVuotaMentreLaFotoNeEsceECiRientra() {
    show()
    val id = fixture.idAt(4)
    // L'apertura: la cella si svuota mentre la foto ne esce e torna piena a espansione finita.
    assertThat(fixture.anchor.hiddenHistory).containsExactly(id, null).inOrder()
    assertThat(fixture.anchor.hidden).isNull()

    // Il trascinamento: vuota mentre il dito tiene la foto...
    pager().performTouchInput {
      down(center)
      moveBy(Offset(0f, 60f), delayMillis = 16)
      moveBy(Offset(0f, 60f), delayMillis = 16)
    }
    composeRule.waitForIdle()
    assertThat(fixture.anchor.hidden).isEqualTo(id)
    // ...e di nuovo piena quando la foto e' tornata al suo posto.
    pager().performTouchInput { up() }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(800)
    composeRule.waitForIdle()
    assertThat(fixture.anchor.hidden).isNull()

    // Il volo alla cella: vuota fino all'arrivo, poi piena nello stesso momento in cui il viewer se ne va.
    composeRule.onNodeWithContentDescription("Chiudi").performClick()
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
    assertThat(fixture.anchor.hidden).isNull()
    assertThat(fixture.anchor.hiddenHistory.takeLast(2)).containsExactly(id, null).inOrder()
  }

  @Test
  fun conIlPannelloInformazioniApertoLaXChiudeIlViewerVolandoAllaCella() {
    // La chiusura porta giu' il pannello e poi vola: prima l'effetto del pannello partiva sulla stessa animazione e la chiusura si fermava a meta'.
    show()
    val id = fixture.idAt(4)
    pager().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.3f) }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(800)
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Chiudi").performClick()
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
    assertThat(fixture.anchor.hiddenHistory.takeLast(2)).containsExactly(id, null).inOrder()
    assertThat(fixture.anchor.hidden).isNull()
  }

  // --- CG-6: la chiusura con il pannello aperto non si ferma per un gesto che arriva intanto ---------------------------------------------
  //
  // Con l'orologio fermo: informazioni aperte, la chiusura parte (la X o la capsula di un album), qualche fotogramma di discesa del pannello,
  // poi un gesto sul pannello; infine l'orologio va. Prima il gesto prendeva la stessa animazione che la chiusura aspettava e la cancellava:
  // il viewer restava in chiusura per sempre, con la cella vuota.

  /** Il pannello aperto e fermo (lo swipe in su sulla foto). */
  private fun openInfoPanel() {
    pager().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.3f) }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(PANEL_REST_MS)
    composeRule.waitForIdle()
  }

  /** Un dito sul pannello che sta scendendo: giu' in basso, dove il pannello c'e' ancora, e su di due passi. */
  private fun dragOnPanel(release: Boolean = true) {
    composeRule.onRoot().performTouchInput {
      down(Offset(centerX, height * PANEL_TOUCH_Y))
      moveBy(Offset(0f, -PANEL_STEP_PX))
      moveBy(Offset(0f, -PANEL_STEP_PX))
      if (release) up()
    }
    composeRule.waitForIdle()
  }

  /** L'orologio va fino in fondo: il viewer esce una volta sola e la cella d'origine non resta vuota. */
  private fun assertClosedOnce(id: MediaId) {
    composeRule.mainClock.autoAdvance = true
    composeRule.waitUntil(TIMEOUT) { closed >= 1 }
    composeRule.mainClock.advanceTimeBy(AFTER_ARRIVAL_MS)
    composeRule.waitForIdle()
    assertThat(closed).isEqualTo(1)
    assertThat(fixture.anchor.hidden).isNull()
    assertThat(fixture.anchor.hiddenHistory.takeLast(2)).containsExactly(id, null).inOrder()
  }

  @Test
  fun cg6_unDitoSulPannelloDopoLaXNonFermaLaChiusura() {
    show()
    val id = fixture.idAt(4)
    openInfoPanel()
    composeRule.mainClock.autoAdvance = false
    composeRule.onNodeWithContentDescription("Chiudi").performSemanticsAction(SemanticsActions.OnClick)
    composeRule.mainClock.advanceTimeBy(FIRST_FRAMES_MS)
    dragOnPanel()
    assertClosedOnce(id)
  }

  @Test
  fun cg6_unDitoSulPannelloDopoLaCapsulaDiUnAlbumNonFermaLaChiusura() {
    val vacanze = ViewerAlbumEntry(key = "a", title = "Vacanze 2024", count = 12)
    val opened = mutableListOf<ViewerAlbumEntry>()
    val albums = object : ViewerMediaAlbums {
      override fun albumsOf(media: MediaId): Flow<List<ViewerAlbumEntry>> = flowOf(listOf(vacanze))
      override fun open(album: ViewerAlbumEntry) {
        opened += album
      }
    }
    show(services = services(mediaAlbums = albums))
    val id = fixture.idAt(4)
    openInfoPanel()
    composeRule.mainClock.autoAdvance = false
    composeRule.onNodeWithText("Vacanze 2024").performSemanticsAction(SemanticsActions.OnClick)
    composeRule.mainClock.advanceTimeBy(FIRST_FRAMES_MS)
    dragOnPanel()
    assertClosedOnce(id)
    assertThat(opened).containsExactly(vacanze)
  }

  @Test
  fun cg6_unGestoSulPannelloCominciatoPrimaDellaXNonFermaLaChiusura() {
    // Un dito tiene il pannello, un altro tocca la X, il primo continua a muoversi.
    show()
    val id = fixture.idAt(4)
    openInfoPanel()
    composeRule.mainClock.autoAdvance = false
    dragOnPanel(release = false)
    composeRule.onNodeWithContentDescription("Chiudi").performSemanticsAction(SemanticsActions.OnClick)
    composeRule.mainClock.advanceTimeBy(FIRST_FRAMES_MS)
    composeRule.onRoot().performTouchInput {
      moveBy(Offset(0f, -PANEL_STEP_PX))
      moveBy(Offset(0f, PANEL_STEP_PX))
      up()
    }
    composeRule.waitForIdle()
    assertClosedOnce(id)
  }

  @Test
  fun cg6_lIndietroPredittivoDelPannelloAnnullatoDuranteLaChiusuraNonLaFerma() {
    // L'Indietro sul pannello aperto e' cominciato, poi la X, poi l'Indietro si annulla: prima rimetteva su il pannello in mezzo alla chiusura.
    show()
    val id = fixture.idAt(4)
    openInfoPanel()
    val dispatcher = composeRule.activity.onBackPressedDispatcher
    composeRule.mainClock.autoAdvance = false
    composeRule.runOnUiThread {
      dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 900f, 0f, BackEventCompat.EDGE_LEFT))
      dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 900f, 0.3f, BackEventCompat.EDGE_LEFT))
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.onNodeWithContentDescription("Chiudi").performSemanticsAction(SemanticsActions.OnClick)
    composeRule.mainClock.advanceTimeBy(FIRST_FRAMES_MS)
    composeRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
    composeRule.waitForIdle()
    assertClosedOnce(id)
  }

  @Test
  fun laCapsulaDiUnAlbumChiudeIlViewerVolandoAllaCella_eSoloDopoApreLAlbum() {
    // CS-22: prima il viewer spariva di colpo (l'host toglieva la richiesta); ora si chiude come con la X, poi l'album si apre.
    val vacanze = ViewerAlbumEntry(key = "a", title = "Vacanze 2024", count = 12)
    val openedAfterClosing = mutableListOf<Pair<ViewerAlbumEntry, Int>>()
    val albums = object : ViewerMediaAlbums {
      override fun albumsOf(media: MediaId): Flow<List<ViewerAlbumEntry>> = flowOf(listOf(vacanze))
      override fun open(album: ViewerAlbumEntry) {
        openedAfterClosing += album to closed
      }
    }
    show(services = services(mediaAlbums = albums))
    val id = fixture.idAt(4)
    pager().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.3f) }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(800)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Vacanze 2024").performSemanticsAction(SemanticsActions.OnClick)
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
    composeRule.waitForIdle()
    // Il volo: la cella vuota fino all'arrivo, poi piena; l'album chiesto una volta sola, a viewer gia' chiuso.
    assertThat(fixture.anchor.hiddenHistory.takeLast(2)).containsExactly(id, null).inOrder()
    assertThat(fixture.anchor.hidden).isNull()
    assertThat(openedAfterClosing).containsExactly(vacanze to 1)
  }

  @Test
  fun anchePizzicandoLaCellaEVuotaFinoAllArrivo() {
    show()
    val id = fixture.idAt(4)
    pager().performTouchInput {
      pinch(
        start0 = center - Offset(300f, 0f),
        end0 = center - Offset(70f, 0f),
        start1 = center + Offset(300f, 0f),
        end1 = center + Offset(70f, 0f),
        durationMillis = 300,
      )
    }
    composeRule.waitUntil(TIMEOUT) { closed == 1 }
    // Dopo l'apertura (id, null): vuota dal pizzico all'arrivo, poi piena. Mai una cella lasciata vuota.
    assertThat(fixture.anchor.hiddenHistory).containsExactly(id, null, id, null).inOrder()
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }
}

/** CG-6: il pannello fermo dopo lo swipe (la molla di `GalleryMotion`, abbondante). */
private const val PANEL_REST_MS = 800L

/** CG-6: i primi fotogrammi della chiusura (tre a 60 Hz): il pannello ha cominciato a scendere ed e' ancora li'. */
private const val FIRST_FRAMES_MS = 48L

/** CG-6: dopo l'arrivo, abbastanza per vedere una seconda uscita se ci fosse. */
private const val AFTER_ARRIVAL_MS = 2_000L

/** CG-6: dove il dito tocca il pannello che scende (frazione dell'altezza) e di quanto si muove a ogni passo. */
private const val PANEL_TOUCH_Y = 0.9f
private const val PANEL_STEP_PX = 60f
