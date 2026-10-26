package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * "Modifica Raccolte" (Fase 7, P11, D33, §2.7): la scheda con le sezioni dei fornitori finti e un controllore vero sopra un deposito in
 * memoria. Si entra con il tasto in alto, ogni sezione ha un interruttore "Mostra" (Album no), si riordina trascinando dalla maniglia o
 * con le azioni di TalkBack "Sposta su" e "Sposta giu'", i bersagli sono da 48 dp.
 */
abstract class CollectionsEditScreenBase : ComposeScreenTest() {

  protected val world = FakeCollectionsWorld()
  protected val store = MemoryCollectionsLayoutStore()
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
  protected val opened = mutableListOf<String>()

  protected val controller = CollectionsLayoutController(
    world.source, true, CollectionsSectionSamples.all(), store, scope, SharingStarted.Eagerly,
  )

  @After
  fun stop() {
    scope.cancel()
  }

  @Composable
  protected fun Host(twoPane: Boolean = false) {
    val inputs by controller.inputs.collectAsState()
    val state = remember(inputs) {
      CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed)
    }
    CollectionsScreen(
      state = state,
      contentPadding = PaddingValues(),
      backdropOut = {},
      onOpenCollection = { opened += it.code },
      onOpenSettings = { opened += "settings" },
      statusHeader = {},
      detail = { kind -> Text("dettaglio ${kind.code}") },
      onOpenEntry = { opened += it.code },
      onEdit = controller::startEditing,
      onDone = controller::stopEditing,
      onReorder = controller::reorder,
      onSetShown = controller::setShown,
    )
  }

  protected fun edit() {
    composeRule.onNodeWithContentDescription("Modifica Raccolte").performClick()
    composeRule.waitForIdle()
  }

  protected fun codes(): List<String> = controller.inputs.value.let {
    CollectionsLayout.state(it.present, emptyList(), it.saved, it.hidden, it.editing, it.saveFailed).sections.map { s -> s.code }
  }

  /**
   * La riga di una sezione com'e' per TalkBack: il nodo che ha le azioni (interruttore, "Sposta su" e "Sposta giu'"), il cui figlio porta il
   * testo ("Viaggi, Mostrata"). Un nodo che unisce i suoi figli non si fonde in un altro che lo fa: sono due nodi, uno dentro l'altro.
   */
  protected fun row(title: String) =
    composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasAnyChild(hasText(title)))

  /** Le azioni di accessibilita' della riga di una sezione, per etichetta. */
  protected fun customActions(title: String): List<String> =
    row(title).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)?.map { it.label }.orEmpty()

  protected fun perform(title: String, label: String) {
    val node = row(title).fetchSemanticsNode()
    val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
    composeRule.runOnUiThread { action.action() }
    composeRule.waitForIdle()
  }
}

class CollectionsEditScreenPhoneTest : CollectionsEditScreenBase() {

  @Test
  fun ilTastoInAltoEntraNellaModificaEFineEsce() {
    show { Host() }
    composeRule.onNodeWithContentDescription("Impostazioni").assertExists()

    edit()

    // Il titolo c'e' due volte (grande e agganciato alla barra).
    assertThat(composeRule.onAllNodesWithText("Modifica Raccolte").fetchSemanticsNodes()).isNotEmpty()
    composeRule.onNodeWithContentDescription("Fine").assertExists()
    // Un solo gruppo di tasti per volta: in modifica non c'e' ne' il tasto "Modifica" ne' le Impostazioni.
    composeRule.onAllNodesWithContentDescription("Impostazioni").assertCountEquals(0)
    composeRule.onAllNodesWithContentDescription("Modifica Raccolte").assertCountEquals(0)
    assertThat(controller.inputs.value.editing).isTrue()

    composeRule.onNodeWithContentDescription("Fine").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Impostazioni").assertExists()
    assertThat(controller.inputs.value.editing).isFalse()
  }

