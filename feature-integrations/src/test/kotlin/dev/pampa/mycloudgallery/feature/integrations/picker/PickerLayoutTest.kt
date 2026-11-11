package dev.pampa.mycloudgallery.feature.integrations.picker

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentPolicy
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import dev.pampa.mycloudgallery.feature.integrations.share.ShareStop
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Il **test dei bordi** del selettore (D30; riga P10 del §4.4 e §4.3: bersagli da 48 dp, testo a 1,3x, niente controlli sovrapposti): sul
 * telefono e sul tablet, con il testo a 1x e a 1,3x, ogni controllo toccabile e' almeno 48 x 48 dp, sta dentro lo schermo e non ne tocca un altro.
 * In piu': con l'app bloccata non c'e' nessuna cella in composizione, e nessuna descrizione per TalkBack porta un nome di file o un id.
 */
abstract class PickerLayoutTests : PickerComposeBase() {

  private val library = PickerSamples.library()
  private val env = PickerSamples.env(library)

  private fun Rect.overlaps(other: Rect): Boolean = left < other.right - 1f && other.left < right - 1f && top < other.bottom - 1f && other.top < bottom - 1f

  private fun Rect.contains(other: Rect): Boolean = left <= other.left + 1f && top <= other.top + 1f && right >= other.right - 1f && bottom >= other.bottom - 1f

  private fun assertBounds(where: String) {
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val root = composeRule.onAllNodes(SemanticsMatcher("radice") { it.isRoot }).fetchSemanticsNodes().first().boundsInRoot
    val nodes = clickable()
    assertWithMessage("$where: ci sono cose da toccare").that(nodes).isNotEmpty()
    for (node in nodes) {
      val b = node.boundsInRoot
      assertWithMessage("$where: ${node.name()} $b alto almeno 48 dp").that(b.height).isAtLeast(min - 1f)
      assertWithMessage("$where: ${node.name()} $b largo almeno 48 dp").that(b.width).isAtLeast(min - 1f)
      assertWithMessage("$where: ${node.name()} $b dentro lo schermo $root").that(b.left >= root.left - 1f && b.right <= root.right + 1f).isTrue()
    }
    for (i in nodes.indices) {
      for (j in i + 1 until nodes.size) {
        val a = nodes[i].boundsInRoot
        val b = nodes[j].boundsInRoot
        if (a.contains(b) || b.contains(a)) continue
        assertWithMessage("$where: ${nodes[i].name()} $a e ${nodes[j].name()} $b si sovrappongono").that(a.overlaps(b)).isFalse()
      }
    }
  }

  private val multi = PickerSamples.request(multiple = true)

  private fun states(): List<Pair<String, PickerUi>> = listOf(
    "griglia" to PickerSamples.grid(library, multi),
    "griglia con tre scelti" to PickerSamples.grid(library, multi, selected = 3),
    "avviso NAS" to PickerSamples.grid(library, multi, selected = 2, notice = PickerNotice.NasUnreachable(NasUnreachableReason.OFFLINE)),
    "avviso troppi" to PickerSamples.grid(library, multi, selected = 2, notice = PickerNotice.TooManyNasOnly(120, 3L * 1024 * 1024 * 1024)),
    "avviso posizione" to PickerSamples.grid(library, multi, selected = 2, notice = PickerNotice.NoLocation(ShareStop.CANNOT_REMOVE_LOCATION, removedFromChoice = true)),
    "bloccata" to PickerUi(PickerScreenState.LOCKED),
    "sblocco non riuscito" to PickerUi(PickerScreenState.LOCKED, unlockFailed = true),
    "non pronta" to PickerUi(PickerScreenState.NOT_READY),
    "in consegna" to PickerUi(PickerScreenState.DELIVERING),
    "in consegna con avanzamento" to PickerUi(PickerScreenState.DELIVERING, progress = PickerProgress(done = 1, total = 3, bytesDone = 40, bytesTotal = 100)),
    "vuota" to PickerUi(PickerScreenState.GRID),
  )

  @Test
  fun ogniStatoRispettaIBordiAUnoEATreDecimi() {
    val ui = mutableStateOf(states().first().second)
    show { PickerScreen(ui = ui.value, request = multi, env = env, actions = PickerActions()) }
    for (fontScale in listOf(1f, LARGE_TEXT)) {
      setFontScale(fontScale)
      for ((name, state) in states()) {
        composeRule.runOnIdle { ui.value = state }
        composeRule.waitForIdle()
        // Niente da toccare, salvo "Annulla" mentre si preparano le copie senza posizione.
        if ((state.screen == PickerScreenState.DELIVERING && state.progress == null) || state.screen == PickerScreenState.CHECKING) continue
        assertBounds("$name ${fontScale}x")
      }
    }
  }

