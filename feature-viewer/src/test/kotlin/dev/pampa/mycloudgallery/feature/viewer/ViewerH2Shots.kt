package dev.pampa.mycloudgallery.feature.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.up
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.MediaBackupState
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.metadata.contract.ExifDetails
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetails
import dev.pampa.mycloudgallery.feature.viewer.details.MediaDetailsSource
import dev.pampa.mycloudgallery.feature.viewer.info.MediaBackupStates
import dev.pampa.mycloudgallery.feature.viewer.info.ViewerAlbumEntry
import dev.pampa.mycloudgallery.feature.viewer.info.ViewerMediaAlbums
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceChip
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceRef
import dev.pampa.mycloudgallery.feature.viewer.place.ViewerMapPlace
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerPlaceMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Gli screenshot di H2, il pannello informazioni: aperto su una foto verticale (la foto sale attaccata al bordo del pannello) e su una larga
 * (ingrandita a riempire la meta' alta, tagliata ai lati); a meta' corsa (il pannello riempie la fascia vuota sotto la foto senza spostarla); un
 * video con i comandi sopra il bordo; tutti i dati e pochi dati; un problema di backup; un pannello lungo scorso (la foto salita con lui). Chiaro e
 * scuro, telefono dell'utente (360 x 780 dp) e tablet (la colonna laterale; a meta' corsa e lo scorrimento sono solo del telefono).
 *
 * `gradlew :feature-viewer:recordRoborazziDebug --tests "dev.pampa.mycloudgallery.feature.viewer.ViewerH2Shots*"` scrive i PNG in
 * `docs/screenshots/beta-grafica/H2/`. Le foto sono disegni (cielo, prato, nuvole): Robolectric non sfoca il vetro.
 */
abstract class ViewerH2Shots(private val device: String, private val tablet: Boolean) : ViewerTestBase() {

  /** Una Libreria con dei video. */
  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private fun path(name: String, dark: Boolean) = "../docs/screenshots/beta-grafica/H2/viewer-info-$name-${if (dark) "scuro" else "chiaro"}-$device.png"

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  /** Apre il viewer sulla foto [index] con le proporzioni [aspectMilli] (larghezza / altezza x 1000). */
  private fun showPhoto(aspectMilli: Int, dark: Boolean, services: ViewerServices, index: Int = 4) {
    fixture.items.publish(aspectView(fixture.library.view(), aspectMilli))
    show(request = fixture.request(startIndex = index), services = services, dark = dark)
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
  }

  /** La bitmap ha le stesse proporzioni che dichiara il catalogo ([aspectView]): la geometria del pannello si calcola da quelle. */
  private fun bitmapFor(portrait: Boolean): Bitmap = if (portrait) scene(PORTRAIT_W, PORTRAIT_H) else scene(WIDE_W, WIDE_H)

  private fun photoServices(portrait: Boolean, backup: MediaBackupState? = MediaBackupState.Safe(1_792_119_120_000), exif: ExifDetails = fullExif) = services(
    imageSource = photoSource(bitmapFor(portrait)),
    backup = backup?.let { state -> MediaBackupStates { flowOf(state) } },
    exif = exif,
  )