  @Test
  fun inModificaCiSonoTutteLeSezioniNelLoroOrdineConLoStatoDiOgnuna() {
    show { Host() }
    edit()

    for (title in listOf("Album", "Album smart", "Viaggi", "Persone", "Cartelle NAS", "Telefono", "Da tenere al sicuro", "Utility", "Nascosti")) {
      composeRule.onNodeWithText(title).assertExists()
    }
    // Una sezione mostrata si vede dall'interruttore: niente frase sotto il nome. Album dice perche' non ha l'interruttore.
    composeRule.onNodeWithText("Sempre visibile").assertExists()
    composeRule.onAllNodesWithText("Mostrata", substring = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Nascosta", substring = true).assertCountEquals(0)
    // Nascosta, dice dove si ritrova (Nascosti resta in Impostazioni → Privacy).
    row("Nascosti").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Nascosta · Si apre da Impostazioni → Privacy").assertExists()
    assertThat(store.hidden.value).containsExactly("hidden")
  }

  @Test
  fun lInterruttoreMostraENascondeEUnaSezioneNascostaRestaInLista() {
    show { Host() }
    edit()

    row("Viaggi").assertIsOn()
    row("Viaggi").performClick()
    composeRule.waitForIdle()

    row("Viaggi").assertIsOff()
    assertThat(store.hidden.value).containsExactly("trips")
    composeRule.onNodeWithText("Nascosta").assertExists()

    composeRule.onNodeWithContentDescription("Fine").performClick()
    composeRule.waitForIdle()
    // Fuori dalla modifica la sezione non c'e' piu'.
    composeRule.onAllNodesWithText("Toscana, agosto 2025").assertCountEquals(0)

    edit()
    composeRule.onNodeWithText("Viaggi").performClick()
    composeRule.waitForIdle()
    row("Viaggi").assertIsOn()
    assertThat(store.hidden.value).isEmpty()
  }

  @Test
  fun albumNonHaLInterruttoreENonSiNasconde() {
    show { Host() }
    edit()

    val album = row("Album").fetchSemanticsNode()
    assertThat(album.config.contains(SemanticsProperties.Role)).isFalse()
    assertThat(album.config.contains(SemanticsProperties.ToggleableState)).isFalse()
    composeRule.onNodeWithText("Album").performClick()
    composeRule.waitForIdle()
    assertThat(store.hidden.value).isEmpty()
    composeRule.onNodeWithText("Sempre visibile").assertExists()
  }

  @Test
  fun lAzioneDiTalkBackSpostaSuESpostaGiu_EleImpossibiliNonCiSono() {
    show { Host() }
    edit()

    // La prima riga non puo' salire, l'ultima non puo' scendere: l'azione non si offre.
    assertThat(customActions("Album")).containsExactly("Sposta giù")
    assertThat(customActions("Nascosti")).containsExactly("Sposta su")
    assertThat(customActions("Viaggi")).containsExactly("Sposta su", "Sposta giù").inOrder()

    perform("Viaggi", "Sposta su")

    assertThat(codes().take(3)).containsExactly("albums", "trips", "smart").inOrder()
    assertThat(store.order.value.take(3)).containsExactly("albums", "trips", "smart").inOrder()
    assertThat(customActions("Viaggi")).containsExactly("Sposta su", "Sposta giù").inOrder()

    perform("Viaggi", "Sposta su")
    assertThat(codes().take(2)).containsExactly("trips", "albums").inOrder()
    assertThat(customActions("Viaggi")).containsExactly("Sposta giù")
    perform("Viaggi", "Sposta giù")
    perform("Viaggi", "Sposta giù")
    assertThat(codes().take(4)).containsExactly("albums", "smart", "trips", "people").inOrder()
  }

  @Test
  fun ogniRigaDiceLaPosizioneEIlSuoStato() {
    show { Host() }
    edit()

    val node = row("Viaggi").fetchSemanticsNode()
    assertThat(node.config[SemanticsProperties.StateDescription]).isEqualTo("Mostrata, posizione 3 di 9")
    assertThat(node.config[SemanticsProperties.Role].toString()).isEqualTo("Switch")
  }

  @Test
  fun trascinareDallaManigliaCambiaLOrdineESiSalva() {
    show { Host() }
    edit()
    val rowHeight = composeRule.onNodeWithText("Viaggi").fetchSemanticsNode().size.height.toFloat()

    composeRule.onNodeWithTag(handleTag("trips"), useUnmergedTree = true).performTouchInput {
      down(center)
      repeat(12) { moveBy(Offset(0f, -rowHeight * 1.3f / 12)) }
      up()
    }
    composeRule.waitForIdle()

    assertThat(codes().take(3)).containsExactly("albums", "trips", "smart").inOrder()
    assertThat(store.order.value.take(3)).containsExactly("albums", "trips", "smart").inOrder()
  }

  @Test
  fun unTocco_SullaManigliaNonCambiaLaVisibilita() {
    show { Host() }
    edit()

    composeRule.onNodeWithTag(handleTag("trips"), useUnmergedTree = true).performClick()
    composeRule.waitForIdle()

    assertThat(store.hidden.value).isEmpty()
  }

  @Test
  fun iBersagliSonoDa48dp() {
    show { Host() }
    edit()

    val dp48 = with(androidx.compose.ui.unit.Density(2f)) { 48.dp.toPx() }
    val handle = composeRule.onNodeWithTag(handleTag("trips"), useUnmergedTree = true).fetchSemanticsNode()
    assertThat(handle.size.width.toFloat()).isAtLeast(dp48)
    assertThat(handle.size.height.toFloat()).isAtLeast(dp48)
    for (title in listOf("Album", "Viaggi", "Nascosti")) {
      assertThat(composeRule.onNodeWithText(title).fetchSemanticsNode().size.height.toFloat()).isAtLeast(dp48)
    }
    // E il tasto "Fine" della barra.
    val done = composeRule.onNodeWithContentDescription("Fine").fetchSemanticsNode()
    assertThat(done.size.width.toFloat()).isAtLeast(dp48)
  }

  @Test
  fun unaScelta_FattaSuUnAltroDispositivoSiVedeSubito() {
    show { Host() }
    edit()
    composeRule.runOnUiThread {
      kotlinx.coroutines.runBlocking {
        store.setHidden("smart", true)
        store.setOrder(listOf("phone", "albums"))
      }
    }
    composeRule.waitForIdle()

    row("Album smart").assertIsOff()
    assertThat(codes().take(2)).containsExactly("phone", "albums").inOrder()
  }

  @Test
  fun unSalvataggioCheFallisceLoDiceInAlto() {
    val failing = object : CollectionsLayoutStore by store {
      override suspend fun setHidden(code: String, hidden: Boolean) = throw IllegalStateException("disco pieno")
    }
    val failingController = CollectionsLayoutController(
      world.source, true, CollectionsSectionSamples.all(), failing, CoroutineScope(Dispatchers.Unconfined + Job()), SharingStarted.Eagerly,
    )
    show {
      val inputs by failingController.inputs.collectAsState()
      val state = remember(inputs) {
        CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed)
      }
      CollectionsScreen(
        state, PaddingValues(), {}, {}, {}, {}, {},
        onEdit = failingController::startEditing, onDone = failingController::stopEditing,
        onReorder = failingController::reorder, onSetShown = failingController::setShown,
      )
    }
    composeRule.onNodeWithContentDescription("Modifica Raccolte").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Viaggi").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Non sono riuscito a salvare la tua scelta. Riprova fra poco.").assertExists()
    row("Viaggi").assertIsOn()
  }

  @Test
  fun senzaNessunaSezioneDaMostrareLaSchedaLoDiceEPortaAllaModifica() {
    val hidden = MemoryCollectionsLayoutStore(hidden = setOf("phone", "safe", "hidden"))
    val c = CollectionsLayoutController(world.source, true, emptyList(), hidden, CoroutineScope(Dispatchers.Unconfined + Job()), SharingStarted.Eagerly)
    show {
      val inputs by c.inputs.collectAsState()
      val state = remember(inputs) { CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed) }
      CollectionsScreen(state, PaddingValues(), {}, {}, {}, {}, {}, onEdit = c::startEditing, onDone = c::stopEditing)
    }

    composeRule.onNodeWithText("Nessuna sezione da mostrare").assertExists()
    composeRule.onNodeWithText("Modifica Raccolte").performClick()
    composeRule.waitForIdle()
    assertThat(c.inputs.value.editing).isTrue()
    row("Telefono").assertIsOff()
  }

  @Test
  fun unOrdineSalvatoSiVedeNellaSchedaEUnaSezioneNuovaStaInCoda() {
    store.let { s ->
      kotlinx.coroutines.runBlocking {
        s.setOrder(listOf("hidden", "phone", "safe", "albums", "smart", "trips", "nas", "utility"))
      }
    }
    show { Host() }

    // "Persone" non e' nell'ordine salvato (arriva con la Fase 8): e' l'ultima.
    assertThat(codes()).containsExactly("hidden", "phone", "safe", "albums", "smart", "trips", "nas", "utility", "people").inOrder()
    composeRule.onNodeWithText("Nascosti").assertExists()
    composeRule.onNodeWithText("Preferiti").assertExists()
  }
}