  @Test
  fun conUnaSolaScelta_CEIlTastoScegli_R8() {
    // R8: anche la scelta singola ha "Scegli" (un tocco sceglie, non consegna).
    val single = PickerSamples.request(multiple = false)
    show { PickerScreen(ui = PickerSamples.grid(library, single), request = single, env = env, actions = PickerActions()) }
    assertThat(composeRule.onAllNodes(hasText("Scegli")).fetchSemanticsNodes()).isNotEmpty()
    assertBounds("scelta singola")
  }

  @Test
  fun conLAppBloccataNonCEUnaSolaCellaInComposizione_E_X7() {
    show { PickerScreen(ui = PickerUi(PickerScreenState.LOCKED), request = multi, env = env, actions = PickerActions()) }
    composeRule.onNodeWithText("MyCloud Gallery è bloccata").assertExists()
    composeRule.onNodeWithText("Sblocca").assertExists()
    val names = composeRule.onAllNodes(SemanticsMatcher("qualunque") { true }).fetchSemanticsNodes().map { it.name() }
    assertThat(names.filter { it.startsWith("Foto") || it.startsWith("Video") && it != "Video" }).isEmpty()
    assertThat(composeRule.onAllNodes(hasText("scelti", substring = true)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun lePrimeRigheDannoUnNomeAOgniControlloSenzaDatiDelleCelle() {
    show { PickerScreen(ui = PickerSamples.grid(library, multi, selected = 2), request = multi, env = env, actions = PickerActions()) }
    val nodes = clickable()
    for (node in nodes) assertWithMessage("un controllo senza nome ${node.boundsInRoot}").that(node.name()).isNotEmpty()
    val names = nodes.map { it.name() }
    assertThat(names).contains("Annulla")
    val cellNames = names.filter { it.startsWith("Foto") || it.startsWith("Video") }
    assertThat(cellNames).isNotEmpty()
    for (name in cellNames) {
      assertThat(name).doesNotContain("IMG_")
      assertThat(name).doesNotMatch(".*[0-9a-f]{8}-[0-9a-f]{4}.*")
    }
  }

  @Test
  fun iTitoliSonoIntestazioniPerTalkBack() {
    show { PickerScreen(ui = PickerSamples.grid(library, multi), request = multi, env = env, actions = PickerActions()) }
    val headings = composeRule.onAllNodes(SemanticsMatcher("intestazione") { it.config.contains(SemanticsProperties.Heading) }).fetchSemanticsNodes()
    val titles = headings.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
    assertThat(titles.joinToString(" ")).contains("Scegli da MyCloud Gallery")
  }

  /** "Rimuovi la posizione" acceso: mentre si preparano le copie la schermata dice cosa succede, a che punto e' e si puo' annullare. */
  @Test
  fun mentreSiTogliaLaPosizioneSiVedeLAvanzamentoESiPuoAnnullare() {
    var cancelled = 0
    val ui = PickerUi(PickerScreenState.DELIVERING, progress = PickerProgress(done = 1, total = 3, bytesDone = 40, bytesTotal = 100))
    show { PickerScreen(ui = ui, request = multi, env = env, actions = PickerActions(onCancelDelivery = { cancelled++ })) }

    composeRule.onNodeWithText("Tolgo la posizione dai file").assertExists()
    composeRule.onNodeWithText("2 di 3").assertExists()
    composeRule.onNodeWithText("Preparo copie senza posizione da consegnare: i tuoi originali non cambiano.").assertExists()
    assertBounds("in consegna con avanzamento")
    // L'"Annulla" della preparazione (quello in alto, con l'icona, chiude il selettore): un tocco ferma solo la preparazione.
    composeRule.onNodeWithText("Annulla").performClick()
    assertThat(cancelled).isEqualTo(1)
  }

  @Test
  fun lAvvisoDellaPosizioneDiceCheNienteEStatoConsegnato() {
    val removed = PickerSamples.grid(library, multi, selected = 2, notice = PickerNotice.NoLocation(ShareStop.CANNOT_REMOVE_LOCATION, removedFromChoice = true))
    show { PickerScreen(ui = removed, request = multi, env = env, actions = PickerActions()) }
    composeRule.onNodeWithText("Questo file non si può condividere senza posizione", substring = true).assertExists()
    composeRule.onNodeWithText("non ho consegnato niente", substring = true).assertExists()
  }

  @Test
  fun ilTettoDeiSoloNasEnellAvvisoConLeCifreGiuste() {
    val ui = PickerSamples.grid(library, multi, selected = 1, notice = PickerNotice.TooManyNasOnly(120, 3L * 1024 * 1024 * 1024))
    show { PickerScreen(ui = ui, request = multi, env = env, actions = PickerActions()) }
    composeRule.onNodeWithText("Hai scelto 120 foto solo sul NAS", substring = true).assertExists()
    assertThat(DocumentPolicy.MAX_NAS_ONLY_ITEMS).isEqualTo(100)
  }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h2600dp-xhdpi")
class PickerPhoneLayoutTest : PickerLayoutTests()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h1600dp-xhdpi")
class PickerTabletLayoutTest : PickerLayoutTests()
