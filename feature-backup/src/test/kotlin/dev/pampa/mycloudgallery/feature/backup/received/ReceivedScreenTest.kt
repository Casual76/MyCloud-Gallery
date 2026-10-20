package dev.pampa.mycloudgallery.feature.backup.received

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTest
import dev.pampa.mycloudgallery.feature.backup.LARGE_TEXT
import dev.pampa.mycloudgallery.feature.backup.testing.ReceivedSamples
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test

/**
 * "Ricevuti in coda" (correzioni 5B, IN-7): ogni stato ha la sua frase, ogni file la sua cartella, la sua miniatura e i tasti che hanno senso. Il telefono
 * da 412 dp, una pagina alta (la lista scorre e una riga mezza fuori non e' un controllo storto).
 */
class ReceivedScreenTest : ComposeBackupTallTest() {

  private val retried = ArrayList<ReceivedItem>()
  private val asked = ArrayList<ReceivedItem>()
  private var confirmed = 0
  private var cancelled = 0

  private fun actions(thumbnail: suspend (ReceivedItem, Int) -> ImageBitmap? = { _, _ -> null }) = ReceivedActions(
    retry = { retried += it },
    askDiscard = { asked += it },
    confirmDiscard = { confirmed++ },
    cancelDiscard = { cancelled++ },
    thumbnail = thumbnail,
  )

  private fun showList(
    items: List<ReceivedItem>,
    discarding: ReceivedItem? = null,
    notice: ReceivedNotice? = null,
    fontScale: Float? = null,
    thumbnail: suspend (ReceivedItem, Int) -> ImageBitmap? = { _, _ -> null },
  ) {
    show(fontScale = fontScale) {
      ReceivedScreen(state = ReceivedUi(items, discarding, notice), actions = actions(thumbnail), onBack = {})
    }
  }

  private fun Rect.contains(other: Rect): Boolean = left <= other.left + 1f && top <= other.top + 1f && right >= other.right - 1f && bottom >= other.bottom - 1f

  @Test
  fun senzaFileDiceCheNonCeNiente() {
    showList(emptyList())
    composeRule.onNodeWithText("Non c’è niente in coda").assertExists()
    composeRule.onNodeWithText("I file che un’altra app ti manda", substring = true).assertExists()
  }

  @Test
  fun ogniStatoHaLaSuaFraseChiara() {
    val expected = mapOf(
      ReceivedStatus.QUEUED to "In coda: parte appena possibile.",
      ReceivedStatus.NETWORK to "In attesa di rete: parte quando il NAS risponde.",
      ReceivedStatus.TRIAL to "Backup in prova: in questa versione non si scrive ancora sul NAS.",
      ReceivedStatus.FOLDER_LOCKED to "Cartella bloccata: adesso non ci si può scrivere.",
      ReceivedStatus.NAS_FULL to "NAS pieno: libera spazio e poi riprova.",
      ReceivedStatus.QUOTA to "Quota esaurita: chiedi all’admin del NAS.",
      ReceivedStatus.PASSWORD to "Serve la password del NAS.",
      ReceivedStatus.FROZEN to "Scritture ferme per cambiamenti anomali: controlla la Diagnostica.",
      ReceivedStatus.PAUSED to "Backup in pausa: riprende con “Riprendi”.",
      ReceivedStatus.UNAVAILABLE to "L’app non è pronta a scrivere sul NAS.",
      ReceivedStatus.UPLOADING to "Lo sto caricando sul NAS.",
      ReceivedStatus.VERIFYING to "Controllo che sia arrivato intero.",
      ReceivedStatus.COPY_CHANGED to "La copia sul telefono non si legge o è cambiata.",
      ReceivedStatus.NOT_INTACT to "Sul NAS non è arrivato intero, dopo tre controlli.",
      ReceivedStatus.FOLDER_REFUSED to "La cartella non può ricevere il file.",
      ReceivedStatus.COPY_MISSING to "La copia sul telefono non c’è più: non si può salvare.",
      ReceivedStatus.FAILED to "Qualcosa è andato storto.",
    )
    // Il test esige una frase per ogni stato del contratto.
    assertThat(expected.keys).containsExactlyElementsIn(ReceivedStatus.entries)
    val state = mutableStateOf(ReceivedUi(listOf(ReceivedSamples.item(1, ReceivedStatus.QUEUED))))
    show { ReceivedScreen(state = state.value, actions = actions(), onBack = {}) }
    for ((status, text) in expected) {
      composeRule.runOnIdle { state.value = ReceivedUi(listOf(ReceivedSamples.item(1, status))) }
      composeRule.waitForIdle()
      composeRule.onNodeWithText(text, substring = true).assertExists()
    }
  }