  /** Il pannello aperto: con lo swipe in su sul telefono, con il tasto sul tablet. */
  private fun openPanel() {
    if (tablet) {
      composeRule.onNodeWithContentDescription("Informazioni").performClick()
    } else {
      pager().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.3f) }
    }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    assertThat(composeRule.onAllNodesWithTag(ViewerTags.InfoPanel).fetchSemanticsNodes()).isNotEmpty()
  }

  private fun shoot(name: String, dark: Boolean) = composeRule.onRoot().captureRoboImage(path(name, dark))

  // --- Le foto ------------------------------------------------------------------------------------------------------------------------------------

  private fun portraitOpen(dark: Boolean) {
    showPhoto(PORTRAIT, dark, photoServices(portrait = true))
    openPanel()
    shoot("foto-verticale-aperto", dark)
  }

  private fun landscapeOpen(dark: Boolean) {
    showPhoto(WIDE, dark, photoServices(portrait = false))
    openPanel()
    shoot("foto-orizzontale-aperto", dark)
  }

  /** A meta' corsa, col dito ancora giu': il pannello riempie la fascia vuota sotto la foto larga, che resta dov'e'. */
  private fun halfway(dark: Boolean) {
    showPhoto(WIDE, dark, photoServices(portrait = false))
    pager().performTouchInput {
      down(Offset(centerX, height * 0.8f))
      moveBy(Offset(0f, -40f), delayMillis = 16)
      moveBy(Offset(0f, -120f), delayMillis = 16)
      moveBy(Offset(0f, -120f), delayMillis = 16)
    }
    composeRule.waitForIdle()
    shoot("a-meta-fascia-vuota", dark)
    pager().performTouchInput { up() }
    composeRule.waitForIdle()
  }

  // --- Il video -----------------------------------------------------------------------------------------------------------------------------------

  private fun video(dark: Boolean) {
    val index = fixture.indicesOf(MediaKind.VIDEO).first { it > 2 }
    fixture.items.publish(aspectView(fixture.library.view(), WIDE))
    show(
      request = fixture.request(startIndex = index),
      services = services(imageSource = photoSource(bitmapFor(portrait = false)), backup = MediaBackupStates { flowOf(MediaBackupState.Queued(null)) }),
      dark = dark,
    )
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    openPanel()
    shoot("video", dark)
  }

  // --- I dati -------------------------------------------------------------------------------------------------------------------------------------

  /** Tutti i dati, con la mappa e le persone: sul telefono il pannello e' piu' lungo di meta' schermo. */
  private fun richServices(portrait: Boolean) = services(
    imageSource = photoSource(bitmapFor(portrait)),
    backup = MediaBackupStates { flowOf(MediaBackupState.Safe(1_792_119_120_000)) },
    placeMap = richPlaceMap(fixture),
    people = FakeViewerPeople(
      listOf(
        ViewerFaceChip.Named(ViewerFaceRef("anna"), PersonId.random(), "Anna"),
        ViewerFaceChip.Named(ViewerFaceRef("marco"), PersonId.random(), "Marco"),
        ViewerFaceChip.Unnamed(ViewerFaceRef("sconosciuto"), null),
      ),
    ),
    mediaAlbums = ShotAlbums,
  )

  /** Gli album della foto di prova: due voci, la seconda un album condiviso della famiglia (con il suo segno). */
  private object ShotAlbums : ViewerMediaAlbums {
    override fun albumsOf(media: MediaId): Flow<List<ViewerAlbumEntry>> =
      flowOf(listOf(ViewerAlbumEntry(key = "a", title = "Vacanze a Oslo", count = 42), ViewerAlbumEntry(key = "b", title = "Famiglia", count = 310, shared = true)))

    override fun open(album: ViewerAlbumEntry) = Unit
  }

  /**
   * CU-13: tutti i dati, visti: sul telefono il pannello cresce fin sotto la barra in alto (un trascinamento lento, senza lancio), e si vedono
   * la testa, le facce, la scheda dello scatto piena, la mini-mappa e gli album. Sul tablet la colonna.
   */
  private fun fullData(dark: Boolean) {
    showPhoto(PORTRAIT, dark, richServices(portrait = true))
    openPanel()
    if (!tablet) {
      pager().performTouchInput {
        down(Offset(centerX, height * 0.3f))
        moveBy(Offset(0f, -40f), delayMillis = 32)
        repeat(5) { moveBy(Offset(0f, -110f), delayMillis = 48) }
      }
      composeRule.mainClock.advanceTimeBy(300)
      composeRule.waitForIdle()
      pager().performTouchInput { up() }
      composeRule.waitForIdle()
      composeRule.mainClock.advanceTimeBy(SETTLE_MS)
      composeRule.waitForIdle()
    }
    // Poi le informazioni scorrono (lente, senza lancio) finche' «Persone» sta dove stava la data: in un fotogramma le facce, la scheda dello
    // scatto intera, la mini-mappa e gli album. La testa (data, ora, luogo, backup) e' negli altri screenshot.
    val dateTop = composeRule.onAllNodesWithText("Martedì 12 marzo 2024").onFirst().fetchSemanticsNode().boundsInRoot.top
    val peopleTop = composeRule.onAllNodesWithText("Persone").onFirst().fetchSemanticsNode().boundsInRoot.top
    val scroll = peopleTop - dateTop
    composeRule.onRoot().performTouchInput {
      val x = if (tablet) width - SIDE_TOUCH_INSET else centerX
      val distance = scroll + viewConfiguration.touchSlop
      down(Offset(x, height * 0.8f))
      repeat(SCROLL_STEPS) { moveBy(Offset(0f, -distance / SCROLL_STEPS), delayMillis = 48) }
    }
    composeRule.mainClock.advanceTimeBy(300)
    composeRule.waitForIdle()
    composeRule.onRoot().performTouchInput { up() }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    shoot("dati-completi", dark)
  }

  /** Pochi dati: uno screenshot (niente EXIF, niente posizione), solo sul telefono. */
  private fun fewData(dark: Boolean) {
    val details = object : MediaDetailsSource {
      override suspend fun details(id: MediaId): MediaDetails = MediaDetails(
        id = id,
        kind = MediaKind.PHOTO,
        fileName = "Screenshot_20240312_124100.png",
        folder = "Pictures/Screenshots/",
        sizeBytes = 412_000L,
        mime = "image/png",
        width = 1080,
        height = 2340,
        captureTime = sampleDetails(id).captureTime,
      )
    }
    // Uno screenshot lungo (1080 x 2340): sta intero in altezza, e a pannello aperto in alto se ne vede la meta' bassa.
    showPhoto(
      SCREENSHOT,
      dark,
      services(
        imageSource = photoSource(lightPhoto(portrait = true)),
        backup = MediaBackupStates { flowOf(MediaBackupState.NotOnNas(dev.pampa.mycloudgallery.core.backup.contract.NotOnNasReason.NOT_YET)) },
        exif = ExifDetails(),
        detailsSource = details,
      ),
    )
    openPanel()
    shoot("pochi-dati", dark)
  }

  private fun backupProblem(dark: Boolean) {
    showPhoto(PORTRAIT, dark, photoServices(portrait = true, backup = MediaBackupState.Problem(BackupProblemCode.VERIFY_FAILED)))
    openPanel()
    shoot("problema-di-backup", dark)
  }

  /** Il pannello lungo scorso: cresce oltre meta' schermo fino alla barra e porta su la foto; poi le informazioni scorrono dentro. */
  private fun longScrolled(dark: Boolean) {
    showPhoto(PORTRAIT, dark, richServices(portrait = true))
    openPanel()
    pager().performTouchInput {
      down(Offset(centerX, height * 0.35f))
      moveBy(Offset(0f, -40f), delayMillis = 16)
      moveBy(Offset(0f, -200f), delayMillis = 16)
      moveBy(Offset(0f, -200f), delayMillis = 16)
      up()
    }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    shoot("pannello-lungo-scorso", dark)
  }

  @Test fun fotoVerticaleAperto_chiaro() = portraitOpen(dark = false)

  @Test fun fotoVerticaleAperto_scuro() = portraitOpen(dark = true)

  @Test fun fotoOrizzontaleAperto_chiaro() = landscapeOpen(dark = false)

  @Test fun fotoOrizzontaleAperto_scuro() = landscapeOpen(dark = true)

  @Test fun video_chiaro() = video(dark = false)

  @Test fun video_scuro() = video(dark = true)

  @Test fun datiCompleti_chiaro() = fullData(dark = false)

  @Test fun datiCompleti_scuro() = fullData(dark = true)

  @Test fun pochiDati_chiaro() = fewData(dark = false)

  @Test fun pochiDati_scuro() = fewData(dark = true)

  @Test fun problemaDiBackup_chiaro() = backupProblem(dark = false)

  @Test fun problemaDiBackup_scuro() = backupProblem(dark = true)

  /** Solo del telefono: la colonna laterale non ha corsa. */
  @Test fun aMeta_chiaro() {
    assumeFalse(tablet)
    halfway(dark = false)
  }

  @Test fun aMeta_scuro() {
    assumeFalse(tablet)
    halfway(dark = true)
  }

  /** Solo del telefono: la colonna laterale scorre al suo interno, la foto accanto non si muove. */
  @Test fun pannelloLungoScorso_chiaro() {
    assumeFalse(tablet)
    longScrolled(dark = false)
  }

  @Test fun pannelloLungoScorso_scuro() {
    assumeFalse(tablet)
    longScrolled(dark = true)
  }

  private companion object {
    const val SETTLE_MS = 800L

    /** Lo scorrimento lento di "dati completi": a passi, ognuno dopo 48 ms (nessun lancio: si aspetta prima di alzare il dito). */
    const val SCROLL_STEPS = 8

    /** Dove si tocca la colonna del tablet: dentro, lontano dal bordo destro. */
    const val SIDE_TOUCH_INSET = 160f

    /** Una foto verticale 3:4. */
    const val PORTRAIT = 750
    const val PORTRAIT_W = 900
    const val PORTRAIT_H = 1200

    /** Uno screenshot del telefono, 1080 x 2340 (la bitmap di `lightPhoto(portrait = true)`). */
    const val SCREENSHOT = 462

    /** Una foto larga 16:9: sul telefono e' bassa meno di meta' schermo, e con il pannello aperto si ingrandisce a riempire la meta' alta. */
    const val WIDE = 1778
    const val WIDE_W = 1600
    const val WIDE_H = 900

    val fullExif = ExifDetails(
      make = "SAMSUNG",
      model = "Samsung Galaxy S25",
      lens = "Obiettivo principale",
      fNumber = 1.8,
      exposureTime = "1/250",
      iso = 50,
      focalLengthMm = 6.3,
      flashFired = false,
    )
  }
}