/** Sul tablet l'elenco delle sezioni ha il dettaglio accanto, nello stesso ordine del telefono; la modifica prende il posto dell'elenco. */
@Config(qualifiers = TABLET_QUALIFIERS)
class CollectionsEditScreenTabletTest : CollectionsEditScreenBase() {

  @Test
  fun suDuePannelliL_ElencoStaAccantoAlDettaglioENelloStessoOrdine() {
    show { Host() }

    composeRule.onNodeWithText("dettaglio favorites").assertExists()
    // Le due colonne: l'elenco a sinistra, il dettaglio a destra; le sezioni nello stesso ordine del telefono.
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Preferiti"))
    assertThat(codes()).isEqualTo(CollectionSections.defaultOrder.filter { it != "memories" })
    val list = composeRule.onNodeWithText("Preferiti").fetchSemanticsNode().boundsInRoot
    val detail = composeRule.onNodeWithText("dettaglio favorites").fetchSemanticsNode().boundsInRoot
    assertThat(detail.left).isAtLeast(list.right)
  }

  @Test
  fun laModificaPrendeIlPostoDellElencoEIlDettaglioRestaDovEra() {
    show { Host() }

    edit()

    row("Viaggi").assertIsOn()
    composeRule.onNodeWithText("dettaglio favorites").assertExists()
    composeRule.onNodeWithText("Viaggi").performClick()
    composeRule.waitForIdle()
    row("Viaggi").assertIsOff()
    composeRule.onNodeWithContentDescription("Fine").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("dettaglio favorites").assertExists()
  }

  @Test
  fun nascondereTelefonoDaTabletNonLasciaUnDettaglioVuoto() {
    show { Host() }
    edit()
    composeRule.onNodeWithText("Telefono").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Fine").performClick()
    composeRule.waitForIdle()

    // La prima raccolta-filtro che si vede non c'e' piu': il pannello dice di sceglierne una, non resta sul dettaglio di Preferiti.
    composeRule.onAllNodesWithText("dettaglio favorites").assertCountEquals(0)
    composeRule.onNodeWithText("Scegli una raccolta").assertExists()
  }

  @Test
  fun unaVoceDelFornitoreSiApreNelDettaglioSeAppDaLaPagina() {
    val picked = mutableListOf<String>()
    show {
      val inputs by controller.inputs.collectAsState()
      val state = remember(inputs) { CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, false, false) }
      CollectionsScreen(
        state, PaddingValues(), {}, {}, {}, {}, { Text("dettaglio ${it.code}") },
        onOpenEntry = { picked += it.code },
        entryDetail = { entry -> Text("pagina ${entry.code}") },
      )
    }

    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Oslo 2026"))
    composeRule.onNodeWithText("Oslo 2026").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("pagina smart/s1").assertExists()
    assertThat(picked).isEmpty()
  }
}
