package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import dev.pampa.mycloudgallery.core.model.MediaId
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
 * Lo stato del backup (Fase 5A, `docs/fase5-progetto.md` §1.5, §2.6, D16; G2 §12.5) e, dalla beta (B2), **il pop-up della nuvola**: ogni
 * caso di `LibraryBackupStatus` dice la sua frase breve e il suo dettaglio (le stesse del piede di prima); un problema ha il tasto che
 * porta dove si risolve ("Apri Backup"), un lavoro si tocca e porta alla pagina Backup; senza problemi il pop-up mostra lo spazio del
 * NAS, l'ultima sincronizzazione e "Libera spazio"; con un problema no. E (il **test dei bordi del pop-up**) su telefono, a 1,3x e sul
 * tablet i bersagli sono di almeno 48 dp, dentro lo schermo e non si toccano. I contrasti dei toni sono in `GalleryFooterStatusTest`.
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
  private val networkState = mutableStateOf<LibraryNetworkStatus?>(null)
  private val fontScaleState = mutableFloatStateOf(1f)
  private var backupClicks = 0
  private var networkClicks = 0
  private var closes = 0
  private var started = false
  private val commands = SpaceCommands()

  /** I comandi della prova: lo spazio del NAS (3,6 TB, 1,2 usati), "Libera spazio" disponibile e contato. */
  private class SpaceCommands(var space: LibraryNasSpace? = LibraryNasSpace.Ready(usedBytes = 1_250_000_000_000L, totalBytes = 3_600_000_000_000L)) : LibraryCommands {
    val freed = mutableListOf<Set<MediaId>>()
    override val canFreeSpace: Boolean get() = true
    override fun freeSpace(ids: Set<MediaId>) {
      freed += ids
    }
    override suspend fun nasSpace(): LibraryNasSpace? = space
  }

  private fun show(
    backup: LibraryBackupStatus,
    nas: LibraryNasStatus = LibraryNasStatus.Idle,
    network: LibraryNetworkStatus? = null,
    fontScale: Float = 1f,
    lastSync: Long? = null,
  ) {
    if (!started) {
      started = true
      backupState.value = backup
      nasState.value = nas
      networkState.value = network
      fontScaleState.floatValue = fontScale
      composeRule.showThemed {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScaleState.floatValue)) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            val cloud = libraryCloud(networkState.value, nasState.value, backupState.value)
            if (cloud != null) {
              LibraryCloudPanel(
                cloud = cloud,
                lastSyncNasMillis = lastSync,
                commands = commands,
                actions = LibraryCloudActions(onNetwork = { networkClicks++ }, onNas = {}, onBackup = { backupClicks++ }),
                close = { closes++ },
              )
            }
          }
        }
      }
    } else {
      composeRule.runOnIdle {
        backupState.value = backup
        nasState.value = nas
        networkState.value = network
        fontScaleState.floatValue = fontScale
      }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun senzaBackupNeNasLaNuvolaNonDiceNiente() {
    assertThat(libraryCloud(null, LibraryNasStatus.Idle, LibraryBackupStatus.Idle)).isNull()
  }

  @Test
  fun tuttoAlSicuroEIlTitoloDelPopUp() {
    show(LibraryBackupStatus.AllSafe(atNasMillis = null))
    composeRule.onNodeWithText("Tutto al sicuro").assertExists()
  }

  @Test
  fun inCodaDiceQuantiEPerche() {
    show(LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING))
    composeRule.onNodeWithText("312 in coda").assertExists()
    // Fase I (BF-8): la formula comune del motivo (la stessa della pagina Backup e della Diagnostica). Giro 2 (BF2-3): «In attesa» una volta sola (il titolo).
    composeRule.onNodeWithText("Serve la corrente").assertExists()
    composeRule.onNodeWithText("In attesa").assertExists()
    assertThat(composeRule.onAllNodesWithText("In attesa della", substring = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ognunaDelleAttesePossibiliHaLaSuaFrase() {
    val phrases = mapOf(
      // Fase I (BF-8): la formula comune per motivo (`gallery_wait_*`), senza promesse di ripartenza.
      LibraryBackupWait.CHARGING to "Serve la corrente",
      LibraryBackupWait.WIFI to "Serve il Wi-Fi",
      LibraryBackupWait.HOME_WIFI to "Serve il Wi-Fi di casa",
      LibraryBackupWait.NIGHT_WINDOW to "Fuori dalla fascia oraria",
      LibraryBackupWait.BATTERY to "Batteria sotto la soglia",
      // FAB-4: il risparmio energetico non e' la batteria bassa e ha la sua frase.
      LibraryBackupWait.POWER_SAVER to "Risparmio energetico attivo",
      LibraryBackupWait.THERMAL to "Il telefono è troppo caldo",
      LibraryBackupWait.DATA_CAP to "Tetto dati del mese raggiunto",
      LibraryBackupWait.NAS to "NAS spento o non raggiungibile",
      LibraryBackupWait.NEXT_RUN to "Al prossimo giro",
      LibraryBackupWait.NAS_BUSY to "File occupato sul NAS, riprovo tra poco.",
      // Giro 2 (BF2-11): niente gergo.
      LibraryBackupWait.OTHER to "Il motivo è nella Diagnostica",
      // T-1: una coda che l'ultimo giro non ha trovato sul telefono non promette un giro.
      LibraryBackupWait.NOT_ON_PHONE to "Niente da caricare adesso",
      // BF-6: il primo backup pronto e' «da avviare», non «in coda al prossimo giro».
      LibraryBackupWait.FIRST_BACKUP to "Primo backup da avviare",
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
    // I4: l'anello con la percentuale e il conto; la frase intera la dice TalkBack.
    composeRule.onNodeWithText("1.240 / 6.658", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("18%", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("circa 1 h 20 min", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithContentDescription("Primo backup: 1.240 di 6.658. circa 1 h 20 min").assertExists()
  }

  /** Giro 2 (BF2-4): chiesto adesso e accettato, il piede dice «Sto partendo» come la pagina, mai «Primo backup: 0 di N». */
  @Test
  fun dopoInizia_OraIlPiedeDiceCheStaPartendo() {
    show(LibraryBackupStatus.FirstBackup(done = 0, total = 7_105, etaMillis = null, starting = true))
    // Come le altre voci di un lavoro in corso: l'anello nella capsula, la frase intera la dice TalkBack.
    composeRule.onNodeWithContentDescription("Sto partendo", substring = true).assertExists()
    assertThat(composeRule.onAllNodesWithText("0 / 7.105", useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    assertThat(composeRule.onAllNodesWithText("0 di 7.105", substring = true).fetchSemanticsNodes()).isEmpty()
    show(LibraryBackupStatus.Running(done = 0, total = 40, etaMillis = null, starting = true))
    composeRule.onNodeWithContentDescription("Sto partendo", substring = true).assertExists()
    assertThat(composeRule.onAllNodesWithText("0 / 40", useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun unGiroNormaleDiceQuantiNeHaFattiESiTocca() {
    show(LibraryBackupStatus.Running(done = 12, total = 40, etaMillis = null))
    composeRule.onNodeWithText("12 / 40", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithContentDescription("Backup in corso: 12 di 40", substring = true).assertExists().performClick()
    assertThat(backupClicks).isEqualTo(1)
    assertThat(closes).isEqualTo(1)
  }

  @Test
  fun ilConfrontoDiceCosaStaFacendo() {
    // Fase I (BF-7): la ricerca si chiama ricerca, come nella pagina; non ancora partita lo dice, mentre gira dice i file guardati.
    show(LibraryBackupStatus.Comparing())
    composeRule.onNodeWithText("Cerco cosa c’è già sul NAS").assertExists()
    composeRule.onNodeWithText("Inizia fra poco.").assertExists()
    show(LibraryBackupStatus.Comparing(seen = 1_234, total = 5_559))
    composeRule.onNodeWithText("1.234 di 5.559 file guardati").assertExists()
  }

  /** Fase I (12/11): la ricerca ferma per una regola dice quale, non «sto cercando». */
  @Test
  fun laRicercaFermaDiceIlMotivoVero() {
    show(LibraryBackupStatus.Comparing(wait = LibraryBackupWait.THERMAL))
    composeRule.onNodeWithText("Ricerca sul NAS in pausa").assertExists()
    composeRule.onNodeWithText("Il telefono è troppo caldo").assertExists()
    assertThat(composeRule.onAllNodesWithText("Cerco cosa c’è già sul NAS").fetchSemanticsNodes()).isEmpty()
    // BF-4: un motivo che il backup non nomina: la pausa e la Diagnostica.
    show(LibraryBackupStatus.Comparing(wait = LibraryBackupWait.OTHER))
    composeRule.onNodeWithText("Il motivo è nella Diagnostica.").assertExists()
  }

  @Test
  fun inProvaDiceCheSiScriveSoloNelleShareDiProva() {
    show(LibraryBackupStatus.TestOnly)
    // I4: come nella pagina Backup, «In pausa · Il backup vero non e' ancora acceso», detto una volta (nel titolo, il perche' dietro la «i»).
    composeRule.onNodeWithTag(PauseTitleTag).assertExists()
    composeRule.onNodeWithContentDescription("In pausa. Il backup vero non è ancora acceso").assertExists()
    assertThat(composeRule.onAllNodesWithText("Il backup vero non è ancora acceso").fetchSemanticsNodes()).isEmpty()
    composeRule.onNodeWithTag(PauseTitleTag).performClick()
    composeRule.onNodeWithText("Il backup vero non è ancora acceso").assertExists()
    assertThat(composeRule.onAllNodesWithText("Backup in prova").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun unProblemaDiceLaFraseBreveECosaFareEIlTastoPortaAlBackup() {
    show(LibraryBackupStatus.Problem(code = "quota", count = 0))
    composeRule.onNodeWithText("Quota esaurita").assertExists()
    // I4: la frase che spiega sta dietro il tocco della capsula.
    assertThat(composeRule.onAllNodesWithText("Chiedi all’admin del NAS.").fetchSemanticsNodes()).isEmpty()
    composeRule.onNodeWithText("Quota esaurita").performClick()
    composeRule.onNodeWithText("Chiedi all’admin del NAS.").assertExists()
    composeRule.onNodeWithText("Apri Backup").performClick()
    assertThat(backupClicks).isEqualTo(1)
    assertThat(closes).isEqualTo(1)
  }

  @Test
  fun unProblemaDiUnMediaDiceQuanti() {
    show(LibraryBackupStatus.Problem(code = "verify_failed", count = 3))
    composeRule.onNodeWithText("3 copie non integre").assertExists()
    show(LibraryBackupStatus.Problem(code = "lost_before_backup", count = 1))
    composeRule.onNodeWithText("1 cancellato prima del backup").assertExists()
  }

  /** IN-7 (correzioni 5B): i file ricevuti non salvati lo dicono, con il numero, e il tasto porta alla pagina Backup. */
  @Test
  fun iFileRicevutiNonSalvatiLoDiconoEIlTastoPortaAllaPagina() {
    show(LibraryBackupStatus.Problem(code = "incoming_failed", count = 2))
    composeRule.onNodeWithText("2 file ricevuti non salvati").assertExists().performClick()
    composeRule.onNodeWithText("Riprova o scartali da Backup.").assertExists()
    composeRule.onNodeWithText("Apri Backup").performClick()
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
    composeRule.onNodeWithText("Problema con il backup").assertExists()
    composeRule.onNodeWithText("Apri Backup").performClick()
    assertThat(backupClicks).isEqualTo(1)
  }

  /** La rete con un problema: la sua frase, divisa al punto, e il tasto con la sua azione (WireGuard). */
  @Test
  fun laReteConUnProblemaHaLaSuaAzione() {
    val network = LibraryNetworkStatus(
      severity = LibraryNetworkSeverity.Attention,
      label = "VPN spenta",
      detail = "Apri WireGuard",
      action = LibraryNetworkAction.WIREGUARD,
    )
    show(LibraryBackupStatus.AllSafe(null), network = network)
    composeRule.onNodeWithText("VPN spenta").assertExists()
    composeRule.onNode(hasText("Apri WireGuard") and hasClickAction()).performClick()
    assertThat(networkClicks).isEqualTo(1)
  }

  /** Senza problemi: lo spazio usato sul totale (letto all'apertura), l'ultima sincronizzazione e "Libera spazio" su tutto il sicuro. */
  @Test
  fun senzaProblemiLoSpazioLUltimaSincronizzazioneELiberaSpazio() {
    show(LibraryBackupStatus.AllSafe(null), nas = LibraryNasStatus.UpToDate(atNasMillis = 0L), lastSync = System.currentTimeMillis())
    // I4: niente titoli a schermo, l'anello e il numero; TalkBack dice la frase intera.
    composeRule.onNodeWithContentDescription("Spazio sul NAS: 1,2 TB usati di 3,6 TB").assertExists()
    composeRule.onNodeWithText("1,2 TB", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("di 3,6 TB", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithContentDescription("Ultima sincronizzazione: Oggi alle", substring = true).assertExists()
    assertThat(composeRule.onAllNodesWithText("Spazio sul NAS").fetchSemanticsNodes()).isEmpty()
    assertThat(composeRule.onAllNodesWithText("Ultima sincronizzazione").fetchSemanticsNodes()).isEmpty()
    composeRule.onNodeWithText("Libera spazio").performClick()
    assertThat(commands.freed).containsExactly(emptySet<MediaId>())
    assertThat(closes).isEqualTo(1)
  }

  @Test
  fun loSpazioCheNonSiLeggeLoDice() {
    commands.space = LibraryNasSpace.Unavailable
    show(LibraryBackupStatus.AllSafe(null))
    composeRule.onNodeWithContentDescription("Lo spazio non si legge adesso.").assertExists()
  }

  @Test
  fun senzaNasLoSpazioNonCe() {
    commands.space = null
    show(LibraryBackupStatus.AllSafe(null))
    assertThat(composeRule.onAllNodesWithTag(SpaceRowTag).fetchSemanticsNodes()).isEmpty()
  }

  /** Con un problema il pop-up dice il problema e basta: niente spazio, niente "Libera spazio" (la richiesta dell'utente). */
  @Test
  fun conUnProblemaNienteSpazioNeLiberaSpazio() {
    show(LibraryBackupStatus.Problem(code = "nas_full", count = 0), lastSync = System.currentTimeMillis())
    composeRule.onNodeWithText("Il NAS è pieno").assertExists()
    assertThat(composeRule.onAllNodesWithTag(SpaceRowTag).fetchSemanticsNodes()).isEmpty()
    assertThat(composeRule.onAllNodesWithText("Libera spazio").fetchSemanticsNodes()).isEmpty()
    assertThat(composeRule.onAllNodesWithContentDescription("Ultima sincronizzazione", substring = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun tuttiGliStatiConvivono() {
    show(
      backup = LibraryBackupStatus.Queued(items = 312, wait = LibraryBackupWait.CHARGING),
      nas = LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L),
      network = LibraryNetworkStatus(severity = LibraryNetworkSeverity.Attention, label = "Rete non riconosciuta"),
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

  // --- I bordi del pop-up -----------------------------------------------------------------------------------------------------

  private fun clickable(): List<SemanticsNode> =
    composeRule.onAllNodes(SemanticsMatcher("toccabile") { it.config.contains(SemanticsActions.OnClick) }).fetchSemanticsNodes()

  private fun Rect.overlaps(other: Rect): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom

  /** Le storie piu' lunghe e piu' corte: l'etichetta piu' lunga, l'avanzamento, il problema con il numero. */
  private val boundaryCases: List<Pair<String, LibraryBackupStatus>> = listOf(
    "tutto al sicuro" to LibraryBackupStatus.AllSafe(null),
    "in coda" to LibraryBackupStatus.Queued(items = 6_658, wait = LibraryBackupWait.HOME_WIFI),
    "primo backup" to LibraryBackupStatus.FirstBackup(done = 1_240, total = 6_658, etaMillis = 80 * 60_000L),
    "ricerca" to LibraryBackupStatus.Comparing(seen = 1_234, total = 5_559),
    "problema lungo" to LibraryBackupStatus.Problem(code = "phone_storage_low", count = 0),
    "problema con il numero" to LibraryBackupStatus.Problem(code = "verify_failed", count = 12_345),
    "in prova" to LibraryBackupStatus.TestOnly,
  )

  private fun assertBounds(where: String) {
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val root = composeRule.onAllNodes(SemanticsMatcher("radice") { it.isRoot }).fetchSemanticsNodes().first().boundsInRoot
    val buttons = clickable()
    assertWithMessage("$where: i bersagli del pop-up").that(buttons.size).isAtLeast(1)
    for (button in buttons) {
      val b = button.boundsInRoot
      assertWithMessage("$where: bersaglio $b alto almeno 48 dp").that(b.height).isAtLeast(min - 1f)
      assertWithMessage("$where: bersaglio $b largo almeno 48 dp").that(b.width).isAtLeast(min - 1f)
      assertWithMessage("$where: bersaglio $b dentro lo schermo ($root)").that(b.left >= root.left - 1f && b.right <= root.right + 1f).isTrue()
    }
    val ordered = buttons.sortedBy { it.boundsInRoot.top }
    for (i in 0 until ordered.size - 1) {
      assertWithMessage("$where: ${ordered[i].boundsInRoot} e ${ordered[i + 1].boundsInRoot}")
        .that(ordered[i].boundsInRoot.overlaps(ordered[i + 1].boundsInRoot)).isFalse()
    }
  }

  @Test
  fun iBordiDelPopUpSuTelefonoAUnoEATreDecimi() {
    for (fontScale in listOf(1f, 1.3f)) {
      for ((name, status) in boundaryCases) {
        show(status, nas = LibraryNasStatus.Frozen(FreezeReason.CHANGED, sinceNasMillis = 0L), fontScale = fontScale)
        assertBounds("telefono ${fontScale}x, $name, con i lavori fermi")
        show(status, nas = LibraryNasStatus.UpToDate(atNasMillis = 0L), fontScale = fontScale, lastSync = 0L)
        assertBounds("telefono ${fontScale}x, $name, senza problemi del NAS")
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
  fun iBordiDelPopUpSulTablet() {
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val cases = listOf<LibraryBackupStatus>(
      LibraryBackupStatus.FirstBackup(done = 1, total = 6_658, etaMillis = null),
      LibraryBackupStatus.Problem(code = "frozen", count = 0),
    )
    val backup = mutableStateOf(cases.first())
    composeRule.showThemed {
      val cloud = libraryCloud(null, LibraryNasStatus.Frozen(FreezeReason.CHANGED, sinceNasMillis = 0L), backup.value)
      if (cloud != null) {
        LibraryCloudPanel(cloud = cloud, lastSyncNasMillis = null, commands = NoLibraryCommands, actions = LibraryCloudActions({}, {}, {}), close = {})
      }
    }
    for (case in cases) {
      composeRule.runOnIdle { backup.value = case }
      composeRule.waitForIdle()
      val buttons = composeRule.onAllNodes(SemanticsMatcher("toccabile") { it.config.contains(SemanticsActions.OnClick) }).fetchSemanticsNodes()
      assertThat(buttons.size).isAtLeast(1)
      for (button in buttons) {
        assertWithMessage("bersaglio ${button.boundsInRoot}").that(button.boundsInRoot.height).isAtLeast(min - 1f)
      }
    }
  }
}