/** Il telefono dell'utente (Galaxy S25, SM-S931B): 360 x 780 dp. */
@Config(qualifiers = "it-w360dp-h780dp-xhdpi")
class ViewerH2ShotsPhone : ViewerH2Shots("telefono", tablet = false)

@Config(qualifiers = "it-w1280dp-h800dp-xhdpi")
class ViewerH2ShotsTablet : ViewerH2Shots("tablet", tablet = true)

/** Ogni foto della Libreria di prova con le proporzioni [milli] (larghezza / altezza x 1000): la geometria del pannello dipende da queste. */
internal fun aspectView(base: TimelineView, milli: Int): TimelineView {
  val snapshot = object : TimelineSnapshot by base.snapshot {
    override fun aspectMilliAt(i: Int): Int = milli
  }
  return object : TimelineView {
    override val snapshot: TimelineSnapshot = snapshot
    override val filter: LibraryFilter = base.filter
    override val size: Int = base.size

    override fun snapshotIndex(viewIndex: Int): Int = base.snapshotIndex(viewIndex)

    override fun viewIndexOf(id: MediaId): Int = base.viewIndexOf(id)
  }
}

/**
 * CU-16: il pannello con il testo di sistema ingrandito (1,3x) sul telefono dell'utente, chiaro e scuro, su una foto di sera: ISO 3200 e 1/1000 s. La
 * striscia dei valori non tronca le cifre: va su due righe da due. Prima di scattare, la prova guarda il testo come e' stato impaginato (nessun
 * valore tagliato o con i puntini).
 */