  @Test
  fun ognifileDiceLaCartellaIlTipoEIlPeso() {
    showList(
      listOf(
        ReceivedSamples.item(1, ReceivedStatus.NETWORK, folder = "Viaggi/Estate", size = 3_200_000L),
        ReceivedSamples.item(2, ReceivedStatus.NETWORK, folder = "", video = true, size = 612_000_000L),
      ),
    )
    composeRule.onNodeWithText("Nella cartella Viaggi/Estate").assertExists()
    composeRule.onNodeWithText("Foto · 3,2 MB", substring = true).assertExists()
    composeRule.onNodeWithText("Nella cartella principale").assertExists()
    composeRule.onNodeWithText("Video · 612 MB", substring = true).assertExists()
    composeRule.onNodeWithText("2 file ricevuti non sono ancora sul NAS.").assertExists()
  }

  @Test
  fun riprovaEScartaChiamanoLaPortaPerIlFileGiusto() {
    val items = ReceivedSamples.few
    showList(items)
    val retry = composeRule.onAllNodes(hasText("Riprova") and hasClickAction())
    val discard = composeRule.onAllNodes(hasText("Scarta") and hasClickAction())
    // Il secondo file sale: non ha tasti. Restano due "Riprova" e due "Scarta".
    assertThat(retry.fetchSemanticsNodes()).hasSize(2)
    assertThat(discard.fetchSemanticsNodes()).hasSize(2)
    retry[1].performClick()
    discard[0].performClick()
    assertThat(retried.map { it.id }).containsExactly(items[2].id)
    assertThat(asked.map { it.id }).containsExactly(items[0].id)
  }

