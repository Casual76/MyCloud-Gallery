package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlot
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTabBarSlotVisibility
import dev.pampa.mycloudgallery.core.designsystem.chrome.LocalGalleryTabBarSlot
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I1 (fase I, 11/11): la scheda **Raccolte come una galleria** ("come Samsung"), per l'utente. Come in produzione: la scheda dentro il posto della
 * pillola, il fondo della pagina, lo stato in cima, le sezioni nell'ordine predefinito e con la forma che `:app` da' alle voci
 * ([CollectionsSectionSamples.production]), le copertine, i volti e i luoghi dalla stessa strada della produzione ([CollectionCovers]).
 *
 * La **mappa** dei Luoghi: MapLibre non si disegna in Robolectric. Le scene normali sono senza la porta della mappa (resta il **mosaico** delle foto
 * dei luoghi, cio' che si vede senza mappa); la scena `mappa` passa una carta **disegnata a mano** ([FakeMap]: mare, terra, nessun nome) per mostrare
 * dove stanno le foto appuntate. Sul telefono la carta e' quella vera (lo stile solo offline della mini-mappa).
 *
 * `gradlew :feature-library:recordRoborazziDebug --tests ...I1RaccoltePhoneShots` (e `...I1RaccolteTabletShots`) scrive in
 * `docs/screenshots/beta-grafica/I1/`, con "dopo" nel nome (i "prima" sono quelli registrati sul codice di partenza).
 */
abstract class I1RaccolteShotBase(private val dark: Boolean, private val device: String) : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val photos = world.visibleIds.toList()

  /** Cinque volti di prova (il ritaglio e' un'immagine sintetica: in produzione e' il volto vero, ritagliato da feature-people). */
  private val faces = (1..5).map { n -> FaceLocator(ContentKey("%064x".format(n)), 0, FaceBox(10 * n, 10, 10 * n + 80, 90)) }
  private val crops = CollectionFaceCrops { face, px -> CollectionFaceCrop(SyntheticPixels.bitmap(face.box.x0.toLong() * 7919L, px, px), setOf(photos[face.box.x0 / 10])) }
  private val covers = CollectionCovers(fakeThumbnailLoader(), world.source.items(LibraryFilter.ALL), crops, Dispatchers.Unconfined)
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

  @After
  fun stop() {
    scope.cancel()
  }

  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, fontScale: Float? = null, after: () -> Unit = {}, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale, content = content)
    composeRule.waitForIdle()
    after()
    composeRule.waitForIdle()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/raccolte-$name-dopo-$look-$device$suffix.png")
  }

  /** Lo stato come lo compone il **controllore vero** con le sezioni come le consegna `:app` in produzione. */
  private fun controller(fresh: Boolean): CollectionsLayoutController {
    val resources = ApplicationProvider.getApplicationContext<Application>().resources
    val providers = CollectionsSectionSamples.production(resources, photos, faces, fresh)
    return CollectionsLayoutController(true, providers, MemoryCollectionsLayoutStore(), scope, SharingStarted.Eagerly)
  }

  @Composable
  private fun Tab(fresh: Boolean, map: CollectionsMapPreview? = null) {
    val c = remember { controller(fresh) }
    val inputs by c.inputs.collectAsState()
    val state = remember(inputs) { CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed) }
    val slot = remember { GalleryTabBarSlot() }
    var backdrop by remember { mutableStateOf<GlassBackdropState?>(null) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      ModalTestHost {
        CompositionLocalProvider(LocalGalleryTabBarSlot provides slot, LocalCollectionCovers provides covers, LocalCollectionsMapPreview provides map) {
          CollectionsScreen(
            state = state,
            contentPadding = PaddingValues(bottom = GalleryTabBarDefaults.ContentInset),
            backdropOut = { backdrop = it },
            onOpenCollection = {},
            onOpenSettings = {},
            statusHeader = { ProductionNetworkHeader() },
            detail = { kind -> Text(kind.code) },
            entryDetail = { e -> Text(e.title) },
          )
        }
        GalleryTabBarSlotVisibility(visible = !slot.taken, modifier = Modifier.align(Alignment.BottomCenter)) {
          GalleryTabBar(selected = GalleryTab.Collections, onSelect = {}, backdrop = backdrop, onOpenSearch = {})
        }
      }
    }
  }

  /** La sezione [code] in cima, subito sotto la barra (la colonna la porta li' per chiave: ogni sezione e' un elemento solo). */
  private fun scrollTo(code: String) {
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToKey("section-$code")
    composeRule.waitForIdle()
  }

  @Test
  fun inCima() = shoot("in-cima") { Tab(fresh = false) }

  @Test
  fun inCimaTestoGrande() = shoot("in-cima", fontScale = LARGE_TEXT) { Tab(fresh = false) }

  @Test
  fun ricordi() = shoot("ricordi", after = { scrollTo(CollectionSections.MEMORIES) }) { Tab(fresh = false) }

  /** I Luoghi senza la carta: il mosaico delle foto dei luoghi (cio' che si vede senza mappa) e le schede dei posti. */
  @Test
  fun luoghi() = shoot("luoghi", after = { scrollTo(CollectionSections.PLACES) }) { Tab(fresh = false) }

  /** I Luoghi con la carta: le foto dei luoghi appuntate dove sono (qui una carta disegnata a mano, sul telefono quella vera). */
  @Test
  fun mappa() = shoot("mappa", after = { scrollTo(CollectionSections.PLACES) }) { Tab(fresh = false, map = FakeMap) }

  @Test
  fun scorsa() = shoot("scorsa", after = { scrollTo(CollectionSections.NAS) }) { Tab(fresh = false) }

  @Test
  fun appenaInstallata() = shoot("appena-installata") { Tab(fresh = true) }

  @Test
  fun appenaInstallataScorsa() = shoot("appena-installata-scorsa", after = { scrollTo(CollectionSections.PLACES) }) { Tab(fresh = true) }

  /** Le persone, scorse di lato fino in fondo al carosello: la scheda "Tutte le persone" e una persona senza nome (solo il volto). */
  @Test
  fun personeInFondo() = shoot("persone-in-fondo", after = {
    scrollTo(CollectionSections.PEOPLE)
    // Il carosello delle persone: quello che scorre di lato e contiene Giulia (anche la colonna la contiene, ma scorre in verticale).
    composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and hasAnyDescendant(hasContentDescription("Giulia", substring = true)))
      // Per chiave, in un colpo solo: scorrendo un passo alla volta Giulia esce dal carosello e il selettore non lo ritrova piu'.
      .performScrollToKey("more:people/all")
    composeRule.waitForIdle()
  }) { Tab(fresh = false) }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/I1"
    const val LARGE_TEXT = 1.3f
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