@Config(qualifiers = "it-w360dp-h780dp-xhdpi", fontScale = 1.3f)
class ViewerH2ShotsTestoGrande : ViewerTestBase() {

  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private val nightExif = ExifDetails(
    make = "SAMSUNG",
    model = "Samsung Galaxy S25",
    lens = "Obiettivo principale",
    fNumber = 1.8,
    exposureTime = "1/1000",
    iso = 3200,
    focalLengthMm = 6.3,
    flashFired = false,
  )

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  private fun largeText(dark: Boolean) {
    fixture.items.publish(aspectView(fixture.library.view(), 750))
    val services = services(
      imageSource = photoSource(scene(900, 1200)),
      backup = MediaBackupStates { flowOf(MediaBackupState.Safe(1_792_119_120_000)) },
      exif = nightExif,
    )
    show(request = fixture.request(startIndex = 4), services = services, dark = dark)
    composeRule.mainClock.advanceTimeBy(SETTLE)
    composeRule.waitForIdle()
    // Il pannello aperto e poi alto (lento, senza lancio): con il testo grande la scheda dello scatto sta piu' in basso.
    pager().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.3f) }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE)
    composeRule.waitForIdle()
    pager().performTouchInput {
      down(Offset(centerX, height * 0.3f))
      moveBy(Offset(0f, -40f), delayMillis = 32)
      repeat(5) { moveBy(Offset(0f, -110f), delayMillis = 48) }
    }
    composeRule.mainClock.advanceTimeBy(300)
    composeRule.waitForIdle()
    pager().performTouchInput { up() }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE)
    composeRule.waitForIdle()
    for (value in listOf("ISO 3200", "6,3 mm", "f/1,8", "1/1000 s")) {
      val node = composeRule.onNodeWithText(value, useUnmergedTree = true).fetchSemanticsNode()
      val layouts = mutableListOf<TextLayoutResult>()
      node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
      assertWithMessage("«$value» intero").that(layouts.single().hasVisualOverflow).isFalse()
    }
    composeRule.onRoot().captureRoboImage("../docs/screenshots/beta-grafica/H2/viewer-info-testo-grande-${if (dark) "scuro" else "chiaro"}-telefono.png")
  }

  @Test fun testoGrande_chiaro() = largeText(dark = false)

  @Test fun testoGrande_scuro() = largeText(dark = true)

  private companion object {
    const val SETTLE = 800L
  }
}

