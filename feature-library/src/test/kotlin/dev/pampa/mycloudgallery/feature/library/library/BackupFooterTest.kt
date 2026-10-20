package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupWait
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.showThemed
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La terza voce del piede, il backup (Fase 5A, `docs/fase5-progetto.md` §1.5, §2.6, D16; G2, `docs/grafica-progetto.md` §12.5): ogni
 * caso di `LibraryBackupStatus` dice la sua frase breve e il suo dettaglio, toccarla apre la pagina Backup, TalkBack la legge intera,
 * e (il **test dei bordi del piede**) su telefono, a 1,3x e sul tablet le tre voci non si toccano e ogni capsula e' un bersaglio da
 * almeno 48 dp. I contrasti dei toni sono in `GalleryFooterStatusTest` (core-designsystem): qui i toni sono quelli e basta.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class BackupFooterTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  // setContent si chiama una volta sola per prova: gli stati cambiano dallo stato, non rimostrando la scena.
  private val backupState = mutableStateOf<LibraryBackupStatus>(LibraryBackupStatus.Idle)
  private val nasState = mutableStateOf<LibraryNasStatus>(LibraryNasStatus.Idle)
  private val fontScaleState = mutableFloatStateOf(1f)
  private var backupClicks = 0
  private var started = false

  private fun show(backup: LibraryBackupStatus, nas: LibraryNasStatus = LibraryNasStatus.Idle, fontScale: Float = 1f) {
    if (!started) {
      started = true
      backupState.value = backup
      nasState.value = nas
      fontScaleState.floatValue = fontScale
      composeRule.showThemed {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScaleState.floatValue)) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
              LibraryStatusBlock(
                nas = nasState.value,
                statusFooter = { Text("Rete non riconosciuta") },
                onNasClick = {},
                backup = backupState.value,
                onBackupClick = { backupClicks++ },
              )
            }
          }
        }
      }
    } else {
      composeRule.runOnIdle {
        backupState.value = backup
        nasState.value = nas
        fontScaleState.floatValue = fontScale
      }
      composeRule.waitForIdle()
    }
  }

  @Test
  fun senzaBackupIlPiedeNonDiceNiente() {
    show(LibraryBackupStatus.Idle)
    composeRule.onNodeWithText("Rete non riconosciuta").assertExists()
    assertThat(composeRule.onAllNodesWithText("backup", substring = true, ignoreCase = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun tuttoAlSicuroDiceQuandoE() {
    show(LibraryBackupStatus.AllSafe(atNasMillis = null))
    composeRule.onNodeWithText("Tutto al sicuro").assertExists()
  }

  @Test
  fun inCodaDiceQuantiEPerche() {
    show(LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING))
    composeRule.onNodeWithText("312 in coda").assertExists()
    composeRule.onNodeWithText("Attesa carica").assertExists()
  }

  @Test
  fun ognunaDelleAttesePossibiliHaLaSuaFrase() {
    val phrases = mapOf(
      LibraryBackupWait.CHARGING to "Attesa carica",
      LibraryBackupWait.WIFI to "Attesa Wi-Fi",
      LibraryBackupWait.HOME_WIFI to "Attesa Wi-Fi di casa",
      LibraryBackupWait.NIGHT_WINDOW to "Attesa della notte",
      LibraryBackupWait.BATTERY to "Attesa batteria",
      LibraryBackupWait.NAS to "Attesa NAS",
      LibraryBackupWait.NEXT_RUN to "Al prossimo giro",
      LibraryBackupWait.NAS_BUSY to "File occupato sul NAS, riprovo tra poco",
      LibraryBackupWait.OTHER to "Attesa delle regole",
    )
    assertThat(phrases.keys).containsExactlyElementsIn(LibraryBackupWait.entries)
    for ((wait, phrase) in phrases) {
      show(LibraryBackupStatus.Queued(items = 5, wait = wait))
      composeRule.onNodeWithText(phrase).assertExists()
    }
  }

  @Test
  fun ilPrimoBackupDiceADoveSiESCrivePoiIlTempo() {
    show(LibraryBackupStatus.FirstBackup(done = 1_240, total = 6_658, etaMillis = 80 * 60_000L))
    composeRule.onNodeWithText("Primo backup: 1.240 di 6.658").assertExists()
    composeRule.onNodeWithText("circa 1 h 20 min").assertExists()
  }

  @Test
  fun unGiroNormaleDiceQuantiNeHaFatti() {
    show(LibraryBackupStatus.Running(done = 12, total = 40, etaMillis = null))
    composeRule.onNodeWithText("Backup in corso: 12 di 40").assertExists()
  }

  @Test
  fun ilConfrontoDiceCosaStaFacendo() {
    show(LibraryBackupStatus.Comparing(pending = null))
    composeRule.onNodeWithText("Sto cercando cosa c’è già sul NAS…").assertExists()
    show(LibraryBackupStatus.Comparing(pending = 120))
    composeRule.onNodeWithText("Sto confrontando 120 foto con le copie sul NAS…").assertExists()
  }

  @Test
  fun inProvaDiceCheSiScriveSoloNelleShareDiProva() {
    show(LibraryBackupStatus.TestOnly)
    composeRule.onNodeWithText("Backup in prova").assertExists()
    composeRule.onNodeWithText("Si scrive solo nelle share di prova.").assertExists()
  }

  @Test
  fun unProblemaDiceLaFraseBreveECosaFare() {
    show(LibraryBackupStatus.Problem(code = "quota", count = 0))
    composeRule.onNodeWithText("Quota esaurita").assertExists()
    composeRule.onNodeWithText("Chiedi all’admin del NAS.").assertExists()
  }

  @Test
  fun unProblemaDiUnMediaDiceQuanti() {
    show(LibraryBackupStatus.Problem(code = "verify_failed", count = 3))
    composeRule.onNodeWithText("3 copie non integre").assertExists()
    show(LibraryBackupStatus.Problem(code = "lost_before_backup", count = 1))
    composeRule.onNodeWithText("1 cancellato prima del backup").assertExists()
  }

  /**
   * IN-7 (correzioni 5B): i file che un'altra app ha condiviso e che non si sono salvati sul NAS lo dicono nel piede (la frase breve con il numero e cosa
   * fare), e il tocco porta alla pagina Backup, dove sta "Ricevuti in coda" con "Riprova" e "Scarta".
   */
  @Test
  fun iFileRicevutiNonSalvatiLoDiconoEIlToccoPortaAllaPagina() {
    show(LibraryBackupStatus.Problem(code = "incoming_failed", count = 2))
    composeRule.onNodeWithText("2 file ricevuti non salvati").assertExists()
    composeRule.onNodeWithText("Riprova o scartali da Backup.").assertExists()
    composeRule.onNodeWithText("2 file ricevuti non salvati").performClick()
    assertThat(backupClicks).isEqualTo(1)
    show(LibraryBackupStatus.Problem(code = "incoming_failed", count = 1))
    composeRule.onNodeWithText("1 file ricevuto non salvato").assertExists()
    // Senza il numero (un conto che non si e' letto) la frase breve resta chiara.
    show(LibraryBackupStatus.Problem(code = "incoming_failed", count = 0))
    composeRule.onNodeWithText("File ricevuti non salvati").assertExists()
  }

  @Test
  fun unCodiceCheNonConoscePortaComunqueAllaPagina() {
    show(LibraryBackupStatus.Problem(code = "un_problema_futuro", count = 0))
    composeRule.onNodeWithText("Problema con il backup").assertExists().performClick()
    assertThat(backupClicks).isEqualTo(1)
  }

  @Test
  fun toccareLaCapsulaApreLaPaginaBackup() {
    show(LibraryBackupStatus.AllSafe(null))
    composeRule.onNodeWithText("Tutto al sicuro").performClick()
    assertThat(backupClicks).isEqualTo(1)
  }

  @Test
  fun tuttiEDueGliStatiConvivonoConLaRete() {
    show(
      backup = LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING),
      nas = LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L),
    )
    composeRule.onNodeWithText("Rete non riconosciuta").assertExists()
    composeRule.onNodeWithText("Lavori fermi: cambiamenti anomali").assertExists()
    composeRule.onNodeWithText("312 in coda").assertExists()
  }

  @Test
  fun talkBackLeggeLaCapsulaEIlDettaglioInsieme() {
    show(LibraryBackupStatus.Problem(code = "quota", count = 0))
    val spoken = composeRule.onAllNodes(SemanticsMatcher("capsula") { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }, useUnmergedTree = false)
      .fetchSemanticsNodes()
      .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    assertThat(spoken).contains("Quota esaurita. Chiedi all’admin del NAS.")
  }

  // --- I bordi del piede ------------------------------------------------------------------------------------------------------

  /** Le capsule toccabili del piede e il resto dei suoi nodi, per i test dei bordi. */
  private fun clickable(): List<SemanticsNode> =
    composeRule.onAllNodes(SemanticsMatcher("toccabile") { it.config.contains(SemanticsActions.OnClick) }).fetchSemanticsNodes()

  private fun Rect.overlaps(other: Rect): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom

  /** Le storie piu' lunghe e piu' corte: l'etichetta piu' lunga, l'avanzamento, il problema con il numero. */
  private val boundaryCases: List<Pair<String, LibraryBackupStatus>> = listOf(
    "tutto al sicuro" to LibraryBackupStatus.AllSafe(null),
    "in coda" to LibraryBackupStatus.Queued(items = 6_658, wait = LibraryBackupWait.HOME_WIFI),
    "primo backup" to LibraryBackupStatus.FirstBackup(done = 1_240, total = 6_658, etaMillis = 80 * 60_000L),
    "confronto" to LibraryBackupStatus.Comparing(pending = 120),
    "problema lungo" to LibraryBackupStatus.Problem(code = "phone_storage_low", count = 0),
    "problema con il numero" to LibraryBackupStatus.Problem(code = "verify_failed", count = 12_345),
    "in prova" to LibraryBackupStatus.TestOnly,
  )

  private fun assertBounds(where: String, widthDp: Float) {
    val density = composeRule.density
    val min = with(density) { GalleryDimens.MinTouchTarget.toPx() }
    val root = composeRule.onAllNodes(SemanticsMatcher("radice") { it.isRoot }).fetchSemanticsNodes().first().boundsInRoot
    val buttons = clickable()
    assertWithMessage("$where: le capsule toccabili (NAS e backup)").that(buttons.size).isAtLeast(2)
    for (button in buttons) {
      val b = button.boundsInRoot
      assertWithMessage("$where: capsula $b alta almeno 48 dp").that(b.height).isAtLeast(min - 1f)
      assertWithMessage("$where: capsula $b larga almeno 48 dp").that(b.width).isAtLeast(min - 1f)
      assertWithMessage("$where: capsula $b dentro lo schermo ($root)").that(b.left >= root.left - 1f && b.right <= root.right + 1f).isTrue()
    }
    // Una sotto l'altra, mai sovrapposte: la voce del NAS sopra, quella del backup sotto.
    val ordered = buttons.sortedBy { it.boundsInRoot.top }
    for (i in 0 until ordered.size - 1) {
      assertWithMessage("$where: ${ordered[i].boundsInRoot} e ${ordered[i + 1].boundsInRoot}")
        .that(ordered[i].boundsInRoot.overlaps(ordered[i + 1].boundsInRoot)).isFalse()
    }
    assertThat(widthDp).isGreaterThan(0f)
  }

  @Test
  fun iBordiDelPiedeSuTelefonoAUnoEATreDecimi() {
    for (fontScale in listOf(1f, 1.3f)) {
      for ((name, status) in boundaryCases) {
        show(status, nas = LibraryNasStatus.Frozen(FreezeReason.CHANGED, sinceNasMillis = 0L), fontScale = fontScale)
        assertBounds("telefono ${fontScale}x, $name", widthDp = 412f)
      }
    }
  }
}