  @Test
  fun unFileCheSaleNonHaTasti() {
    showList(listOf(ReceivedSamples.item(1, ReceivedStatus.UPLOADING, video = true)))
    assertThat(composeRule.onAllNodes(hasText("Riprova") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
    assertThat(composeRule.onAllNodes(hasText("Scarta") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
    composeRule.onNodeWithText("Lo sto caricando sul NAS.", substring = true).assertExists()
  }

  @Test
  fun senzaLaCopiaSulTelefonoSiScartaSoltanto() {
    showList(listOf(ReceivedSamples.item(1, ReceivedStatus.COPY_MISSING)))
    assertThat(composeRule.onAllNodes(hasText("Riprova") and hasClickAction()).fetchSemanticsNodes()).isEmpty()
    composeRule.onNode(hasText("Scarta") and hasClickAction()).assertExists()
  }

  @Test
  fun iTastiDicono_PerTalkBack_DiQualeCartellaParlano() {
    showList(
      listOf(
        ReceivedSamples.item(1, ReceivedStatus.NETWORK, folder = "Ricevute"),
        ReceivedSamples.item(2, ReceivedStatus.COPY_CHANGED, folder = "Viaggi"),
      ),
    )
    composeRule.onNode(hasContentDescription("Riprova: Nella cartella Ricevute")).assertExists()
    composeRule.onNode(hasContentDescription("Scarta: Nella cartella Viaggi")).assertExists()
  }

  @Test
  fun scartaApreLaConfermaEDiceCheIlNasNonCambia() {
    val item = ReceivedSamples.item(1, ReceivedStatus.COPY_CHANGED)
    showList(listOf(item), discarding = item)
    composeRule.onNodeWithText("Scartare questo file?").assertExists()
    composeRule.onNodeWithText("Sul NAS non cambia niente", substring = true).assertExists()
    composeRule.onNode(hasText("Scarta") and hasClickAction() and SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)).performClick()
    assertThat(confirmed).isEqualTo(1)
    composeRule.onNode(hasText("Annulla") and hasClickAction()).performClick()
    assertThat(cancelled).isEqualTo(1)
    assertThat(asked).isEmpty()
  }

  @Test
  fun senzaConfermaApertaIlFoglioNonCe() {
    showList(listOf(ReceivedSamples.item(1, ReceivedStatus.COPY_CHANGED)))
    assertThat(composeRule.onAllNodesWithText("Scartare questo file?").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun dopoUnTaccoUnaFraseDiceComeEAndata() {
    val item = ReceivedSamples.item(1, ReceivedStatus.COPY_CHANGED)
    val texts = mapOf(
      ReceivedNotice.Requeued to "Rimesso in coda: parte appena possibile.",
      ReceivedNotice.Blocked(ReceivedStatus.TRIAL) to "Non riprovo adesso. Backup in prova",
      ReceivedNotice.NoCopy to "La copia sul telefono non c’è più: non si può riprovare. Puoi scartarlo.",
      ReceivedNotice.AlreadySaved to "È già sul NAS, o sta per arrivarci",
      ReceivedNotice.Discarded to "Scartato. Sul NAS non è cambiato niente.",
      ReceivedNotice.Uploading to "Lo sto caricando adesso: aspetta che finisca.",
      ReceivedNotice.Unknown to "Questo file non è più in coda.",
    )
    val state = mutableStateOf(ReceivedUi(listOf(item), notice = ReceivedNotice.Requeued))
    show { ReceivedScreen(state = state.value, actions = actions(), onBack = {}) }
    for ((notice, text) in texts) {
      composeRule.runOnIdle { state.value = ReceivedUi(listOf(item), notice = notice) }
      composeRule.waitForIdle()
      composeRule.onNodeWithText(text, substring = true).assertExists()
    }
  }

  @Test
  fun laMiniaturaArrivaDallaCopiaLocaleELaPortaLaChiedeUnaVoltaPerFile() {
    val requests = ArrayList<Pair<ReceivedId, Int>>()
    val gate = CompletableDeferred<ImageBitmap?>()
    showList(ReceivedSamples.few) { item, px ->
      requests += item.id to px
      gate.await()
    }
    composeRule.waitForIdle()
    // Una richiesta per file, alla taglia della miniatura (64 dp a xhdpi = 128 px).
    assertThat(requests.map { it.first }).containsExactlyElementsIn(ReceivedSamples.few.map { it.id })
    assertThat(requests.map { it.second }.toSet()).containsExactly(128)
    gate.complete(null)
    composeRule.waitForIdle()
    assertThat(composeRule.onAllNodesWithText("Nella cartella Ricevute", substring = true).fetchSemanticsNodes()).hasSize(2)
  }

  @Test
  fun laMiniaturaFinisceNellaSchedaQuandoLaPortaLaDa() {
    val bitmap = ImageBitmap(8, 8)
    showList(listOf(ReceivedSamples.item(1, ReceivedStatus.NETWORK))) { _, _ -> bitmap }
    composeRule.waitForIdle()
    // La scheda compone con la miniatura al posto del segnaposto (nessun errore) e i tasti restano.
    composeRule.onNode(hasText("Riprova") and hasClickAction()).assertExists()
  }

  // --- I bordi: 48 dp e niente sovrapposizioni, a 1x e a 1,3x -----------------------------------------------------------------------

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

  @Test
  fun iBersagliSonoDa48dpEDistintiAUnoEATreDecimi() {
    val state = mutableStateOf(ReceivedUi(ReceivedSamples.everyStatus))
    show { ReceivedScreen(state = state.value, actions = actions(), onBack = {}) }
    for (fontScale in listOf(1f, LARGE_TEXT)) {
      setFontScale(fontScale)
      assertBounds("elenco ${fontScale}x, un file per stato")
    }
    setFontScale(LARGE_TEXT)
    composeRule.runOnIdle { state.value = ReceivedUi(ReceivedSamples.few, discarding = ReceivedSamples.few.first(), notice = ReceivedNotice.Requeued) }
    composeRule.waitForIdle()
    assertBounds("elenco 1,3x con la conferma e la frase")
  }

  @Test
  fun ogniTastoHaUnNomeCheDiceCosaFa() {
    showList(ReceivedSamples.everyStatus)
    val names = clickable().map { it.name() }
    assertThat(names).isNotEmpty()
    for (name in names) assertThat(name).isNotEmpty()
    assertThat(names.filter { it.startsWith("Riprova: ") }).isNotEmpty()
    assertThat(names.filter { it.startsWith("Scarta: ") }).isNotEmpty()
    // Nessun tasto si chiama solo "Riprova" o "Scarta": nell'elenco sono tanti e uguali, senza la cartella non si capisce quale.
    assertThat(names).doesNotContain("Riprova")
    assertThat(names).doesNotContain("Scarta")
  }

  @Test
  fun laSchedaDiOgniFileEUnaFraseSolaPerTalkBack() {
    showList(listOf(ReceivedSamples.item(1, ReceivedStatus.NETWORK, folder = "Ricevute")))
    val merged = composeRule.onAllNodes(
      SemanticsMatcher("scheda unita") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty().contains("Nella cartella Ricevute")
      },
    ).fetchSemanticsNodes()
    assertThat(merged).isNotEmpty()
    val text = merged.first().config.getOrNull(SemanticsProperties.Text)!!.joinToString(" ") { it.text }
    assertThat(text).contains("In attesa di rete")
  }
}

/** Lo stesso elenco sul tablet (1280 dp, orizzontale): resta nella colonna leggibile e i tasti non si allargano a tutto lo schermo. */
class ReceivedTabletLayoutTest : dev.pampa.mycloudgallery.feature.backup.ComposeBackupTallTabletTest() {

  @Test
  fun lElencoSulTabletRestaNellaColonnaLeggibile() {
    show {
      ReceivedScreen(state = ReceivedUi(ReceivedSamples.few), actions = ReceivedActions(), onBack = {})
    }
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val maxWidth = with(composeRule.density) { (640 + 2 * 16).dp.toPx() }
    val nodes = clickable()
    assertThat(nodes).isNotEmpty()
    for (node in nodes) {
      assertThat(node.boundsInRoot.height).isAtLeast(min - 1f)
      assertThat(node.boundsInRoot.width).isAtMost(maxWidth)
    }
    val root = composeRule.onAllNodes(SemanticsMatcher("radice") { it.isRoot }).fetchSemanticsNodes().first().boundsInRoot.width
    val retry = nodes.first { it.name().startsWith("Riprova: ") }
    assertThat(retry.boundsInRoot.width).isLessThan(root * 0.4f)
  }
}