/** La foto chiara di `lightPhoto` (cielo, nuvole, prato) alla misura [width] x [height]: proporzioni scelte dal test. */
private fun scene(width: Int, height: Int): Bitmap {
  val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val w = width.toFloat()
  val h = height.toFloat()
  val sky = Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.62f, 0xFFFDFEFF.toInt(), 0xFFD6E6F3.toInt(), android.graphics.Shader.TileMode.CLAMP) }
  canvas.drawRect(0f, 0f, w, h * 0.62f, sky)
  val grass = Paint().apply { shader = android.graphics.LinearGradient(0f, h * 0.62f, 0f, h, 0xFFF1E7C4.toInt(), 0xFFC9D8A2.toInt(), android.graphics.Shader.TileMode.CLAMP) }
  canvas.drawRect(0f, h * 0.62f, w, h, grass)
  val cloud = Paint().apply { color = 0xFFFFFFFF.toInt() }
  canvas.drawOval(android.graphics.RectF(w * 0.10f, h * 0.08f, w * 0.52f, h * 0.22f), cloud)
  canvas.drawOval(android.graphics.RectF(w * 0.55f, h * 0.03f, w * 0.96f, h * 0.17f), cloud)
  val shadow = Paint().apply { color = 0xFFB9C7D4.toInt() }
  canvas.drawOval(android.graphics.RectF(w * 0.30f, h * 0.45f, w * 0.80f, h * 0.60f), shadow)
  // Un sentiero in basso: si vede dove finisce la foto (il bordo che resta attaccato al pannello).
  val path = Paint().apply { color = 0xFFB08F5A.toInt() }
  canvas.drawRect(android.graphics.RectF(w * 0.42f, h * 0.80f, w * 0.58f, h), path)
  return bitmap
}

/** La mini-mappa per ogni elemento della Libreria di prova: una carta piatta finta con il segno sul punto (MapSnapshotter non si disegna qui). */
private fun richPlaceMap(fixture: ViewerFixture): FakeViewerPlaceMap {
  val map = FakeViewerPlaceMap()
  val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888).also { image ->
    val canvas = Canvas(image)
    canvas.drawColor(android.graphics.Color.rgb(226, 222, 240))
    canvas.drawRect(0f, 200f, 360f, 320f, Paint().apply { color = android.graphics.Color.rgb(205, 196, 232) })
    canvas.drawCircle(320f, 160f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    canvas.drawCircle(320f, 160f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(153, 102, 204) })
  }
  for (i in 0 until fixture.library.count) map.locate(fixture.idAt(i), ViewerMapPlace.Located(bitmap))
  return map
}