/**
 * Una carta disegnata a mano per gli screenshot: mare, una penisola e un'isola, nessun nome e nessuna strada. I luoghi di prova cadono sulla terra, come
 * li metterebbe l'inquadratura vera ([CollectionMapPin] in frazioni della carta).
 */
internal object FakeMap : CollectionsMapPreview {
  override fun preview(places: List<Int>, widthDp: Int, heightDp: Int, density: Float, dark: Boolean) = flowOf(
    CollectionMapImage(draw((widthDp * density).toInt(), (heightDp * density).toInt(), dark), PINS.filter { it.placeId in places }, "Natural Earth · GeoNames"),
  )

  private val PINS = listOf(
    CollectionMapPin(3176959, 0.46f, 0.38f),
    CollectionMapPin(3173435, 0.36f, 0.20f),
    CollectionMapPin(3169070, 0.58f, 0.62f),
    CollectionMapPin(2267057, 0.12f, 0.70f),
  )

  private fun draw(w: Int, h: Int, dark: Boolean): Bitmap {
    val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(if (dark) 0xFF1B2733.toInt() else 0xFFCFE3F0.toInt())
    val land = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF2C3530.toInt() else 0xFFEDEBE3.toInt() }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (dark) 0xFF4A554D.toInt() else 0xFFC9C3B4.toInt()
      style = Paint.Style.STROKE
      strokeWidth = w / 300f
    }
    val peninsula = Path().apply {
      moveTo(w * 0.18f, 0f)
      lineTo(w * 0.62f, 0f)
      cubicTo(w * 0.58f, h * 0.22f, w * 0.52f, h * 0.30f, w * 0.60f, h * 0.48f)
      cubicTo(w * 0.70f, h * 0.66f, w * 0.80f, h * 0.78f, w * 0.78f, h * 0.98f)
      lineTo(w * 0.70f, h.toFloat())
      cubicTo(w * 0.62f, h * 0.80f, w * 0.48f, h * 0.62f, w * 0.40f, h * 0.48f)
      cubicTo(w * 0.32f, h * 0.36f, w * 0.20f, h * 0.24f, w * 0.18f, 0f)
      close()
    }
    val west = Path().apply {
      moveTo(0f, h * 0.40f)
      cubicTo(w * 0.10f, h * 0.44f, w * 0.18f, h * 0.60f, w * 0.16f, h.toFloat())
      lineTo(0f, h.toFloat())
      close()
    }
    canvas.drawPath(peninsula, land)
    canvas.drawPath(west, land)
    canvas.drawOval(w * 0.80f, h * 0.30f, w * 0.90f, h * 0.48f, land)
    canvas.drawPath(peninsula, border)
    return bitmap
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE_QUALIFIERS)
class I1RaccoltePhoneShots(dark: Boolean) : I1RaccolteShotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class I1RaccolteTabletShots(dark: Boolean) : I1RaccolteShotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