/** Lo stesso sul tablet (1280 dp, orizzontale): nessun bordo cambia. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h800dp-land-xhdpi")
class BackupFooterTabletTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  @Test
  fun iBordiDelPiedeSulTablet() {
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val cases = listOf<LibraryBackupStatus>(
      LibraryBackupStatus.FirstBackup(done = 1, total = 6_658, etaMillis = null),
      LibraryBackupStatus.Problem(code = "frozen", count = 0),
    )
    val backup = mutableStateOf(cases.first())
    composeRule.showThemed {
      LibraryStatusBlock(
        nas = LibraryNasStatus.Frozen(FreezeReason.CHANGED, sinceNasMillis = 0L),
        statusFooter = { Text("Rete non riconosciuta") },
        onNasClick = {},
        backup = backup.value,
        onBackupClick = {},
      )
    }
    for (case in cases) {
      composeRule.runOnIdle { backup.value = case }
      composeRule.waitForIdle()
      val buttons = composeRule.onAllNodes(SemanticsMatcher("toccabile") { it.config.contains(SemanticsActions.OnClick) }).fetchSemanticsNodes()
      assertThat(buttons.size).isAtLeast(2)
      for (button in buttons) {
        assertWithMessage("capsula ${button.boundsInRoot}").that(button.boundsInRoot.height).isAtLeast(min - 1f)
      }
    }
  }
}
